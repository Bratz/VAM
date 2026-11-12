package com.bank.vam.statement;

import com.bank.vam.config.HomeBankProperties;
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
import com.bank.vam.statement.BankStatement.Status;
import com.bank.vam.statement.BankStatementFile.Channel;
import com.bank.vam.statement.parse.ParseResult;
import com.bank.vam.statement.parse.ParseResult.ParseError;
import com.bank.vam.statement.parse.ParsedLine;
import com.bank.vam.statement.parse.ParsedStatement;
import com.bank.vam.statement.parse.ParsedStatement.Balance;
import com.bank.vam.statement.parse.ParsedStatement.Kind;
import com.bank.vam.statement.parse.StatementParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Takes in a bank statement file (spec R3–R13): records the file, then each statement in its own transaction — matched
 * to a physical account and its shadow, checked (duplicate, sequence gap, opening vs previous closing, older than the
 * latest), stored with its lines and applied. End of day: the statement wins (D4) — the shadow's bank balance,
 * available balance and as-of, the physical account and that day's snapshot. Intraday (D8): available balance and
 * provisional lines only. One statement that fails does not stop the others.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StatementIntakeService {

    /** As-of time of an end-of-day balance: the end of its statement date. */
    private static final LocalTime END_OF_DAY = LocalTime.of(23, 59, 59);

    private final BankStatementFileRepository files;
    private final BankStatementRepository statements;
    private final BankStatementLineRepository lines;
    private final PhysicalAccountRepository accounts;
    private final VirtualAccountRepository shadows;
    private final ShadowBalanceSnapshotRepository snapshots;
    private final PlatformTransactionManager transactionManager;
    private final NotificationService notifications;

    public record IntakeResult(UUID fileId, String fileName, BankStatementFile.Status status, String error,
                               int applied, int duplicates, int refused, List<StatementResult> statements) {
    }

    public record StatementResult(UUID id, String statementId, String account, Kind kind, LocalDate statementDate,
                                  Status status, String reason, boolean gap, boolean breakFlag, BigDecimal differenceToPlatform,
                                  boolean older, boolean cbsFed) {

        static StatementResult of(BankStatement s) {
            return new StatementResult(s.getId(), s.getStatementId(), s.getIban() != null ? s.getIban() : s.getAccountNumber(),
                    s.getKind(), s.getStatementDate(), s.getStatus(), s.getReason(), s.isGap(), s.isBreakFlag(),
                    s.getDifferenceToPlatform(), s.isOlder(), s.isCbsFed());
        }
    }

    public IntakeResult intake(byte[] content, String fileName, Channel channel) {
        ParseResult parsed = StatementParser.parse(content, fileName);
        BankStatementFile file = new BankStatementFile();
        file.setFileName(cut(fileName, 255));
        file.setChannel(channel);
        file.setReceivedAt(LocalDateTime.now());
        String fileError = parsed.errors().stream().filter(e -> e.statementIndex() == null).map(ParseError::message)
                .collect(Collectors.joining("; "));
        file.setStatus(fileError.isEmpty() ? BankStatementFile.Status.PROCESSED : BankStatementFile.Status.FAILED);
        file.setError(fileError.isEmpty() ? null : cut(fileError, 1000));
        file = files.save(file);
        UUID fileId = file.getId();

        List<BankStatement> taken = new ArrayList<>();
        for (ParseError e : parsed.errors()) {
            if (e.statementIndex() == null) continue;
            BankStatement s = new BankStatement();
            s.setFileId(fileId);
            s.setStatementId(cut(e.statementId(), 35));
            taken.add(statements.save(refuse(s, "statement " + e.statementIndex() + " unreadable: " + e.message())));
        }
        // Each statement in its own transaction: one that fails is refused, the others stand (as sweeps per source).
        TransactionTemplate own = new TransactionTemplate(transactionManager);
        own.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        for (ParsedStatement p : parsed.statements()) {
            try {
                taken.add(own.execute(status -> take(fileId, p)));
            } catch (RuntimeException e) {
                log.warn("Statement {} for {} in {} not applied", p.statementId(), account(p), fileName, e);
                taken.add(statements.save(refuse(header(fileId, p), "not applied: " + e.getMessage())));
            }
        }

        file.setAppliedCount(count(taken, Status.APPLIED));
        file.setDuplicateCount(count(taken, Status.DUPLICATE));
        file.setRefusedCount(count(taken, Status.REFUSED));
        files.save(file);
        log.info("Statement file {} ({}): {} applied, {} duplicate, {} refused{}", fileName, channel, file.getAppliedCount(),
                file.getDuplicateCount(), file.getRefusedCount(), fileError.isEmpty() ? "" : ", unreadable: " + fileError);
        IntakeResult result = new IntakeResult(fileId, fileName, file.getStatus(), file.getError(), file.getAppliedCount(),
                file.getDuplicateCount(), file.getRefusedCount(), taken.stream().map(StatementResult::of).toList());
        alert(result, channel);
        return result;
    }

    /**
     * R2a: an unreadable file or refused statements → one alert for bank operations (no corporate: only the bank's view
     * of the bell shows it). A failing alert never fails the intake — the file is already taken in.
     * ponytail: no action link until Phase 5 gives the intake log a page to open.
     */
    private void alert(IntakeResult r, Channel channel) {
        boolean failed = r.status() == BankStatementFile.Status.FAILED;
        if (!failed && r.refused() == 0) return;
        String message = failed ? "The bank statement file could not be read (" + channel + "): " + r.error()
                : r.refused() + " of " + r.statements().size() + " statements refused (" + r.applied() + " applied, "
                        + r.duplicates() + " duplicate; " + channel + "): " + r.statements().stream()
                        .filter(s -> s.status() == Status.REFUSED).map(s -> Objects.toString(s.statementId(), "?") + " — " + s.reason())
                        .collect(Collectors.joining("; "));
        try {
            notifications.notifyOnce(null, NotificationType.SYSTEM_ALERT, failed ? NotificationCategory.ERROR : NotificationCategory.WARNING,
                    cut((failed ? "Bank statement file not read: " : "Bank statements refused: ") + r.fileName(), 200), message,
                    "BANK_STATEMENT_FILE", r.fileId(), cut(r.fileName(), 100), "file-ingest", "Open intake log");
        } catch (RuntimeException e) {
            log.error("Alert for statement file {} not raised", r.fileName(), e);
        }
    }

    private BankStatement take(UUID fileId, ParsedStatement p) {
        BankStatement s = header(fileId, p);

        // R3: IBAN, else account number (+ BIC, an 8-character BIC being its 11-character ...XXX form)
        // An MT :25: IBAN failing its check digits arrives as an account number; it still matches an account stored with
        // that IBAN (most demo IBANs fail mod-97, and camt takes <IBAN> as given).
        PhysicalAccount pa = (p.iban() != null ? accounts.findByIban(p.iban()) : accounts.findByAccountNumber(p.accountNumber())
                .or(() -> accounts.findByIban(p.accountNumber().replaceAll("\\s+", "").toUpperCase()))).orElse(null);
        if (pa == null) return statements.save(refuse(s, "unknown account " + account(p)));
        s.setPhysicalAccountId(pa.getId());
        s.setCorporateId(pa.getCorporateId());
        if (p.iban() == null && p.bic() != null && pa.getBankCode() != null && !HomeBankProperties.sameBank(p.bic(), pa.getBankCode())) {
            return statements.save(refuse(s, "account " + p.accountNumber() + " is held at " + pa.getBankCode() + ", not " + p.bic()));
        }
        if (!p.currency().equalsIgnoreCase(pa.getCurrencyCode())) {   // R4
            return statements.save(refuse(s, "statement in " + p.currency() + ", account " + account(p) + " in " + pa.getCurrencyCode()));
        }
        VirtualAccount shadow = shadows.findByLinkedPhysicalAccountIdAndAccountCategory(pa.getId(), AccountCategory.PHYSICAL_MIRROR)
                .orElse(null);
        if (shadow == null) return statements.save(refuse(s, "account " + account(p) + " has no shadow account"));
        s.setShadowVaId(shadow.getId());

        // R5: stored once
        s.setDuplicateKey(duplicateKey(pa.getId(), p));
        if (statements.existsByDuplicateKeyAndStatus(s.getDuplicateKey(), Status.APPLIED)) {
            s.setStatus(Status.DUPLICATE);
            s.setReason("already taken in");
            return statements.save(s);
        }

        // R6–R8: flags; the statement is applied all the same
        boolean endOfDay = p.kind() == Kind.END_OF_DAY;
        LocalDate date = p.statementDate();
        s.setOlder(statements.findFirstByPhysicalAccountIdAndKindAndStatusOrderByStatementDateDescCreatedAtDesc(pa.getId(), Kind.END_OF_DAY, Status.APPLIED)
                .map(latest -> endOfDay ? date.isBefore(latest.getStatementDate()) : !date.isAfter(latest.getStatementDate()))
                .orElse(false));
        statements.findFirstByPhysicalAccountIdAndKindAndStatusAndStatementDateLessThanOrderByStatementDateDescCreatedAtDesc(
                pa.getId(), p.kind(), Status.APPLIED, date).ifPresent(previous -> {
            if (p.sequenceNumber() != null && previous.getSequenceNumber() != null && p.sequenceNumber() > previous.getSequenceNumber() + 1) {
                s.setGap(true);
            }
            // ponytail: page 2+ opens on page 1's intermediate closing (same date), so only a first page is compared
            if (endOfDay && (p.pageNumber() == null || p.pageNumber() <= 1) && p.openingBooked() != null
                    && previous.getClosingBooked() != null && p.openingBooked().amount().compareTo(previous.getClosingBooked()) != 0) {
                s.setBreakFlag(true);
            }
        });
        // Plan default: a statement for an account core banking feeds is stored, its balance stays core banking's
        s.setCbsFed(pa.getDataSource() == DataSource.CORE_BANKING || pa.getDataSource() == DataSource.INTERNAL_API);
        s.setStatus(Status.APPLIED);

        if (endOfDay) {
            supersedeProvisional(pa.getId(), date);
            if (!s.isCbsFed()) applyEndOfDay(s, p, pa, shadow);
        } else if (!s.isCbsFed() && !s.isOlder()) {
            applyIntraday(p, pa, shadow);
        }
        BankStatement saved = statements.save(s);
        BankStatementLine.Status lineStatus = endOfDay ? BankStatementLine.Status.FINAL
                : s.isOlder() ? BankStatementLine.Status.SUPERSEDED   // its day is already closed by an end-of-day statement
                : BankStatementLine.Status.PROVISIONAL;
        lines.saveAll(p.lines().stream().map(l -> line(saved, l, lineStatus)).toList());
        return saved;
    }

    /** D4, R7, R9, R10: the statement's closing balances become the bank's; an older one only fills its own day. */
    private void applyEndOfDay(BankStatement s, ParsedStatement p, PhysicalAccount pa, VirtualAccount shadow) {
        BigDecimal booked = p.closingBooked().amount();
        BigDecimal available = p.closingAvailable() != null ? p.closingAvailable().amount() : booked;
        if (s.isOlder()) {
            snapshot(shadow, p.statementDate(), booked, available, available);
            return;
        }
        BigDecimal before = shadow.getBankBalance();
        // Informational only: on an external account this is the bank's own day movements; the break flag is R8's alone
        if (before != null && booked.compareTo(before) != 0) s.setDifferenceToPlatform(booked.subtract(before));
        LocalDateTime asOf = p.statementDate().atTime(END_OF_DAY);
        shadow.setBankBalance(booked);
        shadow.setBankAvailableBalance(available);
        shadow.setBankBalanceAt(asOf);
        // The ledger agrees with the bank, as after a refresh (BalanceRefreshService); the difference to the platform records the gap.
        shadow.setCurrentBalance(booked);
        shadow.setAvailableBalance(booked);
        refreshed(shadow);
        pa.setCurrentBalance(booked);
        pa.setLedgerBalance(booked);
        pa.setAvailableBalance(available);
        followed(pa, asOf);
        snapshot(shadow, p.statementDate(), booked, available, shadow.getBankBalanceEffective());
    }

    /** D8, R9a: available balance and as-of only; booked balance and snapshot untouched. */
    private void applyIntraday(ParsedStatement p, PhysicalAccount pa, VirtualAccount shadow) {
        BigDecimal available = Stream.of(p.interimAvailable(), p.closingAvailable(), p.interimBooked())
                .filter(Objects::nonNull).map(Balance::amount).findFirst().orElse(null);
        if (available == null) return;   // an MT942 with only :90D:/:90C: totals moves no balance
        LocalDateTime asOf = p.reportedAt() != null ? p.reportedAt() : p.statementDate().atStartOfDay();
        shadow.setBankAvailableBalance(available);
        shadow.setBankBalanceAt(asOf);
        refreshed(shadow);
        pa.setAvailableBalance(available);
        followed(pa, asOf);
    }

    /** The bank's figures just arrived: the refresh stamp says so (the 15-minute refresh skips statement-fed shadows, R14). */
    private void refreshed(VirtualAccount shadow) {
        shadow.setLastBalanceRefreshAt(LocalDateTime.now());
        shadow.setLastBalanceRefreshStatus(VirtualAccount.BalanceRefreshStatus.SUCCESS);
        shadows.save(shadow);
    }

    private void followed(PhysicalAccount pa, LocalDateTime asOf) {
        pa.setBalanceAsOf(asOf);
        pa.setLastSyncAt(LocalDateTime.now());
        pa.setSyncStatus(PhysicalAccount.SyncStatus.SYNCED);
        pa.setSyncErrorMessage(null);
        accounts.save(pa);
    }

    /** R10: one row per shadow and statement date. */
    private void snapshot(VirtualAccount shadow, LocalDate date, BigDecimal booked, BigDecimal available, BigDecimal effective) {
        ShadowBalanceSnapshot snapshot = snapshots.findByShadowVaIdAndAsOf(shadow.getId(), date)
                .orElseGet(() -> ShadowBalanceSnapshot.builder().shadowVaId(shadow.getId()).corporateId(shadow.getCorporateId())
                        .currencyCode(shadow.getCurrencyCode()).asOf(date).build());
        snapshot.setBankBalance(booked);
        snapshot.setBankAvailableBalance(available);
        snapshot.setBankBalanceEffective(effective);
        snapshots.save(snapshot);
    }

    /**
     * R9a: the end-of-day statement replaces the provisional lines of its day and before. ponytail: all of them become
     * SUPERSEDED (kept, not deleted) — those it repeats by bank reference and those it does not alike, since its own
     * lines are the final ones either way.
     */
    private void supersedeProvisional(UUID accountId, LocalDate date) {
        List<UUID> intraday = statements.findByPhysicalAccountIdAndKindAndStatementDateLessThanEqual(accountId, Kind.INTRADAY, date)
                .stream().map(BaseEntity::getId).toList();
        if (intraday.isEmpty()) return;
        List<BankStatementLine> provisional = lines.findByBankStatementIdInAndStatus(intraday, BankStatementLine.Status.PROVISIONAL);
        provisional.forEach(l -> l.setStatus(BankStatementLine.Status.SUPERSEDED));
        lines.saveAll(provisional);
    }

    /**
     * Account + kind + statement id + sequence/page + date + closing booked (intraday: reported at), R5. ponytail: checked,
     * not enforced by a unique index — intake takes one file at a time (Phase 3); a partial unique index on APPLIED rows
     * (Flyway) if that changes.
     */
    private static String duplicateKey(UUID accountId, ParsedStatement p) {
        return String.join("|", accountId.toString(), p.kind().name(), Objects.toString(p.statementId(), ""),
                Objects.toString(p.sequenceNumber(), ""), Objects.toString(p.pageNumber(), ""), p.statementDate().toString(),
                p.kind() == Kind.END_OF_DAY ? p.closingBooked().amount().stripTrailingZeros().toPlainString()
                        : Objects.toString(p.reportedAt(), ""));
    }

    private static BankStatement header(UUID fileId, ParsedStatement p) {
        BankStatement s = new BankStatement();
        s.setFileId(fileId);
        s.setIban(p.iban());
        s.setAccountNumber(cut(p.accountNumber(), 50));
        s.setBic(p.bic());
        s.setFormat(p.format());
        s.setKind(p.kind());
        s.setStatementId(cut(p.statementId(), 35));
        s.setSequenceNumber(p.sequenceNumber());
        s.setPageNumber(p.pageNumber());
        s.setStatementDate(p.statementDate());
        s.setReportedAt(p.reportedAt());
        s.setCurrency(p.currency());
        s.setOpeningBooked(amount(p.openingBooked()));
        s.setClosingBooked(amount(p.closingBooked()));
        s.setClosingAvailable(amount(p.closingAvailable()));
        s.setInterimBooked(amount(p.interimBooked()));
        s.setInterimAvailable(amount(p.interimAvailable()));
        return s;
    }

    private static BankStatementLine line(BankStatement s, ParsedLine l, BankStatementLine.Status status) {
        BankStatementLine line = new BankStatementLine();
        line.setBankStatementId(s.getId());
        line.setPhysicalAccountId(s.getPhysicalAccountId());
        line.setValueDate(l.valueDate());
        line.setBookingDate(l.bookingDate());
        line.setAmount(l.amount());
        line.setCreditDebit(l.creditDebit());
        line.setReversal(l.reversal());
        line.setBankReference(cut(l.bankReference(), 35));
        line.setEndToEndId(cut(l.endToEndId(), 35));
        line.setCustomerReference(cut(l.customerReference(), 35));
        line.setRemittanceText(cut(l.remittanceText(), 2000));
        line.setTransactionCode(cut(l.transactionCode(), 35));
        line.setStatus(status);
        return line;
    }

    private static BankStatement refuse(BankStatement s, String reason) {
        s.setStatus(Status.REFUSED);
        s.setReason(cut(reason, 500));
        return s;
    }

    private static String account(ParsedStatement p) {
        return p.iban() != null ? p.iban() : (p.bic() != null ? p.bic() + "/" : "") + p.accountNumber();
    }

    private static BigDecimal amount(Balance b) {
        return b != null ? b.amount() : null;
    }

    private static int count(List<BankStatement> taken, Status status) {
        return (int) taken.stream().filter(s -> s.getStatus() == status).count();
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
