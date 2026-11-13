package com.bank.vam.service.treasury;

import com.bank.vam.entity.PhysicalAccount;
import com.bank.vam.entity.Transaction;
import com.bank.vam.entity.Transaction.MovementType;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.audit.AuditLog;
import com.bank.vam.entity.hierarchy.LegalEntity;
import com.bank.vam.entity.treasury.IhbInterestAccrual;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.exception.ResourceNotFoundException;
import com.bank.vam.repository.PhysicalAccountRepository;
import com.bank.vam.repository.TransactionRepository;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.audit.AuditLogRepository;
import com.bank.vam.repository.credit.InterestConfigurationRepository;
import com.bank.vam.repository.hierarchy.LegalEntityRepository;
import com.bank.vam.repository.treasury.IhbInterestAccrualRepository;
import com.bank.vam.service.audit.AuditLogService;
import com.bank.vam.service.treasury.IhbBookService.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * The In-House Bank page's read models (spec R13-R16): position per entity and currency from IhbExposure with treasury's
 * bank accounts beside it; movements by kind, signed by what they did to the balance; drill-down legs within the
 * corporate; statements; interest history; the limit/rate audit trail. Another corporate's account is never found.
 */
class IhbBookServiceTest {

    private final UUID corp = UUID.randomUUID();
    private final UUID otherCorp = UUID.randomUUID();
    private final LocalDate day = LocalDate.of(2026, 11, 2);

    private final VirtualAccountRepository vas = mock(VirtualAccountRepository.class);
    private final LegalEntityRepository entities = mock(LegalEntityRepository.class);
    private final PhysicalAccountRepository banks = mock(PhysicalAccountRepository.class);
    private final TransactionRepository ledger = mock(TransactionRepository.class);
    private final IhbInterestAccrualRepository accruals = mock(IhbInterestAccrualRepository.class);
    private final AuditLogRepository auditLog = mock(AuditLogRepository.class);
    private final ObjectMapper json = new ObjectMapper();
    private final IhbBookService book = new IhbBookService(vas, entities, banks, ledger, accruals,
        mock(InterestConfigurationRepository.class), auditLog, json);

    private LegalEntity entity(String code, String ihbCcy, String limit) {
        LegalEntity e = new LegalEntity();
        e.setId(UUID.randomUUID());
        e.setCorporateId(corp);
        e.setEntityCode(code);
        e.setEntityName(code + " Ltd");
        e.setIhbCurrency(ihbCcy);
        e.setIhbCreditLimit(limit != null ? new BigDecimal(limit) : null);
        return e;
    }

    private VirtualAccount ihbAccount(LegalEntity owner, String ccy, String balance) {
        VirtualAccount va = VirtualAccount.builder().vaNumber("IHB-" + owner.getEntityCode() + "-" + ccy)
            .corporateId(corp).currencyCode(ccy).ihbParticipant(true).status(VirtualAccount.VaStatus.ACTIVE)
            .owningEntityId(owner.getId()).owningEntityCode(owner.getEntityCode())
            .currentBalance(new BigDecimal(balance)).accruedCreditInterest(BigDecimal.ZERO).accruedDebitInterest(BigDecimal.ZERO)
            .build();
        va.setId(UUID.randomUUID());
        return va;
    }

    private Transaction tx(VirtualAccount va, MovementType type, String channel, String amount, String before, String after) {
        Transaction t = Transaction.builder().referenceNumber("REF-" + UUID.randomUUID()).movementType(type).channel(channel)
            .corporateId(va.getCorporateId()).vaId(va.getId()).currencyCode(va.getCurrencyCode()).amount(new BigDecimal(amount))
            .balanceBefore(before != null ? new BigDecimal(before) : null).balanceAfter(after != null ? new BigDecimal(after) : null)
            .transactionDate(day.atTime(10, 0)).valueDate(day).status(Transaction.TransactionStatus.COMPLETED).build();
        t.setId(UUID.randomUUID());
        return t;
    }

    private void accountsOf(VirtualAccount... accounts) {
        when(vas.findIhbParticipantsByCorporate(corp)).thenReturn(List.of(accounts));
        for (VirtualAccount va : accounts) when(vas.findById(va.getId())).thenReturn(Optional.of(va));
    }

    private void booked(Transaction... txs) {
        when(ledger.findByVaIdInAndTransactionDateGreaterThanEqualAndTransactionDateLessThanOrderByTransactionDateAscCreatedAtAsc(
            any(), any(), any())).thenReturn(List.of(txs));
    }

    @Test
    void positionPerEntityAndCurrencyFromIhbExposureWithTreasurysBankAccountsBeside() {
        LegalEntity uk = entity("MNC-UK", "AED", "1000000");
        LegalEntity dubai = entity("MNC-DUBAI", "AED", "500000");
        VirtualAccount ukAed = ihbAccount(uk, "AED", "-7212.00");
        ukAed.setAccruedDebitInterest(new BigDecimal("3.2500"));
        VirtualAccount dubaiAed = ihbAccount(dubai, "AED", "14.48");
        VirtualAccount dubaiGbp = ihbAccount(dubai, "GBP", "-50.00"); // not its IHB currency: no limit applies there
        accountsOf(ukAed, dubaiAed, dubaiGbp);
        when(entities.findAllById(any())).thenReturn(List.of(uk, dubai));
        when(vas.findByOwningEntityId(uk.getId())).thenReturn(List.of(ukAed));
        when(vas.findByOwningEntityId(dubai.getId())).thenReturn(List.of(dubaiAed, dubaiGbp));

        LegalEntity treasury = entity("MNC-TREASURY", "AED", null);
        when(entities.findByCorporateIdAndCanLendTrue(corp)).thenReturn(List.of(treasury));
        PhysicalAccount aed = PhysicalAccount.builder().accountNumber("PA-AED-002").accountName("Treasury AED").bankName("Emirates NBD")
            .currencyCode("AED").currentBalance(new BigDecimal("10000000")).availableBalance(new BigDecimal("9500000")).build();
        PhysicalAccount usd = PhysicalAccount.builder().accountNumber("USD11564547").accountName("Treasury USD").bankName("HSBC UK")
            .currencyCode("USD").currentBalance(new BigDecimal("5000000")).availableBalance(new BigDecimal("5000000")).build();
        when(banks.findByLegalEntityIdAndStatus(treasury.getId(), PhysicalAccount.AccountStatus.ACTIVE)).thenReturn(List.of(aed, usd));

        Position p = book.position(corp);

        assertThat(p.currencies()).extracting(CurrencyPosition::currency).containsExactly("AED", "GBP", "USD");
        CurrencyPosition aedPos = p.currencies().get(0);
        PositionRow ukRow = aedPos.rows().stream().filter(r -> r.entityCode().equals("MNC-UK")).findFirst().orElseThrow();
        assertThat(ukRow.balance()).isEqualByComparingTo("-7212.00");
        assertThat(ukRow.creditLimit()).isEqualByComparingTo("1000000");
        assertThat(ukRow.used()).isEqualByComparingTo("7212.00");
        assertThat(ukRow.headroom()).isEqualByComparingTo("992788.00");
        assertThat(ukRow.accruedInterest()).isEqualByComparingTo("-3.25");
        assertThat(aedPos.totalBalance()).isEqualByComparingTo("-7197.52");
        assertThat(aedPos.funding()).extracting(FundingAccount::accountNumber).containsExactly("PA-AED-002");
        assertThat(aedPos.totalFunding()).isEqualByComparingTo("10000000");

        PositionRow gbp = p.currencies().get(1).rows().get(0);
        assertThat(gbp.balance()).isEqualByComparingTo("-50.00");
        assertThat(gbp.creditLimit()).isNull();
        assertThat(gbp.used()).isNull();
        assertThat(p.currencies().get(2).rows()).isEmpty(); // funding only
    }

    @Test
    void movementsAreClassifiedSignedByTheBalanceAndFiltered() {
        LegalEntity uk = entity("MNC-UK", "AED", "1000000");
        VirtualAccount ukAed = ihbAccount(uk, "AED", "0");
        accountsOf(ukAed);
        Transaction pobo = tx(ukAed, MovementType.POBO_DEBIT, "IHB_POBO", "412.00", "0.00", "-412.00");
        Transaction sweep = tx(ukAed, MovementType.SWEEP_IN, "SWEEP", "1000.00", "-412.00", "588.00");
        Transaction cobo = tx(ukAed, MovementType.TRANSFER_IN, "IHB_COBO", "995.00", "588.00", "1583.00");
        Transaction settle = tx(ukAed, MovementType.IC_SETTLEMENT, "IC_SETTLEMENT", "600.00", "1583.00", "983.00");
        Transaction debitInterest = tx(ukAed, MovementType.INTEREST, "IHB_INTEREST", "1.10", "983.00", "981.90");
        booked(pobo, sweep, cobo, settle, debitInterest);

        List<Movement> all = book.movements(corp, null, null, null, null, null);
        assertThat(all).extracting(Movement::kind).containsExactly("INTEREST", "SETTLEMENT", "COBO", "SWEEP", "POBO");
        assertThat(all.get(0).amount()).isEqualByComparingTo("-1.10"); // an INTEREST row that took money is a debit
        assertThat(all.get(4).amount()).isEqualByComparingTo("-412.00");
        assertThat(all.get(3).amount()).isEqualByComparingTo("1000.00");

        assertThat(book.movements(corp, null, null, "POBO", null, null)).extracting(Movement::id).containsExactly(pobo.getId());
        assertThat(book.movements(corp, UUID.randomUUID(), null, null, null, null)).isEmpty();
        assertThatThrownBy(() -> book.movements(corp, null, null, "LOAN", null, null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> book.movements(corp, null, null, null, day, day.minusDays(1))).isInstanceOf(BusinessException.class);
    }

    @Test
    void drillDownShowsTheTransactionsLegsWithinTheCorporateOnly() {
        LegalEntity uk = entity("MNC-UK", "AED", "1000000");
        VirtualAccount ukAed = ihbAccount(uk, "AED", "-123.00");
        accountsOf(ukAed);
        PhysicalAccount bank = PhysicalAccount.builder().accountNumber("PA-AED-002").accountName("Treasury AED").bankName("Emirates NBD").build();
        bank.setId(UUID.randomUUID());
        VirtualAccount shadow = VirtualAccount.builder().vaNumber("SHADOW-AED-002").vaName("Shadow: Treasury AED")
            .corporateId(corp).currencyCode("AED").linkedPhysicalAccountId(bank.getId()).build();
        shadow.setId(UUID.randomUUID());

        Transaction leg1 = tx(ukAed, MovementType.POBO_DEBIT, "POBO", "123.00", "0.00", "-123.00");
        leg1.setCorrelationId("POBO-1");
        leg1.setBeneficiaryName("My FULL PARTY");
        Transaction leg2 = tx(shadow, MovementType.DEBIT, "ISO20022_IHB_POBO", "123.00", "500.00", "377.00");
        leg2.setCorrelationId("POBO-1");
        Transaction foreign = tx(shadow, MovementType.DEBIT, "X", "1.00", null, null);
        foreign.setCorporateId(otherCorp);
        foreign.setCorrelationId("POBO-1");
        when(ledger.findById(leg1.getId())).thenReturn(Optional.of(leg1));
        when(ledger.findAllByCorrelationId("POBO-1")).thenReturn(List.of(leg1, leg2, foreign));
        when(vas.findAllById(any())).thenReturn(List.of(ukAed, shadow));
        when(banks.findAllById(any())).thenReturn(List.of(bank));

        MovementDetail d = book.movement(corp, leg1.getId());
        assertThat(d.counterpartyName()).isEqualTo("My FULL PARTY");
        assertThat(d.legs()).extracting(Leg::account)
            .containsExactly("IHB-MNC-UK-AED", "Treasury AED (Emirates NBD PA-AED-002)");
        assertThat(d.legs()).extracting(Leg::amount).usingElementComparator(BigDecimal::compareTo)
            .containsExactly(new BigDecimal("-123.00"), new BigDecimal("-123.00"));

        // a movement on another corporate's account is not found
        when(ledger.findById(foreign.getId())).thenReturn(Optional.of(foreign));
        assertThatThrownBy(() -> book.movement(corp, foreign.getId())).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void statementHasOpeningClosingAndValueDatedLinesAndRefusesAnotherCorporatesAccount() {
        LegalEntity uk = entity("MNC-UK", "AED", "1000000");
        VirtualAccount ukAed = ihbAccount(uk, "AED", "1100.00");
        accountsOf(ukAed);
        Transaction in = tx(ukAed, MovementType.SWEEP_IN, "SWEEP", "1000.00", "600.00", "1600.00");
        Transaction failed = tx(ukAed, MovementType.DEBIT, "ISO20022", "99.00", "1600.00", "1501.00");
        failed.setStatus(Transaction.TransactionStatus.FAILED);
        Transaction out = tx(ukAed, MovementType.SWEEP_OUT, "SWEEP", "500.00", "1600.00", "1100.00");
        booked(in, failed, out);
        when(ledger.findLedgerBalanceBefore(eq(ukAed.getId()), any())).thenReturn(Optional.of(new BigDecimal("600.00")));

        Statement s = book.statement(corp, ukAed.getId(), day, day);
        assertThat(s.openingBalance()).isEqualByComparingTo("600.00");
        assertThat(s.totalCredits()).isEqualByComparingTo("1000.00");
        assertThat(s.totalDebits()).isEqualByComparingTo("500.00");
        assertThat(s.closingBalance()).isEqualByComparingTo("1100.00");
        assertThat(s.lines()).hasSize(2).allSatisfy(l -> assertThat(l.valueDate()).isEqualTo(day));

        VirtualAccount foreign = ihbAccount(uk, "AED", "0");
        foreign.setCorporateId(otherCorp);
        when(vas.findById(foreign.getId())).thenReturn(Optional.of(foreign));
        assertThatThrownBy(() -> book.statement(corp, foreign.getId(), day, day)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void interestHistoryShowsEachDaysRateAndDayCountThePostingsAndTheRatesChargedNow() {
        LegalEntity uk = entity("MNC-UK", "AED", "1000000");
        VirtualAccount ukAed = ihbAccount(uk, "AED", "-36500.00");
        ukAed.setEffectiveDebitRate(new BigDecimal("5.50")); // no credit rate: the run charges the 2.50 default
        accountsOf(ukAed);
        when(accruals.findByVaIdAndAccrualDateBetweenOrderByAccrualDateAscCreatedAtAsc(ukAed.getId(), day, day.plusDays(1))).thenReturn(List.of(
            IhbInterestAccrual.builder().vaId(ukAed.getId()).accrualDate(day).side("DEBIT").balance(new BigDecimal("-36500.00"))
                .rate(new BigDecimal("5.50")).dayCount("ACT/365").amount(new BigDecimal("5.5000")).build(),
            IhbInterestAccrual.builder().vaId(ukAed.getId()).accrualDate(day.plusDays(1)).side("DEBIT").balance(new BigDecimal("-36500.00"))
                .rate(new BigDecimal("5.50")).dayCount("ACT/365").amount(new BigDecimal("5.5000")).build()));
        booked(tx(ukAed, MovementType.INTEREST, "IHB_INTEREST", "11.00", "-36500.00", "-36511.00"),
            tx(ukAed, MovementType.SWEEP_IN, "SWEEP", "11.00", "-36511.00", "-36500.00"));

        InterestHistory h = book.interestHistory(corp, ukAed.getId(), day, day.plusDays(1));
        assertThat(h.accruals()).hasSize(2).allSatisfy(a -> {
            assertThat(a.rate()).isEqualByComparingTo("5.50");
            assertThat(a.dayCount()).isEqualTo("ACT/365");
        });
        assertThat(h.accruedDebit()).isEqualByComparingTo("11.00");
        assertThat(h.accruedCredit()).isEqualByComparingTo("0");
        assertThat(h.postings()).hasSize(1);
        assertThat(h.posted()).isEqualByComparingTo("-11.00");
        assertThat(h.debitRateNow()).isEqualByComparingTo("5.50");
        assertThat(h.creditRateNow()).isEqualByComparingTo("2.50");
        assertThat(h.defaultRatesNow()).isTrue();
        assertThat(h.dayCountNow()).isEqualTo("ACT/365"); // AED market convention, no configuration
    }

    @Test
    void termsHistoryReadsWhoChangedWhatFromWhatToWhatForTheCorporateOnly() {
        AuditLogService audit = new AuditLogService(auditLog, json);
        when(auditLog.findTopByOrderByCreatedAtDesc()).thenReturn(Optional.empty());
        LegalEntity uk = entity("MNC-UK", "AED", "1000000");
        Map<String, Object> before = IhbUnifiedService.terms(uk);
        uk.setIhbCreditLimit(new BigDecimal("500000"));
        uk.setBorrowingRateSpread(new BigDecimal("1.00")); // the 0.75 default before

        IhbUnifiedService.auditTerms(audit, uk, before);
        ArgumentCaptor<AuditLog> saved = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLog).save(saved.capture());
        AuditLog row = saved.getValue();
        assertThat(row.getEventType()).isEqualTo(IhbUnifiedService.TERMS_CHANGED);
        assertThat(row.getCorporateId()).isEqualTo(corp);

        // nothing changed (same value, another scale): nothing written
        Map<String, Object> same = IhbUnifiedService.terms(uk);
        uk.setIhbCreditLimit(new BigDecimal("500000.0000"));
        IhbUnifiedService.auditTerms(audit, uk, same);
        verify(auditLog, times(1)).save(any());

        row.setCreatedAt(LocalDateTime.of(2026, 11, 13, 9, 0));
        when(auditLog.findByCorporateIdAndEventTypeOrderByCreatedAtDesc(eq(corp), eq(IhbUnifiedService.TERMS_CHANGED), any()))
            .thenReturn(new PageImpl<>(List.of(row)));
        List<TermsChange> changes = book.termsHistory(corp);
        assertThat(changes).extracting(TermsChange::field).containsExactly("IHB credit limit", "borrowing spread");
        assertThat(changes.get(0).subject()).isEqualTo("MNC-UK");
        assertThat(changes.get(0).currency()).isEqualTo("AED");
        assertThat(changes.get(1).currency()).isNull(); // a spread is a percentage
        assertThat(changes.get(0).from().decimalValue()).isEqualByComparingTo("1000000");
        assertThat(changes.get(0).to().decimalValue()).isEqualByComparingTo("500000");
        assertThat(changes.get(1).from().decimalValue()).isEqualByComparingTo("0.75");
        assertThat(changes.get(1).to().decimalValue()).isEqualByComparingTo("1.00");
        assertThat(changes.get(0).at()).isEqualTo(LocalDateTime.of(2026, 11, 13, 9, 0));
    }

    @Test
    void changesToTheLimitSpreadsAndChargedRatesAreAuditedWhereTheyAreMade() {
        AuditLogService audit = mock(AuditLogService.class);

        // an IHB current account's rates, re-synced from its configuration (attach, propagate, nightly sync)
        var configs = mock(InterestConfigurationRepository.class);
        var attach = new com.bank.vam.service.credit.InterestConfigAttachmentService(vas, configs,
            mock(com.bank.vam.service.credit.InterestConfigurationService.class), audit);
        LegalEntity uk = entity("MNC-UK", "AED", "1000000");
        VirtualAccount ukAed = ihbAccount(uk, "AED", "0");
        ukAed.setEffectiveCreditRate(new BigDecimal("4.50"));
        ukAed.setEffectiveDebitRate(new BigDecimal("5.50"));
        ukAed.setInternalInterestConfigId(UUID.randomUUID());
        var config = com.bank.vam.entity.credit.InterestConfiguration.builder()
            .status(com.bank.vam.entity.credit.InterestConfiguration.ConfigStatus.ACTIVE)
            .effectiveCreditRate(new BigDecimal("4.00")).effectiveDebitRate(new BigDecimal("6.00")).build();
        when(configs.findById(ukAed.getInternalInterestConfigId())).thenReturn(Optional.of(config));
        attach.syncEffectiveRates(ukAed);
        verify(audit).recordChanges(eq(IhbUnifiedService.TERMS_CHANGED), eq("VirtualAccount"), eq(ukAed.getId()), eq(corp),
            eq("IHB-MNC-UK-AED"), isNull(), eq(Map.of("credit rate", new BigDecimal("4.50"), "debit rate", new BigDecimal("5.50"))),
            eq(Map.of("credit rate", new BigDecimal("4.00"), "debit rate", new BigDecimal("6.00"))));

        // an ordinary account's rates are not IHB terms
        VirtualAccount ordinary = VirtualAccount.builder().vaNumber("OP-1").corporateId(corp).build();
        attach.syncEffectiveRates(ordinary);
        verifyNoMoreInteractions(audit);

        // the entity's limit and spreads, changed in the IHB settings
        IhbUnifiedService ihb = new IhbUnifiedService(entities, vas, ledger, mock(FeePostingService.class),
            mock(com.bank.vam.repository.treasury.SweepRuleRepository.class), configs, mock(com.bank.vam.service.tax.TaxService.class),
            new com.bank.vam.config.MarketProfileProperties(), mock(com.bank.vam.repository.hierarchy.HierarchyNodeRepository.class),
            mock(com.bank.vam.repository.ProgramRepository.class), banks, mock(IhbMirrorService.class), accruals, audit);
        uk.setIhbEnabled(true);
        when(entities.findById(uk.getId())).thenReturn(Optional.of(uk));
        when(entities.save(uk)).thenReturn(uk);
        Map<String, Object> before = IhbUnifiedService.terms(uk);
        var request = new com.bank.vam.dto.treasury.IhbDto.UpdateIhbSettingsRequest();
        request.setCreditLimit(new BigDecimal("750000"));
        ihb.updateIhbSettings(uk.getId(), request);
        verify(audit).recordChanges(eq(IhbUnifiedService.TERMS_CHANGED), eq("LegalEntity"), eq(uk.getId()), eq(corp),
            eq("MNC-UK"), eq(Map.of("currency", "AED")), eq(before), eq(IhbUnifiedService.terms(uk)));
        assertThat(IhbUnifiedService.terms(uk).get("IHB credit limit")).isEqualTo(new BigDecimal("750000"));
    }
}
