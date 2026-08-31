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
 * Weighted allocation: each member receives {@code (weight / sumWeights) * netInterest}.
 * Members with null or non-positive weight are treated as weight = 1.
 * Last member absorbs the rounding residual.
 */
@Component
public class WeightedStrategy implements AllocationStrategy {

    @Override
    public NotionalPool.AllocationMethod method() {
        return NotionalPool.AllocationMethod.WEIGHTED;
    }

    @Override
    public Map<UUID, BigDecimal> allocate(NotionalPool pool,
                                          List<PoolMember> members,
                                          BigDecimal totalBalance,
                                          BigDecimal netInterest) {
        Map<UUID, BigDecimal> out = new HashMap<>();
        if (members.isEmpty()) {
            return out;
        }
        BigDecimal sumWeights = BigDecimal.ZERO;
        for (PoolMember m : members) {
            sumWeights = sumWeights.add(effectiveWeight(m));
        }
        if (sumWeights.signum() == 0) {
            return out;
        }
        BigDecimal allocated = BigDecimal.ZERO;
        for (int i = 0; i < members.size(); i++) {
            PoolMember m = members.get(i);
            BigDecimal share;
            if (i == members.size() - 1) {
                share = netInterest.subtract(allocated);
            } else {
                share = effectiveWeight(m)
                        .divide(sumWeights, 10, RoundingMode.HALF_UP)
                        .multiply(netInterest)
                        .setScale(2, RoundingMode.HALF_UP);
                allocated = allocated.add(share);
            }
            out.put(m.getId(), share);
        }
        return out;
    }

    private BigDecimal effectiveWeight(PoolMember m) {
        BigDecimal w = m.getWeight();
        return (w == null || w.signum() <= 0) ? BigDecimal.ONE : w;
    }
}
