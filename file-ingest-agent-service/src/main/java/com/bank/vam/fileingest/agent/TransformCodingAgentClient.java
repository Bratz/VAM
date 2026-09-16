package com.bank.vam.fileingest.agent;

// ponytail: the tool-use loop shape (build messages, call the model, execute any tool calls,
// repeat until it stops) is duplicated from defect-fix-service's CodingAgentClient — extract to a
// shared module if a third consumer needs this. What's different: the system prompt targets
// writing one RowTransform implementation + its own test in a transform-handlers worktree,
// instead of fixing a defect in the main repo.

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
public class TransformCodingAgentClient {

    private static final Logger log = LoggerFactory.getLogger(TransformCodingAgentClient.class);

    private static final String SYSTEM_PROMPT = """
            You are a coding agent writing a data transform for a customer-uploaded file whose
            format this system has never seen before. You have read_file, write_file, list_files
            and run_command tools scoped to a git worktree of the transform-handlers repository (a
            small, purpose-built Maven project) — nothing outside it matters, and there is no need
            to create a branch or commit; that is handled for you after you finish.

            The worktree already has:
            - src/main/java/com/bank/vam/transformhandlers/RowTransform.java — the interface you
              must implement.
            - src/main/java/com/bank/vam/transformhandlers/TransformRunnerMain.java — a fixed CLI
              entry point that loads your class by name and calls transform() on it; you never need
              to write your own main method.
            - A sample of the customer's actual file, and an analysis of its structure, given to you
              in the task below.

            Write your implementation under a NEW package
            com.bank.vam.transformhandlers.generated.<package> (the exact package name is given in
            the task), as a class named GeneratedTransform implementing RowTransform. Each output
            row is a Map with exactly these keys: amount (a plain decimal string), currency
            (3-letter code), viban, debtorName, debtorAccount, remittanceInfo, reference — use an
            empty string for any field the source file doesn't actually contain. Write a JUnit 5
            test for it (GeneratedTransformTest, same package, under src/test/java) against the
            sample fixture, and run `mvn -q test` yourself via run_command before you finish to
            confirm it passes — the caller runs the same command again independently afterward as
            the real gate.

            Before writing new code, check whether RowTransform or the sample fixture already tells
            you everything you need — do not guess at a file's structure beyond what the analysis
            and sample actually show you. Do not add a dependency, add defensive handling for input
            that cannot occur here, or touch any file outside your own generated package and its
            test. When you believe the transform and its test are complete and passing, stop calling
            tools and reply with a short summary instead.
            """;

    private final AnthropicClient client;
    private final IngestProperties.Agent agentConfig;
    private final String model;

    public TransformCodingAgentClient(IngestProperties properties) {
        this.client = AnthropicOkHttpClient.builder()
                .apiKey(properties.anthropic().apiKey())
                .build();
        this.agentConfig = properties.agent();
        this.model = properties.anthropic().model();
    }

    public record AgentRunResult(boolean stoppedNaturally, String finalMessage) {
    }

    public AgentRunResult generate(Path workDir, String taskBrief) {
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
                log.info("Transform coding agent stopped after turn {}: {}", turn + 1, oneLine(lastText, 300));
                return new AgentRunResult(true, lastText);
            }

            List<ContentBlockParam> results = new ArrayList<>();
            for (ToolUseBlock toolUse : toolUses) {
                JsonNode input = toolUse._input().convert(JsonNode.class);
                log.info("  {} {}", toolUse.name(), summarizeArgs(toolUse.name(), input));
                String result = executeTool(toolUse.name(), input, workDir);
                results.add(ContentBlockParam.ofToolResult(
                        ToolResultBlockParam.builder().toolUseId(toolUse.id()).content(result).build()));
            }
            messages.add(MessageParam.builder().role(MessageParam.Role.USER).contentOfBlockParams(results).build());
        }

        log.warn("Transform coding agent hit the {}-turn budget without stopping naturally. Last text: {}",
                agentConfig.maxTurns(), oneLine(lastText, 300));
        return new AgentRunResult(false, lastText);
    }

    private String executeTool(String toolName, JsonNode input, Path workDir) {
        try {
            return switch (toolName) {
                case "read_file" -> TransformWorkspaceTools.readFile(workDir, input.path("path").asText());
                case "write_file" -> TransformWorkspaceTools.writeFile(workDir, input.path("path").asText(), input.path("content").asText());
                case "list_files" -> TransformWorkspaceTools.listFiles(workDir, input.path("path").asText("."));
                case "run_command" -> TransformWorkspaceTools.runCommand(workDir, input.path("command").asText());
                default -> "Unknown tool: " + toolName;
            };
        } catch (Exception e) {
            log.warn("Tool {} failed: {}", toolName, e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

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
                ToolUnion.ofTool(tool("list_files", "List files under a directory (recursive, excludes .git/target).",
                        schema(Map.of("path", strProp("Directory relative to the working directory, default \".\"")), List.of()))),
                ToolUnion.ofTool(tool("run_command", "Run a shell command in the working directory (e.g. to run tests).",
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
