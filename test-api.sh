#!/usr/bin/env bash
# Phase 3 version: adds tests for the semantic cache and the rate limiter,
# on top of the Phase 2 async ingestion tests.
#
# Usage: ./test-api.sh

set -e

BASE_URL="http://localhost:8080"
SAMPLE_DOC="sample-docs/rate-limiter-runbook.txt"

echo "== 1. Uploading sample document (should return almost instantly) =="
UPLOAD_RESPONSE=$(curl -s -F "file=@${SAMPLE_DOC}" "${BASE_URL}/api/documents/upload")
echo "$UPLOAD_RESPONSE"
DOCUMENT_ID=$(echo "$UPLOAD_RESPONSE" | grep -o '"documentId":"[^"]*"' | cut -d'"' -f4)
echo "Extracted documentId: ${DOCUMENT_ID}"
echo -e "\n"

echo "== 2. Polling status until READY =="
for i in $(seq 1 20); do
  STATUS_RESPONSE=$(curl -s "${BASE_URL}/api/documents/${DOCUMENT_ID}/status")
  STATUS=$(echo "$STATUS_RESPONSE" | grep -o '"status":"[^"]*"' | cut -d'"' -f4)
  echo "  [attempt ${i}] status = ${STATUS}"
  if [ "$STATUS" = "READY" ]; then
    echo "  Document is ready."
    break
  fi
  if [ "$STATUS" = "FAILED" ]; then
    echo "  Ingestion FAILED - check the Kafka consumer logs in your Eclipse console."
    exit 1
  fi
  sleep 1
done
echo -e "\n"

echo "== 3. First question (should be a CACHE MISS - fromCache:false, real LLM call) =="
curl -s -X POST "${BASE_URL}/api/chat" \
  -H "Content-Type: application/json" \
  -d '{"question": "At what request rate did the rate limiter reach saturation?"}'
echo -e "\n"

echo "== 4. Same question, reworded (should be a CACHE HIT - fromCache:true, no LLM call) =="
sleep 1
curl -s -X POST "${BASE_URL}/api/chat" \
  -H "Content-Type: application/json" \
  -d '{"question": "What request rate caused the limiter to saturate?"}'
echo -e "\n"

echo "== 5. Unrelated question (should still correctly refuse - proves cache isn't over-matching) =="
curl -s -X POST "${BASE_URL}/api/chat" \
  -H "Content-Type: application/json" \
  -d '{"question": "What is the capital of France?"}'
echo -e "\n"

echo "== 6. Rate limit test: firing 12 requests fast (limit is 10/60s - expect the last ones to 429) =="
for i in $(seq 1 12); do
  STATUS_CODE=$(curl -s -o /tmp/rate_test_response.json -w "%{http_code}" -X POST "${BASE_URL}/api/chat" \
    -H "Content-Type: application/json" \
    -d '{"question": "test question for rate limiting"}')
  echo "  [request ${i}] HTTP ${STATUS_CODE}"
done
echo -e "\n"

echo "Done. What to check:"
echo "  - Step 3: fromCache should be false"
echo "  - Step 4: fromCache should be true, and the answer should be near-identical to Step 3's,"
echo "            even though the wording of the question was different - that's semantic matching working"
echo "  - Step 5: should correctly refuse, not falsely match the cached rate-limiter answer"
echo "  - Step 6: first ~10 requests should return 200, the rest should return 429"
echo "            (note: Steps 3-5 also count against the same limit, so you may see 429s"
echo "             appear earlier than request 11 depending on how many ran before this loop)"
