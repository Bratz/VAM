package com.bank.vam.entity;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link PhysicalAccount#markPoolMember}/{@link PhysicalAccount#clearPoolMember}
 * and {@link PhysicalAccount#markSweepParticipant}/{@link PhysicalAccount#clearSweepParticipant}.
 *
 * These exist because NotionalPoolService/SweepService (the real enrollment engines) and
 * PhysicalAccountController's independent pooling/sweep-enabled bookkeeping used to be two
 * unsynchronized systems — enrolling a real PoolMember never touched poolingEnabled/poolId,
 * so the Physical Accounts dashboard's "Pooling Enabled" stat and ?poolingEnabled= filter
 * silently disagreed with actual pool membership. This is the regression guard for keeping
 * them in sync: unlike enablePooling()/enableSweepAsParticipant(), these must NOT re-check
 * poolingEligible/sweepEligible — that gate is a separate, unrelated onboarding flag, and by
 * the time NotionalPoolService/SweepService call these, their own real eligibility check
 * (VA category + home-bank BIC) has already run.
 */
class PhysicalAccountFlagSyncTest {

    private PhysicalAccount account() {
        // poolingEligible/sweepEligible intentionally left false — proves markPoolMember/
        // markSweepParticipant don't consult them (unlike enablePooling/enableSweepAsParticipant).
        return PhysicalAccount.builder()
                .poolingEligible(false)
                .sweepEligible(false)
                .build();
    }

    @Test
    void markPoolMember_setsFlagsWithoutEligibilityGate() {
        PhysicalAccount pa = account();
        UUID poolId = UUID.randomUUID();

        pa.markPoolMember(poolId, "POOL-REF-1");

        assertThat(pa.getPoolingEnabled()).isTrue();
        assertThat(pa.getPoolId()).isEqualTo(poolId);
        assertThat(pa.getPoolReference()).isEqualTo("POOL-REF-1");
    }

    @Test
    void clearPoolMember_resetsAllThreeFields() {
        PhysicalAccount pa = account();
        pa.markPoolMember(UUID.randomUUID(), "POOL-REF-1");

        pa.clearPoolMember();

        assertThat(pa.getPoolingEnabled()).isFalse();
        assertThat(pa.getPoolId()).isNull();
        assertThat(pa.getPoolReference()).isNull();
    }

    @Test
    void markSweepParticipant_setsRoleAndRuleWithoutEligibilityGate() {
        PhysicalAccount pa = account();
        UUID ruleId = UUID.randomUUID();

        pa.markSweepParticipant(ruleId, PhysicalAccount.SweepRole.HEADER);

        assertThat(pa.getSweepEnabled()).isTrue();
        assertThat(pa.getSweepRole()).isEqualTo(PhysicalAccount.SweepRole.HEADER);
        assertThat(pa.getSweepRuleId()).isEqualTo(ruleId);
    }

    @Test
    void clearSweepParticipant_resetsAllThreeFields() {
        PhysicalAccount pa = account();
        pa.markSweepParticipant(UUID.randomUUID(), PhysicalAccount.SweepRole.PARTICIPANT);

        pa.clearSweepParticipant();

        assertThat(pa.getSweepEnabled()).isFalse();
        assertThat(pa.getSweepRole()).isNull();
        assertThat(pa.getSweepRuleId()).isNull();
    }
}
