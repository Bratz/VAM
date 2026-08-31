package com.bank.vam.entity.treasury;

import com.bank.vam.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "netting_cycles")
public class NettingCycle extends BaseEntity {

    @Column(name = "cycle_reference", nullable = false, unique = true, length = 20)
    private String cycleReference;

    @Column(name = "cycle_name", nullable = false, length = 100)
    private String cycleName;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    @Column(name = "settlement_date")
    private LocalDate settlementDate;

    @Column(name = "base_currency", nullable = false, length = 3)
    private String baseCurrency = "AED";

    @Column(name = "total_gross", precision = 18, scale = 2)
    private BigDecimal totalGross = BigDecimal.ZERO;

    @Column(name = "total_net", precision = 18, scale = 2)
    private BigDecimal totalNet = BigDecimal.ZERO;

    @Column(name = "savings_amount", precision = 18, scale = 2)
    private BigDecimal savingsAmount = BigDecimal.ZERO;

    @Column(name = "savings_percent", precision = 8, scale = 4)
    private BigDecimal savingsPercent = BigDecimal.ZERO;

    @Column(name = "entry_count")
    private Integer entryCount = 0;

    @Column(name = "participant_count")
    private Integer participantCount = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20)
    private CycleStatus status = CycleStatus.DRAFT;

    @Column(name = "approved_by", length = 100)
    private String approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "settled_at")
    private LocalDateTime settledAt;

    @OneToMany(mappedBy = "cycle", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<NettingEntry> entries = new HashSet<>();

    @OneToMany(mappedBy = "cycle", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<NettingSettlement> settlements = new HashSet<>();

    public enum CycleStatus {
        DRAFT, OPEN, CALCULATING, PENDING_APPROVAL, APPROVED, SETTLED, CANCELLED
    }
}
