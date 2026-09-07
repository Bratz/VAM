package com.bank.vam.service.treasury;

import com.bank.vam.config.HomeBankProperties;
import com.bank.vam.dto.treasury.NotionalPoolDto;
import com.bank.vam.entity.PhysicalAccount;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.treasury.*;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.exception.ResourceNotFoundException;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.treasury.*;
import com.bank.vam.service.audit.AuditLogService;
import com.bank.vam.service.treasury.allocation.AllocationStrategy;
import com.bank.vam.service.treasury.allocation.AllocationStrategyRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotionalPoolService {

    private final NotionalPoolRepository poolRepository;
    private final PoolMemberRepository memberRepository;
    private final VirtualAccountRepository vaRepository;  // For fetching account balances
    private final com.bank.vam.repository.PhysicalAccountRepository physicalAccountRepository;
    private final FeePostingService feePostingService;  // Fee posting integration
    private final HomeBankProperties homeBankProperties;
    private final AllocationStrategyRegistry allocationStrategies;
    private final AuditLogService auditLog;
    private final com.bank.vam.config.MarketProfileProperties marketProfile;
    
    private static final AtomicInteger poolSequence = new AtomicInteger(1);
    
    // NEW: Pool fee constants
    private static final BigDecimal POOL_MANAGEMENT_FEE_RATE = new BigDecimal("0.0001");  // 0.01% monthly
    private static final BigDecimal POOL_MANAGEMENT_FEE_MIN = new BigDecimal("100.00");
    private static final BigDecimal POOL_MEMBERSHIP_FEE = new BigDecimal("25.00");

    @Transactional(readOnly = true)
    public List<NotionalPoolDto.Response> getAllPools() {
        return poolRepository.findAll().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<NotionalPoolDto.Response> getActivePools() {
        return poolRepository.findAllActiveWithMembers().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public NotionalPoolDto.Response getPoolById(UUID id) {
        NotionalPool pool = poolRepository.findByIdWithMembers(id)
                .orElseThrow(() -> new ResourceNotFoundException("Notional pool not found: " + id));

        // Refresh member balances from actual accounts
        refreshMemberBalances(pool);

        return toResponse(pool);
    }

    /**
     * Refresh member balances from their actual virtual accounts.
     * Updates both individual member balances and total pool balance.
     */
    private void refreshMemberBalances(NotionalPool pool) {
        if (pool.getMembers() == null || pool.getMembers().isEmpty()) {
            return;
        }

        // Batch-fetch all member accounts in one query instead of one findById per member (N+1).
        List<UUID> accountIds = pool.getMembers().stream()
                .map(PoolMember::getAccountId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        Map<UUID, VirtualAccount> accountsById = vaRepository.findAllById(accountIds).stream()
                .collect(Collectors.toMap(VirtualAccount::getId, va -> va));

        BigDecimal totalBalance = BigDecimal.ZERO;
        for (PoolMember member : pool.getMembers()) {
            BigDecimal balance = resolveAccountBalance(accountsById.get(member.getAccountId()));
            member.setCurrentBalance(balance);
            totalBalance = totalBalance.add(balance);
        }
        pool.setTotalBalance(totalBalance);

        // Update contribution percentages
        if (totalBalance.compareTo(BigDecimal.ZERO) > 0) {
            for (PoolMember member : pool.getMembers()) {
                BigDecimal contribution = member.getCurrentBalance()
                        .divide(totalBalance, 6, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100));
                member.setContributionPercent(contribution);
            }
        }

        pool.setUpdatedAt(LocalDateTime.now());
        poolRepository.save(pool);
    }

    @Transactional
    public NotionalPoolDto.Response createPool(NotionalPoolDto.CreateRequest request) {
        NotionalPool pool = new NotionalPool();
        pool.setPoolReference("NP-" + String.format("%04d", poolSequence.getAndIncrement()));
        pool.setPoolName(request.getPoolName());
        pool.setPoolCurrency(request.getPoolCurrency() != null ? request.getPoolCurrency() : marketProfile.getDefaultCurrency());
        // V11: persist the owning corporate/program (was silently dropped —
        // no column existed — so Notional Pooling's corporate filter was a
        // no-op: every pool always passed the fail-open predicate).
        pool.setCorporateId(request.getCorporateId());
        pool.setProgramId(request.getProgramId());
        pool.setTargetBalance(request.getTargetBalance());
        pool.setInterestRate(request.getInterestRate());
        pool.setInterestCalculationMethod(request.getInterestCalculationMethod());
        pool.setAllocationMethod(request.getAllocationMethod() != null
                ? request.getAllocationMethod()
                : NotionalPool.AllocationMethod.CONTRIBUTION_PERCENT);
        pool.setEffectiveFrom(request.getEffectiveFrom() != null ? request.getEffectiveFrom() : LocalDate.now());
        pool.setEffectiveTo(request.getEffectiveTo());
        pool.setStatus(NotionalPool.PoolStatus.ACTIVE);

        // Add members and fetch their current balances
        BigDecimal totalBalance = BigDecimal.ZERO;
        if (request.getMembers() != null) {
            // Batch-fetch all candidate accounts in one query instead of one findById
            // per member (N+1) — used for both eligibility checks and balance lookup.
            List<UUID> accountIds = request.getMembers().stream()
                    .map(NotionalPoolDto.MemberRequest::getAccountId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
            Map<UUID, VirtualAccount> accountsById = vaRepository.findAllById(accountIds).stream()
                    .collect(Collectors.toMap(VirtualAccount::getId, va -> va));

            for (NotionalPoolDto.MemberRequest memberReq : request.getMembers()) {
                // v1 eligibility: must be a home-bank-held PHYSICAL_MIRROR
                VirtualAccount va = requireAccountForPool(memberReq.getAccountId(), memberReq.getAccountNumber(), accountsById);
                assertPoolEligible(va, memberReq.getAccountNumber());

                PoolMember member = new PoolMember();
                member.setPool(pool);
                member.setAccountId(memberReq.getAccountId());
                member.setAccountNumber(memberReq.getAccountNumber());
                member.setEntityCode(memberReq.getEntityCode());
                member.setEntityName(memberReq.getEntityName());
                member.setJoinedDate(LocalDate.now());
                member.setStatus(PoolMember.MemberStatus.ACTIVE);
                member.setWeight(memberReq.getWeight() != null ? memberReq.getWeight() : BigDecimal.ONE);

                // Fetch actual account balance (bankBalance for mirrors)
                BigDecimal accountBalance = resolveAccountBalance(va);
                member.setCurrentBalance(accountBalance);
                totalBalance = totalBalance.add(accountBalance);

                pool.getMembers().add(member);
            }
            pool.setMemberCount(pool.getMembers().size());
            pool.setTotalBalance(totalBalance);
        }

        pool = poolRepository.save(pool);

        // NEW: Post membership fees for initial members
        for (PoolMember member : pool.getMembers()) {
            postPoolMembershipFee(pool, member);
        }

        auditLog.record("POOL_CREATED", "NotionalPool", pool.getId(),
                "Created pool " + pool.getPoolReference() + " (" + pool.getAllocationMethod() + ")",
                Map.of(
                        "poolReference", pool.getPoolReference(),
                        "poolCurrency", pool.getPoolCurrency(),
                        "allocationMethod", pool.getAllocationMethod() != null ? pool.getAllocationMethod().name() : "CONTRIBUTION_PERCENT",
                        "memberCount", pool.getMemberCount(),
                        "interestRate", pool.getInterestRate()));

        log.info("Created notional pool: {} - {}", pool.getPoolReference(), pool.getPoolName());
        return toResponse(pool);
    }

    @Transactional
    public NotionalPoolDto.Response updatePool(UUID id, NotionalPoolDto.UpdateRequest request) {
        NotionalPool pool = poolRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Notional pool not found: " + id));

        if (request.getPoolName() != null) pool.setPoolName(request.getPoolName());
        if (request.getTargetBalance() != null) pool.setTargetBalance(request.getTargetBalance());
        if (request.getInterestRate() != null) pool.setInterestRate(request.getInterestRate());
        if (request.getInterestCalculationMethod() != null) {
            pool.setInterestCalculationMethod(request.getInterestCalculationMethod());
        }
        if (request.getAllocationMethod() != null) {
            pool.setAllocationMethod(request.getAllocationMethod());
        }

        pool = poolRepository.save(pool);

        auditLog.record("POOL_UPDATED", "NotionalPool", pool.getId(),
                "Updated pool " + pool.getPoolReference(),
                Map.of(
                        "poolReference", pool.getPoolReference(),
                        "allocationMethod", pool.getAllocationMethod() != null ? pool.getAllocationMethod().name() : "CONTRIBUTION_PERCENT",
                        "interestRate", pool.getInterestRate(),
                        "targetBalance", pool.getTargetBalance() != null ? pool.getTargetBalance() : BigDecimal.ZERO));

        log.info("Updated notional pool: {} (allocation={})", pool.getPoolReference(), pool.getAllocationMethod());
        return toResponse(pool);
    }

    @Transactional
    public NotionalPoolDto.Response addMember(UUID poolId, NotionalPoolDto.MemberRequest request) {
        NotionalPool pool = poolRepository.findByIdWithMembers(poolId)
                .orElseThrow(() -> new ResourceNotFoundException("Notional pool not found: " + poolId));

        // v1 eligibility: must be a home-bank-held PHYSICAL_MIRROR
        assertPoolEligible(request.getAccountId(), request.getAccountNumber());

        PoolMember member = new PoolMember();
        member.setPool(pool);
        member.setAccountId(request.getAccountId());
        member.setAccountNumber(request.getAccountNumber());
        member.setEntityCode(request.getEntityCode());
        member.setEntityName(request.getEntityName());
        member.setJoinedDate(LocalDate.now());
        member.setStatus(PoolMember.MemberStatus.ACTIVE);
        member.setWeight(request.getWeight() != null ? request.getWeight() : BigDecimal.ONE);

        // Fetch actual account balance (bankBalance for mirrors)
        BigDecimal accountBalance = fetchAccountBalance(request.getAccountId());
        member.setCurrentBalance(accountBalance);

        pool.getMembers().add(member);
        pool.setMemberCount(pool.getMembers().size());

        // Update pool total balance
        BigDecimal currentTotalBalance = pool.getTotalBalance() != null ? pool.getTotalBalance() : BigDecimal.ZERO;
        pool.setTotalBalance(currentTotalBalance.add(accountBalance));

        pool = poolRepository.save(pool);

        // Post membership fee for new member
        postPoolMembershipFee(pool, member);

        mirrorPoolMembership(vaRepository.findById(request.getAccountId()).orElse(null),
                pool.getId(), pool.getPoolReference());

        auditLog.record("POOL_MEMBER_ADDED", "NotionalPool", pool.getId(),
                "Added member " + request.getAccountNumber() + " to pool " + pool.getPoolReference(),
                Map.of(
                        "poolReference", pool.getPoolReference(),
                        "accountId", request.getAccountId(),
                        "accountNumber", request.getAccountNumber(),
                        "entityCode", request.getEntityCode() != null ? request.getEntityCode() : "",
                        "weight", member.getWeight() != null ? member.getWeight() : BigDecimal.ONE));

        log.info("Added member {} to pool {}", request.getAccountNumber(), pool.getPoolReference());
        return toResponse(pool);
    }

    /**
     * Bulk-add members to a pool in O(1) queries instead of one sequential
     * {@code addMember} call per member (which is untenable at ~2000 members/pool).
     *
     * Batch-fetches every candidate account once, applies the same eligibility rules as
     * {@link #addMember}, then persists all new members with a single {@code saveAll}.
     * Every requested accountId is accounted for in the response: either added, or skipped
     * with a reason — nothing is silently dropped.
     */
    @Transactional
    public NotionalPoolDto.BulkAddMembersResponse addMembersBulk(UUID poolId, NotionalPoolDto.BulkAddMembersRequest request) {
        NotionalPool pool = poolRepository.findByIdWithMembers(poolId)
                .orElseThrow(() -> new ResourceNotFoundException("Notional pool not found: " + poolId));

        List<UUID> requestedIds = request.getAccountIds() != null ? request.getAccountIds() : List.of();
        List<NotionalPoolDto.SkippedMember> skipped = new ArrayList<>();
        List<PoolMember> newMembers = new ArrayList<>();

        if (!requestedIds.isEmpty()) {
            // Single batch fetch for every candidate account — never a per-id findById loop.
            Map<UUID, VirtualAccount> accountsById = vaRepository.findAllById(requestedIds).stream()
                    .collect(Collectors.toMap(VirtualAccount::getId, va -> va));

            Set<UUID> existingMemberAccountIds = pool.getMembers().stream()
                    .map(PoolMember::getAccountId)
                    .collect(Collectors.toSet());
            Set<UUID> seenInRequest = new HashSet<>();
            BigDecimal addedBalance = BigDecimal.ZERO;

            for (UUID accountId : requestedIds) {
                if (!seenInRequest.add(accountId)) {
                    skipped.add(new NotionalPoolDto.SkippedMember(accountId, "Duplicate accountId in request"));
                    continue;
                }
                if (existingMemberAccountIds.contains(accountId)) {
                    skipped.add(new NotionalPoolDto.SkippedMember(accountId, "Account is already a member of this pool"));
                    continue;
                }
                VirtualAccount va = accountsById.get(accountId);
                if (va == null) {
                    skipped.add(new NotionalPoolDto.SkippedMember(accountId, "Account not found: " + accountId));
                    continue;
                }
                // Same v1 eligibility rule as addMember: home-bank-held PHYSICAL_MIRROR only.
                if (!va.isPhysicalMirror()) {
                    skipped.add(new NotionalPoolDto.SkippedMember(accountId,
                            "Pool members must be PHYSICAL_MIRROR VAs; account is " + va.getAccountCategory()));
                    continue;
                }
                if (!va.isHomeBankHeld(homeBankProperties.getBic())) {
                    skipped.add(new NotionalPoolDto.SkippedMember(accountId,
                            "Pool members must be home-bank-held (BIC " + homeBankProperties.getBic()
                            + "); account mirrors external bank (BIC " + va.getBankSwift() + ")"));
                    continue;
                }

                PoolMember member = new PoolMember();
                member.setPool(pool);
                member.setAccountId(va.getId());
                member.setAccountNumber(va.getVaNumber());
                member.setEntityCode(va.getOwningEntityCode());
                member.setJoinedDate(LocalDate.now());
                member.setStatus(PoolMember.MemberStatus.ACTIVE);
                member.setWeight(BigDecimal.ONE);

                BigDecimal accountBalance = resolveAccountBalance(va);
                member.setCurrentBalance(accountBalance);
                addedBalance = addedBalance.add(accountBalance);

                newMembers.add(member);
            }

            if (!newMembers.isEmpty()) {
                memberRepository.saveAll(newMembers);
                pool.getMembers().addAll(newMembers);
                pool.setMemberCount(pool.getMembers().size());
                BigDecimal currentTotalBalance = pool.getTotalBalance() != null ? pool.getTotalBalance() : BigDecimal.ZERO;
                pool.setTotalBalance(currentTotalBalance.add(addedBalance));
                poolRepository.save(pool);

                for (PoolMember member : newMembers) {
                    postPoolMembershipFee(pool, member);
                }

                mirrorPoolMembershipBulk(newMembers, accountsById, pool.getId(), pool.getPoolReference());
            }
        }

        auditLog.record("POOL_MEMBERS_BULK_ADDED", "NotionalPool", pool.getId(),
                "Bulk-added " + newMembers.size() + " of " + requestedIds.size() + " requested members to pool " + pool.getPoolReference(),
                Map.of(
                        "poolReference", pool.getPoolReference(),
                        "requested", requestedIds.size(),
                        "added", newMembers.size(),
                        "skipped", skipped.size()));

        log.info("Bulk-added {} of {} requested members to pool {} ({} skipped)",
                newMembers.size(), requestedIds.size(), pool.getPoolReference(), skipped.size());

        NotionalPoolDto.BulkAddMembersResponse response = new NotionalPoolDto.BulkAddMembersResponse();
        response.setAdded(newMembers.size());
        response.setSkipped(skipped);
        return response;
    }

    @Transactional
    public void removeMember(UUID poolId, UUID memberId) {
        NotionalPool pool = poolRepository.findByIdWithMembers(poolId)
                .orElseThrow(() -> new ResourceNotFoundException("Notional pool not found: " + poolId));

        UUID removedAccountId = pool.getMembers().stream()
                .filter(m -> m.getId().equals(memberId))
                .map(PoolMember::getAccountId)
                .findFirst().orElse(null);

        pool.getMembers().removeIf(m -> m.getId().equals(memberId));
        pool.setMemberCount(pool.getMembers().size());
        poolRepository.save(pool);

        if (removedAccountId != null) {
            clearPoolMembershipMirror(removedAccountId);
        }

        auditLog.record("POOL_MEMBER_REMOVED", "NotionalPool", pool.getId(),
                "Removed member " + memberId + " from pool " + pool.getPoolReference(),
                Map.of(
                        "poolReference", pool.getPoolReference(),
                        "memberId", memberId));

        log.info("Removed member {} from pool {}", memberId, pool.getPoolReference());
    }

    /**
     * Mirror real pool membership onto the account's PhysicalAccount row (if it has one).
     * PhysicalAccountController exposes its own independent poolingEnabled/poolId
     * bookkeeping (own dashboard stat tile, own filter query param) that this service
     * never otherwise touches — without this, that display drifts from the PoolMember
     * rows that are the actual source of truth.
     */
    private void mirrorPoolMembership(VirtualAccount va, UUID poolId, String poolReference) {
        if (va == null || va.getPhysicalAccountId() == null) {
            return;
        }
        physicalAccountRepository.findById(va.getPhysicalAccountId()).ifPresent(pa -> {
            pa.markPoolMember(poolId, poolReference);
            physicalAccountRepository.save(pa);
        });
    }

    /** Batch counterpart to {@link #mirrorPoolMembership} for {@link #addMembersBulk}. */
    private void mirrorPoolMembershipBulk(List<PoolMember> newMembers, Map<UUID, VirtualAccount> accountsById,
                                           UUID poolId, String poolReference) {
        List<UUID> physicalAccountIds = newMembers.stream()
                .map(m -> accountsById.get(m.getAccountId()))
                .filter(Objects::nonNull)
                .map(VirtualAccount::getPhysicalAccountId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        if (physicalAccountIds.isEmpty()) {
            return;
        }
        List<PhysicalAccount> accounts = physicalAccountRepository.findAllById(physicalAccountIds);
        accounts.forEach(pa -> pa.markPoolMember(poolId, poolReference));
        physicalAccountRepository.saveAll(accounts);
    }

    /** Counterpart to {@link #mirrorPoolMembership} for {@link #removeMember}. */
    private void clearPoolMembershipMirror(UUID accountId) {
        VirtualAccount va = vaRepository.findById(accountId).orElse(null);
        if (va == null || va.getPhysicalAccountId() == null) {
            return;
        }
        physicalAccountRepository.findById(va.getPhysicalAccountId()).ifPresent(pa -> {
            pa.clearPoolMember();
            physicalAccountRepository.save(pa);
        });
    }

    @Transactional
    public NotionalPoolDto.CalculateInterestResponse calculateInterest(UUID poolId) {
        NotionalPool pool = poolRepository.findByIdWithMembers(poolId)
                .orElseThrow(() -> new ResourceNotFoundException("Notional pool not found: " + poolId));

        // Refresh balances from underlying VAs (bankBalance for mirrors) — single source of truth.
        // Replaces the prior Math.random() placeholder.
        refreshMemberBalances(pool);
        BigDecimal totalBalance = pool.getTotalBalance() != null ? pool.getTotalBalance() : BigDecimal.ZERO;

        // Calculate gross interest
        BigDecimal dailyRate = pool.getInterestRate()
                .divide(BigDecimal.valueOf(36500), 10, RoundingMode.HALF_UP);
        BigDecimal grossInterest = totalBalance.multiply(dailyRate).setScale(2, RoundingMode.HALF_UP);
        BigDecimal netInterest = grossInterest; // After any fees/taxes

        // Dispatch allocation through strategy registry
        List<PoolMember> activeMembers = pool.getMembers().stream()
                .filter(m -> m.getStatus() == PoolMember.MemberStatus.ACTIVE)
                .collect(Collectors.toList());
        AllocationStrategy strategy = allocationStrategies.resolve(pool.getAllocationMethod());
        Map<UUID, BigDecimal> shares = strategy.allocate(pool, activeMembers, totalBalance, netInterest);

        log.info("Allocating {} {} via {} across {} active members of pool {}",
                netInterest, pool.getPoolCurrency(),
                pool.getAllocationMethod() != null ? pool.getAllocationMethod() : "CONTRIBUTION_PERCENT (default)",
                activeMembers.size(), pool.getPoolReference());

        // Apply allocations + build response
        List<NotionalPoolDto.MemberInterestAllocation> allocations = new ArrayList<>();
        for (PoolMember member : activeMembers) {
            BigDecimal memberInterest = shares.getOrDefault(member.getId(), BigDecimal.ZERO);
            BigDecimal prior = member.getInterestAllocation() != null ? member.getInterestAllocation() : BigDecimal.ZERO;
            member.setInterestAllocation(prior.add(memberInterest));

            NotionalPoolDto.MemberInterestAllocation alloc = new NotionalPoolDto.MemberInterestAllocation();
            alloc.setMemberId(member.getId());
            alloc.setEntityCode(member.getEntityCode());
            alloc.setBalance(member.getCurrentBalance());
            alloc.setContributionPercent(member.getContributionPercent());
            alloc.setInterestAllocation(memberInterest);
            allocations.add(alloc);
        }

        pool.setLastCalculationDate(LocalDate.now());
        BigDecimal ytd = pool.getInterestSavingsYtd() != null ? pool.getInterestSavingsYtd() : BigDecimal.ZERO;
        pool.setInterestSavingsYtd(ytd.add(netInterest));
        poolRepository.save(pool);
        
        // NEW: Post pool management fee (monthly)
        postPoolManagementFee(pool, totalBalance, LocalDate.now());

        NotionalPoolDto.CalculateInterestResponse response = new NotionalPoolDto.CalculateInterestResponse();
        response.setPoolId(poolId);
        response.setCalculationDate(LocalDate.now());
        response.setPoolBalance(totalBalance);
        response.setInterestRate(pool.getInterestRate());
        response.setGrossInterest(grossInterest);
        response.setNetInterest(netInterest);
        response.setMemberAllocations(allocations);

        log.info("Calculated interest for pool {}: {} gross, {} net", 
                pool.getPoolReference(), grossInterest, netInterest);
        return response;
    }

    @Transactional
    public void deletePool(UUID id) {
        NotionalPool pool = poolRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Notional pool not found: " + id));
        if (pool.getStatus() == NotionalPool.PoolStatus.ACTIVE) {
            throw new BusinessException("Cannot delete active pool. Close it first.");
        }
        poolRepository.delete(pool);
        log.info("Deleted notional pool: {}", pool.getPoolReference());
    }
    
    // ========================================================================
    // NEW: FEE POSTING INTEGRATION
    // ========================================================================
    
    /**
     * Post pool management fee to Settlement VA.
     * 
     * Fee Schedule:
     * - 0.01% of pool balance (monthly)
     * - Minimum: AED 100
     * 
     * Charged to first member's account.
     */
    private void postPoolManagementFee(NotionalPool pool, BigDecimal poolBalance, LocalDate calculationDate) {
        if (poolBalance == null || poolBalance.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        
        // Calculate monthly management fee
        BigDecimal managementFee = poolBalance.multiply(POOL_MANAGEMENT_FEE_RATE)
            .setScale(2, RoundingMode.HALF_UP);
        
        if (managementFee.compareTo(POOL_MANAGEMENT_FEE_MIN) < 0) {
            managementFee = POOL_MANAGEMENT_FEE_MIN;
        }
        
        // Find first member with accountId to charge
        UUID chargeAccountId = null;
        if (pool.getMembers() != null && !pool.getMembers().isEmpty()) {
            PoolMember firstMember = pool.getMembers().stream()
                .filter(m -> m.getAccountId() != null)
                .findFirst()
                .orElse(null);
            if (firstMember != null) {
                chargeAccountId = firstMember.getAccountId();
            }
        }
        
        if (chargeAccountId == null) {
            log.warn("Cannot post pool management fee - no chargeable account for pool {}", 
                pool.getPoolReference());
            return;
        }
        
        try {
            FeePostingService.FeePostingResult result = feePostingService.postFee(
                chargeAccountId,
                managementFee,
                "POOL_MANAGEMENT_FEE",
                pool.getId(),
                "Notional pool " + pool.getPoolReference() + " management fee - " + calculationDate
            );

            if ("POSTED".equals(result.getStatus())) {
                log.info("Posted pool management fee {} for pool {}",
                    managementFee, pool.getPoolReference());
            } else if ("FAILED".equals(result.getStatus())) {
                log.warn("Pool management fee posting failed for pool {}: {}",
                    pool.getPoolReference(), result.getErrorMessage());
            } else if ("SKIPPED".equals(result.getStatus())) {
                log.debug("Pool management fee posting skipped for pool {}: {}",
                    pool.getPoolReference(), result.getSkipReason());
            }
        } catch (Exception e) {
            // Log but don't rethrow - fee posting failure should not fail interest calculation
            log.error("Failed to post pool management fee for pool {}: {}",
                pool.getPoolReference(), e.getMessage());
        }
    }
    
    /**
     * Post pool membership fee when a new member joins.
     *
     * Fee Schedule:
     * - AED 25 flat per member
     *
     * Note: Fee posting failures are logged but don't fail pool creation.
     * The fee posting is non-critical and uses a separate transaction context.
     */
    private void postPoolMembershipFee(NotionalPool pool, PoolMember member) {
        if (member.getAccountId() == null) {
            log.warn("Cannot post membership fee - member account ID is null for {}",
                member.getAccountNumber());
            return;
        }

        try {
            FeePostingService.FeePostingResult result = feePostingService.postFee(
                member.getAccountId(),
                POOL_MEMBERSHIP_FEE,
                "POOL_MEMBERSHIP_FEE",
                member.getId(),
                "Pool membership fee - " + pool.getPoolReference() + " - " + member.getEntityCode()
            );

            if ("POSTED".equals(result.getStatus())) {
                log.debug("Posted pool membership fee for member {} in pool {}",
                    member.getEntityCode(), pool.getPoolReference());
            } else if ("FAILED".equals(result.getStatus())) {
                log.warn("Membership fee posting failed for member {}: {}",
                    member.getEntityCode(), result.getErrorMessage());
            } else if ("SKIPPED".equals(result.getStatus())) {
                log.debug("Membership fee posting skipped for member {}: {}",
                    member.getEntityCode(), result.getSkipReason());
            }
        } catch (Exception e) {
            // Log but don't rethrow - fee posting failure should not fail pool creation
            log.error("Failed to post pool membership fee for member {}: {}",
                member.getEntityCode(), e.getMessage());
        }
    }

    /**
     * Fetch the pool-relevant balance for a member account.
     *
     * Pool members must be home-bank-held PHYSICAL_MIRROR VAs (v1 rule). For those,
     * the legally-held balance is {@code bankBalance} (the home bank's record of the
     * mirrored physical account), NOT {@code currentBalance} (which is the operational
     * VA balance and is zero for mirrors).
     *
     * For any other category (legacy / pre-refactor data), falls back to currentBalance
     * so existing pools don't break, but {@link #assertPoolEligible} prevents new
     * ineligible members from being added.
     */
    private BigDecimal fetchAccountBalance(UUID accountId) {
        if (accountId == null) {
            return BigDecimal.ZERO;
        }
        try {
            VirtualAccount account = vaRepository.findById(accountId).orElse(null);
            return resolveAccountBalance(account);
        } catch (Exception e) {
            log.warn("Failed to fetch balance for account {}: {}", accountId, e.getMessage());
        }
        return BigDecimal.ZERO;
    }

    /**
     * Same balance resolution as {@link #fetchAccountBalance(UUID)}, but operates on an
     * already-fetched {@link VirtualAccount} so callers that batch-fetch accounts (avoiding
     * N+1 lookups) can reuse it.
     */
    private BigDecimal resolveAccountBalance(VirtualAccount account) {
        if (account == null) {
            return BigDecimal.ZERO;
        }
        if (account.isPhysicalMirror()) {
            return account.getBankBalance() != null ? account.getBankBalance() : BigDecimal.ZERO;
        }
        return account.getCurrentBalance() != null ? account.getCurrentBalance() : BigDecimal.ZERO;
    }

    /**
     * Pool eligibility guard (v1).
     *
     * Members must be PHYSICAL_MIRROR VAs whose underlying physical account is held
     * at the home bank — i.e. funds the bank legally holds and can reallocate interest
     * on. External shadows (mirrors at other banks) are sweep sources only.
     *
     * Throws {@link BusinessException} on first violation.
     */
    private void assertPoolEligible(UUID accountId, String accountNumberForError) {
        if (accountId == null) {
            throw new BusinessException("Pool member accountId is required");
        }
        VirtualAccount va = vaRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Pool member VA not found: " + accountId));
        assertPoolEligible(va, accountNumberForError);
    }

    /**
     * Same eligibility checks as {@link #assertPoolEligible(UUID, String)}, but operates on an
     * already-fetched {@link VirtualAccount} so callers validating many members at once can
     * batch-fetch the accounts (one {@code findAllById}) instead of looking each one up
     * individually (N+1).
     */
    private void assertPoolEligible(VirtualAccount va, String accountNumberForError) {
        if (!va.isPhysicalMirror()) {
            throw new BusinessException(
                    "Pool members must be PHYSICAL_MIRROR VAs. " + accountNumberForError
                    + " is " + va.getAccountCategory());
        }
        if (!va.isHomeBankHeld(homeBankProperties.getBic())) {
            throw new BusinessException(
                    "Pool members must be home-bank-held (BIC " + homeBankProperties.getBic()
                    + "). " + accountNumberForError + " mirrors an external bank (BIC "
                    + va.getBankSwift() + ") — use a cross-bank sweep rule instead.");
        }
    }

    /**
     * Looks up a member candidate's account from a batch-fetched map, preserving the same
     * null/not-found errors {@link #assertPoolEligible(UUID, String)} raises for an individual
     * lookup.
     */
    private VirtualAccount requireAccountForPool(UUID accountId, String accountNumberForError,
                                                   Map<UUID, VirtualAccount> accountsById) {
        if (accountId == null) {
            throw new BusinessException("Pool member accountId is required");
        }
        VirtualAccount va = accountsById.get(accountId);
        if (va == null) {
            throw new ResourceNotFoundException("Pool member VA not found: " + accountId);
        }
        return va;
    }

    private NotionalPoolDto.Response toResponse(NotionalPool pool) {
        NotionalPoolDto.Response dto = new NotionalPoolDto.Response();
        dto.setId(pool.getId());
        dto.setPoolReference(pool.getPoolReference());
        dto.setPoolName(pool.getPoolName());
        dto.setPoolCurrency(pool.getPoolCurrency());
        dto.setCorporateId(pool.getCorporateId());
        dto.setProgramId(pool.getProgramId());
        dto.setTargetBalance(pool.getTargetBalance());
        dto.setInterestRate(pool.getInterestRate());
        dto.setInterestCalculationMethod(pool.getInterestCalculationMethod());
        dto.setAllocationMethod(pool.getAllocationMethod());
        dto.setTotalBalance(pool.getTotalBalance());
        dto.setInterestSavingsYtd(pool.getInterestSavingsYtd());
        dto.setMemberCount(pool.getMemberCount());
        dto.setStatus(pool.getStatus());
        dto.setEffectiveFrom(pool.getEffectiveFrom());
        dto.setEffectiveTo(pool.getEffectiveTo());
        dto.setLastCalculationDate(pool.getLastCalculationDate());
        dto.setCreatedAt(pool.getCreatedAt());
        dto.setUpdatedAt(pool.getUpdatedAt());

        if (pool.getMembers() != null) {
            dto.setMembers(pool.getMembers().stream()
                    .map(this::toMemberResponse)
                    .collect(Collectors.toList()));
        }
        return dto;
    }

    private NotionalPoolDto.MemberResponse toMemberResponse(PoolMember member) {
        NotionalPoolDto.MemberResponse dto = new NotionalPoolDto.MemberResponse();
        dto.setId(member.getId());
        dto.setAccountId(member.getAccountId());
        dto.setAccountNumber(member.getAccountNumber());
        dto.setEntityCode(member.getEntityCode());
        dto.setEntityName(member.getEntityName());
        dto.setCurrentBalance(member.getCurrentBalance());
        dto.setContributionPercent(member.getContributionPercent());
        dto.setInterestAllocation(member.getInterestAllocation());
        dto.setWeight(member.getWeight());
        dto.setStatus(member.getStatus());
        dto.setJoinedDate(member.getJoinedDate());
        return dto;
    }
}