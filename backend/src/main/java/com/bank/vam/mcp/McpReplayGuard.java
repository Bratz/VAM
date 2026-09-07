package com.bank.vam.mcp;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks signed-context {@code jti} values already used, so a captured
 * context can't be replayed within its (short) validity window. Same
 * optional-Redis-with-in-memory-fallback pattern as {@code
 * ForecastScheduler}'s mutex: Redis when available (correct across multiple
 * backend instances), an in-process map otherwise (correct for this single
 * demo instance, not for a real multi-node deployment — same documented
 * limitation as the forecast scheduler's fallback). Falls back on both "no
 * Redis bean wired" AND "bean wired but the server is unreachable" — the
 * autoconfigured {@link StringRedisTemplate} bean exists whenever the
 * starter is on the classpath, whether or not a server is actually running.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpReplayGuard {

    /** Comfortably longer than ContextJwtService's context TTL (60s) so a
     *  jti can't become reusable by simply outliving the nonce record. */
    private static final Duration NONCE_RETENTION = Duration.ofMinutes(5);
    private static final String REDIS_KEY_PREFIX = "mcp:signed-context-jti:";

    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;
    private final Map<String, Instant> inMemorySeen = new ConcurrentHashMap<>();

    /** @return true the first time a given jti is seen; false on every subsequent (replay) use. */
    public boolean markUsedIfNew(String jti) {
        StringRedisTemplate redis = redisTemplateProvider.getIfAvailable();
        if (redis != null) {
            try {
                Boolean firstUse = redis.opsForValue().setIfAbsent(REDIS_KEY_PREFIX + jti, "1", NONCE_RETENTION);
                return Boolean.TRUE.equals(firstUse);
            } catch (RedisConnectionFailureException redisDown) {
                log.warn("Redis replay-guard unavailable for jti {} ({}); falling back to local map",
                        jti, redisDown.getMessage());
            } catch (RuntimeException unexpected) {
                log.warn("Redis replay-guard errored for jti {} ({}: {}); falling back to local map",
                        jti, unexpected.getClass().getSimpleName(), unexpected.getMessage());
            }
        }
        evictExpired();
        return inMemorySeen.putIfAbsent(jti, Instant.now().plus(NONCE_RETENTION)) == null;
    }

    private void evictExpired() {
        Instant now = Instant.now();
        inMemorySeen.values().removeIf(expiresAt -> expiresAt.isBefore(now));
    }
}
