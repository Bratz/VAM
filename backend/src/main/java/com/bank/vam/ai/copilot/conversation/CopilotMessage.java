package com.bank.vam.ai.copilot.conversation;

import com.bank.vam.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

/**
 * One message inside a {@link CopilotConversation}. Roles match the standard
 * chat-completion vocabulary so the future LLM swap is a one-class change.
 *
 * <ul>
 *   <li>{@link Role#USER} — input from the human</li>
 *   <li>{@link Role#ASSISTANT} — Copilot reply (markdown-rendered in UI)</li>
 *   <li>{@link Role#TOOL} — output of a tool call, attached to the assistant
 *       turn that requested it. Used by the future LLM loop; the stub also
 *       persists tool results here for traceability.</li>
 * </ul>
 */
@Entity
@Table(name = "copilot_message", indexes = {
        @Index(name = "idx_copmsg_conversation_seq",
               columnList = "conversation_id, sequence_number")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CopilotMessage extends BaseEntity {

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", length = 20, nullable = false)
    private Role role;

    /** Plain text or markdown. May be empty for assistant messages that only emit tool calls. */
    @Column(name = "content", columnDefinition = "text")
    private String content;

    /**
     * JSON array of tool invocations the assistant made on this turn.
     * Shape (set by P3): [{"name": "get_position", "params": {...}, "result": {...}}, ...].
     * Null for USER messages and for assistant messages with no tool calls.
     */
    @Column(name = "tool_calls", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String toolCalls;

    /**
     * Optional reference to the {@code Intent} matched by the router. Useful
     * for telemetry (which intents fire most) and debugging. Null for the
     * P1 hardcoded reply.
     */
    @Column(name = "intent", length = 50)
    private String intent;

    /**
     * Per-conversation monotonic ordering. Allocated by
     * {@link CopilotConversation#allocateSequenceNumber()} so messages
     * sort deterministically even when {@code created_at} ties.
     */
    @Column(name = "sequence_number", nullable = false)
    private Integer sequenceNumber;

    public enum Role { USER, ASSISTANT, TOOL }
}
