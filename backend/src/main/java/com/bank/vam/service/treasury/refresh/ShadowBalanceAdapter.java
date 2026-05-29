package com.bank.vam.service.treasury.refresh;

import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.VirtualAccount.BalanceDataSource;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * SPI for fetching a fresh balance for a PHYSICAL_MIRROR shadow account.
 *
 * One implementation per {@link BalanceDataSource}. Adapters are plain beans;
 * {@link BalanceRefreshService} resolves them by {@link #source()}.
 *
 * v1 ships {@code CoreBankingAdapter} (reads from the linked PhysicalAccount)
 * and {@code StubAdapter} (fallback for SWIFT/Open Banking/H2H/API rails not
 * yet wired). Real rail adapters arrive in v3/v4.
 */
public interface ShadowBalanceAdapter {

    /** The {@link BalanceDataSource} this adapter handles. */
    BalanceDataSource source();

    /**
     * Fetch the current bank balance for the given shadow.
     * Implementations must not mutate the VA; the orchestrator persists the result.
     */
    RefreshResult fetch(VirtualAccount shadow);

    /**
     * Result of a refresh attempt. The orchestrator translates {@code success=false}
     * into {@link VirtualAccount.BalanceRefreshStatus#FAILED} and leaves the existing
     * balance untouched.
     */
    record RefreshResult(boolean success,
                         BigDecimal bankBalance,
                         BigDecimal bankAvailableBalance,
                         LocalDateTime asOf,
                         String errorMessage) {

        public static RefreshResult ok(BigDecimal balance, BigDecimal available, LocalDateTime asOf) {
            return new RefreshResult(true, balance, available, asOf, null);
        }

        public static RefreshResult fail(String message) {
            return new RefreshResult(false, null, null, null, message);
        }
    }
}
