package com.bank.vam.service.treasury.allocation;

import com.bank.vam.entity.treasury.NotionalPool;
import com.bank.vam.entity.treasury.PoolMember;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * Strategy for allocating pooled interest across pool members.
 *
 * Implementations are stateless and chosen by {@link NotionalPool#getAllocationMethod()}.
 * The pool service is responsible for refreshing member balances before invoking
 * allocate(); strategies operate on the snapshot they're given.
 */
public interface AllocationStrategy {

    /**
     * Identifies which {@link NotionalPool.AllocationMethod} this strategy handles.
     */
    NotionalPool.AllocationMethod method();

    /**
     * Allocate {@code netInterest} across the given members.
     *
     * @param pool the parent pool (for currency, base rate, etc.)
     * @param members ACTIVE members with current balances populated
     * @param totalBalance sum of member balances (for convenience; equals sum of currentBalance)
     * @param netInterest the interest pot to distribute (after fees/taxes)
     * @return immutable map of memberId → allocated interest amount. Must sum to {@code netInterest}
     *         within rounding tolerance.
     */
    Map<UUID, BigDecimal> allocate(NotionalPool pool,
                                   java.util.List<PoolMember> members,
                                   BigDecimal totalBalance,
                                   BigDecimal netInterest);
}
