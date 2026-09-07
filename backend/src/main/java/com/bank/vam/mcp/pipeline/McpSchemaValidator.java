package com.bank.vam.mcp.pipeline;

import com.bank.vam.ai.copilot.tools.CopilotTool;
import com.bank.vam.mcp.McpException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Checks {@code tools/call} arguments against the tool's own
 * {@link CopilotTool#inputSchema()} before execution. Deliberately a plain
 * required-field presence check, not a full JSON Schema 2020-12 validator —
 * none of the ~9 tools use anything beyond {@code type}/{@code description}/
 * {@code required} today, and pulling in a schema-validation library for that
 * would be solving a problem this codebase doesn't have yet. If a future tool
 * needs real constraint validation (enums, ranges, patterns), upgrade this
 * class then rather than guessing at the shape now.
 */
@Component
public class McpSchemaValidator {

    @SuppressWarnings("unchecked")
    public void validate(CopilotTool tool, Map<String, Object> arguments) {
        Object requiredRaw = tool.inputSchema().get("required");
        if (!(requiredRaw instanceof List)) {
            return;
        }
        List<String> missing = new ArrayList<>();
        for (Object requiredField : (List<Object>) requiredRaw) {
            String name = String.valueOf(requiredField);
            if (arguments == null || !arguments.containsKey(name) || arguments.get(name) == null) {
                missing.add(name);
            }
        }
        if (!missing.isEmpty()) {
            throw McpException.invalidParams(
                    "Missing required argument(s) for tool '" + tool.name() + "': " + missing);
        }
    }
}
