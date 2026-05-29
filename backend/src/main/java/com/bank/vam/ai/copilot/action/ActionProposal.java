package com.bank.vam.ai.copilot.action;

import com.bank.vam.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A pending write action proposed by Copilot. Created when a write tool
 * (e.g. {@code PauseSweepRuleTool}) is matched but NOT executed — the
 * frontend renders an action card and the user must click Confirm before
 * {@code ActionExecutorService} actually mutates state.
 *
 * <p>Lifecycle:
 * <pre>
 *   PENDING ──→ EXECUTED   (user clicked Confirm before {@code expires_at})
 *      │
 *      ├──→ CANCELLED      (user clicked Cancel)
 *      │
 *      └──→ EXPIRED        (TTL elapsed without action)
 * </pre>
 *
 * <p>P1 only ships the schema; proposals are not created until P5 (write tools).
 */
@Entity
@Table(name = "action_proposal", indexes = {
        @Index(name = "idx_aprop_conversation", columnList = "conversation_id"),
        @Index(name = "idx_aprop_status_expires", columnList = "status, expires_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ActionProposal extends BaseEntity {

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    /** Tool name, e.g. {@code "pause_sweep_rule"}, {@code "set_balance_alert"}. */
    @Column(name = "tool", length = 60, nullable = false)
    private String tool;

    /** JSON object of tool parameters. Validated again at execution time. */
    @Column(name = "params", columnDefinition = "jsonb", nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private String params;

    /** One-line human summary rendered in the action card. */
    @Column(name = "summary", length = 500, nullable = false)
    private String summary;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    @Builder.Default
    private ProposalStatus status = ProposalStatus.PENDING;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "executed_at")
    private LocalDateTime executedAt;

    /** Free-text result of execution (success message or failure reason). */
    @Column(name = "execution_result", columnDefinition = "text")
    private String executionResult;

    public boolean isPending() {
        return status == ProposalStatus.PENDING;
    }

    public boolean isExpired(LocalDateTime now) {
        return now.isAfter(expiresAt);
    }

    public enum ProposalStatus { PENDING, EXECUTED, CANCELLED, EXPIRED }
}
