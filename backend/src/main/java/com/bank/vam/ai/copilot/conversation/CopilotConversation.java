package com.bank.vam.ai.copilot.conversation;

import com.bank.vam.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One Treasury Copilot conversation. A conversation is a sequence of
 * {@link CopilotMessage}s belonging to a (user × corporate) pair.
 *
 * <p>For the stub prototype, each turn is independent — we still group
 * messages into a conversation so the drawer UI can show prior history
 * across page reloads.
 */
@Entity
@Table(name = "copilot_conversation", indexes = {
        @Index(name = "idx_copconv_corporate", columnList = "corporate_id"),
        @Index(name = "idx_copconv_user", columnList = "user_id"),
        @Index(name = "idx_copconv_last_message", columnList = "last_message_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CopilotConversation extends BaseEntity {

    /**
     * Corporate scope. Nullable in the prototype because demo data has no
     * corporate isolation; will become NOT NULL when auth is wired.
     */
    @Column(name = "corporate_id")
    private UUID corporateId;

    /**
     * Authenticated user identifier. Nullable in the prototype for the same
     * reason as {@link #corporateId}.
     */
    @Column(name = "user_id", length = 100)
    private String userId;

    /**
     * Human-readable title. Auto-derived from the first user message after
     * P3 lands; null until then.
     */
    @Column(name = "title", length = 200)
    private String title;

    /**
     * Timestamp of the most recent message. Indexed so the drawer can list
     * conversations newest-first cheaply.
     */
    @Column(name = "last_message_at")
    private LocalDateTime lastMessageAt;

    /**
     * Monotonic counter assigned to each new message. Persisted on the
     * conversation so message inserts don't need a SELECT MAX().
     */
    @Column(name = "next_sequence_number", nullable = false)
    @Builder.Default
    private Integer nextSequenceNumber = 0;

    /**
     * Allocate the next message sequence number atomically (within the same
     * transaction). Caller is responsible for re-saving the conversation.
     */
    public int allocateSequenceNumber() {
        int seq = nextSequenceNumber;
        nextSequenceNumber = seq + 1;
        return seq;
    }
}
