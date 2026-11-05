package com.bank.vam.fx;

import com.bank.vam.config.MarketProfileProperties;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.repository.VirtualAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * A corporate's home currency: the currency of its oldest ROOT account, or the market default when
 * it has none. Corporates have no explicit base-currency field, and they differ (a GBP corporate is
 * not an AED one), so the market profile alone would be wrong for most of them.
 *
 * <p>ponytail: derived, not configured. A per-corporate setting is the upgrade if the derivation is
 * ever wrong for a real corporate.
 */
@Component
@RequiredArgsConstructor
public class HomeCurrency {

    private final VirtualAccountRepository accounts;
    private final MarketProfileProperties marketProfile;

    public String of(UUID corporateId) {
        return accounts.findRootAccount(corporateId).map(VirtualAccount::getCurrencyCode)
                .orElseGet(marketProfile::getDefaultCurrency);
    }
}
