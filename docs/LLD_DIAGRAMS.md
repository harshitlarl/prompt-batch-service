# LLD Diagrams — Prompt Batch Service

> **Purpose.** [`LLD.md`](LLD.md) describes the seams and contracts in prose; this document
> is the **visual companion**: every diagram here is drawn directly from the classes actually
> checked into `src/main/java/com/example/promptbatch` (not just the design intent), so you can
> use it as a map while reading the code.
>
> Render these with any Mermaid-compatible viewer (GitHub renders them natively).

## Table of contents

1. [Package / dependency diagram](#1-package--dependency-diagram)
2. [Class diagram — domain model](#2-class-diagram--domain-model)
3. [Class diagram — seams & implementations](#3-class-diagram--seams--implementations)
4. [Sequence — submit a batch (`POST /batches`)](#4-sequence--submit-a-batch-post-batches)
5. [Sequence — retry with backoff on `429`](#5-sequence--retry-with-backoff-on-429)
6. [Sequence — file upload (`POST /batches/upload`)](#6-sequence--file-upload-post-batchesupload)
7. [State diagram — `Batch` lifecycle](#7-state-diagram--batch-lifecycle)
8. [Concurrency diagram — bounded worker pool](#8-concurrency-diagram--bounded-worker-pool)
9. [Composition root — object graph](#9-composition-root--object-graph)
10. [Component diagram — runtime view](#10-component-diagram--runtime-view)

---

## 1. Package / dependency diagram

Dependencies point one way only: `api → service → {worker, repository, store, ingest} →
client → model/config`. Nothing depends on `api`, and `model`/`config` depend on nothing
internal.

```mermaid
flowchart TD
    API[api<br/><i>BatchResource, DTOs</i>] --> SVC[service<br/><i>BatchService, Aggregator</i>]
    SVC --> WRK[worker<br/><i>TaskExecutor, PromptTask</i>]
    SVC --> REPO[repository<br/><i>BatchRepository</i>]
    SVC --> STORE[store<br/><i>ResultStore</i>]
    SVC --> ING[ingest<br/><i>PromptSource</i>]
    WRK --> CLI[client<br/><i>InferenceClient + retry</i>]
    ING --> MODEL[model]
    CLI --> CFG[config]
    API --> MODEL
    SVC --> MODEL
    WRK --> MODEL
    CLI --> MODEL
    REPO --> MODEL
    STORE --> MODEL
    EXC[exception] -.mapped by.-> API
    HEALTH[health] --> WRK

    classDef leaf fill:#eef,stroke:#88a
    class MODEL,CFG leaf
```

---

## 2. Class diagram — domain model

`Batch` is the only shared *mutable* type; everything else is an immutable record.

```mermaid
classDiagram
    class BatchStatus {
        <<enumeration>>
        QUEUED
        PROCESSING
        COMPLETED
        FAILED
    }

    class PromptOutcome {
        <<enumeration>>
        SUCCESS
        FAILED
    }

    class Prompt {
        <<record>>
        +String id
        +String text
    }

    class PromptResult {
        <<record>>
        +String promptId
        +PromptOutcome outcome
        +String output
        +String failureReason
        +int attempts
        +success(id, output, attempts) PromptResult
        +failure(id, reason, attempts) PromptResult
        +isSuccess() boolean
    }

    class Batch {
        -String id
        -int total
        -Instant createdAt
        -AtomicReference~Instant~ finishedAt
        -AtomicReference~BatchStatus~ status
        -AtomicInteger succeeded
        -AtomicInteger failed
        -ConcurrentHashMap~String,PromptResult~ results
        +recordResult(PromptResult) boolean
        +completed() int
        +percent() double
        +status() BatchStatus
    }

    Batch "1" --> "*" PromptResult : results
    PromptResult --> PromptOutcome
    Batch --> BatchStatus
    Batch "1" ..> "*" Prompt : one task per prompt
```

**Invariant enforced in code (`Batch.recordResult`):** `completed() == succeeded() +
failed()` always, and the `PROCESSING → COMPLETED` transition is a `compareAndSet` — so
exactly one caller (across all worker threads) ever gets `true` back and is responsible for
finalizing the result store. This is unit-tested in `BatchTest` and
`CompletionAggregatorTest` with 500–1000 concurrent writers.

---

## 3. Class diagram — seams & implementations

The seven interfaces (seams) that make every layer swappable, and the v1 implementation
wired behind each one in `PromptBatchApplication.run(...)`.

```mermaid
classDiagram
    direction LR

    class InferenceClient {
        <<interface>>
        +infer(Prompt) InferenceResponse
    }
    class BackoffStrategy {
        <<interface>>
        +delayMillis(int attempt) long
    }
    class RateLimiter {
        <<interface>>
        +acquire() void
    }
    class TaskExecutor {
        <<interface>>
        +submit(Runnable) void
        +activeCount() int
        +queueSize() int
    }
    class BatchRepository {
        <<interface>>
        +save(Batch) void
        +find(String) Optional~Batch~
    }
    class ResultStore {
        <<interface>>
        +write(String, PromptResult) void
        +finalizeBatch(Batch) void
        +read(String) Optional~BatchResults~
    }
    class PromptSource {
        <<interface>>
        +parse(String, InputStream) List~Prompt~
        +supports(String) boolean
    }

    class RetryingInferenceClient
    class MockInferenceClient
    class ExponentialJitterBackoff
    class NoopRateLimiter
    class ThreadPoolTaskExecutor
    class InMemoryBatchRepository
    class InMemoryResultStore
    class JsonFileResultStore
    class JsonPromptSource
    class LinePromptSource

    InferenceClient <|.. RetryingInferenceClient : decorates
    InferenceClient <|.. MockInferenceClient
    RetryingInferenceClient --> InferenceClient : delegate
    RetryingInferenceClient --> BackoffStrategy
    RetryingInferenceClient --> RateLimiter
    BackoffStrategy <|.. ExponentialJitterBackoff
    RateLimiter <|.. NoopRateLimiter
    TaskExecutor <|.. ThreadPoolTaskExecutor
    BatchRepository <|.. InMemoryBatchRepository
    ResultStore <|.. InMemoryResultStore
    ResultStore <|.. JsonFileResultStore
    PromptSource <|.. JsonPromptSource
    PromptSource <|.. LinePromptSource
```

---

## 4. Sequence — submit a batch (`POST /batches`)

Shows the async boundary in `BatchService.submit(...)`: the HTTP thread returns `202` before
any inference call happens.

```mermaid
sequenceDiagram
    autonumber
    participant Client
    participant Res as BatchResource
    participant Svc as BatchService
    participant Repo as BatchRepository
    participant Exec as TaskExecutor
    participant Task as PromptTask
    participant IC as RetryingInferenceClient
    participant Agg as CompletionAggregator
    participant Store as ResultStore

    Client->>Res: POST /batches {prompts:[...]}
    Res->>Svc: submit(promptTexts)
    Svc->>Svc: batchId = "b-" + UUID
    Svc->>Repo: save(new Batch(batchId, total))
    loop for each prompt
        Svc->>Exec: submit(new PromptTask(...))
    end
    Svc-->>Res: Batch (status=PROCESSING)
    Res-->>Client: 202 Accepted {batchId, total}

    Note over Exec,Task: work happens on the bounded pool, off the HTTP thread

    par one task per prompt (bounded by pool size N)
        Exec->>Task: run()
        Task->>IC: infer(prompt)
        IC-->>Task: InferenceResponse | RateLimitedException
        Task->>Agg: record(batch, result)
        Agg->>Store: write(batchId, result)
        Agg->>Agg: batch.recordResult(result)
        alt this call completed the batch (CAS)
            Agg->>Store: finalizeBatch(batch)
        end
    end

    Client->>Res: GET /batches/{id}
    Res->>Svc: get(id)
    Svc->>Repo: find(id)
    Repo-->>Svc: Batch
    Svc-->>Res: Batch
    Res-->>Client: 200 {status, completed, succeeded, failed, percentComplete}
```

---

## 5. Sequence — retry with backoff on `429`

Exactly what `RetryingInferenceClient.infer(...)` does, matching
`RetryingInferenceClientTest`.

```mermaid
sequenceDiagram
    autonumber
    participant Task as PromptTask
    participant RC as RetryingInferenceClient
    participant RL as RateLimiter
    participant BO as BackoffStrategy
    participant SL as Sleeper
    participant Mock as MockInferenceClient

    Task->>RC: infer(prompt)
    loop attempt = 0..maxRetries
        RC->>RL: acquire()
        RC->>Mock: infer(prompt)
        alt 200 OK
            Mock-->>RC: InferenceResponse
            RC-->>Task: InferenceResponse (SUCCESS)
        else RateLimitedException (429 / retryable)
            Mock-->>RC: throw RateLimitedException
            alt attempt == maxRetries
                RC-->>Task: throw RateLimitedException("retries exhausted")
            else attempt < maxRetries
                RC->>BO: delayMillis(attempt)
                BO-->>RC: delay (min(base*2^attempt, maxDelay), jittered)
                RC->>SL: sleep(delay)
                RC->>RC: attempt++
            end
        else NonRetryableInferenceException (e.g. 4xx)
            Mock-->>RC: throw NonRetryableInferenceException
            RC-->>Task: throw immediately (not retried)
        end
    end
    Note over Task: PromptTask.run() never lets an exception escape -\nit becomes PromptResult.failure(...) and the batch keeps going
```

---

## 6. Sequence — file upload (`POST /batches/upload`)

Shows the `ingest` seam: the resource never branches on content type itself, it delegates to
`PromptSourceRegistry`.

```mermaid
sequenceDiagram
    autonumber
    participant Client
    participant Res as BatchResource
    participant Reg as PromptSourceRegistry
    participant Src as PromptSource
    participant Svc as BatchService

    Client->>Res: POST /batches/upload (body, Content-Type)
    Res->>Reg: select(contentType)
    Reg->>Reg: find first source where supports(contentType)
    Reg-->>Res: PromptSource (Json or Line)
    Res->>Svc: submitFromSource(source, body)
    Svc->>Svc: batchId = "b-" + UUID
    Svc->>Src: parse(batchId, body)
    Src-->>Svc: List~Prompt~
    Svc->>Svc: enqueue(batchId, prompts)  (same path as JSON submit)
    Svc-->>Res: Batch
    Res-->>Client: 202 Accepted {batchId, total}
```

---

## 7. State diagram — `Batch` lifecycle

Enforced by `Batch.status` (an `AtomicReference<BatchStatus>`) — monotonic, no
back-transitions, `PROCESSING → COMPLETED` guarded by CAS so it fires exactly once.

```mermaid
stateDiagram-v2
    [*] --> QUEUED : new Batch(id, total)
    QUEUED --> PROCESSING : BatchService.submit()\n(status set before enqueue)
    PROCESSING --> PROCESSING : recordResult()\n(completed < total)
    PROCESSING --> COMPLETED : recordResult() pushes\ncompleted == total\n(CAS, exactly once)
    COMPLETED --> [*]

    note right of COMPLETED
        finishedAt set exactly once (CAS)
        ResultStore.finalizeBatch(batch) called
        exactly once by the completing thread
    end note
```

---

## 8. Concurrency diagram — bounded worker pool

The producer–consumer boundary implemented by `ThreadPoolTaskExecutor`
(`corePoolSize == maxPoolSize`, bounded `ArrayBlockingQueue`, `CallerRunsPolicy`).

```mermaid
flowchart LR
    subgraph Producer
        SVC[BatchService.submit]
    end

    subgraph Executor["ThreadPoolTaskExecutor (N = workerPool.size)"]
        Q[["ArrayBlockingQueue\ncapacity = queueCapacity"]]
        subgraph Pool["Fixed pool: N threads, always"]
            W1[prompt-worker-1]
            W2[prompt-worker-2]
            Wn[prompt-worker-N]
        end
    end

    IC[RetryingInferenceClient]
    AGG[CompletionAggregator]

    SVC -->|submit PromptTask| Q
    Q --> W1 & W2 & Wn
    W1 & W2 & Wn --> IC
    IC --> AGG

    SVC -. "queue full AND pool busy\n=> CallerRunsPolicy runs task\non the calling thread (backpressure)" .-> SVC

    classDef bounded fill:#fee,stroke:#a88
    class Q,Pool bounded
```

**Verified by `ThreadPoolTaskExecutorTest`:** submitting far more tasks than `N` never lets
observed in-flight concurrency exceed `N`, and all tasks still reach a terminal state even
when the queue is deliberately small (backpressure via `CallerRunsPolicy`, not drops).

---

## 9. Composition root — object graph

Everything below is wired in exactly one place: `PromptBatchApplication.run(...)`. This is
the only class that names concrete implementations.

```mermaid
flowchart TB
    CFG[PromptBatchConfiguration] --> APP[PromptBatchApplication.run]

    APP -->|new| REPO[InMemoryBatchRepository]
    APP -->|new, based on store.type| STORE[InMemoryResultStore\nor JsonFileResultStore]
    APP -->|new| AGG[CompletionAggregator]
    APP -->|new + env.lifecycle&#40;&#41;.manage| EXEC[ThreadPoolTaskExecutor]
    APP -->|new| RL[NoopRateLimiter]
    APP -->|new| BO[ExponentialJitterBackoff]
    APP -->|new| TRANSPORT[MockInferenceClient]
    APP -->|new, wraps transport| RIC[RetryingInferenceClient]
    APP -->|new| REG[PromptSourceRegistry\nJson + Line sources]
    APP -->|new| SVC[BatchService]
    APP -->|register| RES[BatchResource]
    APP -->|register| MAPPERS[Exception mappers]
    APP -->|register| HC[WorkerPoolHealthCheck]

    AGG --> STORE
    SVC --> REPO
    SVC --> EXEC
    SVC --> RIC
    SVC --> AGG
    RIC --> TRANSPORT
    RIC --> RL
    RIC --> BO
    RES --> SVC
    RES --> REG
    HC --> EXEC
```

---

## 10. Component diagram — runtime view

How the pieces map onto the running Dropwizard process and its two ports (matches
`config/config-*.yml`).

```mermaid
flowchart TB
    subgraph Client
        C[HTTP client / curl]
    end

    subgraph JVM["Dropwizard JVM (single container)"]
        direction TB
        subgraph AppPort["Application port (8080)"]
            PING["/ping"]
            BATCHES["/batches, /batches/upload,\n/batches/{id}, /batches/{id}/results"]
        end
        subgraph AdminPort["Admin port (8081)"]
            HEALTH["/healthcheck\n(ping, workerPool)"]
            TASKS["/tasks/log-level, /tasks/gc"]
        end
        SVC[BatchService + CompletionAggregator]
        POOL[ThreadPoolTaskExecutor\nN worker threads]
        REG[InMemoryBatchRepository]
        RS[ResultStore\nin-memory / json-file]
        MOCK[MockInferenceClient\n+ RetryingInferenceClient]
    end

    C -->|202 + batchId| BATCHES
    BATCHES --> SVC
    SVC --> POOL
    POOL --> MOCK
    SVC --> REG
    SVC --> RS
    HEALTH --> POOL
```
