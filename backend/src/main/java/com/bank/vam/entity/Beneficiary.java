package com.bank.vam.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(name = "beneficiaries")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Beneficiary extends BaseEntity {

    @Column(name = "corporate_id", nullable = false)
    private UUID corporateId;

    @Column(name = "beneficiary_name", nullable = false)
    private String beneficiaryName;

    @Enumerated(EnumType.STRING)
    @Column(name = "beneficiary_type")
    private BeneficiaryType beneficiaryType;

    @Column(name = "bank_name")
    private String bankName;

    @Column(name = "swift_code", length = 11)
    private String swiftCode;

    @Column(name = "account_number")
    private String accountNumber;

    @Column(name = "iban", length = 34)
    private String iban;

    @Column(name = "currency_code", length = 3)
    private String currencyCode;

    @Column(name = "country_code", length = 2)
    private String countryCode;

    @Column(name = "city")
    private String city;

    @Column(name = "address_line1")
    private String addressLine1;

    @Column(name = "address_line2")
    private String addressLine2;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    @Builder.Default
    private BeneficiaryStatus status = BeneficiaryStatus.ACTIVE;

    @Enumerated(EnumType.STRING)
    @Column(name = "validation_status")
    private ValidationStatus validationStatus;

    public enum BeneficiaryType {
        INDIVIDUAL, CORPORATE
    }

    public enum BeneficiaryStatus {
        ACTIVE, INACTIVE, BLOCKED
    }

    public enum ValidationStatus {
        PENDING, VERIFIED, FAILED, EXPIRED
    }
}
