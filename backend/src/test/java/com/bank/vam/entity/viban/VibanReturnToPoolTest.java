package com.bank.vam.entity.viban;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reuse cool-off counts from returnScheduledAt, so returning a VIBAN to its
 * pool must leave that at the later of "when it was due back" and "now": a payer
 * may still pay up to the validity they were given, and the number must not be
 * reissued while such a payment could still arrive.
 */
class VibanReturnToPoolTest {

    private Viban issued(LocalDateTime dueBack) {
        Viban v = new Viban();
        v.setViban("GB00TEST");
        v.setStatus(Viban.STATUS_ACTIVE);
        v.setVirtualAccountId(UUID.randomUUID());
        v.setReturnScheduledAt(dueBack);
        return v;
    }

    @Test
    void returnedOnScheduleCoolsOffFromNow() {
        Viban v = issued(LocalDateTime.now().minusMinutes(2));
        LocalDateTime before = LocalDateTime.now();
        v.returnToPool();
        assertThat(v.getReturnScheduledAt()).isAfterOrEqualTo(before);
        // COOLING, not RETURNED: it must not count as available stock until the cool-off passes.
        assertThat(v.getStatus()).isEqualTo(Viban.STATUS_COOLING);
        assertThat(v.getVirtualAccountId()).isNull();
    }

    @Test
    void returnedEarlyCoolsOffFromThePromisedValidity() {
        LocalDateTime promised = LocalDateTime.now().plusDays(3);
        Viban v = issued(promised);
        v.returnToPool();
        assertThat(v.getReturnScheduledAt()).isEqualTo(promised);
    }

    @Test
    void permanentVibanReturnedByHandCoolsOffFromNow() {
        Viban v = issued(null);
        LocalDateTime before = LocalDateTime.now();
        v.returnToPool();
        assertThat(v.getReturnScheduledAt()).isAfterOrEqualTo(before);
    }
}
