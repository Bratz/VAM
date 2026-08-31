package com.bank.vam.dto.party;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Party DTOs - Enhanced with POBO/IC fields
 * 
 * Phase 1: Adds DTOs for Payment Factory capabilities:
 * - Entity context (owning entity)
 * - POBO eligibility and configuration
 * - Intercompany identification
 * - Netting eligibility
 */
public class PartyDto {

    // ========================================================================
    // RESPONSE DTOs
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PartyResponse {
        private UUID id;
        private UUID corporateId;  // Added for filtering parties by corporate
        private String partyCode;
        private String partyType;
        private String legalName;
        private String displayName;
        private String tradeName;
        private Set<String> roles;

        // Tax & Registration
        private String taxId;
        private String registrationNumber;
        private String registrationCountry;

        // Contact
        private String contactName;
        private String contactEmail;
        private String contactPhone;

        // Address
        private String addressLine1;
        private String addressLine2;
        private String city;
        private String state;
        private String postalCode;
        private String country;

        // E-commerce
        private Boolean ecommerceEnabled;
        private List<String> ecommercePlatforms;

        // Employee
        private String employeeId;
        private String department;

        // Compliance
        private String kycStatus;
        private LocalDate kycExpiresAt;
        private String riskRating;
        private Integer riskScore;
        private String sanctionsStatus;
        private Boolean pepStatus;
        private Boolean adverseMediaStatus;

        // Status
        private String status;

        // Stats
        private Integer bankAccountsCount;
        private Integer documentsCount;
        private LocalDateTime lastTransactionAt;

        // Metadata
        private LocalDateTime createdAt;
        private LocalDateTime onboardedAt;
        
        // ====================================================================
        // PHASE 1: POBO/IC FIELDS
        // ====================================================================
        
        // Entity Context
        private UUID owningEntityId;
        private String owningEntityCode;
        
        // POBO
        private Boolean poboEligible;
        private UUID poboDefaultPayerEntityId;
        private String poboDefaultPayerEntityCode;
        
        // Intercompany
        private Boolean isIntercompany;
        private UUID linkedLegalEntityId;
        private String linkedLegalEntityCode;
        
        // Netting
        private Boolean nettingEligible;
        private String icSettlementMethod;
        
        // IC Credit
        private BigDecimal icCreditLimit;
        private BigDecimal icCurrentExposure;
        private String icCurrency;
        
        // Computed fields
        private BigDecimal icAvailableCredit;
        private BigDecimal icCreditUtilizationPercent;
        private Boolean canReceivePoboPayment;
        private Boolean canParticipateInNetting;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PartyDetailResponse {
        private PartyResponse party;
        private List<BankAccountResponse> bankAccounts;
        private List<DocumentResponse> documents;
        private ComplianceDetailResponse compliance;
        private List<ActivityLogResponse> activityLog;
        
        // Phase 1: POBO/IC details
        private PoboConfigResponse poboConfig;
        private IntercompanyConfigResponse intercompanyConfig;
    }
    
    // ========================================================================
    // PHASE 1: POBO CONFIGURATION DTOs
    // ========================================================================
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PoboConfigResponse {
        private Boolean poboEligible;
        private UUID defaultPayerEntityId;
        private String defaultPayerEntityCode;
        private String defaultPayerEntityName;
        private LocalDateTime eligibleSince;
        private String approvedBy;
        private List<PoboPayerOptionResponse> availablePayerEntities;
    }
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PoboPayerOptionResponse {
        private UUID entityId;
        private String entityCode;
        private String entityName;
        private String entityType;
        private String currencyCode;
        private BigDecimal availableLiquidity;
    }
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PoboValidationResponse {
        private Boolean isValid;
        private String reason;
        private String partyStatus;
        private String kycStatus;
        private Boolean poboEligible;
        private BigDecimal availableCredit;
        private List<String> warnings;
    }
    
    // ========================================================================
    // PHASE 1: INTERCOMPANY CONFIGURATION DTOs
    // ========================================================================
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IntercompanyConfigResponse {
        private Boolean isIntercompany;
        private UUID linkedLegalEntityId;
        private String linkedLegalEntityCode;
        private String linkedLegalEntityName;
        private String settlementMethod;
        private Boolean nettingEligible;
        private IcCreditConfigResponse creditConfig;
        private IcPositionSummaryResponse positionSummary;
    }
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IcCreditConfigResponse {
        private BigDecimal creditLimit;
        private String currency;
        private BigDecimal currentExposure;
        private BigDecimal availableCredit;
        private BigDecimal utilizationPercent;
        private String creditStatus; // AVAILABLE, WARNING, EXCEEDED
    }
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IcPositionSummaryResponse {
        private BigDecimal totalPayables;
        private BigDecimal totalReceivables;
        private BigDecimal netPosition;
        private String netPositionDirection; // WE_OWE, THEY_OWE, BALANCED
        private Integer openPayablesCount;
        private Integer openReceivablesCount;
        private LocalDateTime lastSettlementDate;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BankAccountResponse {
        private UUID id;
        private String label;
        private String holderName;
        private String bankName;
        private String bankCode;
        private String iban;
        private String accountNumber;
        private String routingNumber;
        private String currency;
        private Boolean isPrimary;
        private Boolean isVerified;
        private LocalDateTime verifiedAt;
        private String status;
        
        // Phase 1: Payment method support
        private List<String> supportedPaymentMethods;
        private Boolean poboEnabled;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DocumentResponse {
        private UUID id;
        private String documentType;
        private String category;
        private String name;
        private String documentNumber;
        private LocalDate issueDate;
        private LocalDate expiryDate;
        private String issuingAuthority;
        private String verificationStatus;
        private String fileType;
        private Long fileSize;
        private Boolean isExpired;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ComplianceDetailResponse {
        private String kycStatus;
        private LocalDate kycExpiresAt;
        private LocalDateTime kycVerifiedAt;
        private String kycVerifiedBy;
        private Integer verificationProgress;

        private String riskRating;
        private Integer riskScore;
        private List<RiskFactorResponse> riskFactors;

        private String sanctionsStatus;
        private LocalDateTime sanctionsLastChecked;
        private Boolean pepStatus;
        private Boolean adverseMediaStatus;

        private List<RequiredDocumentResponse> requiredDocuments;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RiskFactorResponse {
        private String factor;
        private String rating;
        private String description;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RequiredDocumentResponse {
        private String documentType;
        private String name;
        private Boolean isProvided;
        private String status;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ActivityLogResponse {
        private String action;
        private String user;
        private LocalDateTime timestamp;
        private String type; // success, info, warning, error
        private String details;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PartyStatsResponse {
        private Long totalParties;
        private Long customers;
        private Long vendors;
        private Long employees;
        private Long government;
        private Long financial;
        private Long kycPending;
        private Long kycExpired;
        private Long highRisk;
        private Long sanctionsAlerts;
        
        // Phase 1: POBO/IC stats
        private Long poboEligibleVendors;
        private Long intercompanyParties;
        private Long nettingEligibleParties;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PartyListResponse {
        private List<PartyResponse> parties;
        private Long totalCount;
        private Integer page;
        private Integer pageSize;
        private PartyStatsResponse stats;
    }

    // ========================================================================
    // REQUEST DTOs
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreatePartyRequest {
        private String partyType;
        private String legalName;
        private String displayName;
        private String tradeName;
        private Set<String> roles;

        private String taxId;
        private String registrationNumber;
        private String registrationCountry;

        private String contactName;
        private String contactEmail;
        private String contactPhone;

        private String addressLine1;
        private String addressLine2;
        private String city;
        private String state;
        private String postalCode;
        private String country;

        private Boolean ecommerceEnabled;
        private List<String> ecommercePlatforms;

        private String employeeId;
        private String department;
        
        // Phase 1: Entity context
        private UUID owningEntityId;
        private String owningEntityCode;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdatePartyRequest {
        private String displayName;
        private String tradeName;
        private Set<String> roles;

        private String taxId;
        private String registrationNumber;

        private String contactName;
        private String contactEmail;
        private String contactPhone;

        private String addressLine1;
        private String addressLine2;
        private String city;
        private String state;
        private String postalCode;
        private String country;

        private Boolean ecommerceEnabled;
        private List<String> ecommercePlatforms;

        private String employeeId;
        private String department;

        private String status;
        
        // Phase 1: Entity context update
        private UUID owningEntityId;
        private String owningEntityCode;
    }
    
    // ========================================================================
    // PHASE 1: POBO CONFIGURATION REQUESTS
    // ========================================================================
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdatePoboEligibilityRequest {
        private Boolean poboEligible;
        private UUID defaultPayerEntityId;
        private String defaultPayerEntityCode;
        private String approvalNotes;
    }
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ValidatePoboRequest {
        private UUID partyId;
        private UUID payingEntityId;
        private BigDecimal amount;
        private String currencyCode;
    }
    
    // ========================================================================
    // PHASE 1: INTERCOMPANY CONFIGURATION REQUESTS
    // ========================================================================
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdateIntercompanyConfigRequest {
        private Boolean isIntercompany;
        private UUID linkedLegalEntityId;
        private String linkedLegalEntityCode;
        private Boolean nettingEligible;
        private String icSettlementMethod;
    }
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdateIcCreditLimitRequest {
        private BigDecimal creditLimit;
        private String currency;
        private String approvalNotes;
    }
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateIntercompanyPartyRequest {
        /**
         * The legal entity creating this IC party relationship
         */
        private UUID owningEntityId;
        private String owningEntityCode;
        
        /**
         * The group entity being represented as a vendor/customer
         */
        private UUID linkedLegalEntityId;
        private String linkedLegalEntityCode;
        private String linkedLegalEntityName;
        
        /**
         * Role: VENDOR, CUSTOMER, or both
         */
        private Set<String> roles;
        
        /**
         * Settlement method
         */
        private String icSettlementMethod;
        
        /**
         * Credit configuration
         */
        private BigDecimal icCreditLimit;
        private String icCurrency;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AddBankAccountRequest {
        private String label;
        private String holderName;
        private String bankName;
        private String bankCode;
        private String iban;
        private String accountNumber;
        private String routingNumber;
        private String currency;
        private Boolean isPrimary;
        
        // Phase 1: Payment methods
        private List<String> supportedPaymentMethods;
        private Boolean poboEnabled;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UploadDocumentRequest {
        private String documentType;
        private String category;
        private String name;
        private String documentNumber;
        private LocalDate issueDate;
        private LocalDate expiryDate;
        private String issuingAuthority;
        private String issuingCountry;
        // File data would typically be multipart
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdateKycRequest {
        private String kycStatus;
        private LocalDate kycExpiresAt;
        private String notes;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdateRiskRequest {
        private String riskRating;
        private Integer riskScore;
        private String notes;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ScreeningRequest {
        private Boolean runSanctions;
        private Boolean runPep;
        private Boolean runAdverseMedia;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ScreeningResponse {
        private String sanctionsStatus;
        private Boolean pepStatus;
        private Boolean adverseMediaStatus;
        private LocalDateTime screenedAt;
        private List<ScreeningHitResponse> hits;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ScreeningHitResponse {
        private String source;
        private String matchType;
        private Integer score;
        private String matchedName;
        private String details;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VerifyDocumentRequest {
        private String status; // VERIFIED, REJECTED
        private String rejectionReason;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PartySearchRequest {
        private String query;
        private String partyType;
        private String role;
        private String kycStatus;
        private String riskRating;
        private String status;
        private Integer page;
        private Integer pageSize;
        private String sortBy;
        private String sortOrder;
        
        // Phase 1: Additional filters
        private UUID owningEntityId;
        private Boolean poboEligibleOnly;
        private Boolean intercompanyOnly;
        private Boolean nettingEligibleOnly;
    }
}