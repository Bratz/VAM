package com.bank.vam.mcp.pipeline;

import com.bank.vam.ai.copilot.tools.CopilotTool;
import com.bank.vam.ai.copilot.tools.ToolContext;
import com.bank.vam.ai.copilot.tools.ToolRegistry;
import com.bank.vam.ai.copilot.tools.ToolResult;
import com.bank.vam.mcp.McpAuditService;
import com.bank.vam.mcp.McpException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Exercises the full {@code tools/call} pipeline with real stage instances
 * (they're cheap and dependency-free) except {@link ToolRegistry} and
 * {@link McpAuditService}, which are mocked. Real collaborators means this
 * test also proves the stages compose correctly, not just individually.
 */
class McpToolPipelineTest {

    private CopilotTool fakeTool(String name, boolean mutating) {
        return new CopilotTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "fake";
            }

            @Override
            public Map<String, Map<String, Object>> parameterSchema() {
                return Map.of();
            }

            @Override
            public boolean isMutating() {
                return mutating;
            }

            @Override
            public ToolResult execute(ToolContext context, Map<String, Object> params) {
                return ToolResult.ok(name(), "ok", Map.of("echo", params));
            }
        };
    }

    private McpToolPipeline buildPipeline(ToolRegistry toolRegistry, McpAuditService auditService) {
        return new McpToolPipeline(
                toolRegistry,
                new McpArgumentStripper(),
                new McpSchemaValidator(),
                new McpEntitlementGuard(),
                new PassThroughRateLimiter(),
                new McpMaskingPolicy(),
                new McpResponseEnveloper(new ObjectMapper()),
                auditService);
    }

    @Test
    void readOnlyTool_executesAndEnvelopesResult() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        McpAuditService auditService = mock(McpAuditService.class);
        CopilotTool tool = fakeTool("get_position", false);
        when(toolRegistry.find("get_position")).thenReturn(Optional.of(tool));

        McpToolPipeline pipeline = buildPipeline(toolRegistry, auditService);
        ToolContext context = new ToolContext(null, "mcp-unauthenticated", "USD", "US", null);

        Map<String, Object> envelope = pipeline.call("get_position", Map.of("currency", "USD"), context);

        assertThat(envelope.get("resultType")).isEqualTo("complete");
        assertThat(envelope.get("isError")).isEqualTo(false);
        verify(auditService).recordCall(anyString(), any(ToolContext.class), anyBoolean(), anyLong());
    }

    @Test
    void mutatingTool_isTreatedAsUnknown_notExposedThroughMcp() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        McpAuditService auditService = mock(McpAuditService.class);
        CopilotTool writeTool = fakeTool("pause_sweep_rule", true);
        when(toolRegistry.find("pause_sweep_rule")).thenReturn(Optional.of(writeTool));

        McpToolPipeline pipeline = buildPipeline(toolRegistry, auditService);
        ToolContext context = new ToolContext(null, "mcp-unauthenticated", "USD", "US", null);

        assertThatThrownBy(() -> pipeline.call("pause_sweep_rule", Map.of(), context))
                .isInstanceOf(McpException.class)
                .hasMessageContaining("Unknown tool")
                .extracting(e -> ((McpException) e).getRpcCode())
                .isEqualTo(-32602);
    }

    @Test
    void scopeShapedArgument_isStrippedBeforeToolExecution() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        McpAuditService auditService = mock(McpAuditService.class);
        CopilotTool tool = fakeTool("get_accounts", false);
        when(toolRegistry.find("get_accounts")).thenReturn(Optional.of(tool));

        McpToolPipeline pipeline = buildPipeline(toolRegistry, auditService);
        ToolContext context = new ToolContext(null, "mcp-unauthenticated", "USD", "US", null);

        Map<String, Object> envelope = pipeline.call("get_accounts",
                Map.of("corporateId", "someone-elses-corporate", "currency", "USD"), context);

        @SuppressWarnings("unchecked")
        Map<String, Object> structuredContent = (Map<String, Object>) envelope.get("structuredContent");
        @SuppressWarnings("unchecked")
        Map<String, Object> echoedArgs = (Map<String, Object>) structuredContent.get("echo");
        assertThat(echoedArgs).containsOnlyKeys("currency");
    }

    /** Real McpRateLimiter needs a live resilience4j registry — swap in a no-op for this test. */
    private static class PassThroughRateLimiter extends McpRateLimiter {
        PassThroughRateLimiter() {
            super(null, null);
        }

        @Override
        public void acquire(String toolName) {
            // no-op
        }
    }
}
