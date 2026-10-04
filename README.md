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
affected region rather than total program size. This is enforced automatically
with no opt-in flag by `PerformanceBoundTest`, which runs as part of plain
`mvn test`:
* a change confined to one reachable file tears down and re-registers the same
  small number of provenance derivations whether the surrounding program has
  200 or 2,500 unrelated classes (`unrelatedCodeGrowthDoesNotInflateIncrementalWork`);
* the incremental re-analysis is materially cheaper than a full rebuild
  (`oneFileChangeIsRegionBoundedAndMuchCheaperThanFullRebuild`).

The primary regression signal is machine-independent work counters
(`affectedSites`, `derivationsRemoved`, `derivationsRegistered`), not wall
time; best-of-5 millisecond timings (with warm-up) are a secondary signal.
Every run writes a JSON record to `target/benchmark-results/<scenario>-latest.json`
plus a timestamped copy, so results can be compared across builds.

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
* `parallelism` (a build-time config field, `>= 1`) **really changes
  execution**: source files are parsed concurrently on a fixed pool of
  `pointer-analysis-parser-<pool>-<n>` daemon threads, capped at the configured
  worker count. With `parallelism=1` (or a single input file) parsing runs on
  the calling thread only.
* Files are independent parse units: each file is tokenized and parsed by
  exactly one worker, and results are merged in sorted file order on the
  calling thread. The global fixpoint solver stays single-threaded on purpose,
  so thread scheduling cannot change any fact. Points-to sets, alias answers
  and query histories are identical at every parallelism value and across
  repeated runs; see `ParallelAnalysisTest`.
* How to observe the difference locally:
  * `GET /api/status` returns `configuredParallelism`, `parseParallelism`,
    `parserWorkerThreads` (names of threads that actually parsed files),
    `parseMillis`, `solveMillis`, `incrementalMillis`, `lastOutcome`, plus
    `affectedSites` / `derivationsRemoved` / `derivationsRegistered` after an
    incremental update.
  * Build with `"config":{"parallelism":8}` over many files and check that
    `parserWorkerThreads` lists multiple worker threads; rebuild with
    `"parallelism":1` and the list shrinks to the single calling thread.

## Crash recovery

State lives under `pointer-analysis.storage-dir` (default `./pointer-analysis-data`):

* `snapshot.json` — the latest published snapshot (program, configuration,
  variable and field points-to maps), written to a temp file and atomically
  moved into place. The atomic move is the commit point: a crash before it
  leaves the previous snapshot intact; a crash after it leaves the new one.
* `revision` — the revision of the committed snapshot. On startup
  `RevisionStore.reconcileMarkerWithSnapshot()` realigns this marker with
  `snapshot.json` (the snapshot is the source of truth), so a crash in the
  window between snapshot commit and marker write cannot lose or double-apply
  the change.
* `query-history.jsonl` — append-only, one JSON record per answered query;
  previously published answers are never lost.

On startup the service restores the latest consistent snapshot, aligns the
revision marker, and loads the full query history. Idempotency guarantees:
* submitting sources identical to the current program (a retry, a duplicate
  request, or the same change replayed after restart) returns the existing
  revision with no new snapshot, no revision bump, and no query-history side
  effects (`lastOutcome` is reported as `no-op-identical-sources`);
* a change already committed before a crash is never applied a second time.

Crash timing is covered by `CrashRecoveryTest`, which injects hard failures at
`BEFORE_SNAPSHOT_WRITE` and `AFTER_SNAPSHOT_BEFORE_MARKER` and then asserts
that the recovered durable state, the published snapshot and query answers all
line up with a fresh full analysis. These tests are never conditionally
skipped. After a crash-free restart the first update rebuilds the in-memory
provenance graph from the persisted snapshot before applying the change; later
updates in the same process reuse it directly.

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
| `GET  /status`            | Revision, program statistics and execution metrics |
| `GET  /history`           | Reproducible history of answered queries           |

Access paths use `Class#method/arity#root.f1.f2`; the `Class#method/arity#`
prefix is optional (entry method assumed). Contexts use `/`-separated call sites
or `<root>` / `*`.

`POST /build`, `/update` and `/rebuild` return the same body as
`GET /status`. The execution-metric fields are `configuredParallelism`,
`parseParallelism`, `parserWorkerThreads`, `parseMillis`, `solveMillis`,
`incrementalMillis`, `lastOutcome` (`full-build` / `full-rebuild` /
`incremental` / `no-op-identical-sources`) and, for incremental updates,
`affectedSites`, `derivationsRemoved` and `derivationsRegistered`.

## Configuration

| Property                          | Default     | Meaning                          |
|-----------------------------------|-------------|----------------------------------|
| `pointer-analysis.storage-dir`    | `./pointer-analysis-data` | persistence directory |
| `contextK` (build config)         | `1`         | call-string length (`0` = CI)    |
| `parallelism` (build config)      | CPU count   | concurrent per-file parse workers (`>= 1`) |
| `entryClass` / `entryMethod`      | `Main`/`main` | static entry point            |

## Build and test

```bash
mvn test                       # all tests, including performance-bound acceptance
mvn test -Dtest=PerformanceBoundTest  # only the performance-bound scenarios
mvn spring-boot:run            # start the HTTP service on port 8080
```

All tests run under plain `mvn test`; none require an environment variable or
system property to be enabled. The performance-bound scenarios use warm-up and
best-of-N timing plus deterministic work counters to stay reliable on loaded
machines while still failing on a genuine regression toward full-rebuild cost.
Benchmark output lands in `target/benchmark-results/` (cleaned by
`mvn clean`); diff the `<scenario>-latest.json` files between builds to review
performance changes.

## Known limitations

* The accepted language is a deliberately small Java subset: one statement per
  line, no inner classes, generics, arrays, exceptions, reflection, native code,
  threads, or static fields. Type prefixes on locals are accepted and ignored.
* Analysis is flow insensitive and does not model strong vs weak updates of
  multi-object field locations beyond standard Andersen field sensitivity.
* Library code outside the provided sources is treated as nonexistent; calls to
  unresolved methods are ignored.
* Parallelism is deliberately limited to independent per-file parsing. The
  global fixpoint solver is single threaded so results cannot depend on thread
  scheduling; within one process only parsing fans out, while the analyzed
  snapshot itself is produced sequentially.
* After a hard restart, the first update re-derives the provenance graph from
  the persisted snapshot before applying the change; later updates in the same
  process reuse it directly.
