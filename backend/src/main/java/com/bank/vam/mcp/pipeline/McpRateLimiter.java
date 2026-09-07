package com.bank.vam.mcp.pipeline;

import com.bank.vam.mcp.McpException;
import com.bank.vam.mcp.McpProperties;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Per-tool-name rate limiting via the already-declared-but-previously-unused
 * {@code resilience4j-spring-boot3} dependency.
 *
 * <p>Phase 1 keys the limiter by tool name only — there is no caller identity
 * yet to key by. This means, for now, one caller can exhaust a tool's budget
 * for everyone; that's an accepted gap until Phase 3's signed context gives
 * this a real per-caller key ({@code caller.subject()}), not a design goal.
 */
@Component
@RequiredArgsConstructor
public class McpRateLimiter {

    private final RateLimiterRegistry registry;
    private final McpProperties properties;

    public void acquire(String toolName) {
        RateLimiter limiter = registry.rateLimiter("mcp-tool:" + toolName, this::config);
        if (!limiter.acquirePermission()) {
            throw McpException.rateLimited(toolName);
        }
    }

    private RateLimiterConfig config() {
        McpProperties.RateLimit config = properties.getRateLimit();
        return RateLimiterConfig.custom()
                .limitForPeriod(config.getLimitForPeriod())
                .limitRefreshPeriod(Duration.ofSeconds(config.getLimitRefreshPeriodSeconds()))
                .timeoutDuration(Duration.ofMillis(config.getTimeoutMillis()))
                .build();
    }
}
