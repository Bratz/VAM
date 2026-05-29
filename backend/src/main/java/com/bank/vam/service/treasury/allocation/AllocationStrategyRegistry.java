package com.bank.vam.service.treasury.allocation;

import com.bank.vam.entity.treasury.NotionalPool;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Looks up the {@link AllocationStrategy} for a given {@link NotionalPool.AllocationMethod}.
 * Spring discovers strategy beans by component scan; the registry indexes them by method().
 * Falls back to CONTRIBUTION_PERCENT if a requested method has no bound strategy
 * (e.g. OPTIMIZED_WHT before its phase ships).
 */
@Component
public class AllocationStrategyRegistry {

    private final Map<NotionalPool.AllocationMethod, AllocationStrategy> byMethod =
            new EnumMap<>(NotionalPool.AllocationMethod.class);
    private final AllocationStrategy fallback;

    public AllocationStrategyRegistry(List<AllocationStrategy> strategies,
                                      ContributionPercentStrategy fallback) {
        for (AllocationStrategy s : strategies) {
            byMethod.put(s.method(), s);
        }
        this.fallback = fallback;
    }

    public AllocationStrategy resolve(NotionalPool.AllocationMethod method) {
        if (method == null) {
            return fallback;
        }
        return byMethod.getOrDefault(method, fallback);
    }
}
