package com.bank.vam.dto.treasury;

import com.bank.vam.entity.treasury.NotionalPool;
import com.bank.vam.entity.treasury.PoolMember;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public class NotionalPoolDto {

    @Data
    public static class Response {
        private UUID id;
        private String poolReference;
        private String poolName;
        private String poolCurrency;
        private UUID corporateId;
        private UUID programId;
        private UUID legalEntityId;
        private UUID headerAccountId;
        private UUID interestConfigurationId;
        private BigDecimal poolFloor;
        private Rates rates;
        private BigDecimal targetBalance;
        /** Retired: a display copy of {@code rates.effectiveCreditRate}. */
        private BigDecimal interestRate;
        private NotionalPool.InterestCalculationMethod interestCalculationMethod;
        private NotionalPool.AllocationMethod allocationMethod;
        private BigDecimal totalBalance;
        private BigDecimal interestSavingsYtd;
        private Integer memberCount;
        private NotionalPool.PoolStatus status;
        private LocalDate effectiveFrom;
        private LocalDate effectiveTo;
        private LocalDate lastCalculationDate;
        private List<MemberResponse> members;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }

    @Data
    public static class MemberResponse {
        private UUID id;
        private UUID accountId;
        private String accountNumber;
        private String entityCode;
        private String entityName;
        private BigDecimal currentBalance;
        private BigDecimal contributionPercent;
        private BigDecimal interestAllocation;
        private BigDecimal weight;
        private BigDecimal debitLimit;
        private PoolMember.MemberStatus status;
        private LocalDate joinedDate;
        private LocalDate leftDate;
    }

    /**
     * The pool's rates, held in its InterestConfiguration (R7), % a year. On a request: the credit base rate is
     * required; a missing debit base takes the credit base, a missing day count the currency's default; tierConfig
     * is the config's banded tiers JSON. The effective rates and systemDefault are filled on a response only.
     */
    @Data
    public static class Rates {
        private BigDecimal creditBaseRate;
        private BigDecimal creditSpread;
        private BigDecimal debitBaseRate;
        private BigDecimal debitSpread;
        private String dayCountConvention;
        private String tierConfig;
        private BigDecimal effectiveCreditRate;
        private BigDecimal effectiveDebitRate;
        /** Made up by the application (an old pool's debit rate), not agreed: screens show it as a default. */
        private boolean systemDefault;
    }

    @Data
    public static class CreateRequest {
        private String poolName;
        private String poolCurrency;
        private UUID corporateId;
        private UUID programId;
        /** The pool's legal entity; when absent, its header's. */
        private UUID legalEntityId;
        /** One of {@code members}; when absent, the first. */
        private UUID headerAccountId;
        private BigDecimal poolFloor;
        private Rates rates;
        private BigDecimal targetBalance;
        private NotionalPool.InterestCalculationMethod interestCalculationMethod;
        private NotionalPool.AllocationMethod allocationMethod;
        private LocalDate effectiveFrom;
        private LocalDate effectiveTo;
        private List<MemberRequest> members;
    }

    @Data
    public static class MemberRequest {
        private UUID accountId;
        private String accountNumber;
        private String entityCode;
        private String entityName;
        private BigDecimal weight;
        private BigDecimal debitLimit;
    }

    @Data
    public static class BulkAddMembersRequest {
        private List<UUID> accountIds;
    }

    @Data
    public static class BulkAddMembersResponse {
        private int added;
        private List<SkippedMember> skipped;
    }

    @Data
    public static class SkippedMember {
        private UUID accountId;
        private String reason;

        public SkippedMember() {
        }

        public SkippedMember(UUID accountId, String reason) {
            this.accountId = accountId;
            this.reason = reason;
        }
    }

    @Data
    public static class UpdateRequest {
        private String poolName;
        private BigDecimal targetBalance;
        /** An active member's account id. */
        private UUID headerAccountId;
        /**
         * Always applied: null removes the floor.
         * ponytail: whole-form semantics, unlike the fields around it — the edit form is the only caller and sends it.
         */
        private BigDecimal poolFloor;
        /** Fields given replace the pool's config's; absent ones stay. */
        private Rates rates;
        /** Member id → debit limit; an entry replaces that member's limit, a null value removes it. */
        private java.util.Map<UUID, BigDecimal> memberDebitLimits;
        private NotionalPool.InterestCalculationMethod interestCalculationMethod;
        private NotionalPool.AllocationMethod allocationMethod;
    }

    @Data
    public static class CalculateInterestResponse {
        private UUID poolId;
        private LocalDate calculationDate;
        private BigDecimal poolBalance;
        private BigDecimal interestRate;
        private BigDecimal grossInterest;
        private BigDecimal netInterest;
        private List<MemberInterestAllocation> memberAllocations;
    }

    @Data
    public static class MemberInterestAllocation {
        private UUID memberId;
        private String entityCode;
        private BigDecimal balance;
        private BigDecimal contributionPercent;
        private BigDecimal interestAllocation;
    }
}
