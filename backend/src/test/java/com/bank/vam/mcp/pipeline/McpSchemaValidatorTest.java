package com.bank.vam.mcp.pipeline;

import com.bank.vam.ai.copilot.tools.CopilotTool;
import com.bank.vam.ai.copilot.tools.ToolContext;
import com.bank.vam.ai.copilot.tools.ToolResult;
import com.bank.vam.mcp.McpException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpSchemaValidatorTest {

    private final McpSchemaValidator validator = new McpSchemaValidator();

    private final CopilotTool toolWithRequiredArg = new CopilotTool() {
        @Override
        public String name() {
            return "fake_required_tool";
        }

        @Override
        public String description() {
            return "fake";
        }

        @Override
        public Map<String, Map<String, Object>> parameterSchema() {
            return Map.of("ruleId", Map.of("type", "string", "description", "Rule id", "required", true));
        }

        @Override
        public ToolResult execute(ToolContext context, Map<String, Object> params) {
            return ToolResult.ok(name(), "ok", Map.of());
        }
    };

    @Test
    void missingRequiredArgument_throwsInvalidParams() {
        assertThatThrownBy(() -> validator.validate(toolWithRequiredArg, Map.of()))
                .isInstanceOf(McpException.class)
                .extracting(e -> ((McpException) e).getRpcCode())
                .isEqualTo(-32602);
    }

    @Test
    void requiredArgumentPresent_passes() {
        validator.validate(toolWithRequiredArg, Map.of("ruleId", "SWP-1"));
        // no exception
        assertThat(true).isTrue();
    }

    @Test
    void toolWithNoRequiredArgs_neverThrows() {
        CopilotTool optionalOnly = new CopilotTool() {
            @Override
            public String name() {
                return "fake_optional_tool";
            }

            @Override
            public String description() {
                return "fake";
            }

            @Override
            public Map<String, Map<String, Object>> parameterSchema() {
                return Map.of("limit", Map.of("type", "integer", "description", "Max rows"));
            }

            @Override
            public ToolResult execute(ToolContext context, Map<String, Object> params) {
                return ToolResult.ok(name(), "ok", Map.of());
            }
        };

        validator.validate(optionalOnly, Map.of());
        assertThat(true).isTrue();
    }
}
