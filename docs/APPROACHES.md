# Approaches Considered & Trade-off Analysis — Prompt Batch Service

> **Purpose.** The other docs describe *what we build* and *why it's shaped that way*.
> This document exists to answer a different question a reviewer will always ask:
> **"Did you actually consider the alternatives, or did you just pick the first thing
> that worked?"**
>
> For every meaningful design decision we lay out **the options we evaluated**, their
> **pros and cons**, a **side-by-side comparison**, and **why the option we chose is the
> best fit** for this problem. The chosen approach in each section is **final** for v1 —
> this doc is the paper trail that justifies it, not a re-litigation of it.
>
> **Companion docs:** [`SOLUTIONING.md`](SOLUTIONING.md) (HLD + scaling), [`LLD.md`](LLD.md)
> (interfaces/seams), [`DEVELOPMENT_GUIDE.md`](DEVELOPMENT_GUIDE.md) (build guide).

---

## How to read this document

Each decision section follows the same structure:

1. **The decision** — the question being answered.
2. **Options considered** — every realistic alternative, with pros/cons.
3. **Comparison** — a table scoring the options against what *this* problem needs.
4. **Verdict** — the choice and the one-line reason it wins.

A quick note on our evaluation lens. This service is deliberately **I/O-bound and
rate-gated**: the expensive part is waiting on a slow, rate-limited inference endpoint,
not crunching CPU. Almost every trade-off below is decided by that single fact, plus two
hard constraints from the brief:

- **C1 — Immediate acknowledgement.** The HTTP call must never block on processing.
- **C2 — Bounded everything.** No unbounded threads, queues, retries, or delays.

---

## Table of contents

1. [Decision 1 — Overall processing model](#decision-1--overall-processing-model)
2. [Decision 2 — Concurrency mechanism](#decision-2--concurrency-mechanism)
3. [Decision 3 — Queue & backpressure (saturation policy)](#decision-3--queue--backpressure-saturation-policy)
4. [Decision 4 — Retry & backoff algorithm](#decision-4--retry--backoff-algorithm)
5. [Decision 5 — Where backoff waits (sync sleep vs re-queue)](#decision-5--where-backoff-waits-sync-sleep-vs-re-queue)
6. [Decision 6 — Rate-limit handling (reactive vs proactive)](#decision-6--rate-limit-handling-reactive-vs-proactive)
7. [Decision 7 — Resilience: hand-rolled vs a library](#decision-7--resilience-hand-rolled-vs-a-library)
8. [Decision 8 — Batch state & progress storage](#decision-8--batch-state--progress-storage)
9. [Decision 9 — Result aggregation & persistence](#decision-9--result-aggregation--persistence)
10. [Decision 10 — Client notification model](#decision-10--client-notification-model)
11. [Decision 11 — Web framework](#decision-11--web-framework)
12. [Decision 12 — Ingestion (input format handling)](#decision-12--ingestion-input-format-handling)
13. [Master decision matrix](#master-decision-matrix)
14. [What we deliberately deferred (and why that's correct)](#what-we-deliberately-deferred-and-why-thats-correct)

---

## Decision 1 — Overall processing model

**The decision:** how does a submitted batch actually get processed relative to the HTTP
request?

### Options considered

**Option A — Synchronous inline processing.** The request thread processes all prompts
and returns the results in the same HTTP response.
- ✅ Trivial to implement; no state to track; results returned directly.
- ❌ **Violates C1** — the caller blocks for the whole batch (could be minutes).
- ❌ A 1,000-prompt batch ties up a Jetty request thread; a handful of concurrent
  submissions exhaust the HTTP thread pool and the service stops responding.
- ❌ No way to report progress; client timeouts guaranteed.

**Option B — Async in-process (accept now, process on a background pool).** Return
`202 + batchId` immediately; a bounded worker pool processes prompts in the background;
progress/results are queried later. **(chosen)**
- ✅ Satisfies C1 directly — request thread is freed instantly.
- ✅ Decouples ingest rate from processing rate; natural place to bound concurrency.
- ✅ Simple to run (one JVM, no infra) yet models the exact pipeline we later distribute.
- ❌ In-flight state lives in memory → lost on restart (acceptable for v1; see §8).
- ❌ Client must poll for completion.

**Option C — External batch/queue system from day one** (Kafka/SQS + separate worker
deployable, or Spring Batch / Quartz).
- ✅ Durable, horizontally scalable, the "real" answer at 10M users.
- ❌ **Massive overkill for a single-node deliverable** — a broker to run, ops overhead,
  slower to build and demo, harder to reason about for the assignment's core (the
  concurrency discipline).
- ❌ Obscures the thing being evaluated (bounded parallelism + retry) behind infra.

### Comparison

| Criterion | A: Sync inline | B: Async in-process | C: External queue |
|-----------|:--------------:|:-------------------:|:-----------------:|
| Immediate ack (C1) | ❌ | ✅ | ✅ |
| Bounded concurrency (C2) | ⚠️ hard | ✅ | ✅ |
| Time-to-build / demoability | ✅ | ✅ | ❌ |
| Infra required | none | none | broker + more |
| Survives restart | n/a | ❌ | ✅ |
| Right scope for v1 | ❌ | ✅ | ❌ (later) |

### Verdict

**Option B — async in-process.** It is the *only* option that satisfies the immediate-ack
constraint while staying single-node and infra-free, and it is a faithful small-scale
model of the distributed system (Option C) we grow into. We keep every seam (`TaskExecutor`,
`ResultStore`, `RateLimiter`) so Option C is a later implementation swap, not a rewrite.

---

## Decision 2 — Concurrency mechanism

**The decision:** what actually runs the prompts in parallel, with a hard ceiling?

### Options considered

**Option A — Thread-per-prompt (`new Thread()` / `newCachedThreadPool`).**
- ✅ Dead simple; maximum parallelism.
- ❌ **Violates C2** — 1,000 prompts = up to 1,000 threads; OOM / context-switch
  thrashing; hammers the downstream endpoint and guarantees rate-limit storms.

**Option B — Fixed-size `ThreadPoolExecutor` + bounded queue.** A single shared pool,
`core == max == N`, `ArrayBlockingQueue`. **(chosen)**
- ✅ Hard cap: **at most N prompts in flight, ever** — exactly C2.
- ✅ Bounded queue = backpressure (Decision 3).
- ✅ Perfectly suited to I/O-bound work: size N to target in-flight requests, not cores.
- ✅ Simplest thing that is fully correct; trivially testable; JDK-native.
- ❌ Pool size is tuned manually (a feature here — it's the rate-control knob).
- ❌ A backing-off worker holds its slot while sleeping (addressed in Decision 5 / at scale).

**Option C — Virtual threads (JDK 21 Loom), one per prompt.**
- ✅ Cheap to create millions; clean blocking-style code.
- ❌ **Cheap threads ≠ bounded load.** We'd *still* need a `Semaphore` to cap in-flight
  calls to protect the endpoint — so we don't remove the bounding problem, we just move
  it. Net complexity isn't lower for this use case.
- ❌ Unbounded virtual threads would fire thousands of simultaneous calls → instant 429s.
- ➕ A great *future* swap behind the `TaskExecutor` seam; not needed for a correct v1.

**Option D — Reactive / async non-blocking (Project Reactor, WebFlux, CompletableFuture
chains with a bounded scheduler).**
- ✅ Highest thread efficiency for I/O; no thread parked during backoff.
- ❌ Steep complexity: reactive backpressure, operator fusion, harder debugging/stack
  traces, harder to unit-test the retry logic deterministically.
- ❌ Overkill at v1 scale; the bounded pool already gives correct behavior.

**Option E — Parallel streams / `ForkJoinPool.commonPool()`.**
- ✅ One-liner (`prompts.parallelStream()...`).
- ❌ Uses the **shared common pool** (sized to CPU cores) — wrong for I/O work, and
  blocking on it starves every other parallel stream in the JVM.
- ❌ No real control over concurrency, queueing, or shutdown. Anti-pattern here.

### Comparison

| Criterion | A: Thread/prompt | B: Fixed pool | C: Virtual threads | D: Reactive | E: Parallel stream |
|-----------|:---------------:|:-------------:|:------------------:|:-----------:|:------------------:|
| Bounded in-flight (C2) | ❌ | ✅ | ⚠️ needs semaphore | ✅ | ❌ |
| Fit for I/O-bound work | ⚠️ | ✅ | ✅ | ✅ | ❌ |
| Implementation simplicity | ✅ | ✅ | ✅ | ❌ | ✅ |
| Deterministic testability | ⚠️ | ✅ | ✅ | ❌ | ❌ |
| Graceful shutdown/drain | ❌ | ✅ | ⚠️ | ⚠️ | ❌ |
| Right scope for v1 | ❌ | ✅ | ➕ future | ❌ | ❌ |

### Verdict

**Option B — fixed-size `ThreadPoolExecutor` with a bounded queue.** It is the smallest
construct that *directly* enforces the bounded-concurrency requirement, is ideal for
I/O-bound calls, and is the easiest to test and shut down cleanly. Virtual threads
(Option C) are the natural future upgrade — and because concurrency lives behind the
`TaskExecutor` seam, adopting them later is a one-class swap, not a redesign.

---

## Decision 3 — Queue & backpressure (saturation policy)

**The decision:** what happens to submitted-but-not-yet-running tasks, and what happens
when the system is saturated?

### Options considered

**Option A — Unbounded queue (`LinkedBlockingQueue`).**
- ✅ Never rejects; simple.
- ❌ **Violates C2** — a huge batch (or many batches) piles millions of `Runnable`s in
  memory and OOMs the JVM. Backpressure is impossible because the queue never pushes back.

**Option B — Bounded queue + `AbortPolicy` (reject/throw when full).**
- ✅ Bounded memory.
- ❌ **Drops work** under load — a rejected prompt is a lost prompt unless the caller
  re-submits, which contradicts "no prompt is dropped."

**Option C — Bounded queue + `CallerRunsPolicy`.** When pool + queue are full, the
submitting thread runs the task itself. **(chosen)**
- ✅ Bounded memory **and** no dropped work.
- ✅ **Natural backpressure** — the producer (ingestion loop) slows down because it's now
  doing work, which throttles intake without any explicit signaling.
- ❌ The ingest thread can briefly stall during extreme bursts (intended, and it still
  returns `202` for the batch once enqueued).

**Option D — Bounded queue + `DiscardPolicy` / `DiscardOldestPolicy`.**
- ❌ Silently drops prompts — unacceptable for correctness.

**Option E — Explicit `Semaphore` in front of an unbounded queue.**
- ✅ Bounds in-flight work.
- ❌ Reinvents what the bounded-queue + saturation-policy combo already gives us, with
  more moving parts and more places to leak permits.

### Comparison

| Criterion | A: Unbounded | B: Abort | C: CallerRuns | D: Discard | E: Semaphore |
|-----------|:-----------:|:--------:|:-------------:|:----------:|:------------:|
| Bounded memory (C2) | ❌ | ✅ | ✅ | ✅ | ✅ |
| No dropped prompts | ✅ | ❌ | ✅ | ❌ | ✅ |
| Provides backpressure | ❌ | ⚠️ | ✅ | ❌ | ⚠️ |
| Simplicity | ✅ | ✅ | ✅ | ✅ | ⚠️ |

### Verdict

**Option C — bounded `ArrayBlockingQueue` + `CallerRunsPolicy`.** It's the one policy that
is simultaneously bounded, lossless, and self-throttling, with zero extra machinery. It
turns "the system is overloaded" into "the producer naturally slows down," which is exactly
the backpressure behavior we want.

---

## Decision 4 — Retry & backoff algorithm

**The decision:** how do we react to a `429` (or retryable `5xx`) so no prompt is dropped
and we don't stampede the endpoint?

### Options considered

**Option A — No retry (fail on first 429).**
- ❌ Guarantees mass failures against a periodically-throttling endpoint. Fails the brief.

**Option B — Immediate retry (no delay).**
- ❌ Busy-loops the endpoint the instant it's overloaded — makes throttling *worse*.

**Option C — Fixed-delay retry.**
- ✅ Simple; better than nothing.
- ❌ Doesn't relieve a sustained overload (constant pressure), and synchronized retries
  cause a **thundering herd** (many workers retry at the same instant).

**Option D — Exponential backoff, no jitter.** `delay = base · 2^attempt`, capped.
- ✅ Backs off increasingly under load; bounded by a max delay.
- ❌ Still a thundering herd: all workers that hit 429 together retry in lockstep, so the
  retries themselves re-trigger the limit in synchronized waves.

**Option E — Exponential backoff + full jitter,** bounded retries, `Retry-After`-aware.
`sleep = random(0, min(base·2^attempt, maxDelay))`. **(chosen)**
- ✅ Exponential growth relieves the endpoint fast; **jitter spreads retries out** and
  kills the thundering herd; **max delay** caps worst-case latency; **bounded retries**
  mean a prompt eventually fails cleanly (recorded, not looped forever); honoring
  `Retry-After` respects the server's own guidance.
- ❌ Individual prompt latency increases (correct trade for system stability).

**Option F — Decorrelated jitter** (AWS-style `sleep = min(cap, random(base, prev·3))`).
- ✅ Slightly smoother distribution than full jitter in some load profiles.
- ➖ Marginal benefit over full jitter for our case; full jitter is simpler to explain and
  test. Trivially swappable via the `BackoffStrategy` seam if we ever want it.

### Comparison

| Criterion | A: None | B: Immediate | C: Fixed | D: Exp no jitter | E: Exp + full jitter | F: Decorrelated |
|-----------|:-------:|:------------:|:--------:|:----------------:|:--------------------:|:---------------:|
| No dropped prompts | ❌ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Relieves overload | ❌ | ❌ | ⚠️ | ✅ | ✅ | ✅ |
| Avoids thundering herd | ❌ | ❌ | ❌ | ❌ | ✅ | ✅ |
| Bounded worst-case latency | n/a | n/a | ⚠️ | ✅ | ✅ | ✅ |
| Simplicity / explainability | ✅ | ✅ | ✅ | ✅ | ✅ | ⚠️ |

### Verdict

**Option E — bounded exponential backoff with full jitter (+ `Retry-After` awareness).**
It is the industry-standard answer and the only option that checks every box: relieves the
endpoint, avoids synchronized retry waves, caps latency, and never drops a prompt. Because
the delay math is isolated behind `BackoffStrategy`, decorrelated jitter (Option F) is a
drop-in alternative if a specific load profile ever calls for it.

---

## Decision 5 — Where backoff waits (sync sleep vs re-queue)

**The decision:** while a prompt is backing off, do we hold a worker thread, or free it?

### Options considered

**Option A — Synchronous `sleep` on the worker thread.** The backing-off worker occupies
one of the N slots for the duration of the delay. **(chosen for v1)**
- ✅ Dead simple; keeps the retry loop linear and readable.
- ✅ **A backing-off worker naturally reduces offered load** — it's not making calls while
  it sleeps, so the pool itself acts as a crude rate limiter. Desirable at v1's scale.
- ✅ Fully deterministic to test with an injected `Sleeper` (no real waiting).
- ❌ Wastes a thread slot during long backoffs — fine at N=8, wasteful at tens of thousands
  of in-flight items.

**Option B — Re-queue with a visibility delay** (scheduled re-enqueue / delay queue). The
worker returns immediately; the task is re-scheduled after the delay.
- ✅ Frees the thread during backoff → far better utilization at scale.
- ❌ More moving parts (a scheduler / delay queue); harder to reason about ordering and
  completion; unnecessary complexity for a single node.
- ➕ This is exactly what we adopt at scale (SQS delay / Kafka retry-topic).

### Comparison

| Criterion | A: Sync sleep | B: Delayed re-queue |
|-----------|:-------------:|:-------------------:|
| Simplicity | ✅ | ❌ |
| Thread efficiency at high fan-out | ❌ | ✅ |
| Acts as implicit load shedding | ✅ | ⚠️ |
| Deterministic unit testing | ✅ | ⚠️ |
| Right scope for v1 | ✅ | ➕ at scale |

### Verdict

**Option A — synchronous sleep on the worker thread for v1.** At N bounded to a small
number, the "wasted" slot is actually a feature (it throttles offered load), and the code
stays linear and testable. The `BackoffStrategy` + `TaskExecutor` seams let us move to
delayed re-queue (Option B) when fan-out grows — noted explicitly as the scale-time change.

---

## Decision 6 — Rate-limit handling (reactive vs proactive)

**The decision:** do we only *react* to 429s, or also *proactively* limit the rate we
offer requests?

### Options considered

**Option A — Reactive only (retry/backoff on 429).** Plus a `RateLimiter` seam wired as a
**no-op** in v1. **(chosen for v1)**
- ✅ Correct and sufficient on a single node: the fixed pool caps concurrency and backoff
  handles pushback.
- ✅ Zero infra. The seam exists, so going proactive later changes no callers.
- ❌ No *global* view — irrelevant with one process, critical with many.

**Option B — Proactive local rate limiter (token bucket / `Guava RateLimiter`).**
- ✅ Smooths offered load; fewer 429s in the first place.
- ➖ For one node with a bounded pool, the pool already bounds the rate; a local limiter is
  redundant most of the time. Cheap to add via the seam if a demo needs it.

**Option C — Distributed token bucket (Redis).**
- ✅ The *correct* answer for a multi-instance fleet — enforces a global cap so N instances
  don't each independently overwhelm the endpoint.
- ❌ Requires Redis; pointless on a single node. This is the scale-time implementation of
  the same `RateLimiter` seam.

### Comparison

| Criterion | A: Reactive (no-op limiter) | B: Local limiter | C: Distributed (Redis) |
|-----------|:--------------------------:|:----------------:|:----------------------:|
| Correct on single node | ✅ | ✅ | ✅ (overkill) |
| Correct across a fleet | ❌ | ❌ | ✅ |
| Infra required | none | none | Redis |
| Right scope for v1 | ✅ | ➖ optional | ➕ at scale |

### Verdict

**Option A — reactive handling now, with the `RateLimiter` seam pre-wired as a no-op.**
On a single node the bounded pool + backoff is provably sufficient, and by shipping the
seam (even as a no-op) the distributed token bucket (Option C) becomes a pure
implementation swap with **zero caller changes** when we go multi-instance.

---

## Decision 7 — Resilience: hand-rolled vs a library

**The decision:** do we implement retry/backoff ourselves or pull in Resilience4j /
Failsafe / Spring Retry?

### Options considered

**Option A — Hand-rolled retry decorator** (`RetryingInferenceClient` + `BackoffStrategy`
+ injected `Sleeper`/`Random`). **(chosen)**
- ✅ ~40 lines, no new dependency, and the retry logic is the *thing being evaluated* —
  writing it demonstrates the understanding a library would hide.
- ✅ Fully deterministic tests via injected clock/sleeper/random.
- ✅ Clean decorator behind the `InferenceClient` seam — swap in a library later for free.
- ❌ We own the (small) code; no circuit breaker / bulkhead out of the box.

**Option B — Resilience4j (or Failsafe).**
- ✅ Battle-tested; retry + circuit breaker + bulkhead + rate limiter in one library.
- ❌ Extra dependency and config surface; hides the core logic the assignment wants to see;
  harder to make backoff *deterministically* assertable in a unit test than an injected
  `Sleeper`.
- ➕ Genuinely worth adopting at scale (for circuit breaking especially) — and trivial to
  introduce as another `InferenceClient` decorator when we do.

### Comparison

| Criterion | A: Hand-rolled | B: Library (Resilience4j) |
|-----------|:--------------:|:-------------------------:|
| Demonstrates the core skill | ✅ | ❌ |
| Zero extra dependencies | ✅ | ❌ |
| Deterministic testability | ✅ | ⚠️ |
| Circuit breaker / bulkhead built-in | ❌ | ✅ |
| Best at scale | ⚠️ | ✅ |

### Verdict

**Option A — hand-rolled for v1.** The retry/backoff loop is the heart of the assignment;
implementing it (small, tested, injected time) is more valuable than delegating it, and the
decorator seam means a library can be layered in later without touching callers.

---

## Decision 8 — Batch state & progress storage

**The decision:** where do live batch state and progress counters live?

### Options considered

**Option A — In-memory (`ConcurrentHashMap` + `AtomicInteger`/`LongAdder`).** **(chosen for v1)**
- ✅ Fastest possible progress reads (sub-microsecond); no serialization; no infra.
- ✅ Atomic counters give correct concurrent updates with no locks.
- ✅ Perfectly matches a single-node deliverable.
- ❌ **Not durable** — in-flight state is lost on restart; can't be shared across processes.

**Option B — Embedded DB (SQLite / H2).**
- ✅ Survives restart; queryable history; still single-file, no server.
- ❌ Write amplification on the hot path (every counter tick → a write) unless batched;
  slower progress reads; more code for v1's needs.
- ➕ The natural **Phase P1** upgrade for durability on one node.

**Option C — External DB (Postgres) + Redis counters.**
- ✅ The scale answer: durable, shared, fast atomic `HINCRBY` progress.
- ❌ Requires infra; unjustified on a single node.

### Comparison

| Criterion | A: In-memory | B: Embedded DB | C: Postgres + Redis |
|-----------|:-----------:|:--------------:|:-------------------:|
| Progress read latency | ✅ best | ⚠️ | ✅ (Redis) |
| Durable across restart | ❌ | ✅ | ✅ |
| Shared across instances | ❌ | ❌ | ✅ |
| Infra required | none | none | DB + Redis |
| Right scope for v1 | ✅ | ➕ P1 | ➕ scale |

### Verdict

**Option A — in-memory with atomics/concurrent maps for v1.** It gives the fastest,
simplest, lock-free progress tracking for a single node, and the `BatchRepository` seam
makes the SQLite→Postgres+Redis progression (Options B→C) a swap behind a stable interface.
The lost-on-restart limitation is a *deliberate, documented* v1 trade-off, not an oversight —
and it doesn't have to wait for that whole-repository swap: [`LLD.md` §13](LLD.md#13-task-persistence--retry-in-memory-v1-db-swappable)
adds a narrower **task**-level log (independent of this batch-level decision, and itself
in-memory for v1 — see that section's own scope decision) that already gets idle-time retry
for individual prompts today, rebuilds these very in-memory batch counters from that log, and
is a one-line swap away from also surviving a crash once it's backed by SQLite/a DB.

---

## Decision 9 — Result aggregation & persistence

**The decision:** how are per-prompt outcomes collected and where does the final result
live?

### Options considered

**Option A — In-memory aggregation only.**
- ✅ Simplest; fine for demos and tests.
- ❌ Results vanish on restart; memory grows with total results held.

**Option B — Final JSON file per batch** (via a `ResultStore` with `write` + `finalize`).
**(chosen default for v1)**
- ✅ Directly satisfies the "final JSON output" requirement; human-readable; trivially
  durable; no infra.
- ✅ The `write`/`finalize` split lets us stream per-prompt then seal one artifact.
- ❌ Not ideal for rich querying across batches.

**Option C — Embedded DB tables (`batches`, `prompt_results`).**
- ✅ Queryable, survives restart, better for "list my batches."
- ❌ More setup than a JSON file for v1's needs.
- ➕ Config-selectable *today* (`store.type`) — same `ResultStore` seam.

**Option D — Object store (S3) + DB index.**
- ✅ Effectively unlimited, cheap, durable — the scale answer.
- ❌ Requires cloud infra; out of scope for v1.

### Comparison

| Criterion | A: In-memory | B: JSON file | C: Embedded DB | D: S3 + index |
|-----------|:-----------:|:------------:|:--------------:|:-------------:|
| Meets "final JSON output" | ⚠️ | ✅ | ⚠️ | ✅ |
| Durable | ❌ | ✅ | ✅ | ✅ |
| Queryable | ❌ | ❌ | ✅ | ⚠️ |
| Infra required | none | none | none | cloud |
| Right scope for v1 | ⚠️ tests | ✅ | ➕ optional | ➕ scale |

### Verdict

**Option B — JSON file per batch as the default (with embedded DB config-selectable).** It
maps one-to-one to the "aggregate into a final JSON output" requirement, is durable with
zero infra, and the `ResultStore` seam (`write` + `finalize`) keeps the door open to
embedded DB (C) now and S3 (D) at scale — none of which touch the aggregator.

---

## Decision 10 — Client notification model

**The decision:** after `202`, how does the client learn about progress and completion?

### Options considered

**Option A — Polling (`GET /batches/{id}` and `/results`).** **(chosen for v1)**
- ✅ Dead simple, stateless, cache-friendly, works with any HTTP client and `curl`.
- ✅ Progress reads are cheap (in-memory counters).
- ❌ Client must poll; slight latency between completion and the client noticing.

**Option B — Webhook / callback on completion.**
- ✅ Push-based; no polling; great for server-to-server.
- ❌ Requires the client to run an endpoint; delivery/retry semantics to build; more than
  v1 needs.
- ➕ Cheap to add later by *decorating* the `Aggregator` to emit on completion.

**Option C — Server-Sent Events (SSE) / WebSocket stream.**
- ✅ Real-time progress UI.
- ❌ Connection lifecycle/state management; overkill for a batch API; doesn't fit the
  stateless-poll model well.

### Comparison

| Criterion | A: Polling | B: Webhook | C: SSE/WebSocket |
|-----------|:---------:|:----------:|:----------------:|
| Simplicity | ✅ | ⚠️ | ❌ |
| Works with any client/curl | ✅ | ⚠️ | ⚠️ |
| Push (no polling) | ❌ | ✅ | ✅ |
| Extra client requirements | none | endpoint | persistent conn |
| Right scope for v1 | ✅ | ➕ later | ➖ |

### Verdict

**Option A — polling.** It's the simplest correct fit for a batch API, needs nothing of the
client, and pairs perfectly with cheap in-memory progress counters. Webhooks (B) are a
natural, non-breaking future add-on via an `Aggregator` decorator — noted, not needed now.

---

## Decision 11 — Web framework

**The decision:** what runs the HTTP edge and app lifecycle?

### Options considered

**Option A — Dropwizard.** **(chosen)**
- ✅ Opinionated, batteries-included (Jetty + Jersey + Jackson + Metrics + HealthChecks +
  Managed lifecycle) with **no annotation-magic DI** — a perfect match for explicit,
  reviewable wiring at a composition root.
- ✅ `Managed` gives clean pool startup/graceful-drain; health/metrics out of the box.
- ✅ Lightweight, fast startup, small fat-jar — ideal for a Docker deliverable.
- ❌ Smaller ecosystem than Spring; fewer starters.

**Option B — Spring Boot.**
- ✅ Huge ecosystem; `@Async`, Spring Retry, Actuator, starters for everything.
- ❌ Heavier; DI-by-annotation hides the wiring we specifically want to make explicit;
  slower startup / larger image; more magic to explain in a concurrency-focused review.

**Option C — Quarkus / Micronaut.**
- ✅ Fast startup, low memory, native-image friendly.
- ❌ Less familiar; build-time DI adds concepts unrelated to the assignment's core.

**Option D — Plain Java + an embedded server (no framework).**
- ✅ Zero framework overhead; total control.
- ❌ Re-implements config binding, JSON, health, metrics, lifecycle by hand — wasted effort
  that adds no signal.

### Comparison

| Criterion | A: Dropwizard | B: Spring Boot | C: Quarkus | D: Plain |
|-----------|:-------------:|:--------------:|:----------:|:--------:|
| Batteries included | ✅ | ✅ | ✅ | ❌ |
| Explicit, reviewable wiring | ✅ | ⚠️ (magic) | ⚠️ | ✅ |
| Startup / image size | ✅ | ⚠️ | ✅ | ✅ |
| Lifecycle/health/metrics built-in | ✅ | ✅ | ✅ | ❌ |
| Fit for this assignment | ✅ | ⚠️ | ⚠️ | ❌ |

### Verdict

**Option A — Dropwizard.** It provides exactly the production plumbing we need (lifecycle,
health, metrics, JSON, config) while keeping dependency wiring **explicit at a composition
root**, which is precisely what makes the concurrency design easy to read and review. Its
`Managed` interface is a clean fit for the worker pool's graceful-drain requirement.

---

## Decision 12 — Ingestion (input format handling)

**The decision:** how do we support "file upload **or** JSON" (and future formats)?

### Options considered

**Option A — Branch on content type inside the resource** (`if json … else if file …`).
- ✅ Fewer classes upfront.
- ❌ Every new format edits the resource → violates open/closed; the `switch` grows
  unbounded; harder to test each parser in isolation.

**Option B — `PromptSource` strategy + `PromptSourceRegistry`** (one class per format,
selected by content type). **(chosen)**
- ✅ New format = **new class, wire it in** — no edits to resource or service (open/closed).
- ✅ Each parser is independently unit-testable.
- ✅ Textbook Strategy + Registry; matches the seam-based design of the whole service.
- ❌ Slightly more structure upfront (a couple of small classes) — pays for itself on the
  first added format.

### Comparison

| Criterion | A: Branch in resource | B: Strategy + registry |
|-----------|:---------------------:|:----------------------:|
| Add a format without editing callers | ❌ | ✅ |
| Per-parser testability | ⚠️ | ✅ |
| Upfront class count | ✅ fewer | ⚠️ a few more |
| Consistency with rest of design | ❌ | ✅ |

### Verdict

**Option B — `PromptSource` strategy + registry.** The tiny upfront cost buys open/closed
extensibility (CSV, multipart, S3-pointer are all "add a class") and keeps the HTTP resource
thin — consistent with the seam-driven architecture used everywhere else.

---

## Master decision matrix

The whole design at a glance: what we chose, the strongest alternative, and the deciding
reason.

| # | Decision | Chosen (v1) | Strongest alternative | Why our choice wins |
|---|----------|-------------|-----------------------|---------------------|
| 1 | Processing model | Async in-process (`202` + bg pool) | External queue | Meets immediate-ack with zero infra; models the scaled pipeline |
| 2 | Concurrency | Fixed `ThreadPoolExecutor` (N) | Virtual threads | Directly bounds in-flight load; simplest correct; Loom is a future swap |
| 3 | Backpressure | Bounded queue + `CallerRunsPolicy` | Semaphore | Bounded **and** lossless **and** self-throttling, no extra parts |
| 4 | Backoff | Exponential + full jitter | Decorrelated jitter | Kills thundering herd, caps latency; standard, simple, testable |
| 5 | Backoff wait | Sync sleep on worker | Delayed re-queue | Simple + acts as load shedding at v1 scale |
| 6 | Rate limiting | Reactive + no-op limiter seam | Redis token bucket | Sufficient on one node; seam makes global limiter a swap |
| 7 | Resilience code | Hand-rolled decorator | Resilience4j | Shows the core skill; deterministic tests; library layers in later |
| 8 | State/progress | In-memory atomics | Postgres + Redis | Fastest, lock-free, no infra; seam enables durable swap |
| 9 | Results | JSON file (DB-selectable) | Embedded DB | Matches "final JSON output"; durable; seam enables S3 |
| 10 | Notification | Polling | Webhook | Simplest; no client requirements; webhook is a decorator later |
| 11 | Framework | Dropwizard | Spring Boot | Explicit wiring + built-in lifecycle/health; light image |
| 12 | Ingestion | Strategy + registry | Branch in resource | Open/closed; per-parser tests; consistent with design |

**The through-line:** in every decision we chose **the simplest option that is fully
correct for a single node**, and we placed it **behind a seam** so the heavier, distributed
alternative becomes an implementation swap — never a rewrite. The alternatives aren't
rejected forever; most are exactly what we grow into (see [`SOLUTIONING.md`](SOLUTIONING.md)
§8–9). v1 deliberately picks the version of each that maximizes correctness-per-line while
keeping the scaling door open.

---

## What we deliberately deferred (and why that's correct)

To be explicit that these are **conscious trade-offs**, not gaps:

| Deferred | Chosen v1 behavior | Why deferring is correct | Where it lands |
|----------|--------------------|--------------------------|----------------|
| Durability of in-flight state | In-memory, lost on restart | Single-node scope; adding a DB now adds cost without exercising the core concurrency design | P1 (embedded/Postgres) |
| Horizontal scale | One JVM, one pool | Throughput ceiling is the *downstream rate limit*, not our compute; one node proves the model | P2 (queue + fleet) |
| Global rate limiting | No-op limiter seam | One process can't exceed a global cap it alone controls | P4 (Redis token bucket) |
| Off-thread backoff | Sync sleep | Wasted slot is negligible at small N and acts as load shedding | P4 (delay re-queue) |
| Push notifications | Polling | No client-side endpoint required; simplest correct UX | Later (webhook decorator) |
| Circuit breaking / bulkheads | Retry + backoff only | Not required to satisfy the brief; retry handles the specified 429 case | Later (Resilience4j decorator) |

Each deferral is guarded by an existing interface (§4 of [`LLD.md`](LLD.md)), so picking it
up later is additive. That is the core argument of this document: **our approach is optimal
for the problem as scoped, and it is the same approach at scale — just with different
implementations plugged into the same seams.**
