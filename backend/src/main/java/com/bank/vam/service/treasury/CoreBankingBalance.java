package com.bank.vam.service.treasury;

import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.VirtualAccount.BalanceDataSource;
import com.bank.vam.repository.PhysicalAccountRepository;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A mirror whose bank balance comes from core banking is refreshed from its physical account every 15 minutes
 * ({@code CoreBankingAdapter}). So when the app itself moves such a mirror's bank balance (a collection, a payment,
 * a sweep, an FX settlement), the physical account must move by the same amount, or the refresh puts the old balance
 * back. Call this right after {@code applyShadowMovement} or {@code mirrorBankBalance}.
 */
public final class CoreBankingBalance {

    private CoreBankingBalance() {
    }

    public static void follow(PhysicalAccountRepository banks, VirtualAccount mirror, BigDecimal delta) {
        if (mirror.getBalanceDataSource() != BalanceDataSource.CORE_BANKING || mirror.getLinkedPhysicalAccountId() == null
                || delta == null || delta.signum() == 0) {
            return;
        }
        banks.findById(mirror.getLinkedPhysicalAccountId()).ifPresent(p -> {
            p.setCurrentBalance(p.getCurrentBalance().add(delta));
            p.setAvailableBalance(p.getAvailableBalance().add(delta));
            p.setLedgerBalance(p.getLedgerBalance().add(delta));
            p.setBalanceAsOf(LocalDateTime.now());
            banks.save(p);
        });
    }
}
