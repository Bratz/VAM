package com.bank.vam.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

@Entity
@Table(name = "corporates")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Corporate extends BaseEntity {

    @Column(name = "corporate_id", unique = true, nullable = false)
    private String corporateId;

    @Column(name = "legal_name", nullable = false)
    private String legalName;

    @Column(name = "trade_name")
    private String tradeName;

    @Column(name = "registration_number")
    private String registrationNumber;

    @Column(name = "tax_id")
    private String taxId;

    @Column(name = "incorporation_country")
    private String incorporationCountry;

    @Column(name = "incorporation_date")
    private LocalDate incorporationDate;

    @Column(name = "legal_entity_type")
    private String legalEntityType;

    @Column(name = "industry_sector")
    private String industrySector;

    @Column(name = "business_description", columnDefinition = "TEXT")
    private String businessDescription;

    @Column(name = "website")
    private String website;

    @Column(name = "primary_contact_name")
    private String primaryContactName;

    @Column(name = "primary_contact_email")
    private String primaryContactEmail;

    @Column(name = "primary_contact_phone")
    private String primaryContactPhone;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private CorporateStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "kyc_status")
    private KycStatus kycStatus;

    @Column(name = "risk_rating")
    private String riskRating;

    public enum CorporateStatus {
        ACTIVE, INACTIVE, SUSPENDED, PENDING_APPROVAL
    }

    public enum KycStatus {
        PENDING, APPROVED, REJECTED, EXPIRED, UNDER_REVIEW
    }
}
