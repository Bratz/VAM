package com.bank.vam.service.simulator;

import com.bank.vam.dto.simulator.PoolMembershipDto;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.treasury.PoolMember;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.treasury.PoolMemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Resolves notional-pool membership for the Simulator interest-yield Pool
 * basket (R2). Path: physical account → its live PHYSICAL_MIRROR Shadow VA →
 * `pool_members` → `notional_pools`. Read-only; no live-table writes.
 *
 * <p>Separate simulator service/controller (not folded into the shared
 * PhysicalAccountController) — same zero-blast-radius pattern as Phase-2
 * tariffs / Phase-4 source-quality; L9 (reuse, don't bloat the shared path).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SimulatorPoolService {

    private final VirtualAccountRepository vaRepository;
    private final PoolMemberRepository poolMemberRepository;

    @Transactional(readOnly = true)
    public List<PoolMembershipDto> getPoolMembership(
            Collection<UUID> physicalAccountIds) {
        List<PoolMembershipDto> out = new ArrayList<>();
        if (physicalAccountIds == null || physicalAccountIds.isEmpty()) {
            return out;
        }
        for (UUID physId : physicalAccountIds) {
            vaRepository
                .findByLinkedPhysicalAccountIdAndAccountCategory(
                    physId, VirtualAccount.AccountCategory.PHYSICAL_MIRROR)
                .ifPresent(va -> {
                    List<PoolMember> members =
                        poolMemberRepository.findByAccountId(va.getId());
                    if (!members.isEmpty()
                            && members.get(0).getPool() != null) {
                        var pool = members.get(0).getPool();
                        out.add(PoolMembershipDto.builder()
                            .physicalAccountId(physId)
                            .poolReference(pool.getPoolReference())
                            .poolCurrency(pool.getPoolCurrency())
                            .interestRate(pool.getInterestRate())
                            .build());
                    }
                });
        }
        return out;
    }
}
