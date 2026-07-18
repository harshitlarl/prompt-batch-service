# API & Endpoint Reference

Single-page reference for every HTTP endpoint exposed by `prompt-batch-service` — the
application API, the built-in dashboard pages, and the Dropwizard admin/ops endpoints.

There's also a live, clickable version of this at **`/ui/endpoints.html`** on any running
instance (e.g. <https://prompt-batch-service-v5vem.ondigitalocean.app/ui/endpoints.html>).

## Ports

| Connector | Default port | Purpose |
|---|---|---|
| Application | `8080` | Everything below in **App API** and **Dashboard (UI)** |
| Admin | `8081` | Everything below in **Admin / Ops** — health, metrics, JVM diagnostics |

On DigitalOcean App Platform only the application port (`8080`) is exposed on the public
domain; the admin port is for the platform's own internal health checks and for direct
access when running locally / via `docker compose` / SSH-tunneled to a droplet.

## App API (port 8080)

| Method | Path | Description |
|---|---|---|
| `GET` | `/` | Redirects (303) to `/ui` |
| `GET` | `/ping` | Liveness check → `{"status":"ok","message":"pong"}` |
| `POST` | `/batches` | Submit a batch (JSON). Body: `{"prompts": ["...", "..."]}` → `202 Accepted` with `{"batchId", "total"}`. `422` on validation error (e.g. empty list) |
| `POST` | `/batches/upload` | Submit a batch via raw file body. `Content-Type: text/plain` = newline-delimited prompts, `application/json` = JSON array of strings. `415` on unsupported content type |
| `GET` | `/batches` | List every batch (progress summary for each), most recently submitted first |
| `GET` | `/batches/{id}` | One batch's live progress. `404` if `batchId` is unknown |
| `GET` | `/batches/{id}/results` | Per-prompt results, once `COMPLETED`. `409` (with the current progress snapshot) if still running, `404` if unknown |

### Request / response shapes

`POST /batches` request:

```json
{ "prompts": ["prompt one", "prompt two"] }
```

`POST /batches` / `POST /batches/upload` response (`202`):

```json
{ "batchId": "b3f1...", "total": 2 }
```

`GET /batches` and `GET /batches/{id}` response — one `BatchProgressResponse` per batch:

```json
{
  "batchId": "b3f1...",
  "status": "PROCESSING",
  "total": 2,
  "completed": 1,
  "succeeded": 1,
  "failed": 0,
  "percentComplete": 50.0,
  "createdAt": "2026-07-18T06:00:00Z",
  "finishedAt": null
}
```

`status` is one of `QUEUED`, `PROCESSING`, `COMPLETED`, `FAILED` (monotonic — no back-transitions).

`GET /batches/{id}/results` response (`200`, once `COMPLETED`):

```json
{
  "batchId": "b3f1...",
  "status": "COMPLETED",
  "results": [
    { "promptId": "p1", "outcome": "SUCCESS", "output": "...", "failureReason": null, "attempts": 1 },
    { "promptId": "p2", "outcome": "FAILED", "output": null, "failureReason": "...", "attempts": 3 }
  ]
}
```

## Dashboard (UI) (port 8080)

Hand-rolled static pages served at `/ui/*`, calling the App API above from the browser
(same origin, no CORS hop).

| Path | Description |
|---|---|
| `/ui` | **Submit** — paste prompts (plain text / JSON array / file upload), submit a batch, watch it live |
| `/ui/status.html` | **Task Status** — every batch, live-polling progress table, filter by status/search by id |
| `/ui/endpoints.html` | **Endpoints** — this reference, as a clickable page |

## API Docs

| Path | Description |
|---|---|
| `/swagger` | Interactive Swagger UI ("try it out" against the live API) |
| `/openapi.json` | OpenAPI 3 spec, JSON |
| `/openapi.yaml` | OpenAPI 3 spec, YAML |

## Admin / Ops (port 8081)

Dropwizard's built-in admin console. Not exposed on the public DO App Platform domain by
default — reach it via `docker compose` locally, or by exposing/tunneling port 8081.

| Path | Description |
|---|---|
| `/` (admin root) | Admin index — links to everything below |
| `/healthcheck` | JSON health of every registered check: `ping`, `workerPool`, `retryWorkerPool`, and — only when `store.type=postgres` — `database` and (if `recovery.enabled`) `staleTaskRecovery` (the background crash-recovery sweep's liveness) |
| `/metrics` | Full Dropwizard/JVM metrics dump (JSON): request timers, worker pool gauges (`TaskExecutor.primary/retry.activeCount/queueSize`), `BatchRepository.count`, GC, memory, threads, etc. |
| `/metrics/healthcheck` | Metrics-formatted view of health checks |
| `/threads` | Live JVM thread dump |
| `/ping` | Admin connector's own liveness check (separate from the app-port `/ping` above) |
| `/pprof/profile` (CPU Profile) | On-demand CPU profile capture |
| `/pprof/contention` (CPU Contention) | On-demand lock-contention profile capture |
| `/tasks/log-level` | `POST` — change a logger's level at runtime without a restart |
| `/tasks/gc` | `POST` — trigger a JVM garbage collection |

## Data store

Controlled by `store.type` in `config/config-<env>.yml` (`STORE_TYPE` env var overrides it):

| `STORE_TYPE` | Behavior |
|---|---|
| `postgres` (default) | Shared, durable — every instance/container reads/writes the same Postgres database (tables `batches`, `prompt_results`, `batch_prompts`). Survives restarts and crashed containers: in-flight batches are recovered on startup, and a background sweep continuously resubmits prompts lost to a crashed/hung worker to a dedicated retry pool (giving up after `recovery.maxAttempts`). **Used in production/DO deployment.** See [`SOLUTIONING.md` §4.7](SOLUTIONING.md#47-data-model) for the schema. |
| `json-file` | One JSON file per finalized batch; in-memory batch state while running. Single process only — no cross-instance sharing or recovery. |
| `in-memory` | Fastest, zero setup; all state lost on restart. Good for quick local testing only. |
