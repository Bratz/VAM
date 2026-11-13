package com.bank.vam.service.treasury;

import com.bank.vam.dto.VirtualAccountDto;
import com.bank.vam.dto.treasury.IhbDto;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.credit.InterestConfiguration;
import com.bank.vam.entity.hierarchy.LegalEntity;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.repository.PhysicalAccountRepository;
import com.bank.vam.repository.ProgramRepository;
import com.bank.vam.repository.TransactionRepository;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.credit.InterestConfigurationRepository;
import com.bank.vam.repository.hierarchy.HierarchyNodeRepository;
import com.bank.vam.repository.hierarchy.LegalEntityRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Two IHB defects from the functional review: a reversed sweep left the interest accrued on it (MNC Dubai carried
 * AED 1,986.11 of debit interest on an overdraft that never should have existed, C5), and MNC-UK's IHB currency was
 * GBP while its only IHB account is AED, so its exposure always read GBP 0.00 (C4).
 */
class IhbReversalAndCurrencyTest {

    private final LegalEntityRepository entities = mock(LegalEntityRepository.class);
    private final VirtualAccountRepository vas = mock(VirtualAccountRepository.class);
    private final TransactionRepository txns = mock(TransactionRepository.class);
    private final InterestConfigurationRepository configs = mock(InterestConfigurationRepository.class);
    private final IhbUnifiedService service = new IhbUnifiedService(
        entities, vas, txns, mock(FeePostingService.class),
        mock(com.bank.vam.repository.treasury.SweepRuleRepository.class), configs,
        mock(com.bank.vam.service.tax.TaxService.class),
        new com.bank.vam.config.MarketProfileProperties(), mock(HierarchyNodeRepository.class),
        mock(ProgramRepository.class), mock(PhysicalAccountRepository.class), new IhbMirrorService(vas, txns));

    /** Dubai on vam-five: 15 Sep sweep took it from 0.96 to -1,000,000; accrued 16-28 Sep; reversed 1 Oct. */
    private VirtualAccount dubaiAfterReversal() {
        VirtualAccount va = VirtualAccount.builder().vaNumber("IHB-MNC-UAE-DUBAI-AED-0001").currencyCode("AED")
            .accountCategory(VirtualAccount.AccountCategory.TRANSACTION).ihbParticipant(true)
            .owningEntityId(UUID.randomUUID()).currentBalance(new BigDecimal("0.96"))
            .effectiveCreditRate(new BigDecimal("4.5")).effectiveDebitRate(new BigDecimal("5.5"))
            .accruedCreditInterest(BigDecimal.ZERO).accruedDebitInterest(new BigDecimal("1986.1114"))
            .lastInterestCalcDate(LocalDate.of(2026, 9, 28)).lastInterestPostingDate(LocalDate.of(2026, 9, 15))
            .internalInterestConfigId(UUID.randomUUID())
            .build();
        // The September accruals ran on 360 days (152.7778 a day on 1,000,000 at 5.5%), before V54.
        when(configs.findById(va.getInternalInterestConfigId()))
            .thenReturn(Optional.of(InterestConfiguration.builder().dayCountConvention("ACT/360").build()));
        return va;
    }

    @Test
    void aReversedSweepTakesBackTheInterestAccruedOnIt() {
        VirtualAccount dubai = dubaiAfterReversal();

        service.takeBackAccruedInterest(dubai, new BigDecimal("1000000.96"), LocalDate.of(2026, 9, 15));

        // 13 accrual days (16-28 Sep) at 152.7778 come off; what remains is 0.96 at 4.5% for those days.
        assertThat(dubai.getAccruedDebitInterest()).isEqualByComparingTo("0");
        assertThat(dubai.getAccruedCreditInterest()).isEqualByComparingTo("0.0013"); // 13 x 0.0001 (0.000120 stored to 4 places)
    }

    @Test
    void daysAlreadyPostedOrNotYetAccruedAreLeftAlone() {
        VirtualAccount dubai = dubaiAfterReversal();
        // moved after the last accrual run: nothing accrued on it yet
        service.takeBackAccruedInterest(dubai, new BigDecimal("1000000.96"), LocalDate.of(2026, 9, 28));
        assertThat(dubai.getAccruedDebitInterest()).isEqualByComparingTo("1986.1114");

        // a movement older than the last posting only counts from the day after the posting
        dubai.setLastInterestPostingDate(LocalDate.of(2026, 9, 27));
        service.takeBackAccruedInterest(dubai, new BigDecimal("1000000.96"), LocalDate.of(2026, 9, 15));
        assertThat(dubai.getAccruedDebitInterest()).isEqualByComparingTo("1833.3336"); // only 28 Sep: 1986.1114 - 152.7778
    }

    @Test
    void anIhbAccountInAnotherCurrencyThanTheEntitysIhbAccountsIsRefused() {
        LegalEntity uk = ukWithAedAccount();
        LegalEntity treasury = new LegalEntity();
        treasury.setId(UUID.randomUUID());
        treasury.setEntityCode("MNC-TREASURY");
        treasury.setIhbEnabled(true);
        treasury.setCanLend(true);
        when(entities.findByCorporateIdAndCanLendTrue(uk.getCorporateId())).thenReturn(List.of(treasury));
        VirtualAccountDto.IhbCurrentAccountRequest request = new VirtualAccountDto.IhbCurrentAccountRequest();
        request.setParticipantEntityId(uk.getId());
        request.setCurrencyCode("GBP");

        assertThatThrownBy(() -> service.createIhbCurrentAccount(request))
            .isInstanceOf(BusinessException.class).hasMessageContaining("are in AED");
        verify(vas, never()).save(any());
    }

    @Test
    void reEnablingKeepsTheCurrencyOfTheIhbAccountsNotTheFunctionalCurrency() {
        LegalEntity uk = ukWithAedAccount();
        uk.setIhbEnabled(false);
        when(entities.save(any())).thenAnswer(i -> i.getArgument(0));
        IhbDto.EnableIhbRequest request = new IhbDto.EnableIhbRequest();
        request.setCreditLimit(new BigDecimal("1000000"));
        request.setAutoSweepEnabled(false);
        request.setAutoEnrollAccounts(false);

        service.enableIhb(uk.getId(), request);

        assertThat(uk.getIhbCurrency()).isEqualTo("AED");
    }

    private LegalEntity ukWithAedAccount() {
        LegalEntity uk = new LegalEntity();
        uk.setId(UUID.randomUUID());
        uk.setCorporateId(UUID.randomUUID());
        uk.setEntityCode("MNC-UK");
        uk.setFunctionalCurrency("GBP");
        uk.setIhbCurrency("AED");
        uk.setIhbEnabled(true);
        uk.setCanBorrow(true);
        when(entities.findById(uk.getId())).thenReturn(Optional.of(uk));
        VirtualAccount aed = VirtualAccount.builder().vaNumber("IHB-MNC-UK-AED-0002").currencyCode("AED")
            .ihbParticipant(true).owningEntityId(uk.getId()).currentBalance(new BigDecimal("1100.01")).build();
        aed.setId(UUID.randomUUID());
        when(vas.findByOwningEntityId(uk.getId())).thenReturn(List.of(aed));
        return uk;
    }
}
