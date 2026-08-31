package com.bank.vam.service.treasury.allocation;

import com.bank.vam.entity.treasury.NotionalPool;
import com.bank.vam.entity.treasury.PoolMember;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pro-rata interest allocation by member balance contribution.
 *
 * Replicates the legacy {@code NotionalPoolService.calculateInterest} behaviour.
 * Last member absorbs the rounding residual so the allocations sum exactly to
 * {@code netInterest}.
 */
@Component
public class ContributionPercentStrategy implements AllocationStrategy {

    @Override
    public NotionalPool.AllocationMethod method() {
        return NotionalPool.AllocationMethod.CONTRIBUTION_PERCENT;
    }

    @Override
    public Map<UUID, BigDecimal> allocate(NotionalPool pool,
                                          List<PoolMember> members,
                                          BigDecimal totalBalance,
                                          BigDecimal netInterest) {
        Map<UUID, BigDecimal> out = new HashMap<>();
        if (members.isEmpty() || totalBalance == null || totalBalance.signum() == 0) {
            return out;
        }
        BigDecimal allocated = BigDecimal.ZERO;
        for (int i = 0; i < members.size(); i++) {
            PoolMember m = members.get(i);
            BigDecimal share;
            if (i == members.size() - 1) {
                share = netInterest.subtract(allocated);
            } else {
                BigDecimal balance = m.getCurrentBalance() != null ? m.getCurrentBalance() : BigDecimal.ZERO;
                share = balance.divide(totalBalance, 10, RoundingMode.HALF_UP)
                        .multiply(netInterest)
                        .setScale(2, RoundingMode.HALF_UP);
                allocated = allocated.add(share);
            }
            out.put(m.getId(), share);
        }
        return out;
    }
}
