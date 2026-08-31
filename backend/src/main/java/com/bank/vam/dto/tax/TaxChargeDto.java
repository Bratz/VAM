package com.bank.vam.dto.tax;

import com.bank.vam.entity.payables.Payable;
import com.bank.vam.entity.tax.ChargeConfiguration;
import com.bank.vam.entity.tax.TaxConfiguration;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * DTOs for Tax and Charge operations.
 */
public class TaxChargeDto {

    // ========================================================================
    // TAX JURISDICTION DTOs
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class JurisdictionResponse {
        private UUID id;
        private String jurisdictionCode;
        private String jurisdictionName;
        private String countryCode;
        private String regionCode;
        private Boolean supportsVat;
        private Boolean supportsGst;
        private Boolean supportsWithholding;
        private Boolean supportsSalesTax;
        private String taxAuthorityName;
        private String reportingCurrency;
        private String status;
        private LocalDate effectiveFrom;
        private LocalDate effectiveTo;
    }

    // ========================================================================
    // TAX CONFIGURATION DTOs
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TaxConfigResponse {
        private UUID id;
        private String taxCode;
        private String taxName;
        private String description;
        private TaxConfiguration.TaxType taxType;
        private String jurisdictionCode;
        private TaxConfiguration.TaxCategory taxCategory;
        private BigDecimal ratePercentage;
        private BigDecimal minimumAmount;
        private BigDecimal maximumAmount;
        private Boolean appliesToPayables;
        private Boolean appliesToReceivables;
        private Boolean isWithholding;
        private Boolean isRecoverable;
        private BigDecimal recoveryPercentage;
        private String status;
        private LocalDate effectiveFrom;
        private LocalDate effectiveTo;
        private LocalDateTime createdAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateTaxConfigRequest {
        private String taxCode;
        private String taxName;
        private String description;
        private TaxConfiguration.TaxType taxType;
        private String jurisdictionCode;
        private TaxConfiguration.TaxCategory taxCategory;
        private BigDecimal ratePercentage;
        private BigDecimal minimumAmount;
        private BigDecimal maximumAmount;
        private Boolean appliesToPayables;
        private Boolean appliesToReceivables;
        private Boolean isWithholding;
        private Boolean isRecoverable;
        private LocalDate effectiveFrom;
        private LocalDate effectiveTo;
    }

    // ========================================================================
    // CHARGE CONFIGURATION DTOs
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChargeConfigResponse {
        private UUID id;
        private String chargeCode;
        private String chargeName;
        private String description;
        private ChargeConfiguration.ChargeType chargeType;
        private ChargeConfiguration.ChargeCategory chargeCategory;
        private BigDecimal fixedAmount;
        private BigDecimal percentageRate;
        private String currencyCode;
        private BigDecimal minimumCharge;
        private BigDecimal maximumCharge;
        private List<Map<String, Object>> tierConfig;
        private String appliesToPaymentMethod;
        private String appliesToPriority;
        private Boolean isCrossBorder;
        private Boolean isDomestic;
        private BigDecimal waiverThreshold;
        private Boolean waiverForVip;
        private String status;
        private LocalDate effectiveFrom;
        private LocalDate effectiveTo;
        private LocalDateTime createdAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateChargeConfigRequest {
        private String chargeCode;
        private String chargeName;
        private String description;
        private ChargeConfiguration.ChargeType chargeType;
        private ChargeConfiguration.ChargeCategory chargeCategory;
        private BigDecimal fixedAmount;
        private BigDecimal percentageRate;
        private String currencyCode;
        private BigDecimal minimumCharge;
        private BigDecimal maximumCharge;
        private List<Map<String, Object>> tierConfig;
        private Payable.PaymentMethod appliesToPaymentMethod;
        private Payable.PaymentPriority appliesToPriority;
        private Boolean isCrossBorder;
        private Boolean isDomestic;
        private LocalDate effectiveFrom;
        private LocalDate effectiveTo;
    }

    // ========================================================================
    // TAX CALCULATION DTOs
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CalculateTaxRequest {
        private UUID referenceId;
        private String referenceType; // PAYABLE, RECEIVABLE, INVOICE
        private BigDecimal baseAmount;
        private String taxCode;
        private String jurisdictionCode;
        private Boolean isService; // For service vs goods
        private String currencyCode;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CalculateTaxResponse {
        private UUID calculationId;
        private String taxCode;
        private String taxName;
        private TaxConfiguration.TaxType taxType;
        private BigDecimal baseAmount;
        private BigDecimal taxRate;
        private BigDecimal calculatedTax;
        private BigDecimal recoverableTax;
        private Boolean isWithholding;
        private String currencyCode;
        private LocalDateTime calculatedAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TaxBreakdownResponse {
        private UUID referenceId;
        private String referenceType;
        private BigDecimal grossAmount;
        private List<TaxLineItem> taxes;
        private BigDecimal totalTax;
        private BigDecimal totalWithholding;
        private BigDecimal netAmount;
        private String currencyCode;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TaxLineItem {
        private String taxCode;
        private String taxName;
        private TaxConfiguration.TaxType taxType;
        private BigDecimal rate;
        private BigDecimal taxAmount;
        private Boolean isWithholding;
        private Boolean isRecoverable;
    }

    // ========================================================================
    // CHARGE CALCULATION DTOs
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CalculateChargesRequest {
        private UUID referenceId;
        private String referenceType; // PAYABLE, PAYMENT_EXECUTION
        private BigDecimal baseAmount;
        private Payable.PaymentMethod paymentMethod;
        private Payable.PaymentPriority priority;
        private Boolean isCrossBorder;
        private Boolean isVipCustomer;
        private Boolean isBulkPayment;
        private String currencyCode;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CalculateChargesResponse {
        private UUID referenceId;
        private BigDecimal baseAmount;
        private List<ChargeLineItem> charges;
        private BigDecimal totalCharges;
        private BigDecimal totalWaived;
        private BigDecimal netCharges;
        private String currencyCode;
        private LocalDateTime calculatedAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChargeLineItem {
        private UUID calculationId;
        private String chargeCode;
        private String chargeName;
        private ChargeConfiguration.ChargeType chargeType;
        private BigDecimal calculatedCharge;
        private BigDecimal waivedAmount;
        private BigDecimal finalCharge;
        private String waiverReason;
    }

    // ========================================================================
    // NET AMOUNT CALCULATION DTOs
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CalculateNetAmountRequest {
        private BigDecimal grossAmount;
        private BigDecimal discountAmount;
        private String taxCode;
        private String withholdingTaxCode;
        private String jurisdictionCode;
        private String currencyCode;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CalculateNetAmountResponse {
        private BigDecimal grossAmount;
        private BigDecimal discountAmount;
        private BigDecimal taxAmount;
        private BigDecimal withholdingTax;
        private BigDecimal netAmount;
        private String formula;
        private String currencyCode;
        private List<TaxLineItem> taxBreakdown;
    }

    // ========================================================================
    // PAYMENT TOTAL CALCULATION DTOs
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CalculatePaymentTotalRequest {
        private UUID payableId;
        private BigDecimal paymentAmount;
        private Payable.PaymentMethod paymentMethod;
        private Payable.PaymentPriority priority;
        private Boolean isCrossBorder;
        private String destinationCountry;
        private Boolean isVipCustomer;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CalculatePaymentTotalResponse {
        private UUID payableId;
        private BigDecimal paymentAmount;
        private BigDecimal processingFee;
        private BigDecimal swiftFee;
        private BigDecimal fxFee;
        private BigDecimal urgencyFee;
        private BigDecimal totalCharges;
        private BigDecimal totalPayable;
        private List<ChargeLineItem> chargeBreakdown;
        private String currencyCode;
    }

    // ========================================================================
    // STATISTICS DTOs
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TaxChargeStatsResponse {
        private int activeTaxConfigs;
        private int activeChargeConfigs;
        private int jurisdictionCount;
        private BigDecimal totalTaxCalculated;
        private BigDecimal totalChargesCalculated;
        private BigDecimal totalWaivedCharges;
        private Map<String, BigDecimal> taxByType;
        private Map<String, BigDecimal> chargesByType;
        private LocalDateTime asOfDate;
    }
}