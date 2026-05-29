package com.bank.vam.entity.treasury;

import com.bank.vam.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Mandate granted by the corporate to the home bank to initiate debits
 * from an external bank account (mirrored by a shadow VA).
 *
 * Decoupled from {@code PhysicalAccount} so:
 *  - Lifecycle (suspend/revoke/expire) is independent of CBS account state.
 *  - One shadow can carry multiple mandates per rail (e.g. SWIFT MT103 vs SEPA SCT).
 *  - Mandate document references (PDF URLs, eMandate IDs) live alongside the limits.
 *
 * Only mandates in {@link MandateStatus#ACTIVE} are eligible for sweep instruction.
 */
@Entity
@Table(name = "external_mandates", indexes = {
        @Index(name = "idx_mandate_shadow", columnList = "shadow_va_id"),
        @Index(name = "idx_mandate_reference", columnList = "mandate_reference", unique = true),
        @Index(name = "idx_mandate_status", columnList = "status")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExternalMandate extends BaseEntity {

    /** Human-readable mandate reference (e.g. provided by the corporate). */
    @Column(name = "mandate_reference", nullable = false, unique = true, length = 60)
    private String mandateReference;

    /** Shadow VA (PHYSICAL_MIRROR) this mandate authorises debits from. */
    @Column(name = "shadow_va_id", nullable = false)
    private UUID shadowVaId;

    /**
     * Optional rail scope. NULL means "any rail"; otherwise the mandate is valid
     * only when the rule selects this specific rail (e.g. SEPA-only mandate).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "rail_scope", length = 30)
    private SweepRule.Rail railScope;

    @Enumerated(EnumType.STRING)
    @Column(name = "mandate_type", nullable = false, length = 30)
    @Builder.Default
    private MandateType mandateType = MandateType.DIRECT_DEBIT;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    @Builder.Default
    private MandateStatus status = MandateStatus.PENDING_ACTIVATION;

    @Column(name = "valid_from")
    private LocalDate validFrom;

    @Column(name = "valid_to")
    private LocalDate validTo;

    /** BIC of the external (debitor) bank — denormalised for fast eligibility check. */
    @Column(name = "debitor_bank_bic", length = 11)
    private String debitorBankBic;

    /** BIC of the home (creditor) bank. */
    @Column(name = "creditor_bank_bic", length = 11)
    private String creditorBankBic;

    /** Per-transaction cap (nullable = no cap). */
    @Column(name = "max_per_transaction", precision = 19, scale = 4)
    private BigDecimal maxPerTransaction;

    /** Daily cap (nullable per G1=no daily cap in v1; surfaces for future). */
    @Column(name = "max_daily", precision = 19, scale = 4)
    private BigDecimal maxDaily;

    /** URL/reference to the signed mandate document. */
    @Column(name = "document_ref", length = 500)
    private String documentRef;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "revocation_reason", length = 200)
    private String revocationReason;

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    public boolean isActive(LocalDate asOf) {
        if (status != MandateStatus.ACTIVE) return false;
        if (validFrom != null && asOf.isBefore(validFrom)) return false;
        if (validTo != null && asOf.isAfter(validTo)) return false;
        return true;
    }

    public boolean supportsRail(SweepRule.Rail rail) {
        return railScope == null || railScope == rail;
    }

    public enum MandateType {
        DIRECT_DEBIT,
        SEPA_DIRECT_DEBIT,
        STANDING_INSTRUCTION
    }

    public enum MandateStatus {
        PENDING_ACTIVATION,
        ACTIVE,
        SUSPENDED,
        EXPIRED,
        REVOKED
    }
}
