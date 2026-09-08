package com.bank.vam.mcp.pipeline;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Recursively walks a tool's result {@code data} map (which may nest Maps and
 * Lists of Maps to arbitrary depth — e.g. {@code get_pools}' {@code members}
 * list, {@code get_multibank_liquidity}'s bank→currency→shadow tree) and:
 * <ul>
 *   <li>drops any key in {@link #GLOBAL_DROP_KEYS} everywhere, regardless of
 *       tool (unambiguous internal identifiers — never meant to leave the bank)</li>
 *   <li>truncates any key in that tool's entry of {@link #MASK_KEYS_BY_TOOL} to
 *       its last 4 characters, prefixed with {@code "****"}</li>
 * </ul>
 *
 * <p>Keyed by tool name, not globally by field name, because the same generic
 * key (e.g. {@code accountNumber}) means a Virtual Account number on some
 * tools — {@code get_pools}' {@code PoolMember.accountNumber}, {@code
 * get_sweep_rule_detail}'s source accounts, {@code get_sweep_executions}'
 * source/target accounts — all traced to {@code va.getVaNumber()} at their
 * write sites, so never masked — and a real external bank account/IBAN number
 * on others (the ISO 20022 statement's debtor/creditor accounts). A single
 * global rule would either mask a harmless VA number or fail to mask a real
 * IBAN depending on which tool it hit; keying by tool name resolves the
 * ambiguity in exactly one place with no field-renaming needed anywhere.
 */
@Component
public class McpMaskingPolicy {

    /** Unambiguous everywhere they appear — never sent to an external MCP caller. */
    private static final Set<String> GLOBAL_DROP_KEYS = Set.of("bancsCustomerId", "bancsAccountId");

    /**
     * Per-tool truncate-to-last-4 keys. Only list tools that actually surface a
     * bank-side account number/IBAN; a tool absent here has nothing to mask.
     */
    private static final Map<String, Set<String>> MASK_KEYS_BY_TOOL = Map.of(
            "get_multibank_liquidity", Set.of("bankAccountNumber", "bankIban"),
            "get_shadow_balance_freshness", Set.of("bankAccountNumber", "bankIban"),
            "get_account_detail", Set.of("bankAccountNumber", "bankIban"),
            "get_transaction_detail", Set.of("remitterAccount", "beneficiaryAccount"),
            "get_iso20022_statement", Set.of("accountNumber") // debtorAccount/creditorAccount.accountNumber
    );

    public Map<String, Object> apply(String toolName, Map<String, Object> data) {
        if (data == null || data.isEmpty()) {
            return data;
        }
        Set<String> maskKeys = MASK_KEYS_BY_TOOL.getOrDefault(toolName, Set.of());
        return maskMap(data, maskKeys);
    }

    private Map<String, Object> maskMap(Map<String, Object> node, Set<String> maskKeys) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : node.entrySet()) {
            String key = entry.getKey();
            if (GLOBAL_DROP_KEYS.contains(key)) {
                continue; // dropped entirely, not even truncated
            }
            Object value = entry.getValue();
            if (maskKeys.contains(key) && value instanceof String s) {
                out.put(key, truncate(s));
            } else {
                out.put(key, maskValue(value, maskKeys));
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Object maskValue(Object value, Set<String> maskKeys) {
        if (value instanceof Map<?, ?> m) {
            return maskMap((Map<String, Object>) m, maskKeys);
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(maskValue(item, maskKeys));
            }
            return out;
        }
        return value;
    }

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 4 ? "****" + value : "****" + value.substring(value.length() - 4);
    }
}
