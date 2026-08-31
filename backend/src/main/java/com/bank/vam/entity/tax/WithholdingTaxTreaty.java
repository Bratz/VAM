package com.bank.vam.entity.tax;

import com.bank.vam.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * Withholding Tax Treaty Entity - Double Tax Treaty (DTT) rates between jurisdictions.
 *
 * Used for:
 * - IHB intercompany interest payments between entities in different jurisdictions
 * - Cross-border payments where WHT applies
 * - Transfer pricing compliance
 *
 * WHT Scenarios for IHB:
 * 1. Credit Interest (Treasury → Participant): Treasury may withhold when paying interest
 * 2. Debit Interest (Participant → Treasury): Participant may withhold when paying interest
 * 3. Loan Interest: Borrower withholds from interest payments to lender
 * 4. Deposit Interest: Treasury withholds from interest payments to depositor
 *
 * Treaty Network:
 * - UAE has treaties with 100+ countries
 * - Rates vary by income type (dividends, interest, royalties)
 * - Some treaties reduce standard domestic WHT rates
 */
@Entity
@Table(name = "withholding_tax_treaties", indexes = {
    @Index(name = "idx_wht_treaty_payer", columnList = "payer_jurisdiction_code"),
    @Index(name = "idx_wht_treaty_recipient", columnList = "recipient_jurisdiction_code"),
    @Index(name = "idx_wht_treaty_pair", columnList = "payer_jurisdiction_code, recipient_jurisdiction_code"),
    @Index(name = "idx_wht_treaty_income_type", columnList = "income_type"),
    @Index(name = "idx_wht_treaty_status", columnList = "status")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WithholdingTaxTreaty extends BaseEntity {

    // ========================================================================
    // IDENTIFICATION
    // ========================================================================

    @Column(name = "treaty_code", nullable = false, unique = true, length = 30)
    private String treatyCode;

    @Column(name = "treaty_name", nullable = false, length = 150)
    private String treatyName;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    // ========================================================================
    // JURISDICTION PAIR
    // ========================================================================

    /**
     * Jurisdiction of the payer (entity making the payment).
     * For IHB debit interest: Treasury Center's jurisdiction
     * For IHB credit interest: Participant's jurisdiction
     */
    @Column(name = "payer_jurisdiction_code", nullable = false, length = 20)
    private String payerJurisdictionCode;

    /**
     * Jurisdiction of the recipient (entity receiving the payment).
     * For IHB debit interest: Participant's jurisdiction
     * For IHB credit interest: Treasury Center's jurisdiction
     */
    @Column(name = "recipient_jurisdiction_code", nullable = false, length = 20)
    private String recipientJurisdictionCode;

    // ========================================================================
    // INCOME TYPE & RATE
    // ========================================================================

    @Enumerated(EnumType.STRING)
    @Column(name = "income_type", nullable = false, length = 30)
    private IncomeType incomeType;

    /**
     * WHT rate under the treaty (percentage).
     * Example: 5.00 means 5% withholding
     * 0.00 means exempt under treaty
     */
    @Column(name = "treaty_rate", nullable = false, precision = 8, scale = 4)
    private BigDecimal treatyRate;

    /**
     * Domestic WHT rate without treaty (for reference).
     * The treaty rate should be <= domestic rate.
     */
    @Column(name = "domestic_rate", precision = 8, scale = 4)
    private BigDecimal domesticRate;

    /**
     * Minimum amount before WHT applies.
     */
    @Column(name = "minimum_amount", precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal minimumAmount = BigDecimal.ZERO;

    // ========================================================================
    // TREATY CONDITIONS
    // ========================================================================

    /**
     * Whether beneficial ownership certificate is required.
     */
    @Column(name = "requires_beneficial_ownership")
    @Builder.Default
    private Boolean requiresBeneficialOwnership = false;

    /**
     * Whether tax residency certificate is required.
     */
    @Column(name = "requires_tax_residency_cert")
    @Builder.Default
    private Boolean requiresTaxResidencyCert = true;

    /**
     * Whether limitation of benefits (LOB) applies.
     */
    @Column(name = "has_lob_clause")
    @Builder.Default
    private Boolean hasLobClause = false;

    /**
     * Notes on treaty conditions or limitations.
     */
    @Column(name = "treaty_conditions", columnDefinition = "TEXT")
    private String treatyConditions;

    // ========================================================================
    // VALIDITY
    // ========================================================================

    @Column(name = "effective_from", nullable = false)
    @Builder.Default
    private LocalDate effectiveFrom = LocalDate.now();

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20)
    @Builder.Default
    private TreatyStatus status = TreatyStatus.ACTIVE;

    // ========================================================================
    // ENUMS
    // ========================================================================

    public enum IncomeType {
        INTEREST,           // Loan/deposit interest (IHB primary use case)
        DIVIDENDS,          // Dividend payments
        ROYALTIES,          // Royalty payments
        FEES,               // Management/service fees
        CAPITAL_GAINS,      // Capital gains
        RENT,               // Rental income
        OTHER               // Other income types
    }

    public enum TreatyStatus {
        ACTIVE,             // Treaty in force
        PENDING,            // Treaty signed but not ratified
        SUSPENDED,          // Treaty temporarily suspended
        TERMINATED,         // Treaty terminated
        RENEGOTIATING       // Treaty being renegotiated
    }

    // ========================================================================
    // CALCULATION METHODS
    // ========================================================================

    /**
     * Calculate withholding tax amount.
     */
    public BigDecimal calculateWithholding(BigDecimal grossAmount) {
        if (grossAmount == null || grossAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }

        // Check minimum threshold
        if (minimumAmount != null && grossAmount.compareTo(minimumAmount) < 0) {
            return BigDecimal.ZERO;
        }

        // Calculate WHT: gross * rate / 100
        return grossAmount.multiply(treatyRate)
            .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
    }

    /**
     * Calculate net amount after withholding.
     */
    public BigDecimal calculateNetAmount(BigDecimal grossAmount) {
        BigDecimal wht = calculateWithholding(grossAmount);
        return grossAmount.subtract(wht);
    }

    /**
     * Get effective rate (treaty rate if treaty exists, otherwise domestic).
     */
    public BigDecimal getEffectiveRate() {
        if (isActive() && treatyRate != null) {
            return treatyRate;
        }
        return domesticRate != null ? domesticRate : BigDecimal.ZERO;
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================

    public boolean isActive() {
        if (status != TreatyStatus.ACTIVE) {
            return false;
        }
        LocalDate today = LocalDate.now();
        if (effectiveFrom != null && today.isBefore(effectiveFrom)) {
            return false;
        }
        if (effectiveTo != null && today.isAfter(effectiveTo)) {
            return false;
        }
        return true;
    }

    /**
     * Check if WHT applies (rate > 0).
     */
    public boolean hasWithholding() {
        return isActive() && treatyRate != null && treatyRate.compareTo(BigDecimal.ZERO) > 0;
    }

    /**
     * Check if this is an exempt treaty (0% rate).
     */
    public boolean isExempt() {
        return isActive() && treatyRate != null && treatyRate.compareTo(BigDecimal.ZERO) == 0;
    }

    /**
     * Check if documentation requirements are met.
     */
    public boolean requiresDocumentation() {
        return Boolean.TRUE.equals(requiresBeneficialOwnership)
            || Boolean.TRUE.equals(requiresTaxResidencyCert);
    }

    // ========================================================================
    // FACTORY METHODS
    // ========================================================================

    /**
     * Create a treaty for UAE-India interest payments (standard 5% under treaty).
     */
    public static WithholdingTaxTreaty createUaeIndiaTreaty() {
        return WithholdingTaxTreaty.builder()
            .treatyCode("UAE-IND-INT")
            .treatyName("UAE-India DTT - Interest")
            .description("Interest income under UAE-India Double Tax Treaty")
            .payerJurisdictionCode("IND")
            .recipientJurisdictionCode("UAE")
            .incomeType(IncomeType.INTEREST)
            .treatyRate(new BigDecimal("5.00"))
            .domesticRate(new BigDecimal("10.00"))  // India domestic rate is 10%
            .requiresTaxResidencyCert(true)
            .requiresBeneficialOwnership(true)
            .build();
    }

    /**
     * Create a treaty for UAE-Saudi interest payments (typically 0% within GCC).
     */
    public static WithholdingTaxTreaty createUaeSaudiTreaty() {
        return WithholdingTaxTreaty.builder()
            .treatyCode("UAE-SAU-INT")
            .treatyName("UAE-Saudi Interest - GCC Exemption")
            .description("Interest exempt under GCC unified economic agreement")
            .payerJurisdictionCode("SAU")
            .recipientJurisdictionCode("UAE")
            .incomeType(IncomeType.INTEREST)
            .treatyRate(BigDecimal.ZERO)
            .domesticRate(new BigDecimal("5.00"))
            .requiresTaxResidencyCert(false)
            .build();
    }

    /**
     * Create same-jurisdiction entry (no WHT for domestic).
     */
    public static WithholdingTaxTreaty createDomestic(String jurisdictionCode) {
        return WithholdingTaxTreaty.builder()
            .treatyCode(jurisdictionCode + "-" + jurisdictionCode + "-INT")
            .treatyName(jurisdictionCode + " Domestic - Interest")
            .description("Domestic interest - no withholding")
            .payerJurisdictionCode(jurisdictionCode)
            .recipientJurisdictionCode(jurisdictionCode)
            .incomeType(IncomeType.INTEREST)
            .treatyRate(BigDecimal.ZERO)
            .domesticRate(BigDecimal.ZERO)
            .requiresTaxResidencyCert(false)
            .build();
    }
}
