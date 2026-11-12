package com.bank.vam.entity.treasury;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One pool's daily run for one value date (spec R10, D5): the net, the pooled interest on it, the standalone interest
 * the members would have had, the benefit, and the rates used — stored, so a later rate edit never changes history.
 * Interest amounts are signed: credit interest positive, debit interest negative. One run per (pool, value date).
 */
@Data
@Entity
@Table(name = "pool_interest_calculations", uniqueConstraints =
        @UniqueConstraint(name = "uq_pool_calc_pool_date", columnNames = {"pool_id", "calculation_date"}))
public class PoolInterestCalculation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pool_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private NotionalPool pool;

    @Column(name = "calculation_date", nullable = false)
    private LocalDate valueDate;

    /** Σ members' end-of-day balances. */
    @Column(name = "pool_balance", nullable = false, precision = 18, scale = 2)
    private BigDecimal poolNet;

    /** The rate applied to the net: the credit rate when the net is ≥ 0, else the debit rate. */
    @Column(name = "interest_rate", nullable = false, precision = 8, scale = 4)
    private BigDecimal interestRate;

    @Column(name = "gross_interest", nullable = false, precision = 18, scale = 2)
    private BigDecimal pooledInterest;

    @Column(name = "standalone_interest", precision = 18, scale = 2)
    private BigDecimal standaloneInterest;

    /** pooled − Σ standalone (R9): reported, not posted. */
    @Column(name = "benefit", precision = 18, scale = 2)
    private BigDecimal benefit;

    /** pooled − Σ members' rounded shares, added to the header's line (R2). */
    @Column(name = "residue", precision = 18, scale = 2)
    private BigDecimal residue;

    // Rates and day count used (credit/debit effective, debit incl. any penalty).
    // ponytail: tiers are not copied; the stored amounts are the record of what they gave.
    @Column(name = "credit_rate", precision = 9, scale = 5)
    private BigDecimal creditRate;

    @Column(name = "debit_rate", precision = 9, scale = 5)
    private BigDecimal debitRate;

    @Column(name = "day_count", length = 10)
    private String dayCount;

    /** The net was below −pool floor (R13). */
    @Column(name = "floor_breach")
    private Boolean floorBreach = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20)
    private CalculationStatus status = CalculationStatus.CALCULATED;

    @Column(name = "posted_at")
    private LocalDateTime postedAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    @OneToMany(mappedBy = "calculation", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("balance DESC")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private List<PoolInterestLine> lines = new ArrayList<>();

    /** No approval step (D11): a run is CALCULATED; the month-end posting (Phase 4) marks it POSTED. */
    public enum CalculationStatus {
        CALCULATED, POSTED
    }
}
