package com.bank.vam.mcp.pipeline;

import com.bank.vam.ai.copilot.tools.CopilotTool;
import com.bank.vam.ai.copilot.tools.ToolContext;
import com.bank.vam.ai.copilot.tools.ToolRegistry;
import com.bank.vam.ai.copilot.tools.ToolResult;
import com.bank.vam.mcp.McpAuditService;
import com.bank.vam.mcp.McpException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * The {@code tools/call} pipeline: strip → validate → entitle → rate-limit →
 * execute the existing {@link CopilotTool} unchanged → mask → envelope →
 * audit. Each stage is its own class (see this package) so it's independently
 * testable and each phase of {@code docs/mcp-architecture.md} extends exactly
 * one of them rather than touching this orchestration.
 */
@Component
@RequiredArgsConstructor
public class McpToolPipeline {

    private final ToolRegistry toolRegistry;
    private final McpArgumentStripper argumentStripper;
    private final McpSchemaValidator schemaValidator;
    private final McpEntitlementGuard entitlementGuard;
    private final McpRateLimiter rateLimiter;
    private final McpMaskingPolicy maskingPolicy;
    private final McpResponseEnveloper responseEnveloper;
    private final McpAuditService auditService;

    public Map<String, Object> call(String toolName, Map<String, Object> arguments, ToolContext context) {
        // Enquiry-only surface is enforced structurally here, not by a config list:
        // a mutating tool is treated as if it doesn't exist, matching tools/list
        // (which filters the same way) and giving a prober no signal that it exists.
        CopilotTool tool = toolRegistry.find(toolName)
                .filter(t -> !t.isMutating())
                .orElseThrow(() -> McpException.invalidParams("Unknown tool: " + toolName));

        Map<String, Object> cleanArguments = argumentStripper.strip(arguments);
        schemaValidator.validate(tool, cleanArguments);
        entitlementGuard.authorize(tool, context);
        rateLimiter.acquire(toolName);

        long startedAt = System.currentTimeMillis();
        ToolResult result = tool.execute(context, cleanArguments);
        long latencyMs = System.currentTimeMillis() - startedAt;

        Map<String, Object> maskedData = maskingPolicy.apply(toolName, result.data());
        ToolResult maskedResult = new ToolResult(
                result.toolName(), result.ok(), result.summary(), maskedData, result.errorMessage());

        auditService.recordCall(toolName, context, result.ok(), latencyMs);

        return responseEnveloper.envelope(maskedResult);
    }
}
