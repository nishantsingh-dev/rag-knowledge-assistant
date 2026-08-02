package com.nishant.ragassistant.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nishant.ragassistant.dto.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Caches chat answers by MEANING, not exact text match - "How do I reset my
 * password?" and "password reset steps?" should hit the same cache entry.
 *
 * HONEST LIMITATION, worth stating upfront: plain Redis (the redis:7-alpine
 * image used here) has no vector search built in - that requires the
 * RediSearch module (or a product like Redis Enterprise / RedisVL). Rather
 * than add that dependency for a demo project, this does a linear scan over
 * cached entries and computes cosine similarity in application code. That's
 * fine at the cache sizes this project will ever see, but would NOT scale to
 * a large cache - a real production version would either enable RediSearch's
 * vector index, or route semantic caching through pgvector instead of Redis.
 * This tradeoff is worth naming directly if asked "does this scale?"
 */
@Service
public class SemanticCacheService {

    private static final Logger log = LoggerFactory.getLogger(SemanticCacheService.class);
    private static final String CACHE_PREFIX = "chatcache:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final double similarityThreshold;
    private final Duration ttl;

    public SemanticCacheService(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            @Value("${chat.semantic-cache.similarity-threshold}") double similarityThreshold,
            @Value("${chat.semantic-cache.ttl-hours}") long ttlHours) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.similarityThreshold = similarityThreshold;
        this.ttl = Duration.ofHours(ttlHours);
    }

    private record CacheEntry(String question, float[] embedding, String answer, List<ChatResponse.Source> sources) {}

    public Optional<ChatResponse> findSimilar(float[] queryEmbedding) {
        Set<String> keys = redisTemplate.keys(CACHE_PREFIX + "*");
        // NOTE: `keys()` does a full scan and blocks the Redis server on large
        // keyspaces - fine at demo scale, but production Redis code should use
        // `scan()` with a cursor instead. Left as `keys()` here for readability
        // since this project's cache will only ever hold a handful of entries.
        if (keys == null || keys.isEmpty()) {
            return Optional.empty();
        }

        for (String key : keys) {
            String json = redisTemplate.opsForValue().get(key);
            if (json == null) continue;

            try {
                CacheEntry entry = objectMapper.readValue(json, CacheEntry.class);
                double similarity = cosineSimilarity(queryEmbedding, entry.embedding());

                if (similarity >= similarityThreshold) {
                    log.info("Semantic cache HIT (similarity={}) for cached question: \"{}\"",
                            String.format("%.4f", similarity), entry.question());
                    return Optional.of(new ChatResponse(entry.answer(), entry.sources(), true));
                }
            } catch (Exception e) {
                log.warn("Failed to parse cache entry for key {}, skipping", key, e);
            }
        }

        return Optional.empty();
    }

    public void store(String question, float[] embedding, ChatResponse response) {
        try {
            CacheEntry entry = new CacheEntry(question, embedding, response.answer(), response.sources());
            String key = CACHE_PREFIX + UUID.randomUUID();
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(entry), ttl);
        } catch (Exception e) {
            // Cache write failing should never break the actual chat response -
            // log it and move on. A cache is an optimization, not a dependency
            // the core feature should fail over.
            log.warn("Failed to write semantic cache entry, continuing without caching this answer", e);
        }
    }

    private double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) return 0.0;
        double dot = 0.0, normA = 0.0, normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) return 0.0;
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
