package com.bank.vam.statement;

import com.bank.vam.entity.BaseEntity;
import com.bank.vam.entity.PhysicalAccount;
import com.bank.vam.entity.PhysicalAccount.DataSource;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.VirtualAccount.AccountCategory;
import com.bank.vam.entity.notification.Notification.NotificationCategory;
import com.bank.vam.entity.notification.Notification.NotificationType;
import com.bank.vam.entity.treasury.ShadowBalanceSnapshot;
import com.bank.vam.repository.PhysicalAccountRepository;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.treasury.ShadowBalanceSnapshotRepository;
import com.bank.vam.service.notification.NotificationService;
import com.bank.vam.service.treasury.ExternalSweepService;
import com.bank.vam.statement.BankStatement.Status;
import com.bank.vam.statement.BankStatementFile.Channel;
import com.bank.vam.statement.StatementIntakeService.IntakeResult;
import com.bank.vam.statement.StatementIntakeService.StatementResult;
import com.bank.vam.statement.parse.ParsedStatement.Kind;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Spec acceptance 1–5 and 8 on the intake service, over in-memory repositories. */
class StatementIntakeServiceTest {

    private static final String GB07 = "GB07LOYD30963412345678";
    private static final LocalDate NOV11 = LocalDate.of(2026, 11, 11), NOV12 = LocalDate.of(2026, 11, 12);

    private final BankStatementFileRepository files = mock(BankStatementFileRepository.class);
    private final BankStatementRepository statementRepo = mock(BankStatementRepository.class);
    private final BankStatementLineRepository lineRepo = mock(BankStatementLineRepository.class);
    private final PhysicalAccountRepository accountRepo = mock(PhysicalAccountRepository.class);
    private final VirtualAccountRepository shadowRepo = mock(VirtualAccountRepository.class);
    private final ShadowBalanceSnapshotRepository snapshotRepo = mock(ShadowBalanceSnapshotRepository.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final ExternalSweepService externalSweeps = mock(ExternalSweepService.class);
    private final StatementIntakeService service = new StatementIntakeService(files, statementRepo, lineRepo, accountRepo,
            shadowRepo, snapshotRepo, mock(PlatformTransactionManager.class), notifications, externalSweeps);

    private final List<BankStatement> statements = new ArrayList<>();
    private final List<BankStatementLine> lines = new ArrayList<>();
    private final List<PhysicalAccount> accounts = new ArrayList<>();
    private final List<VirtualAccount> shadows = new ArrayList<>();
    private final List<ShadowBalanceSnapshot> snapshots = new ArrayList<>();

    StatementIntakeServiceTest() {
        when(files.save(any())).thenAnswer(i -> withId(i.getArgument(0)));
        when(statementRepo.save(any())).thenAnswer(i -> keep(statements, i.getArgument(0)));
        when(statementRepo.existsByDuplicateKeyAndStatus(any(), any())).thenAnswer(i -> statements.stream()
                .anyMatch(s -> i.getArgument(0).equals(s.getDuplicateKey()) && s.getStatus() == i.getArgument(1)));
        when(statementRepo.findFirstByPhysicalAccountIdAndKindAndStatusOrderByStatementDateDescCreatedAtDesc(any(), any(), any()))
                .thenAnswer(i -> latest(s -> same(s, i.getArgument(0), i.getArgument(1), i.getArgument(2))));
        when(statementRepo.findFirstByPhysicalAccountIdAndKindAndStatusAndStatementDateLessThanOrderByStatementDateDescCreatedAtDesc(any(), any(), any(), any()))
                .thenAnswer(i -> latest(s -> same(s, i.getArgument(0), i.getArgument(1), i.getArgument(2))
                        && s.getStatementDate().isBefore(i.getArgument(3))));
        when(statementRepo.findByPhysicalAccountIdAndKindAndStatementDateLessThanEqual(any(), any(), any())).thenAnswer(i -> statements.stream()
                .filter(s -> i.getArgument(0).equals(s.getPhysicalAccountId()) && s.getKind() == i.getArgument(1)
                        && !s.getStatementDate().isAfter(i.getArgument(2))).toList());
        when(lineRepo.saveAll(any())).thenAnswer(i -> {
            List<BankStatementLine> saved = new ArrayList<>();
            for (BankStatementLine l : (Iterable<BankStatementLine>) i.getArgument(0)) saved.add(keep(lines, l));
            return saved;
        });
        when(lineRepo.findByBankStatementIdInAndStatus(any(), any())).thenAnswer(i -> lines.stream()
                .filter(l -> ((Collection<?>) i.getArgument(0)).contains(l.getBankStatementId()) && l.getStatus() == i.getArgument(1)).toList());
        when(accountRepo.findByIban(any())).thenAnswer(i -> accounts.stream().filter(a -> i.getArgument(0).equals(a.getIban())).findFirst());
        when(accountRepo.findByAccountNumber(any())).thenAnswer(i -> accounts.stream()
                .filter(a -> i.getArgument(0).equals(a.getAccountNumber())).findFirst());
        when(accountRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(shadowRepo.findByLinkedPhysicalAccountIdAndAccountCategory(any(), any())).thenAnswer(i -> shadows.stream()
                .filter(v -> i.getArgument(0).equals(v.getLinkedPhysicalAccountId()) && v.getAccountCategory() == i.getArgument(1)).findFirst());
        when(shadowRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(snapshotRepo.findByShadowVaIdAndAsOf(any(), any())).thenAnswer(i -> snapshots.stream()
                .filter(s -> s.getShadowVaId().equals(i.getArgument(0)) && s.getAsOf().equals(i.getArgument(1))).findFirst());
        when(snapshotRepo.save(any())).thenAnswer(i -> keep(snapshots, i.getArgument(0)));
    }

    // ---- acceptance 1: an MT940 updates the shadow's balance, available balance and as-of ----

    @Test
    void acceptance1_mt940_setsShadowAndPhysicalAccount_snapshot_finalLines() throws Exception {
        VirtualAccount shadow = account(GB07, null, null, "GBP", DataSource.SWIFT_MT940, "125000.00");

        IntakeResult r = service.intake(file("mt940-single.sta"), "mt940-single.sta", Channel.INBOX);

        assertEquals(BankStatementFile.Status.PROCESSED, r.status());
        assertEquals(1, r.applied());
        StatementResult s = r.statements().get(0);
        assertEquals(Status.APPLIED, s.status());
        // no previous statement: no break; the difference to the shadow's bank balance before (125000.00) is informational
        assertFalse(s.gap() || s.older() || s.cbsFed() || s.breakFlag());
        assertBalance("10330.25", s.differenceToPlatform());
        assertEquals(VirtualAccount.BalanceRefreshStatus.SUCCESS, shadow.getLastBalanceRefreshStatus());   // R14: not "not connected"
        assertNotNull(shadow.getLastBalanceRefreshAt());
        assertBalance("135330.25", shadow.getBankBalance());
        assertBalance("134830.25", shadow.getBankAvailableBalance());
        assertBalance("135330.25", shadow.getCurrentBalance());
        assertEquals(NOV11.atTime(23, 59, 59), shadow.getBankBalanceAt());
        PhysicalAccount pa = accounts.get(0);
        assertBalance("135330.25", pa.getCurrentBalance());
        assertBalance("134830.25", pa.getAvailableBalance());
        assertEquals(NOV11.atTime(23, 59, 59), pa.getBalanceAsOf());
        ShadowBalanceSnapshot snap = snapshot(shadow, NOV11);
        assertBalance("135330.25", snap.getBankBalance());
        assertBalance("134830.25", snap.getBankAvailableBalance());
        assertEquals(3, lines.size());
        assertTrue(lines.stream().allMatch(l -> l.getStatus() == BankStatementLine.Status.FINAL && pa.getId().equals(l.getPhysicalAccountId())));
        assertEquals("LL2611110001", lines.get(0).getBankReference());
        verify(notifications, never()).notifyOnce(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    // ---- acceptance 2: camt.053 with two accounts ----

    @Test
    void acceptance2_camt053TwoAccounts_bothApplied_accountNumberMatchedWith8CharacterBic() throws Exception {
        VirtualAccount gbp = account(GB07, null, null, "GBP", DataSource.SWIFT_CAMT053, "125000.00");
        VirtualAccount usd = account(null, "000123456789", "CHASUS33XXX", "USD", DataSource.SWIFT_CAMT053, "-1500.00");

        IntakeResult r = service.intake(file("camt053-v08-two-accounts.xml"), "camt.xml", Channel.API);

        assertEquals(2, r.applied());
        assertBalance("127600.00", gbp.getBankBalance());
        assertBalance("8500.00", usd.getBankBalance());
        assertBalance("8500.00", usd.getBankAvailableBalance());   // no CLAV: available = closing booked
    }

    @Test
    void acceptance2_unknownAccountRefused_theOtherApplied() throws Exception {
        VirtualAccount gbp = account(GB07, null, null, "GBP", DataSource.SWIFT_CAMT053, "125000.00");

        IntakeResult r = service.intake(file("camt053-v08-two-accounts.xml"), "camt.xml", Channel.API);

        assertEquals(1, r.applied());
        assertEquals(1, r.refused());
        StatementResult refused = only(r, Status.REFUSED);
        assertEquals("CH053-20261111", refused.statementId());
        assertTrue(refused.reason().contains("unknown account CHASUS33/000123456789"), refused.reason());
        assertBalance("127600.00", gbp.getBankBalance());
        assertTrue(lines.stream().noneMatch(l -> String.valueOf(l.getBankReference()).startsWith("CH")));
        // R2a: one alert for bank operations (no corporate), naming the refused statement
        verify(notifications).notifyOnce(isNull(), eq(NotificationType.SYSTEM_ALERT), eq(NotificationCategory.WARNING),
                eq("Bank statements refused: camt.xml"), contains("CH053-20261111 — unknown account"), eq("BANK_STATEMENT_FILE"),
                eq(r.fileId()), eq("camt.xml"), eq("file-ingest"), eq("Open intake log"));
    }

    @Test
    void mt940IbanFailingCheckDigits_stillMatchesTheAccountStoredWithIt() {
        String fake = "US33BOFA22000087654321";   // demo-data IBAN, mod-97 fails: the parser reads it as an account number
        VirtualAccount shadow = account(fake, "8765432101", "BOFAUS3NXXX", "USD", DataSource.SWIFT_MT942, "6045000.00");

        IntakeResult r = service.intake(String.join("\n", ":20:BOFA261111USD", ":25:" + fake, ":28C:101/1",
                ":60F:C261110USD6045000,00", ":62F:C261111USD6219999,50", "-").getBytes(StandardCharsets.UTF_8), "bofa.sta", Channel.API);

        assertEquals(1, r.applied(), () -> r.statements().get(0).reason());
        assertBalance("6219999.50", shadow.getBankBalance());
    }

    @Test
    void currencyMismatch_or_otherBic_refused() throws Exception {
        account(GB07, null, null, "EUR", DataSource.SWIFT_CAMT053, "0");
        account(null, "000123456789", "CITIUS33", "USD", DataSource.SWIFT_CAMT053, "0");

        IntakeResult r = service.intake(file("camt053-v08-two-accounts.xml"), "camt.xml", Channel.API);

        assertEquals(2, r.refused());
        assertEquals("statement in GBP, account " + GB07 + " in EUR", r.statements().get(0).reason());
        assertEquals("account 000123456789 is held at CITIUS33, not CHASUS33", r.statements().get(1).reason());
    }

    @Test
    void oneStatementFailing_isRefused_theOthersStand() throws Exception {
        VirtualAccount gbp = account(GB07, null, null, "GBP", DataSource.SWIFT_CAMT053, "125000.00");
        doThrow(new IllegalStateException("database hiccup")).when(accountRepo).findByAccountNumber(any());

        IntakeResult r = service.intake(file("camt053-v08-two-accounts.xml"), "camt.xml", Channel.API);

        assertEquals(1, r.applied());
        assertEquals("not applied: database hiccup", only(r, Status.REFUSED).reason());
        assertBalance("127600.00", gbp.getBankBalance());
    }

    // ---- acceptance 3: the same file again ----

    @Test
    void acceptance3_sameFileAgain_duplicate_nothingChanges() throws Exception {
        VirtualAccount shadow = account(GB07, null, null, "GBP", DataSource.SWIFT_MT940, "125000.00");
        service.intake(file("mt940-single.sta"), "a.sta", Channel.INBOX);
        shadow.setBankBalance(new BigDecimal("135000.00"));   // a payment made since

        IntakeResult again = service.intake(file("mt940-single.sta"), "a.sta", Channel.API);

        assertEquals(1, again.duplicates());
        assertEquals(0, again.applied());
        assertEquals("already taken in", again.statements().get(0).reason());
        assertBalance("135000.00", shadow.getBankBalance());
        assertEquals(3, lines.size());
        assertEquals(1, snapshots.size());
    }

    // ---- acceptance 4: last week's statement fills last week's snapshot only ----

    @Test
    void acceptance4_olderStatement_fillsItsOwnSnapshot_balanceUntouched() throws Exception {
        VirtualAccount shadow = account(GB07, null, null, "GBP", DataSource.SWIFT_MT940, "125000.00");
        service.intake(file("mt940-single.sta"), "today.sta", Channel.INBOX);

        IntakeResult r = service.intake(mt940("LL940-1104", "305", "261103", "118000,00", "261104", "119000,00",
                ":61:2611041104C1000,00NTRFNONREF//LLOLD1"), "old.sta", Channel.INBOX);

        StatementResult s = r.statements().get(0);
        assertEquals(Status.APPLIED, s.status());
        assertTrue(s.older());
        assertBalance("135330.25", shadow.getBankBalance());
        assertBalance("134830.25", shadow.getBankAvailableBalance());
        assertBalance("119000.00", snapshot(shadow, LocalDate.of(2026, 11, 4)).getBankBalance());
        assertBalance("135330.25", snapshot(shadow, NOV11).getBankBalance());
    }

    // ---- acceptance 5: break and sequence gap ----

    @Test
    void acceptance5_openingNotPreviousClosing_break_sequenceGap_appliedAnyway() throws Exception {
        VirtualAccount shadow = account(GB07, null, null, "GBP", DataSource.SWIFT_MT940, "125000.00");
        service.intake(file("mt940-single.sta"), "nov11.sta", Channel.INBOX);   // :28C:00312, closing 135330,25
        shadow.setBankBalance(new BigDecimal("135830.25"));   // the platform's own posting of 500 since

        IntakeResult r = service.intake(mt940("LL940-1112", "314", "261111", "135000,00", "261112", "136000,00",
                ":61:2611121112C1000,00NTRFNONREF//LLN1"), "nov12.sta", Channel.INBOX);

        StatementResult s = r.statements().get(0);
        assertEquals(Status.APPLIED, s.status());
        assertTrue(s.gap());        // 312 then 314
        assertTrue(s.breakFlag());  // opens 135000.00, previous closed 135330.25
        assertBalance("169.75", s.differenceToPlatform());   // 136000.00 − 135830.25
        assertBalance("136000.00", shadow.getBankBalance());
    }

    @Test
    void consecutive_noGap_openingMatches_noBreak_unpostedMovementIsOnlyADifference() throws Exception {
        account(GB07, null, null, "GBP", DataSource.SWIFT_MT940, "125000.00");
        service.intake(file("mt940-single.sta"), "nov11.sta", Channel.INBOX);

        StatementResult s = service.intake(mt940("LL940-1112", "313", "261111", "135330,25", "261112", "136330,25",
                ":61:2611121112C1000,00NTRFNONREF//LLN1"), "nov12.sta", Channel.INBOX).statements().get(0);

        assertFalse(s.gap() || s.older() || s.breakFlag());
        assertBalance("1000.00", s.differenceToPlatform());   // the credit the platform did not post: informational
    }

    // ---- acceptance 8: MT942 moves the available balance, the evening MT940 replaces it ----

    @Test
    void acceptance8_mt942ProvisionalThenMt940Supersedes() throws Exception {
        VirtualAccount shadow = account(GB07, null, null, "GBP", DataSource.SWIFT_MT940, "125000.00");
        service.intake(file("mt940-single.sta"), "nov11.sta", Channel.INBOX);
        String mt942 = String.join("\n", ":20:LL942-261112-1030", ":25:LOYDGB2L/" + GB07, ":28C:313/2", ":34F:GBPD0,",
                ":13D:2611121030+0000", ":61:2611121112C7300,00NTRFINV-2026-0502//LL2611120001",
                ":86:Helios Trading Ltd INV-2026-0502", ":61:2611121112D1250,40NTRFSUPP-889//LL2611120002",
                ":64:C261112GBP141379,85", "-");

        IntakeResult intraday = service.intake(mt942.getBytes(StandardCharsets.UTF_8), "nov12.942", Channel.INBOX);

        assertEquals(Kind.INTRADAY, intraday.statements().get(0).kind());
        assertBalance("141379.85", shadow.getBankAvailableBalance());
        assertBalance("135330.25", shadow.getBankBalance());   // booked untouched
        assertEquals(LocalDateTime.of(2026, 11, 12, 10, 30), shadow.getBankBalanceAt());
        assertTrue(snapshots.stream().noneMatch(s -> s.getAsOf().equals(NOV12)));
        List<BankStatementLine> provisional = lines.stream().filter(l -> l.getStatus() == BankStatementLine.Status.PROVISIONAL).toList();
        assertEquals(2, provisional.size());

        service.intake(mt940("LL940-1112", "313", "261111", "135330,25", "261112", "141379,85",
                ":61:2611121112C7300,00NTRFINV-2026-0502//LL2611120001"), "nov12.sta", Channel.INBOX);   // the debit not in it

        assertBalance("141379.85", shadow.getBankBalance());
        assertBalance("141379.85", snapshot(shadow, NOV12).getBankBalance());
        assertTrue(provisional.stream().allMatch(l -> l.getStatus() == BankStatementLine.Status.SUPERSEDED));
        assertEquals(0, lines.stream().filter(l -> l.getStatus() == BankStatementLine.Status.PROVISIONAL).count());
        assertEquals(1, lines.stream().filter(l -> l.getValueDate().equals(NOV12) && l.getStatus() == BankStatementLine.Status.FINAL).count());
    }

    // ---- plan defaults and file errors ----

    @Test
    void coreBankingFedAccount_storedAndFlagged_balanceLeftToCoreBanking() throws Exception {
        VirtualAccount shadow = account(GB07, null, null, "GBP", DataSource.CORE_BANKING, "125000.00");

        StatementResult s = service.intake(file("mt940-single.sta"), "cbs.sta", Channel.INBOX).statements().get(0);

        assertEquals(Status.APPLIED, s.status());
        assertTrue(s.cbsFed());
        assertBalance("125000.00", shadow.getBankBalance());
        assertTrue(snapshots.isEmpty());
        assertEquals(3, lines.size());
    }

    @Test
    void unreadableStatementsRefused_readableOnesApplied() throws Exception {
        account(GB07, null, null, "GBP", DataSource.SWIFT_MT940, "135330.25");

        IntakeResult r = service.intake(file("mt940-one-broken.sta"), "broken.sta", Channel.INBOX);

        assertEquals(BankStatementFile.Status.PROCESSED, r.status());
        assertEquals(2, r.applied());
        assertEquals(2, r.refused());
        assertTrue(r.statements().stream().filter(s -> s.status() == Status.REFUSED).allMatch(s -> s.reason().contains("unreadable")));
        StatementResult d = r.statements().stream().filter(s -> "LL940-D".equals(s.statementId())).findFirst().orElseThrow();
        assertTrue(d.gap() && d.breakFlag());   // 314 then 317; opens 135500.00 after A closed 135430.25
    }

    @Test
    void unreadableFile_failed() {
        IntakeResult r = service.intake("hello".getBytes(StandardCharsets.UTF_8), "x.txt", Channel.API);

        assertEquals(BankStatementFile.Status.FAILED, r.status());
        assertNotNull(r.error());
        assertTrue(r.statements().isEmpty());
        verify(notifications).notifyOnce(isNull(), eq(NotificationType.SYSTEM_ALERT), eq(NotificationCategory.ERROR),
                eq("Bank statement file not read: x.txt"), any(), any(), eq(r.fileId()), any(), any(), any());
    }

    // ---- external-bank sweeps R15, R17: an applied end-of-day statement is offered to the sweep confirmation ----

    @Test
    void endOfDayStatement_offersItsFinalLinesToSweepConfirmation_noBreak() throws Exception {
        VirtualAccount shadow = account(GB07, null, null, "GBP", DataSource.SWIFT_MT940, "125000.00");

        StatementResult s = service.intake(file("mt940-single.sta"), "mt940-single.sta", Channel.INBOX).statements().get(0);

        assertFalse(s.breakFlag());
        verify(externalSweeps).confirm(eq(shadow.getId()), argThat(l -> l.size() == 3
                && l.stream().allMatch(x -> x.getStatus() == BankStatementLine.Status.FINAL && s.id().equals(x.getBankStatementId()))));
    }

    @Test
    void sweepConfirmationThatFails_leavesTheStatementApplied() throws Exception {
        VirtualAccount shadow = account(GB07, null, null, "GBP", DataSource.SWIFT_MT940, "125000.00");
        doThrow(new IllegalStateException("boom")).when(externalSweeps).confirm(any(), any());

        IntakeResult r = service.intake(file("mt940-single.sta"), "mt940-single.sta", Channel.INBOX);

        assertEquals(1, r.applied());
        assertEquals(0, r.refused());
        assertEquals(Status.APPLIED, r.statements().get(0).status());
        assertBalance("135330.25", shadow.getBankBalance());
        assertEquals(3, lines.size());
    }

    @Test
    void intradayOrCoreBankingFedStatement_confirmsNothing() throws Exception {
        account(GB07, null, null, "GBP", DataSource.CORE_BANKING, "125000.00");
        service.intake(file("mt940-single.sta"), "cbs.sta", Channel.INBOX);
        service.intake(file("mt942-intraday.sta"), "mt942.sta", Channel.INBOX);

        verify(externalSweeps, never()).confirm(any(), any());
    }

    // ---- helpers ----

    private VirtualAccount account(String iban, String accountNumber, String bic, String ccy, DataSource source, String balance) {
        PhysicalAccount pa = PhysicalAccount.builder().iban(iban).accountNumber(accountNumber != null ? accountNumber : "PA-" + ccy)
                .bankCode(bic).currencyCode(ccy).dataSource(source).corporateId(UUID.randomUUID()).build();
        pa.setId(UUID.randomUUID());
        VirtualAccount shadow = VirtualAccount.builder().accountCategory(AccountCategory.PHYSICAL_MIRROR).linkedPhysicalAccountId(pa.getId())
                .currencyCode(ccy).corporateId(pa.getCorporateId()).bankBalance(new BigDecimal(balance))
                .currentBalance(new BigDecimal(balance)).build();
        shadow.setId(UUID.randomUUID());
        accounts.add(pa);
        shadows.add(shadow);
        return shadow;
    }

    private static byte[] mt940(String id, String seq, String openDate, String opening, String closeDate, String closing, String... lines) {
        List<String> rows = new ArrayList<>(List.of(":20:" + id, ":25:" + GB07, ":28C:" + seq + "/1", ":60F:C" + openDate + "GBP" + opening));
        rows.addAll(List.of(lines));
        rows.addAll(List.of(":62F:C" + closeDate + "GBP" + closing, "-"));
        return String.join("\n", rows).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] file(String name) throws Exception {
        try (InputStream in = StatementIntakeServiceTest.class.getResourceAsStream("/statements/" + name)) {
            return in.readAllBytes();
        }
    }

    private ShadowBalanceSnapshot snapshot(VirtualAccount shadow, LocalDate date) {
        return snapshots.stream().filter(s -> s.getShadowVaId().equals(shadow.getId()) && s.getAsOf().equals(date)).findFirst().orElseThrow();
    }

    private static StatementResult only(IntakeResult r, Status status) {
        List<StatementResult> found = r.statements().stream().filter(s -> s.status() == status).toList();
        assertEquals(1, found.size());
        return found.get(0);
    }

    private static boolean same(BankStatement s, UUID accountId, Kind kind, Status status) {
        return accountId.equals(s.getPhysicalAccountId()) && s.getKind() == kind && s.getStatus() == status;
    }

    private Optional<BankStatement> latest(Predicate<BankStatement> filter) {
        return statements.stream().filter(filter).reduce((a, b) -> b.getStatementDate().isBefore(a.getStatementDate()) ? a : b);
    }

    private static <T extends BaseEntity> T withId(T e) {
        if (e.getId() == null) e.setId(UUID.randomUUID());
        return e;
    }

    private static <T extends BaseEntity> T keep(List<T> store, T e) {
        if (e.getId() == null) {
            e.setId(UUID.randomUUID());
            store.add(e);
        }
        return e;
    }

    private static void assertBalance(String expected, BigDecimal actual) {
        assertNotNull(actual);
        assertEquals(0, new BigDecimal(expected).compareTo(actual), () -> "expected " + expected + " but was " + actual);
    }
}
