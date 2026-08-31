package com.bank.vam.entity.treasury;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Entity
@Table(name = "pool_interest_calculations")
public class PoolInterestCalculation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pool_id", nullable = false)
    private NotionalPool pool;

    @Column(name = "calculation_date", nullable = false)
    private LocalDate calculationDate;

    @Column(name = "pool_balance", nullable = false, precision = 18, scale = 2)
    private BigDecimal poolBalance;

    @Column(name = "interest_rate", nullable = false, precision = 8, scale = 4)
    private BigDecimal interestRate;

    @Column(name = "gross_interest", nullable = false, precision = 18, scale = 2)
    private BigDecimal grossInterest;

    @Column(name = "net_interest", nullable = false, precision = 18, scale = 2)
    private BigDecimal netInterest;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20)
    private CalculationStatus status = CalculationStatus.PENDING;

    @Column(name = "approved_by", length = 100)
    private String approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "posted_at")
    private LocalDateTime postedAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    public enum CalculationStatus {
        PENDING, APPROVED, POSTED, REJECTED
    }
}
