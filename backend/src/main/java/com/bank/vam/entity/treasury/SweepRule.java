package com.bank.vam.entity.treasury;

import com.bank.vam.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "sweep_rules")
public class SweepRule extends BaseEntity {

    @Column(name = "rule_reference", nullable = false, unique = true, length = 20)
    private String ruleReference;

    @Column(name = "rule_name", nullable = false, length = 100)
    private String ruleName;

    @Enumerated(EnumType.STRING)
    @Column(name = "sweep_type", nullable = false, length = 20)
    private SweepType sweepType;

    @Column(name = "target_amount", precision = 18, scale = 2)
    private BigDecimal targetAmount;

    @Column(name = "threshold_min", precision = 18, scale = 2)
    private BigDecimal thresholdMin;

    @Column(name = "threshold_max", precision = 18, scale = 2)
    private BigDecimal thresholdMax;

    @Column(name = "percentage", precision = 5, scale = 2)
    private BigDecimal percentage;

    @Column(name = "target_account_id", nullable = false)
    private UUID targetAccountId;

    @Column(name = "target_account_number", length = 34)
    private String targetAccountNumber;

    @Column(name = "target_entity_code", length = 20)
    private String targetEntityCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "frequency", nullable = false, length = 20)
    private SweepFrequency frequency = SweepFrequency.DAILY;

    @Column(name = "execution_time")
    private LocalTime executionTime;

    @Column(name = "execution_day")
    private Integer executionDay;

    @Column(name = "priority")
    private Integer priority = 1;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20)
    private SweepStatus status = SweepStatus.ACTIVE;

    @Column(name = "total_swept", precision = 18, scale = 2)
    private BigDecimal totalSwept = BigDecimal.ZERO;

    @Column(name = "execution_count")
    private Integer executionCount = 0;

    @Column(name = "last_execution")
    private LocalDateTime lastExecution;

    @Column(name = "next_execution")
    private LocalDateTime nextExecution;

    @Column(name = "currency_code", length = 3)
    private String currencyCode = "AED";

    /**
     * Owning corporate (and optional program). Nullable: legacy/seed rules
     * created before V9 carry none and surface only under "All Corporates".
     * Persisted from the create request so Cash Concentration can filter
     * rules by the selected corporate.
     */
    @Column(name = "corporate_id")
    private UUID corporateId;

    @Column(name = "program_id")
    private UUID programId;

    // ========================================================================
    // MULTI-BANK LIQUIDITY v2 — cross-bank sweep configuration
    // Populated only when the rule has at least one shadow source.
    // ========================================================================

    /**
     * Whether the rule moves real funds via an external rail, treats the rule
     * as notional only (no instruction), or attempts real and falls back to
     * notional if the rail is unavailable / cut-off missed.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "execution_mode", length = 20)
    private ExecutionMode executionMode = ExecutionMode.NOTIONAL;

    /**
     * Which clearing rail to use for real movement. {@code AUTO} picks by
     * (currency, source BIC, target BIC) at execution time.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "rail", length = 30)
    private Rail rail = Rail.AUTO;

    /**
     * Cut-off frame of reference. SOURCE_BANK_LOCAL applies the cut-off table
     * in the source bank's local time zone (typical for SWIFT/SEPA). CORPORATE_HQ
     * applies it in {@code corporateHqTimezone} (treasurer-friendly view).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "cutoff_mode", length = 30)
    private CutoffMode cutoffMode = CutoffMode.SOURCE_BANK_LOCAL;

    /**
     * IANA zone ID for CUTOFF_MODE=CORPORATE_HQ (e.g. "Asia/Dubai"). Ignored otherwise.
     */
    @Column(name = "corporate_hq_timezone", length = 60)
    private String corporateHqTimezone;

    /**
     * Whether the cut-off check should also skip currency-clearing bank holidays.
     * Default true; set false only for testing / forced execution.
     */
    @Column(name = "respect_bank_calendar")
    private Boolean respectBankCalendar = Boolean.TRUE;

    /**
     * How fresh the shadow balance must be at execution time.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "balance_refresh_policy", length = 30)
    private BalanceRefreshPolicy balanceRefreshPolicy = BalanceRefreshPolicy.REFRESH_IF_STALE;

    @OneToMany(mappedBy = "rule", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SweepRuleSource> sourceAccounts = new ArrayList<>();

    public enum SweepType {
        ZERO_BALANCE,
        TARGET_BALANCE,
        THRESHOLD,
        PERCENTAGE
    }

    public enum SweepFrequency {
        REAL_TIME,
        DAILY,
        WEEKLY,
        MONTHLY
    }

    public enum SweepStatus {
        ACTIVE,
        PAUSED,
        DISABLED
    }

    /** v2: how real-vs-notional execution is treated. */
    public enum ExecutionMode {
        /** Real movement via external rail. Requires mandate + active rail adapter. */
        REAL,
        /** No instruction — sweep is purely a reporting/position construct. */
        NOTIONAL,
        /** Try REAL; fall back to NOTIONAL carry-forward on rail/cut-off failure. */
        HYBRID
    }

    /** v2: external rail selector. */
    public enum Rail {
        /** Pick rail at execution time based on (currency, source BIC, target BIC). */
        AUTO,
        SWIFT_MT103,
        SWIFT_MT202,
        SWIFT_MT202COV,
        SWIFT_PACS008,
        SWIFT_PACS009,
        SEPA_SCT,
        SEPA_INST
    }

    /** v2: cut-off frame of reference. */
    public enum CutoffMode {
        SOURCE_BANK_LOCAL,
        CORPORATE_HQ
    }

    /** v2: pre-execution balance refresh strategy. */
    public enum BalanceRefreshPolicy {
        /** Use last-known shadow balance even if stale. Fastest, least accurate. */
        USE_LAST,
        /** Only call refresh adapter if last refresh exceeds the freshness threshold. */
        REFRESH_IF_STALE,
        /** Always call the refresh adapter immediately before sweep calculation. */
        REFRESH_BEFORE_EXECUTION
    }
}
