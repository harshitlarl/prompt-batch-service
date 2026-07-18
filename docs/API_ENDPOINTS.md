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
| `POST` | `/batches` | Submit a batch. Body: `{"prompts": ["...", "..."]}` → `202 Accepted` with `{"batchId", "total"}` |
| `POST` | `/batches/upload` | Submit a batch via raw file body. `Content-Type: text/plain` = one prompt per line, `application/json` = JSON array of strings |
| `GET` | `/batches` | List every batch (id, status, progress, counts, timestamps), most recent first |
| `GET` | `/batches/{id}` | One batch's live progress: `status`, `total`, `completed`, `succeeded`, `failed`, `percentComplete` |
| `GET` | `/batches/{id}/results` | Per-prompt results (output/failure reason/attempts). `409` if the batch hasn't finished yet |

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
| `/healthcheck` | JSON health of every registered check: `ping`, `deadlocks`, `workerPool`, `retryWorkerPool`, and (when `STORE_TYPE=postgres`) `database` + `staleTaskRecovery` (the background recovery sweep's liveness) |
| `/metrics` | Full Dropwizard/JVM metrics dump (JSON): request timers, worker pool gauges, GC, memory, threads, etc. |
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
| `postgres` | Shared, durable — every instance/container reads/writes the same Postgres database. Survives restarts and crashed containers (in-flight batches are recovered on startup). **Used in production/DO deployment.** |
| `sqlite` | Durable, single-file, local to one container. Fine for a single local instance; not shared across replicas. |
| `json-file` | One JSON file per finalized batch; in-memory while running. |
| `in-memory` | Fastest, zero setup; all state lost on restart. Good for quick local testing only. |
