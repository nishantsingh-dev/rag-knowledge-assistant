# Event-Driven RAG Knowledge Assistant — Phase 3

Phases 1 and 2 proved the RAG loop works and made ingestion async. This phase
adds the two pieces that make `/chat` production-viable rather than just a
demo: a semantic cache (stop paying for LLM calls on repeat questions) and a
rate limiter (stop one client from exhausting your LLM budget).

## What's in this phase vs. later phases

| Phase | What it adds | Status |
|---|---|---|
| 1 | Upload → extract → chunk → embed → store. Ask → retrieve → prompt → answer with citations. | ✅ Done |
| 2 | Kafka: upload publishes an event, a consumer does the actual ingestion work asynchronously | ✅ Done |
| **3 (this code)** | Redis: semantic cache in front of the LLM call, rate limiter in front of `/chat` | Build this now |
| 4 | Docker Compose for everything, polish, push to GitHub | Not yet built |

## What's in this zip

```
rag-knowledge-assistant/
├── pom.xml                       ← now includes spring-boot-starter-data-redis
├── docker-compose.yml
├── init.sql
├── .gitignore
├── test-api.sh                   ← now tests semantic cache hits and rate limiting
├── sample-docs/
│   └── rate-limiter-runbook.txt
├── README.md
└── src/main/
    ├── java/com/nishant/ragassistant/
    │   ├── RagAssistantApplication.java
    │   ├── controller/DocumentController.java
    │   ├── controller/ChatController.java
    │   ├── service/IngestionService.java
    │   ├── service/IngestionConsumer.java
    │   ├── service/DocumentStatusService.java
    │   ├── service/ChatService.java              ← rewritten: checks cache before searching/calling the LLM
    │   ├── service/SemanticCacheService.java      ← NEW: cosine-similarity cache over Redis
    │   ├── service/ChatRateLimiterService.java    ← NEW: sliding-window-log limiter over Redis
    │   ├── event/DocumentUploadedEvent.java
    │   ├── config/KafkaTopicConfig.java
    │   ├── config/ChatRateLimitFilter.java        ← NEW: applies the limiter to /api/chat before the controller runs
    │   └── dto/ (UploadResponse, ChatRequest, ChatResponse)
    └── resources/application.yml     ← now includes Redis connection + rate-limit/cache tuning properties
```

**Still not included, on purpose:** the Maven wrapper. Eclipse's m2e handles the
build without it.

## What changed from Phase 2

- **`ChatRateLimitFilter`** now sits in front of `/api/chat`, rejecting requests over quota with `429` before they ever reach the controller — meaning before they'd trigger an expensive vector search or LLM call. This is a sliding-window-log limiter over Redis, the same algorithm family described on the resume for the standalone rate limiter project, reimplemented here from scratch.
- **`SemanticCacheService`** checks for a semantically similar prior question before doing any retrieval or LLM call. On a hit, `ChatResponse.fromCache` is `true` and no LLM call happens at all.
- **`ChatService`** now embeds the question once up front, checks the cache, and only does real retrieval + generation on a miss — then stores the result for next time.
- Default limits (tune these in `application.yml`): **10 requests/60s** per client (by IP), semantic cache similarity threshold **0.92**, cache entries expire after **24 hours**.

## Setup

1. **Start infrastructure** (Redis was already included from Phase 1 — nothing new to add here, it's just actually used now):
   ```bash
   docker compose up -d
   ```

2. **Pull the Ollama models** (skip if already done):
   ```bash
   docker exec -it rag-ollama ollama pull llama3.2
   docker exec -it rag-ollama ollama pull nomic-embed-text
   ```

3. **Refresh in Eclipse:** right-click the project → Maven → Update Project (Force Update) to pick up the new `spring-boot-starter-data-redis` dependency.

4. **Run:** right-click `RagAssistantApplication.java` → Run As → Java Application.

5. **Test everything:**
   ```bash
   chmod +x test-api.sh
   ./test-api.sh
   ```
   Watch Step 3 vs Step 4 closely — same underlying question, different wording, and Step 4 should come back with `"fromCache":true` and (usually) noticeably faster, since it skips the LLM call entirely. Step 6 fires a burst of requests and you should see HTTP status codes shift from `200` to `429` partway through.

   To inspect the cache directly:
   ```bash
   docker exec -it rag-redis redis-cli KEYS "chatcache:*"
   docker exec -it rag-redis redis-cli GET "chatcache:<some-key-from-above>"
   ```

## How the pieces map to the architecture (and the interview prep doc)

- `IngestionService` = the "extract, chunk, embed, store" box in the ingestion diagram.
- `ChatService` = the "vector search" + "LLM call" boxes in the query diagram, combined.
- `DocumentController` / `ChatController` = the thin HTTP layer — they don't contain logic, just delegate.
- The `SYSTEM_PROMPT` in `ChatService` is the concrete implementation of the
  "context-only prompting" tradeoff discussed in section 4.3 of the interview prep doc.

Read the comments in `IngestionService.java` and `ChatService.java` closely —
they're written to explain *why*, not just *what*, since that's what you need
to be able to answer interview questions rather than just recite the pitch.

## Concepts to understand before/while building this (see also the video list I sent earlier)

- **Embeddings**: a chunk of text turned into a list of numbers (a vector) that
  captures its meaning. Similar meaning → similar vector.
- **Cosine similarity**: the math used to measure how "close" two vectors are —
  this is what `similaritySearch` uses under the hood to find relevant chunks.
- **Top-K**: how many of the closest-matching chunks to retrieve. Set to 4 here — tune it.
- **Chunking / token splitting**: why documents get split into pieces before
  embedding (embedding models have a token limit, and smaller chunks retrieve more precisely).
- **HNSW index**: the approximate-nearest-neighbor index type configured in
  `application.yml` — makes similarity search fast even as the number of chunks grows.

## Once this is running and you've tested it

1. Ask the same question two different ways and confirm the second is a cache hit (`fromCache: true`) — that's semantic matching, not exact-text matching, actually working.
2. Fire enough requests fast enough to trigger a `429` — confirm the rate limiter actually blocks, not just that it exists in code.
3. Try a question deliberately similar-but-different to a cached one (e.g., ask about a *different* number than what's cached) and confirm it does NOT incorrectly return the cached answer — this is the real risk of semantic caching, worth deliberately testing, not just hoping the threshold is right.
4. Update `RAG_Project_Interview_Prep.md` section 6 with everything you've hit across all three phases so far.
5. Push to GitHub — three full phases now working is a genuinely strong project to point to.
6. When ready: k6 load-test `/chat` the same way you did the standalone rate limiter — this is what would take the "(In Progress)" resume bullet to the same evidence-backed standard as your best one.

## Known gaps in this phase (be upfront about these in interviews)

- **Semantic cache uses `KEYS` + linear scan, not a real vector index** — fine at demo scale, would not scale to a large cache. See the detailed comment in `SemanticCacheService.java` for the honest tradeoff (RediSearch vector index or pgvector would be the real fix).
- **Rate limit key is IP-based**, not user/API-key based — fine for a demo with no auth, wrong for a real multi-tenant system (one IP could represent many users behind NAT/a corporate proxy).
- **Cache invalidation on document update doesn't exist** — if you re-upload a changed document, previously-cached answers based on the old version will keep being served until their TTL expires. A real fix would tie cache entries to a document version.
- **Question embedded up to twice on a cache miss** (once for the cache lookup, once inside `vectorStore.similaritySearch`) — a known, named inefficiency, not an oversight. See the comment in `ChatService.java`.
- **No dead-letter handling for failed ingestion** (from Phase 2, still true)
- **Status tracking is in-memory only** (from Phase 2, still true)
- **Uploaded files are never cleaned up** (from Phase 2, still true)
- **No document deletion/update endpoint yet**
- **No automated tests yet**
- **No idempotency check on re-upload** — truncate between test runs if needed: `docker exec -it rag-postgres psql -U rag -d ragdb -c "TRUNCATE vector_store;"`. To clear the semantic cache too: `docker exec -it rag-redis redis-cli --scan --pattern "chatcache:*" | xargs -r docker exec -i rag-redis redis-cli DEL` (or just `docker exec -it rag-redis redis-cli FLUSHDB` for a full wipe during testing)
