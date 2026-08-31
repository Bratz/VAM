package com.bank.vam.dto.treasury;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * DTOs for In-House Bank Page
 */
public class InHouseBankDto {

    // ========================================================================
    // PARTICIPANT (Enhanced IHB Entity with Position)
    // ========================================================================

    public enum EntityType {
        PARENT, SUBSIDIARY, BRANCH, DIVISION
    }

    public enum Position {
        SURPLUS, DEFICIT, NEUTRAL
    }

    public enum ParticipantStatus {
        ACTIVE, INACTIVE, SUSPENDED
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ParticipantResponse {
        private UUID id;
        private String name;
        private String entityCode;
        private EntityType entityType;
        private UUID virtualAccountId;
        private String virtualAccountNumber;
        private String currency;
        private BigDecimal currentBalance;      // Net position (deposits - loans)
        private BigDecimal availableBalance;    // Credit limit - current exposure
        private Position position;              // Derived from currentBalance
        private BigDecimal creditLimit;
        private BigDecimal interestRateLend;    // Rate IHB pays to entity (surplus)
        private BigDecimal interestRateBorrow;  // Rate IHB charges entity (deficit)
        private BigDecimal accruedInterest;     // Total accrued for this entity
        private ParticipantStatus status;
        private LocalDate joinedDate;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateParticipantRequest {
        private String name;
        private String entityCode;
        private EntityType entityType;
        private UUID virtualAccountId;
        private String currency;
        private BigDecimal creditLimit;
        private BigDecimal interestRateLend;
        private BigDecimal interestRateBorrow;
        private String contactName;
        private String contactEmail;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdateParticipantRequest {
        private String name;
        private BigDecimal creditLimit;
        private BigDecimal interestRateLend;
        private BigDecimal interestRateBorrow;
        private ParticipantStatus status;
        private String contactName;
        private String contactEmail;
    }

    // ========================================================================
    // FUNDING TRANSACTIONS
    // ========================================================================

    public enum TransactionType {
        FUNDING,          // IHB lends to entity (deficit funding)
        BORROWING,        // IHB borrows from entity (surplus funding)
        INTEREST_CREDIT,  // Interest paid to entity (surplus)
        INTEREST_DEBIT    // Interest charged to entity (deficit)
    }

    public enum TransactionStatus {
        PENDING, COMPLETED, REVERSED
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FundingTransactionResponse {
        private UUID id;
        private String transactionRef;
        private TransactionType transactionType;
        private String fromEntity;
        private UUID fromEntityId;
        private String toEntity;
        private UUID toEntityId;
        private BigDecimal amount;
        private String currency;
        private BigDecimal interestRate;
        private LocalDate transactionDate;
        private LocalDate valueDate;
        private TransactionStatus status;
        private String description;
        private LocalDateTime createdAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateFundingRequest {
        private TransactionType transactionType;
        private UUID fromEntityId;
        private UUID toEntityId;
        private BigDecimal amount;
        private String currency;
        private BigDecimal interestRate;
        private LocalDate valueDate;
        private LocalDate maturityDate;
        private String description;
    }

    // ========================================================================
    // INTEREST ACCRUALS
    // ========================================================================

    public enum AccrualStatus {
        ACCRUED, POSTED, SETTLED
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InterestAccrualResponse {
        private UUID id;
        private LocalDate periodStart;
        private LocalDate periodEnd;
        private UUID entityId;
        private String entityName;
        private Position position;
        private BigDecimal avgBalance;
        private BigDecimal rate;
        private BigDecimal accruedAmount;
        private AccrualStatus status;
        private LocalDateTime calculatedAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CalculateInterestRequest {
        private LocalDate periodStart;
        private LocalDate periodEnd;
        private boolean postImmediately;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PostInterestRequest {
        private List<UUID> accrualIds;
        private LocalDate postingDate;
    }

    // ========================================================================
    // DASHBOARD / SUMMARY
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IhbSummaryResponse {
        private BigDecimal ihbPoolBalance;       // Master account balance
        private int participantCount;
        private BigDecimal totalSurplus;         // Sum of all surplus positions
        private BigDecimal totalDeficit;         // Sum of all deficit positions (absolute)
        private BigDecimal netInterestMTD;       // Month-to-date net interest
        private BigDecimal netInterestYTD;       // Year-to-date net interest
        private int surplusEntityCount;
        private int deficitEntityCount;
        private int neutralEntityCount;
        private String masterCurrency;
        private LocalDateTime lastCalculationDate;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CalculateInterestResponse {
        private LocalDate periodStart;
        private LocalDate periodEnd;
        private int entitiesProcessed;
        private BigDecimal totalInterestPayable;    // To surplus entities
        private BigDecimal totalInterestReceivable; // From deficit entities
        private BigDecimal netSpreadIncome;
        private List<InterestAccrualResponse> accruals;
    }
}
