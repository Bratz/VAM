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
        private Integer memberCount;
        /** From the pool's runs (R15); set on the list and the single-pool read. */
        private Figures figures;
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

    /** A daily run (spec R10). Interest signed: credit +, debit −. */
    public record Run(UUID id, LocalDate valueDate, String status, BigDecimal poolNet, BigDecimal interestRate,
                      BigDecimal pooledInterest, BigDecimal standaloneInterest, BigDecimal benefit, BigDecimal residue,
                      BigDecimal creditRate, BigDecimal debitRate, String dayCount, boolean floorBreach,
                      int limitBreaches, int snapshotsMissing, LocalDateTime postedAt, List<RunLine> lines) {}

    /**
     * A pool's figures from its current runs, never from stored counters (R9, R15), in the pool's currency. The
     * month and year are those of {@code asOf} (yesterday: the latest date a run can take). Interest signed: credit +,
     * debit −; lastRun* are null when the pool has no run.
     */
    public record Figures(LocalDate asOf, LocalDate lastRunDate, BigDecimal lastRunNet, BigDecimal lastRunPooledInterest,
                          BigDecimal mtdPooledInterest, BigDecimal mtdBenefit, BigDecimal ytdBenefit) {}

    /** One pool's catch-up: the runs it has from..to, or why it failed. */
    public record CatchUp(String poolReference, int runs, String error) {}

    public record RunLine(UUID accountId, String accountNumber, BigDecimal balance, LocalDate balanceAsOf,
                          boolean snapshotMissing, BigDecimal standaloneInterest, BigDecimal allocatedInterest,
                          boolean debitLimitBreach, String postingReference) {}

    /**
     * One posting of pool interest (R11a): the month's (kind MONTH, value date = the last date it covers) or a
     * correction's (kind CORRECTION, value date = the corrected date). Amounts signed: credit +, debit −.
     */
    public record Posting(String correlationId, String kind, String month, LocalDate valueDate, LocalDateTime postedAt,
                          BigDecimal total, List<PostingLine> members) {}

    public record PostingLine(UUID accountId, String accountNumber, BigDecimal amount, String reference) {}

}
