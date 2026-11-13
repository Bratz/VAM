package com.bank.vam.service.treasury;

import com.bank.vam.config.HomeBankProperties;
import com.bank.vam.entity.Transaction;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.notification.Notification.NotificationCategory;
import com.bank.vam.entity.notification.Notification.NotificationType;
import com.bank.vam.entity.treasury.ExternalMandate;
import com.bank.vam.entity.treasury.SweepExecution;
import com.bank.vam.entity.treasury.SweepInstruction;
import com.bank.vam.entity.treasury.SweepInstruction.InstructionStatus;
import com.bank.vam.entity.treasury.SweepRule;
import com.bank.vam.iso20022.dto.Iso20022PaymentDto.OutwardPaymentRequest;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.exception.ResourceNotFoundException;
import com.bank.vam.iso20022.util.Iso20022XmlBuilder;
import com.bank.vam.repository.PhysicalAccountRepository;
import com.bank.vam.repository.TransactionRepository;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.treasury.ExternalMandateRepository;
import com.bank.vam.repository.treasury.SweepExecutionRepository;
import com.bank.vam.repository.treasury.SweepInstructionRepository;
import com.bank.vam.repository.treasury.SweepRuleRepository;
import com.bank.vam.service.notification.NotificationService;
import com.bank.vam.service.treasury.cutoff.CutoffCalendarService;
import com.bank.vam.statement.BankStatement;
import com.bank.vam.statement.BankStatementLine;
import com.bank.vam.statement.BankStatementLineRepository;
import com.bank.vam.statement.BankStatementRepository;
import com.bank.vam.statement.StatementFreshness;
import com.bank.vam.statement.parse.ParsedLine.CreditDebit;
import com.bank.vam.statement.parse.ParsedStatement.Kind;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Currency;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

/**
 * The external leg of a sweep from an account at another bank (spec external-bank-sweeps R3, R8–R14, D4, D6, D8): the
 * run-time checks and sizing, and the request for transfer (MT101 or pain.001) the home bank produces under the
 * account's authority; then its confirmation by the other bank's statement or its failure (R15-R17, D5, D7). The
 * booking itself stays in {@link SweepService}; this class moves a balance only through the instruction fee and when a
 * leg fails (the booking reversed).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExternalSweepService {

    private final BankStatementRepository statements;
    private final BankStatementLineRepository lines;
    private final StatementFreshness freshness;
    private final SweepInstructionRepository instructions;
    private final ExternalMandateRepository mandates;
    private final FeePostingService fees;
    private final Iso20022XmlBuilder isoXml;
    private final CutoffCalendarService calendar;
    private final VirtualAccountRepository vas;
    private final TransactionRepository txns;
    private final PhysicalAccountRepository physicals;
    private final SweepExecutionRepository executions;
    private final SweepRuleRepository rules;
    private final NotificationService notifications;
    private final HomeBankProperties homeBank;

    /** Flat charge per instruction, in the account currency (D8). Field default for tests built with {@code new}. */
    @Value("${vam.sweeps.external-instruction-fee:25}")
    private BigDecimal instructionFee = new BigDecimal("25");

    private static final DateTimeFormatter YYMMDD = DateTimeFormatter.ofPattern("yyMMdd");

    /** D7: a leg not confirmed this many business days after its execution date fails. */
    static final int CONFIRMATION_WINDOW_BUSINESS_DAYS = 2;

    /** Pending at the other bank (R10) and failed (not counted against the daily limit), in the table's own statuses. */
    static final Set<InstructionStatus> IN_FLIGHT =
            EnumSet.of(InstructionStatus.PREPARED, InstructionStatus.INSTRUCTED, InstructionStatus.ACK_RECEIVED);
    static final Set<InstructionStatus> FAILED =
            EnumSet.of(InstructionStatus.REJECTED, InstructionStatus.NACKED, InstructionStatus.EXPIRED);

    /**
     * What to instruct for one external source, or why not ({@code skip} set). {@code balance} is the last end-of-day
     * statement's closing booked balance, null when there is none.
     */
    public record Plan(BigDecimal balance, BigDecimal amount, ExternalMandate authority, String skip) {
        static Plan skip(BigDecimal balance, String reason) {
            return new Plan(balance, BigDecimal.ZERO, null, reason);
        }
    }

    /**
     * R10, D6, R8, R3 in that order: nothing pending on the source; its last APPLIED end-of-day statement, not stale;
     * {@code size} applied to that statement's closing booked balance (never the ledger or an intraday figure); then
     * the first authority on the account that covers the amount, counting what it already instructed today.
     */
    public Plan plan(VirtualAccount source, LocalDate today, UnaryOperator<BigDecimal> size) {
        String va = source.getVaNumber();
        if (instructions.existsBySourceShadowVaIdAndStatusIn(source.getId(), IN_FLIGHT)) {
            return Plan.skip(null, "a sweep instruction on " + va + " is still pending at the other bank; it is not swept"
                    + " again until that one is confirmed or fails");
        }
        BankStatement eod = statements.findFirstByShadowVaIdAndKindAndStatusOrderByStatementDateDescCreatedAtDesc(
                source.getId(), Kind.END_OF_DAY, BankStatement.Status.APPLIED).orElse(null);
        if (eod == null || eod.getClosingBooked() == null) {
            return Plan.skip(null, "no end-of-day statement for " + va + ": an account at another bank is swept only on"
                    + " its statement balance");
        }
        BigDecimal balance = eod.getClosingBooked();
        if (freshness.stale(eod.getStatementDate(), source.getCurrencyCode(), today)) {
            return Plan.skip(balance, "stale balance: the last end-of-day statement for " + va + " is dated "
                    + eod.getStatementDate() + ", none for the last business day");
        }
        // Never instruct more than the statement shows: cut to the currency's decimals, rounding down.
        BigDecimal amount = size.apply(balance).setScale(decimals(source.getCurrencyCode()), RoundingMode.DOWN);
        if (amount.signum() <= 0) return Plan.skip(balance, "No funds to sweep or below threshold");

        List<String> refusals = new ArrayList<>();
        // ponytail: one instructed-today query per authority on the account; a handful at most
        for (ExternalMandate authority : mandates.findByShadowVaIdOrderByCreatedAtDesc(source.getId())) {
            String refusal = authority.refusal(amount, today, instructions.instructedOn(authority.getId(), today, FAILED));
            if (refusal == null) return new Plan(balance, amount, authority, null);
            refusals.add(refusal);
        }
        return Plan.skip(balance, "no authority covers instructing " + money(amount, source.getCurrencyCode()) + " from " + va + ": "
                + (refusals.isEmpty() ? "none is registered" : String.join("; ", refusals)));
    }

    /**
     * Produce and store the request for transfer for an execution already booked (D4) and charge the flat instruction
     * fee to the home-bank target (D8). Runs in the source's transaction: a failure here undoes the booking too.
     */
    public SweepInstruction instruct(SweepExecution execution, VirtualAccount source, VirtualAccount target,
                                     String ownerName, Plan plan, LocalDate today,
                                     String standardFeeRef, String targetCreditRef) {
        String reference = "XSW" + UUID.randomUUID().toString().replace("-", "").substring(0, 13).toUpperCase();
        String currency = execution.getCurrencyCode();
        // 34 characters: fits one MT101 :70: line whole ("Cash concentration " cut the reference's last 8)
        String remittance = "Cash sweep " + execution.getExecutionReference();
        ExternalMandate.InstructionFormat format = plan.authority().getInstructionFormat();

        LocalDateTime now = LocalDateTime.now();
        // ponytail: rail AUTO — the column is NOT NULL with a fixed CHECK and has no value for a request for transfer;
        // instructionFormat is the real message type.
        SweepInstruction i = SweepInstruction.builder()
                .idempotencyKey(reference).externalReference(reference)
                .sweepExecutionId(execution.getId()).ruleId(execution.getRule().getId())
                .sourceShadowVaId(source.getId()).targetVaId(target.getId()).mandateId(plan.authority().getId())
                .rail(SweepRule.Rail.AUTO).instructionFormat(format)
                .messageText(format == ExternalMandate.InstructionFormat.PAIN_001
                        ? pain001(reference, source, target, ownerName, plan.amount(), currency, today, remittance)
                        : mt101(reference, source, target, ownerName, plan.amount(), currency, today, remittance))
                .amount(plan.amount()).currencyCode(currency).executionDate(today)
                .status(InstructionStatus.INSTRUCTED).preparedAt(now).instructedAt(now)
                .standardFeeRef(standardFeeRef).targetCreditRef(targetCreditRef).instructionFee(instructionFee)
                .instructionFeeRef(postInstructionFee(target, execution, reference))
                .build();
        return instructions.save(i);
    }

    /** As {@code SweepService.postSweepFee}: a fee that cannot be posted is logged, the sweep stands. */
    private String postInstructionFee(VirtualAccount target, SweepExecution execution, String reference) {
        try {
            FeePostingService.FeePostingResult r = fees.postFee(target.getId(), instructionFee, "SWEEP_INSTRUCTION_FEE",
                    execution.getId(), "External sweep instruction fee - " + reference);
            return r != null && r.getDebitTransaction() != null ? r.getDebitTransaction().getReferenceNumber() : null;
        } catch (Exception e) {
            log.error("Failed to post instruction fee for {}: {}", reference, e.getMessage());
            return null;
        }
    }

    // ---- confirmation and failure (R15-R17, D5, D7) ---------------------------------------------------------

    /**
     * R15, D5: the lines of an end-of-day statement just applied for {@code shadowVaId} confirm the instructions pending
     * on that account. A debit line carrying the end-to-end reference (as end-to-end id, bank or customer reference)
     * first; otherwise a debit of the same amount valued on the execution date. A line confirms one instruction at most.
     * Breaks are not touched (R17): the debit is among the statement's own lines.
     * @return how many were confirmed
     */
    @Transactional
    public int confirm(UUID shadowVaId, List<BankStatementLine> lines) {
        List<SweepInstruction> pending = instructions.findBySourceShadowVaIdAndStatusInOrderByInstructedAtAsc(shadowVaId, IN_FLIGHT);
        if (pending.isEmpty() || lines.isEmpty()) return 0;
        // A line already confirming an instruction is never used again (the stored lines of older statements, below).
        Set<UUID> used = Set.copyOf(instructions.usedLines(lines.stream().map(BankStatementLine::getId).toList()));
        // ponytail: reversal lines (RC/RD) are left out rather than interpreted
        List<BankStatementLine> debits = new ArrayList<>(lines.stream()
                .filter(l -> l.getCreditDebit() == CreditDebit.D && !l.isReversal() && !used.contains(l.getId())).toList());
        int confirmed = 0;
        for (SweepInstruction i : pending) {   // by reference first, for all, so an amount match cannot take its line
            Optional<BankStatementLine> line = debits.stream().filter(l -> i.getExternalReference() != null
                    && Stream.of(l.getEndToEndId(), l.getBankReference(), l.getCustomerReference()).anyMatch(i.getExternalReference()::equals))
                    .findFirst();
            if (line.isPresent()) confirmed += settle(i, line.get(), debits);
        }
        for (SweepInstruction i : pending) {
            if (!IN_FLIGHT.contains(i.getStatus())) continue;
            Optional<BankStatementLine> line = debits.stream().filter(l -> l.getAmount().compareTo(i.getAmount()) == 0
                    && Objects.equals(l.getValueDate(), i.getExecutionDate())).findFirst();
            if (line.isPresent()) confirmed += settle(i, line.get(), debits);
        }
        return confirmed;
    }

    private int settle(SweepInstruction i, BankStatementLine line, List<BankStatementLine> unused) {
        if (instructions.claim(i.getId(), InstructionStatus.SETTLED, IN_FLIGHT) != 1) {
            log.warn("External sweep {} was confirmed or failed meanwhile; line {} left unused", i.getExternalReference(), line.getId());
            return 0;
        }
        unused.removeIf(l -> l == line);
        LocalDateTime now = LocalDateTime.now();
        i.setStatus(InstructionStatus.SETTLED);
        i.setSettledAt(now);
        i.setConfirmedByLineId(line.getId());
        i.setReconciliationRef(line.getBankReference());
        instructions.save(i);
        vas.findById(i.getSourceShadowVaId()).ifPresent(source -> {
            release(source, i.getAmount());
            vas.save(source);
        });
        executions.findById(i.getSweepExecutionId()).filter(SweepExecution::isCommitted).ifPresent(e -> {
            e.setStatus(SweepExecution.ExecutionStatus.SETTLED);   // ponytail: one instruction per execution
            e.setSettledAt(now);
            executions.save(e);
        });
        log.info("External sweep {} confirmed by statement line {} ({} {})", i.getExternalReference(), line.getId(),
                i.getAmount(), i.getCurrencyCode());
        return 1;
    }

    /**
     * Phase 5 A: the confirmation the intake hook should have made. If the hook failed, the debit sits in a stored
     * statement while the instruction stays pending; before it fails, its account's stored FINAL lines from APPLIED,
     * non-CBS END_OF_DAY statements dated on or after its execution date are matched as {@link #confirm} does (all the
     * account's pending instructions, reference first; a line already used is skipped).
     * @return the line that confirmed {@code i}, null when none did
     */
    private BankStatementLine confirmFromStoredStatements(SweepInstruction i) {
        if (i.getExecutionDate() == null) return null;
        List<UUID> ids = statements.findByShadowVaIdAndKindAndStatusAndCbsFedFalseAndStatementDateGreaterThanEqual(
                i.getSourceShadowVaId(), Kind.END_OF_DAY, BankStatement.Status.APPLIED, i.getExecutionDate())
                .stream().map(BankStatement::getId).toList();
        if (ids.isEmpty()) return null;
        List<BankStatementLine> stored = lines.findByBankStatementIdInAndStatus(ids, BankStatementLine.Status.FINAL);
        confirm(i.getSourceShadowVaId(), stored);   // same persistence context: settles this very instance
        if (i.getStatus() != InstructionStatus.SETTLED) return null;
        return stored.stream().filter(l -> l.getId().equals(i.getConfirmedByLineId())).findFirst().orElse(null);
    }

    /** D7: instructions still pending more than {@link #CONFIRMATION_WINDOW_BUSINESS_DAYS} business days after their execution date. */
    public List<SweepInstruction> overdue(LocalDate today) {
        Map<String, LocalDate> due = new HashMap<>();   // per currency: an execution date before this one is overdue
        return instructions.findByStatusInOrderByInstructedAtDesc(List.copyOf(IN_FLIGHT)).stream()
                .filter(i -> i.getExecutionDate() != null && i.getExecutionDate().isBefore(
                        due.computeIfAbsent(i.getCurrencyCode(), c -> businessDaysBefore(c, today, CONFIRMATION_WINDOW_BUSINESS_DAYS))))
                .toList();
    }

    private LocalDate businessDaysBefore(String currency, LocalDate date, int days) {
        for (int n = 0; n < days; n++) date = calendar.previousBusinessDay(currency, date);
        return date;
    }

    /**
     * R16: a pending leg fails, REJECTED (marked rejected, R14) or EXPIRED (window passed, D7). The target's credit is
     * reversed on its ledger, bank balance and physical account, never refused (it may overdraw or fall below target);
     * the source's ledger debit is put back unless an end-of-day statement has reset it since the instruction; the
     * instruction fee is refunded, the standard fee stands; the rule's total swept and the committed float drop; the
     * execution FAILED with the reason; one alert. Only a pending instruction can fail.
     * <p>Phase 5: first the stored statements are checked ({@link #confirmFromStoredStatements}). A debit found there
     * confirms the leg instead: EXPIRED returns it SETTLED; REJECTED (an operator's call) is refused with a 400 naming
     * the line, and the confirmation stands (no rollback for BusinessException: every other one here is thrown before
     * anything is written). Then the leg is claimed atomically: of two callers (the daily job and Mark rejected) only
     * the one that moves the status reverses anything.
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public SweepInstruction fail(UUID instructionId, InstructionStatus status, String reason) {
        if (status != InstructionStatus.REJECTED && status != InstructionStatus.EXPIRED) {
            throw new IllegalArgumentException("a leg fails as REJECTED or EXPIRED, not " + status);
        }
        SweepInstruction i = instructions.findById(instructionId)
                .orElseThrow(() -> new ResourceNotFoundException("Sweep instruction not found: " + instructionId));
        if (!IN_FLIGHT.contains(i.getStatus())) {
            throw new BusinessException("Sweep instruction " + i.getExternalReference() + " is " + i.getStatus()
                    + ": only one still pending at the other bank can fail");
        }
        BankStatementLine shown = confirmFromStoredStatements(i);
        if (i.getStatus() == InstructionStatus.SETTLED) {
            if (status == InstructionStatus.EXPIRED) return i;
            throw new BusinessException("Sweep instruction " + i.getExternalReference() + " is not rejected: the bank's"
                    + " statement shows this debit: " + describe(shown, i) + ". It is confirmed instead.");
        }
        if (instructions.claim(i.getId(), status, IN_FLIGHT) != 1) {
            throw new BusinessException("Sweep instruction " + i.getExternalReference()
                    + " is no longer pending: it was confirmed or failed meanwhile");
        }
        String why = reason != null && !reason.isBlank() ? reason.strip() : status == InstructionStatus.EXPIRED
                ? "not confirmed by the other bank's statement within " + CONFIRMATION_WINDOW_BUSINESS_DAYS + " business days"
                : "rejected by the other bank";
        BigDecimal amount = i.getAmount();
        String reference = i.getExternalReference();
        String note = "External sweep " + reference + " failed (" + status + "): " + why;

        VirtualAccount target = vas.findById(i.getTargetVaId())
                .orElseThrow(() -> new ResourceNotFoundException("Sweep target not found: " + i.getTargetVaId()));
        CoreBankingBalance.mirrorIfHomeBankShadow(physicals, homeBank.getBic(), target, amount.negate());
        reverse(target, amount.negate(), i.getSourceShadowVaId(), reference, note + " - credit " + i.getTargetCreditRef() + " reversed");

        VirtualAccount source = vas.findById(i.getSourceShadowVaId())
                .orElseThrow(() -> new ResourceNotFoundException("Sweep source not found: " + i.getSourceShadowVaId()));
        release(source, amount);
        boolean resetSince = statements.existsByShadowVaIdAndKindAndStatusAndOlderFalseAndCbsFedFalseAndCreatedAtAfter(
                source.getId(), Kind.END_OF_DAY, BankStatement.Status.APPLIED, i.getInstructedAt());
        if (resetSince) vas.save(source);   // the statement already set its ledger to the bank's own figure
        else reverse(source, amount, target.getId(), reference, note + " - instructed debit put back");

        refundInstructionFee(i, note);
        rules.findById(i.getRuleId()).ifPresent(rule -> {
            rule.setTotalSwept(Objects.requireNonNullElse(rule.getTotalSwept(), BigDecimal.ZERO).subtract(amount));
            rules.save(rule);
        });
        LocalDateTime now = LocalDateTime.now();
        executions.findById(i.getSweepExecutionId()).filter(SweepExecution::isCommitted).ifPresent(e -> {
            e.setStatus(SweepExecution.ExecutionStatus.FAILED);
            e.setErrorMessage(note);
            e.setCompletedAt(now);
            executions.save(e);
        });
        i.setStatus(status);
        i.setRejectedAt(now);
        i.setRejectionReason(why.length() <= 500 ? why : why.substring(0, 500));
        SweepInstruction saved = instructions.save(i);

        // Bank operations and the corporate: the bank's bell lists every corporate's notifications, so one raise reaches both.
        notifications.notifyOnce(source.getCorporateId(), NotificationType.PAYMENT_FAILED, NotificationCategory.ERROR,
                "External sweep failed: " + source.getVaNumber(), note + ". " + money(amount, i.getCurrencyCode())
                        + " taken back from " + target.getVaNumber() + "; the instruction fee is refunded.",
                "SWEEP_INSTRUCTION", i.getId(), reference, "sweeping", "Open Cash Concentration");
        log.warn("{}: {} {} reversed on {}", note, amount, i.getCurrencyCode(), target.getVaNumber());
        return saved;
    }

    private static String describe(BankStatementLine line, SweepInstruction i) {
        if (line == null) return "line " + i.getConfirmedByLineId();
        return money(line.getAmount(), i.getCurrencyCode()) + " valued " + line.getValueDate()
                + (line.getBankReference() != null ? ", bank reference " + line.getBankReference() : "");
    }

    /**
     * The sweep execution view (R19): the execution's instruction, the statement line that confirmed it and the standard
     * fee charged (the instruction only keeps its reference), each null when there is none.
     */
    public record InstructionView(SweepInstruction instruction, BankStatementLine confirmingLine, BigDecimal standardFee) {
    }

    @Transactional(readOnly = true)
    public InstructionView view(UUID executionId) {
        return instructions.findFirstBySweepExecutionId(executionId).map(i -> new InstructionView(i,
                i.getConfirmedByLineId() == null ? null : lines.findById(i.getConfirmedByLineId()).orElse(null),
                i.getStandardFeeRef() == null ? null
                        : txns.findByReferenceNumber(i.getStandardFeeRef()).map(Transaction::getAmount).orElse(null)))
                .orElse(null);
    }

    /** The committed float raised at instruction ({@code SweepService.instructExternal}) comes off; never below zero. */
    private static void release(VirtualAccount source, BigDecimal amount) {
        source.setBankBalanceCommitted(Objects.requireNonNullElse(source.getBankBalanceCommitted(), BigDecimal.ZERO)
                .subtract(amount).max(BigDecimal.ZERO));
    }

    /** A signed ledger movement written as a REVERSAL leg, as {@link OverdrawingSweepReversalService} does; never refused. */
    private void reverse(VirtualAccount va, BigDecimal delta, UUID counterparty, String correlation, String note) {
        BigDecimal before = va.getCurrentBalance();
        va.setCurrentBalance(before.add(delta));
        if (va.getAvailableBalance() != null) va.setAvailableBalance(va.getAvailableBalance().add(delta));
        vas.save(va);
        txns.save(OverdrawingSweepReversalService.movement(va, Transaction.MovementType.REVERSAL, delta.abs(), before,
                va.getCurrentBalance(), counterparty, correlation, note));
    }

    /**
     * R13, D8: the instruction fee's own posting run backwards: the account it was credited to pays it back to the
     * account it was taken from. ponytail: FeePostingService has no refund; the fee's debit leg names both accounts.
     */
    private void refundInstructionFee(SweepInstruction i, String note) {
        if (i.getInstructionFeeRef() == null) return;   // never charged
        Transaction fee = txns.findByReferenceNumber(i.getInstructionFeeRef()).orElse(null);
        if (fee == null || fee.getVaId() == null || fee.getCounterpartyVaId() == null) {
            log.error("Instruction fee {} of {} not found: not refunded", i.getInstructionFeeRef(), i.getExternalReference());
            return;
        }
        String refund = note + " - instruction fee " + fee.getReferenceNumber() + " refunded";
        vas.findById(fee.getCounterpartyVaId()).ifPresent(settlement ->
                reverse(settlement, fee.getAmount().negate(), fee.getVaId(), i.getExternalReference(), refund));
        vas.findById(fee.getVaId()).ifPresent(payer ->
                reverse(payer, fee.getAmount(), fee.getCounterpartyVaId(), i.getExternalReference(), refund));
    }

    /**
     * MT101 request for transfer, block-4 body only (no envelope, no trailing "-"). Sequence A: the account at the other
     * bank (:50H:) and that bank (:52A:); sequence B: the home-bank account (:59:) at the home bank (:57A:).
     * ponytail: plain string building, lines cut to 35 characters, no SWIFT character-set transliteration; \n line
     * ends. A real SWIFT link needs the X charset and CRLF.
     */
    static String mt101(String reference, VirtualAccount source, VirtualAccount target, String ownerName,
                        BigDecimal amount, String currency, LocalDate date, String remittance) {
        StringBuilder m = new StringBuilder();
        m.append(":20:").append(reference).append('\n');
        m.append(":28D:1/1\n");
        m.append(":50H:/").append(account(source)).append('\n').append(cut(ownerName)).append('\n');
        if (source.getBankSwift() != null) m.append(":52A:").append(source.getBankSwift()).append('\n');
        m.append(":30:").append(date.format(YYMMDD)).append('\n');
        m.append(":21:").append(reference).append('\n');
        m.append(":32B:").append(currency).append(swiftAmount(amount, currency)).append('\n');
        m.append(":57A:").append(target.getBankSwift()).append('\n');
        m.append(":59:/").append(account(target)).append('\n').append(cut(ownerName)).append('\n');
        m.append(":70:").append(cut(remittance)).append('\n');
        m.append(":71A:SHA");
        return m.toString();
    }

    /**
     * pain.001.001.09 through the platform's own builder: debtor = the account at the other bank (Dbtr / DbtrAcct /
     * DbtrAgt), creditor = the home-bank account; reference as InstrId, EndToEndId, MsgId and PmtInfId.
     * ponytail: the builder writes both accounts as {@code <IBAN>}; an account with no IBAN goes there as its number.
     */
    String pain001(String reference, VirtualAccount source, VirtualAccount target, String ownerName,
                   BigDecimal amount, String currency, LocalDate date, String remittance) {
        OutwardPaymentRequest request = OutwardPaymentRequest.builder()
                .instructionId(reference).endToEndId(reference).amount(amount).currency(currency)
                .debtorName(ownerName).debtorAccount(account(source)).debtorBic(source.getBankSwift())
                .creditorName(ownerName).creditorAccount(account(target)).creditorBic(target.getBankSwift())
                .remittanceInfo(remittance).requestedExecutionDate(date).build();
        Transaction header = Transaction.builder().referenceNumber(reference).correlationId(reference).build();
        return isoXml.buildPain001(request, source, header, null);
    }

    private static String account(VirtualAccount va) {
        if (va.getBankIban() != null && !va.getBankIban().isBlank()) return va.getBankIban();
        return va.getBankAccountNumber() != null ? va.getBankAccountNumber() : va.getVaNumber();
    }

    private static String cut(String s) {
        String v = s == null ? "" : s.strip();
        return v.length() <= 35 ? v : v.substring(0, 35);
    }

    /** SWIFT amount: the currency's decimals, comma separator, always a comma (JPY "1000,"). */
    static String swiftAmount(BigDecimal amount, String currency) {
        String plain = amount.setScale(decimals(currency), RoundingMode.DOWN).toPlainString().replace('.', ',');
        return plain.contains(",") ? plain : plain + ",";
    }

    /** For people: "3,012,000.00 USD" (was the raw "3012000.0000 USD"). */
    static String money(BigDecimal amount, String currency) {
        return String.format(java.util.Locale.ROOT, "%,." + decimals(currency) + "f", amount) + " " + currency;
    }

    private static int decimals(String currency) {
        try {
            int d = Currency.getInstance(currency).getDefaultFractionDigits();
            return d < 0 ? 2 : d;
        } catch (RuntimeException e) {
            return 2;
        }
    }
}
