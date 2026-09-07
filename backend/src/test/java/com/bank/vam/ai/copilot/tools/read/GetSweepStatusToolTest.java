package com.bank.vam.ai.copilot.tools.read;

import com.bank.vam.ai.copilot.tools.ToolContext;
import com.bank.vam.ai.copilot.tools.ToolResult;
import com.bank.vam.entity.treasury.SweepRule;
import com.bank.vam.repository.treasury.SweepRuleRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Regression guard: {@code get_sweep_status} previously called {@code findAll()}
 * unconditionally, returning every corporate's sweep rules to any caller — the
 * exact leak the MCP exposure plan's Phase 0 scope audit was checking for.
 */
class GetSweepStatusToolTest {

    private SweepRule rule(UUID corporateId, SweepRule.SweepStatus status) {
        SweepRule r = new SweepRule();
        r.setId(UUID.randomUUID());
        r.setCorporateId(corporateId);
        r.setStatus(status);
        return r;
    }

    @Test
    void scopedCaller_onlySeesOwnCorporatesRules() {
        SweepRuleRepository repository = mock(SweepRuleRepository.class);
        UUID corporateId = UUID.randomUUID();
        when(repository.findByCorporateId(corporateId))
                .thenReturn(List.of(rule(corporateId, SweepRule.SweepStatus.ACTIVE)));

        GetSweepStatusTool tool = new GetSweepStatusTool(repository);
        ToolContext scoped = new ToolContext(corporateId, "user", "USD", "US", null);

        ToolResult result = tool.execute(scoped, Map.of());

        assertThat(result.ok()).isTrue();
        assertThat(result.data().get("totalRules")).isEqualTo(1);
        verify(repository).findByCorporateId(corporateId);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void unscopedCaller_seesAllRules_inAppCopilotBehaviourUnchanged() {
        SweepRuleRepository repository = mock(SweepRuleRepository.class);
        when(repository.findAll())
                .thenReturn(List.of(rule(UUID.randomUUID(), SweepRule.SweepStatus.ACTIVE)));

        GetSweepStatusTool tool = new GetSweepStatusTool(repository);
        ToolContext unscoped = new ToolContext(null, "user", "USD", "US", null);

        ToolResult result = tool.execute(unscoped, Map.of());

        assertThat(result.ok()).isTrue();
        verify(repository).findAll();
    }
}
