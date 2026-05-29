package com.bank.vam.entity.treasury;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Entity
@Table(name = "ihb_interest_accruals")
public class IhbInterestAccrual {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "entity_id", nullable = false)
    private IhbEntity entity;

    @Column(name = "entity_code", length = 20)
    private String entityCode;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    @Enumerated(EnumType.STRING)
    @Column(name = "position_type", length = 20)
    private PositionType positionType;

    @Column(name = "average_balance", precision = 18, scale = 2)
    private BigDecimal averageBalance;

    @Column(name = "interest_rate", precision = 8, scale = 4)
    private BigDecimal interestRate;

    @Column(name = "accrued_amount", precision = 18, scale = 2)
    private BigDecimal accruedAmount;

    @Column(name = "currency_code", length = 3)
    private String currencyCode = "AED";

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20)
    private AccrualStatus status = AccrualStatus.ACCRUED;

    @Column(name = "posted_date")
    private LocalDate postedDate;

    @Column(name = "posting_reference", length = 30)
    private String postingReference;

    @Column(name = "calculated_at")
    private LocalDateTime calculatedAt = LocalDateTime.now();

    @Column(name = "posted_at")
    private LocalDateTime postedAt;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    public enum PositionType {
        SURPLUS, DEFICIT
    }

    public enum AccrualStatus {
        ACCRUED, POSTED, SETTLED, REVERSED
    }
}
