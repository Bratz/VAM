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
 * Equal-split allocation: each active member receives {@code netInterest / count}.
 * Last member absorbs the rounding residual.
 */
@Component
public class EqualStrategy implements AllocationStrategy {

    @Override
    public NotionalPool.AllocationMethod method() {
        return NotionalPool.AllocationMethod.EQUAL;
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
        BigDecimal perMember = netInterest.divide(BigDecimal.valueOf(members.size()), 2, RoundingMode.HALF_UP);
        BigDecimal allocated = BigDecimal.ZERO;
        for (int i = 0; i < members.size(); i++) {
            PoolMember m = members.get(i);
            BigDecimal share = (i == members.size() - 1) ? netInterest.subtract(allocated) : perMember;
            allocated = allocated.add(share);
            out.put(m.getId(), share);
        }
        return out;
    }
}
