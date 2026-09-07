package com.bank.vam.mcp;

import com.bank.vam.ai.copilot.tools.ToolContext;
import com.bank.vam.service.audit.AuditLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Records every {@code tools/call} through the existing {@link AuditLogService}
 * — the same table and service the pool/action-execution events already use,
 * so this doesn't create a second audit system. Kept as its own class (rather
 * than calling {@code AuditLogService} directly from the controller) so Phase
 * 5's hash-chaining work has exactly one place to land.
 */
@Component
@RequiredArgsConstructor
public class McpAuditService {

    private static final String EVENT_TYPE = "MCP_TOOL_CALL";
    private static final String ENTITY_TYPE = "CopilotTool";

    private final AuditLogService auditLogService;

    public void recordCall(String toolName, ToolContext context, boolean ok, long latencyMs) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tool", toolName);
        payload.put("ok", ok);
        payload.put("latencyMs", latencyMs);
        if (context.caller() != null) {
            payload.put("subject", context.caller().subject());
            payload.put("tokenId", context.caller().tokenId());
        }

        String actorForSummary = context.caller() != null ? context.caller().subject() : "mcp-unauthenticated";
        auditLogService.record(
                EVENT_TYPE,
                ENTITY_TYPE,
                null,
                context.corporateId(),
                String.format("MCP tool_call %s by %s: %s", toolName, actorForSummary, ok ? "ok" : "error"),
                payload);
    }
}
