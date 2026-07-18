# API Testing Guide — curl request/response contracts

This is a practical, copy-pasteable companion to the [README](../README.md#using-the-api) and
[LLD](LLD.md): every endpoint's exact request/response shape, real curl commands you can run
against a live instance, and every edge case worth exercising (validation errors, 404s, 409s,
malformed input, concurrency/race behavior) so you can confirm the service is working correctly
end to end.

> **Verified against a live run.** Every request/response pair below was actually executed
> against a locally built jar (`mvn clean package`, then
> `java -jar target/prompt-batch-service.jar server config/config-local.yml`) on 2026-07-18,
> not just inferred from reading the source. A few things came out differently than a
> code-reading-only pass would predict — those are called out explicitly (§2.5, §3.5, §3.6,
> §6.2, §5.1's `attempts` quirk).

Assumes the app is running locally per the [README](../README.md#how-to-run-it):

```bash
export BASE=http://localhost:8080     # application port
export ADMIN=http://localhost:8081    # admin port (health/metrics)
```

All response bodies below are real shapes produced by the DTOs in `api/dto/*` — nothing is
invented. Fields you'll actually see:

- `CreateBatchResponse` → `batchId`, `total`
- `BatchProgressResponse` → `batchId`, `status`, `total`, `completed`, `succeeded`, `failed`, `percentComplete`
- `BatchResultsResponse` → `batchId`, `status`, `results[]` (each a `PromptResult`: `promptId`, `outcome`, `output`, `failureReason`, `attempts`)
- Error bodies (400/404) → `{"error": "<message>"}` (see `exception/*ExceptionMapper`)

`status` is one of `QUEUED`, `PROCESSING`, `COMPLETED` (`FAILED` is a reserved enum value not
currently reachable via the API — nothing in the codebase transitions a batch to it today).

---

## 1. Liveness

### 1.1 `GET /ping` — sanity check

```bash
curl -i "$BASE/ping"
```

**200 OK**
```json
{"status":"ok","message":"pong"}
```

### 1.2 `GET /healthcheck` (admin port) — dependency health

```bash
curl -i "$ADMIN/healthcheck"
```

**200 OK** (all checks healthy) — Dropwizard returns `500` here if *any* check reports unhealthy:
```json
{
  "ping": {"healthy": true, "message": "prompt-batch-service is up"},
  "workerPool": {"healthy": true, "message": "active=0, queued=0"}
}
```

Edge case — watch `workerPool` under load: submit a large batch (§2.4) and immediately curl this
endpoint; `active` should climb toward `workerPool.size` (8 locally) and `queued` should reflect
backlog beyond that, then both drain back to 0 once the batch completes.

---

## 2. Create a batch — `POST /batches` (JSON body)

**Request contract**

| Field | Type | Required | Constraint |
|---|---|---|---|
| `prompts` | `string[]` | yes | `@NotEmpty` — must be present and non-empty |

```bash
curl -i -X POST "$BASE/batches" \
  -H 'Content-Type: application/json' \
  -d '{"prompts":["hello","world","foo","bar"]}'
```

**202 Accepted**
```json
{"batchId":"b-2f9c1a3e-...","total":4}
```

Save the id for the next sections:

```bash
BATCH_ID=$(curl -s -X POST "$BASE/batches" \
  -H 'Content-Type: application/json' \
  -d '{"prompts":["hello","world","foo","bar"]}' | jq -r .batchId)
echo "$BATCH_ID"
```

### 2.1 Edge case — empty `prompts` array

```bash
curl -i -X POST "$BASE/batches" \
  -H 'Content-Type: application/json' \
  -d '{"prompts":[]}'
```

**422 Unprocessable Entity** (Dropwizard's default Jersey bean-validation response for a failed
`@NotEmpty` constraint, thrown *before* the request ever reaches `BatchService`):
```json
{"errors":["prompts must not be empty"]}
```

### 2.2 Edge case — missing `prompts` field entirely

```bash
curl -i -X POST "$BASE/batches" \
  -H 'Content-Type: application/json' \
  -d '{}'
```

**422 Unprocessable Entity** — same as above (`prompts` deserializes to `null`, which also
fails `@NotEmpty`).

### 2.3 Edge case — malformed JSON

```bash
curl -i -X POST "$BASE/batches" \
  -H 'Content-Type: application/json' \
  -d '{"prompts": "not-an-array"'   # missing closing brace on purpose
```

**400 Bad Request** — Jackson can't parse the body; Dropwizard's built-in
`JsonProcessingExceptionMapper` handles this before it reaches the resource at all, so the body
is Dropwizard's generic shape, not the app's `{"error":...}` shape (that one's reserved for
`BadInputException`/`BatchNotFoundException`, see §2.1 vs here). Verified body:
```json
{"code":400,"message":"Unable to process JSON"}
```

### 2.4 Edge case — missing `Content-Type`

```bash
curl -i -X POST "$BASE/batches" \
  -d '{"prompts":["hello"]}'   # no -H, so curl sends no Content-Type at all
```

**415 Unsupported Media Type** — the resource only `@Consumes(APPLICATION_JSON)`. Verified body:
```json
{"code":415,"message":"HTTP 415 Unsupported Media Type"}
```

### 2.5 Edge case — non-string elements in `prompts`

```bash
curl -i -X POST "$BASE/batches" \
  -H 'Content-Type: application/json' \
  -d '{"prompts":[1,2,3]}'
```

**202 Accepted, `"total":3`** — *not* a `400` as you might expect from `List<String>`. Jackson's
default deserializer coerces scalar JSON numbers into strings when binding into `List<String>`,
so `[1,2,3]` becomes `["1","2","3"]` and three prompts are created. Verified:
```json
{"batchId":"b-6a8619f6-...","total":3}
```
If you need strict typing here (reject non-string elements), you'd need to disable Jackson's
`COERCE_NUMBER_TO_STRING` coercion in the `ObjectMapper` — not currently done in this codebase.

### 2.6 Edge case — large batch (concurrency/backpressure sanity)

```bash
python3 -c 'import json; print(json.dumps({"prompts":[f"prompt-{i}" for i in range(500)]}))' \
  | curl -i -X POST "$BASE/batches" -H 'Content-Type: application/json' --data-binary @-
```

**202 Accepted** with `"total":500` returned immediately (proves the `202` happens before any
inference call — see §5.7 in LLD). Poll progress (§3) to watch it drain through the bounded
8-worker pool; `succeeded + failed` should always equal `completed`, and it should reach
`COMPLETED` with `failed == 0` eventually (retries absorb the mock endpoint's 30% injected
`429` rate — see `mockEndpoint.rateLimitProbability` in `config/config-local.yml`).

### 2.7 Edge case — duplicate prompt text is fine

```bash
curl -i -X POST "$BASE/batches" \
  -H 'Content-Type: application/json' \
  -d '{"prompts":["same","same","same"]}'
```

**202 Accepted** with `"total":3` — prompts are identified by position (`<batchId>-p0`,
`-p1`, `-p2`, ...), not by content, so duplicates are independent prompts.

---

## 3. Create a batch — `POST /batches/upload` (file upload)

Accepts `text/plain` (line-delimited), `application/octet-stream` (same parser), or
`application/json` (same `{"prompts":[...]}` shape as §2, routed to `JsonPromptSource`).

### 3.1 Happy path — line-delimited upload

```bash
printf "hello\nworld\nfoo\nbar\n" | curl -i -X POST "$BASE/batches/upload" \
  -H 'Content-Type: text/plain' --data-binary @-
```

**202 Accepted**
```json
{"batchId":"b-91ab...","total":4}
```

### 3.2 Edge case — blank lines are skipped, not counted

```bash
printf "hello\n\n\nworld\n  \nfoo\n" | curl -i -X POST "$BASE/batches/upload" \
  -H 'Content-Type: text/plain' --data-binary @-
```

**202 Accepted** with `"total":3` — blank/whitespace-only lines are filtered out
(`LinePromptSource#parse` trims and skips empties); each line is also trimmed before becoming a
prompt (`"  hello  "` → `"hello"`).

### 3.3 Edge case — empty upload body

```bash
printf "" | curl -i -X POST "$BASE/batches/upload" \
  -H 'Content-Type: text/plain' --data-binary @-
```

**400 Bad Request**
```json
{"error":"Upload contained no prompts"}
```

### 3.4 Edge case — upload with only blank lines

```bash
printf "\n\n   \n" | curl -i -X POST "$BASE/batches/upload" \
  -H 'Content-Type: text/plain' --data-binary @-
```

**400 Bad Request** — same `"Upload contained no prompts"` body as §3.3 (all lines filtered to
empty).

### 3.5 Edge case — unsupported content type

```bash
printf "hello\nworld\n" | curl -i -X POST "$BASE/batches/upload" \
  -H 'Content-Type: application/xml' --data-binary @-
```

**415 Unsupported Media Type** — *not* the app's own `400 {"error":"Unsupported content
type: ..."}` as you'd guess from reading `PromptSourceRegistry` in isolation. JAX-RS/Jersey
enforces the resource method's `@Consumes({TEXT_PLAIN, APPLICATION_OCTET_STREAM,
APPLICATION_JSON})` *before* the method body (and therefore `PromptSourceRegistry.select`) ever
runs, so an unmatched content type never reaches the registry's `orElseThrow`. Verified body:
```json
{"code":415,"message":"HTTP 415 Unsupported Media Type"}
```
The registry's `"Unsupported content type: ..."` `BadInputException` message is effectively
dead code for this endpoint given the current `@Consumes` list — it would only fire if you
widened `@Consumes` to something broader (e.g. a wildcard) while leaving some types unhandled
by any registered `PromptSource`.

### 3.6 Edge case — no `Content-Type` header at all

```bash
printf "hello\nworld\n" | curl -i -X POST "$BASE/batches/upload" --data-binary @-
```

**415 Unsupported Media Type** — same as §3.5. curl still sends a default
`Content-Type: application/x-www-form-urlencoded` header for a `POST` with a body even without
an explicit `-H`, which doesn't match `@Consumes` either. (A client that truly sends *no*
`Content-Type` at all is rare in practice; if one did, Jersey defaults it to
`application/octet-stream`, which **would** match and succeed with `202` — so "no header" isn't
reliably reproducible as a `400` via curl the way `PromptSourceRegistry`'s null-check might
suggest.)

### 3.7 Edge case — JSON upload via the same endpoint

```bash
printf '{"prompts":["a","b","c"]}' | curl -i -X POST "$BASE/batches/upload" \
  -H 'Content-Type: application/json' --data-binary @-
```

**202 Accepted** with `"total":3` — routed to `JsonPromptSource`, identical parsing rules and
error cases as §2.1–2.3, just via the upload path.

### 3.8 Edge case — malformed JSON via upload

```bash
printf '{"prompts":' | curl -i -X POST "$BASE/batches/upload" \
  -H 'Content-Type: application/json' --data-binary @-
```

**400 Bad Request**
```json
{"error":"Unable to parse JSON prompt payload: ..."}
```

Unlike §2.3 (which goes through Jersey's `@Valid` body binding and gets Dropwizard's generic
handler), the upload path parses JSON manually inside `JsonPromptSource`, so malformed JSON here
*does* surface as the app's own `{"error":...}` shape via `BadInputExceptionMapper`.

### 3.9 Edge case — binary/non-UTF-8 upload

```bash
head -c 64 /dev/urandom | curl -i -X POST "$BASE/batches/upload" \
  -H 'Content-Type: application/octet-stream' --data-binary @-
```

**202 Accepted or 400**, depending on the random bytes — arbitrary bytes are decoded as UTF-8
line-by-line; most byte sequences will still produce *some* non-empty trimmed "lines" (garbled
but valid prompts get created — `total` > 0), while an all-null/whitespace-decoding buffer would
hit §3.3/§3.4's `400`. Good for confirming the parser never throws an uncaught exception on
garbage input (LLD.md's "a task never throws out of `run()`" applies to ingestion too).

---

## 4. Check progress — `GET /batches/{id}`

```bash
curl -i "$BASE/batches/$BATCH_ID"
```

**200 OK** (mid-flight):
```json
{"batchId":"b-2f9c...","status":"PROCESSING","total":4,"completed":2,
 "succeeded":2,"failed":0,"percentComplete":50.0}
```

**200 OK** (done):
```json
{"batchId":"b-2f9c...","status":"COMPLETED","total":4,"completed":4,
 "succeeded":4,"failed":0,"percentComplete":100.0}
```

Poll loop to watch it converge:

```bash
watch -n 0.5 "curl -s $BASE/batches/$BATCH_ID"
# or, one-shot busy poll:
until [ "$(curl -s $BASE/batches/$BATCH_ID | jq -r .status)" = "COMPLETED" ]; do sleep 0.2; done
```

### 4.1 Edge case — unknown batch id

```bash
curl -i "$BASE/batches/does-not-exist"
```

**404 Not Found**
```json
{"error":"Batch not found: does-not-exist"}
```

### 4.2 Edge case — progress right after submission (`QUEUED`/`PROCESSING` race)

```bash
curl -s -X POST "$BASE/batches" -H 'Content-Type: application/json' \
  -d '{"prompts":["a"]}' | jq -r .batchId | xargs -I{} curl -i "$BASE/batches/{}"
```

**200 OK** — you should see `status` already `PROCESSING` (never `QUEUED`) because
`BatchService#enqueue` sets `PROCESSING` synchronously before returning the `202`; `QUEUED` is
the model's initial value but is never externally observable through this API today.

### 4.3 Edge case — invariant check across the whole lifecycle

For any response, `completed == succeeded + failed` and `completed <= total` must always hold,
and `percentComplete == completed * 100.0 / total` (or `100.0` if `total == 0`, which can't
happen via these endpoints since both ingestion paths reject empty prompt lists). Script a quick
assertion:

```bash
curl -s "$BASE/batches/$BATCH_ID" | jq -e '.completed == (.succeeded + .failed)'
```

---

## 5. Fetch final results — `GET /batches/{id}/results`

### 5.1 Happy path — batch completed

```bash
curl -i "$BASE/batches/$BATCH_ID/results"
```

**200 OK** — verified live response for `{"prompts":["hello","world","foo","bar"]}`:
```json
{
  "batchId": "b-7628b046-...",
  "status": "COMPLETED",
  "results": [
    {"promptId":"b-7628b046-...-p0","outcome":"SUCCESS","output":"echo:hello","failureReason":null,"attempts":1,"success":true},
    {"promptId":"b-7628b046-...-p1","outcome":"SUCCESS","output":"echo:world","failureReason":null,"attempts":1,"success":true},
    {"promptId":"b-7628b046-...-p2","outcome":"SUCCESS","output":"echo:foo","failureReason":null,"attempts":1,"success":true},
    {"promptId":"b-7628b046-...-p3","outcome":"SUCCESS","output":"echo:bar","failureReason":null,"attempts":1,"success":true}
  ]
}
```

Two things worth knowing that only show up at runtime (not obvious from the DTO source alone):

- There's an extra `"success": true/false` field beyond the record's four declared components.
  `PromptResult` is a record but also declares a plain `isSuccess()` method; Jackson's default
  bean introspection picks up any `isXxx()` accessor as a serializable property in addition to
  the record's canonical components, so it rides along in every result. Don't be surprised by
  it, and don't rely on `outcome` being the *only* signal — `success`/`outcome` are always
  consistent with each other.
- `attempts` is currently always `1` on success and always `0` on failure, **regardless of how
  many actual HTTP attempts the retrying client made**. `output` is always `"echo:<prompt
  text>"` for successes, since `MockInferenceClient` just echoes. The real retry count lives
  only in application logs (`RetryingInferenceClient` warns per retry) and in the
  `failureReason` string for failures (e.g. `"retries exhausted after 5 attempts for prompt
  ..."`) — `attempts` in the JSON body does not reflect it. This is a real characteristic of
  `PromptTask#run` (it hardcodes `1`/`0` rather than propagating the client's actual attempt
  count), not a doc error — worth knowing if you're asserting on `attempts` in tests.

### 5.2 Edge case — results requested while still running

```bash
curl -i "$BASE/batches/$BATCH_ID/results"   # immediately after submit, before it finishes
```

**409 Conflict** — body is the *live progress snapshot* (same shape as §4), not an error object:
```json
{"batchId":"b-2f9c...","status":"PROCESSING","total":4,"completed":1,
 "succeeded":1,"failed":0,"percentComplete":25.0}
```

This is a deliberate contract choice: `409` + progress body lets a client poll `/results`
directly and either get the final answer or the current progress, without a second endpoint.

### 5.3 Edge case — unknown batch id

```bash
curl -i "$BASE/batches/does-not-exist/results"
```

**404 Not Found**
```json
{"error":"Batch not found: does-not-exist"}
```

### 5.4 Edge case — a batch with partial failures still reaches `COMPLETED`

Every prompt either exhausts `retry.maxRetries` (4 locally) and fails *individually*, or
succeeds — the batch as a whole always reaches `COMPLETED` once `completed == total`, regardless
of how many individual prompts failed:

Verified live example — a 500-prompt batch against the default
`mockEndpoint.rateLimitProbability: 0.3` reached `COMPLETED` with `"succeeded":497,"failed":3`
(exact numbers are random per run: each prompt independently has `0.3^5 ≈ 0.24%` odds of
exhausting all `maxRetries: 4` retries, so ~1-3 failures per 500 prompts is typical, `0` is also
common). A real failed entry looked like:
```json
{
  "promptId": "b-45a63274-...-p110",
  "outcome": "FAILED",
  "output": null,
  "failureReason": "retries exhausted after 5 attempts for prompt b-45a63274-...-p110: 429 Too Many Requests for b-45a63274-...-p110",
  "attempts": 0,
  "success": false
}
```
Note `attempts: 0` even though the message says "5 attempts" — see the callout in §5.1 about
`attempts` not reflecting the real retry count.

To force failures deterministically for testing (rather than relying on the ~0.24% odds above),
temporarily push `mockEndpoint.rateLimitProbability` toward `1.0` in `config/config-local.yml`
so most/all prompts exhaust retries and fail, then confirm `succeeded + failed == total` still
holds and the batch still reaches `COMPLETED` (not stuck in `PROCESSING`):

```bash
docker run --rm -e APP_ENV=local -p 8080:8080 -p 8081:8081 \
  -e WORKER_POOL_SIZE=4 prompt-batch-service:local
# then edit config-local.yml's mockEndpoint.rateLimitProbability to 0.95 and rebuild, or
# add a MOCK_RATE_LIMIT_PROBABILITY env override if you wire one into the yml.
```

- `outcome: "FAILED"` always pairs with `output: null` and a non-null `failureReason`
  (never both `output` and `failureReason` populated — see `PromptResult`'s factory methods).
- `outcome: "SUCCESS"` always pairs with `output` set and `failureReason: null`.

### 5.5 Edge case — results are stable after completion (idempotent GET)

```bash
curl -s "$BASE/batches/$BATCH_ID/results" > /tmp/first.json
curl -s "$BASE/batches/$BATCH_ID/results" > /tmp/second.json
diff /tmp/first.json /tmp/second.json && echo "IDENTICAL"
```

A completed batch's results never change on subsequent reads — good regression check after any
change to `ResultStore`/`CompletionAggregator`.

---

## 6. Cross-cutting / HTTP-method edge cases

### 6.1 Wrong HTTP method

```bash
curl -i -X DELETE "$BASE/batches/$BATCH_ID"
curl -i -X PUT "$BASE/batches"
```

**405 Method Not Allowed** — no `@DELETE`/`@PUT` mapping exists on `BatchResource`.

### 6.2 Trailing slash

```bash
curl -i "$BASE/batches/$BATCH_ID/"
```

**200 OK** — verified live: Jersey normalizes the trailing slash and still matches
`@Path("/{id}")`, returning the identical progress body as without the slash. (Don't assume
this generalizes to every JAX-RS setup — Jersey's default matrix-param/trailing-slash handling
is lenient here — but for *this* service, both forms are interchangeable everywhere.)

### 6.3 Batch id with URL-unsafe characters

```bash
curl -i "$BASE/batches/../ping"
```

Confirms path traversal isn't possible: this resolves to a literal batch id lookup (`..` is not
special to Jersey's path matching here), so expect **404 Not Found** with
`{"error":"Batch not found: .."}`, not an unintended route to `/ping`.

### 6.4 Concurrent submits get independent, non-colliding ids

```bash
for i in $(seq 1 20); do
  curl -s -X POST "$BASE/batches" -H 'Content-Type: application/json' \
    -d '{"prompts":["x"]}' &
done
wait
```

Each call gets a fresh UUID-based `batchId` (`b-<uuid>`); run with `| jq -r .batchId` piped to
`sort -u | wc -l` and confirm the count equals the number of requests (no collisions).

---

## 7. Suggested smoke-test script

A minimal end-to-end script exercising the happy path + the highest-value edge cases in one go:

```bash
#!/usr/bin/env bash
set -euo pipefail
BASE=${BASE:-http://localhost:8080}

pass() { echo "PASS: $1"; }
fail() { echo "FAIL: $1"; exit 1; }

# 1. liveness
[ "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/ping")" = "200" ] && pass "ping" || fail "ping"

# 2. happy path create + poll + results
BATCH_ID=$(curl -s -X POST "$BASE/batches" -H 'Content-Type: application/json' \
  -d '{"prompts":["hello","world","foo","bar"]}' | jq -r .batchId)
[ -n "$BATCH_ID" ] && [ "$BATCH_ID" != "null" ] && pass "create returns batchId" || fail "create"

until [ "$(curl -s "$BASE/batches/$BATCH_ID" | jq -r .status)" = "COMPLETED" ]; do sleep 0.2; done
pass "batch reached COMPLETED"

TOTAL=$(curl -s "$BASE/batches/$BATCH_ID/results" | jq '.results | length')
[ "$TOTAL" = "4" ] && pass "results has 4 entries" || fail "results count"

# 3. edge cases
[ "$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/batches" \
  -H 'Content-Type: application/json' -d '{"prompts":[]}')" = "422" ] && pass "empty prompts -> 422" || fail "empty prompts"

[ "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/batches/no-such-id")" = "404" ] && pass "unknown id -> 404" || fail "404 case"

echo "ALL CHECKS PASSED"
```

Save as `docs/smoke-test.sh`, `chmod +x`, and run with `BASE=http://localhost:8080 ./smoke-test.sh`.

---

## 8. Reference — HTTP status code summary

| Endpoint | Scenario | Status | Body |
|---|---|---|---|
| `GET /ping` | always | 200 | `{"status":"ok","message":"pong"}` |
| `GET /healthcheck` (admin) | all checks healthy | 200 | health check map |
| `GET /healthcheck` (admin) | any check unhealthy | 500 | health check map |
| `POST /batches` | valid, non-empty `prompts` | 202 | `{"batchId","total"}` |
| `POST /batches` | empty/missing `prompts` | 422 | `{"errors":["prompts must not be empty"]}` |
| `POST /batches` | non-string elements (e.g. numbers) | 202 | Jackson coerces to strings — **not** an error (§2.5) |
| `POST /batches` | malformed JSON | 400 | `{"code":400,"message":"Unable to process JSON"}` |
| `POST /batches` | wrong/missing `Content-Type` | 415 | `{"code":415,"message":"HTTP 415 Unsupported Media Type"}` |
| `POST /batches/upload` | valid line/JSON upload | 202 | `{"batchId","total"}` |
| `POST /batches/upload` | empty / all-blank body | 400 | `{"error":"Upload contained no prompts"}` |
| `POST /batches/upload` | unsupported/missing content type | 415 | `{"code":415,"message":"HTTP 415 Unsupported Media Type"}` — rejected by `@Consumes` before the resource runs (§3.5/§3.6) |
| `POST /batches/upload` | malformed JSON body (with `Content-Type: application/json`) | 400 | `{"error":"Unable to parse JSON prompt payload: ..."}` |
| `GET /batches/{id}` | known id (with or without trailing slash) | 200 | progress object |
| `GET /batches/{id}` | unknown id | 404 | `{"error":"Batch not found: <id>"}` |
| `GET /batches/{id}/results` | `status == COMPLETED` | 200 | results object (each result also carries an extra `success` boolean, §5.1) |
| `GET /batches/{id}/results` | still `PROCESSING`/`QUEUED` | 409 | progress object |
| `GET /batches/{id}/results` | unknown id | 404 | `{"error":"Batch not found: <id>"}` |
| any | wrong HTTP method | 405 | `{"code":405,"message":"HTTP 405 Method Not Allowed"}` |
