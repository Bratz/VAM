package com.bank.vam.ai.copilot;

import com.bank.vam.ai.copilot.conversation.CopilotConversation;
import com.bank.vam.ai.copilot.conversation.CopilotMessage;
import com.bank.vam.ai.copilot.dto.ChatChunkDone;
import com.bank.vam.ai.copilot.dto.ChatChunkMeta;
import com.bank.vam.ai.copilot.dto.ChatChunkToken;
import com.bank.vam.ai.copilot.dto.ChatRequest;
import com.bank.vam.ai.copilot.intent.IntentExecutor;
import com.bank.vam.ai.copilot.intent.IntentMatch;
import com.bank.vam.ai.copilot.intent.IntentRouter;
import com.bank.vam.ai.copilot.intent.ResponseComposer;
import com.bank.vam.ai.copilot.streaming.TokenStreamer;
import com.bank.vam.ai.copilot.tools.ToolResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * Orchestrates one Copilot turn:
 *
 * <ol>
 *   <li>persist the user message (synchronous, transactional)</li>
 *   <li>route the input through {@link IntentRouter} → {@link IntentMatch}</li>
 *   <li>execute the intent's tools via {@link IntentExecutor} → {@link ToolResult}s</li>
 *   <li>compose the markdown reply via {@link ResponseComposer}</li>
 *   <li>persist the assistant message <i>before</i> streaming starts, so a
 *       client disconnect mid-stream cannot lose the reply</li>
 *   <li>stream the persisted text via {@link TokenStreamer} as paced SSE events</li>
 * </ol>
 *
 * <p>The first SSE event ({@code meta}) carries the conversation + user-message
 * IDs so the frontend can mark the user bubble as saved. The final event
 * ({@code done}) carries the already-persisted assistant message ID and the
 * matched intent.
 *
 * <p>Tool results are serialised into the assistant message's {@code tool_calls}
 * jsonb column for auditability and to feed the drawer's expandable "looked
 * up X" rows.
 *
 * <p>For the stub we know the full reply up front. When this swaps to a real
 * LLM in v1, the streaming-aware variant will accumulate tokens and persist on
 * a terminal signal ({@code doFinally}) instead.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CopilotService {

    private final CopilotConversationService conversationService;
    private final TokenStreamer tokenStreamer;
    private final IntentRouter intentRouter;
    private final IntentExecutor intentExecutor;
    private final ResponseComposer responseComposer;
    private final ObjectMapper objectMapper;

    public Flux<ServerSentEvent<Object>> handleTurn(ChatRequest request) {
        // (1) Persist user turn synchronously so the conversation + user
        //     message exist before the first SSE event is emitted.
        CopilotConversation conv = conversationService.findOrCreateConversation(request);
        CopilotMessage userMsg = conversationService.appendUserMessage(conv, request.message());

        // (2) Route → match.
        IntentMatch match = intentRouter.match(request.message());

        // (3) Execute tools for the matched intent. Write tools need the
        //     conversation id so the {@code ActionProposal} they create can
        //     be scoped to this turn.
        List<ToolResult> toolResults = intentExecutor.execute(match, conv.getId());

        // (4) Compose the markdown reply from the tool results.
        ResponseComposer.ComposedReply reply = responseComposer.compose(match, toolResults);

        // (5) Persist the assistant message NOW, before any token streams.
        //     This makes the reply durable even if the client disconnects
        //     mid-stream. Includes the matched intent + tool-call provenance.
        String toolCallsJson = serialiseToolCalls(toolResults);
        CopilotMessage asstMsg = conversationService.appendAssistantMessage(
                conv, reply.text(), reply.intent(), toolCallsJson);

        log.info("Aperture Copilot turn: conversation={} intent={} tools={} user-msg={} assistant-msg={} reply-len={}",
                conv.getId(), reply.intent(),
                toolResults.stream().map(ToolResult::toolName).toList(),
                userMsg.getId(), asstMsg.getId(), reply.text().length());

        // (6) Stream the SSE flux: meta → tokens → done.
        ServerSentEvent<Object> metaEvent = sse("meta",
                new ChatChunkMeta(conv.getId(), userMsg.getId()));

        Flux<ServerSentEvent<Object>> tokenEvents = tokenStreamer.stream(reply.text())
                .map(token -> sse("token", new ChatChunkToken(token)));

        ServerSentEvent<Object> doneEvent = sse("done",
                new ChatChunkDone(asstMsg.getId(), reply.intent()));

        return Flux.concat(Flux.just(metaEvent), tokenEvents, Flux.just(doneEvent));
    }

    private String serialiseToolCalls(List<ToolResult> results) {
        if (results == null || results.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(results);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialise tool calls: {}", e.getMessage());
            return null;
        }
    }

    private static <T> ServerSentEvent<Object> sse(String event, T payload) {
        return ServerSentEvent.<Object>builder()
                .event(event)
                .data(payload)
                .build();
    }
}
