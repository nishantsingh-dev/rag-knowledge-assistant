package com.nishant.ragassistant.service;

import com.nishant.ragassistant.dto.ChatResponse;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * This is the RAG "brain" - cache check, then retrieval, then generation.
 *
 * PHASE 3 (current): semantic cache is checked FIRST, before any vector
 * search or LLM call. Rate limiting happens even earlier than that - in
 * ChatRateLimitFilter, before the request ever reaches this class at all.
 * So the full defense-in-depth order for a request is:
 *   1. ChatRateLimitFilter  - reject if over quota (cheapest check, runs first)
 *   2. SemanticCacheService - reject an LLM call if we've essentially seen
 *      this question before (cheap-ish: one embedding call, no LLM call)
 *   3. This class            - only reached on an actual cache miss: real
 *      vector search + real LLM call (the expensive path)
 */
@Service
public class ChatService {

    private final VectorStore vectorStore;
    private final ChatClient chatClient;
    private final EmbeddingModel embeddingModel;
    private final SemanticCacheService semanticCacheService;

    // How many chunks to retrieve per question. Too low -> missing context.
    // Too high -> noisy prompt, higher token cost, possibly worse answers.
    // Start here, tune based on what you observe.
    private static final int TOP_K = 4;

    private static final String SYSTEM_PROMPT = """
            You are a knowledge assistant that answers questions using ONLY the
            provided context below. Do not use any outside knowledge.

            If the context does not contain enough information to answer the
            question, say clearly: "I don't have information about that in the
            provided documents." Do not guess or make up an answer.

            Context:
            {context}
            """;

    public ChatService(
            VectorStore vectorStore,
            ChatClient.Builder chatClientBuilder,
            EmbeddingModel embeddingModel,
            SemanticCacheService semanticCacheService) {
        this.vectorStore = vectorStore;
        this.chatClient = chatClientBuilder.build();
        this.embeddingModel = embeddingModel;
        this.semanticCacheService = semanticCacheService;
    }

    public ChatResponse ask(String question) {
        // Step 0: embed the question ONCE, up front, so both the cache lookup
        // and (on a miss) the eventual cache write use the same vector.
        //
        // HONEST INEFFICIENCY, worth naming if asked: vectorStore.similaritySearch()
        // below takes the raw question text and re-embeds it internally - Spring
        // AI's VectorStore interface doesn't expose a "search by pre-computed
        // vector" overload here. So a cache MISS currently embeds the question
        // twice total (once here, once inside similaritySearch). A cache HIT
        // only embeds once. Known optimization: a lower-level PgVectorStore API
        // call could accept the vector directly and skip the second embed.
        float[] queryEmbedding = embeddingModel.embed(question);

        // Step 1: check the semantic cache before doing anything expensive.
        Optional<ChatResponse> cached = semanticCacheService.findSimilar(queryEmbedding);
        if (cached.isPresent()) {
            return cached.get();
        }

        // Step 2: cache miss - do the real retrieval.
        List<Document> relevantChunks = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(question)
                        .topK(TOP_K)
                        .build()
        );

        if (relevantChunks.isEmpty()) {
            // Deliberately NOT cached - "no documents yet" is a transient state,
            // not a real answer worth reusing for a similar future question.
            return new ChatResponse(
                    "I don't have any documents to answer that from yet - try uploading some first.",
                    List.of(),
                    false
            );
        }

        // Step 3: build the context block from retrieved chunks.
        String context = relevantChunks.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n\n---\n\n"));

        // Step 4: call the LLM with context-only instructions (see SYSTEM_PROMPT).
        // This is the single most important design choice for reducing
        // hallucination - the model is explicitly told not to use outside knowledge.
        String answer = chatClient.prompt()
                .system(SYSTEM_PROMPT.replace("{context}", context))
                .user(question)
                .call()
                .content();

        // Step 5: build citations so the answer isn't a black box.
        List<ChatResponse.Source> sources = relevantChunks.stream()
                .map(doc -> new ChatResponse.Source(
                        String.valueOf(doc.getMetadata().get("documentId")),
                        String.valueOf(doc.getMetadata().get("filename")),
                        doc.getText().substring(0, Math.min(150, doc.getText().length())) + "..."
                ))
                .distinct()
                .collect(Collectors.toList());

        ChatResponse response = new ChatResponse(answer, sources, false);

        // Step 6: store in the semantic cache for next time, using the SAME
        // embedding computed in Step 0 - no extra embed call needed here.
        semanticCacheService.store(question, queryEmbedding, response);

        return response;
    }
}
