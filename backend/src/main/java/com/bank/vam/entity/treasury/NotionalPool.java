package com.bank.vam.entity.treasury;

import com.bank.vam.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "notional_pools")
public class NotionalPool extends BaseEntity {

    @Column(name = "pool_reference", nullable = false, unique = true, length = 20)
    private String poolReference;

    @Column(name = "pool_name", nullable = false, length = 100)
    private String poolName;

    @Column(name = "pool_currency", nullable = false, length = 3)
    private String poolCurrency = "AED";

    @Column(name = "target_balance", precision = 18, scale = 2)
    private BigDecimal targetBalance = BigDecimal.ZERO;

    @Column(name = "interest_rate", precision = 8, scale = 4)
    private BigDecimal interestRate = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "interest_calculation_method", length = 20)
    private InterestCalculationMethod interestCalculationMethod = InterestCalculationMethod.DAILY_AVERAGE;

    /**
     * Method used to allocate pooled interest across members.
     * Defaults to CONTRIBUTION_PERCENT (existing behaviour).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "allocation_method", length = 30)
    private AllocationMethod allocationMethod = AllocationMethod.CONTRIBUTION_PERCENT;

    @Column(name = "total_balance", precision = 18, scale = 2)
    private BigDecimal totalBalance = BigDecimal.ZERO;

    @Column(name = "interest_savings_ytd", precision = 18, scale = 2)
    private BigDecimal interestSavingsYtd = BigDecimal.ZERO;

    @Column(name = "member_count")
    private Integer memberCount = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20)
    private PoolStatus status = PoolStatus.ACTIVE;

    @Column(name = "effective_from")
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "last_calculation_date")
    private LocalDate lastCalculationDate;

    /**
     * Owning corporate (and optional program). Nullable: legacy/seed pools
     * created before V11 carry none (V12 backfills them from their members'
     * accounts) and surface only under "All Corporates". Persisted from the
     * create request so Notional Pooling can filter pools by corporate.
     */
    @Column(name = "corporate_id")
    private UUID corporateId;

    @Column(name = "program_id")
    private UUID programId;

    @OneToMany(mappedBy = "pool", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PoolMember> members = new ArrayList<>();

    public enum InterestCalculationMethod {
        DAILY_AVERAGE, MONTH_END, TIER_BASED
    }

    public enum PoolStatus {
        ACTIVE, SUSPENDED, PENDING, CLOSED
    }

    /**
     * Allocation methods for distributing pooled interest to members.
     *
     * <ul>
     *   <li>{@code CONTRIBUTION_PERCENT} — pro-rata by member balance share (default, legacy).</li>
     *   <li>{@code EQUAL} — split interest equally across active members.</li>
     *   <li>{@code WEIGHTED} — apply member-level {@code weight} as the allocation share.</li>
     *   <li>{@code OPTIMIZED_WHT} — cross-entity allocation that minimises withholding tax
     *       (only meaningful for CROSS_ENTITY pools; future phase).</li>
     * </ul>
     */
    public enum AllocationMethod {
        CONTRIBUTION_PERCENT,
        EQUAL,
        WEIGHTED,
        OPTIMIZED_WHT
    }
}
