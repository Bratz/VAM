package com.bank.vam.entity.simulator;

import com.bank.vam.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Per-bank / per-rail fee tariff for the Phase-2 "Bank fees" score line.
 *
 * <p>Manually curated; {@link #source} records provenance ('ESTIMATE' until a
 * real tariff sheet is loaded) and is surfaced in the Optimisation Score
 * "View assumptions" drawer — the spec's honesty mechanism.
 *
 * <p>Reference data, append-mostly. Effective-dated: a lookup resolves the row
 * whose {@code effectiveFrom <= asOf AND (effectiveTo IS NULL OR effectiveTo
 * >= asOf)}.
 *
 * @see com.bank.vam.entity.BaseEntity for id / audit / version columns
 */
@Entity
@Table(
    name = "bank_fee_tariff",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_bank_fee_tariff",
        columnNames = {"bank_code", "payment_rail", "effective_from"}),
    indexes = {
        @Index(name = "idx_bft_bank", columnList = "bank_code"),
        @Index(name = "idx_bft_effective", columnList = "effective_from, effective_to")
    })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
/** @deprecated 2026-05-16 — Bank-fees rewired onto ChargeConfiguration
 *  (tax-charges). bank_fee_tariff is unwired; table retained (forward-only). */
@Deprecated
public class BankFeeTariff extends BaseEntity {

    @Column(name = "bank_code", nullable = false, length = 20)
    private String bankCode;

    /** BANCS | SWIFT_MT103 | SEPA | RTGS | OPEN_BANKING (validated string, no DB enum). */
    @Column(name = "payment_rail", nullable = false, length = 20)
    private String paymentRail;

    @Column(name = "fee_currency", nullable = false, length = 3)
    private String feeCurrency;

    /** Per-transaction fixed fee. */
    @Column(name = "fee_fixed", precision = 12, scale = 4)
    @Builder.Default
    private BigDecimal feeFixed = BigDecimal.ZERO;

    /** Basis points on principal (÷10000). */
    @Column(name = "fee_bps", precision = 8, scale = 4)
    @Builder.Default
    private BigDecimal feeBps = BigDecimal.ZERO;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    /** 'TARIFF_SHEET_2026' | 'ESTIMATE' | … — disclosed in the assumptions drawer. */
    @Column(name = "source", length = 50)
    private String source;
}
