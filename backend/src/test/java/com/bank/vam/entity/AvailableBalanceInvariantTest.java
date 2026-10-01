package com.bank.vam.entity;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * availableBalance = currentBalance - heldBalance.
 *
 * Every mutator that touches availableBalance must preserve this, because it is the only thing that
 * makes the column reconstructible. It stopped being true twice: IhbUnifiedService persisted
 * currentBalance + effectiveCreditLimit into it, so a sweep read a borrowing facility as cash; and
 * sweeps called commitOutflow(), which subtracted from available without ever releasing, grinding
 * it down by 27.9 billion across ten accounts. Both are fixed, V28 realigned the five rows that had
 * drifted, and this pins the rule the repair depends on.
 */
class AvailableBalanceInvariantTest {

    private static VirtualAccount account(String current, String held) {
        VirtualAccount va = VirtualAccount.builder()
                .vaNumber("INV-TEST")
                .currentBalance(new BigDecimal(current))
                .availableBalance(new BigDecimal(current).subtract(new BigDecimal(held)))
                .heldBalance(new BigDecimal(held))
                .build();
        return va;
    }

    private static void assertInvariant(VirtualAccount va) {
        BigDecimal held = va.getHeldBalance() != null ? va.getHeldBalance() : BigDecimal.ZERO;
        assertThat(va.getAvailableBalance())
                .as("available should equal current - held")
                .isEqualByComparingTo(va.getCurrentBalance().subtract(held));
    }

    @Test
    void creditPreservesIt() {
        VirtualAccount va = account("100", "0");
        va.credit(new BigDecimal("250"));
        assertInvariant(va);
        assertThat(va.getCurrentBalance()).isEqualByComparingTo("350");
    }

    @Test
    void debitPreservesIt() {
        VirtualAccount va = account("100", "0");
        va.debit(new BigDecimal("250"));
        assertInvariant(va);
        assertThat(va.getCurrentBalance()).isEqualByComparingTo("-150");
    }

    @Test
    void holdPreservesIt() {
        VirtualAccount va = account("1000", "0");
        va.hold(new BigDecimal("400"));
        assertInvariant(va);
        assertThat(va.getAvailableBalance()).isEqualByComparingTo("600");
    }

    @Test
    void releaseHoldPreservesIt() {
        VirtualAccount va = account("1000", "400");
        va.releaseHold(new BigDecimal("400"));
        assertInvariant(va);
        assertThat(va.getAvailableBalance()).isEqualByComparingTo("1000");
    }

    @Test
    void aSequenceOfOperationsStillPreservesIt() {
        VirtualAccount va = account("500", "0");
        va.credit(new BigDecimal("100"));
        va.hold(new BigDecimal("250"));
        va.debit(new BigDecimal("75"));
        va.releaseHold(new BigDecimal("50"));
        va.credit(new BigDecimal("10"));
        assertInvariant(va);
        assertThat(va.getCurrentBalance()).isEqualByComparingTo("535");
        assertThat(va.getHeldBalance()).isEqualByComparingTo("200");
        assertThat(va.getAvailableBalance()).isEqualByComparingTo("335");
    }
}
