package com.bank.vam.mcp.pipeline;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code corporateId} (and equivalents) must never reach a tool from the
 * caller's own arguments — it comes only from the verified context.
 */
class McpArgumentStripperTest {

    private final McpArgumentStripper stripper = new McpArgumentStripper();

    @Test
    void removesScopeShapedArguments_keepsEverythingElse() {
        Map<String, Object> arguments = Map.of(
                "corporateId", "11111111-1111-1111-1111-111111111111",
                "corporate_id", "22222222-2222-2222-2222-222222222222",
                "userId", "someone-else",
                "currency", "USD",
                "limit", 25);

        Map<String, Object> cleaned = stripper.strip(arguments);

        assertThat(cleaned).containsOnlyKeys("currency", "limit");
        assertThat(cleaned.get("currency")).isEqualTo("USD");
    }

    @Test
    void emptyArguments_returnsEmptyMap() {
        assertThat(stripper.strip(null)).isEmpty();
        assertThat(stripper.strip(Map.of())).isEmpty();
    }
}
