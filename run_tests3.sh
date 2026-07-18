#!/usr/bin/env bash
cd /workspaces/prompt-batch-service
BASE=http://localhost:8080

section() { echo; echo "##### $1 #####"; }

section "409 conflict: submit big batch, immediately hit /results"
RESP=$(curl -s -X POST "$BASE/batches" -H 'Content-Type: application/json' -d '{"prompts":["a","b","c","d","e","f","g","h","i","j","k","l","m","n","o","p","q","r","s","t"]}')
echo "$RESP"
BID=$(echo "$RESP" | python3 -c "import sys,json; print(json.load(sys.stdin)['batchId'])")
curl -s -i "$BASE/batches/$BID/results"
echo

section "Wait for it to complete and check results shape"
for i in $(seq 1 60); do
  STATUS=$(curl -s "$BASE/batches/$BID" | python3 -c "import sys,json; print(json.load(sys.stdin)['status'])")
  [ "$STATUS" = "COMPLETED" ] && break
  sleep 0.2
done
curl -s "$BASE/batches/$BID/results"
echo

section "500-prompt batch: check for any FAILED entries after retries"
RESP2=$(python3 -c 'import json; print(json.dumps({"prompts":[f"p{i}" for i in range(500)]}))' | curl -s -X POST "$BASE/batches" -H 'Content-Type: application/json' --data-binary @-)
echo "$RESP2"
BID2=$(echo "$RESP2" | python3 -c "import sys,json; print(json.load(sys.stdin)['batchId'])")
for i in $(seq 1 120); do
  STATUS=$(curl -s "$BASE/batches/$BID2" | python3 -c "import sys,json; print(json.load(sys.stdin)['status'])")
  [ "$STATUS" = "COMPLETED" ] && break
  sleep 0.3
done
curl -s "$BASE/batches/$BID2" ; echo
curl -s "$BASE/batches/$BID2/results" | python3 -c "
import sys, json
d = json.load(sys.stdin)
results = d['results']
failed = [r for r in results if r['outcome'] == 'FAILED']
print('total results:', len(results))
print('failed count:', len(failed))
if failed:
    print('sample failed entry:', json.dumps(failed[0], indent=2))
print('sample success entry:', json.dumps(next(r for r in results if r['outcome']=='SUCCESS'), indent=2))
"

section "trailing slash re-check with -L off, exact status"
curl -s -o /dev/null -w 'trailing slash status: %{http_code}\n' "$BASE/batches/$BID/"

section "double-check unsupported content type still 415 with different type"
printf "hello\n" | curl -s -i -X POST "$BASE/batches/upload" -H 'Content-Type: application/xml' --data-binary @-
echo
