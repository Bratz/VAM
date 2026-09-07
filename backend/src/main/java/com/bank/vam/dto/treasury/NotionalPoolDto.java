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
        private BigDecimal targetBalance;
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
        private PoolMember.MemberStatus status;
        private LocalDate joinedDate;
    }

    @Data
    public static class CreateRequest {
        private String poolName;
        private String poolCurrency;
        private UUID corporateId;
        private UUID programId;
        private BigDecimal targetBalance;
        private BigDecimal interestRate;
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
        private BigDecimal interestRate;
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
