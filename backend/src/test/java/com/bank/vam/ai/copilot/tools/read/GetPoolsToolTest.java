package com.bank.vam.ai.copilot.tools.read;

import com.bank.vam.ai.copilot.tools.ToolContext;
import com.bank.vam.ai.copilot.tools.ToolResult;
import com.bank.vam.dto.treasury.NotionalPoolDto;
import com.bank.vam.entity.credit.InterestConfiguration;
import com.bank.vam.entity.treasury.NotionalPool;
import com.bank.vam.entity.treasury.PoolMember;
import com.bank.vam.repository.credit.InterestConfigurationRepository;
import com.bank.vam.repository.treasury.NotionalPoolRepository;
import com.bank.vam.service.treasury.PoolRunService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Notional pooling 5.2: the copilot counts and lists active members only, and reports rates, header and run figures. */
class GetPoolsToolTest {

    @Test
    @SuppressWarnings("unchecked")
    void activeMembersOnly_withHeaderRatesAndRunFigures() {
        NotionalPoolRepository pools = mock(NotionalPoolRepository.class);
        InterestConfigurationRepository configs = mock(InterestConfigurationRepository.class);
        PoolRunService runs = mock(PoolRunService.class);

        NotionalPool pool = new NotionalPool();
        pool.setId(UUID.randomUUID());
        pool.setPoolReference("POOL-BRTO-AED");
        pool.setPoolCurrency("AED");
        pool.setMemberCount(3); // the stored count still includes the member who left
        PoolMember header = member(pool, "BRTO0000001", PoolMember.MemberStatus.ACTIVE);
        member(pool, "BRTO0000002", PoolMember.MemberStatus.ACTIVE);
        member(pool, "BRTO0000003", PoolMember.MemberStatus.INACTIVE);
        pool.setHeaderAccountId(header.getAccountId());
        InterestConfiguration config = new InterestConfiguration();
        config.setId(UUID.randomUUID());
        config.setCreditBaseRate(new BigDecimal("3.20"));
        config.setDebitBaseRate(new BigDecimal("5.20"));
        config.setDayCountConvention("ACT/365");
        pool.setInterestConfigurationId(config.getId());
        NotionalPoolDto.Figures figures = new NotionalPoolDto.Figures(LocalDate.now().minusDays(1), LocalDate.now().minusDays(1),
                new BigDecimal("14640000"), new BigDecimal("1283.51"), new BigDecimal("12835.10"), new BigDecimal("42.00"),
                new BigDecimal("42.00"));

        when(pools.findAll()).thenReturn(List.of(pool));
        when(pools.findByIdWithMembers(pool.getId())).thenReturn(Optional.of(pool));
        when(configs.findById(config.getId())).thenReturn(Optional.of(config));
        when(runs.figures(any())).thenReturn(Map.of(pool.getId(), figures));
        GetPoolsTool tool = new GetPoolsTool(pools, configs, runs);
        ToolContext ctx = new ToolContext(null, "user", "AED", "UAE", null);

        Map<String, Object> listed = ((List<Map<String, Object>>) tool.execute(ctx, Map.of()).data().get("pools")).get(0);
        assertThat(listed.get("memberCount")).isEqualTo(2);
        assertThat(listed.get("headerAccountNumber")).isEqualTo("BRTO0000001");
        assertThat(listed.get("figures")).isEqualTo(figures);
        assertThat((Map<String, Object>) listed.get("rates")).containsEntry("dayCount", "ACT/365");
        assertThat(listed).doesNotContainKeys("interestSavingsYtd", "totalBalance");

        ToolResult detail = tool.execute(ctx, Map.of("id", pool.getId().toString()));
        List<Map<String, Object>> members = (List<Map<String, Object>>)
                ((List<Map<String, Object>>) detail.data().get("pools")).get(0).get("members");
        assertThat(members).extracting(m -> m.get("accountNumber")).containsExactly("BRTO0000001", "BRTO0000002");
        assertThat(detail.summary()).contains("2 active member(s)");
    }

    private static PoolMember member(NotionalPool pool, String number, PoolMember.MemberStatus status) {
        PoolMember m = new PoolMember();
        m.setPool(pool);
        m.setAccountId(UUID.randomUUID());
        m.setAccountNumber(number);
        m.setStatus(status);
        pool.getMembers().add(m);
        return m;
    }
}
