package com.bank.vam.service.treasury;

import com.bank.vam.entity.VirtualAccount;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guard for a live incident: on 2026-09-15 a ZERO_BALANCE sweep moved 1,000,000.96 out
 * of IHB-MNC-UAE-DUBAI-AED-0001, an account holding 0.96, leaving it at -1,000,000.
 *
 * <p>Nothing was wrong with the sweep arithmetic or its guard. IhbUnifiedService persists
 * getIhbAvailableBalance() -- currentBalance + effectiveCreditLimit -- into the availableBalance
 * column, so the sweep sized itself off cash PLUS the participant's borrowing facility, and
 * canCommitOutflow then checked the amount against that same inflated figure and passed. The sweep
 * swept the credit line.
 *
 * <p>A cash concentration sweep must move cash. Moving borrowed money draws on the facility to fund
 * the header and then charges debit interest on the drawdown.
 */
class SweepableCashTest {

    private static VirtualAccount account(String current, String available) {
        VirtualAccount va = VirtualAccount.builder()
                .vaNumber("TEST-VA")
                .currentBalance(current == null ? null : new BigDecimal(current))
                .availableBalance(available == null ? null : new BigDecimal(available))
                .build();
        return va;
    }

    @Test
    void doesNotSweepTheCreditFacility() {
        // The exact live shape: 0.96 of cash, a 1,000,000 facility folded into availableBalance.
        VirtualAccount va = account("0.96", "1000000.96");
        assertThat(SweepService.sweepableCash(va)).isEqualByComparingTo("0.96");
    }

    @Test
    void anOverdrawnParticipantSweepsNothing() {
        // -7,213.10 of cash against 992,786.90 of remaining facility: borrowing more to fund the
        // header is never a sweep.
        VirtualAccount va = account("-7213.10", "992786.90");
        assertThat(SweepService.sweepableCash(va)).isEqualByComparingTo("0");
    }

    @Test
    void aFullyDrawnParticipantSweepsNothing() {
        VirtualAccount va = account("-1000000", "0");
        assertThat(SweepService.sweepableCash(va)).isEqualByComparingTo("0");
    }

    @Test
    void holdsAndCommitmentsStillReduceWhatCanBeSwept() {
        // availableBalance BELOW cash is its real job — a hold must still win.
        VirtualAccount va = account("5000", "1200");
        assertThat(SweepService.sweepableCash(va)).isEqualByComparingTo("1200");
    }

    @Test
    void anOrdinaryAccountSweepsItsWholeBalance() {
        VirtualAccount va = account("100.01", "100.01");
        assertThat(SweepService.sweepableCash(va)).isEqualByComparingTo("100.01");
    }

    @Test
    void nullBalancesAreTreatedAsZeroRatherThanThrowing() {
        assertThat(SweepService.sweepableCash(account(null, "1000"))).isEqualByComparingTo("0");
        assertThat(SweepService.sweepableCash(account("500", null))).isEqualByComparingTo("0");
    }
}
