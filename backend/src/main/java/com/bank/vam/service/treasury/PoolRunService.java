package com.bank.vam.service.treasury;

import com.bank.vam.dto.treasury.NotionalPoolDto;
import com.bank.vam.entity.credit.InterestConfiguration;
import com.bank.vam.entity.treasury.*;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.exception.ResourceNotFoundException;
import com.bank.vam.repository.treasury.NotionalPoolRepository;
import com.bank.vam.repository.treasury.PoolInterestCalculationRepository;
import com.bank.vam.repository.treasury.ShadowBalanceSnapshotRepository;
import com.bank.vam.service.credit.InterestConfigurationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Daily notional pool runs (spec R6, R8–R11, R13; D4–D6): per pool and value date, each member's end-of-day balance,
 * pooled interest on the net, the standalone interest each member would have had, the benefit, and the pooled
 * interest allocated pro rata. Stored once per (pool, date); nothing is posted here (PoolPostingService posts).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PoolRunService {

    /** Longest manual catch-up in one request, in days. */
    static final int MAX_RANGE_DAYS = 92;

    private final NotionalPoolRepository poolRepository;
    private final PoolInterestCalculationRepository runRepository;
    private final ShadowBalanceSnapshotRepository snapshotRepository;
    private final InterestConfigurationService interestConfigurationService;

    /**
     * Runs every value date from {@code from} to {@code to} (default: {@code from}) and returns their runs; a date
     * already run returns its stored run unchanged, a date with no member in the pool has none.
     * ponytail: there is no pool status history, so a manual run is allowed for any past date with members.
     */
    @Transactional
    public List<NotionalPoolDto.Run> runRange(UUID poolId, LocalDate from, LocalDate to) {
        if (from == null) throw new BusinessException("from is required");
        if (to == null) to = from;
        if (to.isBefore(from)) throw new BusinessException("to (" + to + ") is before from (" + from + ")");
        if (!to.isBefore(LocalDate.now())) {
            throw new BusinessException("Value dates must be before today: today's end-of-day balances are not final");
        }
        if (ChronoUnit.DAYS.between(from, to) >= MAX_RANGE_DAYS) {
            throw new BusinessException("At most " + MAX_RANGE_DAYS + " days per run request");
        }
        NotionalPool pool = poolRepository.findByIdWithMembers(poolId)
                .orElseThrow(() -> new ResourceNotFoundException("Notional pool not found: " + poolId));
        if (pool.getInterestConfigurationId() == null) {
            throw new BusinessException("Pool " + pool.getPoolReference() + " has no interest configuration");
        }
        InterestConfiguration config = interestConfigurationService.getById(pool.getInterestConfigurationId());
        List<NotionalPoolDto.Run> runs = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            run(pool, config, d).map(PoolRunService::toDto).ifPresent(runs::add);
        }
        return runs;
    }

    /** Stored runs, newest first; defaults to the last 31 days. */
    @Transactional(readOnly = true)
    public List<NotionalPoolDto.Run> history(UUID poolId, LocalDate from, LocalDate to) {
        if (to == null) to = LocalDate.now();
        if (from == null) from = to.minusDays(31);
        // ponytail: lines load per run (one query each); fine for a month of history, batch-fetch if it grows.
        return runRepository.findByPoolIdAndValueDateBetweenOrderByValueDateDesc(poolId, from, to).stream()
                .map(PoolRunService::toDto).toList();
    }

    /** The date pool figures are as of: yesterday, the latest value date a run can take. */
    public static LocalDate figuresAsOf() {
        return LocalDate.now().minusDays(1);
    }

    /** Each pool's figures from its current runs (R15), one query for all; a pool without runs gets empty figures. */
    @Transactional(readOnly = true)
    public Map<UUID, NotionalPoolDto.Figures> figures(Collection<UUID> poolIds) {
        if (poolIds.isEmpty()) return Map.of();
        LocalDate asOf = figuresAsOf();
        // ponytail: loads the year's run rows (no lines) per call — a few hundred per pool; aggregate in SQL if it grows.
        Map<UUID, List<PoolInterestCalculation>> byPool = new HashMap<>();
        runRepository.findByPoolIdInAndStatusNotAndValueDateBetween(poolIds, PoolInterestCalculation.CalculationStatus.SUPERSEDED,
                        asOf.withDayOfYear(1).minusDays(31), asOf) // a month back so a last run in December still shows
                .forEach(r -> byPool.computeIfAbsent(r.getPool().getId(), k -> new ArrayList<>()).add(r));
        Map<UUID, NotionalPoolDto.Figures> out = new HashMap<>();
        for (UUID id : poolIds) out.put(id, figures(byPool.getOrDefault(id, List.of()), asOf));
        return out;
    }

    /**
     * Figures of one pool from its current runs: the last run's date, net and pooled interest (the latest day's
     * accrual); Σ pooled interest and Σ benefit over {@code asOf}'s month, Σ benefit over its year. "Savings" is the
     * benefit only (R9).
     */
    static NotionalPoolDto.Figures figures(List<PoolInterestCalculation> runs, LocalDate asOf) {
        LocalDate monthStart = asOf.withDayOfMonth(1), yearStart = asOf.withDayOfYear(1);
        PoolInterestCalculation last = null;
        BigDecimal mtdPooled = BigDecimal.ZERO, mtdBenefit = BigDecimal.ZERO, ytdBenefit = BigDecimal.ZERO;
        for (PoolInterestCalculation r : runs) {
            LocalDate d = r.getValueDate();
            if (d.isAfter(asOf)) continue;
            if (last == null || d.isAfter(last.getValueDate())) last = r;
            if (d.isBefore(yearStart)) continue;
            ytdBenefit = ytdBenefit.add(nz(r.getBenefit()));
            if (d.isBefore(monthStart)) continue;
            mtdPooled = mtdPooled.add(nz(r.getPooledInterest()));
            mtdBenefit = mtdBenefit.add(nz(r.getBenefit()));
        }
        return new NotionalPoolDto.Figures(asOf, last == null ? null : last.getValueDate(),
                last == null ? null : last.getPoolNet(), last == null ? null : last.getPooledInterest(),
                mtdPooled, mtdBenefit, ytdBenefit);
    }

    /** One value date: the stored run if there is one (idempotent, R10), else a new one; empty if no member that day. */
    private Optional<PoolInterestCalculation> run(NotionalPool pool, InterestConfiguration config, LocalDate date) {
        Optional<PoolInterestCalculation> stored = runRepository.findByPoolIdAndValueDateAndStatusNot(pool.getId(), date,
                PoolInterestCalculation.CalculationStatus.SUPERSEDED);
        if (stored.isPresent()) return stored;
        List<PoolMember> members = pool.getMembers().stream().filter(m -> memberOn(m, date)).toList();
        if (members.isEmpty()) return Optional.empty();

        PoolInterestCalculation run = new PoolInterestCalculation();
        run.setPool(pool);
        run.setValueDate(date);
        BigDecimal net = BigDecimal.ZERO;
        BigDecimal standaloneExact = BigDecimal.ZERO; // unrounded, so the benefit isn't a sum of rounding cents
        for (PoolMember m : members) {
            // R6: the member account is the shadow; its EOD bank balance for the date, else the last earlier one.
            // ponytail: one query per member; pools are a handful of accounts.
            Optional<ShadowBalanceSnapshot> snap =
                    snapshotRepository.findFirstByShadowVaIdAndAsOfLessThanEqualOrderByAsOfDesc(m.getAccountId(), date);
            BigDecimal balance = snap.map(ShadowBalanceSnapshot::getBankBalance).orElse(BigDecimal.ZERO)
                    .setScale(2, RoundingMode.HALF_UP);
            PoolInterestLine line = new PoolInterestLine();
            line.setCalculation(run);
            line.setAccountId(m.getAccountId());
            line.setAccountNumber(m.getAccountNumber());
            line.setBalance(balance);
            line.setBalanceAsOf(snap.map(ShadowBalanceSnapshot::getAsOf).orElse(null));
            line.setSnapshotMissing(!date.equals(line.getBalanceAsOf()));
            BigDecimal exact = exactInterest(config, balance);
            standaloneExact = standaloneExact.add(exact);
            line.setStandaloneInterest(exact.setScale(2, RoundingMode.HALF_UP));
            line.setAllocatedInterest(BigDecimal.ZERO);
            line.setDebitLimitBreach(m.getDebitLimit() != null && balance.compareTo(m.getDebitLimit().negate()) < 0);
            run.getLines().add(line);
            net = net.add(balance);
        }

        BigDecimal pooledExact = exactInterest(config, net);
        BigDecimal pooled = pooledExact.setScale(2, RoundingMode.HALF_UP);
        BigDecimal standalone = run.getLines().stream().map(PoolInterestLine::getStandaloneInterest)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal creditRate = nz(config.getEffectiveCreditRate());
        BigDecimal debitRate = nz(config.getEffectiveDebitRate()).add(nz(config.getPenaltyRate()));
        run.setPoolNet(net);
        run.setPooledInterest(pooled);
        run.setStandaloneInterest(standalone);
        // From the unrounded figures: members all in credit at one rate have no benefit, not −0.01 of rounding.
        run.setBenefit(pooledExact.subtract(standaloneExact).setScale(2, RoundingMode.HALF_UP));
        run.setResidue(allocate(run.getLines(), pooled, net.signum() < 0 ? -1 : 1, pool.getHeaderAccountId()));
        run.setCreditRate(creditRate);
        run.setDebitRate(debitRate);
        run.setInterestRate(net.signum() < 0 ? debitRate : creditRate);
        run.setDayCount(config.getDayCountConvention());
        run.setFloorBreach(pool.getPoolFloor() != null && net.compareTo(pool.getPoolFloor().negate()) < 0);
        if (pool.getLastCalculationDate() == null || date.isAfter(pool.getLastCalculationDate())) {
            pool.setLastCalculationDate(date); // the pool detail's "Last Calculated"
        }
        log.info("Pool {} run {}: net {}, pooled {}, benefit {}",pool.getPoolReference(), date, net, pooled, run.getBenefit());
        return Optional.of(runRepository.save(run));
    }

    /** Was {@code m} in the pool on {@code date}: joined on or before it and not yet left. */
    private static boolean memberOn(PoolMember m, LocalDate date) {
        return (m.getJoinedDate() == null || !m.getJoinedDate().isAfter(date))
                && (m.getLeftDate() == null || m.getLeftDate().isAfter(date));
    }

    /** One day's interest to the cent, signed: + earned on a credit balance, − charged on a debit balance. */
    private BigDecimal exactInterest(InterestConfiguration config, BigDecimal balance) {
        BigDecimal i = interestConfigurationService.calculateDailyInterest(config, balance); // the engine's 4 dp
        return balance.signum() < 0 ? i.negate() : i;
    }

    /**
     * D6/R11: the pooled interest is shared by the members on the net's side ({@code side} 1 = in credit, −1 = in
     * debit) in proportion to their balances, each share rounded toward zero to the cent; the residue goes to the
     * header (R2), which takes it even with no share of its own. Returns the residue.
     */
    static BigDecimal allocate(List<PoolInterestLine> lines, BigDecimal pooled, int side, UUID headerAccountId) {
        if (pooled.signum() == 0) return BigDecimal.ZERO;
        List<PoolInterestLine> sharers = lines.stream().filter(l -> l.getBalance().signum() == side).toList();
        BigDecimal total = sharers.stream().map(l -> l.getBalance().abs()).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal shared = BigDecimal.ZERO;
        for (PoolInterestLine l : sharers) {
            BigDecimal share = pooled.multiply(l.getBalance().abs()).divide(total, 2, RoundingMode.DOWN);
            l.setAllocatedInterest(share);
            shared = shared.add(share);
        }
        BigDecimal residue = pooled.subtract(shared);
        // ponytail: a header that was not yet a member on the date has no line; the largest sharer takes the residue.
        PoolInterestLine header = lines.stream().filter(l -> l.getAccountId().equals(headerAccountId)).findFirst()
                .orElseGet(() -> sharers.stream().max(Comparator.comparing(l -> l.getBalance().abs())).orElseThrow());
        header.setAllocatedInterest(header.getAllocatedInterest().add(residue));
        return residue;
    }

    static NotionalPoolDto.Run toDto(PoolInterestCalculation r) {
        List<NotionalPoolDto.RunLine> lines = r.getLines().stream().map(l -> new NotionalPoolDto.RunLine(
                l.getAccountId(), l.getAccountNumber(), l.getBalance(), l.getBalanceAsOf(), l.isSnapshotMissing(),
                l.getStandaloneInterest(), l.getAllocatedInterest(), l.isDebitLimitBreach(), l.getPostingReference())).toList();
        return new NotionalPoolDto.Run(r.getId(), r.getValueDate(), r.getStatus().name(), r.getPoolNet(),
                r.getInterestRate(), r.getPooledInterest(), r.getStandaloneInterest(), r.getBenefit(), r.getResidue(),
                r.getCreditRate(), r.getDebitRate(), r.getDayCount(), Boolean.TRUE.equals(r.getFloorBreach()),
                (int) lines.stream().filter(NotionalPoolDto.RunLine::debitLimitBreach).count(),
                (int) lines.stream().filter(NotionalPoolDto.RunLine::snapshotMissing).count(), r.getPostedAt(), lines);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
