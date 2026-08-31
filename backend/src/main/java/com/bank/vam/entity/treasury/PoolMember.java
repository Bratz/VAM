package com.bank.vam.entity.treasury;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Entity
@Table(name = "pool_members")
public class PoolMember {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pool_id", nullable = false)
    private NotionalPool pool;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "account_number", nullable = false, length = 34)
    private String accountNumber;

    @Column(name = "entity_code", length = 20)
    private String entityCode;

    @Column(name = "entity_name", length = 100)
    private String entityName;

    @Column(name = "current_balance", precision = 18, scale = 2)
    private BigDecimal currentBalance = BigDecimal.ZERO;

    @Column(name = "contribution_percent", precision = 8, scale = 4)
    private BigDecimal contributionPercent = BigDecimal.ZERO;

    @Column(name = "interest_allocation", precision = 18, scale = 2)
    private BigDecimal interestAllocation = BigDecimal.ZERO;

    /**
     * Member weight used by {@code AllocationMethod.WEIGHTED}. The pool engine
     * normalises member weights so the share of pooled interest a member receives
     * equals {@code weight / sum(weights)}. Ignored by other allocation methods.
     */
    @Column(name = "weight", precision = 12, scale = 4)
    private BigDecimal weight = BigDecimal.ONE;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20)
    private MemberStatus status = MemberStatus.ACTIVE;

    @Column(name = "joined_date")
    private LocalDate joinedDate;

    @Column(name = "left_date")
    private LocalDate leftDate;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt = LocalDateTime.now();

    public enum MemberStatus {
        ACTIVE, INACTIVE, SUSPENDED
    }
}
