# Development Guide — How to Code the Prompt Batch Service

> **What this document is.** A hands-on, step-by-step **implementation guide** that turns
> the design in [`SOLUTIONING.md`](SOLUTIONING.md) into working code, on top of the existing
> skeleton (a running Dropwizard app with `/ping` + a health check).
>
> **Read `SOLUTIONING.md` first** — it explains *why* the design is shaped this way.
> For the interface/seam-level contracts and the extensibility rules (where new code and
> scaling plug in), see [`LLD.md`](LLD.md). This guide explains *how to build it*, class by
> class, in an order that keeps the app compiling and testable at every step.
>
> The guide is written against the interview "Evaluation Areas" (18 of them). Every step
> notes which area(s) it satisfies so nothing is missed.

---

## 0. How to use this guide

- Build in the **milestone order** below. Each milestone leaves the app **compiling and
  green**, so you always have something to demo.
- Code snippets are **reference implementations** — the shape you should aim for. Adapt
  names/packages to taste, but keep the layering and concurrency discipline.
- After each milestone, run the build (`mvn -q verify`, or the Docker command from the
  README) so regressions are caught immediately.
- The golden rule for this assignment: **the HTTP request must never block on processing**,
  and **concurrency must always be bounded**. If a change violates either, it's wrong.

### Evaluation area → where it's implemented

| # | Evaluation area | Milestone / package |
|---|-----------------|---------------------|
| 1 | Clean architecture | Whole guide — layered packages (§2) |
| 2 | Asynchronous processing | M5 `service` (submit → `202`) |
| 3 | Bounded concurrency | M4 `worker` (`WorkerPool`) |
| 4 | Producer–consumer | M4 `worker` (bounded queue + pool) |
| 5 | Retry & backoff | M3 `client` (`RetryingInferenceClient`) |
| 6 | Thread safety | M2 `repository`, M4/M5 (atomics, concurrent maps) |
| 7 | Result aggregation | M5 `service` (`Aggregator`) |
| 8 | Batch state tracking | M1 `model` + M2 `repository` |
| 9 | REST API design | M6 `api` (`BatchResource`) |
| 10 | Mock external client | M3 `client` |
| 11 | Configuration | M0 `config` + `config.yml` |
| 12 | Logging | M7 (cross-cutting) |
| 13 | Docker | M9 (skeleton already has it) |
| 14 | Testing | M8 (`src/test`) |
| 15 | CI/CD | M9 (`.github/workflows`) |
| 16 | Documentation | This file + `SOLUTIONING.md` + README |
| 17 | Architecture diagram | `SOLUTIONING.md` §4.1 + README |
| 18 | Future extensions | `SOLUTIONING.md` §8 |

---

## 1. Milestone map (build order)

```
M0  Configuration knobs        →  config/ + PromptBatchConfiguration + config.yml
M1  Domain model               →  model/
M2  Repository (state)         →  repository/
M3  Inference client + retry   →  client/
M4  Worker pool (concurrency)  →  worker/
M5  Service + aggregator       →  service/
M6  REST API                   →  api/
M7  Wiring + logging           →  PromptBatchApplication, exception/
M8  Tests                      →  src/test/...
M9  Docker + CI                →  Dockerfile (exists), .github/workflows/ci.yml
```

Each milestone below has: **goal → files → code → why → verify**.

---

## 2. Target package structure

Create these packages under `src/main/java/com/example/promptbatch/`. This is the
"clean architecture" the interviewer is looking for (area 1) — one responsibility per layer,
dependencies pointing **downward only** (`api → service → worker → client`, everyone can use
`model`; nobody depends on `api`).

```
com/example/promptbatch/
├── PromptBatchApplication.java      # entry point + wiring (exists)
├── PromptBatchConfiguration.java    # root config (exists, extend it)
├── api/                             # Jersey REST resources (HTTP only, no business logic)
│   ├── BatchResource.java
│   └── dto/                         # request/response payloads
│       ├── CreateBatchRequest.java
│       ├── CreateBatchResponse.java
│       ├── BatchProgressResponse.java
│       └── BatchResultsResponse.java
├── config/                          # typed config blocks
│   ├── WorkerPoolConfig.java
│   ├── RetryConfig.java
│   └── MockEndpointConfig.java
├── model/                           # domain types (POJOs/enums), thread-safe where shared
│   ├── Batch.java
│   ├── BatchStatus.java
│   ├── Prompt.java
│   ├── PromptResult.java
│   └── PromptOutcome.java
├── repository/                      # state storage (in-memory for v1)
│   └── BatchRepository.java
├── client/                          # external I/O: the mock inference endpoint + retry
│   ├── InferenceClient.java
│   ├── MockInferenceClient.java
│   ├── RetryingInferenceClient.java
│   ├── InferenceResponse.java
│   ├── RateLimitedException.java
│   └── Sleeper.java
├── worker/                          # concurrency: pool, task, manager
│   ├── WorkerPool.java
│   └── PromptTask.java
├── service/                         # orchestration + aggregation (the brains)
│   ├── BatchService.java
│   └── Aggregator.java
├── health/                          # health checks (PingHealthCheck exists)
│   └── WorkerPoolHealthCheck.java
└── exception/                       # mapped errors
    └── BatchNotFoundException.java
```

> Keep `resources/PingResource.java` as-is (or move it under `api/`); it's your liveness
> proof and a template for a Jersey resource.

---

## 3. M0 — Configuration knobs (area 11)

**Goal:** externalize every concurrency/retry/mock value to `config.yml` so behavior is
tunable without a rebuild.

**Files:** `config/WorkerPoolConfig.java`, `config/RetryConfig.java`,
`config/MockEndpointConfig.java`, extend `PromptBatchConfiguration.java`, extend `config.yml`.

```java
// config/WorkerPoolConfig.java
package com.example.promptbatch.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;

public class WorkerPoolConfig {
    @Min(1) @JsonProperty private int size = 8;              // N: max prompts in flight
    @Min(1) @JsonProperty private int queueCapacity = 10_000; // bounded backlog

    public int getSize() { return size; }
    public int getQueueCapacity() { return queueCapacity; }
}
```

```java
// config/RetryConfig.java
package com.example.promptbatch.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;

public class RetryConfig {
    @Min(0) @JsonProperty private int maxRetries = 4;
    @Min(1) @JsonProperty private long baseDelayMs = 500;
    @Min(1) @JsonProperty private long maxDelayMs = 5_000;
    @JsonProperty private boolean jitter = true;

    public int getMaxRetries() { return maxRetries; }
    public long getBaseDelayMs() { return baseDelayMs; }
    public long getMaxDelayMs() { return maxDelayMs; }
    public boolean isJitter() { return jitter; }
}
```

```java
// config/MockEndpointConfig.java
package com.example.promptbatch.config;

import com.fasterxml.jackson.annotation.JsonProperty;

public class MockEndpointConfig {
    @JsonProperty private double rateLimitProbability = 0.3; // chance of a 429
    @JsonProperty private long baseLatencyMs = 150;

    public double getRateLimitProbability() { return rateLimitProbability; }
    public long getBaseLatencyMs() { return baseLatencyMs; }
}
```

Wire them into the root config:

```java
// PromptBatchConfiguration.java (add fields + getters)
@Valid @NotNull @JsonProperty("workerPool")
private WorkerPoolConfig workerPool = new WorkerPoolConfig();

@Valid @NotNull @JsonProperty("retry")
private RetryConfig retry = new RetryConfig();

@Valid @NotNull @JsonProperty("mockEndpoint")
private MockEndpointConfig mockEndpoint = new MockEndpointConfig();

public WorkerPoolConfig getWorkerPool() { return workerPool; }
public RetryConfig getRetry() { return retry; }
public MockEndpointConfig getMockEndpoint() { return mockEndpoint; }
```

Add to `config.yml`:

```yaml
workerPool:
  size: 8
  queueCapacity: 10000

retry:
  maxRetries: 4
  baseDelayMs: 500
  maxDelayMs: 5000
  jitter: true

mockEndpoint:
  rateLimitProbability: 0.3
  baseLatencyMs: 150
```

**Verify:** `mvn -q compile`. The app should still boot with `server config.yml`.

---

## 4. M1 — Domain model (areas 8, 6)

**Goal:** the types that flow through the system. `Batch` holds live, thread-safe counters
so progress reads are cheap and correct.

```java
// model/BatchStatus.java
package com.example.promptbatch.model;

public enum BatchStatus { QUEUED, PROCESSING, COMPLETED, FAILED }
```

```java
// model/PromptOutcome.java
package com.example.promptbatch.model;

public enum PromptOutcome { SUCCESS, FAILED }
```

```java
// model/Prompt.java
package com.example.promptbatch.model;

public record Prompt(String id, String text) {}
```

```java
// model/PromptResult.java
package com.example.promptbatch.model;

public record PromptResult(
        String promptId,
        PromptOutcome outcome,
        String output,        // null on failure
        String failureReason, // null on success
        int attempts) {

    public static PromptResult success(String promptId, String output, int attempts) {
        return new PromptResult(promptId, PromptOutcome.SUCCESS, output, null, attempts);
    }
    public static PromptResult failure(String promptId, String reason, int attempts) {
        return new PromptResult(promptId, PromptOutcome.FAILED, null, reason, attempts);
    }
}
```

```java
// model/Batch.java  — shared mutable state, so it MUST be thread-safe (area 6)
package com.example.promptbatch.model;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class Batch {
    private final String id;
    private final int total;
    private final Instant createdAt = Instant.now();
    private final AtomicReference<Instant> finishedAt = new AtomicReference<>();
    private final AtomicReference<BatchStatus> status =
            new AtomicReference<>(BatchStatus.QUEUED);

    private final AtomicInteger succeeded = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();

    // promptId -> result; concurrent because many workers write at once (area 6, 7)
    private final Map<String, PromptResult> results = new ConcurrentHashMap<>();

    public Batch(String id, int total) {
        this.id = id;
        this.total = total;
    }

    public void recordResult(PromptResult r) {
        results.put(r.promptId(), r);
        if (r.outcome() == PromptOutcome.SUCCESS) succeeded.incrementAndGet();
        else failed.incrementAndGet();
        if (succeeded.get() + failed.get() == total) {
            finishedAt.compareAndSet(null, Instant.now());
            status.set(BatchStatus.COMPLETED);
        }
    }

    public int completed()  { return succeeded.get() + failed.get(); }
    public double percent()  { return total == 0 ? 100.0 : (completed() * 100.0) / total; }

    public String id() { return id; }
    public int total() { return total; }
    public int succeeded() { return succeeded.get(); }
    public int failed() { return failed.get(); }
    public BatchStatus status() { return status.get(); }
    public void status(BatchStatus s) { status.set(s); }
    public Instant createdAt() { return createdAt; }
    public Instant finishedAt() { return finishedAt.get(); }
    public Map<String, PromptResult> results() { return results; }
}
```

**Why:** counters are `AtomicInteger`, results is a `ConcurrentHashMap`, and completion is
detected atomically — so N workers can update the same batch without locks and progress reads
never block (areas 6, 7, 8).

**Verify:** `mvn -q compile`.

---

## 5. M2 — Repository (areas 6, 8)

**Goal:** one thread-safe place that owns all batches.

```java
// repository/BatchRepository.java
package com.example.promptbatch.repository;

import com.example.promptbatch.model.Batch;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class BatchRepository {
    private final Map<String, Batch> batches = new ConcurrentHashMap<>();

    public void save(Batch batch) { batches.put(batch.id(), batch); }
    public Optional<Batch> find(String id) { return Optional.ofNullable(batches.get(id)); }
}
```

**Why:** a `ConcurrentHashMap` keyed by `batchId` is enough for v1; the interface is the seam
you'd later swap for Postgres (`SOLUTIONING.md` §8). No `HashMap`/`ArrayList` for shared state
(area 6).

**Verify:** `mvn -q compile`.

---

## 6. M3 — Inference client + retry/backoff (areas 5, 10)

This is one of the most heavily weighted areas. Keep **all HTTP details** here so business
logic never sees them (area 10), and make retry **deterministically testable** by injecting a
`Sleeper` and a `Random` (area 14).

```java
// client/InferenceResponse.java
package com.example.promptbatch.client;

public record InferenceResponse(String output) {}
```

```java
// client/RateLimitedException.java  — models a 429 (or retryable 5xx)
package com.example.promptbatch.client;

public class RateLimitedException extends RuntimeException {
    public RateLimitedException(String message) { super(message); }
}
```

```java
// client/InferenceClient.java  — the abstraction workers depend on
package com.example.promptbatch.client;

import com.example.promptbatch.model.Prompt;

public interface InferenceClient {
    InferenceResponse infer(Prompt prompt); // throws RateLimitedException on 429/5xx
}
```

```java
// client/Sleeper.java  — inject a fake in tests so backoff is instant + assertable (area 14)
package com.example.promptbatch.client;

@FunctionalInterface
public interface Sleeper {
    void sleep(long millis) throws InterruptedException;

    Sleeper REAL = Thread::sleep;
}
```

### 6.1 The mock endpoint

For v1 the "external API" can be a local component that *simulates* latency and 429s. (You can
later swap it for a real HTTP call using Dropwizard's `HttpClientBuilder` without touching
callers.)

```java
// client/MockInferenceClient.java
package com.example.promptbatch.client;

import com.example.promptbatch.config.MockEndpointConfig;
import com.example.promptbatch.model.Prompt;
import java.util.concurrent.ThreadLocalRandom;

public class MockInferenceClient implements InferenceClient {
    private final MockEndpointConfig config;

    public MockInferenceClient(MockEndpointConfig config) { this.config = config; }

    @Override
    public InferenceResponse infer(Prompt prompt) {
        try {
            Thread.sleep(config.getBaseLatencyMs()); // simulate network latency
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted", e);
        }
        if (ThreadLocalRandom.current().nextDouble() < config.getRateLimitProbability()) {
            throw new RateLimitedException("429 Too Many Requests for " + prompt.id());
        }
        return new InferenceResponse("echo:" + prompt.text());
    }
}
```

### 6.2 The retry/backoff decorator

Wrap the raw client in a decorator that implements **bounded exponential backoff with full
jitter**. This is the piece the interviewer will read most carefully.

```java
// client/RetryingInferenceClient.java
package com.example.promptbatch.client;

import com.example.promptbatch.config.RetryConfig;
import com.example.promptbatch.model.Prompt;
import java.util.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RetryingInferenceClient implements InferenceClient {
    private static final Logger LOG = LoggerFactory.getLogger(RetryingInferenceClient.class);

    private final InferenceClient delegate; // the real/mock transport
    private final RetryConfig config;
    private final Sleeper sleeper;
    private final Random random;

    public RetryingInferenceClient(InferenceClient delegate, RetryConfig config,
                                   Sleeper sleeper, Random random) {
        this.delegate = delegate;
        this.config = config;
        this.sleeper = sleeper;
        this.random = random;
    }

    @Override
    public InferenceResponse infer(Prompt prompt) {
        int attempt = 0;
        while (true) {
            try {
                return delegate.infer(prompt);            // success → return immediately
            } catch (RateLimitedException e) {
                if (attempt >= config.getMaxRetries()) {  // give up on THIS prompt only
                    throw new RateLimitedException(
                        "retries exhausted after " + (attempt + 1) + " attempts: "
                        + e.getMessage());
                }
                long delay = backoffDelay(attempt);
                LOG.warn("429 for prompt {} (attempt {}/{}), backing off {}ms",
                        prompt.id(), attempt + 1, config.getMaxRetries() + 1, delay);
                sleepQuietly(delay);
                attempt++;
            }
        }
    }

    // min(base * 2^attempt, maxDelay), then full jitter: random(0, delay]
    long backoffDelay(int attempt) {
        long exp = config.getBaseDelayMs() * (1L << attempt);
        long capped = Math.min(exp, config.getMaxDelayMs());
        if (!config.isJitter()) return capped;
        return 1 + (long) (random.nextDouble() * capped);
    }

    private void sleepQuietly(long millis) {
        try {
            sleeper.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RateLimitedException("interrupted during backoff");
        }
    }
}
```

**Why this shape (map to area 5):**
- **Retry limit** — `attempt >= maxRetries` stops the loop; the prompt fails, the batch lives on.
- **Exponential backoff** — `base * 2^attempt`, capped by `maxDelay`.
- **Full jitter** — `random(0, delay]` avoids the thundering herd.
- **Logging** — every retry logs the prompt id, attempt, and delay (area 12).
- **No dropped prompts** — exhaustion throws a typed exception the worker turns into a
  `FAILED` result (recorded, never silently lost).
- **Testable** — `Sleeper` + `Random` are injected, so a test can assert attempt counts and
  growing delays with **zero real waiting** (area 14).

**Verify:** `mvn -q compile`.

---

## 7. M4 — Worker pool: bounded concurrency & producer–consumer (areas 3, 4)

**Goal:** a **single fixed-size** `ThreadPoolExecutor` with a **bounded** queue. This is the
producer (service submitting tasks) / consumer (worker threads) boundary.

```java
// worker/WorkerPool.java
package com.example.promptbatch.worker;

import com.example.promptbatch.config.WorkerPoolConfig;
import io.dropwizard.lifecycle.Managed;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Owns the one shared, bounded executor. Managed so Dropwizard drains it on shutdown. */
public class WorkerPool implements Managed {
    private static final Logger LOG = LoggerFactory.getLogger(WorkerPool.class);

    private final ThreadPoolExecutor executor;

    public WorkerPool(WorkerPoolConfig config) {
        ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger n = new AtomicInteger();
            @Override public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "prompt-worker-" + n.incrementAndGet());
                t.setDaemon(false);
                return t;
            }
        };
        this.executor = new ThreadPoolExecutor(
                config.getSize(), config.getSize(),        // fixed: core == max
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(config.getQueueCapacity()), // BOUNDED queue
                factory,
                new ThreadPoolExecutor.CallerRunsPolicy());  // backpressure, not dropping
    }

    public void submit(Runnable task) { executor.execute(task); }

    public int activeCount() { return executor.getActiveCount(); }
    public int queueSize()   { return executor.getQueue().size(); }

    @Override public void start() { LOG.info("Worker pool started: {} threads",
            executor.getCorePoolSize()); }

    @Override public void stop() throws InterruptedException {
        LOG.info("Shutting down worker pool...");
        executor.shutdown();                                  // stop accepting new tasks
        if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
            LOG.warn("Pool did not drain in time; forcing shutdown");
            executor.shutdownNow();                           // interrupt stragglers
        }
        LOG.info("Worker pool stopped");
    }
}
```

**Why (map to areas 3 & 4):**
- `corePoolSize == maxPoolSize` → **at most N prompts in flight**, ever. No `new Thread()`
  per prompt, no unbounded parallelism.
- `ArrayBlockingQueue` (bounded) → the classic **producer–consumer** buffer with backpressure;
  submitted-but-not-started work is capped so a huge batch can't OOM the JVM.
- `CallerRunsPolicy` → when the queue is full, the **submitting thread runs the task**,
  throttling the producer instead of dropping prompts.
- `implements Managed` + `awaitTermination` → **proper shutdown handling** (in-flight prompts
  drain before exit).

The per-prompt unit of work:

```java
// worker/PromptTask.java  — a Runnable that: call client → build result → report to batch
package com.example.promptbatch.worker;

import com.example.promptbatch.client.InferenceClient;
import com.example.promptbatch.client.RateLimitedException;
import com.example.promptbatch.model.Batch;
import com.example.promptbatch.model.Prompt;
import com.example.promptbatch.model.PromptResult;
import com.example.promptbatch.service.Aggregator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PromptTask implements Runnable {
    private static final Logger LOG = LoggerFactory.getLogger(PromptTask.class);

    private final Batch batch;
    private final Prompt prompt;
    private final InferenceClient client;   // the RETRYING client
    private final Aggregator aggregator;

    public PromptTask(Batch batch, Prompt prompt, InferenceClient client, Aggregator aggregator) {
        this.batch = batch;
        this.prompt = prompt;
        this.client = client;
        this.aggregator = aggregator;
    }

    @Override
    public void run() {
        PromptResult result;
        try {
            var response = client.infer(prompt);
            result = PromptResult.success(prompt.id(), response.output(), 1);
        } catch (RateLimitedException e) {                     // retries exhausted
            result = PromptResult.failure(prompt.id(), e.getMessage(), 0);
        } catch (RuntimeException e) {                          // any other error → contained
            LOG.error("Unexpected error processing prompt {}", prompt.id(), e);
            result = PromptResult.failure(prompt.id(), "error: " + e.getMessage(), 0);
        }
        aggregator.record(batch, result); // exceptions never escape the worker thread
    }
}
```

**Why:** a task **never throws** out of `run()` — any failure becomes a `FAILED` result, so one
bad prompt can't kill a worker thread or the batch (area 5, 7).

**Verify:** `mvn -q compile` (after M5's `Aggregator` exists, or stub it first).

---

## 8. M5 — Service + aggregator: async submit & aggregation (areas 2, 7)

### 8.1 Aggregator

```java
// service/Aggregator.java
package com.example.promptbatch.service;

import com.example.promptbatch.model.Batch;
import com.example.promptbatch.model.PromptResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Safely folds each worker's result into the batch's thread-safe counters. */
public class Aggregator {
    private static final Logger LOG = LoggerFactory.getLogger(Aggregator.class);

    public void record(Batch batch, PromptResult result) {
        batch.recordResult(result); // all atomicity lives in Batch (area 6)
        if (batch.completed() == batch.total()) {
            LOG.info("Batch {} COMPLETED: {} succeeded, {} failed of {}",
                    batch.id(), batch.succeeded(), batch.failed(), batch.total());
        }
    }
}
```

**Why:** the aggregator is a thin, stateless coordinator; the atomic counting lives in `Batch`,
so concurrent `record` calls from N workers are always correct (areas 6, 7).

### 8.2 Service — the async boundary

```java
// service/BatchService.java
package com.example.promptbatch.service;

import com.example.promptbatch.client.InferenceClient;
import com.example.promptbatch.model.Batch;
import com.example.promptbatch.model.BatchStatus;
import com.example.promptbatch.model.Prompt;
import com.example.promptbatch.repository.BatchRepository;
import com.example.promptbatch.worker.PromptTask;
import com.example.promptbatch.worker.WorkerPool;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BatchService {
    private static final Logger LOG = LoggerFactory.getLogger(BatchService.class);

    private final BatchRepository repository;
    private final WorkerPool workerPool;
    private final InferenceClient inferenceClient; // the retrying client
    private final Aggregator aggregator;

    public BatchService(BatchRepository repository, WorkerPool workerPool,
                        InferenceClient inferenceClient, Aggregator aggregator) {
        this.repository = repository;
        this.workerPool = workerPool;
        this.inferenceClient = inferenceClient;
        this.aggregator = aggregator;
    }

    /** Returns immediately after enqueuing work — never blocks on processing (area 2). */
    public Batch submit(List<String> promptTexts) {
        String batchId = "b-" + UUID.randomUUID();
        Batch batch = new Batch(batchId, promptTexts.size());
        batch.status(BatchStatus.PROCESSING);
        repository.save(batch);
        LOG.info("Batch {} received with {} prompts", batchId, promptTexts.size());

        int i = 0;
        for (String text : promptTexts) {
            Prompt prompt = new Prompt(batchId + "-p" + (i++), text);
            workerPool.submit(new PromptTask(batch, prompt, inferenceClient, aggregator));
        }
        LOG.info("Batch {} enqueued to worker pool", batchId);
        return batch; // caller (resource) turns this into 202 + batchId
    }

    public Batch get(String id) {
        return repository.find(id)
            .orElseThrow(() -> new com.example.promptbatch.exception
                .BatchNotFoundException(id));
    }
}
```

**Why (area 2):** `submit` does three cheap things — create record, save, enqueue — then
returns. The heavy work happens on the pool. The HTTP thread is freed immediately, which is
exactly what lets the resource answer `202 Accepted` right away.

> **Backpressure note:** with `CallerRunsPolicy`, if the bounded queue is full the `submit`
> loop itself runs a task inline. That momentarily slows ingestion (intended) but never drops
> a prompt and never spawns extra threads.

**Verify:** `mvn -q compile`.

---

## 9. M6 — REST API (area 9)

Keep resources **thin**: parse/validate input, call the service, map to a DTO. **No business
logic in resources** (area 1).

```java
// api/dto/CreateBatchRequest.java
package com.example.promptbatch.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public class CreateBatchRequest {
    @NotEmpty @JsonProperty private List<String> prompts;
    public List<String> getPrompts() { return prompts; }
    public void setPrompts(List<String> prompts) { this.prompts = prompts; }
}
```

```java
// api/dto/CreateBatchResponse.java
package com.example.promptbatch.api.dto;

public record CreateBatchResponse(String batchId, int total) {}
```

```java
// api/dto/BatchProgressResponse.java
package com.example.promptbatch.api.dto;

import com.example.promptbatch.model.Batch;

public record BatchProgressResponse(
        String batchId, String status, int total, int completed,
        int succeeded, int failed, double percentComplete) {

    public static BatchProgressResponse from(Batch b) {
        return new BatchProgressResponse(b.id(), b.status().name(), b.total(),
                b.completed(), b.succeeded(), b.failed(), b.percent());
    }
}
```

```java
// api/dto/BatchResultsResponse.java
package com.example.promptbatch.api.dto;

import com.example.promptbatch.model.PromptResult;
import java.util.Collection;

public record BatchResultsResponse(String batchId, String status, Collection<PromptResult> results) {}
```

```java
// api/BatchResource.java
package com.example.promptbatch.api;

import com.example.promptbatch.api.dto.*;
import com.example.promptbatch.model.Batch;
import com.example.promptbatch.model.BatchStatus;
import com.example.promptbatch.service.BatchService;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/batches")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class BatchResource {
    private final BatchService service;

    public BatchResource(BatchService service) { this.service = service; }

    @POST
    public Response create(@Valid CreateBatchRequest request) {
        Batch batch = service.submit(request.getPrompts());
        return Response.status(Response.Status.ACCEPTED)          // 202 (area 2, 9)
                .entity(new CreateBatchResponse(batch.id(), batch.total()))
                .build();
    }

    @GET @Path("/{id}")
    public BatchProgressResponse progress(@PathParam("id") String id) {
        return BatchProgressResponse.from(service.get(id));
    }

    @GET @Path("/{id}/results")
    public Response results(@PathParam("id") String id) {
        Batch batch = service.get(id);
        if (batch.status() != BatchStatus.COMPLETED) {
            return Response.status(Response.Status.CONFLICT)      // 409: still running
                    .entity(BatchProgressResponse.from(batch)).build();
        }
        return Response.ok(new BatchResultsResponse(
                batch.id(), batch.status().name(), batch.results().values())).build();
    }
}
```

Map the not-found exception to a clean `404`:

```java
// exception/BatchNotFoundException.java
package com.example.promptbatch.exception;

public class BatchNotFoundException extends RuntimeException {
    public BatchNotFoundException(String id) { super("Batch not found: " + id); }
}
```

```java
// exception/BatchNotFoundExceptionMapper.java (register in run())
package com.example.promptbatch.exception;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;

public class BatchNotFoundExceptionMapper implements ExceptionMapper<BatchNotFoundException> {
    @Override public Response toResponse(BatchNotFoundException e) {
        return Response.status(Response.Status.NOT_FOUND)
                .type(MediaType.APPLICATION_JSON)
                .entity(java.util.Map.of("error", e.getMessage()))
                .build();
    }
}
```

**Verify:** `mvn -q compile`.

---

## 10. M7 — Wiring + logging (areas 1, 12)

Assemble the graph in `run()`. This is the **only** place that knows how the pieces fit,
which keeps every other class independently testable (dependency injection by hand).

```java
// PromptBatchApplication.java (run method)
@Override
public void run(final PromptBatchConfiguration config, final Environment env) {
    // --- construct the object graph (composition root) ---
    BatchRepository repository = new BatchRepository();
    Aggregator aggregator = new Aggregator();

    WorkerPool workerPool = new WorkerPool(config.getWorkerPool());
    env.lifecycle().manage(workerPool);              // graceful shutdown (area 3)

    InferenceClient transport = new MockInferenceClient(config.getMockEndpoint());
    InferenceClient client = new RetryingInferenceClient(
            transport, config.getRetry(), Sleeper.REAL, new java.util.Random());

    BatchService service = new BatchService(repository, workerPool, client, aggregator);

    // --- REST + health + error mapping ---
    env.jersey().register(new PingResource());
    env.jersey().register(new BatchResource(service));
    env.jersey().register(new BatchNotFoundExceptionMapper());
    env.healthChecks().register("ping", new PingHealthCheck());
    env.healthChecks().register("workerPool", new WorkerPoolHealthCheck(workerPool));
}
```

**Logging (area 12).** Use SLF4J (already on the classpath via Dropwizard) and log the
lifecycle events the interviewer lists: *batch received, batch enqueued, worker retry attempts,
batch completed/failed*. Prefer **structured context** — include `batchId`/`promptId` in every
message (or use MDC):

```java
// example: correlate all logs for a batch
org.slf4j.MDC.put("batchId", batchId);
try {
    // ... work ...
} finally {
    org.slf4j.MDC.remove("batchId");
}
```

Add a health check that surfaces pool saturation:

```java
// health/WorkerPoolHealthCheck.java
package com.example.promptbatch.health;

import com.codahale.metrics.health.HealthCheck;
import com.example.promptbatch.worker.WorkerPool;

public class WorkerPoolHealthCheck extends HealthCheck {
    private final WorkerPool pool;
    public WorkerPoolHealthCheck(WorkerPool pool) { this.pool = pool; }

    @Override protected Result check() {
        return Result.healthy("active=%d, queued=%d"
                .formatted(pool.activeCount(), pool.queueSize()));
    }
}
```

**Verify:** `mvn -q package`, then `docker compose up --build` and exercise the API:

```bash
# submit a batch → expect 202 + batchId
curl -s -X POST localhost:8080/batches \
  -H 'Content-Type: application/json' \
  -d '{"prompts":["hello","world","foo","bar"]}'

# poll progress
curl -s localhost:8080/batches/<batchId>

# fetch results once COMPLETED
curl -s localhost:8080/batches/<batchId>/results
```

---

## 11. M8 — Testing (area 14)

Cover the four areas the brief calls out. Dependencies (JUnit 5, AssertJ, Mockito,
dropwizard-testing) are already in `pom.xml`.

### 11.1 Retry logic (the highest-value test)

Inject a fake `Sleeper` (records delays, never actually sleeps) and a stub transport that
returns a scripted sequence.

```java
// src/test/java/com/example/promptbatch/client/RetryingInferenceClientTest.java
class RetryingInferenceClientTest {

    private final RetryConfig config = new RetryConfig(); // maxRetries=4, base=500, max=5000

    @Test
    void succeedsAfterTransient429s() {
        var delays = new java.util.ArrayList<Long>();
        Sleeper fakeSleeper = delays::add;                       // no real sleeping

        InferenceClient transport = mock(InferenceClient.class);
        when(transport.infer(any()))
            .thenThrow(new RateLimitedException("429"))
            .thenThrow(new RateLimitedException("429"))
            .thenReturn(new InferenceResponse("ok"));

        var client = new RetryingInferenceClient(
                transport, config, fakeSleeper, new Random(1));

        var res = client.infer(new Prompt("p1", "hi"));

        assertThat(res.output()).isEqualTo("ok");
        verify(transport, times(3)).infer(any());               // 2 failures + 1 success
        assertThat(delays).hasSize(2);                          // slept twice
    }

    @Test
    void failsAfterMaxRetries() {
        InferenceClient transport = mock(InferenceClient.class);
        when(transport.infer(any())).thenThrow(new RateLimitedException("429"));

        var client = new RetryingInferenceClient(
                transport, config, millis -> {}, new Random(1));

        assertThatThrownBy(() -> client.infer(new Prompt("p1", "hi")))
                .isInstanceOf(RateLimitedException.class)
                .hasMessageContaining("retries exhausted");
        verify(transport, times(config.getMaxRetries() + 1)).infer(any());
    }

    @Test
    void backoffGrowsExponentiallyWhenJitterOff() {
        var cfg = new RetryConfig(); // set jitter=false via a test constructor/reflection
        var client = new RetryingInferenceClient(mock(InferenceClient.class), cfg,
                m -> {}, new Random(1));
        assertThat(client.backoffDelay(0)).isEqualTo(500);
        assertThat(client.backoffDelay(1)).isEqualTo(1000);
        assertThat(client.backoffDelay(2)).isEqualTo(2000);
        assertThat(client.backoffDelay(10)).isEqualTo(5000);    // capped at maxDelay
    }
}
```

> Tip: give `RetryConfig` a test-only all-args constructor (or use a builder) so you can flip
> `jitter=false` and assert exact delays.

### 11.2 Worker logic

```java
// PromptTaskTest — prompt processed / exceptions handled / aggregator updated
@Test
void recordsSuccess() {
    var batch = new Batch("b1", 1);
    InferenceClient client = p -> new InferenceResponse("ok");
    var agg = new Aggregator();
    new PromptTask(batch, new Prompt("b1-p0", "hi"), client, agg).run();
    assertThat(batch.succeeded()).isEqualTo(1);
    assertThat(batch.status()).isEqualTo(BatchStatus.COMPLETED);
}

@Test
void recordsFailureWhenExhausted() {
    var batch = new Batch("b1", 1);
    InferenceClient client = p -> { throw new RateLimitedException("exhausted"); };
    new PromptTask(batch, new Prompt("b1-p0", "hi"), client, new Aggregator()).run();
    assertThat(batch.failed()).isEqualTo(1);
}
```

### 11.3 Aggregator concurrency

Fire many threads at one `Batch` and assert the totals are exact (no lost updates → proves
thread safety, area 6).

```java
@Test
void concurrentUpdatesAreConsistent() throws Exception {
    var batch = new Batch("b1", 1000);
    var agg = new Aggregator();
    var pool = java.util.concurrent.Executors.newFixedThreadPool(16);
    for (int i = 0; i < 1000; i++) {
        final int id = i;
        pool.submit(() -> agg.record(batch,
            PromptResult.success("b1-p" + id, "ok", 1)));
    }
    pool.shutdown();
    pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);
    assertThat(batch.succeeded()).isEqualTo(1000);
    assertThat(batch.status()).isEqualTo(BatchStatus.COMPLETED);
}
```

### 11.4 Batch service / integration

```java
// BatchServiceTest — submit returns fast, progress advances to completion
@Test
void submitReturnsImmediatelyThenCompletes() {
    var repo = new BatchRepository();
    var pool = new WorkerPool(new WorkerPoolConfig());
    pool.start();
    InferenceClient client = p -> new InferenceResponse("ok"); // no failures
    var service = new BatchService(repo, pool, client, new Aggregator());

    var batch = service.submit(java.util.List.of("a", "b", "c"));
    assertThat(batch.total()).isEqualTo(3);                    // returned right away

    await().atMost(5, SECONDS).until(() ->
        service.get(batch.id()).status() == BatchStatus.COMPLETED);
}
```

Optionally add a full HTTP test with `DropwizardAppExtension` asserting `POST /batches` → `202`.

**Verify:** `mvn -q verify` — all green.

---

## 12. M9 — Docker & CI/CD (areas 13, 15)

**Docker (area 13)** already works — `docker compose up --build` builds a fat jar in a
`maven` stage and runs it on a slim JRE. Nothing to change unless you add config/env vars.

**CI (area 15):** add `.github/workflows/ci.yml`:

```yaml
name: CI
on:
  push: { branches: [ main ] }
  pull_request:

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
          cache: maven
      - name: Build + test
        run: mvn -B verify
      - name: Build Docker image
        run: docker build -t prompt-batch-service:ci .
      - name: Smoke test container
        run: |
          docker run -d --rm -p 8080:8080 -p 8081:8081 \
            --name pbs prompt-batch-service:ci
          for i in $(seq 1 20); do
            curl -sf localhost:8080/ping && break || sleep 2
          done
          curl -sf localhost:8080/ping
          docker stop pbs
```

**Verify:** push a branch, open a PR, confirm the workflow goes green.

---

## 13. Final verification checklist

Run through this before calling it done — it mirrors the evaluation rubric:

- [ ] `POST /batches` returns **`202`** with a `batchId` **immediately** (area 2).
- [ ] Processing continues in the background; `GET /batches/{id}` shows `completed` rising
      then `status: COMPLETED` (areas 2, 8).
- [ ] With `mockEndpoint.rateLimitProbability > 0`, logs show **retry/backoff** attempts and
      the batch still completes with **no dropped prompts** (area 5).
- [ ] Worker count is **fixed** and configurable; no `new Thread()` per prompt (areas 3, 11).
- [ ] Shared state uses `ConcurrentHashMap`/atomics only — no plain `HashMap`/`ArrayList`
      for shared mutable state (area 6).
- [ ] `GET /batches/{id}/results` returns the aggregated per-prompt outcomes (area 7).
- [ ] Business/service code contains **no HTTP details** — those live only in `client/`
      (areas 1, 10).
- [ ] `mvn verify` runs unit tests for retry, worker, aggregator, and service (area 14).
- [ ] `docker compose up --build` runs the whole thing with one command (area 13).
- [ ] GitHub Actions builds + tests on every PR (area 15).
- [ ] README + `SOLUTIONING.md` + this guide document the design, run steps, and trade-offs
      (areas 16, 17, 18).

---

## 14. Common pitfalls (what loses points)

| Pitfall | Fix |
|---------|-----|
| Doing inference work inside the resource / service call thread | Enqueue to the pool and return `202` immediately. |
| `Executors.newCachedThreadPool()` or `new Thread()` per prompt | Fixed-size `ThreadPoolExecutor` with `core == max`. |
| Unbounded `LinkedBlockingQueue` | Bounded `ArrayBlockingQueue` + `CallerRunsPolicy`. |
| `Thread.sleep` hard-coded in retry → untestable | Inject a `Sleeper`; assert delays in tests. |
| Retry with no jitter | `random(0, delay]` full jitter to avoid thundering herd. |
| One failing prompt fails the whole batch | Catch in `PromptTask`, record `FAILED`, keep going. |
| `HashMap`/`ArrayList` for shared counters/results | `ConcurrentHashMap`, `AtomicInteger`. |
| No graceful shutdown | `implements Managed` + `awaitTermination` → `shutdownNow`. |
| HTTP/retry logic leaking into the service | Keep it behind the `InferenceClient` interface. |

---

### TL;DR build order

`config → model → repository → client (+retry) → worker pool → service (+aggregator) →
REST resources → wire in Application → tests → CI`. Keep it compiling and green after every
milestone, and never let the HTTP thread block on processing.
