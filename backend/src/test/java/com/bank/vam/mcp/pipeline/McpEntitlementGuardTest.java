package com.bank.vam.mcp.pipeline;

import com.bank.vam.ai.copilot.tools.CopilotTool;
import com.bank.vam.ai.copilot.tools.ToolContext;
import com.bank.vam.ai.copilot.tools.ToolContext.CallerContext;
import com.bank.vam.ai.copilot.tools.ToolResult;
import com.bank.vam.mcp.McpException;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpEntitlementGuardTest {

    private final McpEntitlementGuard guard = new McpEntitlementGuard();

    private final CopilotTool fakeTool = new CopilotTool() {
        @Override
        public String name() {
            return "fake_tool";
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
        public ToolResult execute(ToolContext context, Map<String, Object> params) {
            return ToolResult.ok(name(), "ok", Map.of());
        }
    };

    @Test
    void nullCaller_isPermitted_matchesUnauthenticatedInAppCopilotBehaviour() {
        ToolContext context = new ToolContext(null, "mcp-unauthenticated", "USD", "US", null);
        assertThatCode(() -> guard.authorize(fakeTool, context)).doesNotThrowAnyException();
    }

    @Test
    void authenticatedCaller_withEntitledCorporate_isPermitted() {
        UUID corporateId = UUID.randomUUID();
        CallerContext caller = new CallerContext("treasurer1", Set.of(corporateId), "VIEW", java.util.List.of(), "jti-1");
        ToolContext context = new ToolContext(corporateId, "treasurer1", "USD", "US", null, caller);

        assertThatCode(() -> guard.authorize(fakeTool, context)).doesNotThrowAnyException();
    }

    @Test
    void authenticatedCaller_withoutEntitlementForActiveScope_isRejected() {
        UUID entitledCorporate = UUID.randomUUID();
        UUID requestedCorporate = UUID.randomUUID();
        CallerContext caller = new CallerContext("treasurer1", Set.of(entitledCorporate), "VIEW", java.util.List.of(), "jti-1");
        ToolContext context = new ToolContext(requestedCorporate, "treasurer1", "USD", "US", null, caller);

        assertThatThrownBy(() -> guard.authorize(fakeTool, context))
                .isInstanceOf(McpException.class)
                .extracting(e -> ((McpException) e).getHttpStatus())
                .isEqualTo(403);
    }

    @Test
    void authenticatedCaller_withNoActiveScope_isRejected() {
        CallerContext caller = new CallerContext("treasurer1", Set.of(UUID.randomUUID()), "VIEW", java.util.List.of(), "jti-1");
        ToolContext context = new ToolContext(null, "treasurer1", "USD", "US", null, caller);

        assertThatThrownBy(() -> guard.authorize(fakeTool, context)).isInstanceOf(McpException.class);
    }
}
