package com.bank.vam.entity.treasury;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** One member's line in a daily pool run (R6, R9, R11, R13). Amounts signed as on the run. */
@Data
@Entity
@Table(name = "pool_interest_lines", indexes = @Index(name = "idx_pool_line_calc", columnList = "calculation_id"))
public class PoolInterestLine {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "calculation_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private PoolInterestCalculation calculation;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "account_number", length = 34)
    private String accountNumber;

    /** End-of-day bank balance used (shadow_balance_snapshot.bank_balance); zero when there was no snapshot. */
    @Column(name = "balance", nullable = false, precision = 18, scale = 2)
    private BigDecimal balance;

    /** as_of of the snapshot used; null when none existed on or before the value date. */
    @Column(name = "balance_as_of")
    private LocalDate balanceAsOf;

    /** No snapshot for the value date: an earlier one (or zero) was used. */
    @Column(name = "snapshot_missing", nullable = false)
    private boolean snapshotMissing;

    @Column(name = "standalone_interest", nullable = false, precision = 18, scale = 2)
    private BigDecimal standaloneInterest;

    /** This member's share of the pooled interest; the header's includes the run's residue. */
    @Column(name = "allocated_interest", nullable = false, precision = 18, scale = 2)
    private BigDecimal allocatedInterest;

    /** The balance was below −debit limit (R13). */
    @Column(name = "debit_limit_breach", nullable = false)
    private boolean debitLimitBreach;
}
