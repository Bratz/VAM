package com.bank.vam.entity.treasury;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One day's interest on an IHB current account, as the daily run booked it: the balance it ran on, the rate it charged,
 * the day count and the amount added to the account's accrued credit or debit interest (spec ihb-intercompany-pages
 * R15). A reversal that takes accrued interest back is a row too (negative amount, with a note), so the rows of a
 * period add up to what was accrued. Posted amounts are the INTEREST rows of the ledger. Written by
 * IhbUnifiedService; the old per-entity table of this name never had a writer (V65 dropped it).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "ihb_interest_accruals", indexes = @Index(name = "idx_ihb_accrual_va_date", columnList = "va_id, accrual_date"))
public class IhbInterestAccrual {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "va_id", nullable = false)
    private UUID vaId;

    /** The run's date (the day the interest was added to the accrual). */
    @Column(name = "accrual_date", nullable = false)
    private LocalDate accrualDate;

    /** CREDIT: earned by the participant (balance in credit); DEBIT: owed by it (overdrawn). */
    @Column(name = "side", length = 6, nullable = false)
    private String side;

    @Column(name = "balance", precision = 18, scale = 2)
    private BigDecimal balance;

    /** Annual rate in percent, as charged. */
    @Column(name = "rate", precision = 10, scale = 5)
    private BigDecimal rate;

    /** True when no rate was configured and the built-in default was charged. */
    @Column(name = "default_rate")
    private boolean defaultRate;

    @Column(name = "day_count", length = 10)
    private String dayCount;

    /** Added to the accrued interest of {@link #side}, to the accrued columns' 4 places; negative when taken back. */
    @Column(name = "amount", precision = 18, scale = 4, nullable = false)
    private BigDecimal amount;

    /** Null for a daily accrual; says why for a correction. */
    @Column(name = "note", length = 200)
    private String note;

    @Builder.Default
    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();
}
