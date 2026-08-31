package com.bank.vam.entity.tax;

import com.bank.vam.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Tax Jurisdiction Entity - Defines tax regulatory jurisdictions.
 * 
 * Supports:
 * - Multiple tax types (VAT, GST, Withholding, Sales Tax)
 * - Country/region specific rules
 * - Registration thresholds
 * - Tax authority information
 * 
 * Domain Model:
 * - TaxJurisdiction (1) -> TaxConfiguration (N)
 */
@Entity
@Table(name = "tax_jurisdictions", indexes = {
    @Index(name = "idx_jurisdiction_country", columnList = "country_code"),
    @Index(name = "idx_jurisdiction_status", columnList = "status")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TaxJurisdiction extends BaseEntity {

    @Column(name = "jurisdiction_code", nullable = false, unique = true, length = 20)
    private String jurisdictionCode;

    @Column(name = "jurisdiction_name", nullable = false, length = 100)
    private String jurisdictionName;

    @Column(name = "country_code", nullable = false, length = 3)
    private String countryCode;

    @Column(name = "region_code", length = 20)
    private String regionCode;

    // Tax types supported
    @Column(name = "supports_vat")
    @Builder.Default
    private Boolean supportsVat = true;

    @Column(name = "supports_gst")
    @Builder.Default
    private Boolean supportsGst = false;

    @Column(name = "supports_withholding")
    @Builder.Default
    private Boolean supportsWithholding = true;

    @Column(name = "supports_sales_tax")
    @Builder.Default
    private Boolean supportsSalesTax = false;

    // Regulatory info
    @Column(name = "tax_authority_name", length = 200)
    private String taxAuthorityName;

    @Column(name = "tax_authority_id", length = 50)
    private String taxAuthorityId;

    @Column(name = "reporting_currency", length = 3)
    @Builder.Default
    private String reportingCurrency = "AED";

    // Thresholds
    @Column(name = "vat_registration_threshold", precision = 18, scale = 2)
    private BigDecimal vatRegistrationThreshold;

    @Column(name = "withholding_threshold", precision = 18, scale = 2)
    private BigDecimal withholdingThreshold;

    // Status and validity
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20)
    @Builder.Default
    private JurisdictionStatus status = JurisdictionStatus.ACTIVE;

    @Column(name = "effective_from", nullable = false)
    @Builder.Default
    private LocalDate effectiveFrom = LocalDate.now();

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    public enum JurisdictionStatus {
        ACTIVE, INACTIVE, PENDING
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================

    public boolean isActive() {
        return status == JurisdictionStatus.ACTIVE
            && (effectiveTo == null || !LocalDate.now().isAfter(effectiveTo));
    }

    public boolean supportsAnyTax() {
        return Boolean.TRUE.equals(supportsVat) 
            || Boolean.TRUE.equals(supportsGst) 
            || Boolean.TRUE.equals(supportsWithholding)
            || Boolean.TRUE.equals(supportsSalesTax);
    }

    // ========================================================================
    // FACTORY METHODS
    // ========================================================================

    public static TaxJurisdiction createUAE() {
        return TaxJurisdiction.builder()
            .jurisdictionCode("UAE")
            .jurisdictionName("United Arab Emirates")
            .countryCode("ARE")
            .supportsVat(true)
            .supportsWithholding(true)
            .taxAuthorityName("Federal Tax Authority")
            .reportingCurrency("AED")
            .effectiveFrom(LocalDate.of(2018, 1, 1))
            .build();
    }

    public static TaxJurisdiction createSaudiArabia() {
        return TaxJurisdiction.builder()
            .jurisdictionCode("SAU")
            .jurisdictionName("Saudi Arabia")
            .countryCode("SAU")
            .supportsVat(true)
            .supportsWithholding(true)
            .taxAuthorityName("General Authority of Zakat and Tax")
            .reportingCurrency("SAR")
            .effectiveFrom(LocalDate.of(2018, 1, 1))
            .build();
    }
}