package com.bank.vam.service.tax;

import com.bank.vam.dto.tax.TaxChargeDto.*;
import com.bank.vam.entity.tax.*;
import com.bank.vam.entity.tax.WithholdingTaxTreaty.IncomeType;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.exception.ResourceNotFoundException;
import com.bank.vam.repository.tax.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Tax Service - Comprehensive tax calculation and management.
 * 
 * Features:
 * - Tax calculation based on jurisdiction and tax code
 * - Withholding tax support
 * - Net amount calculation (Gross - Discount + Tax - WHT)
 * - Tax recovery tracking
 * - Audit trail for all calculations
 * 
 * VAM Compliance:
 * - Multi-jurisdiction support (UAE, Saudi, UK, India, US)
 * - VAT, GST, WHT calculation
 * - Transfer pricing support
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaxService {

    private final TaxJurisdictionRepository jurisdictionRepository;
    private final TaxConfigurationRepository taxConfigRepository;
    private final CalculatedTaxRepository calculatedTaxRepository;
    private final WithholdingTaxTreatyRepository whtTreatyRepository;
    private final com.bank.vam.config.MarketProfileProperties marketProfile;

    // ========================================================================
    // JURISDICTION OPERATIONS
    // ========================================================================

    @Transactional(readOnly = true)
    public List<JurisdictionResponse> getAllJurisdictions() {
        return jurisdictionRepository.findAllActive().stream()
            .map(this::toJurisdictionResponse)
            .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public JurisdictionResponse getJurisdiction(String code) {
        TaxJurisdiction jurisdiction = jurisdictionRepository.findByJurisdictionCode(code)
            .orElseThrow(() -> new ResourceNotFoundException("Jurisdiction not found: " + code));
        return toJurisdictionResponse(jurisdiction);
    }

    // ========================================================================
    // TAX CONFIGURATION OPERATIONS
    // ========================================================================

    @Transactional(readOnly = true)
    public List<TaxConfigResponse> getAllTaxConfigs() {
        return taxConfigRepository.findAllActive().stream()
            .map(this::toTaxConfigResponse)
            .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public TaxConfigResponse getTaxConfig(String taxCode) {
        TaxConfiguration config = taxConfigRepository.findActiveByCode(taxCode)
            .orElseThrow(() -> new ResourceNotFoundException("Tax configuration not found: " + taxCode));
        return toTaxConfigResponse(config);
    }

    @Transactional(readOnly = true)
    public List<TaxConfigResponse> getTaxConfigsByJurisdiction(String jurisdictionCode) {
        return taxConfigRepository.findByJurisdictionCode(jurisdictionCode).stream()
            .filter(TaxConfiguration::isActive)
            .map(this::toTaxConfigResponse)
            .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<TaxConfigResponse> getWithholdingTaxConfigs() {
        return taxConfigRepository.findActiveWithholdingTaxes().stream()
            .map(this::toTaxConfigResponse)
            .collect(Collectors.toList());
    }

    @Transactional
    public TaxConfigResponse createTaxConfig(CreateTaxConfigRequest request) {
        if (taxConfigRepository.findByTaxCode(request.getTaxCode()).isPresent()) {
            throw new BusinessException("Tax code already exists: " + request.getTaxCode());
        }

        TaxConfiguration config = TaxConfiguration.builder()
            .taxCode(request.getTaxCode())
            .taxName(request.getTaxName())
            .description(request.getDescription())
            .taxType(request.getTaxType())
            .jurisdictionCode(request.getJurisdictionCode())
            .taxCategory(request.getTaxCategory())
            .ratePercentage(request.getRatePercentage())
            .minimumAmount(request.getMinimumAmount())
            .maximumAmount(request.getMaximumAmount())
            .appliesToPayables(request.getAppliesToPayables())
            .appliesToReceivables(request.getAppliesToReceivables())
            .isWithholding(request.getIsWithholding())
            .isRecoverable(request.getIsRecoverable())
            .effectiveFrom(request.getEffectiveFrom())
            .effectiveTo(request.getEffectiveTo())
            .status(TaxConfiguration.TaxStatus.ACTIVE)
            .build();

        config = taxConfigRepository.save(config);
        log.info("Created tax configuration: {} - {}", config.getTaxCode(), config.getTaxName());
        return toTaxConfigResponse(config);
    }

    // ========================================================================
    // TAX CALCULATION
    // ========================================================================

    /**
     * Calculate tax for a given amount and tax code.
     */
    @Transactional
    public CalculateTaxResponse calculateTax(CalculateTaxRequest request) {
        TaxConfiguration config = taxConfigRepository.findActiveByCode(request.getTaxCode())
            .orElseThrow(() -> new ResourceNotFoundException("Tax configuration not found: " + request.getTaxCode()));

        BigDecimal taxAmount = config.calculateTax(request.getBaseAmount());
        BigDecimal recoverableTax = config.calculateRecoverableTax(taxAmount);

        // Create audit record
        CalculatedTax calculated = CalculatedTax.builder()
            .referenceType(CalculatedTax.ReferenceType.valueOf(request.getReferenceType()))
            .referenceId(request.getReferenceId())
            .taxConfiguration(config)
            .taxCode(config.getTaxCode())
            .taxType(config.getTaxType())
            .baseAmount(request.getBaseAmount())
            .taxRate(config.getRatePercentage())
            .calculatedTax(taxAmount)
            .finalTax(taxAmount)
            .currencyCode(request.getCurrencyCode() != null ? request.getCurrencyCode() : marketProfile.getDefaultCurrency())
            .isWithholding(config.getIsWithholding())
            .status(CalculatedTax.CalculationStatus.CALCULATED)
            .build();

        calculated = calculatedTaxRepository.save(calculated);

        log.info("Calculated tax {} for reference {}: {} -> {}", 
            config.getTaxCode(), request.getReferenceId(), request.getBaseAmount(), taxAmount);

        return CalculateTaxResponse.builder()
            .calculationId(calculated.getId())
            .taxCode(config.getTaxCode())
            .taxName(config.getTaxName())
            .taxType(config.getTaxType())
            .baseAmount(request.getBaseAmount())
            .taxRate(config.getRatePercentage())
            .calculatedTax(taxAmount)
            .recoverableTax(recoverableTax)
            .isWithholding(config.getIsWithholding())
            .currencyCode(calculated.getCurrencyCode())
            .calculatedAt(LocalDateTime.now())
            .build();
    }

    /**
     * Calculate all applicable taxes for a payable.
     */
    @Transactional
    public TaxBreakdownResponse calculatePayableTaxes(UUID payableId, BigDecimal grossAmount, 
                                                       String jurisdictionCode, boolean isService) {
        List<TaxConfiguration> configs = taxConfigRepository.findApplicableToPayables().stream()
            .filter(c -> jurisdictionCode == null || jurisdictionCode.equals(c.getJurisdictionCode()))
            .filter(c -> c.appliesTo(true, isService))
            .collect(Collectors.toList());

        List<TaxLineItem> taxItems = new ArrayList<>();
        BigDecimal totalTax = BigDecimal.ZERO;
        BigDecimal totalWithholding = BigDecimal.ZERO;

        for (TaxConfiguration config : configs) {
            BigDecimal tax = config.calculateTax(grossAmount);
            
            if (tax.compareTo(BigDecimal.ZERO) > 0) {
                // Create audit record
                CalculatedTax calculated = CalculatedTax.create(
                    CalculatedTax.ReferenceType.PAYABLE, payableId, config, grossAmount);
                calculatedTaxRepository.save(calculated);

                taxItems.add(TaxLineItem.builder()
                    .taxCode(config.getTaxCode())
                    .taxName(config.getTaxName())
                    .taxType(config.getTaxType())
                    .rate(config.getRatePercentage())
                    .taxAmount(tax)
                    .isWithholding(config.getIsWithholding())
                    .isRecoverable(config.getIsRecoverable())
                    .build());

                if (Boolean.TRUE.equals(config.getIsWithholding())) {
                    totalWithholding = totalWithholding.add(tax);
                } else {
                    totalTax = totalTax.add(tax);
                }
            }
        }

        // Net = Gross + Tax - Withholding
        BigDecimal netAmount = grossAmount.add(totalTax).subtract(totalWithholding);

        return TaxBreakdownResponse.builder()
            .referenceId(payableId)
            .referenceType("PAYABLE")
            .grossAmount(grossAmount)
            .taxes(taxItems)
            .totalTax(totalTax)
            .totalWithholding(totalWithholding)
            .netAmount(netAmount)
            .currencyCode("AED")
            .build();
    }

    /**
     * Calculate net amount with full tax breakdown.
     * Formula: Net = Gross - Discount + Tax - WithholdingTax
     */
    @Transactional(readOnly = true)
    public CalculateNetAmountResponse calculateNetAmount(CalculateNetAmountRequest request) {
        BigDecimal grossAmount = request.getGrossAmount();
        BigDecimal discountAmount = request.getDiscountAmount() != null 
            ? request.getDiscountAmount() : BigDecimal.ZERO;
        
        BigDecimal taxAmount = BigDecimal.ZERO;
        BigDecimal withholdingTax = BigDecimal.ZERO;
        List<TaxLineItem> taxBreakdown = new ArrayList<>();

        // Calculate VAT/GST if tax code provided
        if (request.getTaxCode() != null) {
            TaxConfiguration taxConfig = taxConfigRepository.findActiveByCode(request.getTaxCode())
                .orElse(null);
            if (taxConfig != null) {
                BigDecimal baseForTax = grossAmount.subtract(discountAmount);
                taxAmount = taxConfig.calculateTax(baseForTax);
                
                taxBreakdown.add(TaxLineItem.builder()
                    .taxCode(taxConfig.getTaxCode())
                    .taxName(taxConfig.getTaxName())
                    .taxType(taxConfig.getTaxType())
                    .rate(taxConfig.getRatePercentage())
                    .taxAmount(taxAmount)
                    .isWithholding(false)
                    .isRecoverable(taxConfig.getIsRecoverable())
                    .build());
            }
        }

        // Calculate withholding tax if code provided
        if (request.getWithholdingTaxCode() != null) {
            TaxConfiguration whtConfig = taxConfigRepository.findActiveByCode(request.getWithholdingTaxCode())
                .orElse(null);
            if (whtConfig != null && Boolean.TRUE.equals(whtConfig.getIsWithholding())) {
                withholdingTax = whtConfig.calculateTax(grossAmount);
                
                taxBreakdown.add(TaxLineItem.builder()
                    .taxCode(whtConfig.getTaxCode())
                    .taxName(whtConfig.getTaxName())
                    .taxType(whtConfig.getTaxType())
                    .rate(whtConfig.getRatePercentage())
                    .taxAmount(withholdingTax)
                    .isWithholding(true)
                    .isRecoverable(false)
                    .build());
            }
        }

        // Calculate net: Gross - Discount + Tax - WHT
        BigDecimal netAmount = grossAmount
            .subtract(discountAmount)
            .add(taxAmount)
            .subtract(withholdingTax);

        String formula = String.format("Net = %.2f - %.2f + %.2f - %.2f = %.2f",
            grossAmount, discountAmount, taxAmount, withholdingTax, netAmount);

        return CalculateNetAmountResponse.builder()
            .grossAmount(grossAmount)
            .discountAmount(discountAmount)
            .taxAmount(taxAmount)
            .withholdingTax(withholdingTax)
            .netAmount(netAmount)
            .formula(formula)
            .currencyCode(request.getCurrencyCode() != null ? request.getCurrencyCode() : marketProfile.getDefaultCurrency())
            .taxBreakdown(taxBreakdown)
            .build();
    }

    /**
     * Get tax calculations for a reference.
     */
    @Transactional(readOnly = true)
    public List<CalculateTaxResponse> getTaxesForReference(String referenceType, UUID referenceId) {
        List<CalculatedTax> taxes = calculatedTaxRepository.findByReferenceTypeAndReferenceId(
            CalculatedTax.ReferenceType.valueOf(referenceType), referenceId);
        
        return taxes.stream()
            .map(ct -> CalculateTaxResponse.builder()
                .calculationId(ct.getId())
                .taxCode(ct.getTaxCode())
                .taxType(ct.getTaxType())
                .baseAmount(ct.getBaseAmount())
                .taxRate(ct.getTaxRate())
                .calculatedTax(ct.getCalculatedTax())
                .isWithholding(ct.getIsWithholding())
                .currencyCode(ct.getCurrencyCode())
                .calculatedAt(ct.getCreatedAt())
                .build())
            .collect(Collectors.toList());
    }

    /**
     * Apply calculated taxes (mark as applied).
     */
    @Transactional
    public void applyTaxes(UUID referenceId) {
        List<CalculatedTax> taxes = calculatedTaxRepository.findByReferenceTypeAndReferenceId(
            CalculatedTax.ReferenceType.PAYABLE, referenceId);
        
        for (CalculatedTax tax : taxes) {
            tax.apply();
        }
        
        calculatedTaxRepository.saveAll(taxes);
        log.info("Applied {} taxes for reference {}", taxes.size(), referenceId);
    }

    // ========================================================================
    // STANDARD TAX CODES (Convenience Methods)
    // ========================================================================

    /**
     * Get standard VAT rate for UAE (5%).
     */
    public BigDecimal getUaeVatRate() {
        return taxConfigRepository.findActiveByCode("VAT5")
            .map(TaxConfiguration::getRatePercentage)
            .orElse(new BigDecimal("5"));
    }

    /**
     * Calculate UAE VAT.
     */
    public BigDecimal calculateUaeVat(BigDecimal amount) {
        return amount.multiply(getUaeVatRate())
            .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
    }

    /**
     * Get standard rate for jurisdiction.
     */
    public BigDecimal getStandardRate(String jurisdictionCode) {
        return taxConfigRepository.findStandardVatForJurisdiction(jurisdictionCode)
            .map(TaxConfiguration::getRatePercentage)
            .orElse(BigDecimal.ZERO);
    }

    // ========================================================================
    // MAPPERS
    // ========================================================================

    private JurisdictionResponse toJurisdictionResponse(TaxJurisdiction j) {
        return JurisdictionResponse.builder()
            .id(j.getId())
            .jurisdictionCode(j.getJurisdictionCode())
            .jurisdictionName(j.getJurisdictionName())
            .countryCode(j.getCountryCode())
            .regionCode(j.getRegionCode())
            .supportsVat(j.getSupportsVat())
            .supportsGst(j.getSupportsGst())
            .supportsWithholding(j.getSupportsWithholding())
            .supportsSalesTax(j.getSupportsSalesTax())
            .taxAuthorityName(j.getTaxAuthorityName())
            .reportingCurrency(j.getReportingCurrency())
            .status(j.getStatus().name())
            .effectiveFrom(j.getEffectiveFrom())
            .effectiveTo(j.getEffectiveTo())
            .build();
    }

    // ========================================================================
    // WITHHOLDING TAX TREATY OPERATIONS (for IHB)
    // ========================================================================

    /**
     * Calculate withholding tax for IHB interest payment between two jurisdictions.
     *
     * @param payerJurisdiction    Jurisdiction of the entity making the interest payment
     * @param recipientJurisdiction Jurisdiction of the entity receiving the interest
     * @param grossInterest        The gross interest amount before WHT
     * @return WHT calculation result
     */
    @Transactional(readOnly = true)
    public IhbWhtResult calculateIhbInterestWht(
            String payerJurisdiction,
            String recipientJurisdiction,
            BigDecimal grossInterest) {

        // Same jurisdiction = no WHT
        if (payerJurisdiction != null && payerJurisdiction.equalsIgnoreCase(recipientJurisdiction)) {
            return IhbWhtResult.builder()
                .grossAmount(grossInterest)
                .whtAmount(BigDecimal.ZERO)
                .netAmount(grossInterest)
                .whtRate(BigDecimal.ZERO)
                .treatyApplied(false)
                .sameJurisdiction(true)
                .build();
        }

        // Look up treaty
        Optional<WithholdingTaxTreaty> treatyOpt = whtTreatyRepository.findActiveInterestTreaty(
            payerJurisdiction, recipientJurisdiction);

        if (treatyOpt.isPresent()) {
            WithholdingTaxTreaty treaty = treatyOpt.get();
            BigDecimal whtAmount = treaty.calculateWithholding(grossInterest);
            BigDecimal netAmount = grossInterest.subtract(whtAmount);

            log.debug("WHT calculated for {} → {}: {} @ {}% = {} (treaty: {})",
                payerJurisdiction, recipientJurisdiction, grossInterest,
                treaty.getTreatyRate(), whtAmount, treaty.getTreatyCode());

            return IhbWhtResult.builder()
                .grossAmount(grossInterest)
                .whtAmount(whtAmount)
                .netAmount(netAmount)
                .whtRate(treaty.getTreatyRate())
                .treatyCode(treaty.getTreatyCode())
                .treatyName(treaty.getTreatyName())
                .treatyApplied(true)
                .sameJurisdiction(false)
                .requiresTaxResidencyCert(treaty.getRequiresTaxResidencyCert())
                .requiresBeneficialOwnership(treaty.getRequiresBeneficialOwnership())
                .build();
        }

        // No treaty found - check if domestic WHT applies
        // For now, return zero WHT if no treaty exists (conservative approach)
        log.warn("No WHT treaty found for {} → {} - assuming no WHT applies",
            payerJurisdiction, recipientJurisdiction);

        return IhbWhtResult.builder()
            .grossAmount(grossInterest)
            .whtAmount(BigDecimal.ZERO)
            .netAmount(grossInterest)
            .whtRate(BigDecimal.ZERO)
            .treatyApplied(false)
            .sameJurisdiction(false)
            .noTreatyFound(true)
            .build();
    }

    /**
     * Check if WHT applies between two jurisdictions for interest.
     */
    @Transactional(readOnly = true)
    public boolean hasInterestWht(String payerJurisdiction, String recipientJurisdiction) {
        if (payerJurisdiction != null && payerJurisdiction.equalsIgnoreCase(recipientJurisdiction)) {
            return false;
        }
        return whtTreatyRepository.hasWithholdingTax(payerJurisdiction, recipientJurisdiction);
    }

    /**
     * Get WHT rate between two jurisdictions for interest.
     * Returns 0 if no WHT applies.
     */
    @Transactional(readOnly = true)
    public BigDecimal getInterestWhtRate(String payerJurisdiction, String recipientJurisdiction) {
        if (payerJurisdiction != null && payerJurisdiction.equalsIgnoreCase(recipientJurisdiction)) {
            return BigDecimal.ZERO;
        }
        return whtTreatyRepository.findActiveInterestTreaty(payerJurisdiction, recipientJurisdiction)
            .map(WithholdingTaxTreaty::getTreatyRate)
            .orElse(BigDecimal.ZERO);
    }

    /**
     * Get all active interest treaties.
     */
    @Transactional(readOnly = true)
    public List<WithholdingTaxTreaty> getAllActiveInterestTreaties() {
        return whtTreatyRepository.findAllActiveInterestTreaties();
    }

    /**
     * Create or update a WHT treaty.
     */
    @Transactional
    public WithholdingTaxTreaty saveWhtTreaty(WithholdingTaxTreaty treaty) {
        if (treaty.getTreatyCode() == null) {
            // Generate treaty code
            treaty.setTreatyCode(String.format("%s-%s-%s",
                treaty.getPayerJurisdictionCode(),
                treaty.getRecipientJurisdictionCode(),
                treaty.getIncomeType().name().substring(0, 3)));
        }
        return whtTreatyRepository.save(treaty);
    }

    /**
     * Result of IHB WHT calculation.
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class IhbWhtResult {
        private BigDecimal grossAmount;
        private BigDecimal whtAmount;
        private BigDecimal netAmount;
        private BigDecimal whtRate;
        private String treatyCode;
        private String treatyName;
        private boolean treatyApplied;
        private boolean sameJurisdiction;
        private boolean noTreatyFound;
        private Boolean requiresTaxResidencyCert;
        private Boolean requiresBeneficialOwnership;
    }

    // ========================================================================
    // MAPPERS
    // ========================================================================

    private TaxConfigResponse toTaxConfigResponse(TaxConfiguration c) {
        return TaxConfigResponse.builder()
            .id(c.getId())
            .taxCode(c.getTaxCode())
            .taxName(c.getTaxName())
            .description(c.getDescription())
            .taxType(c.getTaxType())
            .jurisdictionCode(c.getJurisdictionCode())
            .taxCategory(c.getTaxCategory())
            .ratePercentage(c.getRatePercentage())
            .minimumAmount(c.getMinimumAmount())
            .maximumAmount(c.getMaximumAmount())
            .appliesToPayables(c.getAppliesToPayables())
            .appliesToReceivables(c.getAppliesToReceivables())
            .isWithholding(c.getIsWithholding())
            .isRecoverable(c.getIsRecoverable())
            .recoveryPercentage(c.getRecoveryPercentage())
            .status(c.getStatus().name())
            .effectiveFrom(c.getEffectiveFrom())
            .effectiveTo(c.getEffectiveTo())
            .createdAt(c.getCreatedAt())
            .build();
    }
}