package com.bank.vam.service.simulator;

import com.bank.vam.config.HomeBankProperties;
import com.bank.vam.config.MarketProfileProperties;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.credit.InterestConfiguration;
import com.bank.vam.entity.simulator.SimulatorScenario;
import com.bank.vam.entity.treasury.NotionalPool;
import com.bank.vam.entity.treasury.PoolMember;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.repository.PhysicalAccountRepository;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.credit.InterestConfigurationRepository;
import com.bank.vam.repository.simulator.SimulatorScenarioRepository;
import com.bank.vam.repository.treasury.NotionalPoolRepository;
import com.bank.vam.repository.treasury.PoolInterestCalculationRepository;
import com.bank.vam.repository.treasury.PoolMemberRepository;
import com.bank.vam.service.audit.AuditLogService;
import com.bank.vam.service.credit.InterestConfigurationService;
import com.bank.vam.service.treasury.NotionalPoolService;
import com.bank.vam.service.treasury.PoolPostingService;
import com.bank.vam.service.treasury.PoolRunService;
import com.bank.vam.service.treasury.SweepService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Notional pooling 5.4: the simulator's "activate" goes through the live pool service, so the pool it creates passes
 * the live checks — a header member, one legal entity, rates in a POOL interest config — or the activation says why not.
 */
class SimulatorPoolActivationTest {

    private static final String HOME_BIC = "EBILAEADXXX";
    private static final UUID CORP = UUID.randomUUID(), ENTITY = UUID.randomUUID();

    private final SimulatorScenarioRepository scenarios = mock(SimulatorScenarioRepository.class);
    private final VirtualAccountRepository vas = mock(VirtualAccountRepository.class);
    private final NotionalPoolRepository pools = mock(NotionalPoolRepository.class);
    private final PoolMemberRepository members = mock(PoolMemberRepository.class);
    private final InterestConfigurationRepository configs = mock(InterestConfigurationRepository.class);
    private final Map<UUID, VirtualAccount> accounts = new HashMap<>();
    private final Map<UUID, InterestConfiguration> savedConfigs = new HashMap<>();
    private final List<NotionalPool> savedPools = new ArrayList<>();
    private SimulatorActivationService activation;

    @BeforeEach
    void setUp() {
        HomeBankProperties home = new HomeBankProperties();
        home.setBic(HOME_BIC);
        NotionalPoolService poolService = new NotionalPoolService(pools, members, vas, mock(PhysicalAccountRepository.class),
                home, mock(AuditLogService.class), mock(MarketProfileProperties.class), new InterestConfigurationService(configs),
                configs, mock(PoolInterestCalculationRepository.class), mock(PoolPostingService.class), mock(PoolRunService.class));
        activation = new SimulatorActivationService(scenarios, vas, mock(SweepService.class), poolService, pools, new ObjectMapper());

        when(pools.nextPoolNumber()).thenReturn(21L);
        when(pools.save(any())).thenAnswer(i -> {
            NotionalPool p = i.getArgument(0);
            if (p.getId() == null) p.setId(UUID.randomUUID());
            if (!savedPools.contains(p)) savedPools.add(p);
            return p;
        });
        when(configs.save(any())).thenAnswer(i -> {
            InterestConfiguration c = i.getArgument(0);
            if (c.getId() == null) c.setId(UUID.randomUUID());
            savedConfigs.put(c.getId(), c);
            return c;
        });
        when(configs.findById(any())).thenAnswer(i -> Optional.ofNullable(savedConfigs.get(i.getArgument(0))));
        when(vas.findAllById(any())).thenAnswer(i -> {
            List<VirtualAccount> out = new ArrayList<>();
            for (Object id : (Collection<?>) i.getArgument(0)) if (accounts.containsKey(id)) out.add(accounts.get(id));
            return out;
        });
        when(members.findByAccountIdInAndStatus(anyCollection(), eq(PoolMember.MemberStatus.ACTIVE))).thenReturn(List.of());
        when(scenarios.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void activateCreatesAPoolWithAHeaderOneEntityAndAPoolRateConfig() {
        VirtualAccount a = account(ENTITY), b = account(ENTITY);
        SimulatorScenario s = scenario(a, b, "3.5");

        activation.activate(s.getId(), null, null, "tester");

        assertThat(s.getStatus()).isEqualTo(SimulatorScenario.ScenarioStatus.ACTIVATED);
        assertThat(savedPools).hasSize(1);
        NotionalPool pool = savedPools.get(0);
        assertThat(pool.getPoolReference()).isEqualTo("NP-0021");
        assertThat(pool.getHeaderAccountId()).isEqualTo(a.getId());
        assertThat(pool.getLegalEntityId()).isEqualTo(ENTITY);
        assertThat(pool.getCorporateId()).isEqualTo(CORP);
        InterestConfiguration config = savedConfigs.get(pool.getInterestConfigurationId());
        assertThat(config.getTargetType()).isEqualTo(InterestConfiguration.TargetType.POOL);
        assertThat(config.getCreditBaseRate()).isEqualByComparingTo("3.5");
        assertThat(config.getDayCountConvention()).isEqualTo("ACT/365"); // AED
    }

    @Test
    void membersOfTwoEntities_activationSaysWhichPoolAndWhy() {
        SimulatorScenario s = scenario(account(ENTITY), account(UUID.randomUUID()), "3.5");

        assertThatThrownBy(() -> activation.activate(s.getId(), null, null, "tester"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Notional pool 'Sim pool' can't be activated")
                .hasMessageContaining("another legal entity");
    }

    @Test
    void aPoolWithoutARateIsRefused() {
        SimulatorScenario s = scenario(account(ENTITY), account(ENTITY), null);

        assertThatThrownBy(() -> activation.activate(s.getId(), null, null, "tester"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("has no rate");
    }

    private SimulatorScenario scenario(VirtualAccount a, VirtualAccount b, String ratePct) {
        String payload = """
                {"shadows":[{"localId":"s1","physicalAccountId":"%s"},{"localId":"s2","physicalAccountId":"%s"}],
                 "rules":[],
                 "pools":[{"poolName":"Sim pool","poolCurrency":"AED",%s"memberLocalIds":["s1","s2"]}]}
                """.formatted(a.getLinkedPhysicalAccountId(), b.getLinkedPhysicalAccountId(),
                ratePct == null ? "" : "\"poolRatePct\":" + ratePct + ",");
        SimulatorScenario s = SimulatorScenario.builder().status(SimulatorScenario.ScenarioStatus.READY).proposedPayload(payload).build();
        s.setId(UUID.randomUUID());
        when(scenarios.findById(s.getId())).thenReturn(Optional.of(s));
        return s;
    }

    private VirtualAccount account(UUID entityId) {
        VirtualAccount va = VirtualAccount.builder()
                .accountCategory(VirtualAccount.AccountCategory.PHYSICAL_MIRROR)
                .status(VirtualAccount.VaStatus.ACTIVE)
                .currencyCode("AED")
                .bankSwift(HOME_BIC)
                .corporateId(CORP)
                .owningEntityId(entityId)
                .vaNumber("VA-" + UUID.randomUUID().toString().substring(0, 8))
                .linkedPhysicalAccountId(UUID.randomUUID())
                .bankBalance(new BigDecimal("1000"))
                .build();
        va.setId(UUID.randomUUID());
        accounts.put(va.getId(), va);
        when(vas.findByLinkedPhysicalAccountId(va.getLinkedPhysicalAccountId())).thenReturn(Optional.of(va));
        return va;
    }
}
