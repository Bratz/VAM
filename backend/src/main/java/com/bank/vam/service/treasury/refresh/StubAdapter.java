package com.bank.vam.service.treasury.refresh;

import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.VirtualAccount.BalanceDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Set;

/**
 * Fallback adapter for rails that aren't wired in v1 (SWIFT, Open Banking,
 * Host-to-Host, API aggregators, manual). No bank is contacted, so every
 * fetch fails with "not connected" and the existing balance is left as it is;
 * real adapters arrive in v3/v4 (SWIFT) and later phases.
 *
 * Registered once and serves all unhandled {@link BalanceDataSource} values
 * via {@link BalanceRefreshService}'s fallback path.
 */
@Slf4j
@Component
public class StubAdapter implements ShadowBalanceAdapter {

    /** All sources this stub claims when CORE_BANKING is not applicable. */
    public static final Set<BalanceDataSource> HANDLED = EnumSet.of(
            BalanceDataSource.SWIFT_MT940,
            BalanceDataSource.SWIFT_MT942,
            BalanceDataSource.OPEN_BANKING,
            BalanceDataSource.HOST_TO_HOST,
            BalanceDataSource.API,
            BalanceDataSource.MANUAL);

    /**
     * Stub serves as the default fallback in {@link BalanceRefreshService};
     * the source() value is informational only — the registry does not key on it.
     */
    @Override
    public BalanceDataSource source() {
        return BalanceDataSource.MANUAL;
    }

    @Override
    public RefreshResult fetch(VirtualAccount shadow) {
        return RefreshResult.fail("Not connected: no adapter for balance source "
                + shadow.getBalanceDataSource());
    }
}
