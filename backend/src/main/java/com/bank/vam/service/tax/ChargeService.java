package com.bank.vam.service.tax;

import com.bank.vam.dto.tax.TaxChargeDto.*;
import com.bank.vam.entity.payables.Payable;
import com.bank.vam.entity.tax.*;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.exception.ResourceNotFoundException;
import com.bank.vam.repository.tax.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Charge Service - Fee and charge calculation for payments.
 * 
 * Features:
 * - Processing fees (domestic/international)
 * - SWIFT transfer fees
 * - FX conversion fees
 * - Urgency/priority fees
 * - POBO service fees
 * - Waiver management
 * 
 * VAM Compliance:
 * - Transparent fee disclosure
 * - Tiered pricing support
 * - VIP/bulk waivers
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChargeService {

    private final ChargeConfigurationRepository chargeConfigRepository;
    private final CalculatedChargeRepository calculatedChargeRepository;
    private final com.bank.vam.config.MarketProfileProperties marketProfile;

    // ========================================================================
    // CHARGE CONFIGURATION OPERATIONS
    // ========================================================================

    @Transactional(readOnly = true)
    public List<ChargeConfigResponse> getAllChargeConfigs() {
        return chargeConfigRepository.findAllActive().stream()
            .map(this::toChargeConfigResponse)
            .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public ChargeConfigResponse getChargeConfig(String chargeCode) {
        ChargeConfiguration config = chargeConfigRepository.findActiveByCode(chargeCode)
            .orElseThrow(() -> new ResourceNotFoundException("Charge configuration not found: " + chargeCode));
        return toChargeConfigResponse(config);
    }

    @Transactional(readOnly = true)
    public List<ChargeConfigResponse> getChargeConfigsByType(ChargeConfiguration.ChargeType type) {
        return chargeConfigRepository.findActiveByType(type).stream()
            .map(this::toChargeConfigResponse)
            .collect(Collectors.toList());
    }

    @Transactional
    public ChargeConfigResponse createChargeConfig(CreateChargeConfigRequest request) {
        if (chargeConfigRepository.findByChargeCode(request.getChargeCode()).isPresent()) {
            throw new BusinessException("Charge code already exists: " + request.getChargeCode());
        }

        ChargeConfiguration config = ChargeConfiguration.builder()
            .chargeCode(request.getChargeCode())
            .chargeName(request.getChargeName())
            .description(request.getDescription())
            .chargeType(request.getChargeType())
            .chargeCategory(request.getChargeCategory())
            .fixedAmount(request.getFixedAmount())
            .percentageRate(request.getPercentageRate())
            .currencyCode(request.getCurrencyCode())
            .minimumCharge(request.getMinimumCharge())
            .maximumCharge(request.getMaximumCharge())
            .tierConfig(request.getTierConfig())
            .appliesToPaymentMethod(request.getAppliesToPaymentMethod())
            .appliesToPriority(request.getAppliesToPriority())
            .isCrossBorder(request.getIsCrossBorder())
            .isDomestic(request.getIsDomestic())
            .effectiveFrom(request.getEffectiveFrom())
            .effectiveTo(request.getEffectiveTo())
            .status(ChargeConfiguration.ChargeStatus.ACTIVE)
            .build();

        config = chargeConfigRepository.save(config);
        log.info("Created charge configuration: {} - {}", config.getChargeCode(), config.getChargeName());
        return toChargeConfigResponse(config);
    }

    // ========================================================================
    // CHARGE CALCULATION
    // ========================================================================

    /**
     * Calculate all applicable charges for a payment.
     */
    @Transactional
    public CalculateChargesResponse calculateCharges(CalculateChargesRequest request) {
        List<ChargeLineItem> chargeItems = new ArrayList<>();
        BigDecimal totalCharges = BigDecimal.ZERO;
        BigDecimal totalWaived = BigDecimal.ZERO;

        // Get applicable charges
        List<ChargeConfiguration> configs = findApplicableCharges(
            request.getPaymentMethod(),
            request.getPriority(),
            request.getIsCrossBorder()
        );

        for (ChargeConfiguration config : configs) {
            BigDecimal charge = config.calculateCharge(request.getBaseAmount());
            BigDecimal waived = BigDecimal.ZERO;
            String waiverReason = null;

            // Check for waivers
            if (config.shouldWaive(request.getBaseAmount(), 
                    Boolean.TRUE.equals(request.getIsVipCustomer()),
                    Boolean.TRUE.equals(request.getIsBulkPayment()))) {
                waived = charge;
                waiverReason = Boolean.TRUE.equals(request.getIsVipCustomer()) ? "VIP Customer" :
                              Boolean.TRUE.equals(request.getIsBulkPayment()) ? "Bulk Payment" : "Threshold Waiver";
                charge = BigDecimal.ZERO;
            }

            // Create audit record
            CalculatedCharge calculated = CalculatedCharge.builder()
                .referenceType(CalculatedCharge.ReferenceType.valueOf(request.getReferenceType()))
                .referenceId(request.getReferenceId())
                .chargeConfiguration(config)
                .chargeCode(config.getChargeCode())
                .chargeType(config.getChargeType())
                .baseAmount(request.getBaseAmount())
                .calculatedCharge(config.calculateCharge(request.getBaseAmount()))
                .waivedAmount(waived)
                .finalCharge(charge)
                .calculationMethod(config.getChargeCategory())
                .waiverReason(waiverReason)
                .currencyCode(request.getCurrencyCode() != null ? request.getCurrencyCode() : marketProfile.getDefaultCurrency())
                .status(CalculatedCharge.CalculationStatus.CALCULATED)
                .build();

            calculated = calculatedChargeRepository.save(calculated);

            chargeItems.add(ChargeLineItem.builder()
                .calculationId(calculated.getId())
                .chargeCode(config.getChargeCode())
                .chargeName(config.getChargeName())
                .chargeType(config.getChargeType())
                .calculatedCharge(calculated.getCalculatedCharge())
                .waivedAmount(waived)
                .finalCharge(charge)
                .waiverReason(waiverReason)
                .build());

            totalCharges = totalCharges.add(charge);
            totalWaived = totalWaived.add(waived);
        }

        log.info("Calculated {} charges for reference {}: total={}, waived={}",
            chargeItems.size(), request.getReferenceId(), totalCharges, totalWaived);

        return CalculateChargesResponse.builder()
            .referenceId(request.getReferenceId())
            .baseAmount(request.getBaseAmount())
            .charges(chargeItems)
            .totalCharges(totalCharges)
            .totalWaived(totalWaived)
            .netCharges(totalCharges)
            .currencyCode(request.getCurrencyCode() != null ? request.getCurrencyCode() : marketProfile.getDefaultCurrency())
            .calculatedAt(LocalDateTime.now())
            .build();
    }

    /**
     * Calculate total payment amount including all fees.
     */
    @Transactional
    public CalculatePaymentTotalResponse calculatePaymentTotal(CalculatePaymentTotalRequest request) {
        BigDecimal paymentAmount = request.getPaymentAmount();
        BigDecimal processingFee = BigDecimal.ZERO;
        BigDecimal swiftFee = BigDecimal.ZERO;
        BigDecimal fxFee = BigDecimal.ZERO;
        BigDecimal urgencyFee = BigDecimal.ZERO;
        List<ChargeLineItem> chargeBreakdown = new ArrayList<>();

        // Processing fee
        String procCode = Boolean.TRUE.equals(request.getIsCrossBorder()) ? "PROC_INT" : "PROC_DOM";
        Optional<ChargeConfiguration> procConfig = chargeConfigRepository.findActiveByCode(procCode);
        if (procConfig.isPresent()) {
            processingFee = procConfig.get().calculateCharge(paymentAmount);
            addChargeItem(chargeBreakdown, procConfig.get(), processingFee);
        }

        // SWIFT fee for cross-border
        if (Boolean.TRUE.equals(request.getIsCrossBorder())) {
            String swiftCode = request.getPriority() == Payable.PaymentPriority.URGENT ? "SWIFT_URG" : "SWIFT_STD";
            Optional<ChargeConfiguration> swiftConfig = chargeConfigRepository.findActiveByCode(swiftCode);
            if (swiftConfig.isPresent()) {
                swiftFee = swiftConfig.get().calculateCharge(paymentAmount);
                addChargeItem(chargeBreakdown, swiftConfig.get(), swiftFee);
            }

            // FX fee
            String fxCode = Boolean.TRUE.equals(request.getIsVipCustomer()) ? "FX_CORP" : "FX_STD";
            Optional<ChargeConfiguration> fxConfig = chargeConfigRepository.findActiveByCode(fxCode);
            if (fxConfig.isPresent()) {
                fxFee = fxConfig.get().calculateCharge(paymentAmount);
                addChargeItem(chargeBreakdown, fxConfig.get(), fxFee);
            }
        }

        // Urgency fee
        if (request.getPriority() != null && request.getPriority() != Payable.PaymentPriority.NORMAL) {
            String urgCode = "URG_" + request.getPriority().name();
            Optional<ChargeConfiguration> urgConfig = chargeConfigRepository.findActiveByCode(urgCode);
            if (urgConfig.isPresent()) {
                urgencyFee = urgConfig.get().calculateCharge(paymentAmount);
                addChargeItem(chargeBreakdown, urgConfig.get(), urgencyFee);
            }
        }

        BigDecimal totalCharges = processingFee.add(swiftFee).add(fxFee).add(urgencyFee);
        BigDecimal totalPayable = paymentAmount.add(totalCharges);

        return CalculatePaymentTotalResponse.builder()
            .payableId(request.getPayableId())
            .paymentAmount(paymentAmount)
            .processingFee(processingFee)
            .swiftFee(swiftFee)
            .fxFee(fxFee)
            .urgencyFee(urgencyFee)
            .totalCharges(totalCharges)
            .totalPayable(totalPayable)
            .chargeBreakdown(chargeBreakdown)
            .currencyCode("AED")
            .build();
    }

    /**
     * Calculate POBO service fee.
     */
    @Transactional
    public BigDecimal calculatePoboFee(BigDecimal amount) {
        return chargeConfigRepository.findActivePoboFee()
            .map(config -> config.calculateCharge(amount))
            .orElse(BigDecimal.ZERO);
    }

    /**
     * Calculate POBO fee with custom rate.
     */
    public BigDecimal calculatePoboFee(BigDecimal amount, BigDecimal ratePercentage, 
                                        BigDecimal minFee, BigDecimal maxFee) {
        BigDecimal fee = amount.multiply(ratePercentage)
            .divide(new BigDecimal("100"), 2, java.math.RoundingMode.HALF_UP);
        
        if (minFee != null && fee.compareTo(minFee) < 0) fee = minFee;
        if (maxFee != null && fee.compareTo(maxFee) > 0) fee = maxFee;
        
        return fee;
    }

    // ========================================================================
    // WAIVER MANAGEMENT
    // ========================================================================

    /**
     * Waive a charge.
     */
    @Transactional
    public void waiveCharge(UUID calculationId, String reason, String approvedBy) {
        CalculatedCharge charge = calculatedChargeRepository.findById(calculationId)
            .orElseThrow(() -> new ResourceNotFoundException("Calculated charge not found: " + calculationId));
        
        charge.waiveAll(reason, approvedBy);
        calculatedChargeRepository.save(charge);
        
        log.info("Waived charge {}: reason={}, approvedBy={}", calculationId, reason, approvedBy);
    }

    /**
     * Partial waiver.
     */
    @Transactional
    public void waiveCharge(UUID calculationId, BigDecimal waiverAmount, String reason, String approvedBy) {
        CalculatedCharge charge = calculatedChargeRepository.findById(calculationId)
            .orElseThrow(() -> new ResourceNotFoundException("Calculated charge not found: " + calculationId));
        
        charge.waive(waiverAmount, reason, approvedBy);
        calculatedChargeRepository.save(charge);
        
        log.info("Partially waived charge {}: amount={}, reason={}", calculationId, waiverAmount, reason);
    }

    /**
     * Get waived charges for reporting.
     */
    @Transactional(readOnly = true)
    public List<ChargeLineItem> getWaivedCharges() {
        return calculatedChargeRepository.findWaivedCharges().stream()
            .map(cc -> ChargeLineItem.builder()
                .calculationId(cc.getId())
                .chargeCode(cc.getChargeCode())
                .chargeType(cc.getChargeType())
                .calculatedCharge(cc.getCalculatedCharge())
                .waivedAmount(cc.getWaivedAmount())
                .finalCharge(cc.getFinalCharge())
                .waiverReason(cc.getWaiverReason())
                .build())
            .collect(Collectors.toList());
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================

    private List<ChargeConfiguration> findApplicableCharges(Payable.PaymentMethod method,
                                                             Payable.PaymentPriority priority,
                                                             Boolean isCrossBorder) {
        List<ChargeConfiguration> configs = new ArrayList<>();

        // Processing fee based on domestic/international
        if (Boolean.TRUE.equals(isCrossBorder)) {
            chargeConfigRepository.findActiveByCode("PROC_INT").ifPresent(configs::add);
        } else {
            chargeConfigRepository.findActiveByCode("PROC_DOM").ifPresent(configs::add);
        }

        // SWIFT fee for cross-border
        if (Boolean.TRUE.equals(isCrossBorder)) {
            chargeConfigRepository.findActiveByCode("SWIFT_STD").ifPresent(configs::add);
            chargeConfigRepository.findActiveByCode("FX_STD").ifPresent(configs::add);
        }

        // Urgency fee
        if (priority == Payable.PaymentPriority.URGENT) {
            chargeConfigRepository.findActiveByCode("URG_URGENT").ifPresent(configs::add);
        } else if (priority == Payable.PaymentPriority.HIGH) {
            chargeConfigRepository.findActiveByCode("URG_HIGH").ifPresent(configs::add);
        }

        return configs;
    }

    private void addChargeItem(List<ChargeLineItem> items, ChargeConfiguration config, BigDecimal amount) {
        items.add(ChargeLineItem.builder()
            .chargeCode(config.getChargeCode())
            .chargeName(config.getChargeName())
            .chargeType(config.getChargeType())
            .calculatedCharge(amount)
            .waivedAmount(BigDecimal.ZERO)
            .finalCharge(amount)
            .build());
    }

    /**
     * Apply charges (mark as applied).
     */
    @Transactional
    public void applyCharges(UUID referenceId) {
        List<CalculatedCharge> charges = calculatedChargeRepository.findByReferenceTypeAndReferenceId(
            CalculatedCharge.ReferenceType.PAYABLE, referenceId);
        
        for (CalculatedCharge charge : charges) {
            charge.apply();
        }
        
        calculatedChargeRepository.saveAll(charges);
        log.info("Applied {} charges for reference {}", charges.size(), referenceId);
    }

    /**
     * Get sum of charges for a reference.
     */
    @Transactional(readOnly = true)
    public BigDecimal getTotalCharges(String referenceType, UUID referenceId) {
        return calculatedChargeRepository.sumChargesForReference(
            CalculatedCharge.ReferenceType.valueOf(referenceType), referenceId);
    }

    // ========================================================================
    // MAPPERS
    // ========================================================================

    private ChargeConfigResponse toChargeConfigResponse(ChargeConfiguration c) {
        return ChargeConfigResponse.builder()
            .id(c.getId())
            .chargeCode(c.getChargeCode())
            .chargeName(c.getChargeName())
            .description(c.getDescription())
            .chargeType(c.getChargeType())
            .chargeCategory(c.getChargeCategory())
            .fixedAmount(c.getFixedAmount())
            .percentageRate(c.getPercentageRate())
            .currencyCode(c.getCurrencyCode())
            .minimumCharge(c.getMinimumCharge())
            .maximumCharge(c.getMaximumCharge())
            .tierConfig(c.getTierConfig())
            .appliesToPaymentMethod(c.getAppliesToPaymentMethod() != null 
                ? c.getAppliesToPaymentMethod().name() : null)
            .appliesToPriority(c.getAppliesToPriority() != null 
                ? c.getAppliesToPriority().name() : null)
            .isCrossBorder(c.getIsCrossBorder())
            .isDomestic(c.getIsDomestic())
            .waiverThreshold(c.getWaiverThreshold())
            .waiverForVip(c.getWaiverForVip())
            .status(c.getStatus().name())
            .effectiveFrom(c.getEffectiveFrom())
            .effectiveTo(c.getEffectiveTo())
            .createdAt(c.getCreatedAt())
            .build();
    }
}