package com.bank.vam.entity.viban;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/** A paid invoice's VIBAN still takes money in (held as unapplied cash); closed ones refuse. */
class VibanReceiveFundsTest {

    private static Viban viban(String status, LocalDateTime validUntil) {
        Viban v = Viban.builder().status(status).validUntil(validUntil).build();
        return v;
    }

    @Test
    void aPaidVibanStillReceivesFundsButNoLongerAcceptsThemAsPayment() {
        Viban paid = viban(Viban.STATUS_PAID, LocalDateTime.now().plusDays(10));
        assertFalse(paid.canAcceptPayment());
        assertTrue(paid.canReceiveFunds());
    }

    @Test
    void closedVibansRefuse() {
        assertFalse(viban(Viban.STATUS_CANCELLED, null).canReceiveFunds());
        assertFalse(viban(Viban.STATUS_SUSPENDED, null).canReceiveFunds());
        assertFalse(viban(Viban.STATUS_EXPIRED, null).canReceiveFunds());
        // Paid but past its validity: expired, so refused.
        assertFalse(viban(Viban.STATUS_PAID, LocalDateTime.now().minusDays(1)).canReceiveFunds());
    }

    @Test
    void anActiveVibanReceivesAsBefore() {
        assertTrue(viban(Viban.STATUS_ACTIVE, null).canReceiveFunds());
    }
}
