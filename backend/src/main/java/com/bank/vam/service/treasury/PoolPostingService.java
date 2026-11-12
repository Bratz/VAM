package com.bank.vam.service.treasury;

import com.bank.vam.dto.treasury.NotionalPoolDto;
import com.bank.vam.entity.Transaction;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.treasury.NotionalPool;
import com.bank.vam.entity.treasury.PoolInterestCalculation;
import com.bank.vam.entity.treasury.PoolInterestLine;
import com.bank.vam.entity.treasury.PoolMember;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.exception.ResourceNotFoundException;
import com.bank.vam.repository.PhysicalAccountRepository;
import com.bank.vam.repository.TransactionRepository;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.treasury.NotionalPoolRepository;
import com.bank.vam.repository.treasury.PoolInterestCalculationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

import static com.bank.vam.entity.treasury.PoolInterestCalculation.CalculationStatus.*;

/**
 * Posts notional pool interest (spec R11a, D5, D11): at month end, one movement per member for its share of the
 * month's runs; a correction posts only the difference. Runs are marked POSTED, never edited.
 *
 * <p>A movement lands on the member account — a home-bank PHYSICAL_MIRROR shadow — and on the bank account behind it,
 * like an FX settlement leg ({@code FxPosting}): the shadow's ledger and bank balance move by the amount, the
 * physical account follows ({@link CoreBankingBalance#follow}), and one ledger row (va_movements) goes on the
 * member's statement. The other side is the bank's own interest expense (credit interest) or income (debit
 * interest), which is outside the VAM ledger: no VA stands for the bank's P&amp;L, so there is no contra row.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PoolPostingService {

    static final String SOURCE_MONTH = "POOL_INTEREST";
    static final String SOURCE_CORRECTION = "POOL_INTEREST_CORRECTION";
    private static final DateTimeFormatter MON_YYYY = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);

    private final NotionalPoolRepository poolRepository;
    private final PoolInterestCalculationRepository runRepository;
    private final PoolRunService runService;
    private final VirtualAccountRepository accounts;
    private final PhysicalAccountRepository banks;
    private final TransactionRepository ledger;

    /**
     * Posts a past month: runs its missing dates, then one movement per member for Σ its allocated interest over the
     * month's unposted runs. Posting a month again posts nothing new. Returns the month's postings.
     */
    @Transactional
    public List<NotionalPoolDto.Posting> postMonth(UUID poolId, YearMonth month) {
        if (month == null) throw new BusinessException("month is required (yyyy-MM)");
        if (!month.isBefore(YearMonth.now())) {
            throw new BusinessException("Only a past month can be posted; " + month + " is not over (closing the pool posts it)");
        }
        post(pool(poolId), month, month.atEndOfMonth());
        return history(poolId).stream().filter(p -> p.month().equals(month.toString())).toList();
    }

    /**
     * R5: before a pool closes, what it has accrued and not posted is posted, through yesterday — this month's
     * partial month and any earlier month with unposted runs.
     */
    @Transactional
    public void postAccrued(NotionalPool pool) {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        SortedSet<YearMonth> months = new TreeSet<>(List.of(YearMonth.from(yesterday)));
        runRepository.findByPoolIdAndStatusAndValueDateBetween(pool.getId(), CALCULATED, LocalDate.EPOCH, yesterday)
                .forEach(r -> months.add(YearMonth.from(r.getValueDate())));
        months.forEach(m -> post(pool, m, yesterday));
    }

    /**
     * R11a: a posted date is never edited. The current run is marked SUPERSEDED, a new run is made from the data as
     * it is now, and — if the old one was posted — each member's difference is posted at once and the new run is
     * POSTED with it. An unposted run is just replaced; the month-end posting takes the new one.
     */
    @Transactional
    public NotionalPoolDto.Run correct(UUID poolId, LocalDate date) {
        NotionalPool pool = pool(poolId);
        PoolInterestCalculation old = runRepository.findByPoolIdAndValueDateAndStatusNot(poolId, date, SUPERSEDED)
                .orElseThrow(() -> new BusinessException("No run for " + date + " to correct; run the date instead"));
        boolean wasPosted = old.getStatus() == POSTED;
        old.setStatus(SUPERSEDED);
        runRepository.saveAndFlush(old); // before the new run's insert: one current run per date (V52)
        runService.runRange(poolId, date, date);
        PoolInterestCalculation fresh = runRepository.findByPoolIdAndValueDateAndStatusNot(poolId, date, SUPERSEDED)
                .orElseThrow(() -> new BusinessException("The pool had no member on " + date + "; nothing to correct to"));
        if (wasPosted) {
            Map<UUID, BigDecimal> diff = new LinkedHashMap<>();
            fresh.getLines().forEach(l -> diff.merge(l.getAccountId(), l.getAllocatedInterest(), BigDecimal::add));
            old.getLines().forEach(l -> diff.merge(l.getAccountId(), l.getAllocatedInterest().negate(), BigDecimal::add));
            Map<UUID, String> refs = postEach(diff, date, "Pool interest correction " + pool.getPoolReference() + " " + date,
                    SOURCE_CORRECTION, pool, "POOLCOR-" + pool.getPoolReference() + "-" + date);
            markPosted(List.of(fresh), refs);
        }
        return PoolRunService.toDto(fresh);
    }

    /** Postings made for the pool (month and correction), newest first, read back from the ledger. */
    @Transactional(readOnly = true)
    public List<NotionalPoolDto.Posting> history(UUID poolId) {
        Map<UUID, String> numbers = new HashMap<>();
        for (PoolMember m : pool(poolId).getMembers()) numbers.putIfAbsent(m.getAccountId(), m.getAccountNumber());
        if (numbers.isEmpty()) return List.of();
        Map<String, List<Transaction>> byPosting = ledger
                .findByVaIdInAndSourceReferenceOrderByTransactionDateDesc(numbers.keySet(), poolId.toString()).stream()
                .collect(Collectors.groupingBy(Transaction::getCorrelationId, LinkedHashMap::new, Collectors.toList()));
        return byPosting.entrySet().stream().map(e -> {
            Transaction t = e.getValue().get(0);
            List<NotionalPoolDto.PostingLine> lines = e.getValue().stream().map(x -> new NotionalPoolDto.PostingLine(
                    x.getVaId(), numbers.get(x.getVaId()), signed(x), x.getReferenceNumber())).toList();
            BigDecimal total = lines.stream().map(NotionalPoolDto.PostingLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
            return new NotionalPoolDto.Posting(e.getKey(), SOURCE_CORRECTION.equals(t.getSourceType()) ? "CORRECTION" : "MONTH",
                    YearMonth.from(t.getValueDate()).toString(), t.getValueDate(), t.getTransactionDate(), total, lines);
        }).toList();
    }

    /** The month's posting through {@code through}: missing dates run first, then unposted runs posted per member. */
    private void post(NotionalPool pool, YearMonth month, LocalDate through) {
        LocalDate first = month.atDay(1);
        LocalDate last = through.isBefore(month.atEndOfMonth()) ? through : month.atEndOfMonth();
        // ponytail: missing dates are run only for an ACTIVE pool. There is no status history, and the daily scheduler
        // skips a suspended pool, so a suspended pool posts what it accrued before but accrues nothing new here.
        if (pool.getStatus() == NotionalPool.PoolStatus.ACTIVE && !last.isBefore(first)) {
            runService.runRange(pool.getId(), first, last);
        }
        List<PoolInterestCalculation> runs =
                runRepository.findByPoolIdAndStatusAndValueDateBetween(pool.getId(), CALCULATED, first, last);
        if (runs.isEmpty()) return;
        Map<UUID, BigDecimal> totals = new LinkedHashMap<>();
        runs.forEach(r -> r.getLines().forEach(l -> totals.merge(l.getAccountId(), l.getAllocatedInterest(), BigDecimal::add)));
        Map<UUID, String> refs = postEach(totals, last, "Pool interest " + pool.getPoolReference() + " " + MON_YYYY.format(month),
                SOURCE_MONTH, pool, "POOLINT-" + pool.getPoolReference() + "-" + month);
        markPosted(runs, refs);
        log.info("Pool {} {} posted: {} run(s), {} movement(s)", pool.getPoolReference(), month, runs.size(), refs.size());
    }

    /** One movement per non-zero amount; returns account → movement reference. */
    private Map<UUID, String> postEach(Map<UUID, BigDecimal> amounts, LocalDate valueDate, String description,
                                       String source, NotionalPool pool, String correlationBase) {
        String correlationId = correlationBase + "-" + System.currentTimeMillis(); // one posting = one correlation
        Map<UUID, String> refs = new HashMap<>();
        amounts.forEach((account, amount) -> {
            if (amount.signum() != 0) refs.put(account, move(account, amount, valueDate, description, source, pool, correlationId));
        });
        return refs;
    }

    private static void markPosted(List<PoolInterestCalculation> runs, Map<UUID, String> refs) {
        LocalDateTime now = LocalDateTime.now();
        for (PoolInterestCalculation r : runs) {
            r.setStatus(POSTED);
            r.setPostedAt(now);
            for (PoolInterestLine l : r.getLines()) l.setPostingReference(refs.get(l.getAccountId()));
        }
    }

    /**
     * {@code delta} (+ credit interest, − debit interest) on a member's shadow and its bank account, with its ledger
     * row; returns the row's reference. A debit may take the account below zero: interest charged is not optional.
     */
    private String move(UUID accountId, BigDecimal delta, LocalDate valueDate, String description, String source,
                        NotionalPool pool, String correlationId) {
        VirtualAccount v = accounts.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Pool member account not found: " + accountId));
        BigDecimal before = v.getCurrentBalance();
        v.applyShadowMovement(delta);
        accounts.save(v);
        CoreBankingBalance.follow(banks, v, delta);
        // INTEREST reads as a credit on the statement (Transaction.isCredit); debit interest is a DEBIT.
        Transaction.MovementType type = delta.signum() > 0 ? Transaction.MovementType.INTEREST : Transaction.MovementType.DEBIT;
        return ledger.save(Transaction.builder()
                .movementType(type)
                .transactionCategory(Transaction.TransactionCategory.EXTERNAL)
                .corporateId(v.getCorporateId())
                .vaId(v.getId())
                .physicalAccountId(v.getLinkedPhysicalAccountId())
                .programId(v.getProgramId())
                .amount(delta.abs())
                .currencyCode(v.getCurrencyCode())
                .balanceBefore(before)
                .balanceAfter(v.getCurrentBalance())
                .transactionDate(LocalDateTime.now())
                .valueDate(valueDate)
                .referenceNumber(Transaction.generateReference(type))
                .description(description)
                .channel("NOTIONAL_POOL")
                .sourceType(source)
                .sourceReference(pool.getId().toString())
                .correlationId(correlationId)
                .status(Transaction.TransactionStatus.COMPLETED)
                .build()).getReferenceNumber();
    }

    private static BigDecimal signed(Transaction t) {
        return t.getMovementType() == Transaction.MovementType.DEBIT ? t.getAmount().negate() : t.getAmount();
    }

    private NotionalPool pool(UUID poolId) {
        return poolRepository.findByIdWithMembers(poolId)
                .orElseThrow(() -> new ResourceNotFoundException("Notional pool not found: " + poolId));
    }
}
