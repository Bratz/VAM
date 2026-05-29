package com.bank.vam.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

@Entity
@Table(name = "unified_programs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Program extends BaseEntity {

    @Column(name = "program_code", unique = true, nullable = false)
    private String programCode;

    @Column(name = "program_name", nullable = false)
    private String programName;

    @Enumerated(EnumType.STRING)
    @Column(name = "program_type", nullable = false)
    private ProgramType programType;

    @Column(name = "corporate_id", nullable = false)
    private UUID corporateId;

    @Column(name = "physical_account_id", nullable = false)
    private UUID physicalAccountId;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "va_prefix", length = 10)
    private String vaPrefix;

    @Column(name = "va_format")
    private String vaFormat;

    @Column(name = "max_virtual_accounts")
    private Integer maxVirtualAccounts;

    @Column(name = "auto_reconciliation")
    @Builder.Default
    private Boolean autoReconciliation = true;

    @Column(name = "settlement_frequency")
    private String settlementFrequency;

    @Column(name = "settlement_time")
    private LocalTime settlementTime;

    @Column(name = "min_balance_threshold", precision = 18, scale = 2)
    private BigDecimal minBalanceThreshold;

    @Column(name = "viban_enabled")
    @Builder.Default
    private Boolean vibanEnabled = false;

    @Column(name = "wallet_enabled")
    @Builder.Default
    private Boolean walletEnabled = false;

    @Column(name = "escrow_enabled")
    @Builder.Default
    private Boolean escrowEnabled = false;

    @Column(name = "ihb_enabled")
    @Builder.Default
    private Boolean ihbEnabled = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    @Builder.Default
    private ProgramStatus status = ProgramStatus.ACTIVE;

    @Column(name = "effective_from")
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    public enum ProgramType {
        COLLECTION, VIBAN, ESCROW, WALLET, IHB, PAYABLES
    }

    public enum ProgramStatus {
        ACTIVE, INACTIVE, SUSPENDED, PENDING_APPROVAL
    }
}
