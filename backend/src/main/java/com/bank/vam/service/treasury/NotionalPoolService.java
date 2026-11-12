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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotionalPoolService {

    private final NotionalPoolRepository poolRepository;
    private final PoolMemberRepository memberRepository;
    private final VirtualAccountRepository vaRepository;  // For fetching account balances
    private final com.bank.vam.repository.PhysicalAccountRepository physicalAccountRepository;
    private final HomeBankProperties homeBankProperties;
    private final AuditLogService auditLog;
    private final com.bank.vam.config.MarketProfileProperties marketProfile;

    // No pool fees in v1 (spec D9/R12): the per-calculation management fee and the joining fee are gone.

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
        // Members who left keep their row (history, R3) but no longer count toward the pool.
        List<PoolMember> active = activeMembers(pool);
        pool.setMemberCount(active.size());

        // Batch-fetch all member accounts in one query instead of one findById per member (N+1).
        List<UUID> accountIds = active.stream()
                .map(PoolMember::getAccountId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        Map<UUID, VirtualAccount> accountsById = accountIds.isEmpty() ? Map.of()
                : vaRepository.findAllById(accountIds).stream()
                        .collect(Collectors.toMap(VirtualAccount::getId, va -> va));

        BigDecimal totalBalance = BigDecimal.ZERO;
        for (PoolMember member : active) {
            BigDecimal balance = resolveAccountBalance(accountsById.get(member.getAccountId()));
            member.setCurrentBalance(balance);
            totalBalance = totalBalance.add(balance);
        }
        pool.setTotalBalance(totalBalance);

        // Update contribution percentages
        if (totalBalance.compareTo(BigDecimal.ZERO) > 0) {
            for (PoolMember member : active) {
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
        pool.setPoolReference(String.format("NP-%04d", poolRepository.nextPoolNumber()));
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
        Map<UUID, VirtualAccount> accountsById = Map.of();
        if (request.getMembers() != null) {
            // Batch-fetch all candidate accounts in one query instead of one findById
            // per member (N+1) — used for both eligibility checks and balance lookup.
            List<UUID> accountIds = request.getMembers().stream()
                    .map(NotionalPoolDto.MemberRequest::getAccountId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
            accountsById = vaRepository.findAllById(accountIds).stream()
                    .collect(Collectors.toMap(VirtualAccount::getId, va -> va));
            Set<UUID> pooled = accountsInAPool(accountIds);
            UUID entityId = null;

            for (NotionalPoolDto.MemberRequest memberReq : request.getMembers()) {
                VirtualAccount va = requireAccountForPool(memberReq.getAccountId(), memberReq.getAccountNumber(), accountsById);
                entityId = admit(pool, va, memberReq.getAccountNumber(), entityId, pooled);

                PoolMember member = new PoolMember();
                member.setPool(pool);
                member.setAccountId(memberReq.getAccountId());
                // From the account, not the request (a request by id alone failed on the NOT NULL account number;
                // client-sent codes stored truncated UUIDs).
                member.setAccountNumber(va.getVaNumber());
                member.setEntityCode(va.getOwningEntityCode() != null ? va.getOwningEntityCode() : memberReq.getEntityCode());
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
        mirrorPoolMembershipBulk(pool.getMembers(), accountsById, pool.getId(), pool.getPoolReference());

        auditLog.record("POOL_CREATED", "NotionalPool", pool.getId(), pool.getCorporateId(),
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

        auditLog.record("POOL_UPDATED", "NotionalPool", pool.getId(), pool.getCorporateId(),
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

        assertOpen(pool);
        if (request.getAccountId() == null) {
            throw new BusinessException("Pool member accountId is required");
        }
        VirtualAccount va = vaRepository.findById(request.getAccountId())
                .orElseThrow(() -> new ResourceNotFoundException("Pool member VA not found: " + request.getAccountId()));
        admit(pool, va, request.getAccountNumber(), poolEntityId(pool), accountsInAPool(List.of(va.getId())));

        PoolMember member = new PoolMember();
        member.setPool(pool);
        member.setAccountId(request.getAccountId());
        member.setAccountNumber(va.getVaNumber()); // from the account, as on create and bulk add
        member.setEntityCode(va.getOwningEntityCode() != null ? va.getOwningEntityCode() : request.getEntityCode());
        member.setEntityName(request.getEntityName());
        member.setJoinedDate(LocalDate.now());
        member.setStatus(PoolMember.MemberStatus.ACTIVE);
        member.setWeight(request.getWeight() != null ? request.getWeight() : BigDecimal.ONE);

        // Fetch actual account balance (bankBalance for mirrors)
        BigDecimal accountBalance = resolveAccountBalance(va);
        member.setCurrentBalance(accountBalance);

        pool.getMembers().add(member);
        pool.setMemberCount(activeMembers(pool).size());

        // Update pool total balance
        BigDecimal currentTotalBalance = pool.getTotalBalance() != null ? pool.getTotalBalance() : BigDecimal.ZERO;
        pool.setTotalBalance(currentTotalBalance.add(accountBalance));

        pool = poolRepository.save(pool);

        mirrorPoolMembership(va, pool.getId(), pool.getPoolReference());

        auditLog.record("POOL_MEMBER_ADDED", "NotionalPool", pool.getId(), pool.getCorporateId(),
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
        assertOpen(pool);

        List<UUID> requestedIds = request.getAccountIds() != null ? request.getAccountIds() : List.of();
        List<NotionalPoolDto.SkippedMember> skipped = new ArrayList<>();
        List<PoolMember> newMembers = new ArrayList<>();

        if (!requestedIds.isEmpty()) {
            // Single batch fetch for every candidate account — never a per-id findById loop.
            Map<UUID, VirtualAccount> accountsById = vaRepository.findAllById(requestedIds).stream()
                    .collect(Collectors.toMap(VirtualAccount::getId, va -> va));

            Set<UUID> pooled = accountsInAPool(requestedIds);
            UUID entityId = poolEntityId(pool);
            Set<UUID> seenInRequest = new HashSet<>();
            BigDecimal addedBalance = BigDecimal.ZERO;

            for (UUID accountId : requestedIds) {
                if (!seenInRequest.add(accountId)) {
                    skipped.add(new NotionalPoolDto.SkippedMember(accountId, "Duplicate accountId in request"));
                    continue;
                }
                VirtualAccount va = accountsById.get(accountId);
                if (va == null) {
                    skipped.add(new NotionalPoolDto.SkippedMember(accountId, "Account not found: " + accountId));
                    continue;
                }
                // Same rules as addMember; a refusal skips this account instead of failing the batch.
                try {
                    entityId = admit(pool, va, va.getVaNumber(), entityId, pooled);
                } catch (BusinessException e) {
                    skipped.add(new NotionalPoolDto.SkippedMember(accountId, e.getMessage()));
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
                pool.setMemberCount(activeMembers(pool).size());
                BigDecimal currentTotalBalance = pool.getTotalBalance() != null ? pool.getTotalBalance() : BigDecimal.ZERO;
                pool.setTotalBalance(currentTotalBalance.add(addedBalance));
                poolRepository.save(pool);

                mirrorPoolMembershipBulk(newMembers, accountsById, pool.getId(), pool.getPoolReference());
            }
        }

        auditLog.record("POOL_MEMBERS_BULK_ADDED", "NotionalPool", pool.getId(), pool.getCorporateId(),
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

        PoolMember member = activeMembers(pool).stream()
                .filter(m -> memberId.equals(m.getId()))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No active member " + memberId + " in pool " + pool.getPoolReference()));

        // Leaving is dated, never a delete (R3): the row stays as history.
        leave(member);
        refreshMemberBalances(pool);

        auditLog.record("POOL_MEMBER_REMOVED", "NotionalPool", pool.getId(), pool.getCorporateId(),
                "Removed member " + memberId + " from pool " + pool.getPoolReference(),
                Map.of(
                        "poolReference", pool.getPoolReference(),
                        "memberId", memberId));

        log.info("Removed member {} from pool {}", memberId, pool.getPoolReference());
    }

    /**
     * Mirror real pool membership onto the account's PhysicalAccount row (if it has one).
     * The PhysicalAccount poolingEnabled/poolId flags feed PhysicalAccountController's dashboard
     * tile and filter; this service is their only writer, so they follow the PoolMember rows.
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

    /**
     * Retired (plan 1.3): each click added one day's interest on the current total to the YTD figure and the
     * members' allocations, so a second click double-counted. Pool interest comes from the daily runs (Phase 3).
     */
    public NotionalPoolDto.CalculateInterestResponse calculateInterest(UUID poolId) {
        throw new BusinessException("Manual pool interest calculation is retired: it is replaced by daily runs.");
    }

    /** ACTIVE → SUSPENDED (R5). Members stay; a suspended pool can still be closed. */
    @Transactional
    public NotionalPoolDto.Response suspendPool(UUID id) {
        NotionalPool pool = poolRepository.findByIdWithMembers(id)
                .orElseThrow(() -> new ResourceNotFoundException("Notional pool not found: " + id));
        if (pool.getStatus() != NotionalPool.PoolStatus.ACTIVE) {
            throw new BusinessException("Only an ACTIVE pool can be suspended; " + pool.getPoolReference() + " is " + pool.getStatus());
        }
        return changeStatus(pool, NotionalPool.PoolStatus.SUSPENDED, "POOL_SUSPENDED");
    }

    /**
     * ACTIVE or SUSPENDED → CLOSED (R5). Every member leaves (dated, rows kept) so its account can join another pool.
     * ponytail: nothing is accrued yet to post on close — the month-end posting (Phase 4) adds that step here.
     */
    @Transactional
    public NotionalPoolDto.Response closePool(UUID id) {
        NotionalPool pool = poolRepository.findByIdWithMembers(id)
                .orElseThrow(() -> new ResourceNotFoundException("Notional pool not found: " + id));
        if (pool.getStatus() != NotionalPool.PoolStatus.ACTIVE && pool.getStatus() != NotionalPool.PoolStatus.SUSPENDED) {
            throw new BusinessException("Only an ACTIVE or SUSPENDED pool can be closed; " + pool.getPoolReference() + " is " + pool.getStatus());
        }
        activeMembers(pool).forEach(this::leave);
        pool.setEffectiveTo(LocalDate.now());
        refreshMemberBalances(pool);
        return changeStatus(pool, NotionalPool.PoolStatus.CLOSED, "POOL_CLOSED");
    }

    private NotionalPoolDto.Response changeStatus(NotionalPool pool, NotionalPool.PoolStatus to, String action) {
        NotionalPool.PoolStatus from = pool.getStatus();
        pool.setStatus(to);
        pool = poolRepository.save(pool);
        auditLog.record(action, "NotionalPool", pool.getId(), pool.getCorporateId(),
                "Pool " + pool.getPoolReference() + " " + from + " -> " + to,
                Map.of("poolReference", pool.getPoolReference(), "from", from.name(), "to", to.name()));
        log.info("Pool {} {} -> {}", pool.getPoolReference(), from, to);
        return toResponse(pool);
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

    /** A member leaves: dated, status INACTIVE, its physical account no longer flagged as pooled. */
    private void leave(PoolMember member) {
        member.setStatus(PoolMember.MemberStatus.INACTIVE);
        member.setLeftDate(LocalDate.now());
        member.setUpdatedAt(LocalDateTime.now());
        clearPoolMembershipMirror(member.getAccountId());
    }

    private static List<PoolMember> activeMembers(NotionalPool pool) {
        return pool.getMembers() == null ? List.of() : pool.getMembers().stream()
                .filter(m -> m.getStatus() == PoolMember.MemberStatus.ACTIVE)
                .collect(Collectors.toList());
    }

    private static void assertOpen(NotionalPool pool) {
        if (pool.getStatus() == NotionalPool.PoolStatus.CLOSED) {
            throw new BusinessException("Pool " + pool.getPoolReference() + " is closed");
        }
    }

    /** Accounts among {@code accountIds} that are already an active member of some pool (one pool per account, R3). */
    private Set<UUID> accountsInAPool(Collection<UUID> accountIds) {
        if (accountIds.isEmpty()) {
            return new HashSet<>();
        }
        return memberRepository.findByAccountIdInAndStatus(accountIds, PoolMember.MemberStatus.ACTIVE).stream()
                .map(PoolMember::getAccountId)
                .collect(Collectors.toCollection(HashSet::new));
    }

    /**
     * The pool's legal entity: the owning entity of its active members (R4).
     * ponytail: derived from the members until notional_pools gets legal_entity_id (Phase 2); the first member sets it.
     */
    private UUID poolEntityId(NotionalPool pool) {
        List<UUID> ids = activeMembers(pool).stream().map(PoolMember::getAccountId).filter(Objects::nonNull).toList();
        if (ids.isEmpty()) {
            return null;
        }
        return vaRepository.findAllById(ids).stream()
                .map(VirtualAccount::getOwningEntityId)
                .filter(Objects::nonNull)
                .findFirst().orElse(null);
    }

    /**
     * Membership rules (R1, R3, R4), the one place every add path goes through: an active home-bank
     * PHYSICAL_MIRROR, in the pool's currency, of the pool's corporate and legal entity, in no other pool.
     * A pool without a corporate (e.g. the simulator's) takes its first member's. Adds the account to
     * {@code pooled} so a repeat in the same request is refused too.
     *
     * @return the pool's legal entity after this member (the member's, when the pool had none yet)
     */
    private UUID admit(NotionalPool pool, VirtualAccount va, String label, UUID poolEntityId, Set<UUID> pooled) {
        if (label == null) label = va.getVaNumber(); // the request may name the account by id only
        assertPoolEligible(va, label);
        if (!va.getCurrencyCode().equalsIgnoreCase(pool.getPoolCurrency())) {
            throw new BusinessException("Pool " + pool.getPoolReference() + " is in " + pool.getPoolCurrency()
                    + "; " + label + " is in " + va.getCurrencyCode());
        }
        if (pool.getCorporateId() != null && !pool.getCorporateId().equals(va.getCorporateId())) {
            throw new BusinessException(label + " belongs to another corporate than pool " + pool.getPoolReference());
        }
        if (va.getOwningEntityId() == null) {
            throw new BusinessException(label + " has no owning legal entity; a pool is for one legal entity");
        }
        if (poolEntityId != null && !poolEntityId.equals(va.getOwningEntityId())) {
            throw new BusinessException(label + " belongs to another legal entity (" + va.getOwningEntityCode()
                    + ") than the members of pool " + pool.getPoolReference());
        }
        if (!pooled.add(va.getId())) {
            throw new BusinessException(label + " is already a member of a notional pool");
        }
        if (pool.getCorporateId() == null) {
            pool.setCorporateId(va.getCorporateId());
        }
        return va.getOwningEntityId();
    }

    /**
     * The pool-relevant balance of a member account: {@code bankBalance} for a PHYSICAL_MIRROR (the home bank's
     * record of the mirrored account; its {@code currentBalance} is zero), else {@code currentBalance} so legacy
     * members of other categories don't break.
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
     * Pool eligibility guard (v1): an ACTIVE PHYSICAL_MIRROR whose physical account is held at the home bank —
     * funds the bank legally holds and can reallocate interest on. External shadows are sweep sources only.
     */
    private void assertPoolEligible(VirtualAccount va, String accountNumberForError) {
        if (va.getStatus() != VirtualAccount.VaStatus.ACTIVE) {
            throw new BusinessException("Pool members must be active accounts. " + accountNumberForError
                    + " is " + va.getStatus());
        }
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

    /** Looks up a member candidate's account from a batch-fetched map. */
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
        dto.setLeftDate(member.getLeftDate());
        return dto;
    }
}