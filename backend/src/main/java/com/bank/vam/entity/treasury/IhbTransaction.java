package com.bank.vam.entity.treasury;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Entity
@Table(name = "ihb_transactions")
public class IhbTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "transaction_ref", nullable = false, unique = true, length = 30)
    private String transactionRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false, length = 20)
    private TransactionType transactionType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "from_entity_id")
    private IhbEntity fromEntity;

    @Column(name = "from_entity_code", length = 20)
    private String fromEntityCode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "to_entity_id")
    private IhbEntity toEntity;

    @Column(name = "to_entity_code", length = 20)
    private String toEntityCode;

    @Column(name = "amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency_code", length = 3)
    private String currencyCode = "AED";

    @Column(name = "interest_rate", precision = 8, scale = 4)
    private BigDecimal interestRate;

    @Column(name = "transaction_date", nullable = false)
    private LocalDate transactionDate;

    @Column(name = "value_date", nullable = false)
    private LocalDate valueDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20)
    private TransactionStatus status = TransactionStatus.PENDING;

    @Column(name = "description", length = 500)
    private String description;

    // Link to source record (loan, deposit, or accrual)
    @Column(name = "source_type", length = 20)
    private String sourceType;

    @Column(name = "source_id")
    private UUID sourceId;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "created_by", length = 100)
    private String createdBy;

    public enum TransactionType {
        FUNDING,           // IHB lends to deficit entity
        BORROWING,         // IHB borrows from surplus entity
        INTEREST_CREDIT,   // Interest paid to surplus entity
        INTEREST_DEBIT,    // Interest charged to deficit entity
        REPAYMENT,         // Entity repays loan
        WITHDRAWAL         // Entity withdraws deposit
    }

    public enum TransactionStatus {
        PENDING, COMPLETED, REVERSED
    }
}
