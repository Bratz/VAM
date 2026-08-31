package com.bank.vam.dto;

import lombok.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Program Type Configuration DTO.
 * 
 * Provides field-level configuration for each program type,
 * enabling dynamic form generation in the frontend.
 * 
 * Used by:
 * - Frontend to show/hide fields based on program type
 * - Backend to validate requests based on program type
 * - API documentation generation
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProgramTypeConfigDto {

    // ========================================================================
    // IDENTIFICATION
    // ========================================================================
    
    /**
     * Program type code.
     * Values: COLLECTION, VIBAN, ESCROW, WALLET, IHB, PAYABLES, 
     *         RECEIVABLES, LOYALTY, GIFT_CARD, CORPORATE_CARD, MOBILE_MONEY
     */
    private String programType;
    
    /**
     * Display name for UI.
     */
    private String displayName;
    
    /**
     * Description of the program type.
     */
    private String description;
    
    /**
     * Icon name for UI (e.g., "wallet", "credit-card", "building2").
     */
    private String icon;
    
    /**
     * Color scheme for UI (e.g., "primary", "success", "warning").
     */
    private String colorScheme;

    // ========================================================================
    // FEATURE FLAGS
    // ========================================================================
    
    /**
     * Whether hierarchy structure is required/supported.
     */
    private boolean hierarchyRequired;
    private boolean hierarchySupported;
    
    /**
     * Whether spending/topup limits are required/supported.
     */
    private boolean limitsRequired;
    private boolean limitsSupported;
    
    /**
     * Whether KYC verification is required/supported.
     */
    private boolean kycRequired;
    private boolean kycSupported;
    
    /**
     * Whether MCC restrictions are supported.
     */
    private boolean mccRestrictionsSupported;
    
    /**
     * Whether expiry configuration is supported.
     */
    private boolean expirySupported;
    
    /**
     * Whether interest calculation is supported (IHB).
     */
    private boolean interestSupported;
    
    /**
     * Whether points/loyalty is supported.
     */
    private boolean pointsSupported;
    
    /**
     * Whether card program features are supported.
     */
    private boolean cardProgramSupported;
    
    /**
     * Whether VIBAN assignment is supported.
     */
    private boolean vibanSupported;
    
    /**
     * Whether wallet operations (topup/withdraw) are supported.
     */
    private boolean walletOperationsSupported;

    // ========================================================================
    // FIELD CLASSIFICATION
    // ========================================================================
    
    /**
     * Fields that are required for this program type.
     * These must be provided when creating a VA.
     */
    private List<String> requiredFields;
    
    /**
     * Fields that are optional but recommended.
     */
    private List<String> optionalFields;
    
    /**
     * Fields that should be hidden (not applicable).
     */
    private List<String> hiddenFields;
    
    /**
     * Fields that are read-only (set by system).
     */
    private List<String> readOnlyFields;

    // ========================================================================
    // DEFAULT VALUES
    // ========================================================================
    
    /**
     * Default values for fields when creating a VA of this type.
     */
    private Map<String, Object> defaultValues;
    
    /**
     * Default limit configuration.
     */
    private DefaultLimits defaultLimits;
    
    /**
     * Default KYC configuration.
     */
    private DefaultKyc defaultKyc;

    // ========================================================================
    // VALIDATION RULES
    // ========================================================================
    
    /**
     * Validation rules per field.
     */
    private Map<String, ValidationRule> validationRules;
    
    /**
     * Cross-field validation rules.
     */
    private List<CrossFieldValidation> crossFieldValidations;

    // ========================================================================
    // UI CONFIGURATION
    // ========================================================================
    
    /**
     * Tab configuration for create/edit modal.
     */
    private List<TabConfig> tabs;
    
    /**
     * Field groupings within tabs.
     */
    private Map<String, List<String>> fieldGroups;

    // ========================================================================
    // COLLECTION CHANNELS (for COLLECTION/VIBAN types)
    // ========================================================================
    
    /**
     * Supported collection channels.
     */
    private List<EnumOption> collectionChannels;

    // ========================================================================
    // WALLET TYPES (for WALLET/MOBILE_MONEY types)
    // ========================================================================
    
    /**
     * Supported wallet types.
     */
    private List<EnumOption> walletTypes;

    // ========================================================================
    // CARD PROGRAM TYPES (for CORPORATE_CARD type)
    // ========================================================================
    
    /**
     * Supported card program types.
     */
    private List<EnumOption> cardProgramTypes;

    // ========================================================================
    // NESTED CLASSES
    // ========================================================================
    
    /**
     * Validation rule for a field.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ValidationRule {
        private String field;
        
        /**
         * Rule type: required, min, max, minLength, maxLength, pattern, enum, custom
         */
        private String type;
        
        /**
         * Rule value (interpretation depends on type).
         */
        private Object value;
        
        /**
         * Error message to display.
         */
        private String message;
        
        /**
         * Condition when this rule applies (SpEL expression).
         */
        private String condition;
    }

    /**
     * Cross-field validation rule.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CrossFieldValidation {
        /**
         * Fields involved in validation.
         */
        private List<String> fields;
        
        /**
         * Validation expression (SpEL).
         */
        private String expression;
        
        /**
         * Error message.
         */
        private String message;
    }

    /**
     * Default limit configuration.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DefaultLimits {
        private BigDecimal perTransactionLimit;
        private BigDecimal dailyLimit;
        private BigDecimal weeklyLimit;
        private BigDecimal monthlyLimit;
        private BigDecimal annualLimit;
        private BigDecimal maxBalance;
        private BigDecimal dailyTopupLimit;
        private BigDecimal monthlyTopupLimit;
    }

    /**
     * Default KYC configuration.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DefaultKyc {
        private boolean required;
        private Integer minLevel;
        private Integer validityDays;
        private boolean autoUpgrade;
    }

    /**
     * Tab configuration for UI.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TabConfig {
        private String id;
        private String label;
        private String icon;
        private boolean visible;
        private boolean disabled;
        private int order;
        private List<String> fields;
    }

    /**
     * Enum option for dropdowns.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EnumOption {
        private String value;
        private String label;
        private String description;
        private String icon;
        private boolean isDefault;
    }

    // ========================================================================
    // FACTORY METHODS
    // ========================================================================
    
    /**
     * Get configuration for COLLECTION program type.
     */
    public static ProgramTypeConfigDto forCollection() {
        return ProgramTypeConfigDto.builder()
            .programType("COLLECTION")
            .displayName("Collection Program")
            .description("Receivables collection with channel segmentation")
            .icon("credit-card")
            .colorScheme("info")
            .hierarchyRequired(true)
            .hierarchySupported(true)
            .limitsRequired(false)
            .limitsSupported(false)
            .kycRequired(false)
            .kycSupported(false)
            .mccRestrictionsSupported(false)
            .expirySupported(false)
            .interestSupported(false)
            .pointsSupported(false)
            .cardProgramSupported(false)
            .vibanSupported(true)
            .walletOperationsSupported(false)
            .requiredFields(List.of("vaName", "corporateId", "physicalAccountId", "currencyCode"))
            .optionalFields(List.of("hierarchyNodeId", "collectionChannel", "externalReference", "viban"))
            .hiddenFields(List.of("walletType", "kycLevel", "cardProgramType", "mccWhitelist", 
                "pointsToCurrencyRate", "loyaltyTier", "balanceExpiryDate"))
            .collectionChannels(List.of(
                EnumOption.builder().value("INVOICE").label("Invoice").isDefault(true).build(),
                EnumOption.builder().value("ECOMMERCE").label("E-commerce").build(),
                EnumOption.builder().value("POS").label("Point of Sale").build(),
                EnumOption.builder().value("DIRECT").label("Direct Transfer").build(),
                EnumOption.builder().value("SUBSCRIPTION").label("Subscription").build(),
                EnumOption.builder().value("QR_CODE").label("QR Code").build()
            ))
            .tabs(List.of(
                TabConfig.builder().id("basic").label("Basic Info").visible(true).order(1).build(),
                TabConfig.builder().id("hierarchy").label("Hierarchy").visible(true).order(2).build(),
                TabConfig.builder().id("advanced").label("Advanced").visible(true).order(3).build()
            ))
            .build();
    }

    /**
     * Get configuration for WALLET program type.
     */
    public static ProgramTypeConfigDto forWallet() {
        return ProgramTypeConfigDto.builder()
            .programType("WALLET")
            .displayName("Wallet Program")
            .description("Prepaid/stored value wallet")
            .icon("wallet")
            .colorScheme("warning")
            .hierarchyRequired(false)
            .hierarchySupported(true)
            .limitsRequired(true)
            .limitsSupported(true)
            .kycRequired(true)
            .kycSupported(true)
            .mccRestrictionsSupported(true)
            .expirySupported(true)
            .interestSupported(false)
            .pointsSupported(true)
            .cardProgramSupported(false)
            .vibanSupported(true)
            .walletOperationsSupported(true)
            .requiredFields(List.of("vaName", "corporateId", "physicalAccountId", "currencyCode", "walletType"))
            .optionalFields(List.of("kycLevel", "expiresAt", "holderPartyId", 
                "perTransactionLimit", "dailyLimit", "monthlyLimit", "maxBalance",
                "dailyTopupLimit", "monthlyTopupLimit", "mccBlacklist"))
            .hiddenFields(List.of("hierarchyNodeId", "collectionChannel", "cardProgramType", 
                "budgetOwnerId", "costCenter", "department", "creditLimit"))
            .defaultLimits(DefaultLimits.builder()
                .perTransactionLimit(new BigDecimal("5000"))
                .dailyLimit(new BigDecimal("10000"))
                .monthlyLimit(new BigDecimal("50000"))
                .maxBalance(new BigDecimal("100000"))
                .dailyTopupLimit(new BigDecimal("20000"))
                .monthlyTopupLimit(new BigDecimal("100000"))
                .build())
            .defaultKyc(DefaultKyc.builder()
                .required(true)
                .minLevel(1)
                .validityDays(365)
                .build())
            .walletTypes(List.of(
                EnumOption.builder().value("CONSUMER").label("Consumer Wallet").isDefault(true).build(),
                EnumOption.builder().value("EMPLOYEE").label("Employee Wallet").build(),
                EnumOption.builder().value("MERCHANT").label("Merchant Wallet").build(),
                EnumOption.builder().value("AGENT").label("Agent Wallet").build(),
                EnumOption.builder().value("CORPORATE").label("Corporate Wallet").build(),
                EnumOption.builder().value("GIFT").label("Gift Wallet").build()
            ))
            .tabs(List.of(
                TabConfig.builder().id("basic").label("Basic Info").visible(true).order(1).build(),
                TabConfig.builder().id("limits").label("Limits").visible(true).order(2).build(),
                TabConfig.builder().id("wallet").label("Wallet Config").visible(true).order(3).build(),
                TabConfig.builder().id("mcc").label("MCC Restrictions").visible(true).order(4).build(),
                TabConfig.builder().id("advanced").label("Advanced").visible(true).order(5).build()
            ))
            .build();
    }

    /**
     * Get configuration for IHB program type.
     */
    public static ProgramTypeConfigDto forIhb() {
        return ProgramTypeConfigDto.builder()
            .programType("IHB")
            .displayName("In-House Bank")
            .description("Intercompany treasury management")
            .icon("building2")
            .colorScheme("primary")
            .hierarchyRequired(true)
            .hierarchySupported(true)
            .limitsRequired(false)
            .limitsSupported(true)
            .kycRequired(false)
            .kycSupported(false)
            .mccRestrictionsSupported(false)
            .expirySupported(false)
            .interestSupported(true)
            .pointsSupported(false)
            .cardProgramSupported(false)
            .vibanSupported(true)
            .walletOperationsSupported(true)
            .requiredFields(List.of("vaName", "corporateId", "physicalAccountId", "currencyCode", "hierarchyNodeId"))
            .optionalFields(List.of("creditLimit", "costCenter", "department", "externalReference"))
            .hiddenFields(List.of("walletType", "kycLevel", "cardProgramType", "mccWhitelist",
                "pointsToCurrencyRate", "loyaltyTier", "balanceExpiryDate", "dailyLimit", "maxBalance"))
            .tabs(List.of(
                TabConfig.builder().id("basic").label("Basic Info").visible(true).order(1).build(),
                TabConfig.builder().id("hierarchy").label("Hierarchy").visible(true).order(2).build(),
                TabConfig.builder().id("ihb").label("IHB Config").visible(true).order(3).build(),
                TabConfig.builder().id("advanced").label("Advanced").visible(true).order(4).build()
            ))
            .build();
    }

    /**
     * Get configuration for CORPORATE_CARD program type.
     */
    public static ProgramTypeConfigDto forCorporateCard() {
        return ProgramTypeConfigDto.builder()
            .programType("CORPORATE_CARD")
            .displayName("Corporate Card")
            .description("Expense management and budget control")
            .icon("credit-card")
            .colorScheme("accent")
            .hierarchyRequired(false)
            .hierarchySupported(true)
            .limitsRequired(true)
            .limitsSupported(true)
            .kycRequired(false)
            .kycSupported(true)
            .mccRestrictionsSupported(true)
            .expirySupported(true)
            .interestSupported(false)
            .pointsSupported(true)
            .cardProgramSupported(true)
            .vibanSupported(false)
            .walletOperationsSupported(false)
            .requiredFields(List.of("vaName", "corporateId", "physicalAccountId", "currencyCode", 
                "cardProgramType", "budgetOwnerId"))
            .optionalFields(List.of("linkedCardId", "costCenter", "department",
                "perTransactionLimit", "dailyLimit", "weeklyLimit", "monthlyLimit", "annualLimit",
                "mccWhitelist", "mccBlacklist", "countryWhitelist"))
            .hiddenFields(List.of("walletType", "collectionChannel", "pointsToCurrencyRate", 
                "loyaltyTier", "creditLimit", "dailyTopupLimit"))
            .defaultLimits(DefaultLimits.builder()
                .perTransactionLimit(new BigDecimal("10000"))
                .dailyLimit(new BigDecimal("25000"))
                .weeklyLimit(new BigDecimal("75000"))
                .monthlyLimit(new BigDecimal("100000"))
                .annualLimit(new BigDecimal("500000"))
                .build())
            .cardProgramTypes(List.of(
                EnumOption.builder().value("TRAVEL").label("Travel & Entertainment").build(),
                EnumOption.builder().value("PROCUREMENT").label("Procurement").build(),
                EnumOption.builder().value("FLEET").label("Fleet/Fuel").build(),
                EnumOption.builder().value("VIRTUAL").label("Virtual Card").build(),
                EnumOption.builder().value("EXPENSE").label("Employee Expense").isDefault(true).build(),
                EnumOption.builder().value("PETTY_CASH").label("Petty Cash").build()
            ))
            .tabs(List.of(
                TabConfig.builder().id("basic").label("Basic Info").visible(true).order(1).build(),
                TabConfig.builder().id("limits").label("Limits").visible(true).order(2).build(),
                TabConfig.builder().id("card").label("Card Config").visible(true).order(3).build(),
                TabConfig.builder().id("mcc").label("MCC Restrictions").visible(true).order(4).build(),
                TabConfig.builder().id("advanced").label("Advanced").visible(true).order(5).build()
            ))
            .build();
    }

    /**
     * Get configuration for ESCROW program type.
     */
    public static ProgramTypeConfigDto forEscrow() {
        return ProgramTypeConfigDto.builder()
            .programType("ESCROW")
            .displayName("Escrow Program")
            .description("Digital escrow and milestone-based payments")
            .icon("shield")
            .colorScheme("success")
            .hierarchyRequired(false)
            .hierarchySupported(true)
            .limitsRequired(false)
            .limitsSupported(true)
            .kycRequired(true)
            .kycSupported(true)
            .mccRestrictionsSupported(false)
            .expirySupported(true)
            .interestSupported(true)
            .pointsSupported(false)
            .cardProgramSupported(false)
            .vibanSupported(true)
            .walletOperationsSupported(true)
            .requiredFields(List.of("vaName", "corporateId", "physicalAccountId", "currencyCode"))
            .optionalFields(List.of("maxBalance", "kycLevel", "externalReference", "balanceExpiryDate"))
            .hiddenFields(List.of("walletType", "cardProgramType", "mccWhitelist", "loyaltyTier",
                "dailyLimit", "weeklyLimit"))
            .defaultKyc(DefaultKyc.builder()
                .required(true)
                .minLevel(2)
                .build())
            .tabs(List.of(
                TabConfig.builder().id("basic").label("Basic Info").visible(true).order(1).build(),
                TabConfig.builder().id("escrow").label("Escrow Config").visible(true).order(2).build(),
                TabConfig.builder().id("advanced").label("Advanced").visible(true).order(3).build()
            ))
            .build();
    }

    /**
     * Get configuration for LOYALTY program type.
     */
    public static ProgramTypeConfigDto forLoyalty() {
        return ProgramTypeConfigDto.builder()
            .programType("LOYALTY")
            .displayName("Loyalty Program")
            .description("Points and miles management")
            .icon("star")
            .colorScheme("warning")
            .hierarchyRequired(false)
            .hierarchySupported(false)
            .limitsRequired(false)
            .limitsSupported(true)
            .kycRequired(false)
            .kycSupported(true)
            .mccRestrictionsSupported(false)
            .expirySupported(true)
            .interestSupported(false)
            .pointsSupported(true)
            .cardProgramSupported(false)
            .vibanSupported(false)
            .walletOperationsSupported(false)
            .requiredFields(List.of("vaName", "corporateId", "physicalAccountId", "currencyCode",
                "valueType", "pointsToCurrencyRate"))
            .optionalFields(List.of("loyaltyTier", "loyaltyProgramId", "balanceExpiryDate", "expiryAction"))
            .hiddenFields(List.of("walletType", "cardProgramType", "mccWhitelist", "hierarchyNodeId",
                "collectionChannel", "dailyLimit", "maxBalance", "creditLimit"))
            .defaultValues(Map.of(
                "valueType", "POINTS",
                "expiryAction", "FORFEIT"
            ))
            .tabs(List.of(
                TabConfig.builder().id("basic").label("Basic Info").visible(true).order(1).build(),
                TabConfig.builder().id("loyalty").label("Loyalty Config").visible(true).order(2).build(),
                TabConfig.builder().id("advanced").label("Advanced").visible(true).order(3).build()
            ))
            .build();
    }

    /**
     * Get configuration for PAYABLES program type.
     */
    public static ProgramTypeConfigDto forPayables() {
        return ProgramTypeConfigDto.builder()
            .programType("PAYABLES")
            .displayName("Payables Program")
            .description("Payment-on-Behalf (POBO) management")
            .icon("banknote")
            .colorScheme("danger")
            .hierarchyRequired(true)
            .hierarchySupported(true)
            .limitsRequired(true)
            .limitsSupported(true)
            .kycRequired(false)
            .kycSupported(false)
            .mccRestrictionsSupported(true)
            .expirySupported(false)
            .interestSupported(false)
            .pointsSupported(false)
            .cardProgramSupported(false)
            .vibanSupported(true)
            .walletOperationsSupported(true)
            .requiredFields(List.of("vaName", "corporateId", "physicalAccountId", "currencyCode", "hierarchyNodeId"))
            .optionalFields(List.of("costCenter", "department", "budgetOwnerId",
                "perTransactionLimit", "dailyLimit", "monthlyLimit", "countryWhitelist"))
            .hiddenFields(List.of("walletType", "kycLevel", "cardProgramType", "loyaltyTier",
                "pointsToCurrencyRate", "balanceExpiryDate"))
            .tabs(List.of(
                TabConfig.builder().id("basic").label("Basic Info").visible(true).order(1).build(),
                TabConfig.builder().id("hierarchy").label("Hierarchy").visible(true).order(2).build(),
                TabConfig.builder().id("limits").label("Limits").visible(true).order(3).build(),
                TabConfig.builder().id("advanced").label("Advanced").visible(true).order(4).build()
            ))
            .build();
    }

    /**
     * Get configuration for MOBILE_MONEY program type.
     */
    public static ProgramTypeConfigDto forMobileMoney() {
        return ProgramTypeConfigDto.builder()
            .programType("MOBILE_MONEY")
            .displayName("Mobile Money")
            .description("Agent banking and mobile money")
            .icon("smartphone")
            .colorScheme("success")
            .hierarchyRequired(false)
            .hierarchySupported(true)
            .limitsRequired(true)
            .limitsSupported(true)
            .kycRequired(true)
            .kycSupported(true)
            .mccRestrictionsSupported(true)
            .expirySupported(true)
            .interestSupported(false)
            .pointsSupported(false)
            .cardProgramSupported(false)
            .vibanSupported(true)
            .walletOperationsSupported(true)
            .requiredFields(List.of("vaName", "corporateId", "physicalAccountId", "currencyCode", "walletType"))
            .optionalFields(List.of("kycLevel", "holderPartyId",
                "perTransactionLimit", "dailyLimit", "monthlyLimit", "maxBalance",
                "dailyTopupLimit", "monthlyTopupLimit", "expiresAt"))
            .hiddenFields(List.of("cardProgramType", "budgetOwnerId", "costCenter", "department",
                "pointsToCurrencyRate", "loyaltyTier", "creditLimit"))
            .defaultLimits(DefaultLimits.builder()
                .perTransactionLimit(new BigDecimal("2000"))
                .dailyLimit(new BigDecimal("5000"))
                .monthlyLimit(new BigDecimal("50000"))
                .maxBalance(new BigDecimal("100000"))
                .dailyTopupLimit(new BigDecimal("50000"))
                .build())
            .defaultKyc(DefaultKyc.builder()
                .required(true)
                .minLevel(1)
                .validityDays(365)
                .build())
            .walletTypes(List.of(
                EnumOption.builder().value("AGENT").label("Agent Wallet").isDefault(true).build(),
                EnumOption.builder().value("CONSUMER").label("Consumer Wallet").build(),
                EnumOption.builder().value("MERCHANT").label("Merchant Wallet").build()
            ))
            .tabs(List.of(
                TabConfig.builder().id("basic").label("Basic Info").visible(true).order(1).build(),
                TabConfig.builder().id("limits").label("Limits").visible(true).order(2).build(),
                TabConfig.builder().id("wallet").label("Wallet Config").visible(true).order(3).build(),
                TabConfig.builder().id("advanced").label("Advanced").visible(true).order(4).build()
            ))
            .build();
    }

    /**
     * Get configuration for GIFT_CARD program type.
     */
    public static ProgramTypeConfigDto forGiftCard() {
        return ProgramTypeConfigDto.builder()
            .programType("GIFT_CARD")
            .displayName("Gift Card")
            .description("Gift and prepaid cards")
            .icon("gift")
            .colorScheme("accent")
            .hierarchyRequired(false)
            .hierarchySupported(false)
            .limitsRequired(true)
            .limitsSupported(true)
            .kycRequired(false)
            .kycSupported(false)
            .mccRestrictionsSupported(true)
            .expirySupported(true)
            .interestSupported(false)
            .pointsSupported(false)
            .cardProgramSupported(false)
            .vibanSupported(false)
            .walletOperationsSupported(false)
            .requiredFields(List.of("vaName", "corporateId", "physicalAccountId", "currencyCode", "maxBalance"))
            .optionalFields(List.of("balanceExpiryDate", "expiryAction", "mccWhitelist", "merchantWhitelist"))
            .hiddenFields(List.of("walletType", "kycLevel", "cardProgramType", "hierarchyNodeId",
                "collectionChannel", "dailyLimit", "creditLimit", "loyaltyTier"))
            .defaultValues(Map.of(
                "expiryAction", "FORFEIT"
            ))
            .defaultLimits(DefaultLimits.builder()
                .maxBalance(new BigDecimal("5000"))
                .build())
            .tabs(List.of(
                TabConfig.builder().id("basic").label("Basic Info").visible(true).order(1).build(),
                TabConfig.builder().id("gift").label("Gift Card Config").visible(true).order(2).build(),
                TabConfig.builder().id("mcc").label("Merchant Restrictions").visible(true).order(3).build()
            ))
            .build();
    }

    /**
     * Get configuration for VIBAN program type.
     */
    public static ProgramTypeConfigDto forViban() {
        return ProgramTypeConfigDto.builder()
            .programType("VIBAN")
            .displayName("VIBAN Program")
            .description("Virtual IBAN assignment and routing")
            .icon("hash")
            .colorScheme("accent")
            .hierarchyRequired(false)
            .hierarchySupported(true)
            .limitsRequired(false)
            .limitsSupported(false)
            .kycRequired(false)
            .kycSupported(true)
            .mccRestrictionsSupported(false)
            .expirySupported(false)
            .interestSupported(false)
            .pointsSupported(false)
            .cardProgramSupported(false)
            .vibanSupported(true)
            .walletOperationsSupported(false)
            .requiredFields(List.of("vaName", "corporateId", "physicalAccountId", "currencyCode"))
            .optionalFields(List.of("viban", "hierarchyNodeId", "collectionChannel", "externalReference"))
            .hiddenFields(List.of("walletType", "kycLevel", "cardProgramType", "mccWhitelist",
                "dailyLimit", "maxBalance", "loyaltyTier", "creditLimit"))
            .collectionChannels(List.of(
                EnumOption.builder().value("INVOICE").label("Invoice").isDefault(true).build(),
                EnumOption.builder().value("ECOMMERCE").label("E-commerce").build(),
                EnumOption.builder().value("DIRECT").label("Direct Transfer").build()
            ))
            .tabs(List.of(
                TabConfig.builder().id("basic").label("Basic Info").visible(true).order(1).build(),
                TabConfig.builder().id("viban").label("VIBAN Config").visible(true).order(2).build(),
                TabConfig.builder().id("advanced").label("Advanced").visible(true).order(3).build()
            ))
            .build();
    }

    /**
     * Get configuration for a program type by code.
     */
    public static ProgramTypeConfigDto forType(String programType) {
        return switch (programType.toUpperCase()) {
            case "COLLECTION" -> forCollection();
            case "WALLET" -> forWallet();
            case "IHB" -> forIhb();
            case "CORPORATE_CARD" -> forCorporateCard();
            case "ESCROW" -> forEscrow();
            case "LOYALTY" -> forLoyalty();
            case "PAYABLES" -> forPayables();
            case "MOBILE_MONEY" -> forMobileMoney();
            case "GIFT_CARD" -> forGiftCard();
            case "VIBAN" -> forViban();
            case "RECEIVABLES" -> forCollection(); // Similar to COLLECTION
            default -> forCollection(); // Default fallback
        };
    }

    /**
     * Get all program type configurations.
     */
    public static List<ProgramTypeConfigDto> getAllConfigs() {
        return List.of(
            forCollection(),
            forViban(),
            forEscrow(),
            forWallet(),
            forIhb(),
            forPayables(),
            forLoyalty(),
            forGiftCard(),
            forCorporateCard(),
            forMobileMoney()
        );
    }
}