# pointer-analysis-java

A local, file-based pointer and may-alias analysis service. It builds program-level
points-to relations from a batch of source files, answers points-to and possible-alias
queries, supports file-level incremental updates with full-rebuild equivalence checking,
runs queries concurrently against immutable published snapshots, persists state for crash
recovery, can analyze in parallel with a configurable degree of parallelism, and produces
deterministic, reproducible results.

## Source language

The service analyzes a small, explicit pointer-analysis language (one method per
declaration, name-based method resolution, single type namespace, no classes or
inheritance). Statements are prefixed by their kind so parsing is unambiguous:

```text
file     := { method }
method   := [ "entry" ] "void" IDENT "(" [ IDENT { "," IDENT } ] ")" "{" { stmt } "}"
stmt     := "new"   IDENT "=" "new" IDENT "(" ")" ";"
         | "copy"  IDENT "=" IDENT ";"
         | "load"  IDENT "=" IDENT "." IDENT ";"
         | "store" IDENT "." IDENT "=" IDENT ";"
         | "call"  IDENT "=" IDENT "(" [ IDENT { "," IDENT } ] ")" ";"
         | "return" [ IDENT ] ";"
```

`//` starts a line comment. Example:

```text
entry void main() {
  new a = new Node();
  store a.next = a;
  call b = id(a);
  load c = b.next;
}
void id(p) {
  copy q = p;
  return q;
}
```

If no method is marked `entry`, the lexicographically first method is treated as the entry.
Methods unreachable from entries are not analyzed.

## Analysis semantics

- **Algorithm.** Andersen-style, field-sensitive, inclusion-based (subset-constraint)
  analysis, solved as a monotone least fixpoint. Constraints are:
  `v = new O()`, `v1 = v2`, `v = b.f` (load), `b.f = v` (store).
- **Allocations.** Heap objects are abstract allocation sites identified by
  `file:line@lhs`. Changing the allocated *type name* on an unchanged line does not change
  the site identity; changing the flow (e.g. which variable is passed/returned) does.
- **Context sensitivity.** A context is a bounded call string of call-site identifiers
  `file:line`, depth `k = pointer-analysis.context-depth` (k-CFA-style). Locals and formal
  parameters in different contexts are distinct `CVar`s and answers report the owning
  context. `k = 0` merges all contexts. Recursion and call chains longer than `k` are
  handled by keeping the newest `k` frames.
- **Heap model.** Context-insensitive heap: object fields are keyed by allocation site and
  field name, shared across contexts.
- **Points-to query.** An access path `a.f.g` is resolved starting from the
  context-qualified root variable and unioning the field points-to sets at each step. The
  answer is a sorted, stable list of allocation-site ids.
- **May-alias query.** Two access paths may-alias exactly when their resolved points-to
  sets intersect. Empty results are reported as "not may alias".
- **Context notation.** `[]` is the empty/entry context; `[a.lang:4]` means "reached via
  the call at a.lang:4"; multiple frames are comma separated.

## Incremental updates and full-rebuild equivalence

On an increment, constraints are regenerated for the new program, the previous points-to
state is reused, and the dependency-reachable region of the change is cleared and
re-derived. The affected region includes changed methods and the caller/callee variables
connected through parameter and return copies, so work is confined to the impacted part of
the program.

Every incremental result can be checked against a fresh whole-program analysis with
`POST /api/verify`. The service guarantees the incremental state is equal to the full
rebuild (same variables, fields, and sets); the endpoint reports `consistent: false` with
exact mismatches otherwise. Incremental requests carry an optional `changeId`; an already
applied id is ignored, so replaying an increment after a crash does not double-apply it.

## Concurrency and snapshots

- Analyses and increments are serialized, making the sequence of published revisions
  deterministic for a given request order.
- Queries never block on analysis. They read the `Snapshot` reference captured at entry;
  successors are fully built and durably persisted before the reference is atomically
  swapped, so readers observe only complete old or complete new revisions, never mixed
  intermediate state. Published snapshots are immutable.

## Crash recovery

State lives under `pointer-analysis.storage-dir`:

- `snapshot.json` — latest published revision, both points-to tables, the mirrored source
  contents, and applied change ids. Published atomically via temp-file write + atomic
  rename, so a crash can never leave a half-written current snapshot.
- `queries.jsonl` — append-only, one compact JSON record per published query answer with a
  monotonic sequence number; a torn tail line from a crash is detected and ignored while
  all earlier records survive.

On restart the service loads the latest snapshot and resumes with the revision counter and
change-id journal intact.

## Determinism

All symbol tables, sets, maps and worklists use sorted keys. The least fixpoint of the
constraints is unique, so the final state is identical regardless of worker interleaving;
parallelism only changes scheduling. The query log sequence is determined by query order.
For the same inputs, configuration and operation sequence, the final points-to state and
the query history are byte-for-byte reproducible.

## Configuration

| Property | Default | Meaning |
| --- | --- | --- |
| `pointer-analysis.context-depth` | `1` | Call-string length `k`; `0` is context-insensitive. |
| `pointer-analysis.parallelism` | `1` | Fixpoint worker threads; does not affect results. |
| `pointer-analysis.storage-dir` | `${java.io.tmpdir}/pointer-analysis-java` | Snapshot and query-log directory. |

## HTTP API

- `POST /api/rebuild` — `{ "files": [ { "path": "...", "content": "..." } ] }`, full build.
- `POST /api/increment` — `{ "changeId": "optional-id", "files": [...] }`, file-level update.
- `GET /api/snapshot` — current revision number.
- `POST /api/query/points-to` —
  `{ "method": "get", "context": "[a.lang:4]", "expression": "p" }` (or `p.next`).
- `POST /api/query/alias` —
  `{ "method": "main", "context": "[]", "a": {"base":"x","fields":[]},
     "b": {"base":"y","fields":["next"]} }`.
- `POST /api/verify` — re-analyzes from scratch and compares; returns
  `{ "consistent": true, "mismatches": [] }`.

Run locally:

```bash
mvn spring-boot:run
```

## Known limitations

- The input language is a purpose-built Java-like subset, not Java source: no real classes,
  inheritance, interfaces, virtual dispatch, static fields, arrays, or thread/escape
  modeling. Method resolution is by unique name; duplicate names and arity mismatches are
  rejected at parse/constraint-generation time.
- The heap is context-insensitive (allocation sites are shared across contexts); only the
  local/parameter naming is context-sensitive.
- May-alias is set intersection of Andersen results: it is sound for the modeled
  subset-language but does not model strong updates beyond what the field-sensitive
  constraints express, and ignores arrays as well as reflection/native code.
- Incremental updates regenerate the reachable-context constraint set for the program
  (linear in program size) even though fixpoint re-solution is confined to the affected
  region; for very large programs with tiny changes the constraint-regeneration phase, not
  the solver phase, eventually dominates.
- Persistence uses local filesystem atomic rename; network or non-POSIX filesystems with
  weaker rename guarantees are not covered.
