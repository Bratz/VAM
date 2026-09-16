package com.bank.vam.fileingest.agent;

// ponytail: the tool-use loop shape (build messages, call the model, execute
// any tool calls, repeat until it stops) is duplicated from defect-fix-service's
// CodingAgentClient — extract to a shared module if a third consumer needs
// this. What's different: read-only tools (no write_file/run_command), and
// termination is a required submit_profile tool call instead of "the model
// just stops calling tools", since this agent's output needs to be structured
// data, not a free-text summary.

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUnion;
import com.anthropic.models.messages.ToolUseBlock;
import com.bank.vam.fileingest.config.IngestProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class AnalysisAgentClient {

    private static final Logger log = LoggerFactory.getLogger(AnalysisAgentClient.class);

    private static final String SYSTEM_PROMPT = """
            You are a file-structure analysis agent. A customer has uploaded a file for
            payables/receivables/payment processing, in whatever format their own system
            exports. You have read_file and sample_rows tools, scoped to the current
            working directory, to inspect it — you cannot write or execute anything, only
            read.

            Figure out: the delimiter (comma, semicolon, tab, fixed-width, ...), the
            column headers or field names in order, and which column (if any) represents
            an amount that a control-total check could sum across every row to sanity-check
            a downstream transform against. When you're done, call submit_profile exactly
            once with your findings — that is the only way to finish this task. Do not
            guess at values you have not actually seen in the file's own content.
            """;

    private final AnthropicClient client;
    private final IngestProperties.Agent agentConfig;
    private final String model;

    public AnalysisAgentClient(IngestProperties properties) {
        this.client = AnthropicOkHttpClient.builder()
                .apiKey(properties.anthropic().apiKey())
                .build();
        this.agentConfig = properties.agent();
        this.model = properties.anthropic().model();
    }

    public record AnalysisResult(boolean succeeded, FileStructureProfile profile, String failureReason) {
    }

    /** {@code fileName} is the file's path relative to workDir — e.g. "source.csv". */
    public AnalysisResult analyze(Path workDir, String fileName) {
        String taskBrief = "Analyze the file at path \"" + fileName + "\" and submit its structure profile.";
        List<MessageParam> messages = new ArrayList<>();
        messages.add(MessageParam.builder().role(MessageParam.Role.USER).content(taskBrief).build());

        List<ToolUnion> tools = buildTools();

        for (int turn = 0; turn < agentConfig.maxTurns(); turn++) {
            MessageCreateParams params = MessageCreateParams.builder()
                    .model(model)
                    .maxTokens(2048L)
                    .system(SYSTEM_PROMPT)
                    .messages(messages)
                    .tools(tools)
                    .build();

            Message response = client.messages().create(params);
            messages.add(response.toParam());

            List<ToolUseBlock> toolUses = response.content().stream()
                    .filter(ContentBlock::isToolUse)
                    .map(ContentBlock::asToolUse)
                    .toList();

            for (ToolUseBlock toolUse : toolUses) {
                if ("submit_profile".equals(toolUse.name())) {
                    JsonNode input = toolUse._input().convert(JsonNode.class);
                    FileStructureProfile profile = toProfile(input);
                    log.info("Analysis agent submitted profile after {} turn(s): {} column(s), delimiter=\"{}\"",
                            turn + 1, profile.columns().size(), profile.delimiter());
                    return new AnalysisResult(true, profile, null);
                }
            }

            if (toolUses.isEmpty()) {
                String lastText = response.content().stream()
                        .filter(ContentBlock::isText)
                        .map(b -> b.asText().text())
                        .reduce("", (a, b) -> a + b);
                log.warn("Analysis agent stopped without calling submit_profile: {}", oneLine(lastText, 300));
                return new AnalysisResult(false, null, "Agent stopped without submitting a profile: " + oneLine(lastText, 300));
            }

            List<ContentBlockParam> results = new ArrayList<>();
            for (ToolUseBlock toolUse : toolUses) {
                if ("submit_profile".equals(toolUse.name())) {
                    continue; // already handled above; nothing to feed back for it
                }
                JsonNode input = toolUse._input().convert(JsonNode.class);
                String result = executeTool(toolUse.name(), input, workDir);
                results.add(ContentBlockParam.ofToolResult(
                        ToolResultBlockParam.builder().toolUseId(toolUse.id()).content(result).build()));
            }
            if (!results.isEmpty()) {
                messages.add(MessageParam.builder().role(MessageParam.Role.USER).contentOfBlockParams(results).build());
            }
        }

        log.warn("Analysis agent hit the {}-turn budget without submitting a profile.", agentConfig.maxTurns());
        return new AnalysisResult(false, null, "Agent hit the " + agentConfig.maxTurns() + "-turn budget without submitting a profile");
    }

    private FileStructureProfile toProfile(JsonNode input) {
        List<String> columns = new ArrayList<>();
        input.path("columns").forEach(c -> columns.add(c.asText()));
        String controlTotalColumn = input.path("controlTotalColumn").asText(null);
        return new FileStructureProfile(
                columns,
                input.path("delimiter").asText(","),
                (controlTotalColumn == null || controlTotalColumn.isBlank()) ? null : controlTotalColumn,
                input.path("notes").asText(null)
        );
    }

    private String executeTool(String toolName, JsonNode input, Path workDir) {
        try {
            return switch (toolName) {
                case "read_file" -> AnalysisWorkspaceTools.readFile(workDir, input.path("path").asText());
                case "sample_rows" -> AnalysisWorkspaceTools.sampleRows(workDir, input.path("path").asText(),
                        input.path("maxLines").asInt(20));
                default -> "Unknown tool: " + toolName;
            };
        } catch (Exception e) {
            log.warn("Tool {} failed: {}", toolName, e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    private String oneLine(String text, int maxChars) {
        String flattened = text.replace("\n", " \\n ").strip();
        return flattened.length() > maxChars ? flattened.substring(0, maxChars) + "..." : flattened;
    }

    private List<ToolUnion> buildTools() {
        return List.of(
                ToolUnion.ofTool(tool("read_file", "Read a file's full contents.",
                        schema(Map.of("path", strProp("Path relative to the working directory")), List.of("path")))),
                ToolUnion.ofTool(tool("sample_rows", "Read the first N lines of a file — cheaper than read_file for a large file.",
                        schema(Map.of(
                                "path", strProp("Path relative to the working directory"),
                                "maxLines", Map.of("type", "integer", "description", "How many lines to read, default 20")),
                                List.of("path")))),
                ToolUnion.ofTool(tool("submit_profile",
                        "Submit the file's structure profile. Calling this ends the analysis.",
                        schema(Map.of(
                                "columns", Map.of("type", "array", "items", Map.of("type", "string"),
                                        "description", "Column headers / field names, in order"),
                                "delimiter", strProp("The field delimiter, e.g. \",\", \";\", \"\\t\", or \"fixed-width\""),
                                "controlTotalColumn", strProp("Which column is an amount suitable for a control-total check, if any"),
                                "notes", strProp("Anything else worth recording about this file's shape")),
                                List.of("columns", "delimiter"))))
        );
    }

    private Tool tool(String name, String description, Tool.InputSchema schema) {
        return Tool.builder().name(name).description(description).inputSchema(schema).build();
    }

    private Map<String, Object> strProp(String description) {
        return Map.of("type", "string", "description", description);
    }

    private Tool.InputSchema schema(Map<String, Object> properties, List<String> required) {
        Tool.InputSchema.Properties.Builder propsBuilder = Tool.InputSchema.Properties.builder();
        properties.forEach((key, value) -> propsBuilder.putAdditionalProperty(key, JsonValue.from(value)));
        return Tool.InputSchema.builder()
                .type(JsonValue.from("object"))
                .properties(propsBuilder.build())
                .required(required)
                .build();
    }
}
