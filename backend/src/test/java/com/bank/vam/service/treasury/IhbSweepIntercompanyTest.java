package com.bank.vam.service.treasury;

import com.bank.vam.dto.treasury.SweepRuleDto;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.hierarchy.LegalEntity;
import com.bank.vam.entity.treasury.SweepExecution;
import com.bank.vam.entity.treasury.SweepRule;
import com.bank.vam.entity.treasury.SweepRuleSource;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.repository.TransactionRepository;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.hierarchy.LegalEntityRepository;
import com.bank.vam.repository.treasury.SweepExecutionRepository;
import com.bank.vam.repository.treasury.SweepRuleRepository;
import com.bank.vam.repository.treasury.SweepRuleSourceRepository;
import com.bank.vam.service.TransactionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * A cross-entity sweep between IHB-configured entities moves cash AND records the subsidiary's claim on treasury
 * (the intercompany leg on its IHB current account). Before: the claim was best-effort after the cash had moved —
 * skipped silently with no IHB current account or link, swallowed when it threw — and deficit top-ups were checked
 * one by one against an entity exposure nothing ever updated.
 */
class IhbSweepIntercompanyTest {

    private final SweepRuleRepository rules = mock(SweepRuleRepository.class);
    private final SweepExecutionRepository executions = mock(SweepExecutionRepository.class);
    private final VirtualAccountRepository vas = mock(VirtualAccountRepository.class);
    private final TransactionRepository txns = mock(TransactionRepository.class);
    private final LegalEntityRepository entities = mock(LegalEntityRepository.class);
    private final TransactionService transactions = mock(TransactionService.class);
    private final PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
    private final SweepService service = new SweepService(rules, executions, mock(SweepRuleSourceRepository.class), vas,
        txns, entities, mock(FeePostingService.class), mock(IhbFxService.class),
        mock(com.bank.vam.config.MarketProfileProperties.class), txManager,
        mock(SweepRunBootstrap.class), mock(com.bank.vam.repository.treasury.SweepRunRepository.class),
        mock(com.bank.vam.config.HomeBankProperties.class),
        mock(com.bank.vam.repository.treasury.ExternalMandateRepository.class),
        mock(com.bank.vam.repository.PhysicalAccountRepository.class), transactions);

    private final LegalEntity subsidiary = entity("MNC-UAE");
    private final LegalEntity treasury = entity("MNC-TREASURY");
    private final VirtualAccount treasuryVa = account(treasury, "TC-SETTLE-AED", "10000");
    private final VirtualAccount operating = account(subsidiary, "OPS-AED", "0");
    private final VirtualAccount icMirror = account(treasury, "IC-MNC-UAE-AED", "0");
    private final VirtualAccount ihbCurrent = account(subsidiary, "IHB-MNC-UAE-AED-0001", "0");

    @BeforeEach
    void setUp() {
        service.initTxTemplates();
        treasury.setCanLend(true);
        ihbCurrent.setIhbParticipant(true);
        ihbCurrent.setEffectiveCreditLimit(new BigDecimal("1000"));
        ihbCurrent.setIcPayableVaId(icMirror.getId());
        ihbCurrent.setIcReceivableVaId(icMirror.getId());
        when(vas.findByOwningEntityIdAndIhbParticipantTrueAndCurrencyCode(subsidiary.getId(), "AED"))
            .thenReturn(Optional.of(ihbCurrent));
        when(executions.save(any())).thenAnswer(i -> i.getArgument(0));
        when(entities.findAllById(any())).thenReturn(List.of(subsidiary, treasury));
        when(vas.findAllById(List.of(operating.getId()))).thenReturn(List.of(operating));
    }

    private static LegalEntity entity(String code) {
        LegalEntity e = new LegalEntity();
        e.setId(UUID.randomUUID());
        e.setEntityCode(code);
        e.setStatus(LegalEntity.EntityStatus.ACTIVE);
        e.setIhbEnabled(true);
        e.setCanBorrow(true);
        e.setCanLend(false);
        return e;   // no entity ihbCreditLimit: canBorrowAmount() let every top-up through
    }

    private VirtualAccount account(LegalEntity owner, String number, String balance) {
        VirtualAccount va = VirtualAccount.builder().vaNumber(number).currencyCode("AED").owningEntityId(owner.getId())
            .currentBalance(new BigDecimal(balance)).availableBalance(new BigDecimal(balance)).heldBalance(BigDecimal.ZERO)
            .accountCategory(VirtualAccount.AccountCategory.TRANSACTION).build();
        va.setId(UUID.randomUUID());
        when(vas.findById(va.getId())).thenReturn(Optional.of(va));
        return va;
    }

    private SweepRule rule(SweepRule.SweepType type) {
        SweepRule rule = new SweepRule();
        rule.setId(UUID.randomUUID());
        rule.setRuleName("IHB Sweep: MNC UAE -> Treasury (AED)");
        rule.setSweepType(type);
        rule.setTargetAmount(new BigDecimal("800"));
        rule.setCurrencyCode("AED");
        rule.setTargetAccountId(treasuryVa.getId());
        rule.setStatus(SweepRule.SweepStatus.ACTIVE);
        rule.setTotalSwept(BigDecimal.ZERO);
        rule.setExecutionCount(0);
        SweepRuleSource s = new SweepRuleSource();
        s.setId(UUID.randomUUID());
        s.setRule(rule);
        s.setAccountId(operating.getId());
        s.setAccountNumber(operating.getVaNumber());
        rule.setSourceAccounts(new ArrayList<>(List.of(s)));
        when(rules.findAllByIdWithSources(List.of(rule.getId()))).thenReturn(List.of(rule));
        return rule;
    }

    private SweepRuleDto.RunSweepsResponse sweep() {
        SweepRuleDto.RunSweepsRequest request = new SweepRuleDto.RunSweepsRequest();
        request.setRuleIds(List.of(rule(SweepRule.SweepType.ZERO_BALANCE).getId()));
        return service.runSweeps(request);
    }

    private SweepRuleDto.DeficitFundingResult fundDeficit(SweepRule rule) {
        SweepRuleDto.DeficitFundingRequest request = new SweepRuleDto.DeficitFundingRequest();
        request.setRuleIds(List.of(rule.getId()));
        return service.runDeficitFunding(request).getResults().get(0);
    }

    private void spend(VirtualAccount va) {
        va.setCurrentBalance(BigDecimal.ZERO);
        va.setAvailableBalance(BigDecimal.ZERO);
    }

    @Test
    void deficitTopUpsStopAtTheIhbHeadroom() {
        // The intercompany leg debits the IHB current account, which is what makes the headroom cumulative.
        doAnswer(i -> {
            ihbCurrent.setCurrentBalance(ihbCurrent.getCurrentBalance().subtract(i.getArgument(1)));
            return null;
        }).when(transactions).postSweepDeficitIntercompanyLeg(eq(ihbCurrent), any(), any(), any());
        SweepRule rule = rule(SweepRule.SweepType.TARGET_BALANCE);

        assertThat(fundDeficit(rule).isFunded()).isTrue();          // 800 of 1,000
        assertThat(ihbCurrent.getCurrentBalance()).isEqualByComparingTo("-800");

        spend(operating);
        SweepRuleDto.DeficitFundingResult second = fundDeficit(rule);  // another 800, only 200 left

        assertThat(second.isFunded()).isFalse();
        assertThat(second.getMessage()).contains("IHB credit headroom of 200");
        assertThat(treasuryVa.getCurrentBalance()).isEqualByComparingTo("9200");   // only the first moved
        assertThat(operating.getCurrentBalance()).isEqualByComparingTo("0");
        verify(transactions, times(1)).postSweepDeficitIntercompanyLeg(any(), any(), any(), any());
    }

    @Test
    void aMissingOrUnlinkedIhbCurrentAccountRefusesTheSweepBeforeCashMoves() {
        operating.setCurrentBalance(new BigDecimal("1000"));
        operating.setAvailableBalance(new BigDecimal("1000"));

        ihbCurrent.setIcPayableVaId(null);
        SweepRuleDto.ExecutionResponse unlinked = sweep().getExecutions().get(0);
        assertThat(unlinked.getStatus()).isEqualTo(SweepExecution.ExecutionStatus.FAILED.name());
        assertThat(unlinked.getErrorMessage()).contains("has no active IC Payable");

        when(vas.findByOwningEntityIdAndIhbParticipantTrueAndCurrencyCode(subsidiary.getId(), "AED"))
            .thenReturn(Optional.empty());
        SweepRuleDto.ExecutionResponse missing = sweep().getExecutions().get(0);
        assertThat(missing.getErrorMessage()).contains("MNC-UAE has no active AED IHB current account");

        // Deficit funding needs the same account, for the same reason.
        spend(operating);
        assertThat(fundDeficit(rule(SweepRule.SweepType.TARGET_BALANCE)).getMessage())
            .contains("has no active AED IHB current account");

        assertThat(operating.getCurrentBalance()).isEqualByComparingTo("0");
        assertThat(treasuryVa.getCurrentBalance()).isEqualByComparingTo("10000");
        verifyNoInteractions(txns);
        verify(transactions, never()).postSweepSurplusIntercompanyLeg(any(), any(), any(), any());
        verify(transactions, never()).postSweepDeficitIntercompanyLeg(any(), any(), any(), any());
    }

    @Test
    void anIntercompanyLegThatThrowsRollsTheCashTransferBack() {
        operating.setCurrentBalance(new BigDecimal("1000"));
        operating.setAvailableBalance(new BigDecimal("1000"));
        when(transactions.postSweepSurplusIntercompanyLeg(any(), any(), any(), any()))
            .thenThrow(new BusinessException("IC Payable VA not found"));

        SweepRuleDto.RunSweepsResponse response = sweep();

        // The transfer's writes and the failed leg ran in one per-source transaction, which was rolled back...
        InOrder order = inOrder(txns, transactions, txManager, executions);
        order.verify(txns, times(2)).save(any());                         // SWEEP_OUT / SWEEP_IN rows
        order.verify(transactions).postSweepSurplusIntercompanyLeg(any(), any(), any(), any());
        order.verify(txManager).rollback(any());
        // ...and the source is then recorded FAILED with the reason, in a transaction of its own.
        ArgumentCaptor<SweepExecution> saved = ArgumentCaptor.forClass(SweepExecution.class);
        order.verify(executions).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(SweepExecution.ExecutionStatus.FAILED);
        assertThat(saved.getValue().getErrorMessage()).contains("IC Payable VA not found");
        verify(executions, times(1)).save(any());                         // no SETTLED row ever saved
        assertThat(response.getFailedCount()).isEqualTo(1);
        assertThat(response.getTotalSwept()).isEqualByComparingTo("0");
        // The in-memory treasury VA the rolled-back transfer credited is re-read, not carried to the next source.
        verify(vas, times(2)).findById(treasuryVa.getId());
    }

    @Test
    void anIhbSweepThatSucceedsRecordsTheClaim() {
        operating.setCurrentBalance(new BigDecimal("1000"));
        operating.setAvailableBalance(new BigDecimal("1000"));

        SweepRuleDto.RunSweepsResponse response = sweep();

        assertThat(response.getSuccessCount() + response.getSkippedCount() + response.getFailedCount()).isEqualTo(1);
        assertThat(response.getExecutions().get(0).getStatus()).isEqualTo(SweepExecution.ExecutionStatus.SETTLED.name());
        assertThat(treasuryVa.getCurrentBalance()).isEqualByComparingTo("11000");
        verify(transactions).postSweepSurplusIntercompanyLeg(eq(ihbCurrent), eq(new BigDecimal("1000")), any(), any());
        verify(txManager, never()).rollback(any());
    }
}
