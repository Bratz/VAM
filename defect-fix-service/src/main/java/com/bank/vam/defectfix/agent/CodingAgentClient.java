package com.bank.vam.defectfix.agent;

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
import com.bank.vam.defectfix.config.PipelineProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Runs a bounded tool-use loop against a single git worktree: the model reads
 * and edits files (and can run shell commands) until it stops calling tools
 * or the turn budget runs out. It does NOT judge whether the fix is correct —
 * that's the test gate's job, run by the caller after this returns.
 */
@Component
public class CodingAgentClient {

    private static final Logger log = LoggerFactory.getLogger(CodingAgentClient.class);

    private static final String SYSTEM_PROMPT = """
            You are a coding agent fixing a single defect in the vam-portal repository.
            You have read_file, write_file, list_files and run_command tools scoped to
            the current working directory (a checkout of the actual branch that failed
            CI — nothing outside it matters, and there is no need to create a branch or
            commit; that is handled for you after you finish).

            Make the smallest change that fixes the described defect. Do not refactor
            unrelated code. When you believe the fix is complete, stop calling tools and
            reply with a short summary of what you changed instead.

            If the defect is a failing test: fix the PRODUCTION code the test is
            exercising, not the test itself. Do not weaken, delete, or rewrite
            assertions, and do not define a new type/stub that merely makes the test
            pass without touching the real defect — the test is presumed correct; the
            code under test is presumed buggy. If you cannot find the file the defect
            actually refers to, say so in your final reply rather than fabricating a
            substitute.
            """;

    private final AnthropicClient client;
    private final PipelineProperties.Agent agentConfig;
    private final String model;

    public CodingAgentClient(PipelineProperties properties) {
        this.client = AnthropicOkHttpClient.builder()
                .apiKey(properties.anthropic().apiKey())
                .build();
        this.agentConfig = properties.agent();
        this.model = properties.anthropic().model();
    }

    public record AgentRunResult(boolean stoppedNaturally, String finalMessage) {
    }

    public AgentRunResult runFix(Path workDir, String taskBrief) {
        List<MessageParam> messages = new ArrayList<>();
        messages.add(MessageParam.builder().role(MessageParam.Role.USER).content(taskBrief).build());

        List<ToolUnion> tools = buildTools();
        String lastText = "";

        for (int turn = 0; turn < agentConfig.maxTurns(); turn++) {
            MessageCreateParams params = MessageCreateParams.builder()
                    .model(model)
                    .maxTokens(4096L)
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

            lastText = response.content().stream()
                    .filter(ContentBlock::isText)
                    .map(b -> b.asText().text())
                    .reduce("", (a, b) -> a + b);

            if (toolUses.isEmpty()) {
                log.info("Agent stopped after turn {}: {}", turn + 1, oneLine(lastText, 300));
                return new AgentRunResult(true, lastText);
            }

            List<ContentBlockParam> results = new ArrayList<>();
            for (ToolUseBlock toolUse : toolUses) {
                String result = executeTool(toolUse, workDir);
                results.add(ContentBlockParam.ofToolResult(
                        ToolResultBlockParam.builder().toolUseId(toolUse.id()).content(result).build()));
            }
            messages.add(MessageParam.builder().role(MessageParam.Role.USER).contentOfBlockParams(results).build());
        }

        log.warn("Coding agent hit the {}-turn budget without stopping naturally. Last text: {}",
                agentConfig.maxTurns(), oneLine(lastText, 300));
        return new AgentRunResult(false, lastText);
    }

    private String executeTool(ToolUseBlock toolUse, Path workDir) {
        JsonNode input = toolUse._input().convert(JsonNode.class);
        log.info("  {} {}", toolUse.name(), summarizeArgs(toolUse.name(), input));
        try {
            String result = switch (toolUse.name()) {
                case "read_file" -> WorkspaceTools.readFile(workDir, input.path("path").asText());
                case "write_file" -> WorkspaceTools.writeFile(workDir, input.path("path").asText(), input.path("content").asText());
                case "list_files" -> WorkspaceTools.listFiles(workDir, input.path("path").asText("."));
                case "run_command" -> WorkspaceTools.runCommand(workDir, input.path("command").asText());
                default -> "Unknown tool: " + toolUse.name();
            };
            log.debug("  -> {}", oneLine(result, 500));
            return result;
        } catch (Exception e) {
            log.warn("Tool {} failed: {}", toolUse.name(), e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    /** Short, log-friendly summary of a tool call's arguments — the ones worth seeing at a glance, not the full payload. */
    private String summarizeArgs(String toolName, JsonNode input) {
        return switch (toolName) {
            case "read_file", "list_files" -> input.path("path").asText();
            case "write_file" -> input.path("path").asText() + " (" + input.path("content").asText().length() + " chars)";
            case "run_command" -> oneLine(input.path("command").asText(), 200);
            default -> input.toString();
        };
    }

    private String oneLine(String text, int maxChars) {
        String flattened = text.replace("\n", " \\n ").strip();
        return flattened.length() > maxChars ? flattened.substring(0, maxChars) + "..." : flattened;
    }

    private List<ToolUnion> buildTools() {
        return List.of(
                ToolUnion.ofTool(tool("read_file", "Read a text file's contents.",
                        schema(Map.of("path", strProp("Path relative to the working directory")), List.of("path")))),
                ToolUnion.ofTool(tool("write_file", "Write (or overwrite) a text file, creating parent directories as needed.",
                        schema(Map.of(
                                "path", strProp("Path relative to the working directory"),
                                "content", strProp("Full new content of the file")),
                                List.of("path", "content")))),
                ToolUnion.ofTool(tool("list_files", "List files under a directory (recursive, excludes .git/node_modules/target).",
                        schema(Map.of("path", strProp("Directory relative to the working directory, default \".\"")), List.of()))),
                ToolUnion.ofTool(tool("run_command", "Run a shell command in the working directory (e.g. to run tests or lint).",
                        schema(Map.of("command", strProp("The shell command to run")), List.of("command"))))
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
