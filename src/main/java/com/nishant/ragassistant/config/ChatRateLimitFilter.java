package com.nishant.ragassistant.config;

import com.nishant.ragassistant.service.ChatRateLimiterService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Sits in front of /api/chat and rejects requests over the configured limit
 * with HTTP 429 - BEFORE they reach the controller, and therefore before
 * they'd trigger a vector search or an LLM call. This is the actual point
 * of rate limiting an AI endpoint: LLM calls cost real money, so the limiter
 * needs to reject cheaply and early, not after the expensive work is done.
 */
@Component
public class ChatRateLimitFilter extends OncePerRequestFilter {

    private final ChatRateLimiterService rateLimiterService;

    public ChatRateLimitFilter(ChatRateLimiterService rateLimiterService) {
        this.rateLimiterService = rateLimiterService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        if (request.getRequestURI().startsWith("/api/chat")) {
            String clientKey = request.getRemoteAddr();   // IP-based for this demo - see class javadoc on ChatRateLimiterService

            if (!rateLimiterService.isAllowed(clientKey)) {
                response.setStatus(429);
                response.setContentType("application/json");
                response.getWriter().write("{\"error\":\"Rate limit exceeded. Try again shortly.\"}");
                return;   // do NOT call filterChain.doFilter - request stops here, never reaches the controller
            }
        }

        filterChain.doFilter(request, response);
    }
}
