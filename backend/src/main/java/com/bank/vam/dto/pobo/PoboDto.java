package com.bank.vam.dto.pobo;

import com.bank.vam.entity.pobo.IntercompanyRecharge;
import com.bank.vam.entity.pobo.PoboAuthorization;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * POBO DTOs - Phase 5 Enhanced
 * 
 * Includes:
 * - Authorization request/response
 * - Recharge management
 * - POBO validation
 * - Execution history
 * - Dashboard summaries
 */
public class PoboDto {

    // ========================================================================
    // AUTHORIZATION DTOs
    // ========================================================================
    
    @Data
    @Builder
    public static class CreateAuthorizationRequest {
        private UUID payerEntityId;
        private String payerEntityCode;
        private UUID payerVaId;
        private UUID behalfEntityId;
        private String behalfEntityCode;
        private UUID behalfVaId;
        private PoboAuthorization.AuthorizationType authorizationType;
        private BigDecimal singlePaymentLimit;
        private BigDecimal dailyLimit;
        private BigDecimal monthlyLimit;
        private String currencyCode;
        private String allowedPaymentTypes;  // JSON or comma-separated
        private String allowedVendorIds;     // JSON or comma-separated UUIDs
        private Boolean autoRecharge;
        private BigDecimal rechargeServiceFeeRate;
        private Boolean requiresApproval;
        private LocalDate effectiveFrom;
        private LocalDate effectiveTo;
    }
    
    @Data
    @Builder
    public static class AuthorizationResponse {
        private UUID id;
        private String authorizationCode;
        private UUID payerEntityId;
        private String payerEntityCode;
        private UUID behalfEntityId;
        private String behalfEntityCode;
        private PoboAuthorization.AuthorizationType authorizationType;
        private BigDecimal singlePaymentLimit;
        private BigDecimal dailyLimit;
        private BigDecimal monthlyLimit;
        private String currencyCode;
        private BigDecimal usedToday;
        private BigDecimal usedThisMonth;
        private BigDecimal remainingDailyLimit;
        private BigDecimal remainingMonthlyLimit;
        private Boolean autoRecharge;
        private BigDecimal rechargeServiceFeeRate;
        private Boolean requiresApproval;
        private String status;
        private LocalDate effectiveFrom;
        private LocalDate effectiveTo;
        private String approvedBy;
        private LocalDateTime approvedAt;
        private LocalDateTime createdAt;
    }
    
    @Data
    @Builder
    public static class UpdateLimitsRequest {
        private BigDecimal singlePaymentLimit;
        private BigDecimal dailyLimit;
        private BigDecimal monthlyLimit;
    }

    // ========================================================================
    // VALIDATION DTOs
    // ========================================================================
    
    @Data
    @Builder
    public static class ValidatePoboRequest {
        private UUID payerEntityId;
        private String payerEntityCode;
        private UUID behalfEntityId;
        private String behalfEntityCode;
        private BigDecimal amount;
        private String currencyCode;
        private String paymentType;
        private UUID vendorId;
    }
    
    @Data
    @Builder
    public static class ValidatePoboResponse {
        private Boolean isAuthorized;
        private String authorizationCode;
        private Boolean withinLimits;
        private Boolean paymentTypeAllowed;
        private Boolean vendorAllowed;
        private BigDecimal maxAvailable;
        private BigDecimal remainingDailyLimit;
        private BigDecimal remainingMonthlyLimit;
        private Boolean requiresApproval;
        private Boolean autoRechargeEnabled;
        private BigDecimal serviceFeeRate;
        private String rejectionReason;
        private List<String> warnings;
    }

    // ========================================================================
    // RECHARGE DTOs
    // ========================================================================
    
    @Data
    @Builder
    public static class CreateRechargeRequest {
        private UUID payerEntityId;
        private String payerEntityCode;
        private String payerEntityName;
        private UUID payerVaId;
        private UUID behalfEntityId;
        private String behalfEntityCode;
        private String behalfEntityName;
        private UUID behalfVaId;
        private UUID originalPayableId;
        private UUID originalPaymentExecutionId;
        private String originalPaymentReference;
        private BigDecimal originalAmount;
        private String currencyCode;
        private BigDecimal serviceFeeRate;
        private BigDecimal adminFee;
        private Boolean requiresApproval;
    }
    
    @Data
    @Builder
    public static class RechargeResponse {
        private UUID id;
        private String rechargeReference;
        private UUID payerEntityId;
        private String payerEntityCode;
        private String payerEntityName;
        private UUID behalfEntityId;
        private String behalfEntityCode;
        private String behalfEntityName;
        private UUID originalPayableId;
        private String originalPaymentReference;
        private BigDecimal originalAmount;
        private BigDecimal rechargeAmount;
        private BigDecimal serviceFee;
        private BigDecimal adminFee;
        private BigDecimal fxMarkup;
        private BigDecimal totalRecharge;
        private String currencyCode;
        private Boolean armLengthValidated;
        private String armLengthNotes;
        private IntercompanyRecharge.RechargeStatus status;
        private LocalDate settlementDate;
        private String settlementReference;
        private IntercompanyRecharge.SettlementMethod settledVia;
        private UUID ihbLoanId;
        private String approvedBy;
        private LocalDateTime approvedAt;
        private LocalDateTime createdAt;
    }
    
    @Data
    @Builder
    public static class SettleRechargeRequest {
        private UUID rechargeId;
        private IntercompanyRecharge.SettlementMethod settlementMethod;
        private String settlementReference;
        private UUID ihbLoanId;
    }

    // ========================================================================
    // PAYMENT PROCESSING DTOs
    // ========================================================================
    
    @Data
    @Builder
    public static class PoboPaymentRequest {
        private UUID payerEntityId;
        private String payerEntityCode;
        private UUID behalfEntityId;
        private String behalfEntityCode;
        private UUID payableId;
        private BigDecimal amount;
        private String currencyCode;
        private Boolean createRecharge;
        private Boolean createIhbLoan;  // Phase 5: Option to create IHB loan for reimbursement
    }
    
    @Data
    @Builder
    public static class PoboPaymentResponse {
        private UUID paymentExecutionId;
        private String paymentReference;
        private String status;
        private BigDecimal paidAmount;
        private UUID rechargeId;
        private String rechargeReference;
        private BigDecimal rechargeAmount;
        private BigDecimal serviceFee;
        private BigDecimal totalRecharge;
        private UUID ihbLoanId;           // Phase 5: IHB loan ID if created
        private String ihbLoanReference;  // Phase 5: IHB loan reference if created
        private LocalDateTime processedAt;
    }

    // ========================================================================
    // STATISTICS DTOs
    // ========================================================================
    
    @Data
    @Builder
    public static class PoboStatsResponse {
        private int activeAuthorizations;
        private int pendingRecharges;
        private int settledRecharges;
        private BigDecimal totalRechargesOutstanding;
        private BigDecimal totalServiceFeesCollected;
        private int ihbLoansCreated;
        private int armLengthValidationsPending;
        private LocalDateTime asOfDate;
    }

    // ========================================================================
    // PHASE 5: HISTORY DTOs
    // ========================================================================
    
    @Data
    @Builder
    public static class PoboHistoryResponse {
        private Long totalElements;
        private int totalPages;
        private int page;
        private int size;
        private List<PoboExecutionSummary> executions;
    }
    
    @Data
    @Builder
    public static class PoboExecutionSummary {
        private UUID executionId;
        private String executionReference;
        private UUID payableId;
        private String payableNumber;
        private String vendorName;
        private UUID treasuryEntityId;
        private String treasuryEntityCode;
        private UUID subsidiaryEntityId;
        private String subsidiaryEntityCode;
        private BigDecimal paidAmount;
        private String currency;
        private UUID rechargeId;
        private String rechargeReference;
        private BigDecimal totalRecharge;
        private boolean ihbLoanCreated;
        private String status;
        private LocalDateTime executedAt;
        private String executedBy;
    }
    
    @Data
    @Builder
    public static class PoboExecutionDetailResponse {
        private UUID executionId;
        private String executionReference;
        
        // Payable details
        private UUID payableId;
        private String payableNumber;
        private String vendorName;
        private String vendorAccount;
        private BigDecimal payableAmount;
        
        // Entity details
        private UUID treasuryEntityId;
        private String treasuryEntityCode;
        private String treasuryEntityName;
        private UUID subsidiaryEntityId;
        private String subsidiaryEntityCode;
        private String subsidiaryEntityName;
        
        // Payment details
        private String paymentTransactionRef;
        private BigDecimal paidAmount;
        private String currency;
        private LocalDateTime paymentTimestamp;
        
        // Recharge details
        private UUID rechargeId;
        private String rechargeReference;
        private BigDecimal rechargeAmount;
        private BigDecimal serviceFee;
        private BigDecimal processingFee;
        private BigDecimal fxMarkup;
        private BigDecimal totalRecharge;
        private String rechargeStatus;
        
        // IHB loan details (if created)
        private boolean ihbLoanCreated;
        private UUID ihbLoanId;
        private String ihbLoanReference;
        private BigDecimal ihbLoanAmount;
        private BigDecimal ihbInterestRate;
        private LocalDate ihbMaturityDate;
        
        // Audit
        private String executedBy;
        private LocalDateTime executedAt;
        private String approvedBy;
        private LocalDateTime approvedAt;
    }

    // ========================================================================
    // PHASE 5: DASHBOARD DTOs
    // ========================================================================
    
    @Data
    @Builder
    public static class SubsidiaryPoboDashboard {
        private UUID entityId;
        private int activeAuthorizations;
        private int pendingRecharges;
        private BigDecimal totalOutstandingRecharge;
        private List<AuthorizationResponse> authorizations;
        private List<RechargeResponse> recentRecharges;
    }
    
    @Data
    @Builder
    public static class TreasuryPoboDashboard {
        private UUID entityId;
        private int totalSubsidiaries;
        private long activeAuthorizations;
        private int pendingApprovals;
        private BigDecimal totalRechargesOutstanding;
        private BigDecimal totalServiceFeesCollected;
        private int armLengthValidationsPending;
        private List<SubsidiaryPoboStatus> subsidiaryStatuses;
        private List<RechargeResponse> pendingRecharges;
    }
    
    @Data
    @Builder
    public static class SubsidiaryPoboStatus {
        private UUID entityId;
        private String entityCode;
        private String authorizationCode;
        private String status;
        private BigDecimal dailyLimit;
        private BigDecimal usedToday;
        private BigDecimal remainingDailyLimit;
        private BigDecimal monthlyLimit;
        private BigDecimal usedThisMonth;
        private BigDecimal remainingMonthlyLimit;
    }

    // ========================================================================
    // ENTITY POSITION DTOs (for reporting)
    // ========================================================================
    
    @Data
    @Builder
    public static class EntityPoboPositionResponse {
        private UUID entityId;
        private String entityCode;
        private String entityName;
        private String entityType; // TREASURY or SUBSIDIARY
        
        // As Treasury (payer)
        private int subsidiariesAuthorized;
        private BigDecimal totalPaidOnBehalf;
        private BigDecimal totalRechargesOutstanding;
        private BigDecimal totalServiceFeesEarned;
        
        // As Subsidiary (behalf)
        private int treasuriesAuthorized;
        private BigDecimal totalReceivedViaPOBO;
        private BigDecimal totalRechargesOwed;
        private BigDecimal totalServiceFeesPaid;
        
        // Net position
        private BigDecimal netPoboPosition;
    }
    
    @Data
    @Builder
    public static class HierarchyPoboSummary {
        private UUID corporateId;
        private String corporateName;
        private List<PayerSummary> payers;
        private List<BehalfSummary> subsidiaries;
        private BigDecimal totalIntercompanyVolume;
        private BigDecimal totalServiceFees;
        private int totalTransactions;
        private LocalDate periodStart;
        private LocalDate periodEnd;
    }
    
    @Data
    @Builder
    public static class PayerSummary {
        private UUID entityId;
        private String entityCode;
        private String entityName;
        private int subsidiariesServed;
        private BigDecimal totalPaid;
        private BigDecimal totalRechargesCreated;
        private BigDecimal outstandingRecharges;
    }
    
    @Data
    @Builder
    public static class BehalfSummary {
        private UUID entityId;
        private String entityCode;
        private String entityName;
        private BigDecimal totalReceivedViaPOBO;
        private BigDecimal totalRechargesOwed;
        private BigDecimal settledViaIHB;
        private BigDecimal settledViaNetting;
    }
}