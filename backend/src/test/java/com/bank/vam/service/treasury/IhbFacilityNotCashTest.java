package com.bank.vam.service.treasury;

import com.bank.vam.entity.VirtualAccount;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A participant's borrowing facility must stay out of availableBalance.
 *
 * IhbUnifiedService used to write getIhbAvailableBalance() -- currentBalance + effectiveCreditLimit
 * -- into the availableBalance column after every deposit, withdrawal, transfer and interest
 * posting. Every other consumer reads that column as cash, so the facility was indistinguishable
 * from money: a ZERO_BALANCE sweep sized itself off it and moved 1,000,000.96 out of an account
 * holding 0.96. The IHB paths now use credit()/debit() like every other path, which keep
 * availableBalance in step with cash, and the facility is consulted only by canWithdrawIhb().
 */
class IhbFacilityNotCashTest {

    private static VirtualAccount participant(String cash, String facility) {
        VirtualAccount va = VirtualAccount.builder()
                .vaNumber("IHB-TEST-AED-0001")
                .currentBalance(new BigDecimal(cash))
                .availableBalance(new BigDecimal(cash))
                .effectiveCreditLimit(new BigDecimal(facility))
                .build();
        return va;
    }

    @Test
    void creditKeepsAvailableEqualToCashNotCashPlusFacility() {
        VirtualAccount va = participant("0.96", "1000000");
        va.credit(new BigDecimal("100"));
        assertThat(va.getCurrentBalance()).isEqualByComparingTo("100.96");
        assertThat(va.getAvailableBalance()).isEqualByComparingTo("100.96");
    }

    @Test
    void debitLeavesTheOverdraftVisibleAsNegativeCash() {
        VirtualAccount va = participant("0.96", "1000000");
        va.debit(new BigDecimal("1000"));
        assertThat(va.getCurrentBalance()).isEqualByComparingTo("-999.04");
        // The old code would have written 0.96 + 1,000,000 - 1,000 here.
        assertThat(va.getAvailableBalance()).isEqualByComparingTo("-999.04");
    }

    @Test
    void theFacilityIsStillDrawableThroughItsOwnGate() {
        // canWithdrawIhb is the reference model's "credit limit consulted at posting time":
        // the limit permits the draw without ever being part of a balance.
        VirtualAccount va = participant("0.96", "1000000");
        assertThat(va.canWithdrawIhb(new BigDecimal("500000"))).isTrue();
        assertThat(va.canWithdrawIhb(new BigDecimal("2000000"))).isFalse();
    }

    @Test
    void sweepsSeeOnlyTheCashEvenWithAFacility() {
        // The two fixes meeting: available no longer carries the facility, so the sweep sizes on
        // 0.96 whichever way it is computed.
        VirtualAccount va = participant("0.96", "1000000");
        assertThat(SweepService.sweepableCash(va)).isEqualByComparingTo("0.96");
    }
}
