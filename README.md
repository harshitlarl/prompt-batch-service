# prompt-batch-service

A Java [Dropwizard](https://www.dropwizard.io/) backend service that ingests **batches of
AI prompts** (via JSON or file upload), **acknowledges immediately** (`202 Accepted` +
`batchId`), processes them **concurrently** against a mock, rate-limited inference endpoint,
**survives rate limiting** by retrying with exponential backoff + jitter, **aggregates**
results into a final JSON output, and exposes **live progress** while the batch is running.

The interesting engineering here isn't the AI call (it's mocked) — it's the **concurrency
discipline**: a bounded worker pool, backpressure, correct retry/backoff, and accurate
aggregation under partial failure.

> **Status: implemented (v1).** Batch ingestion, the bounded worker pool, retry/backoff,
> result aggregation, and the progress/results API are all built and unit-tested. See
> [What's implemented](#whats-implemented) below for the full list.



## Design & architecture docs

The docs build up in four layers:

- `[docs/SOLUTIONING.md](docs/SOLUTIONING.md)` — the **HLD**: problem, single-node design
(worker pool, retry/backoff, aggregation, progress) with flow diagrams, and a phased plan
to scale to ~10M users.
- `[docs/LLD.md](docs/LLD.md)` — the **LLD**: the exact interfaces/seams, class contracts,
concurrency & error rules, and an extensibility playbook so features and scaling drop in
behind stable interfaces without rewrites.
- `[docs/LLD_DIAGRAMS.md](docs/LLD_DIAGRAMS.md)` — the **visual companion to the LLD**:
package/class/sequence/state/concurrency diagrams drawn directly from the code that's
actually checked in (Mermaid, renders natively on GitHub).
- `[docs/APPROACHES.md](docs/APPROACHES.md)` — the **trade-off analysis**: every major
design decision, the alternatives evaluated, their pros/cons, and why the chosen approach
is the best fit.
- `[docs/DEVELOPMENT_GUIDE.md](docs/DEVELOPMENT_GUIDE.md)` — the **build guide**: how each
layer was implemented, milestone by milestone.
- `[docs/API_TESTING.md](docs/API_TESTING.md)` — the **API contract & test guide**: exact
curl request/response pairs for every endpoint, plus every edge case (validation errors,
404/409/415/422, malformed input, concurrency) so you can confirm the service end to end.



## Tech stack

- Java 21
- Dropwizard 4.0 (Jetty + Jersey + Jackson under the hood)
- Maven (build)
- Docker / Docker Compose (packaging & running)
- JUnit 5 + AssertJ + Mockito (testing)



## What's implemented


| Area                                                               | Where                                                               |
| ------------------------------------------------------------------ | ------------------------------------------------------------------- |
| Batch ingestion (JSON body or file upload)                         | `api/BatchResource`, `ingest/*`                                     |
| Immediate `202 Accepted` + `batchId`                               | `service/BatchService#submit` / `#submitFromSource`                 |
| Bounded, fixed-size worker pool (no unbounded threads)             | `worker/ThreadPoolTaskExecutor`                                     |
| Retry with exponential backoff + full jitter on `429`/`5xx`        | `client/RetryingInferenceClient`, `client/ExponentialJitterBackoff` |
| Mock rate-limited inference endpoint                               | `client/MockInferenceClient`                                        |
| Result aggregation (thread-safe counters, exactly-once completion) | `model/Batch`, `service/CompletionAggregator`                       |
| Result persistence (in-memory, JSON file, or shared Postgres) | `store/InMemoryResultStore`, `store/JsonFileResultStore`, `store/postgres` |
| Shared Postgres store for multi-instance/horizontal scaling + startup crash-recovery of in-flight batches | `store/postgres/PostgresBatchStore`, `PromptBatchApplication#recoverInFlightBatches` |
| Live progress endpoint                                             | `GET /batches/{id}`                                                 |
| Final results endpoint                                             | `GET /batches/{id}/results`                                         |
| Health checks (liveness, worker pool saturation, DB connectivity)  | `health/PingHealthCheck`, `health/WorkerPoolHealthCheck`, `health/DatabaseHealthCheck` |
| Metrics (inference latency/outcomes, worker throughput/backlog, batch/HTTP counters), pushed periodically to logs/JMX | `client/MeteredInferenceClient`, `worker/MeteredTaskExecutor`, `config/config-*.yml` `metrics:` block |
| Unit tests for all of the above                                    | `src/test/java/...` (60 tests)                                      |


Every one of these is built behind a small interface (a "seam") so it can be swapped without
touching callers — see `[docs/LLD.md](docs/LLD.md)` §4 and the diagrams in
`[docs/LLD_DIAGRAMS.md](docs/LLD_DIAGRAMS.md)` §3 for the full seam map.

## Project structure

```
prompt-batch-service/
├── pom.xml                     Maven build config (fat-jar via shade plugin)
├── config/                     One Dropwizard config file per environment
│   ├── config-local.yml
│   ├── config-stage.yml
│   └── config-prod.yml
├── Dockerfile                  Multi-stage build (Maven build -> slim JRE runtime)
├── docker-compose.yml          Convenience wrapper to build + run the container
├── docker-compose.scale.yml    Overlay: multiple app replicas + nginx LB, all sharing one Postgres
├── docker/nginx.conf           Load balancer config used by the overlay above
├── .env.example                Template for DATABASE_URL - the ONE instance local/preprod/prod all mount
├── .do/app.yaml                DigitalOcean App Platform spec (scaling + health checks + DB binding)
├── docs/                       Design docs (HLD, LLD, diagrams, trade-offs, build guide)
└── src
    ├── main/java/com/example/promptbatch/
    │   ├── PromptBatchApplication.java      Composition root: wires every seam together
    │   ├── PromptBatchConfiguration.java     Config bound from config/*.yml
    │   ├── api/                              BatchResource + request/response DTOs
    │   ├── model/                            Batch, Prompt, PromptResult, enums
    │   ├── service/                           BatchService (async orchestration), Aggregator
    │   ├── worker/                            TaskExecutor (bounded pool) + PromptTask
    │   ├── client/                            InferenceClient, retry/backoff, mock endpoint
    │   ├── ingest/                            PromptSource (JSON / line-delimited upload)
    │   ├── repository/                        BatchRepository (live batch state)
    │   ├── store/                             ResultStore (in-memory / JSON file / postgres)
    │   ├── config/                            Typed config blocks (worker pool, retry, ...)
    │   ├── exception/                         Typed errors + JAX-RS mappers
    │   └── health/                            Health checks
    └── test/java/com/example/promptbatch/     Unit tests (mirrors the package layout above)
```



## Configuration & environments

Every environment's full Dropwizard config lives in its own file under
`[config/](config)`:


| File                      | Env             | Notes                                                                                  |
| ------------------------- | --------------- | --------------------------------------------------------------------------------------- |
| `config/config-local.yml` | local dev       | `DEBUG` app logging, small worker pool                                                  |
| `config/config-stage.yml` | preprod/staging | `INFO` logging, bigger worker pool                                                      |
| `config/config-prod.yml`  | production      | leaner logging, largest worker pool/retry budget                                        |

**All three default to `STORE_TYPE=postgres` against the exact same `DATABASE_URL`** — one
shared Postgres instance mounted by local, preprod, and prod alike. See
[One shared Postgres instance for local/preprod/prod](#one-shared-postgres-instance-for-localpreprodprod)
below for setup; pass `STORE_TYPE=json-file` to fall back to a throwaway local file store if you
ever need to work fully offline from that database.


Which file is loaded is controlled by the `APP_ENV` environment variable
(`local` / `stage` / `prod`), and each file also has its own knobs (port,
worker pool size, store location, etc.) overridable via env vars using
`${VAR:-default}` syntax, e.g. `STORE_DIRECTORY`, `WORKER_POOL_SIZE`,
`LOG_LEVEL`. That means you rarely need to edit the YAML for a one-off
override in stage/prod — just set the env var at deploy time.

The batch-processing knobs (all tunable without a rebuild) are:

```yaml
workerPool:
  size: 8              # N — max prompts processed concurrently
  queueCapacity: 10000  # bounded backlog before backpressure kicks in
retry:
  maxRetries: 4
  baseDelayMs: 500
  maxDelayMs: 5000
  jitter: true
mockEndpoint:
  rateLimitProbability: 0.3   # how often the mock endpoint returns "429"
  baseLatencyMs: 150
store:
  type: postgres         # or json-file / in-memory
  databaseUrl: ${DATABASE_URL}   # same instance for local/preprod/prod - see .env.example
```

To add a new environment, copy one of the existing files to
`config/config-<name>.yml`, adjust its values, and run with `APP_ENV=<name>`.

## How to run it



### Option 1: Docker (recommended, no local Java/Maven needed)

Everything is built inside the Docker image using a multi-stage build (a
`maven:3.9-eclipse-temurin-21` build stage, then copied into a slim
`eclipse-temurin:21-jre-jammy` runtime stage).

The store defaults to `postgres`, and every environment (local included) is meant to be mounted
to the same shared instance (see
[One shared Postgres instance for local/preprod/prod](#one-shared-postgres-instance-for-localpreprodprod)),
so set `DATABASE_URL` once before running:

```bash
# from the prompt-batch-service directory
cp .env.example .env   # then fill in the real DATABASE_URL
docker compose up --build

# or target a different environment's config (config/config-stage.yml, config-prod.yml, ...)
# - same DATABASE_URL, same underlying data, different worker-pool/logging tuning
APP_ENV=stage docker compose up --build
```

This will:

1. Build the project and package it into an executable jar.
2. Start the server using `config/config-${APP_ENV}.yml` (defaults to `local`), exposing:
  - `http://localhost:8080` — application port (REST API)
  - `http://localhost:8081` — admin port (health checks, `/metrics` JSON snapshot + periodic push
    to the `metrics` logger, ping)

Override individual settings (store directory, log level, worker pool size, ...) without
touching the YAML, since each config file reads them from env vars with defaults:

```bash
APP_ENV=prod STORE_DIRECTORY=/data/prod-batches WORKER_POOL_SIZE=48 docker compose up --build
```

Stop it with `Ctrl+C`, or `docker compose down` if run detached (`docker compose up -d --build`).

#### Plain Docker (without Compose)

```bash
docker build -t prompt-batch-service:local .
docker run --rm -e DATABASE_URL="$DATABASE_URL" -p 8080:8080 -p 8081:8081 prompt-batch-service:local

# target preprod/stage instead of local - same DATABASE_URL, same shared data
docker run --rm -e APP_ENV=stage -e DATABASE_URL="$DATABASE_URL" -p 8080:8080 -p 8081:8081 prompt-batch-service:local
```



### Option 2: Locally (requires JDK 21 + Maven 3.9+)

```bash
mvn clean package
java -jar target/prompt-batch-service.jar server config/config-local.yml

# or any other environment
java -jar target/prompt-batch-service.jar server config/config-stage.yml
```



### Verify it's alive

```bash
curl http://localhost:8080/ping
# {"status":"ok","message":"pong"}

curl http://localhost:8081/healthcheck
# {"ping":{"healthy":true,...}, "workerPool":{"healthy":true,"message":"active=0, queued=0"}}

curl http://localhost:8081/metrics
# JSON snapshot of every Timer/Meter/Counter/Gauge (inference latency, retry/rate-limit rates,
# worker pool backlog, batch counts, per-endpoint request timers, JVM stats). The same data is
# also pushed every 30s (local) / 1m (stage/prod) to the "metrics" logger - see the `metrics:`
# block in config/config-*.yml - so it flows straight into whatever log pipeline you already use.
```



## One shared Postgres instance for local/preprod/prod

`store/postgres/PostgresBatchStore` is the default store (`STORE_TYPE=postgres`) in **every**
environment — local, preprod (`stage`), and prod all point at the exact same `DATABASE_URL` by
design, not three separate databases. That's a deliberate choice here (see
[Known limitation](#known-limitation) for the trade-off it implies), and it's what makes this
work end to end:

- lets **many app containers/instances — and every environment — share one database**, so
  `GET /batches/{id}` is correct no matter which instance or environment answers it, and
- **persists every submitted prompt** (`batch_prompts` table), not just finished results, so
  that on startup any instance can find prompts a crashed/killed container never finished
  (`BatchRepository#pendingPrompts`) and resubmit just those (`PromptBatchApplication
  #recoverInFlightBatches`) — a killed container doesn't lose or restart the whole batch.

`store.databaseUrl` (env var `DATABASE_URL`) accepts either a DigitalOcean-style connection
string (`postgresql://user:pass@host:port/db?sslmode=require` — exactly what a DO Managed
Database gives you, and what App Platform injects as `${db.DATABASE_URL}`) or a plain
`jdbc:postgresql://...` URL.

### One-time setup

Point every environment at the real instance once — a DigitalOcean Managed Database, or a
Postgres already running on your droplet:

```bash
cp .env.example .env
# edit .env: DATABASE_URL=postgresql://user:pass@host:port/dbname?sslmode=require
```

`docker compose up --build` (see [How to run it](#how-to-run-it)) then mounts that same
`DATABASE_URL` regardless of `APP_ENV` — local, `APP_ENV=stage`, and `APP_ENV=prod` (or your DO
App Platform deployment, or a self-hosted droplet run) all read/write the same batches.

### Try multi-container crash recovery locally

To see the crash-recovery behavior with multiple containers sharing that same Postgres, use the
scaling overlay (see `docker-compose.scale.yml` for details):

```bash
docker compose -f docker-compose.yml -f docker-compose.scale.yml \
  up --build --scale prompt-batch-service=3
```

Submit a batch, then `docker kill` one of the `prompt-batch-service` replicas mid-batch —
`GET /batches/{id}` (via the nginx LB on `:8080`) keeps working throughout, and whichever
container restart/replacement comes back up finishes the batch's remaining prompts on startup.

### Deploying to DigitalOcean App Platform (with an existing Managed Postgres)

`.do/app.yaml` is a ready-to-edit App Platform spec that:

- runs `instance_count` (with `autoscaling`) copies of the service behind App Platform's load
  balancer for horizontal scaling,
- points App Platform's health checks at `/healthcheck` (admin port `8081`) so a container that
  crashes or fails health checks is **automatically restarted/replaced** — no docker-compose
  `restart:` policy needed in this deployment style,
- attaches your **existing** DigitalOcean Managed Postgres cluster (by `cluster_name`, under
  `databases:` — it does not provision a new one) and wires its connection string into
  `DATABASE_URL` for every instance.

```bash
# after editing the placeholders in .do/app.yaml (repo/branch, cluster_name)
doctl apps create --spec .do/app.yaml         # first deploy
doctl apps update <app-id> --spec .do/app.yaml # subsequent changes
```

If you'd rather point at a self-hosted Postgres container running on your own droplet instead of
a DO Managed Database, just set `DATABASE_URL` to that instance's connection string — nothing
else in the app needs to change, since the seam is the same `store.databaseUrl` config either way.

### Known limitations

- Recovery runs on **startup**, and only for the shared Postgres store — a crashed container's
  in-flight prompts resume once *some* instance restarts and runs its startup scan, not the
  instant the crash happens. `json-file`/`in-memory` don't implement
  `savePrompts`/`pendingPrompts` (they default to no-ops), since a single-file/in-memory store
  already lives and dies with its one container and isn't meant to be shared across instances.
- Pointing local/preprod/prod at **one** database instance (rather than one per environment) is
  simple and matches the ask here, but it means local dev runs and preprod tests write into the
  same `batches`/`prompt_results`/`batch_prompts` tables prod does — there's no isolation between
  environments. If that ever becomes a problem, the cleanest fix without touching any code is
  giving each environment its own database/schema on the same instance (e.g. `promptbatch_local`,
  `promptbatch_preprod`, `promptbatch_prod`) and setting a different `DATABASE_URL` per
  environment — the store itself doesn't care, it's purely a connection-string choice.



## Interactive API docs (Swagger UI)

Every endpoint is documented and callable straight from the browser — no curl needed:

- **Swagger UI:** [http://localhost:8080/swagger](http://localhost:8080/swagger) — click any endpoint,
  "Try it out", fill in the params/body, and "Execute" to call the live API and see the real response.
- **Raw OpenAPI spec:** `http://localhost:8080/openapi.json` (or `.../openapi.yaml`).

CORS is enabled on all `/*` routes, so the API can also be called directly from any other page/origin
(e.g. a hand-rolled HTML page with a button that `fetch()`s `http://localhost:8080/batches`).



## Using the API



### Submit a batch (JSON)

```bash
curl -s -X POST localhost:8080/batches \
  -H 'Content-Type: application/json' \
  -d '{"prompts":["hello","world","foo","bar"]}'
# 202 Accepted
# {"batchId":"b-2f9c...","total":4}
```



### Submit a batch (file upload, one prompt per line)

```bash
printf "hello\nworld\nfoo\nbar\n" | curl -s -X POST localhost:8080/batches/upload \
  -H 'Content-Type: text/plain' --data-binary @-
# 202 Accepted
# {"batchId":"b-91ab...","total":4}
```



### Check progress

```bash
curl -s localhost:8080/batches/<batchId>
# {"batchId":"b-2f9c...","status":"PROCESSING","total":4,"completed":2,
#  "succeeded":2,"failed":0,"percentComplete":50.0}
```



### Fetch final results (once completed)

```bash
curl -s localhost:8080/batches/<batchId>/results
# 200 once COMPLETED:
# {"batchId":"b-2f9c...","status":"COMPLETED",
#  "results":[{"promptId":"b-2f9c...-p0","outcome":"SUCCESS","output":"echo:hello", ...}, ...]}
#
# 409 while still RUNNING (with the current progress snapshot as the body)
```

With `mockEndpoint.rateLimitProbability > 0` (the default), watch the application logs —
you'll see `RetryingInferenceClient` warn about `429`s and back off, and the batch will still
reach `COMPLETED` with **zero dropped prompts**.

## How to test

Run the full unit test suite (60 tests covering retry/backoff determinism, bounded
concurrency, thread-safe aggregation under load, ingestion parsing, and the HTTP layer):

**Locally (if Maven is installed):**

```bash
mvn test
```

**Inside Docker (no local Maven needed):**

```bash
docker run --rm -v "$PWD":/build -w /build maven:3.9-eclipse-temurin-21 mvn test
```

**Full build (compile + test + package the fat jar):**

```bash
mvn clean verify
```



### What's covered


| Test class                                                                          | What it proves                                                                                                                                                                                                               |
| ----------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `RetryingInferenceClientTest`                                                       | Succeeds after transient `429`s, fails a prompt (not the batch) after `maxRetries`, non-retryable errors aren't retried, `RateLimiter.acquire()` is called every attempt — all with an injected `Sleeper` (no real waiting). |
| `ExponentialJitterBackoffTest`                                                      | Backoff grows exponentially and caps at `maxDelayMs`; jittered delay stays within bounds.                                                                                                                                    |
| `ThreadPoolTaskExecutorTest`                                                        | Observed in-flight concurrency never exceeds the configured pool size, even with far more tasks than threads; graceful shutdown drains in-flight work.                                                                       |
| `BatchTest` / `CompletionAggregatorTest`                                            | Thread-safe counters stay correct under 500–1000 concurrent writers; the batch transitions to `COMPLETED` and finalizes exactly once.                                                                                        |
| `PromptTaskTest`                                                                    | A task never throws out of `run()`; one failing prompt doesn't affect its siblings.                                                                                                                                          |
| `BatchServiceTest`                                                                  | `submit()` returns before processing completes (the async boundary); unknown batch ids raise `BatchNotFoundException`.                                                                                                       |
| `JsonPromptSourceTest`, `LinePromptSourceTest`, `PromptSourceRegistryTest`          | Ingestion parsing + content-type based source selection.                                                                                                                                                                     |
| `BatchResourceTest`                                                                 | Correct HTTP status codes (`202`, `200`, `409`) and delegation, with no business logic in the resource.                                                                                                                      |
| `JsonFileResultStoreTest`, `InMemoryResultStoreTest`, `InMemoryBatchRepositoryTest` | Persistence seams round-trip correctly.                                                                                                                                                                                      |




## Roadmap

The v1 in this repo is deliberately single-node and in-memory-first — see
`[docs/SOLUTIONING.md](docs/SOLUTIONING.md)` §7–9 for why that doesn't scale to ~10M users
and the phased migration plan (durable store → queue-decoupled workers → shared
Redis/Postgres/S3 state → global distributed rate limiting → elastic ops). Every seam needed
for that migration already exists in this codebase (see `docs/LLD.md` §10) — scaling is a
sequence of implementation swaps behind stable interfaces, not a rewrite.

Not yet built:

- CI/CD workflow (`.github/workflows/ci.yml`).
- A real (non-mock) `HttpInferenceClient`.
- Distributed rate limiting (`RateLimiter` is currently a `NoopRateLimiter`; a
  Redis-token-bucket implementation is the documented next step for multi-instance rate limits
  shared across all app containers, not just per-instance).

