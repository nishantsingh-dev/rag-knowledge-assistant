package com.nishant.ragassistant.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

/**
 * Sliding-window-log rate limiter, backed by Redis. Same algorithm family as
 * the distributed rate limiter described on the resume - implemented here
 * from scratch for this project so it's independently explainable, not
 * copy-pasted from that other codebase.
 *
 * HOW THE SLIDING WINDOW LOG WORKS:
 * For each client, we keep a Redis sorted set (ZSET) where the score is the
 * request timestamp (epoch millis) and the member is a unique request ID.
 * On each request:
 *   1. Remove all entries older than (now - window) - these have "aged out"
 *   2. Count what's left - if it's >= the limit, reject
 *   3. Otherwise, add this request's timestamp and allow it
 *
 * This is more accurate than a simple fixed-window counter (which lets a
 * client burst up to 2x the limit right at a window boundary) at the cost
 * of storing one entry per request instead of a single counter - a real
 * memory-vs-accuracy tradeoff worth naming if asked.
 */
@Service
public class ChatRateLimiterService {

    private final StringRedisTemplate redisTemplate;
    private final int maxRequests;
    private final Duration window;

    public ChatRateLimiterService(
            StringRedisTemplate redisTemplate,
            @Value("${chat.rate-limit.max-requests}") int maxRequests,
            @Value("${chat.rate-limit.window-seconds}") long windowSeconds) {
        this.redisTemplate = redisTemplate;
        this.maxRequests = maxRequests;
        this.window = Duration.ofSeconds(windowSeconds);
    }

    /**
     * @param clientKey identifies who's making the request - IP address here
     *                  since this demo has no auth. In a real multi-tenant
     *                  system this would be a user ID or API key instead.
     */
    public boolean isAllowed(String clientKey) {
        String redisKey = "ratelimit:chat:" + clientKey;
        long now = System.currentTimeMillis();
        long windowStartMillis = now - window.toMillis();

        // Step 1: evict expired entries from the window
        redisTemplate.opsForZSet().removeRangeByScore(redisKey, 0, windowStartMillis);

        // Step 2: count what's left inside the current window
        Long currentCount = redisTemplate.opsForZSet().zCard(redisKey);
        if (currentCount != null && currentCount >= maxRequests) {
            return false;   // caller should respond 429
        }

        // Step 3: record this request and let it through
        redisTemplate.opsForZSet().add(redisKey, UUID.randomUUID().toString(), now);
        redisTemplate.expire(redisKey, window);   // let Redis clean up if this client goes idle

        return true;
    }
}
