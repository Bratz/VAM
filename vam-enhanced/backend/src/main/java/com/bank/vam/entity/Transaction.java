package com.bank.vam.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "va_movements")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Transaction extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "movement_type", nullable = false)
    private MovementType movementType;

    @Column(name = "va_id", nullable = false)
    private UUID vaId;

    @Column(name = "physical_account_id", nullable = false)
    private UUID physicalAccountId;

    @Column(name = "amount", precision = 18, scale = 2, nullable = false)
    private BigDecimal amount;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    @Column(name = "balance_before", precision = 18, scale = 2)
    private BigDecimal balanceBefore;

    @Column(name = "balance_after", precision = 18, scale = 2)
    private BigDecimal balanceAfter;

    @Column(name = "transaction_date", nullable = false)
    private LocalDateTime transactionDate;

    @Column(name = "value_date")
    private LocalDate valueDate;

    @Column(name = "reference_number", unique = true)
    private String referenceNumber;

    @Column(name = "description")
    private String description;

    @Column(name = "channel")
    private String channel;

    @Column(name = "remitter_name")
    private String remitterName;

    @Column(name = "remitter_account")
    private String remitterAccount;

    @Column(name = "beneficiary_name")
    private String beneficiaryName;

    @Column(name = "beneficiary_account")
    private String beneficiaryAccount;

    @Column(name = "counterparty_va_id")
    private UUID counterpartyVaId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    @Builder.Default
    private TransactionStatus status = TransactionStatus.COMPLETED;

    @Column(name = "bancs_reference")
    private String bancsReference;

    @Column(name = "external_reference")
    private String externalReference;

    public enum MovementType {
        CREDIT, DEBIT, TRANSFER_IN, TRANSFER_OUT, REVERSAL
    }

    public enum TransactionStatus {
        PENDING, COMPLETED, FAILED, REVERSED, CANCELLED
    }
}
