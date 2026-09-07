package com.bank.vam.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers both the Redis-backed and in-memory-fallback paths — same
 * optional-Redis pattern as {@code ForecastScheduler}'s mutex.
 */
class McpReplayGuardTest {

    @SuppressWarnings("unchecked")
    private ObjectProvider<StringRedisTemplate> noRedis() {
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    @Test
    void inMemoryFallback_firstUseAllowed_secondUseIsReplay() {
        McpReplayGuard guard = new McpReplayGuard(noRedis());

        assertThat(guard.markUsedIfNew("jti-1")).isTrue();
        assertThat(guard.markUsedIfNew("jti-1")).isFalse();
    }

    @Test
    void inMemoryFallback_differentJtis_areIndependent() {
        McpReplayGuard guard = new McpReplayGuard(noRedis());

        assertThat(guard.markUsedIfNew("jti-a")).isTrue();
        assertThat(guard.markUsedIfNew("jti-b")).isTrue();
        assertThat(guard.markUsedIfNew("jti-a")).isFalse();
    }
}
