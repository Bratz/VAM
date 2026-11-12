package com.bank.vam.service.treasury;

import com.bank.vam.dto.VirtualAccountDto;
import com.bank.vam.entity.Transaction;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.credit.InterestConfiguration;
import com.bank.vam.repository.PhysicalAccountRepository;
import com.bank.vam.repository.ProgramRepository;
import com.bank.vam.repository.TransactionRepository;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.credit.InterestConfigurationRepository;
import com.bank.vam.repository.hierarchy.HierarchyNodeRepository;
import com.bank.vam.repository.hierarchy.LegalEntityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * IHB interest accrued every currency on 360 days. It now takes the account's configured day count, else the
 * currency's market convention (AED ACT/365, USD ACT/360). An account with no rate still accrues at the built-in
 * 2.50% / 5.00%, but says so — on the accrual result and on the posted ledger row.
 */
class IhbInterestConventionTest {

    private final VirtualAccountRepository vas = mock(VirtualAccountRepository.class);
    private final TransactionRepository txns = mock(TransactionRepository.class);
    private final InterestConfigurationRepository configs = mock(InterestConfigurationRepository.class);
    private final IhbUnifiedService service = new IhbUnifiedService(
        mock(LegalEntityRepository.class), vas, txns, mock(FeePostingService.class),
        mock(com.bank.vam.repository.treasury.SweepRuleRepository.class), configs,
        mock(com.bank.vam.service.tax.TaxService.class),
        new com.bank.vam.config.MarketProfileProperties(), mock(HierarchyNodeRepository.class),
        mock(ProgramRepository.class), mock(PhysicalAccountRepository.class), new IhbMirrorService(vas, txns));

    @BeforeEach
    void setUp() {
        when(vas.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private static VirtualAccount account(String ccy, String balance, String creditRate) {
        VirtualAccount va = VirtualAccount.builder()
            .vaNumber("IHB-SUB-" + ccy + "-0001").currencyCode(ccy).corporateId(UUID.randomUUID())
            .accountCategory(VirtualAccount.AccountCategory.TRANSACTION).ihbParticipant(true)
            .owningEntityId(UUID.randomUUID())
            .currentBalance(new BigDecimal(balance)).availableBalance(new BigDecimal(balance))
            .effectiveCreditRate(creditRate != null ? new BigDecimal(creditRate) : null)
            .build();
        va.setId(UUID.randomUUID());
        return va;
    }

    private VirtualAccountDto.IhbInterestCalcResult accrue(VirtualAccount va) {
        when(vas.findIhbParticipantsNeedingInterestCalc(any())).thenReturn(List.of(va));
        return service.calculateDailyInterestForCurrentAccounts().get(0);
    }

    @Test
    void aedAccruesOn365AndUsdOn360() {
        // 365,000 at 1% for one day: 10.00 on ACT/365, 10.138889 on ACT/360.
        assertThat(accrue(account("AED", "365000", "1.00")).getInterestAmount()).isEqualByComparingTo("10.000000");
        assertThat(accrue(account("USD", "365000", "1.00")).getInterestAmount()).isEqualByComparingTo("10.138889");
    }

    @Test
    void theAccountsConfiguredDayCountWins() {
        VirtualAccount aed = account("AED", "365000", "1.00");
        InterestConfiguration config = InterestConfiguration.builder().dayCountConvention("ACT/360").build();
        aed.setInternalInterestConfigId(UUID.randomUUID());
        when(configs.findById(aed.getInternalInterestConfigId())).thenReturn(Optional.of(config));

        assertThat(accrue(aed).getInterestAmount()).isEqualByComparingTo("10.138889");
    }

    @Test
    void aDefaultRateIsLabelledOnTheAccrualAndOnThePosting() {
        VirtualAccount va = account("AED", "365000", null);

        VirtualAccountDto.IhbInterestCalcResult accrual = accrue(va);
        assertThat(accrual.getRateApplied()).isEqualByComparingTo("2.50");
        assertThat(accrual.getRateSource()).isEqualTo(IhbUnifiedService.RATE_DEFAULT);

        when(vas.findIhbParticipantsNeedingInterestPosting(any())).thenReturn(List.of(va));
        service.postInterestForCurrentAccounts();

        ArgumentCaptor<Transaction> row = ArgumentCaptor.forClass(Transaction.class);
        verify(txns).save(row.capture());
        assertThat(row.getValue().getDescription()).contains("default rate 2.50% — no rate configured");
    }
}
