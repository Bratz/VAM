package com.bank.vam.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "virtual_accounts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VirtualAccount extends BaseEntity {

    @Column(name = "va_number", unique = true, nullable = false)
    private String vaNumber;

    @Column(name = "viban", unique = true)
    private String viban;

    @Column(name = "va_name", nullable = false)
    private String vaName;

    @Column(name = "program_id")
    private UUID programId;

    @Column(name = "corporate_id", nullable = false)
    private UUID corporateId;

    @Column(name = "physical_account_id", nullable = false)
    private UUID physicalAccountId;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    @Column(name = "current_balance", precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal currentBalance = BigDecimal.ZERO;

    @Column(name = "available_balance", precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal availableBalance = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    @Builder.Default
    private VaStatus status = VaStatus.ACTIVE;

    @Column(name = "external_reference")
    private String externalReference;

    @Column(name = "kyc_verified")
    @Builder.Default
    private Boolean kycVerified = false;

    @Column(name = "wallet_type")
    private String walletType;

    @Column(name = "metadata", columnDefinition = "jsonb")
    private String metadata;

    public enum VaStatus {
        ACTIVE, INACTIVE, SUSPENDED, CLOSED
    }
}
