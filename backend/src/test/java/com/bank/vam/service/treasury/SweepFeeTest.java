package com.bank.vam.service.treasury;

import com.bank.vam.dto.treasury.SweepRuleDto;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.hierarchy.LegalEntity;
import com.bank.vam.entity.treasury.SweepRule;
import com.bank.vam.entity.treasury.SweepRuleSource;
import com.bank.vam.repository.TransactionRepository;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.hierarchy.LegalEntityRepository;
import com.bank.vam.repository.treasury.SweepExecutionRepository;
import com.bank.vam.repository.treasury.SweepRuleRepository;
import com.bank.vam.repository.treasury.SweepRuleSourceRepository;
import com.bank.vam.service.TransactionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * The sweep fee is charged only for a sweep that moved money, once per execution (spec R16). An IHB current account is
 * not swept: its balance is a position with treasury, and sweeping it credited the same amount straight back through the
 * intercompany leg, so every 5-minute run swept it again and charged AED 5 (MNC Dubai).
 */
class SweepFeeTest {

    private final SweepRuleRepository rules = mock(SweepRuleRepository.class);
    private final SweepExecutionRepository executions = mock(SweepExecutionRepository.class);
    private final VirtualAccountRepository vas = mock(VirtualAccountRepository.class);
    private final LegalEntityRepository entities = mock(LegalEntityRepository.class);
    private final FeePostingService fees = mock(FeePostingService.class);
    private final TransactionService transactions = mock(TransactionService.class);
    private final SweepService service = new SweepService(rules, executions, mock(SweepRuleSourceRepository.class), vas,
        mock(TransactionRepository.class), entities, fees, mock(IhbFxService.class),
        mock(com.bank.vam.config.MarketProfileProperties.class), mock(PlatformTransactionManager.class),
        mock(SweepRunBootstrap.class), mock(com.bank.vam.repository.treasury.SweepRunRepository.class),
        mock(com.bank.vam.config.HomeBankProperties.class),
        mock(com.bank.vam.repository.treasury.ExternalMandateRepository.class),
        mock(com.bank.vam.repository.PhysicalAccountRepository.class), transactions);

    private final LegalEntity subsidiary = entity(false);
    private final LegalEntity treasury = entity(true);
    private final VirtualAccount target = account(treasury, "TC-SETTLE-AED", "0");

    @BeforeEach
    void setUp() {
        service.initTxTemplates();
        when(executions.save(any())).thenAnswer(i -> i.getArgument(0));
        when(entities.findAllById(any())).thenReturn(List.of(subsidiary, treasury));
    }

    private static LegalEntity entity(boolean lends) {
        LegalEntity e = new LegalEntity();
        e.setId(UUID.randomUUID());
        e.setIsTreasuryCenter(lends);
        return e;
    }

    private VirtualAccount account(LegalEntity owner, String number, String balance) {
        VirtualAccount va = VirtualAccount.builder().vaNumber(number).currencyCode("AED").owningEntityId(owner.getId())
            .currentBalance(new BigDecimal(balance)).availableBalance(new BigDecimal(balance)).heldBalance(BigDecimal.ZERO)
            .accountCategory(VirtualAccount.AccountCategory.TRANSACTION).build();
        va.setId(UUID.randomUUID());
        when(vas.findById(va.getId())).thenReturn(Optional.of(va));
        return va;
    }

    private SweepRuleDto.RunSweepsResponse run(VirtualAccount source) {
        SweepRule rule = new SweepRule();
        rule.setId(UUID.randomUUID());
        rule.setRuleName("IHB Sweep: MNC Dubai -> Treasury (AED)");
        rule.setSweepType(SweepRule.SweepType.ZERO_BALANCE);
        rule.setCurrencyCode("AED");
        rule.setTargetAccountId(target.getId());
        rule.setStatus(SweepRule.SweepStatus.ACTIVE);
        rule.setTotalSwept(BigDecimal.ZERO);
        rule.setExecutionCount(0);
        SweepRuleSource s = new SweepRuleSource();
        s.setId(UUID.randomUUID());
        s.setRule(rule);
        s.setAccountId(source.getId());
        s.setAccountNumber(source.getVaNumber());
        rule.setSourceAccounts(new ArrayList<>(List.of(s)));
        when(rules.findAllByIdWithSources(List.of(rule.getId()))).thenReturn(List.of(rule));
        when(vas.findAllById(List.of(source.getId()))).thenReturn(List.of(source));
        SweepRuleDto.RunSweepsRequest request = new SweepRuleDto.RunSweepsRequest();
        request.setRuleIds(List.of(rule.getId()));
        return service.runSweeps(request);
    }

    @Test
    void anIhbCurrentAccountIsNotSweptSoNoMoneyMovesAndNoFeeIsCharged() {
        VirtualAccount ihbCurrent = account(subsidiary, "IHB-MNC-UAE-DUBAI-AED-0001", "14.48");
        ihbCurrent.setIhbParticipant(true);
        ihbCurrent.setMirrorAccountType(VirtualAccount.MirrorAccountType.IHB_CURRENT);

        for (int tick = 0; tick < 3; tick++) {
            var response = run(ihbCurrent);
            assertThat(response.getSkippedCount()).isEqualTo(1);
            assertThat(response.getExecutions().get(0).getErrorMessage()).contains("in-house account");
        }

        assertThat(ihbCurrent.getCurrentBalance()).isEqualByComparingTo("14.48");
        assertThat(target.getCurrentBalance()).isEqualByComparingTo("0");
        verifyNoInteractions(fees);
        verify(transactions, never()).postSweepSurplusIntercompanyLeg(any(), any(), any(), any());
    }

    @Test
    void aSweepThatMovesMoneyIsChargedOnceAndAnEmptyAccountIsNot() {
        VirtualAccount operating = account(subsidiary, "OPS-AED", "1000");

        run(operating);
        assertThat(operating.getCurrentBalance()).isEqualByComparingTo("0");
        assertThat(target.getCurrentBalance()).isEqualByComparingTo("1000");
        verify(fees, times(1)).postFee(eq(operating.getId()), eq(new BigDecimal("5.00")), eq("SWEEP_EXECUTION_FEE"), any(), anyString());

        run(operating); // nothing left to sweep
        verify(fees, times(1)).postFee(any(), any(), any(), any(), any());
    }
}
