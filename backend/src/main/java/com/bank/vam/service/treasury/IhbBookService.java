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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The in-house bank's book for one corporate (spec ihb-intercompany-pages D7, D10; R13-R17): the position per entity
 * and currency with treasury's bank accounts beside it, the movements on the IHB current accounts with their
 * transactions, per-account statements, interest history at the rates charged, and the limit/rate audit trail.
 * Read-only. Every call is scoped to the corporate: an account, movement or audit row of another one is not found.
 * The who-owes-whom between entities is the Intercompany page's (IntercompanyGroupPositionService), not this.
 */
@Service
@RequiredArgsConstructor
public class IhbBookService {

    private final VirtualAccountRepository accounts;
    private final LegalEntityRepository entities;
    private final PhysicalAccountRepository bankAccounts;
    private final TransactionRepository ledger;
    private final IhbInterestAccrualRepository accruals;
    private final InterestConfigurationRepository interestConfigs;
    private final AuditLogRepository auditLog;
    private final ObjectMapper json;

    // ---------------------------------------------------------------- position (R13)

    /**
     * One entity's IHB current accounts in one currency. Limit, used and headroom are the entity's IhbExposure, given
     * on the row in its IHB currency only (null on any other: no limit applies there). Accrued interest is net,
     * signed like the balance: positive is owed to the entity.
     */
    public record PositionRow(UUID entityId, String entityCode, String entityName, String currency, int accounts,
                              BigDecimal balance, BigDecimal creditLimit, BigDecimal used, BigDecimal headroom,
                              BigDecimal accruedInterest) {}

    /** One of treasury's bank accounts: what funds the in-house bank. */
    public record FundingAccount(UUID id, String accountNumber, String accountName, String bankName, String currency,
                                 BigDecimal balance, BigDecimal available, LocalDateTime balanceAsOf) {}

    /** totalBalance: what the IHB owes its participants in the currency, net (negative: they owe it). */
    public record CurrencyPosition(String currency, List<PositionRow> rows, BigDecimal totalBalance,
                                   BigDecimal totalAccruedInterest, List<FundingAccount> funding, BigDecimal totalFunding) {}

    public record Position(UUID corporateId, List<CurrencyPosition> currencies) {}

    @Transactional(readOnly = true)
    public Position position(UUID corporateId) {
        List<VirtualAccount> ihb = currentAccounts(corporateId);
        Map<UUID, LegalEntity> owners = owners(ihb);

        Map<String, List<PositionRow>> rows = new TreeMap<>();
        Map<UUID, Map<String, List<VirtualAccount>>> byEntity = ihb.stream().collect(Collectors.groupingBy(
            VirtualAccount::getOwningEntityId, LinkedHashMap::new, Collectors.groupingBy(VirtualAccount::getCurrencyCode)));
        byEntity.forEach((entityId, byCcy) -> {
            LegalEntity e = owners.get(entityId);
            IhbExposure exposure = e != null ? IhbExposure.of(accounts, e) : null;
            byCcy.forEach((ccy, vas) -> {
                boolean limitCcy = exposure != null && ccy.equals(e.getEffectiveIhbCurrency());
                rows.computeIfAbsent(ccy, k -> new ArrayList<>()).add(new PositionRow(entityId,
                    e != null ? e.getEntityCode() : vas.get(0).getOwningEntityCode(), e != null ? e.getEntityName() : null,
                    ccy, vas.size(), total(vas, VirtualAccount::getCurrentBalance),
                    limitCcy ? exposure.creditLimit() : null, limitCcy ? exposure.drawn() : null,
                    limitCcy ? exposure.available() : null, total(vas, VirtualAccount::getNetAccruedInterest)));
            });
        });

        Map<String, List<FundingAccount>> funding = new TreeMap<>();
        for (LegalEntity treasury : entities.findByCorporateIdAndCanLendTrue(corporateId)) {
            for (PhysicalAccount pa : bankAccounts.findByLegalEntityIdAndStatus(treasury.getId(), PhysicalAccount.AccountStatus.ACTIVE)) {
                funding.computeIfAbsent(pa.getCurrencyCode(), k -> new ArrayList<>()).add(new FundingAccount(pa.getId(),
                    pa.getAccountNumber(), pa.getAccountName(), pa.getBankName(), pa.getCurrencyCode(),
                    pa.getCurrentBalance(), pa.getAvailableBalance(), pa.getBalanceAsOf()));
            }
        }

        SortedSet<String> currencies = new TreeSet<>(rows.keySet());
        currencies.addAll(funding.keySet());
        List<CurrencyPosition> out = new ArrayList<>();
        for (String ccy : currencies) {
            List<PositionRow> r = rows.getOrDefault(ccy, List.of());
            List<FundingAccount> f = funding.getOrDefault(ccy, List.of());
            out.add(new CurrencyPosition(ccy, r, total(r, PositionRow::balance), total(r, PositionRow::accruedInterest),
                f, total(f, FundingAccount::balance)));
        }
        return new Position(corporateId, out);
    }

    // ---------------------------------------------------------------- movements and statement (R14)

    public static final String SWEEP = "SWEEP", POBO = "POBO", COBO = "COBO", INTEREST = "INTEREST",
        SETTLEMENT = "SETTLEMENT", FEE = "FEE", OTHER = "OTHER";
    public static final List<String> KINDS = List.of(SWEEP, POBO, COBO, INTEREST, SETTLEMENT, FEE, OTHER);

    /** amount is signed: positive credits the account, negative debits it. */
    public record Movement(UUID id, String reference, LocalDateTime bookedAt, LocalDate valueDate, UUID accountId,
                           String accountNumber, String entityCode, String currency, String kind, String description,
                           BigDecimal amount, BigDecimal balanceAfter, String status) {}

    /** The movements on the corporate's IHB current accounts booked between from and to (both inclusive), newest first. */
    @Transactional(readOnly = true)
    public List<Movement> movements(UUID corporateId, UUID entityId, String currency, String kind, LocalDate from, LocalDate to) {
        String ccy = currency == null || currency.isBlank() ? null : currency;
        String type = kind == null || kind.isBlank() ? null : kind;
        if (type != null && !KINDS.contains(type)) throw new BusinessException("Unknown movement type " + type + "; one of " + KINDS);
        LocalDate[] period = period(from, to);
        Map<UUID, VirtualAccount> byId = currentAccounts(corporateId).stream()
            .filter(va -> entityId == null || entityId.equals(va.getOwningEntityId()))
            .filter(va -> ccy == null || ccy.equals(va.getCurrencyCode()))
            .collect(Collectors.toMap(VirtualAccount::getId, Function.identity()));
        if (byId.isEmpty()) return List.of();
        List<Movement> out = booked(byId.keySet(), period[0], period[1]).stream()
            .map(t -> movement(t, byId.get(t.getVaId())))
            .filter(m -> type == null || type.equals(m.kind()))
            .collect(Collectors.toCollection(ArrayList::new));
        Collections.reverse(out);
        return out;
    }

    /** One leg of the transaction a movement belongs to. account: a bank account, an IHB current account or a name. */
    public record Leg(String account, String entityCode, String currency, BigDecimal amount, String description) {}

    public record MovementDetail(Movement movement, String transactionReference, String counterpartyName,
                                 String counterpartyAccount, String paymentReference, List<Leg> legs) {}

    /** Drill-down: the movement and every leg of its transaction within the corporate (none of another corporate's). */
    @Transactional(readOnly = true)
    public MovementDetail movement(UUID corporateId, UUID movementId) {
        Transaction t = ledger.findById(movementId)
            .orElseThrow(() -> new ResourceNotFoundException("Movement not found: " + movementId));
        VirtualAccount va = account(corporateId, t.getVaId());
        List<Transaction> legs = t.getCorrelationId() == null ? List.of(t)
            : ledger.findAllByCorrelationId(t.getCorrelationId()).stream()
                .filter(l -> corporateId.equals(l.getCorporateId())).toList();
        Map<UUID, VirtualAccount> legAccounts = accounts.findAllById(legs.stream().map(Transaction::getVaId).distinct().toList())
            .stream().collect(Collectors.toMap(VirtualAccount::getId, Function.identity()));
        Map<UUID, PhysicalAccount> banks = bankAccounts.findAllById(legAccounts.values().stream()
                .map(VirtualAccount::getLinkedPhysicalAccountId).filter(Objects::nonNull).distinct().toList())
            .stream().collect(Collectors.toMap(PhysicalAccount::getId, Function.identity()));
        List<Leg> out = legs.stream().map(l -> {
            VirtualAccount a = legAccounts.get(l.getVaId());
            return new Leg(label(a, banks), a != null ? a.getOwningEntityCode() : null, l.getCurrencyCode(), signed(l),
                l.getDescription());
        }).toList();
        boolean credit = signed(t).signum() >= 0;
        return new MovementDetail(movement(t, va), t.getCorrelationId(),
            credit ? orElse(t.getRemitterName(), t.getBeneficiaryName()) : orElse(t.getBeneficiaryName(), t.getRemitterName()),
            credit ? orElse(t.getRemitterAccount(), t.getBeneficiaryAccount()) : orElse(t.getBeneficiaryAccount(), t.getRemitterAccount()),
            orElse(t.getEndToEndId(), t.getSourceReference()), out);
    }

    public record Statement(UUID accountId, String accountNumber, String accountName, String entityCode, String currency,
                            LocalDate from, LocalDate to, BigDecimal openingBalance, BigDecimal totalCredits,
                            BigDecimal totalDebits, BigDecimal closingBalance, List<Movement> lines) {}

    /**
     * An IHB current account's statement: movements booked from..to (inclusive) oldest first, each with its value
     * date; opening balance = the ledger balance before the period, closing = the last line's balance after it.
     * Failed and cancelled movements are left out, as the ledger balance leaves them out.
     */
    @Transactional(readOnly = true)
    public Statement statement(UUID corporateId, UUID accountId, LocalDate from, LocalDate to) {
        VirtualAccount va = account(corporateId, accountId);
        LocalDate[] period = period(from, to);
        List<Transaction> booked = booked(List.of(va.getId()), period[0], period[1]).stream()
            .filter(t -> t.getStatus() != Transaction.TransactionStatus.FAILED && t.getStatus() != Transaction.TransactionStatus.CANCELLED)
            .toList();
        List<Movement> lines = booked.stream().map(t -> movement(t, va)).toList();
        BigDecimal opening = ledger.findLedgerBalanceBefore(va.getId(), period[0].atStartOfDay())
            .orElse(booked.isEmpty() ? BigDecimal.ZERO : orZero(booked.get(0).getBalanceBefore()));
        BigDecimal credits = lines.stream().map(Movement::amount).filter(a -> a.signum() > 0).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal debits = lines.stream().map(Movement::amount).filter(a -> a.signum() < 0).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal closing = lines.isEmpty() || lines.get(lines.size() - 1).balanceAfter() == null
            ? opening.add(credits).add(debits) : lines.get(lines.size() - 1).balanceAfter();
        return new Statement(va.getId(), va.getVaNumber(), va.getVaName(), va.getOwningEntityCode(), va.getCurrencyCode(),
            period[0], period[1], opening, credits, debits.negate(), closing, lines);
    }

    // ---------------------------------------------------------------- interest history (R15, R17)

    public record AccrualDay(LocalDate date, String side, BigDecimal balance, BigDecimal rate, boolean defaultRate,
                             String dayCount, BigDecimal amount, String note) {}

    /** amount is signed: positive credited interest to the account, negative charged it. */
    public record InterestPosting(UUID movementId, LocalDateTime postedAt, LocalDate valueDate, BigDecimal amount,
                                  String description) {}

    /**
     * Per account and period: each day's accrual as the run recorded it (balance, rate charged, day count, amount), the
     * interest posted to the account, and the account's terms now (the rates the run charges, its day count, what is
     * accrued and not yet posted). Accruals are recorded from the 13 Nov 2026 release on; earlier periods show postings only.
     */
    public record InterestHistory(UUID accountId, String accountNumber, String entityCode, String currency, LocalDate from,
                                  LocalDate to, List<AccrualDay> accruals, BigDecimal accruedCredit, BigDecimal accruedDebit,
                                  List<InterestPosting> postings, BigDecimal posted, BigDecimal creditRateNow,
                                  BigDecimal debitRateNow, boolean defaultRatesNow, String dayCountNow,
                                  BigDecimal unpostedCredit, BigDecimal unpostedDebit, LocalDate lastAccrual, LocalDate lastPosting) {}

    @Transactional(readOnly = true)
    public InterestHistory interestHistory(UUID corporateId, UUID accountId, LocalDate from, LocalDate to) {
        VirtualAccount va = account(corporateId, accountId);
        LocalDate[] period = period(from, to);
        List<AccrualDay> days = accruals.findByVaIdAndAccrualDateBetweenOrderByAccrualDateAscCreatedAtAsc(va.getId(), period[0], period[1])
            .stream().map(a -> new AccrualDay(a.getAccrualDate(), a.getSide(), a.getBalance(), a.getRate(), a.isDefaultRate(),
                a.getDayCount(), a.getAmount(), a.getNote())).toList();
        List<InterestPosting> postings = booked(List.of(va.getId()), period[0], period[1]).stream()
            .filter(t -> t.getMovementType() == MovementType.INTEREST)
            .map(t -> new InterestPosting(t.getId(), t.getTransactionDate(), t.getValueDate(), signed(t), t.getDescription()))
            .toList();
        return new InterestHistory(va.getId(), va.getVaNumber(), va.getOwningEntityCode(), va.getCurrencyCode(),
            period[0], period[1], days, side(days, "CREDIT"), side(days, "DEBIT"),
            postings, total(postings, InterestPosting::amount),
            IhbUnifiedService.chargedRate(va, true), IhbUnifiedService.chargedRate(va, false),
            va.getEffectiveCreditRate() == null || va.getEffectiveDebitRate() == null,
            IhbUnifiedService.dayCount(Optional.ofNullable(va.getInternalInterestConfigId()).flatMap(interestConfigs::findById),
                va.getCurrencyCode()),
            orZero(va.getAccruedCreditInterest()), orZero(va.getAccruedDebitInterest()),
            va.getLastInterestCalcDate(), va.getLastInterestPostingDate());
    }

    // ---------------------------------------------------------------- limit / rate audit trail (R16)

    /**
     * One field of one change: an entity's IHB credit limit or spread, or an IHB current account's rates. currency is
     * set on the credit limit (an amount); the others are annual percentages.
     */
    public record TermsChange(LocalDateTime at, String by, String subject, String field, JsonNode from, JsonNode to,
                              String currency) {}

    /** The corporate's IHB terms changes, newest first (the last 500). */
    @Transactional(readOnly = true)
    public List<TermsChange> termsHistory(UUID corporateId) {
        List<TermsChange> out = new ArrayList<>();
        for (AuditLog row : auditLog.findByCorporateIdAndEventTypeOrderByCreatedAtDesc(
                corporateId, IhbUnifiedService.TERMS_CHANGED, PageRequest.of(0, 500))) {
            try {
                JsonNode payload = json.readTree(row.getPayload());
                for (JsonNode c : payload.path("changes")) {
                    String field = c.path("field").asText();
                    out.add(new TermsChange(row.getCreatedAt(), row.getActor(), payload.path("subject").asText(null),
                        field, c.get("from"), c.get("to"),
                        IhbUnifiedService.CREDIT_LIMIT.equals(field) ? payload.path("currency").asText(null) : null));
                }
            } catch (Exception e) {
                out.add(new TermsChange(row.getCreatedAt(), row.getActor(), null, row.getSummary(), null, null, null));
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- helpers

    /** The corporate's active IHB current accounts (an entity's account at the in-house bank). */
    private List<VirtualAccount> currentAccounts(UUID corporateId) {
        return accounts.findIhbParticipantsByCorporate(corporateId).stream()
            .filter(VirtualAccount::isIhbCurrentAccount).toList();
    }

    /** The corporate's IHB current account (closed ones too, for their history); another corporate's is not found. */
    private VirtualAccount account(UUID corporateId, UUID accountId) {
        return accounts.findById(accountId)
            .filter(va -> corporateId.equals(va.getCorporateId()) && va.isIhbCurrentAccount())
            .orElseThrow(() -> new ResourceNotFoundException("No in-house bank current account " + accountId + " for this corporate"));
    }

    private Map<UUID, LegalEntity> owners(List<VirtualAccount> vas) {
        return entities.findAllById(vas.stream().map(VirtualAccount::getOwningEntityId).distinct().toList()).stream()
            .collect(Collectors.toMap(LegalEntity::getId, Function.identity()));
    }

    /** [from, to] defaulting to the last three months; refused when reversed. */
    private static LocalDate[] period(LocalDate from, LocalDate to) {
        LocalDate end = to != null ? to : LocalDate.now();
        LocalDate start = from != null ? from : end.minusMonths(3);
        if (start.isAfter(end)) throw new BusinessException("The period starts after it ends: " + start + " > " + end);
        return new LocalDate[] {start, end};
    }

    private List<Transaction> booked(Collection<UUID> vaIds, LocalDate from, LocalDate to) {
        return ledger.findByVaIdInAndTransactionDateGreaterThanEqualAndTransactionDateLessThanOrderByTransactionDateAscCreatedAtAsc(
            vaIds, from.atStartOfDay(), to.plusDays(1).atStartOfDay());
    }

    private static Movement movement(Transaction t, VirtualAccount va) {
        return new Movement(t.getId(), t.getReferenceNumber(), t.getTransactionDate(), t.getValueDate(), t.getVaId(),
            va != null ? va.getVaNumber() : null, va != null ? va.getOwningEntityCode() : null, t.getCurrencyCode(),
            kind(t), t.getDescription(), signed(t), t.getBalanceAfter(), t.getStatus() != null ? t.getStatus().name() : null);
    }

    /** What the movement was, in the page's terms. */
    static String kind(Transaction t) {
        MovementType type = t.getMovementType();
        String channel = t.getChannel() != null ? t.getChannel().toUpperCase() : "";
        if (type == MovementType.INTEREST) return INTEREST;
        if (type == MovementType.SWEEP_IN || type == MovementType.SWEEP_OUT || channel.startsWith("SWEEP")) return SWEEP;
        if (type == MovementType.POBO_DEBIT || Boolean.TRUE.equals(t.getIsPobo()) || channel.contains("POBO")) return POBO;
        if (type == MovementType.ROBO_CREDIT || Boolean.TRUE.equals(t.getIsRobo()) || channel.contains("COBO")) return COBO;
        if (type == MovementType.IC_SETTLEMENT || type == MovementType.IHB_SETTLEMENT || type == MovementType.NETTING
            || channel.contains("NETTING") || channel.contains("SETTLEMENT")) return SETTLEMENT;
        if (type == MovementType.FEE) return FEE;
        return OTHER;
    }

    /**
     * Signed by what it did to the balance (balance before → after) when the row says; else by its type. An IHB
     * interest posting is one INTEREST row either way, so the type alone would call a debit interest a credit.
     */
    static BigDecimal signed(Transaction t) {
        if (t.getBalanceBefore() != null && t.getBalanceAfter() != null) {
            int dir = t.getBalanceAfter().compareTo(t.getBalanceBefore());
            if (dir != 0) return dir > 0 ? t.getAmount().abs() : t.getAmount().abs().negate();
        }
        return t.getSignedAmount();
    }

    /** A leg's account as the corporate knows it: its bank account, its IHB current account, else the account's name. */
    private static String label(VirtualAccount va, Map<UUID, PhysicalAccount> banks) {
        if (va == null) return null;
        PhysicalAccount bank = va.getLinkedPhysicalAccountId() != null ? banks.get(va.getLinkedPhysicalAccountId()) : null;
        if (bank != null) return bank.getAccountName() + " (" + bank.getBankName() + " " + bank.getAccountNumber() + ")";
        if (va.isIhbCurrentAccount()) return va.getVaNumber();
        return va.getVaName();
    }

    private static <T> BigDecimal total(List<T> items, Function<T, BigDecimal> f) {
        return items.stream().map(f).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal side(List<AccrualDay> days, String side) {
        return total(days.stream().filter(d -> side.equals(d.side())).toList(), AccrualDay::amount);
    }

    private static BigDecimal orZero(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    private static String orElse(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }
}
