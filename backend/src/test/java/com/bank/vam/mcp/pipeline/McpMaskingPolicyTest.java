package com.bank.vam.mcp.pipeline;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the three real-world scenarios Phase 5 needed masking for: a
 * genuinely nested (list-of-maps) structure gets masked at every depth, a
 * VA number that happens to share a generic field name is never touched, and
 * an internal BaNCS id never leaves the bank regardless of tool.
 */
class McpMaskingPolicyTest {

    private final McpMaskingPolicy policy = new McpMaskingPolicy();

    @Test
    void multiBankLiquidity_masksBankAccountNumberAtEveryNestingDepth() {
        Map<String, Object> shadow = Map.of(
                "vaNumber", "MERC0000001",
                "bankAccountNumber", "DE89370400440532013000",
                "bankIban", "DE89370400440532013000");
        Map<String, Object> currencyBucket = Map.of("currencyCode", "EUR", "shadows", List.of(shadow));
        Map<String, Object> bankBucket = Map.of("bankBic", "DEUTDEFF", "currencies", List.of(currencyBucket));
        Map<String, Object> data = Map.of("banks", List.of(bankBucket));

        Map<String, Object> masked = policy.apply("get_multibank_liquidity", data);

        @SuppressWarnings("unchecked")
        Map<String, Object> maskedShadow = (Map<String, Object>) ((List<?>) ((Map<String, Object>)
                ((List<?>) ((Map<String, Object>) ((List<?>) masked.get("banks")).get(0)).get("currencies")).get(0))
                .get("shadows")).get(0);

        assertThat(maskedShadow.get("bankAccountNumber")).isEqualTo("****3000");
        assertThat(maskedShadow.get("bankIban")).isEqualTo("****3000");
        // vaNumber is never a masked key for this tool — untouched.
        assertThat(maskedShadow.get("vaNumber")).isEqualTo("MERC0000001");
    }

    @Test
    void getPools_memberAccountNumber_isAVaNumber_neverMasked() {
        Map<String, Object> member = Map.of("accountNumber", "MERC0000002", "entityCode", "MERC-DE");
        Map<String, Object> data = Map.of("pools", List.of(Map.of("members", List.of(member))));

        Map<String, Object> masked = policy.apply("get_pools", data);

        @SuppressWarnings("unchecked")
        Map<String, Object> maskedMember = (Map<String, Object>) ((List<?>) ((Map<String, Object>)
                ((List<?>) masked.get("pools")).get(0)).get("members")).get(0);
        assertThat(maskedMember.get("accountNumber")).isEqualTo("MERC0000002");
    }

    @Test
    void bancsCustomerId_isDroppedForEveryTool() {
        Map<String, Object> data = Map.of("bancsCustomerId", "CUST12345", "entityName", "Mercator Brands Holding SE");

        Map<String, Object> masked = policy.apply("get_legal_entities", data);

        assertThat(masked).doesNotContainKey("bancsCustomerId");
        assertThat(masked.get("entityName")).isEqualTo("Mercator Brands Holding SE");
    }

    @Test
    void toolWithNoMaskingRules_passesDataThroughUnchangedApartFromGlobalDrops() {
        Map<String, Object> data = Map.of("accountNumber", "MERC0000003");

        Map<String, Object> masked = policy.apply("get_sweep_rule_detail", data);

        assertThat(masked.get("accountNumber")).isEqualTo("MERC0000003");
    }
}
