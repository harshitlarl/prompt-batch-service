#!/usr/bin/env python3
"""
End-to-end test / ingestion script for a live prompt-batch-service deployment
(e.g. the DigitalOcean App Platform instance described in .do/app.yaml).

Exercises every functional (F1-F6) and non-functional (N1-N6) requirement from
docs/SOLUTIONING.md and every documented request/response contract + edge case
from docs/API_TESTING.md, against a real running instance over HTTP.

Usage:
    python3 scripts/live_e2e_test.py --base-url https://your-app.ondigitalocean.app
    # or
    BASE_URL=https://your-app.ondigitalocean.app python3 scripts/live_e2e_test.py

Options:
    --base-url URL       Application base URL (default: $BASE_URL or http://localhost:8080)
    --admin-url URL      Admin port base URL for /healthcheck /metrics, if reachable
                          (default: $ADMIN_URL; often NOT publicly reachable on DO App
                          Platform since only http_port is exposed - failures there are
                          reported as SKIP, not FAIL)
    --large-batch-size N Size of the batch used for concurrency/backpressure checks
                          (default: 200)
    --timeout SECONDS    Poll timeout per batch (default: 120)
    -v, --verbose        Print each HTTP request/response

Exit code is 0 iff every test passed (SKIPs don't count as failures).
"""

import argparse
import concurrent.futures
import json
import os
import sys
import time
import uuid
from dataclasses import dataclass, field
from typing import Any, Callable, Optional

try:
    import requests
except ImportError:
    print("This script requires the 'requests' package: pip install requests", file=sys.stderr)
    sys.exit(2)


# --------------------------------------------------------------------------- #
# Minimal test harness
# --------------------------------------------------------------------------- #

@dataclass
class Results:
    passed: int = 0
    failed: int = 0
    skipped: int = 0
    failures: list = field(default_factory=list)


RESULTS = Results()
VERBOSE = False


def log(msg: str) -> None:
    print(msg)


def vlog(msg: str) -> None:
    if VERBOSE:
        print(f"    {msg}")


def check(name: str, condition: bool, detail: str = "") -> None:
    if condition:
        RESULTS.passed += 1
        log(f"  PASS  {name}")
    else:
        RESULTS.failed += 1
        RESULTS.failures.append(name)
        log(f"  FAIL  {name}" + (f" -- {detail}" if detail else ""))


def skip(name: str, reason: str) -> None:
    RESULTS.skipped += 1
    log(f"  SKIP  {name} -- {reason}")


def section(title: str) -> None:
    log(f"\n=== {title} ===")


def run_test(name: str, fn: Callable[[], None]) -> None:
    """Run a whole test function, catching unexpected exceptions as failures."""
    try:
        fn()
    except AssertionError as e:
        RESULTS.failed += 1
        RESULTS.failures.append(name)
        log(f"  FAIL  {name} -- assertion: {e}")
    except requests.RequestException as e:
        RESULTS.failed += 1
        RESULTS.failures.append(name)
        log(f"  FAIL  {name} -- request error: {e}")
    except Exception as e:  # noqa: BLE001 - want to keep going no matter what
        RESULTS.failed += 1
        RESULTS.failures.append(name)
        log(f"  FAIL  {name} -- unexpected error: {type(e).__name__}: {e}")


# --------------------------------------------------------------------------- #
# HTTP helpers
# --------------------------------------------------------------------------- #

class Client:
    def __init__(self, base_url: str, timeout: float = 30.0):
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self.session = requests.Session()

    def _url(self, path: str) -> str:
        return f"{self.base_url}{path}"

    def request(self, method: str, path: str, **kwargs) -> requests.Response:
        url = self._url(path)
        kwargs.setdefault("timeout", self.timeout)
        vlog(f"{method} {url} {kwargs.get('data') or kwargs.get('json') or ''}")
        resp = self.session.request(method, url, **kwargs)
        vlog(f"-> {resp.status_code} {resp.text[:300]}")
        return resp

    def get(self, path: str, **kwargs) -> requests.Response:
        return self.request("GET", path, **kwargs)

    def post(self, path: str, **kwargs) -> requests.Response:
        return self.request("POST", path, **kwargs)


def safe_json(resp: requests.Response) -> Optional[Any]:
    try:
        return resp.json()
    except ValueError:
        return None


def poll_until_completed(client: Client, batch_id: str, timeout: float) -> dict:
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        resp = client.get(f"/batches/{batch_id}")
        assert resp.status_code == 200, f"progress GET returned {resp.status_code}"
        body = safe_json(resp)
        assert body is not None, "progress response was not JSON"
        last = body
        assert body["completed"] == body["succeeded"] + body["failed"], (
            f"invariant violated: completed={body['completed']} != "
            f"succeeded({body['succeeded']})+failed({body['failed']})"
        )
        assert body["completed"] <= body["total"], "completed exceeds total"
        if body["status"] == "COMPLETED":
            return body
        time.sleep(0.3)
    raise AssertionError(f"batch {batch_id} did not reach COMPLETED within {timeout}s (last={last})")


# --------------------------------------------------------------------------- #
# Tests -- Section 1: Liveness (F-none / N4 observability)
# --------------------------------------------------------------------------- #

def test_ping(app: Client) -> None:
    resp = app.get("/ping")
    body = safe_json(resp)
    check("GET /ping -> 200", resp.status_code == 200, f"got {resp.status_code}")
    check("GET /ping body has status=ok", bool(body) and body.get("status") == "ok", str(body))


def test_admin_healthcheck(admin: Optional[Client]) -> None:
    if admin is None:
        skip("GET /healthcheck (admin)", "no --admin-url given/reachable on this deployment")
        return
    try:
        resp = admin.get("/healthcheck")
    except requests.RequestException as e:
        skip("GET /healthcheck (admin)", f"admin port unreachable: {e}")
        return
    body = safe_json(resp)
    check("GET /healthcheck -> 200 (all checks healthy)", resp.status_code == 200, f"got {resp.status_code}: {body}")


# --------------------------------------------------------------------------- #
# Section 2: POST /batches (JSON) -- F1, F2, F3, F4, F5, F6
# --------------------------------------------------------------------------- #

def test_create_batch_happy_path(app: Client) -> dict:
    prompts = ["hello", "world", "foo", "bar"]
    resp = app.post("/batches", json={"prompts": prompts})
    body = safe_json(resp)
    check("POST /batches happy path -> 202", resp.status_code == 202, f"got {resp.status_code}: {body}")
    check("response has batchId + total", bool(body) and body.get("total") == len(prompts) and body.get("batchId"),
          str(body))
    return body or {}


def test_full_lifecycle(app: Client, timeout: float) -> None:
    prompts = ["hello", "world", "foo", "bar"]
    resp = app.post("/batches", json={"prompts": prompts})
    body = safe_json(resp)
    assert resp.status_code == 202, f"create failed: {resp.status_code} {body}"
    batch_id = body["batchId"]

    final = poll_until_completed(app, batch_id, timeout)
    check("full lifecycle reaches COMPLETED", final["status"] == "COMPLETED", str(final))
    check("full lifecycle: succeeded+failed==total", final["succeeded"] + final["failed"] == final["total"],
          str(final))

    results_resp = app.get(f"/batches/{batch_id}/results")
    results_body = safe_json(results_resp)
    check("GET results after completion -> 200", results_resp.status_code == 200,
          f"got {results_resp.status_code}: {results_body}")
    results = (results_body or {}).get("results", [])
    check("results length == total", len(results) == len(prompts), f"{len(results)} != {len(prompts)}")

    for r in results:
        outcome = r.get("outcome")
        if outcome == "SUCCESS":
            check(f"result {r.get('promptId')}: SUCCESS has output, no failureReason",
                  r.get("output") is not None and r.get("failureReason") is None, str(r))
        elif outcome == "FAILED":
            check(f"result {r.get('promptId')}: FAILED has failureReason, no output",
                  r.get("output") is None and r.get("failureReason") is not None, str(r))
        else:
            check(f"result {r.get('promptId')}: outcome is SUCCESS or FAILED", False, str(r))

    # idempotent re-read (§5.5)
    second_resp = app.get(f"/batches/{batch_id}/results")
    check("results are idempotent on re-read", safe_json(second_resp) == results_body)


def test_empty_prompts_array(app: Client) -> None:
    resp = app.post("/batches", json={"prompts": []})
    check("empty prompts array -> 422", resp.status_code == 422, f"got {resp.status_code}: {resp.text}")


def test_missing_prompts_field(app: Client) -> None:
    resp = app.post("/batches", json={})
    check("missing prompts field -> 422", resp.status_code == 422, f"got {resp.status_code}: {resp.text}")


def test_malformed_json(app: Client) -> None:
    resp = app.post("/batches", data='{"prompts": "not-an-array"',
                     headers={"Content-Type": "application/json"})
    check("malformed JSON body -> 400", resp.status_code == 400, f"got {resp.status_code}: {resp.text}")


def test_wrong_content_type(app: Client) -> None:
    # BatchResource only @Consumes(APPLICATION_JSON); any other declared content type
    # must be rejected by JAX-RS with 415 before the resource method runs. (Truly
    # *omitting* the header is not reliably reproducible across HTTP clients - see
    # docs/API_TESTING.md §3.6 - so we test with an explicit unsupported type instead,
    # which every client sends identically.)
    resp = app.post("/batches", data=json.dumps({"prompts": ["hello"]}),
                     headers={"Content-Type": "text/plain"})
    check("wrong Content-Type (text/plain) -> 415", resp.status_code == 415, f"got {resp.status_code}: {resp.text}")


def test_non_string_elements_coerced(app: Client) -> None:
    resp = app.post("/batches", json={"prompts": [1, 2, 3]})
    body = safe_json(resp)
    check("numeric prompt elements -> 202 (coerced to strings)",
          resp.status_code == 202 and body and body.get("total") == 3, f"got {resp.status_code}: {body}")


def test_duplicate_prompts_independent(app: Client) -> None:
    resp = app.post("/batches", json={"prompts": ["same", "same", "same"]})
    body = safe_json(resp)
    check("duplicate prompt text -> 202, total=3", resp.status_code == 202 and body and body.get("total") == 3,
          f"got {resp.status_code}: {body}")


def test_large_batch_backpressure(app: Client, size: int, timeout: float) -> None:
    prompts = [f"prompt-{i}" for i in range(size)]
    t0 = time.time()
    resp = app.post("/batches", json={"prompts": prompts})
    ack_latency = time.time() - t0
    body = safe_json(resp)
    check(f"large batch (n={size}) -> 202 immediately", resp.status_code == 202 and body and body.get("total") == size,
          f"got {resp.status_code}: {body}")
    check("large batch ack is fast (< 5s, proves 202 precedes inference) (F2)", ack_latency < 5.0,
          f"took {ack_latency:.2f}s")
    if resp.status_code != 202:
        return
    batch_id = body["batchId"]
    final = poll_until_completed(app, batch_id, timeout)
    check("large batch reaches COMPLETED with zero dropped prompts (F4, N3)",
          final["completed"] == size and final["succeeded"] + final["failed"] == size, str(final))


def test_results_conflict_while_running(app: Client, size: int) -> None:
    """§5.2 - GET /results while still PROCESSING returns 409 + progress body."""
    prompts = [f"race-{i}" for i in range(size)]
    resp = app.post("/batches", json={"prompts": prompts})
    body = safe_json(resp)
    assert resp.status_code == 202, f"create failed: {resp.status_code} {body}"
    batch_id = body["batchId"]

    results_resp = app.get(f"/batches/{batch_id}/results")
    if results_resp.status_code == 200:
        skip("GET results while running -> 409", "batch completed too fast to observe RUNNING state; "
             "try a larger --large-batch-size")
        return
    results_body = safe_json(results_resp)
    check("GET results while running -> 409", results_resp.status_code == 409,
          f"got {results_resp.status_code}: {results_body}")
    check("409 body is a progress snapshot (has status/total/completed)",
          bool(results_body) and {"status", "total", "completed"} <= results_body.keys(), str(results_body))
    # drain it so we don't leave dangling load
    poll_until_completed(app, batch_id, timeout=120)


# --------------------------------------------------------------------------- #
# Section 3: POST /batches/upload (file upload) -- F1
# --------------------------------------------------------------------------- #

def test_upload_line_delimited(app: Client) -> None:
    resp = app.post("/batches/upload", data="hello\nworld\nfoo\nbar\n",
                     headers={"Content-Type": "text/plain"})
    body = safe_json(resp)
    check("line-delimited upload -> 202, total=4", resp.status_code == 202 and body and body.get("total") == 4,
          f"got {resp.status_code}: {body}")


def test_upload_blank_lines_skipped(app: Client) -> None:
    resp = app.post("/batches/upload", data="hello\n\n\nworld\n  \nfoo\n",
                     headers={"Content-Type": "text/plain"})
    body = safe_json(resp)
    check("blank lines skipped -> total=3", resp.status_code == 202 and body and body.get("total") == 3,
          f"got {resp.status_code}: {body}")


def test_upload_empty_body(app: Client) -> None:
    resp = app.post("/batches/upload", data="", headers={"Content-Type": "text/plain"})
    check("empty upload body -> 400", resp.status_code == 400, f"got {resp.status_code}: {resp.text}")


def test_upload_blank_only_body(app: Client) -> None:
    resp = app.post("/batches/upload", data="\n\n   \n", headers={"Content-Type": "text/plain"})
    check("all-blank upload body -> 400", resp.status_code == 400, f"got {resp.status_code}: {resp.text}")


def test_upload_unsupported_content_type(app: Client) -> None:
    resp = app.post("/batches/upload", data="hello\nworld\n", headers={"Content-Type": "application/xml"})
    check("unsupported upload content-type -> 415", resp.status_code == 415, f"got {resp.status_code}: {resp.text}")


def test_upload_json_via_upload_endpoint(app: Client) -> None:
    resp = app.post("/batches/upload", data=json.dumps({"prompts": ["a", "b", "c"]}),
                     headers={"Content-Type": "application/json"})
    body = safe_json(resp)
    check("JSON body via /upload -> 202, total=3", resp.status_code == 202 and body and body.get("total") == 3,
          f"got {resp.status_code}: {body}")


def test_upload_malformed_json(app: Client) -> None:
    resp = app.post("/batches/upload", data='{"prompts":', headers={"Content-Type": "application/json"})
    check("malformed JSON via /upload -> 400", resp.status_code == 400, f"got {resp.status_code}: {resp.text}")


# --------------------------------------------------------------------------- #
# Section 4/5: progress + results edge cases
# --------------------------------------------------------------------------- #

def test_progress_unknown_id(app: Client) -> None:
    resp = app.get("/batches/does-not-exist-" + uuid.uuid4().hex)
    check("progress for unknown id -> 404", resp.status_code == 404, f"got {resp.status_code}: {resp.text}")


def test_results_unknown_id(app: Client) -> None:
    resp = app.get("/batches/does-not-exist-" + uuid.uuid4().hex + "/results")
    check("results for unknown id -> 404", resp.status_code == 404, f"got {resp.status_code}: {resp.text}")


def test_progress_right_after_submit_never_queued(app: Client) -> None:
    resp = app.post("/batches", json={"prompts": ["a"]})
    body = safe_json(resp)
    assert resp.status_code == 202, f"create failed: {resp.status_code} {body}"
    prog_resp = app.get(f"/batches/{body['batchId']}")
    prog = safe_json(prog_resp)
    check("progress immediately after submit -> 200, status != QUEUED",
          prog_resp.status_code == 200 and prog and prog.get("status") != "QUEUED", str(prog))


# --------------------------------------------------------------------------- #
# Section 6: cross-cutting HTTP edge cases
# --------------------------------------------------------------------------- #

def test_wrong_http_methods(app: Client) -> None:
    resp1 = app.request("DELETE", "/batches/anything")
    check("DELETE /batches/{id} -> 405", resp1.status_code == 405, f"got {resp1.status_code}")
    resp2 = app.request("PUT", "/batches")
    check("PUT /batches -> 405", resp2.status_code == 405, f"got {resp2.status_code}")


def test_trailing_slash_equivalence(app: Client) -> None:
    resp = app.post("/batches", json={"prompts": ["x"]})
    body = safe_json(resp)
    assert resp.status_code == 202
    batch_id = body["batchId"]
    a = app.get(f"/batches/{batch_id}")
    b = app.get(f"/batches/{batch_id}/")
    check("trailing slash returns 200 too", b.status_code == 200, f"got {b.status_code}")
    check("trailing slash body matches non-slash body", safe_json(a) == safe_json(b))


def test_path_traversal_is_literal(app: Client) -> None:
    # requests/urllib3 (like most HTTP client libraries, unlike curl) normalizes ".."
    # path segments client-side before the request ever leaves the machine, so
    # app.get("/batches/../ping") would silently become a request for "/ping" and never
    # actually exercise the server's routing. Percent-encode the dots so the literal
    # "/batches/../ping" bytes reach the server's path-matching untouched.
    resp = app.get("/batches/%2E%2E/ping")
    body = safe_json(resp)
    check("'../ping' resolves to literal id lookup -> 404 (no traversal)",
          resp.status_code == 404, f"got {resp.status_code}: {body}")


def test_concurrent_submits_unique_ids(app: Client, n: int = 20) -> None:
    def submit(_: int) -> Optional[str]:
        resp = app.post("/batches", json={"prompts": ["x"]})
        body = safe_json(resp)
        return body.get("batchId") if resp.status_code == 202 and body else None

    with concurrent.futures.ThreadPoolExecutor(max_workers=n) as pool:
        ids = list(pool.map(submit, range(n)))

    ok_ids = [i for i in ids if i]
    check(f"{n} concurrent submits all succeeded", len(ok_ids) == n, f"{len(ok_ids)}/{n} succeeded")
    check(f"{n} concurrent submits got unique batchIds (N1/N2 sanity)", len(set(ok_ids)) == len(ok_ids),
          f"{len(set(ok_ids))} unique out of {len(ok_ids)}")


# --------------------------------------------------------------------------- #
# Main
# --------------------------------------------------------------------------- #

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base-url", default=os.environ.get("BASE_URL", "http://localhost:8080"))
    parser.add_argument("--admin-url", default=os.environ.get("ADMIN_URL"))
    parser.add_argument("--large-batch-size", type=int, default=int(os.environ.get("LARGE_BATCH_SIZE", "200")))
    parser.add_argument("--timeout", type=float, default=float(os.environ.get("POLL_TIMEOUT", "120")))
    parser.add_argument("-v", "--verbose", action="store_true")
    args = parser.parse_args()

    global VERBOSE
    VERBOSE = args.verbose

    app = Client(args.base_url)
    admin = Client(args.admin_url) if args.admin_url else None

    log(f"Target application: {args.base_url}")
    log(f"Target admin port : {args.admin_url or '(not provided - admin-only checks will SKIP)'}")

    section("1. Liveness")
    run_test("ping", lambda: test_ping(app))
    run_test("admin healthcheck", lambda: test_admin_healthcheck(admin))

    section("2. POST /batches (JSON) - happy path + full lifecycle (F1-F6)")
    run_test("create batch happy path", lambda: test_create_batch_happy_path(app))
    run_test("full lifecycle: create -> poll -> results", lambda: test_full_lifecycle(app, args.timeout))

    section("2b. POST /batches - validation & edge cases")
    run_test("empty prompts array -> 422", lambda: test_empty_prompts_array(app))
    run_test("missing prompts field -> 422", lambda: test_missing_prompts_field(app))
    run_test("malformed JSON -> 400", lambda: test_malformed_json(app))
    run_test("wrong content-type -> 415", lambda: test_wrong_content_type(app))
    run_test("non-string elements coerced -> 202", lambda: test_non_string_elements_coerced(app))
    run_test("duplicate prompts independent -> 202", lambda: test_duplicate_prompts_independent(app))

    section("2c. Concurrency / backpressure (N1, N2, N3, F3, F4)")
    run_test("large batch backpressure + completion", lambda: test_large_batch_backpressure(app, args.large_batch_size, args.timeout))
    run_test("results 409 while running", lambda: test_results_conflict_while_running(app, max(args.large_batch_size, 50)))
    run_test("concurrent submits get unique ids", lambda: test_concurrent_submits_unique_ids(app))

    section("3. POST /batches/upload (file upload) (F1)")
    run_test("line-delimited upload happy path", lambda: test_upload_line_delimited(app))
    run_test("blank lines skipped", lambda: test_upload_blank_lines_skipped(app))
    run_test("empty upload body -> 400", lambda: test_upload_empty_body(app))
    run_test("blank-only upload body -> 400", lambda: test_upload_blank_only_body(app))
    run_test("unsupported content-type -> 415", lambda: test_upload_unsupported_content_type(app))
    run_test("JSON via /upload -> 202", lambda: test_upload_json_via_upload_endpoint(app))
    run_test("malformed JSON via /upload -> 400", lambda: test_upload_malformed_json(app))

    section("4/5. Progress & results edge cases")
    run_test("progress unknown id -> 404", lambda: test_progress_unknown_id(app))
    run_test("results unknown id -> 404", lambda: test_results_unknown_id(app))
    run_test("progress right after submit never QUEUED", lambda: test_progress_right_after_submit_never_queued(app))

    section("6. Cross-cutting HTTP edge cases")
    run_test("wrong HTTP methods -> 405", lambda: test_wrong_http_methods(app))
    run_test("trailing slash equivalence", lambda: test_trailing_slash_equivalence(app))
    run_test("path traversal is literal (no traversal)", lambda: test_path_traversal_is_literal(app))

    log("\n" + "=" * 60)
    log(f"PASSED:  {RESULTS.passed}")
    log(f"FAILED:  {RESULTS.failed}")
    log(f"SKIPPED: {RESULTS.skipped}")
    if RESULTS.failures:
        log("\nFailed checks:")
        for name in RESULTS.failures:
            log(f"  - {name}")
    log("=" * 60)

    return 1 if RESULTS.failed else 0


if __name__ == "__main__":
    sys.exit(main())
