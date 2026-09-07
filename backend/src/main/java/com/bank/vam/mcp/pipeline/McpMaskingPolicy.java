package com.bank.vam.mcp.pipeline;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Phase 1 stub — passes {@code data} through unchanged. Phase 5 populates real
 * rules (truncate physical account numbers/IBANs to last-4, drop internal
 * fields like {@code bancsCustomerId}) once the ~22-tool catalogue exists and
 * there's an actual set of fields to decide on, rather than guessing now at
 * what the full tool set will return.
 */
@Component
public class McpMaskingPolicy {

    public Map<String, Object> apply(String toolName, Map<String, Object> data) {
        return data;
    }
}
