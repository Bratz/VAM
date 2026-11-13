package com.bank.vam.service.treasury;

import com.bank.vam.config.HomeBankProperties;
import com.bank.vam.dto.treasury.SweepRuleDto;
import com.bank.vam.entity.PhysicalAccount;
import com.bank.vam.entity.Transaction;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.hierarchy.LegalEntity;
import com.bank.vam.entity.treasury.ExternalMandate;
import com.bank.vam.entity.treasury.ExternalMandate.InstructionFormat;
import com.bank.vam.entity.treasury.ExternalMandate.MandateStatus;
import com.bank.vam.entity.treasury.SweepExecution;
import com.bank.vam.entity.treasury.SweepInstruction;
import com.bank.vam.entity.treasury.SweepInstruction.InstructionStatus;
import com.bank.vam.entity.treasury.SweepRule;
import com.bank.vam.entity.treasury.SweepRuleSource;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.iso20022.util.Iso20022XmlBuilder;
import com.bank.vam.repository.PhysicalAccountRepository;
import com.bank.vam.repository.TransactionRepository;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.hierarchy.LegalEntityRepository;
import com.bank.vam.repository.treasury.ExternalMandateRepository;
import com.bank.vam.repository.treasury.SweepExecutionRepository;
import com.bank.vam.repository.treasury.SweepInstructionRepository;
import com.bank.vam.repository.treasury.SweepRuleRepository;
import com.bank.vam.repository.treasury.SweepRuleSourceRepository;
import com.bank.vam.service.TransactionService;
import com.bank.vam.service.notification.NotificationService;
import com.bank.vam.service.treasury.cutoff.CutoffCalendarService;
import com.bank.vam.statement.BankStatement;
import com.bank.vam.statement.BankStatementLine;
import com.bank.vam.statement.BankStatementLineRepository;
import com.bank.vam.statement.BankStatementRepository;
import com.bank.vam.statement.StatementFreshness;
import com.bank.vam.statement.parse.ParsedLine.CreditDebit;
import com.bank.vam.statement.parse.ParsedStatement.Kind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Phases 3 and 4 of external-bank sweeps (spec R3, R8–R17, D4–D8): an account at another bank is sized on its last
 * end-of-day statement, skipped with the reason when stale / pending / not authorised, else instructed (MT101 or
 * pain.001), the home-bank target credited at once and both fees charged to it; execution COMMITTED. Then the other
 * bank's statement confirms it (SETTLED), or it fails after 2 business days or when marked rejected (reversed).
 */
class ExternalSweepRunTest {

    private static final LocalDate TODAY = LocalDate.now();   // SweepService runs on the clock

    private final SweepRuleRepository rules = mock(SweepRuleRepository.class);
    private final SweepExecutionRepository executions = mock(SweepExecutionRepository.class);
    private final VirtualAccountRepository vas = mock(VirtualAccountRepository.class);
    private final TransactionRepository txns = mock(TransactionRepository.class);
    private final LegalEntityRepository entities = mock(LegalEntityRepository.class);
    private final FeePostingService fees = mock(FeePostingService.class);
    private final PhysicalAccountRepository physicals = mock(PhysicalAccountRepository.class);
    private final ExternalMandateRepository mandates = mock(ExternalMandateRepository.class);
    private final SweepInstructionRepository instructions = mock(SweepInstructionRepository.class);
    private final BankStatementRepository statements = mock(BankStatementRepository.class);
    private final BankStatementLineRepository statementLines = mock(BankStatementLineRepository.class);
    private final CutoffCalendarService calendar = mock(CutoffCalendarService.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final HomeBankProperties homeBank = new HomeBankProperties();
    private final ExternalSweepService external = new ExternalSweepService(statements, statementLines,
            new StatementFreshness(statements, calendar), instructions, mandates, fees,
            new Iso20022XmlBuilder(mock(com.bank.vam.config.MarketProfileProperties.class)), calendar, vas, txns, physicals,
            executions, rules, notifications, homeBank);
    private final SweepService service = new SweepService(rules, executions, mock(SweepRuleSourceRepository.class), vas,
            txns, entities, fees, mock(IhbFxService.class), mock(com.bank.vam.config.MarketProfileProperties.class),
            mock(PlatformTransactionManager.class), mock(SweepRunBootstrap.class),
            mock(com.bank.vam.repository.treasury.SweepRunRepository.class), homeBank, mandates, physicals,
            mock(TransactionService.class), external);

    private final LegalEntity albion = new LegalEntity();
    private VirtualAccount lloyds;
    private VirtualAccount home;
    private PhysicalAccount homePhysical;
    private ExternalMandate authority;
    private VirtualAccount settlement;
    private SweepRule rule;
    private final List<SweepInstruction> saved = new ArrayList<>();
    private final Map<UUID, SweepExecution> executionsById = new HashMap<>();

    @BeforeEach
    void setUp() {
        homeBank.setBic("HBUKGB4BXXX");
        service.initTxTemplates();
        albion.setId(UUID.randomUUID());
        albion.setEntityName("Albion Engineering Ltd");
        when(entities.findAllById(any())).thenReturn(List.of(albion));
        when(executions.save(any())).thenAnswer(i -> {
            var e = (com.bank.vam.entity.treasury.SweepExecution) i.getArgument(0);
            if (e.getId() == null) e.setId(UUID.randomUUID());
            executionsById.put(e.getId(), e);
            return e;
        });
        when(executions.findById(any())).thenAnswer(i -> Optional.ofNullable(executionsById.get(i.<UUID>getArgument(0))));
        when(instructions.save(any())).thenAnswer(i -> {
            SweepInstruction s = i.getArgument(0);
            if (s.getId() == null) {
                s.setId(UUID.randomUUID());
                saved.add(s);
            }
            return s;
        });
        when(instructions.findById(any())).thenAnswer(i -> saved.stream().filter(s -> s.getId().equals(i.getArgument(0))).findFirst());
        when(instructions.findBySourceShadowVaIdAndStatusInOrderByInstructedAtAsc(any(), any())).thenAnswer(i -> saved.stream()
                .filter(s -> s.getSourceShadowVaId().equals(i.getArgument(0)) && i.<java.util.Collection<?>>getArgument(1).contains(s.getStatus()))
                .sorted(Comparator.comparing(SweepInstruction::getInstructedAt)).toList());
        when(instructions.findByStatusInOrderByInstructedAtDesc(any())).thenAnswer(i -> saved.stream()
                .filter(s -> i.<List<?>>getArgument(0).contains(s.getStatus())).toList());
        when(rules.findById(any())).thenAnswer(i -> Optional.ofNullable(rule));
        // The conditional UPDATE as the database runs it: moves the row only from one of the given statuses.
        when(instructions.claim(any(), any(), any())).thenAnswer(i -> saved.stream()
                .filter(s -> s.getId().equals(i.getArgument(0)) && i.<java.util.Collection<?>>getArgument(2).contains(s.getStatus()))
                .peek(s -> s.setStatus(i.getArgument(1))).mapToInt(s -> 1).sum());
        when(instructions.instructedOn(any(), any(), any())).thenReturn(BigDecimal.ZERO);
        when(calendar.previousBusinessDay(anyString(), any())).thenAnswer(i -> ((LocalDate) i.getArgument(1)).minusDays(1));
        when(fees.postFee(any(), any(), anyString(), any(), anyString())).thenAnswer(i -> FeePostingService.FeePostingResult.builder()
                .debitTransaction(Transaction.builder().referenceNumber("FEE-" + i.getArgument(2)).build()).build());

        // Ledger 1000 at Lloyds, but the last statement says 800: the statement sizes the sweep.
        lloyds = shadow("LLOYDS-GBP", "LOYDGB2LXXX", "GB29LOYD30963412345678", "1000");
        home = shadow("HOME-GBP", "HBUKGB4BXXX", "GB11HBUK40051512345678", "5000");
        homePhysical = PhysicalAccount.builder().currentBalance(new BigDecimal("5000")).availableBalance(new BigDecimal("5000"))
                .ledgerBalance(new BigDecimal("5000")).build();
        homePhysical.setId(UUID.randomUUID());
        home.setLinkedPhysicalAccountId(homePhysical.getId());
        home.setBalanceDataSource(VirtualAccount.BalanceDataSource.CORE_BANKING);
        when(physicals.findById(homePhysical.getId())).thenReturn(Optional.of(homePhysical));
        lloyds.setCorporateId(UUID.randomUUID());
        settlement = VirtualAccount.builder().vaNumber("SETTLE-GBP").currencyCode("GBP")
                .currentBalance(new BigDecimal("100")).availableBalance(new BigDecimal("100")).build();
        settlement.setId(UUID.randomUUID());
        when(vas.findById(settlement.getId())).thenReturn(Optional.of(settlement));
        // The instruction fee's debit leg, as FeePostingService writes it: paid by the target into the settlement account.
        when(txns.findByReferenceNumber("FEE-SWEEP_INSTRUCTION_FEE")).thenReturn(Optional.of(Transaction.builder()
                .referenceNumber("FEE-SWEEP_INSTRUCTION_FEE").vaId(home.getId()).counterpartyVaId(settlement.getId())
                .amount(new BigDecimal("25")).build()));

        authority = authority(InstructionFormat.MT101);
        when(mandates.findByShadowVaIdOrderByCreatedAtDesc(lloyds.getId())).thenReturn(List.of(authority));
        statement(TODAY.minusDays(1), "800");
    }

    private VirtualAccount shadow(String number, String bic, String iban, String balance) {
        VirtualAccount va = VirtualAccount.builder().vaNumber(number).vaName(number).currencyCode("GBP").bankSwift(bic)
                .bankIban(iban).accountCategory(VirtualAccount.AccountCategory.PHYSICAL_MIRROR).owningEntityId(albion.getId())
                .currentBalance(new BigDecimal(balance)).availableBalance(new BigDecimal(balance))
                .bankBalance(new BigDecimal(balance)).bankAvailableBalance(new BigDecimal(balance)).heldBalance(BigDecimal.ZERO).build();
        va.setId(UUID.randomUUID());
        when(vas.findById(va.getId())).thenReturn(Optional.of(va));
        return va;
    }

    private ExternalMandate authority(InstructionFormat format) {
        ExternalMandate m = ExternalMandate.builder().mandateReference("DEMO-AUTH-LLOYDS").shadowVaId(lloyds.getId())
                .status(MandateStatus.ACTIVE).instructionFormat(format).build();
        m.setId(UUID.randomUUID());
        return m;
    }

    private void statement(LocalDate date, String closingBooked) {
        BankStatement s = new BankStatement();
        s.setStatementDate(date);
        s.setClosingBooked(new BigDecimal(closingBooked));
        when(statements.findFirstByShadowVaIdAndKindAndStatusOrderByStatementDateDescCreatedAtDesc(
                lloyds.getId(), Kind.END_OF_DAY, BankStatement.Status.APPLIED)).thenReturn(Optional.of(s));
    }

    private SweepRuleDto.RunSweepsResponse run() {
        rule = new SweepRule();
        rule.setId(UUID.randomUUID());
        rule.setRuleName("Albion Lloyds -> home");
        rule.setSweepType(SweepRule.SweepType.ZERO_BALANCE);
        rule.setCurrencyCode("GBP");
        rule.setTargetAccountId(home.getId());
        rule.setStatus(SweepRule.SweepStatus.ACTIVE);
        rule.setTotalSwept(BigDecimal.ZERO);
        rule.setExecutionCount(0);
        SweepRuleSource s = new SweepRuleSource();
        s.setId(UUID.randomUUID());
        s.setRule(rule);
        s.setAccountId(lloyds.getId());
        s.setAccountNumber(lloyds.getVaNumber());
        rule.setSourceAccounts(new ArrayList<>(List.of(s)));
        when(rules.findAllByIdWithSources(List.of(rule.getId()))).thenReturn(List.of(rule));
        when(vas.findAllById(List.of(lloyds.getId()))).thenReturn(List.of(lloyds));
        SweepRuleDto.RunSweepsRequest request = new SweepRuleDto.RunSweepsRequest();
        request.setRuleIds(List.of(rule.getId()));
        return service.runSweeps(request);
    }

    private SweepInstruction instructed() {
        ArgumentCaptor<SweepInstruction> saved = ArgumentCaptor.forClass(SweepInstruction.class);
        verify(instructions).save(saved.capture());
        return saved.getValue();
    }

    // ---- instruct and book (acceptance 2, 3) ----------------------------------------------------------------

    @Test
    void anAuthorisedSourceIsInstructedOnItsStatementBalanceAndTheHomeBankTargetIsCreditedAtOnce() {
        var response = run();

        var execution = response.getExecutions().get(0);
        assertThat(execution.getStatus()).isEqualTo("COMMITTED");
        assertThat(response.getSuccessCount()).isEqualTo(1);   // instructed counts as executed, not skipped
        assertThat(response.getSkippedCount()).isZero();
        assertThat(response.getTotalSwept()).isEqualByComparingTo("800");
        assertThat(execution.getSweepAmount()).isEqualByComparingTo("800");
        assertThat(execution.getBalanceBefore()).isEqualByComparingTo("800");   // the statement's, not the ledger's 1000
        assertThat(execution.getBalanceAfter()).isEqualByComparingTo("0");

        // Target: ledger, bank balance and physical account follow the credit (fees are mocked, they move nothing here).
        assertThat(home.getCurrentBalance()).isEqualByComparingTo("5800");
        assertThat(home.getBankBalance()).isEqualByComparingTo("5800");
        assertThat(home.getBankAvailableBalance()).isEqualByComparingTo("5800");   // available follows too
        assertThat(homePhysical.getCurrentBalance()).isEqualByComparingTo("5800");
        // Source: ledger shows the instructed debit, the bank balance stays the bank's own figure (statement wins).
        assertThat(lloyds.getCurrentBalance()).isEqualByComparingTo("200");
        assertThat(lloyds.getBankBalance()).isEqualByComparingTo("1000");
        // ...and the amount is committed float, so the effective bank balance counts it at the home bank only.
        assertThat(lloyds.getBankBalanceCommitted()).isEqualByComparingTo("800");
        assertThat(lloyds.getBankBalanceEffective()).isEqualByComparingTo("200");

        SweepInstruction i = instructed();
        assertThat(i.getStatus()).isEqualTo(InstructionStatus.INSTRUCTED);
        assertThat(i.getInstructionFormat()).isEqualTo(InstructionFormat.MT101);
        assertThat(i.getMandateId()).isEqualTo(authority.getId());
        assertThat(i.getSourceShadowVaId()).isEqualTo(lloyds.getId());
        assertThat(i.getTargetVaId()).isEqualTo(home.getId());
        assertThat(i.getSweepExecutionId()).isEqualTo(execution.getId());
        assertThat(i.getAmount()).isEqualByComparingTo("800");
        assertThat(i.getExecutionDate()).isEqualTo(TODAY);
        assertThat(i.getIdempotencyKey()).hasSize(16).startsWith("XSW").isEqualTo(i.getExternalReference());
        assertThat(i.getMessageText()).startsWith(":20:" + i.getIdempotencyKey() + "\n")
                .contains(":50H:/GB29LOYD30963412345678\nAlbion Engineering Ltd\n", ":32B:GBP800,00\n",
                        ":70:Cash sweep " + execution.getExecutionReference() + "\n");
        assertThat(i.getTargetCreditRef()).isNotNull();
        verify(txns, times(2)).save(any());   // SWEEP_OUT + SWEEP_IN
    }

    @Test
    void bothFeesAreChargedToTheHomeBankTargetAndRecordedOnTheInstruction() {
        run();

        verify(fees).postFee(eq(home.getId()), eq(new BigDecimal("5.00")), eq("SWEEP_EXECUTION_FEE"), any(), anyString());
        verify(fees).postFee(eq(home.getId()), eq(new BigDecimal("25")), eq("SWEEP_INSTRUCTION_FEE"), any(), anyString());
        verify(fees, never()).postFee(eq(lloyds.getId()), any(), any(), any(), any());
        SweepInstruction i = instructed();
        assertThat(i.getStandardFeeRef()).isEqualTo("FEE-SWEEP_EXECUTION_FEE");
        assertThat(i.getInstructionFeeRef()).isEqualTo("FEE-SWEEP_INSTRUCTION_FEE");
        assertThat(i.getInstructionFee()).isEqualByComparingTo("25");
    }

    @Test
    void aPain001AuthorityProducesAPain001() {
        when(mandates.findByShadowVaIdOrderByCreatedAtDesc(lloyds.getId())).thenReturn(List.of(authority(InstructionFormat.PAIN_001)));

        assertThat(run().getExecutions().get(0).getStatus()).isEqualTo("COMMITTED");
        SweepInstruction i = instructed();
        assertThat(i.getInstructionFormat()).isEqualTo(InstructionFormat.PAIN_001);
        assertThat(i.getMessageText()).contains("pain.001.001.09", "<EndToEndId>" + i.getExternalReference() + "</EndToEndId>");
    }

    // ---- skips (acceptance 6): nothing booked, nothing instructed, no fee ------------------------------------

    private void assertSkipped(String reason) {
        var response = run();
        var execution = response.getExecutions().get(0);
        assertThat(execution.getStatus()).isEqualTo("SKIPPED");
        assertThat(execution.getErrorMessage()).contains(reason);
        assertThat(response.getSkippedCount()).isEqualTo(1);
        assertThat(home.getCurrentBalance()).isEqualByComparingTo("5000");
        assertThat(lloyds.getCurrentBalance()).isEqualByComparingTo("1000");
        verify(instructions, never()).save(any());
        verifyNoInteractions(fees, txns);
    }

    @Test
    void noEndOfDayStatementIsASkip() {
        when(statements.findFirstByShadowVaIdAndKindAndStatusOrderByStatementDateDescCreatedAtDesc(any(), any(), any()))
                .thenReturn(Optional.empty());
        assertSkipped("no end-of-day statement for LLOYDS-GBP");
    }

    @Test
    void aStaleStatementIsASkip() {
        statement(TODAY.minusDays(2), "800");
        assertSkipped("stale balance: the last end-of-day statement for LLOYDS-GBP is dated " + TODAY.minusDays(2));
    }

    @Test
    void aSourceWithAnInstructionInFlightIsNotSweptAgain() {
        when(instructions.existsBySourceShadowVaIdAndStatusIn(lloyds.getId(), ExternalSweepService.IN_FLIGHT)).thenReturn(true);
        assertSkipped("still pending at the other bank");
    }

    @Test
    void noAuthorityOrOneThatDoesNotCoverTheAmountIsASkip() {
        when(mandates.findByShadowVaIdOrderByCreatedAtDesc(lloyds.getId())).thenReturn(List.of());
        assertSkipped("none is registered");

        ExternalMandate revoked = authority(InstructionFormat.MT101);
        revoked.setStatus(MandateStatus.REVOKED);
        ExternalMandate small = authority(InstructionFormat.MT101);
        small.setMaxPerTransaction(new BigDecimal("500"));
        when(mandates.findByShadowVaIdOrderByCreatedAtDesc(lloyds.getId())).thenReturn(List.of(revoked, small));
        assertSkipped("is REVOKED; authority DEMO-AUTH-LLOYDS: 800 is over the limit per transaction of 500");
    }

    @Test
    void theDailyLimitCountsWhatTheAuthorityAlreadyInstructedTodayExceptFailures() {
        authority.setMaxDaily(new BigDecimal("10000"));
        when(instructions.instructedOn(authority.getId(), TODAY, ExternalSweepService.FAILED)).thenReturn(new BigDecimal("9500"));
        assertSkipped("800 with 9500 already instructed on " + TODAY + " is over the daily limit of 10000");

        when(instructions.instructedOn(authority.getId(), TODAY, ExternalSweepService.FAILED)).thenReturn(new BigDecimal("9200"));
        assertThat(run().getExecutions().get(0).getStatus()).isEqualTo("COMMITTED");
        assertThat(ExternalSweepService.FAILED)
                .containsExactlyInAnyOrder(InstructionStatus.REJECTED, InstructionStatus.NACKED, InstructionStatus.EXPIRED);
    }

    // ---- confirmation (R15, D5; acceptance 4) -----------------------------------------------------------------

    private static BankStatementLine line(CreditDebit cd, String amount, LocalDate valueDate, String endToEndId) {
        BankStatementLine l = new BankStatementLine();
        l.setId(UUID.randomUUID());
        l.setCreditDebit(cd);
        l.setAmount(new BigDecimal(amount));
        l.setValueDate(valueDate);
        l.setEndToEndId(endToEndId);
        l.setBankReference("LL" + amount);
        l.setStatus(BankStatementLine.Status.FINAL);
        return l;
    }

    private SweepInstruction pending(String reference, String amount, LocalDate executionDate, int minutesAgo) {
        return instructions.save(SweepInstruction.builder().idempotencyKey(reference).externalReference(reference)
                .sourceShadowVaId(lloyds.getId()).targetVaId(home.getId()).amount(new BigDecimal(amount)).currencyCode("GBP")
                .rail(SweepRule.Rail.AUTO).status(InstructionStatus.INSTRUCTED).executionDate(executionDate)
                .instructedAt(LocalDateTime.now().minusMinutes(minutesAgo)).build());
    }

    @Test
    void aDebitLineWithTheEndToEndReferenceConfirmsTheLegAndReleasesTheFloat() {
        run();
        SweepInstruction i = saved.get(0);
        BankStatementLine decoy = line(CreditDebit.D, "800", TODAY, null);   // same amount and date, but no reference
        BankStatementLine byReference = line(CreditDebit.D, "800", TODAY.plusDays(1), i.getExternalReference());

        assertThat(external.confirm(lloyds.getId(), List.of(decoy, byReference))).isEqualTo(1);

        assertThat(i.getStatus()).isEqualTo(InstructionStatus.SETTLED);
        assertThat(i.getConfirmedByLineId()).isEqualTo(byReference.getId());
        assertThat(i.getSettledAt()).isNotNull();
        assertThat(executionsById.get(i.getSweepExecutionId()).getStatus()).isEqualTo(SweepExecution.ExecutionStatus.SETTLED);
        assertThat(executionsById.get(i.getSweepExecutionId()).getSettledAt()).isNotNull();
        assertThat(lloyds.getBankBalanceCommitted()).isEqualByComparingTo("0");
        assertThat(home.getCurrentBalance()).isEqualByComparingTo("5800");   // the booking stands
        assertThat(rule.getTotalSwept()).isEqualByComparingTo("800");
    }

    @Test
    void withoutTheReferenceADebitOfTheSameAmountValuedOnTheExecutionDateConfirms() {
        run();
        SweepInstruction i = saved.get(0);
        List<BankStatementLine> near = List.of(line(CreditDebit.C, "800", TODAY, null), line(CreditDebit.D, "800", TODAY.minusDays(1), null),
                line(CreditDebit.D, "799.99", TODAY, null));
        assertThat(external.confirm(lloyds.getId(), near)).isZero();

        BankStatementLine match = line(CreditDebit.D, "800.00", TODAY, null);
        assertThat(external.confirm(lloyds.getId(), List.of(match))).isEqualTo(1);
        assertThat(i.getStatus()).isEqualTo(InstructionStatus.SETTLED);
        assertThat(i.getConfirmedByLineId()).isEqualTo(match.getId());
        assertThat(executionsById.get(i.getSweepExecutionId()).getStatus()).isEqualTo(SweepExecution.ExecutionStatus.SETTLED);
    }

    @Test
    void aLineConfirmsOneInstructionOnlyAndAReferenceMatchComesFirst() {
        SweepInstruction older = pending("XSWOLDER", "800", TODAY, 10);
        SweepInstruction newer = pending("XSWNEWER", "800", TODAY, 5);
        // The only line carries the newer one's reference: it is the newer one's, though the older matches by amount.
        BankStatementLine line = line(CreditDebit.D, "800", TODAY, null);
        line.setCustomerReference("XSWNEWER");

        assertThat(external.confirm(lloyds.getId(), List.of(line))).isEqualTo(1);
        assertThat(newer.getStatus()).isEqualTo(InstructionStatus.SETTLED);
        assertThat(older.getStatus()).isEqualTo(InstructionStatus.INSTRUCTED);

        // Two by amount, one line: the oldest takes it, the other stays pending.
        SweepInstruction a = pending("XSWA", "500", TODAY, 4);
        SweepInstruction b = pending("XSWB", "500", TODAY, 3);
        assertThat(external.confirm(lloyds.getId(), List.of(line(CreditDebit.D, "500", TODAY, null)))).isEqualTo(1);
        assertThat(a.getStatus()).isEqualTo(InstructionStatus.SETTLED);
        assertThat(b.getStatus()).isEqualTo(InstructionStatus.INSTRUCTED);
    }

    @Test
    void noMatchingLineLeavesTheLegInFlight() {
        run();
        SweepInstruction i = saved.get(0);

        assertThat(external.confirm(lloyds.getId(), List.of(line(CreditDebit.D, "120.50", TODAY, "SOMETHING-ELSE")))).isZero();

        assertThat(i.getStatus()).isEqualTo(InstructionStatus.INSTRUCTED);
        assertThat(executionsById.get(i.getSweepExecutionId()).getStatus()).isEqualTo(SweepExecution.ExecutionStatus.COMMITTED);
        assertThat(lloyds.getBankBalanceCommitted()).isEqualByComparingTo("800");
    }

    // ---- expiry (D7) -----------------------------------------------------------------------------------------

    @Test
    void aLegIsOverdueMoreThanTwoBusinessDaysAfterItsExecutionDate() {
        SweepInstruction monday = pending("XSWMON", "800", LocalDate.of(2026, 11, 9), 1);

        assertThat(external.overdue(LocalDate.of(2026, 11, 10))).isEmpty();   // 1 business day
        assertThat(external.overdue(LocalDate.of(2026, 11, 11))).isEmpty();   // 2
        assertThat(external.overdue(LocalDate.of(2026, 11, 12))).containsExactly(monday);
    }

    @Test
    void theWindowCountsBusinessDaysAcrossAWeekend() {
        doAnswer(i -> {
            LocalDate d = ((LocalDate) i.getArgument(1)).minusDays(1);
            while (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) d = d.minusDays(1);
            return d;
        }).when(calendar).previousBusinessDay(anyString(), any());
        SweepInstruction thursday = pending("XSWTHU", "800", LocalDate.of(2026, 11, 12), 1);

        assertThat(external.overdue(LocalDate.of(2026, 11, 13))).isEmpty();   // Friday, 1 business day
        assertThat(external.overdue(LocalDate.of(2026, 11, 16))).isEmpty();   // Monday, 2 (4 calendar days)
        assertThat(external.overdue(LocalDate.of(2026, 11, 17))).containsExactly(thursday);
        thursday.setStatus(InstructionStatus.SETTLED);
        assertThat(external.overdue(LocalDate.of(2026, 11, 17))).isEmpty();
    }

    @Test
    void theDailyJobExpiresEachOverdueLegAndOneFailureDoesNotStopTheOthers() {
        ExternalSweepService svc = mock(ExternalSweepService.class);
        SweepInstruction a = pending("XSWA", "800", TODAY.minusDays(5), 2);
        SweepInstruction b = pending("XSWB", "900", TODAY.minusDays(5), 1);
        when(svc.overdue(TODAY)).thenReturn(List.of(a, b));
        when(svc.fail(a.getId(), InstructionStatus.EXPIRED, null)).thenThrow(new BusinessException("boom"));

        new ExternalSweepExpiryScheduler(svc).expireOverdue();

        verify(svc).fail(b.getId(), InstructionStatus.EXPIRED, null);
    }

    // ---- failure (R16; acceptance 5) -------------------------------------------------------------------------

    @Test
    void anExpiredLegReversesTheTargetRefundsOnlyTheInstructionFeeAndPutsBackTheSourceLedger() {
        run();
        SweepInstruction i = saved.get(0);

        external.fail(i.getId(), InstructionStatus.EXPIRED, null);

        assertThat(i.getStatus()).isEqualTo(InstructionStatus.EXPIRED);
        assertThat(i.getRejectedAt()).isNotNull();
        assertThat(i.getRejectionReason()).isEqualTo("not confirmed by the other bank's statement within 2 business days");
        // Target: credit reversed on the ledger, the bank balance and the physical account; +25 instruction fee back.
        assertThat(home.getCurrentBalance()).isEqualByComparingTo("5025");
        assertThat(home.getBankBalance()).isEqualByComparingTo("5000");
        assertThat(home.getBankAvailableBalance()).isEqualByComparingTo("5000");
        assertThat(homePhysical.getCurrentBalance()).isEqualByComparingTo("5000");
        assertThat(settlement.getCurrentBalance()).isEqualByComparingTo("75");
        verify(txns, never()).findByReferenceNumber("FEE-SWEEP_EXECUTION_FEE");   // the standard fee stands
        // Source: no statement since the instruction, so the instructed debit is put back on its ledger.
        assertThat(lloyds.getCurrentBalance()).isEqualByComparingTo("1000");
        assertThat(lloyds.getBankBalance()).isEqualByComparingTo("1000");
        assertThat(lloyds.getBankBalanceCommitted()).isEqualByComparingTo("0");
        assertThat(rule.getTotalSwept()).isEqualByComparingTo("0");
        SweepExecution e = executionsById.get(i.getSweepExecutionId());
        assertThat(e.getStatus()).isEqualTo(SweepExecution.ExecutionStatus.FAILED);
        assertThat(e.getErrorMessage()).contains(i.getExternalReference(), "EXPIRED");
        // 2 sweep legs + target, source and the fee pair reversed
        verify(txns, times(6)).save(any());
        verify(notifications).notifyOnce(eq(lloyds.getCorporateId()), any(), any(), contains("LLOYDS-GBP"), anyString(),
                eq("SWEEP_INSTRUCTION"), eq(i.getId()), eq(i.getExternalReference()), any(), any());
    }

    @Test
    void afterAnEndOfDayStatementTheSourceLedgerIsLeftAsTheStatementSetIt() {
        run();
        SweepInstruction i = saved.get(0);
        when(statements.existsByShadowVaIdAndKindAndStatusAndOlderFalseAndCbsFedFalseAndCreatedAtAfter(
                lloyds.getId(), Kind.END_OF_DAY, BankStatement.Status.APPLIED, i.getInstructedAt())).thenReturn(true);
        lloyds.setCurrentBalance(new BigDecimal("1000"));   // as the statement set it

        external.fail(i.getId(), InstructionStatus.EXPIRED, null);

        assertThat(lloyds.getCurrentBalance()).isEqualByComparingTo("1000");
        assertThat(lloyds.getBankBalanceCommitted()).isEqualByComparingTo("0");
        assertThat(home.getCurrentBalance()).isEqualByComparingTo("5025");
    }

    @Test
    void markRejectedFailsTheLegNowNeverRefusedAndOnlyOnce() {
        run();
        SweepInstruction i = saved.get(0);
        home.setCurrentBalance(new BigDecimal("300"));   // the swept cash already spent: the reversal overdraws it

        external.fail(i.getId(), InstructionStatus.REJECTED, " AC04 account closed ");

        assertThat(i.getStatus()).isEqualTo(InstructionStatus.REJECTED);
        assertThat(i.getRejectionReason()).isEqualTo("AC04 account closed");
        assertThat(home.getCurrentBalance()).isEqualByComparingTo("-475");
        assertThat(executionsById.get(i.getSweepExecutionId()).getErrorMessage()).contains("REJECTED", "AC04 account closed");

        assertThatThrownBy(() -> external.fail(i.getId(), InstructionStatus.REJECTED, "again"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("is REJECTED");
        assertThat(home.getCurrentBalance()).isEqualByComparingTo("-475");
        verify(notifications, times(1)).notifyOnce(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void aConfirmedLegCannotFail() {
        run();
        SweepInstruction i = saved.get(0);
        external.confirm(lloyds.getId(), List.of(line(CreditDebit.D, "800", TODAY, i.getExternalReference())));

        assertThatThrownBy(() -> external.fail(i.getId(), InstructionStatus.EXPIRED, null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("is SETTLED");
        assertThat(home.getCurrentBalance()).isEqualByComparingTo("5800");
    }

    // ---- Phase 5 A: the stored statement confirms before anything fails ------------------------------------------

    /** An APPLIED end-of-day statement already stored for Lloyds (the intake hook failed on it) with these lines. */
    private void storedStatement(BankStatementLine... stored) {
        BankStatement s = new BankStatement();
        s.setId(UUID.randomUUID());
        s.setStatementDate(TODAY);
        when(statements.findByShadowVaIdAndKindAndStatusAndCbsFedFalseAndStatementDateGreaterThanEqual(
                lloyds.getId(), Kind.END_OF_DAY, BankStatement.Status.APPLIED, TODAY)).thenReturn(List.of(s));
        when(statementLines.findByBankStatementIdInAndStatus(List.of(s.getId()), BankStatementLine.Status.FINAL))
                .thenReturn(List.of(stored));
    }

    @Test
    void theDailyJobConfirmsFromAStoredStatementInsteadOfExpiringMoneyThatMoved() {
        run();
        SweepInstruction i = saved.get(0);
        storedStatement(line(CreditDebit.D, "800", TODAY, i.getExternalReference()));

        assertThat(external.fail(i.getId(), InstructionStatus.EXPIRED, null).getStatus()).isEqualTo(InstructionStatus.SETTLED);

        assertThat(executionsById.get(i.getSweepExecutionId()).getStatus()).isEqualTo(SweepExecution.ExecutionStatus.SETTLED);
        assertThat(home.getCurrentBalance()).isEqualByComparingTo("5800");   // nothing reversed
        assertThat(lloyds.getBankBalanceCommitted()).isEqualByComparingTo("0");
        verifyNoInteractions(notifications);
    }

    @Test
    void markRejectedIsRefusedWhenTheStoredStatementShowsTheDebitAndTheLegIsConfirmed() {
        run();
        SweepInstruction i = saved.get(0);
        storedStatement(line(CreditDebit.D, "800", TODAY, null));   // no reference: amount + value date

        assertThatThrownBy(() -> external.fail(i.getId(), InstructionStatus.REJECTED, "AC04"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("the bank's statement shows this debit: 800.00 GBP valued " + TODAY + ", bank reference LL800");

        assertThat(i.getStatus()).isEqualTo(InstructionStatus.SETTLED);
        assertThat(home.getCurrentBalance()).isEqualByComparingTo("5800");
    }

    @Test
    void aStoredLineAlreadyUsedOrNotMatchingDoesNotStopTheFailure() {
        run();
        SweepInstruction i = saved.get(0);
        BankStatementLine used = line(CreditDebit.D, "800", TODAY, null);
        storedStatement(used, line(CreditDebit.D, "120", TODAY, null));
        when(instructions.usedLines(any())).thenReturn(List.of(used.getId()));   // confirms another instruction

        external.fail(i.getId(), InstructionStatus.REJECTED, "AC04");

        assertThat(i.getStatus()).isEqualTo(InstructionStatus.REJECTED);
        assertThat(home.getCurrentBalance()).isEqualByComparingTo("5025");
    }

    // ---- Phase 5 B: one caller moves a pending leg -------------------------------------------------------------

    @Test
    void theSecondCallerLosesTheClaimAndReversesNothing() {
        run();
        SweepInstruction i = saved.get(0);
        // The daily job claimed it first: the row is EXPIRED, this caller still holds its INSTRUCTED copy.
        doReturn(0).when(instructions).claim(eq(i.getId()), any(), any());

        assertThatThrownBy(() -> external.fail(i.getId(), InstructionStatus.REJECTED, "AC04"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("no longer pending");
        assertThat(external.confirm(lloyds.getId(), List.of(line(CreditDebit.D, "800", TODAY, i.getExternalReference())))).isZero();

        assertThat(i.getStatus()).isEqualTo(InstructionStatus.INSTRUCTED);
        assertThat(home.getCurrentBalance()).isEqualByComparingTo("5800");
        assertThat(lloyds.getBankBalanceCommitted()).isEqualByComparingTo("800");
        assertThat(rule.getTotalSwept()).isEqualByComparingTo("800");
        assertThat(executionsById.get(i.getSweepExecutionId()).getStatus()).isEqualTo(SweepExecution.ExecutionStatus.COMMITTED);
        verify(txns, times(2)).save(any());   // only the sweep's own legs
        verifyNoInteractions(notifications);
    }

    // ---- message content ------------------------------------------------------------------------------------

    @Test
    void mt101IsTheBlock4BodyOfARequestForTransferFromTheOtherBankToTheHomeBank() {
        String mt = ExternalSweepService.mt101("XSW0123456789ABC", lloyds, home, "Albion Engineering Ltd",
                new BigDecimal("1234.5"), "GBP", LocalDate.of(2026, 11, 13), "Cash concentration SWE-1");
        assertThat(mt).isEqualTo("""
                :20:XSW0123456789ABC
                :28D:1/1
                :50H:/GB29LOYD30963412345678
                Albion Engineering Ltd
                :52A:LOYDGB2LXXX
                :30:261113
                :21:XSW0123456789ABC
                :32B:GBP1234,50
                :57A:HBUKGB4BXXX
                :59:/GB11HBUK40051512345678
                Albion Engineering Ltd
                :70:Cash concentration SWE-1
                :71A:SHA""");
        assertThat(ExternalSweepService.swiftAmount(new BigDecimal("1000"), "JPY")).isEqualTo("1000,");
    }

    @Test
    void pain001DebitsTheAccountAtTheOtherBankAndCreditsTheHomeBankAccount() {
        String xml = external.pain001("XSW0123456789ABC", lloyds, home, "Albion Engineering Ltd",
                new BigDecimal("1234.50"), "GBP", LocalDate.of(2026, 11, 13), "Cash concentration SWE-1");
        assertThat(xml).contains("urn:iso:std:iso:20022:tech:xsd:pain.001.001.09")
                .containsSubsequence(
                        "<MsgId>XSW0123456789ABC</MsgId>",
                        "<CtrlSum>1234.50</CtrlSum>",
                        "<Dt>2026-11-13</Dt>",
                        "<Dbtr>", "<Nm>Albion Engineering Ltd</Nm>",
                        "<DbtrAcct>", "<IBAN>GB29LOYD30963412345678</IBAN>",
                        "<DbtrAgt>", "<BICFI>LOYDGB2LXXX</BICFI>",
                        "<EndToEndId>XSW0123456789ABC</EndToEndId>",
                        "<InstdAmt Ccy=\"GBP\">1234.50</InstdAmt>",
                        "<CdtrAgt>", "<BICFI>HBUKGB4BXXX</BICFI>",
                        "<Cdtr>", "<Nm>Albion Engineering Ltd</Nm>",
                        "<CdtrAcct>", "<IBAN>GB11HBUK40051512345678</IBAN>",
                        "<Ustrd>Cash concentration SWE-1</Ustrd>");
    }
}
