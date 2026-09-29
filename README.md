# Pointer & Alias Analysis Service (Java)

A local, in-process pointer and may-alias analysis service for a small Java-like
language. It builds program-wide points-to relations from source files, answers
**points-to** and **possible-alias** queries, supports **file-level incremental
updates**, **concurrent snapshot-isolated queries**, **crash recovery**,
configurable **context sensitivity**, and produces **deterministic, reproducible**
results.

The service is implemented on top of Spring Boot but the analysis core
(`com.github.guoyyit.pointeranalysisjava.analysis`) has no web dependency and can
be embedded directly.

## Analysis semantics

* **Algorithm**: Andersen-style, inclusion-based (subset-constraint), flow
  insensitive, **field sensitive**.
* **Context sensitivity**: k-limited call strings (`contextK`, default `1`).
  Locals/formals in different calling contexts are distinct variables; query
  answers are labeled with their owning context. `contextK = 0` merges callers.
* **Heap model**: allocation-site based; a heap object is identified by
  `allocationSite@heapContext`, where the heap context is the k-limited calling
  context active at the allocation.
* **Pointers**: context-qualified local variables/parameters and instance-field
  addresses `(object, fieldName)`.
* **Statements understood** (one statement per line):
  * `[Type] lhs = new Type();` — allocation
  * `lhs = rhs;` — local copy
  * `lhs = base.f;` — field load; `base.f = rhs;` — field store
    (a complex right-hand side is desugared through a stable synthetic local)
  * `receiver.Type.method(args);` — virtual call (explicit receiver type)
  * `a.foo(args);` — virtual call where the static type is inferred from `a`
  * `Type.method(args);` — static call (capitalized callee name)
  * `return value;` — return flow to the call's left-hand side
* **Dispatch**: virtual calls resolve on the runtime type of the receiver,
  walking the `class C extends P` chain to find the closest matching method by
  name/arity. Static calls bind directly. The receiver is bound to `this`.
* **Entry point**: the static method `Main.main()` unless overridden via
  configuration. Only code reachable from the entry point is analyzed.

### Points-to

`evaluatePath(context, "Class#method/arity#root.f1.f2...")` returns every
concrete heap object the access path may denote. A `null`/`*` context returns
matches across all contexts; otherwise only the given context is considered.

### May-alias

Two access paths **may alias** when, within the same calling context, their
points-to sets intersect. Distinct allocations never alias; the answer is a
sound may-alias for the supported language.

## Incremental updates

Incrementality is **exact**, not an approximation:

1. Every derived node (points-to fact, reachability fact, call binding,
   copy/load/store edge, statement activation) is kept in a provenance graph.
   A node exists only while at least one supporting derivation exists; each
   derivation records its prerequisite nodes and the originating statement site.
2. On a file change (add/modify/delete), all derivations rooted at a statement
   site in that file are removed. Nodes that lose every support cascade away.
3. The changed file is re-parsed, new derivations are registered, and fixpoint
   propagation continues. A fact justified by multiple independent statements
   survives as long as any support remains.

Consequently, the incremental result is **identical to a full rebuild** for the
same inputs (asserted by `AnalysisEngineTest`, `SolverSanityTest`, and the
`/api/verify` cross-check). When the changed region does not affect most of the
reachable program (e.g. a leaf file), re-analysis cost is proportional to the
affected region rather than total program size. The performance-bound test
(`-Drun.perf=true`) demonstrates ~90x speedup for a leaf-file change in a
2,500-class reachable program.

## Concurrency model

* The currently published state is an immutable `RevisionView` held in an
  `AtomicReference`.
* A query reads the reference once and computes over that immutable snapshot, so
  it always observes one consistent published revision and never a mix of old
  and new facts. A snapshot stays unchanged until atomically replaced.
* Build/update/rebuild are serialized by a single writer lock and only swap the
  published reference after a new immutable snapshot is fully computed and
  durably stored.
* All sets are sorted and worklists are drained in total order, so results are
  deterministic for identical inputs regardless of query/update interleaving.
* `parallelism` configures per-file parse/processing parallelism at the service
  boundary; global fixpoint solving is intentionally single-threaded to preserve
  an exact, order-independent result.

## Crash recovery

State lives under `pointer-analysis.storage-dir` (default `./pointer-analysis-data`):

* `snapshot.json` — the latest published snapshot (program, configuration,
  variable and field points-to maps), written to a temp file and atomically
  moved into place (never torn).
* `revision` — the latest applied revision (monotonic), preventing an
  incremental change from being applied twice after restart.
* `query-history.jsonl` — append-only, one JSON record per answered query;
  previously published answers are never lost.

On startup the service restores the latest consistent snapshot and full query
history. Subsequent updates continue from that revision; their results equal a
fresh full rebuild (see `AnalysisServiceTest.recoveredIncrementalMatchesFreshFullRebuild`).

## HTTP API

Base path: `/api`

| Method & path             | Purpose                                            |
|---------------------------|----------------------------------------------------|
| `POST /build`             | Full analysis from `{sources, config}`             |
| `POST /update`            | File-level incremental update from full new sources|
| `POST /rebuild`           | Forced full rebuild                                |
| `POST /verify`            | Cross-check incremental result vs full rebuild     |
| `POST /query/points-to`   | `{context, path}` points-to query                  |
| `POST /query/alias`       | `{context, left, right}` may-alias query           |
| `GET  /status`            | Revision and program statistics                    |
| `GET  /history`           | Reproducible history of answered queries           |

Access paths use `Class#method/arity#root.f1.f2`; the `Class#method/arity#`
prefix is optional (entry method assumed). Contexts use `/`-separated call sites
or `<root>` / `*`.

## Configuration

| Property                          | Default     | Meaning                          |
|-----------------------------------|-------------|----------------------------------|
| `pointer-analysis.storage-dir`    | `./pointer-analysis-data` | persistence directory |
| `contextK` (build config)         | `1`         | call-string length (`0` = CI)    |
| `parallelism` (build config)      | CPU count   | per-file parse worker count      |
| `entryClass` / `entryMethod`      | `Main`/`main` | static entry point            |

## Build and test

```bash
mvn test                       # all unit/service/web tests (perf test skipped)
mvn test -Drun.perf=true       # include the performance-bound test
mvn spring-boot:run            # start the HTTP service on port 8080
```

## Known limitations

* The accepted language is a deliberately small Java subset: one statement per
  line, no inner classes, generics, arrays, exceptions, reflection, native code,
  threads, or static fields. Type prefixes on locals are accepted and ignored.
* Analysis is flow insensitive and does not model strong vs weak updates of
  multi-object field locations beyond standard Andersen field sensitivity.
* Library code outside the provided sources is treated as nonexistent; calls to
  unresolved methods are ignored.
* Global fixpoint solving is single threaded for exact determinism; only
  file-level parsing/processing is parallelized.
* After a hard restart, the first update re-derives the provenance graph from
  the persisted snapshot before applying the change; later updates in the same
  process reuse it directly.
