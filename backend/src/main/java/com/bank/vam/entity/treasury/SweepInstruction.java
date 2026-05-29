package com.bank.vam.entity.treasury;

import com.bank.vam.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One real-rail instruction emitted by the sweep engine for a shadow source.
 *
 * Lifecycle:
 * <pre>
 *  PREPARED ──→ INSTRUCTED ──→ ACK_RECEIVED ──→ SETTLED
 *      │            │                │
 *      │            │                └──→ NACKED   (post-ACK reject)
 *      │            └──→ REJECTED            (immediate reject by rail)
 *      └──→ EXPIRED                          (cut-off missed before INSTRUCTED)
 * </pre>
 *
 * Idempotency: {@code idempotencyKey} is uniquely indexed; the rail adapter
 * uses it as the EndToEndId / InstructionId so retries cannot double-debit.
 *
 * Reconciliation: when a camt.053 / MT940 entry referencing {@code externalReference}
 * arrives, {@code SweepReconciliationService} matches and flips to {@code SETTLED}.
 */
@Entity
@Table(name = "sweep_instructions", indexes = {
        @Index(name = "idx_swi_idem_key", columnList = "idempotency_key", unique = true),
        @Index(name = "idx_swi_external_ref", columnList = "external_reference"),
        @Index(name = "idx_swi_status", columnList = "status"),
        @Index(name = "idx_swi_source_shadow", columnList = "source_shadow_va_id"),
        @Index(name = "idx_swi_sweep_execution", columnList = "sweep_execution_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SweepInstruction extends BaseEntity {

    /**
     * Deterministic key: {ruleRef}-{sourceShadowVaId}-{yyyymmdd}-{seq}.
     * Used as the rail's EndToEndId / InstructionId. UNIQUE.
     */
    @Column(name = "idempotency_key", nullable = false, unique = true, length = 100)
    private String idempotencyKey;

    /** Link back to the parent {@code SweepExecution} (existing entity). */
    @Column(name = "sweep_execution_id")
    private UUID sweepExecutionId;

    @Column(name = "source_shadow_va_id", nullable = false)
    private UUID sourceShadowVaId;

    @Column(name = "target_va_id", nullable = false)
    private UUID targetVaId;

    @Column(name = "amount", precision = 19, scale = 4, nullable = false)
    private BigDecimal amount;

    @Column(name = "currency_code", length = 3, nullable = false)
    private String currencyCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "rail", length = 30, nullable = false)
    private SweepRule.Rail rail;

    @Column(name = "mandate_id")
    private UUID mandateId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 30, nullable = false)
    @Builder.Default
    private InstructionStatus status = InstructionStatus.PREPARED;

    /** EndToEndId / InstructionId sent on the rail (typically equals idempotencyKey). */
    @Column(name = "external_reference", length = 100)
    private String externalReference;

    /** Reference returned by the clearing system on ACK. */
    @Column(name = "clearing_reference", length = 100)
    private String clearingReference;

    /** Serialised pacs.008 / MT103 / SCT payload (audit trail). */
    @Column(name = "rail_message_payload", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String railMessagePayload;

    // Lifecycle timestamps -----------------------------------------------------
    @Column(name = "prepared_at")     private LocalDateTime preparedAt;
    @Column(name = "instructed_at")   private LocalDateTime instructedAt;
    @Column(name = "ack_at")          private LocalDateTime ackAt;
    @Column(name = "settled_at")      private LocalDateTime settledAt;
    @Column(name = "rejected_at")     private LocalDateTime rejectedAt;

    // Rejection details --------------------------------------------------------
    @Column(name = "rejection_code", length = 20)
    private String rejectionCode;

    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    /**
     * Determined by {@code RejectionCodeRegistry}. Drives auto-pause vs retry.
     * Null until a rejection lands.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "rejection_category", length = 20)
    private RejectionCategory rejectionCategory;

    @Column(name = "retry_count")
    @Builder.Default
    private Integer retryCount = 0;

    @Column(name = "next_retry_at")
    private LocalDateTime nextRetryAt;

    /** camt.053 / MT940 entry reference once matched. */
    @Column(name = "reconciliation_ref", length = 100)
    private String reconciliationRef;

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    public boolean isTerminal() {
        return status == InstructionStatus.SETTLED
            || status == InstructionStatus.REJECTED
            || status == InstructionStatus.NACKED
            || status == InstructionStatus.EXPIRED;
    }

    public enum InstructionStatus {
        /** Created locally; not yet sent to rail. */
        PREPARED,
        /** Sent to rail; awaiting ACK. */
        INSTRUCTED,
        /** Rail ACKed the instruction; awaiting settlement confirmation. */
        ACK_RECEIVED,
        /** Settlement confirmed by inbound camt.053 / MT940. Terminal. */
        SETTLED,
        /** Rejected by rail before ACK. Terminal. */
        REJECTED,
        /** Rejected after ACK (e.g. settlement-cycle reject). Terminal. */
        NACKED,
        /** Cut-off missed before INSTRUCTED. Terminal. */
        EXPIRED
    }

    public enum RejectionCategory {
        /** Transient or operational reject — retry with backoff. */
        RECOVERABLE,
        /** Mandate/account/structural reject — auto-pause rule. */
        UNRECOVERABLE
    }
}
