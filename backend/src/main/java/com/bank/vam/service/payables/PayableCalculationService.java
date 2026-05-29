package com.bank.vam.service.payables;

import com.bank.vam.dto.pobo.PoboDto;
import com.bank.vam.dto.tax.TaxChargeDto.*;
import com.bank.vam.entity.payables.Payable;
import com.bank.vam.service.pobo.PoboService;
import com.bank.vam.service.tax.ChargeService;
import com.bank.vam.service.tax.TaxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Payable Calculation Service - Integrates Tax, Charges, and POBO with Payables.
 * 
 * This service bridges the gap between:
 * - Payables module (Week 4)
 * - Tax/Charge module (Phase 5)
 * - POBO module (Phase 5)
 * 
 * Key Functions:
 * 1. Calculate complete net amount (with taxes and discounts)
 * 2. Calculate payment total (with all charges/fees)
 * 3. Validate and process POBO payments
 * 4. Create intercompany recharges
 * 
 * Formula for Net Amount:
 *   Net = Gross - Discount + Tax - WithholdingTax
 * 
 * Formula for Payment Total:
 *   Total = Net + ProcessingFee + SwiftFee + FxFee + UrgencyFee
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PayableCalculationService {

    private final TaxService taxService;
    private final ChargeService chargeService;
    private final PoboService poboService;

    // ========================================================================
    // COMPLETE PAYABLE CALCULATION
    // ========================================================================

    /**
     * Calculate complete payable amounts including taxes.
     * This should be called when creating or updating a payable.
     * 
     * @param grossAmount Original invoice amount
     * @param discountAmount Early payment or volume discount
     * @param taxCode Tax code to apply (e.g., "VAT5")
     * @param withholdingTaxCode Withholding tax code (e.g., "WHT5")
     * @param jurisdictionCode Tax jurisdiction (e.g., "UAE")
     * @return Complete calculation with breakdown
     */
    @Transactional
    public PayableAmountCalculation calculatePayableAmounts(
            BigDecimal grossAmount,
            BigDecimal discountAmount,
            String taxCode,
            String withholdingTaxCode,
            String jurisdictionCode) {

        CalculateNetAmountRequest request = CalculateNetAmountRequest.builder()
            .grossAmount(grossAmount)
            .discountAmount(discountAmount)
            .taxCode(taxCode)
            .withholdingTaxCode(withholdingTaxCode)
            .jurisdictionCode(jurisdictionCode)
            .currencyCode("AED")
            .build();

        CalculateNetAmountResponse result = taxService.calculateNetAmount(request);

        return PayableAmountCalculation.builder()
            .grossAmount(result.getGrossAmount())
            .discountAmount(result.getDiscountAmount())
            .taxAmount(result.getTaxAmount())
            .withholdingTax(result.getWithholdingTax())
            .netAmount(result.getNetAmount())
            .formula(result.getFormula())
            .taxBreakdown(result.getTaxBreakdown())
            .build();
    }

    /**
     * Calculate payment total with all applicable charges.
     * This should be called when executing a payment.
     * 
     * @param payableId The payable being paid
     * @param paymentAmount Amount being paid
     * @param paymentMethod Payment method (BANK_TRANSFER, SWIFT, etc.)
     * @param priority Payment priority (NORMAL, HIGH, URGENT)
     * @param isCrossBorder Is this a cross-border payment?
     * @param isVipCustomer Is this a VIP customer?
     * @return Complete payment calculation with fees
     */
    @Transactional
    public PaymentTotalCalculation calculatePaymentTotal(
            UUID payableId,
            BigDecimal paymentAmount,
            Payable.PaymentMethod paymentMethod,
            Payable.PaymentPriority priority,
            boolean isCrossBorder,
            boolean isVipCustomer) {

        CalculatePaymentTotalRequest request = CalculatePaymentTotalRequest.builder()
            .payableId(payableId)
            .paymentAmount(paymentAmount)
            .paymentMethod(paymentMethod)
            .priority(priority)
            .isCrossBorder(isCrossBorder)
            .isVipCustomer(isVipCustomer)
            .build();

        CalculatePaymentTotalResponse result = chargeService.calculatePaymentTotal(request);

        return PaymentTotalCalculation.builder()
            .payableId(payableId)
            .paymentAmount(result.getPaymentAmount())
            .processingFee(result.getProcessingFee())
            .swiftFee(result.getSwiftFee())
            .fxFee(result.getFxFee())
            .urgencyFee(result.getUrgencyFee())
            .totalCharges(result.getTotalCharges())
            .totalPayable(result.getTotalPayable())
            .chargeBreakdown(result.getChargeBreakdown())
            .build();
    }

    // ========================================================================
    // POBO PAYMENT PROCESSING
    // ========================================================================

    /**
     * Validate POBO payment.
     * Call this before executing a POBO payment.
     */
    @Transactional(readOnly = true)
    public PoboValidationResult validatePoboPayment(
            UUID payerEntityId,
            String payerEntityCode,
            UUID behalfEntityId,
            String behalfEntityCode,
            BigDecimal amount,
            String paymentType,
            UUID vendorId) {

        PoboDto.ValidatePoboRequest request = PoboDto.ValidatePoboRequest.builder()
            .payerEntityId(payerEntityId)
            .payerEntityCode(payerEntityCode)
            .behalfEntityId(behalfEntityId)
            .behalfEntityCode(behalfEntityCode)
            .amount(amount)
            .paymentType(paymentType)
            .vendorId(vendorId)
            .currencyCode("AED")
            .build();

        PoboDto.ValidatePoboResponse validation = poboService.validatePoboPayment(request);

        return PoboValidationResult.builder()
            .isAuthorized(validation.getIsAuthorized())
            .authorizationCode(validation.getAuthorizationCode())
            .withinLimits(validation.getWithinLimits())
            .maxAvailable(validation.getMaxAvailable())
            .remainingDailyLimit(validation.getRemainingDailyLimit())
            .remainingMonthlyLimit(validation.getRemainingMonthlyLimit())
            .requiresApproval(validation.getRequiresApproval())
            .autoRechargeEnabled(validation.getAutoRechargeEnabled())
            .serviceFeeRate(validation.getServiceFeeRate())
            .rejectionReason(validation.getRejectionReason())
            .warnings(validation.getWarnings())
            .build();
    }

    /**
     * Process POBO payment with automatic recharge creation.
     * This is the complete POBO payment flow.
     */
    @Transactional
    public PoboPaymentResult processPoboPayment(
            UUID payerEntityId,
            String payerEntityCode,
            String payerEntityName,
            UUID behalfEntityId,
            String behalfEntityCode,
            String behalfEntityName,
            UUID payableId,
            BigDecimal amount,
            String currencyCode,
            boolean createRecharge,
            boolean createIhbLoan) {

        PoboDto.PoboPaymentRequest request = PoboDto.PoboPaymentRequest.builder()
            .payerEntityId(payerEntityId)
            .payerEntityCode(payerEntityCode)
            .behalfEntityId(behalfEntityId)
            .behalfEntityCode(behalfEntityCode)
            .payableId(payableId)
            .amount(amount)
            .currencyCode(currencyCode != null ? currencyCode : "AED")
            .createRecharge(createRecharge)
            .createIhbLoan(createIhbLoan)
            .build();

        PoboDto.PoboPaymentResponse result = poboService.processPoboPayment(request);

        return PoboPaymentResult.builder()
            .paymentExecutionId(result.getPaymentExecutionId())
            .paymentReference(result.getPaymentReference())
            .status(result.getStatus())
            .paidAmount(result.getPaidAmount())
            .rechargeId(result.getRechargeId())
            .rechargeReference(result.getRechargeReference())
            .rechargeAmount(result.getRechargeAmount())
            .serviceFee(result.getServiceFee())
            .totalRecharge(result.getTotalRecharge())
            .ihbLoanId(result.getIhbLoanId())
            .processedAt(result.getProcessedAt())
            .build();
    }

    // ========================================================================
    // CONVENIENCE METHODS
    // ========================================================================

    /**
     * Quick calculation of net amount with UAE VAT.
     */
    public BigDecimal calculateNetWithUaeVat(BigDecimal grossAmount, BigDecimal discountAmount) {
        BigDecimal base = grossAmount.subtract(discountAmount != null ? discountAmount : BigDecimal.ZERO);
        BigDecimal vat = taxService.calculateUaeVat(base);
        return base.add(vat);
    }

    /**
     * Calculate POBO service fee.
     */
    public BigDecimal calculatePoboServiceFee(BigDecimal amount) {
        return chargeService.calculatePoboFee(amount);
    }

    /**
     * Get total charges for a payable.
     */
    public BigDecimal getTotalCharges(UUID payableId) {
        return chargeService.getTotalCharges("PAYABLE", payableId);
    }

    // ========================================================================
    // RESULT CLASSES
    // ========================================================================

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class PayableAmountCalculation {
        private BigDecimal grossAmount;
        private BigDecimal discountAmount;
        private BigDecimal taxAmount;
        private BigDecimal withholdingTax;
        private BigDecimal netAmount;
        private String formula;
        private java.util.List<TaxLineItem> taxBreakdown;
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class PaymentTotalCalculation {
        private UUID payableId;
        private BigDecimal paymentAmount;
        private BigDecimal processingFee;
        private BigDecimal swiftFee;
        private BigDecimal fxFee;
        private BigDecimal urgencyFee;
        private BigDecimal totalCharges;
        private BigDecimal totalPayable;
        private java.util.List<ChargeLineItem> chargeBreakdown;
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class PoboValidationResult {
        private Boolean isAuthorized;
        private String authorizationCode;
        private Boolean withinLimits;
        private BigDecimal maxAvailable;
        private BigDecimal remainingDailyLimit;
        private BigDecimal remainingMonthlyLimit;
        private Boolean requiresApproval;
        private Boolean autoRechargeEnabled;
        private BigDecimal serviceFeeRate;
        private String rejectionReason;
        private java.util.List<String> warnings;
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class PoboPaymentResult {
        private UUID paymentExecutionId;
        private String paymentReference;
        private String status;
        private BigDecimal paidAmount;
        private UUID rechargeId;
        private String rechargeReference;
        private BigDecimal rechargeAmount;
        private BigDecimal serviceFee;
        private BigDecimal totalRecharge;
        private UUID ihbLoanId;
        private java.time.LocalDateTime processedAt;
    }
}