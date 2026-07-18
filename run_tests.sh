#!/usr/bin/env bash
cd /workspaces/prompt-batch-service
BASE=http://localhost:8080
ADMIN=http://localhost:8081

section() { echo; echo "##### $1 #####"; }

section "2. Happy path create (JSON)"
RESP=$(curl -s -i -X POST "$BASE/batches" -H 'Content-Type: application/json' -d '{"prompts":["hello","world","foo","bar"]}')
echo "$RESP"
BATCH_ID=$(echo "$RESP" | tail -1 | python3 -c "import sys,json; print(json.load(sys.stdin)['batchId'])")
echo ">>> BATCH_ID=$BATCH_ID"

section "2.1 empty prompts array -> expect 422"
curl -s -i -X POST "$BASE/batches" -H 'Content-Type: application/json' -d '{"prompts":[]}'; echo

section "2.2 missing prompts field -> expect 422"
curl -s -i -X POST "$BASE/batches" -H 'Content-Type: application/json' -d '{}'; echo

section "2.3 malformed JSON -> expect 400"
curl -s -i -X POST "$BASE/batches" -H 'Content-Type: application/json' -d '{"prompts": "not-an-array"'; echo

section "2.4 missing Content-Type -> expect 415"
curl -s -i -X POST "$BASE/batches" -d '{"prompts":["hello"]}'; echo

section "2.5 non-string elements -> expect 400"
curl -s -i -X POST "$BASE/batches" -H 'Content-Type: application/json' -d '{"prompts":[1,2,3]}'; echo

section "2.6 large batch (500 prompts) -> expect 202 total:500"
python3 -c 'import json; print(json.dumps({"prompts":[f"prompt-{i}" for i in range(500)]}))' | curl -s -i -X POST "$BASE/batches" -H 'Content-Type: application/json' --data-binary @-; echo

section "2.7 duplicate prompt text -> expect 202 total:3"
curl -s -i -X POST "$BASE/batches" -H 'Content-Type: application/json' -d '{"prompts":["same","same","same"]}'; echo

section "3.1 upload line-delimited -> expect 202 total:4"
printf "hello\nworld\nfoo\nbar\n" | curl -s -i -X POST "$BASE/batches/upload" -H 'Content-Type: text/plain' --data-binary @-; echo

section "3.2 blank lines skipped -> expect 202 total:3"
printf "hello\n\n\nworld\n  \nfoo\n" | curl -s -i -X POST "$BASE/batches/upload" -H 'Content-Type: text/plain' --data-binary @-; echo

section "3.3 empty upload body -> expect 400"
printf "" | curl -s -i -X POST "$BASE/batches/upload" -H 'Content-Type: text/plain' --data-binary @-; echo

section "3.4 only blank lines -> expect 400"
printf "\n\n   \n" | curl -s -i -X POST "$BASE/batches/upload" -H 'Content-Type: text/plain' --data-binary @-; echo

section "3.5 unsupported content type -> expect 400"
printf "hello\nworld\n" | curl -s -i -X POST "$BASE/batches/upload" -H 'Content-Type: application/xml' --data-binary @-; echo

section "3.6 no Content-Type -> expect 400"
printf "hello\nworld\n" | curl -s -i -X POST "$BASE/batches/upload" --data-binary @-; echo

section "3.7 JSON via upload endpoint -> expect 202 total:3"
printf '{"prompts":["a","b","c"]}' | curl -s -i -X POST "$BASE/batches/upload" -H 'Content-Type: application/json' --data-binary @-; echo

section "3.8 malformed JSON via upload -> expect 400"
printf '{"prompts":' | curl -s -i -X POST "$BASE/batches/upload" -H 'Content-Type: application/json' --data-binary @-; echo

section "4.1 unknown batch id -> expect 404"
curl -s -i "$BASE/batches/does-not-exist"; echo

section "4. progress for happy-path batch"
curl -s -i "$BASE/batches/$BATCH_ID"; echo

section "5.3 results unknown id -> expect 404"
curl -s -i "$BASE/batches/does-not-exist/results"; echo

section "6.1 wrong HTTP method -> expect 405"
curl -s -i -X DELETE "$BASE/batches/$BATCH_ID"; echo
curl -s -i -X PUT "$BASE/batches"; echo

section "6.2 trailing slash -> expect 404"
curl -s -i "$BASE/batches/$BATCH_ID/"; echo

section "Wait for happy-path batch to complete, then fetch results"
for i in $(seq 1 60); do
  STATUS=$(curl -s "$BASE/batches/$BATCH_ID" | python3 -c "import sys,json; print(json.load(sys.stdin)['status'])")
  if [ "$STATUS" = "COMPLETED" ]; then
    echo "completed after $i polls"
    break
  fi
  sleep 0.3
done
curl -s "$BASE/batches/$BATCH_ID"; echo
section "5.1 results after completion -> expect 200"
curl -s -i "$BASE/batches/$BATCH_ID/results"; echo
