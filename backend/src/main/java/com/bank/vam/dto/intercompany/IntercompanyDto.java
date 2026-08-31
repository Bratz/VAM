package com.bank.vam.dto.intercompany;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Intercompany DTOs - Phase 6 Enhanced
 * 
 * Includes:
 * - Entity management
 * - POBO/COBO operations
 * - Bilateral positions
 * - Netting integration
 * - Settlement workflows
 * - Transfer pricing
 * - Reporting
 */
public class IntercompanyDto {

    // ========================================================================
    // ENUMS (must match entity enums)
    // ========================================================================

    public enum TransactionType {
        POBO, POBO_PAYMENT, COBO, COBO_COLLECTION, SETTLEMENT, INTEREST, ADJUSTMENT,
        IC_RECEIVABLE,   // Treasury's receivable from subsidiary
        IC_PAYABLE       // Subsidiary's payable to treasury
    }

    public enum TransactionStatus {
        PENDING, ACTIVE, PROCESSED, COMPLETED, SETTLED, REVERSED, FAILED
    }

    // ========================================================================
    // ENTITY DTOs
    // ========================================================================

    @Data
    @Builder
    public static class EntitySummary {
        private UUID id;
        private String code;
        private String name;
    }

    @Data
    @Builder
    public static class EntityWithPositionResponse {
        private UUID entityId;
        private String entityCode;
        private String entityName;
        private String status;
        private BigDecimal creditLimit;
        private BigDecimal currentExposure;
        private BigDecimal availableLimit;
    }

    @Data
    @Builder
    public static class EntityPositionDetailResponse {
        private UUID entityId;
        private String entityCode;
        private String entityName;
        private List<LoanSummary> loansAsLender;
        private List<LoanSummary> loansAsBorrower;
        private List<DepositSummary> deposits;
        private BigDecimal totalLent;
        private BigDecimal totalBorrowed;
        private BigDecimal totalDeposited;
        private BigDecimal netPosition;
    }

    @Data
    @Builder
    public static class EntityValidationRequest {
        private BigDecimal amount;
        private String currencyCode;
        private String transactionType;
    }

    @Data
    @Builder
    public static class EntityValidationResponse {
        private UUID entityId;
        private String entityCode;
        private String entityName;
        private boolean valid;
        private String reason;
        private BigDecimal creditLimit;
        private BigDecimal currentExposure;
        private BigDecimal availableLimit;
        private BigDecimal proposedExposure;
        private BigDecimal utilizationPercent;
        private List<String> warnings;
    }

    // ========================================================================
    // LOAN/DEPOSIT SUMMARIES
    // ========================================================================

    @Data
    @Builder
    public static class LoanSummary {
        private UUID loanId;
        private String loanRef;
        private BigDecimal principalAmount;
        private BigDecimal outstandingAmount;
        private BigDecimal interestRate;
        private String status;
    }

    @Data
    @Builder
    public static class DepositSummary {
        private UUID depositId;
        private String depositRef;
        private BigDecimal principalAmount;
        private BigDecimal interestRate;
        private String status;
    }

    // ========================================================================
    // POBO DTOs
    // ========================================================================

    @Data
    @Builder
    public static class PoboRequest {
        private UUID payingEntityId;
        private UUID behalfEntityId;
        private BigDecimal amount;
        private String currencyCode;
        private String description;
        private String originalReference;
    }

    @Data
    @Builder
    public static class PoboPreviewResponse {
        private UUID payingEntityId;
        private String payingEntityCode;
        private String payingEntityName;
        private UUID behalfEntityId;
        private String behalfEntityCode;
        private String behalfEntityName;
        private BigDecimal amount;
        private String currencyCode;
        private BigDecimal charges;
        private BigDecimal totalAmount;
        private IhbLoanPreview ihbLoanPreview;
        private boolean withinCreditLimit;
        private BigDecimal availableLimit;
        private List<String> warnings;
        private List<ChargeBreakdown> chargeBreakdown;
    }

    @Data
    @Builder
    public static class PoboResultResponse {
        private String transactionRef;
        private String status;
        private String message;
        private UUID payingEntityId;
        private String payingEntityCode;
        private UUID behalfEntityId;
        private String behalfEntityCode;
        private BigDecimal amount;
        private String currencyCode;
        private BigDecimal charges;
        private BigDecimal totalAmount;
        private UUID ihbLoanId;
        private String ihbLoanRef;
        private LocalDateTime processedAt;
    }

    @Data
    @Builder
    public static class IhbLoanPreview {
        private UUID lenderId;
        private UUID borrowerId;
        private BigDecimal principalAmount;
        private BigDecimal interestRate;
        private BigDecimal estimatedInterest;
    }

    @Data
    @Builder
    public static class ChargeBreakdown {
        private String chargeType;
        private String description;
        private BigDecimal amount;
    }

    // ========================================================================
    // COBO DTOs
    // ========================================================================

    @Data
    @Builder
    public static class CoboRequest {
        private UUID collectingEntityId;
        private UUID behalfEntityId;
        private BigDecimal amount;
        private String currencyCode;
        private String description;
        private boolean generateViban;
    }

    @Data
    @Builder
    public static class CoboResultResponse {
        private String transactionRef;
        private String status;
        private String message;
        private UUID collectingEntityId;
        private String collectingEntityCode;
        private UUID behalfEntityId;
        private String behalfEntityCode;
        private BigDecimal amount;
        private String currencyCode;
        private BigDecimal charges;
        private BigDecimal totalAmount;
        private String viban;
        private UUID vibanId;
        private LocalDateTime createdAt;
    }

    @Data
    @Builder
    public static class CoboCollectionRequest {
        private BigDecimal amount;
        private String payerReference;
        private String payerName;
    }

    @Data
    @Builder
    public static class CoboCollectionResponse {
        private String transactionRef;
        private String status;
        private String message;
        private BigDecimal collectedAmount;
        private UUID ihbDepositId;
        private String ihbDepositRef;
        private LocalDateTime processedAt;
    }

    // ========================================================================
    // TRANSACTION DTOs
    // ========================================================================

    @Data
    @Builder
    public static class IntercompanyTransactionResponse {
        private UUID id;
        private String transactionRef;
        private String transactionType;
        private UUID payingEntityId;
        private String payingEntityCode;
        private String payingEntityName;
        private UUID behalfEntityId;
        private String behalfEntityCode;
        private String behalfEntityName;
        private BigDecimal amount;
        private String currencyCode;
        private BigDecimal charges;
        private BigDecimal netAmount;
        private String status;
        private LocalDateTime createdAt;
        private LocalDateTime processedAt;
    }

    @Data
    @Builder
    public static class IntercompanyTransactionDetailResponse {
        private UUID id;
        private String transactionRef;
        private String transactionType;
        private EntitySummary payingEntity;
        private EntitySummary behalfEntity;
        private BigDecimal amount;
        private String currencyCode;
        private BigDecimal charges;
        private BigDecimal netAmount;
        private UUID ihbLoanId;
        private UUID ihbDepositId;
        private String viban;
        private UUID vibanId;
        private String status;
        private String statusReason;
        private LocalDateTime createdAt;
        private LocalDateTime processedAt;
        private LocalDateTime settledAt;
        private String settlementRef;
        private String createdBy;
    }

    // ========================================================================
    // SETTLEMENT DTOs
    // ========================================================================

    @Data
    @Builder
    public static class NetSettlementRequest {
        private UUID entity1Id;
        private UUID entity2Id;
    }

    @Data
    @Builder
    public static class NetSettlementResponse {
        private UUID entity1Id;
        private UUID entity2Id;
        private BigDecimal entity1OwesEntity2;
        private BigDecimal entity2OwesEntity1;
        private BigDecimal netAmount;
        private UUID netPayerId;
        private UUID netReceiverId;
        private int transactionCount;
        private List<UUID> transactionIds;
    }

    @Data
    @Builder
    public static class SettlementRequest {
        private List<UUID> transactionIds;
        private String settlementMethod;
        private String notes;
    }

    @Data
    @Builder
    public static class SettlementResultResponse {
        private String settlementRef;
        private String status;
        private String message;
        private int transactionsSettled;
        private BigDecimal totalAmount;
        private LocalDateTime settledAt;
    }

    @Data
    @Builder
    public static class SettlementHistoryResponse {
        private String settlementRef;
        private int transactionCount;
        private BigDecimal totalAmount;
        private LocalDateTime settledAt;
    }

    @Data
    @Builder
    public static class SettlementDetailResponse {
        private String settlementRef;
        private int transactionCount;
        private BigDecimal totalAmount;
        private List<IntercompanyTransactionResponse> transactions;
        private LocalDateTime settledAt;
    }

    // ========================================================================
    // STATISTICS & REPORTING
    // ========================================================================

    @Data
    @Builder
    public static class IntercompanyStatsResponse {
        private int totalPoboTransactions;
        private int totalCoboTransactions;
        private int pendingSettlement;
        private BigDecimal totalPoboVolume;
        private BigDecimal totalCoboVolume;
        private int activeEntities;
    }

    @Data
    @Builder
    public static class IntercompanyActivityReport {
        private LocalDate startDate;
        private LocalDate endDate;
        private UUID entityId;
        private int totalTransactions;
        private BigDecimal totalVolume;
        private int poboCount;
        private int coboCount;
        private int settledCount;
    }

    // ========================================================================
    // PHASE 6: ENTITY PAIR & BILATERAL POSITION DTOs
    // ========================================================================

    @Data
    @Builder
    public static class EntityPairResponse {
        private UUID entity1Id;
        private String entity1Code;
        private String entity1Name;
        private UUID entity2Id;
        private String entity2Code;
        private String entity2Name;
        private BigDecimal entity1OwesEntity2;
        private BigDecimal entity2OwesEntity1;
        private BigDecimal netPosition;
        private UUID netCreditorId;
        private UUID netDebtorId;
        private int totalTransactions;
        private int pendingTransactions;
        private boolean nettingEligible;
    }

    @Data
    @Builder
    public static class BilateralPositionResponse {
        private UUID entity1Id;
        private String entity1Code;
        private String entity1Name;
        private UUID entity2Id;
        private String entity2Code;
        private String entity2Name;
        private BigDecimal grossPayables;
        private BigDecimal grossReceivables;
        private BigDecimal netPosition;
        private String netDirection;
        private UUID netCreditorId;
        private UUID netDebtorId;
        private int transactionCount;
        private int rechargeCount;
        private String currency;
        private LocalDateTime asOfDate;
    }

    // ========================================================================
    // PHASE 6: NETTING INTEGRATION DTOs
    // ========================================================================

    @Data
    @Builder
    public static class NettingEligibilityCheckResponse {
        private UUID entity1Id;
        private UUID entity2Id;
        private boolean isEligible;
        private String ineligibilityReason;
        private int eligibleTransactionCount;
        private int eligibleRechargeCount;
        private BigDecimal totalEligibleAmount;
        private UUID suggestedCycleId;
        private String suggestedCycleReference;
    }

    @Data
    @Builder
    public static class AddToNettingResponse {
        private UUID cycleId;
        private String cycleReference;
        private int transactionsAdded;
        private int rechargesAdded;
        private int totalAdded;
        private BigDecimal totalAmountAdded;
    }

    // ========================================================================
    // PHASE 6: TRANSFER PRICING DTOs
    // ========================================================================

    @Data
    @Builder
    public static class TransferPricingRequest {
        private UUID transactionId;
        private String validationNotes;
        private String validatedBy;
    }

    @Data
    @Builder
    public static class TransferPricingResponse {
        private UUID transactionId;
        private String transactionRef;
        private boolean isCompliant;
        private List<String> findings;
        private BigDecimal appliedChargeRate;
        private BigDecimal marketRateLow;
        private BigDecimal marketRateHigh;
        private String validationNotes;
        private LocalDateTime validatedAt;
        private String validatedBy;
    }

    // ========================================================================
    // PHASE 6: CORPORATE POSITION SUMMARY
    // ========================================================================

    @Data
    @Builder
    public static class CorporatePositionSummaryResponse {
        private UUID corporateId;
        private String corporateName;
        private int totalEntities;
        private BigDecimal totalIntercompanyPayables;
        private BigDecimal totalIntercompanyReceivables;
        private BigDecimal netIntercompanyPosition;
        private int pendingTransactions;
        private int pendingRecharges;
        private int activeNettingCycles;
        private BigDecimal totalNettingSavings;
        private String baseCurrency;
        private LocalDateTime asOfDate;
    }

    @Data
    @Builder
    public static class EntityPositionBreakdown {
        private UUID entityId;
        private String entityCode;
        private String entityName;
        private BigDecimal intercompanyPayables;
        private BigDecimal intercompanyReceivables;
        private BigDecimal netPosition;
        private int counterpartyCount;
        private List<CounterpartyPosition> topCounterparties;
    }

    @Data
    @Builder
    public static class CounterpartyPosition {
        private UUID counterpartyId;
        private String counterpartyCode;
        private String counterpartyName;
        private BigDecimal amountOwed;
        private BigDecimal amountDue;
        private BigDecimal netPosition;
    }

    // ========================================================================
    // PHASE 6: INTERCOMPANY REPORT DTOs
    // ========================================================================

    @Data
    @Builder
    public static class IntercompanyReportRequest {
        private UUID corporateId;
        private UUID entityId;
        private LocalDate startDate;
        private LocalDate endDate;
        private String reportType; // SUMMARY, DETAILED, RECONCILIATION
        private List<String> includeTransactionTypes;
    }

    @Data
    @Builder
    public static class IntercompanyReportResponse {
        private String reportId;
        private String reportType;
        private UUID corporateId;
        private UUID entityId;
        private LocalDate periodStart;
        private LocalDate periodEnd;
        private ReportSummary summary;
        private List<IntercompanyTransactionResponse> transactions;
        private LocalDateTime generatedAt;
    }

    @Data
    @Builder
    public static class ReportSummary {
        private int totalTransactions;
        private BigDecimal totalVolume;
        private int poboCount;
        private BigDecimal poboVolume;
        private int coboCount;
        private BigDecimal coboVolume;
        private int settlementCount;
        private BigDecimal settlementVolume;
        private BigDecimal totalCharges;
        private BigDecimal netPosition;
    }

    // ========================================================================
    // PHASE 6: DASHBOARD DTOs
    // ========================================================================

    @Data
    @Builder
    public static class IntercompanyDashboardResponse {
        private UUID entityId;
        private String entityCode;
        private String entityName;
        
        // Position summary
        private BigDecimal totalPayables;
        private BigDecimal totalReceivables;
        private BigDecimal netPosition;
        
        // Transaction counts
        private int pendingPobo;
        private int pendingCobo;
        private int awaitingSettlement;
        
        // Alerts
        private List<IntercompanyAlert> alerts;
        
        // Recent activity
        private List<IntercompanyTransactionResponse> recentTransactions;
        
        // Top counterparties
        private List<CounterpartyPosition> topCounterparties;
        
        private LocalDateTime asOfDate;
    }

    @Data
    @Builder
    public static class IntercompanyAlert {
        private String alertType; // LIMIT_WARNING, OVERDUE, TRANSFER_PRICING, NETTING_OPPORTUNITY
        private String severity; // INFO, WARNING, CRITICAL
        private String message;
        private UUID relatedEntityId;
        private UUID relatedTransactionId;
        private LocalDateTime createdAt;
    }
}