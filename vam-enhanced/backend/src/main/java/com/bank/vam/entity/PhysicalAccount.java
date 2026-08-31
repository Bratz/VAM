package com.bank.vam.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "physical_accounts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PhysicalAccount extends BaseEntity {

    @Column(name = "account_number", unique = true, nullable = false)
    private String accountNumber;

    @Column(name = "account_name", nullable = false)
    private String accountName;

    @Column(name = "corporate_id", nullable = false)
    private UUID corporateId;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type")
    private AccountType accountType;

    @Column(name = "bancs_customer_id")
    private String bancsCustomerId;

    @Column(name = "bancs_account_id")
    private String bancsAccountId;

    @Column(name = "iban", length = 34)
    private String iban;

    @Column(name = "current_balance", precision = 18, scale = 2)
    private BigDecimal currentBalance;

    @Column(name = "available_balance", precision = 18, scale = 2)
    private BigDecimal availableBalance;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private AccountStatus status;

    @Column(name = "opened_date")
    private LocalDate openedDate;

    @Column(name = "branch_code")
    private String branchCode;

    @Column(name = "relationship_manager")
    private String relationshipManager;

    public enum AccountType {
        CURRENT, SAVINGS, ESCROW, POOL, COLLECTION
    }

    public enum AccountStatus {
        ACTIVE, INACTIVE, FROZEN, CLOSED, DORMANT
    }
}
