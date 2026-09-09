package com.bank.vam.service.treasury;

import com.bank.vam.dto.treasury.SweepRuleDto;
import com.bank.vam.entity.treasury.SweepRule;
import com.bank.vam.repository.treasury.SweepRuleRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression guard for the "Total Swept" inflation bug: {@link ScheduledJobService}'s
 * per-frequency jobs (executeRealTimeSweeps every 5 minutes, executeDailySweeps at
 * 6pm, etc.) must only pick up rules configured for that exact frequency.
 * Before this fix, {@link SweepRuleDto.RunSweepsRequest} had no frequency field at
 * all, so every scheduled job ran every active rule regardless of its configured
 * frequency — a DAILY rule was actually swept ~289x/day (288 times from the
 * 5-minute job alone, plus once more from the daily job), inflating cumulative
 * "Total Swept" figures into the billions after weeks of uptime.
 */
class SweepServiceFrequencyFilterTest {

    private SweepRule rule(SweepRule.SweepFrequency frequency) {
        SweepRule r = new SweepRule();
        r.setId(UUID.randomUUID());
        r.setStatus(SweepRule.SweepStatus.ACTIVE);
        r.setFrequency(frequency);
        return r;
    }

    private SweepService serviceWith(SweepRuleRepository ruleRepository) {
        // Only ruleRepository is touched by resolveRulesForRun(); every other
        // collaborator can stay null for this unit test.
        return new SweepService(
                ruleRepository, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null);
    }

    @Test
    void scheduledRequestWithFrequencyOnlyReturnsMatchingRules() {
        SweepRule realTime = rule(SweepRule.SweepFrequency.REAL_TIME);
        SweepRule daily = rule(SweepRule.SweepFrequency.DAILY);
        SweepRule weekly = rule(SweepRule.SweepFrequency.WEEKLY);

        SweepRuleRepository ruleRepository = mock(SweepRuleRepository.class);
        when(ruleRepository.findAllActiveWithSources()).thenReturn(List.of(realTime, daily, weekly));

        SweepService service = serviceWith(ruleRepository);

        SweepRuleDto.RunSweepsRequest request = new SweepRuleDto.RunSweepsRequest();
        request.setFrequency(SweepRule.SweepFrequency.DAILY);

        assertThat(service.resolveRulesForRun(request)).containsExactly(daily);
    }

    @Test
    void manualRequestWithNoFrequencySetStillReturnsEveryActiveRule() {
        SweepRule realTime = rule(SweepRule.SweepFrequency.REAL_TIME);
        SweepRule daily = rule(SweepRule.SweepFrequency.DAILY);

        SweepRuleRepository ruleRepository = mock(SweepRuleRepository.class);
        when(ruleRepository.findAllActiveWithSources()).thenReturn(List.of(realTime, daily));

        SweepService service = serviceWith(ruleRepository);

        // No frequency set — this is the manual "Run Sweeps" button's request
        // shape, which must keep running everything, unchanged from before
        // this fix.
        SweepRuleDto.RunSweepsRequest request = new SweepRuleDto.RunSweepsRequest();

        assertThat(service.resolveRulesForRun(request)).containsExactlyInAnyOrder(realTime, daily);
    }

    @Test
    void explicitRuleIdsIgnoreFrequencyEntirely() {
        SweepRule daily = rule(SweepRule.SweepFrequency.DAILY);
        daily.setId(UUID.randomUUID());

        SweepRuleRepository ruleRepository = mock(SweepRuleRepository.class);
        when(ruleRepository.findAllByIdWithSources(List.of(daily.getId()))).thenReturn(List.of(daily));

        SweepService service = serviceWith(ruleRepository);

        SweepRuleDto.RunSweepsRequest request = new SweepRuleDto.RunSweepsRequest();
        request.setRuleIds(List.of(daily.getId()));
        // Even if a frequency were also set, an explicit rule pick wins.
        request.setFrequency(SweepRule.SweepFrequency.REAL_TIME);

        assertThat(service.resolveRulesForRun(request)).containsExactly(daily);
    }
}
