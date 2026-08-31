package com.bank.vam.ai.copilot;

import com.bank.vam.ai.copilot.action.ActionExecutorService;
import com.bank.vam.ai.copilot.conversation.CopilotConversation;
import com.bank.vam.ai.copilot.conversation.CopilotMessage;
import com.bank.vam.ai.copilot.dto.ChatRequest;
import com.bank.vam.ai.copilot.tools.CopilotTool;
import com.bank.vam.ai.copilot.tools.ToolContext;
import com.bank.vam.ai.copilot.tools.ToolRegistry;
import com.bank.vam.ai.copilot.tools.ToolResult;
import com.bank.vam.dto.ApiResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Treasury Copilot REST surface.
 *
 * <p>{@code POST /api/ai/copilot/chat} streams a turn as Server-Sent Events
 * with three event names:
 * <ul>
 *   <li>{@code meta}  — first event, carries conversationId + userMessageId</li>
 *   <li>{@code token} — paced chunks of the assistant reply</li>
 *   <li>{@code done}  — final event, carries assistantMessageId + intent</li>
 * </ul>
 *
 * <p>The other endpoints are conventional JSON for the drawer's history view.
 *
 * <p>Auth: relies on the existing {@code SecurityConfig} which is permitAll
 * in dev. When auth is wired, {@code req.userIdOrDefault()} should be replaced
 * with the principal name extracted from the security context.
 */
@Slf4j
@RestController
@RequestMapping("/api/ai/copilot")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@Tag(name = "Treasury Copilot", description = "AI prototype (stub-only) for treasury queries and gated actions")
public class CopilotController {

    private final CopilotService copilotService;
    private final CopilotConversationService conversationService;
    private final CopilotProperties properties;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
    private final ActionExecutorService actionExecutor;

    private static final TypeReference<List<Map<String, Object>>> TOOL_CALLS_TYPE =
            new TypeReference<>() {};

    /**
     * Health probe — confirms the feature is enabled and reports its config.
     * Useful for the frontend's offline-state detection without hitting the
     * heavier {@code /chat} endpoint.
     */
    @GetMapping("/health")
    @Operation(summary = "Copilot feature health and configuration")
    public ResponseEntity<ApiResponse<Map<String, Object>>> health() {
        Map<String, Object> body = Map.of(
                "enabled", properties.isEnabled(),
                "mode", "stub",
                "streamTokenDelayMs", properties.getStreamTokenDelayMs(),
                "actionProposalTtlMinutes", properties.getActionProposalTtlMinutes(),
                "toolCount", toolRegistry.all().size(),
                "phase", "P6"
        );
        return ResponseEntity.ok(ApiResponse.success(body));
    }

    /**
     * Stream one chat turn back as Server-Sent Events. Returns 503 when the
     * feature is disabled via {@code vam.ai.copilot.enabled=false}.
     */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Send a message and stream the reply as SSE")
    public Flux<ServerSentEvent<Object>> chat(@Valid @RequestBody ChatRequest request) {
        if (!properties.isEnabled()) {
            log.warn("Copilot chat invoked while disabled");
            return Flux.just(ServerSentEvent.<Object>builder()
                    .event("error")
                    .data(Map.of("message", "Copilot is disabled"))
                    .build());
        }
        log.debug("Copilot chat request: conversation={} message-len={}",
                request.conversationId(),
                request.message() == null ? 0 : request.message().length());
        return copilotService.handleTurn(request);
    }

    /**
     * List conversations for a user, newest-first. Drawer history sidebar.
     */
    @GetMapping("/conversations")
    @Operation(summary = "List a user's conversations")
    public ResponseEntity<ApiResponse<List<ConversationSummary>>> listConversations(
            @RequestParam(defaultValue = "demo-user") String userId) {
        List<ConversationSummary> summaries = conversationService
                .listConversationsForUser(userId)
                .stream()
                .map(ConversationSummary::from)
                .toList();
        return ResponseEntity.ok(ApiResponse.success(summaries));
    }

    /**
     * Fetch all messages in a conversation, in send order. Drawer rehydration.
     */
    @GetMapping("/conversations/{id}/messages")
    @Operation(summary = "List all messages in a conversation")
    public ResponseEntity<ApiResponse<List<MessageView>>> listMessages(@PathVariable UUID id) {
        List<MessageView> view = conversationService.listMessages(id)
                .stream()
                .map(m -> MessageView.from(m, parseToolCalls(m.getToolCalls())))
                .toList();
        return ResponseEntity.ok(ApiResponse.success(view));
    }

    /**
     * Parse the {@code tool_calls} jsonb string back into a list of maps so
     * the frontend gets structured data, not an escaped JSON string.
     */
    private List<Map<String, Object>> parseToolCalls(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, TOOL_CALLS_TYPE);
        } catch (Exception e) {
            log.warn("Failed to parse tool_calls jsonb: {}", e.getMessage());
            return null;
        }
    }

    // =====================================================================
    // Tool diagnostics — exists so P2 work is verifiable independent of the
    // IntentRouter that lands in P3. Lets you `curl` each tool directly and
    // confirm it returns sensible data shapes.
    // =====================================================================

    @GetMapping("/tools")
    @Operation(summary = "List all registered Copilot tools")
    public ResponseEntity<ApiResponse<List<ToolDescriptor>>> listTools() {
        List<ToolDescriptor> tools = toolRegistry.all().stream()
                .map(ToolDescriptor::from)
                .toList();
        return ResponseEntity.ok(ApiResponse.success(tools));
    }

    /**
     * Execute one tool directly with an inline parameter map. Diagnostic only —
     * the production path is intent-routing in P3, not direct invocation.
     */
    @PostMapping("/tools/{name}/execute")
    @Operation(summary = "Execute one Copilot tool by name (diagnostic)")
    public ResponseEntity<ApiResponse<ToolResult>> executeTool(
            @PathVariable String name,
            @RequestBody(required = false) Map<String, Object> params) {
        return toolRegistry.find(name)
                .map(tool -> {
                    ToolContext ctx = toolRegistry.buildContext(null, "demo-user");
                    ToolResult result = tool.execute(ctx, params == null ? Map.of() : params);
                    return ResponseEntity.ok(ApiResponse.success(result));
                })
                .orElseGet(() -> ResponseEntity.status(404)
                        .body(ApiResponse.error("Unknown tool: " + name, "TOOL_NOT_FOUND")));
    }

    // =====================================================================
    // Action proposal lifecycle — Confirm / Cancel buttons on the frontend
    // hit these. The mutation only happens inside ActionExecutorService.
    // =====================================================================

    @PostMapping("/actions/{id}/execute")
    @Operation(summary = "Confirm and execute a pending action proposal")
    public ResponseEntity<ApiResponse<ActionExecutorService.ExecutionResult>> executeAction(
            @PathVariable UUID id) {
        ActionExecutorService.ExecutionResult result = actionExecutor.execute(id);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PostMapping("/actions/{id}/cancel")
    @Operation(summary = "Cancel a pending action proposal")
    public ResponseEntity<ApiResponse<ActionExecutorService.ExecutionResult>> cancelAction(
            @PathVariable UUID id) {
        ActionExecutorService.ExecutionResult result = actionExecutor.cancel(id);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    // ---------------------------------------------------------------------
    // View models — kept inside the controller since they're trivial DTOs
    // ---------------------------------------------------------------------

    public record ConversationSummary(UUID id, String title, String lastMessageAt, int messageCount) {
        static ConversationSummary from(CopilotConversation c) {
            return new ConversationSummary(
                    c.getId(),
                    c.getTitle(),
                    c.getLastMessageAt() == null ? null : c.getLastMessageAt().toString(),
                    c.getNextSequenceNumber()
            );
        }
    }

    public record MessageView(UUID id, String role, String content, String intent,
                              Integer sequenceNumber, String createdAt,
                              List<Map<String, Object>> toolCalls) {
        static MessageView from(CopilotMessage m, List<Map<String, Object>> toolCalls) {
            return new MessageView(
                    m.getId(),
                    m.getRole().name(),
                    m.getContent(),
                    m.getIntent(),
                    m.getSequenceNumber(),
                    m.getCreatedAt() == null ? null : m.getCreatedAt().toString(),
                    toolCalls
            );
        }
    }

    public record ToolDescriptor(String name, String description, boolean mutating,
                                 Map<String, Map<String, Object>> parameters) {
        static ToolDescriptor from(CopilotTool t) {
            return new ToolDescriptor(t.name(), t.description(), t.isMutating(), t.parameterSchema());
        }
    }
}
