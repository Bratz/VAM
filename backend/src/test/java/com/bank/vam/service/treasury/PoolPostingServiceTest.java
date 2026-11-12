package com.bank.vam.service.treasury;

import com.bank.vam.dto.treasury.NotionalPoolDto;
import com.bank.vam.entity.PhysicalAccount;
import com.bank.vam.entity.Transaction;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.credit.InterestConfiguration;
import com.bank.vam.entity.treasury.*;
import com.bank.vam.entity.treasury.PoolInterestCalculation.CalculationStatus;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.repository.PhysicalAccountRepository;
import com.bank.vam.repository.TransactionRepository;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.credit.InterestConfigurationRepository;
import com.bank.vam.repository.treasury.NotionalPoolRepository;
import com.bank.vam.repository.treasury.PoolInterestCalculationRepository;
import com.bank.vam.repository.treasury.ShadowBalanceSnapshotRepository;
import com.bank.vam.service.credit.InterestConfigurationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Notional pooling phase 4 (spec R5, R11a, D11, acceptance 2 and 4): month-end posting of the daily runs' allocated
 * interest, one movement per member, on the real posting service over real accounts — shadow, bank account behind it
 * and ledger rows — with the run store, accounts and ledger kept in memory.
 */
class PoolPostingServiceTest {

    private static final YearMonth M = YearMonth.now().minusMonths(2);
    private static final UUID A = UUID.randomUUID(), B = UUID.randomUUID(), C = UUID.randomUUID(), H = UUID.randomUUID();

    private final NotionalPoolRepository pools = mock(NotionalPoolRepository.class);
    private final PoolInterestCalculationRepository runs = mock(PoolInterestCalculationRepository.class);
    private final ShadowBalanceSnapshotRepository snapshots = mock(ShadowBalanceSnapshotRepository.class);
    private final InterestConfigurationRepository configs = mock(InterestConfigurationRepository.class);
    private final VirtualAccountRepository vas = mock(VirtualAccountRepository.class);
    private final PhysicalAccountRepository banks = mock(PhysicalAccountRepository.class);
    private final TransactionRepository ledgerRepo = mock(TransactionRepository.class);

    private final List<PoolInterestCalculation> stored = new ArrayList<>();
    private final List<ShadowBalanceSnapshot> snaps = new ArrayList<>();
    private final Map<UUID, VirtualAccount> accounts = new HashMap<>();
    private final Map<UUID, PhysicalAccount> physicals = new HashMap<>();
    private final List<Transaction> ledger = new ArrayList<>();
    private final NotionalPool pool = new NotionalPool();
    private final InterestConfiguration config = new InterestConfiguration();
    private PoolRunService runService;
    private PoolPostingService service;

    @BeforeEach
    void setUp() {
        runService = new PoolRunService(pools, runs, snapshots, new InterestConfigurationService(configs));
        service = new PoolPostingService(pools, runs, runService, vas, banks, ledgerRepo);
        pool.setId(UUID.randomUUID());
        pool.setPoolReference("NP-0099");
        pool.setStatus(NotionalPool.PoolStatus.ACTIVE);
        pool.setHeaderAccountId(H);
        pool.setInterestConfigurationId(UUID.randomUUID());
        config.setId(pool.getInterestConfigurationId());
        config.setCreditBaseRate(new BigDecimal("3.1"));
        config.setDebitBaseRate(new BigDecimal("6"));
        config.calculateEffectiveRates();
        config.setDayCountConvention("ACT/365");
        when(configs.findById(config.getId())).thenReturn(Optional.of(config));
        when(pools.findByIdWithMembers(pool.getId())).thenReturn(Optional.of(pool));

        when(runs.findByPoolIdAndValueDateAndStatusNot(any(), any(), any())).thenAnswer(i -> stored.stream()
                .filter(r -> r.getValueDate().equals(i.getArgument(1)) && r.getStatus() != i.getArgument(2)).findFirst());
        when(runs.findByPoolIdAndStatusAndValueDateBetween(any(), any(), any(), any())).thenAnswer(i -> stored.stream()
                .filter(r -> r.getStatus() == i.getArgument(1) && !r.getValueDate().isBefore(i.getArgument(2))
                        && !r.getValueDate().isAfter(i.getArgument(3))).toList());
        when(runs.save(any())).thenAnswer(i -> keep(i.getArgument(0)));
        when(runs.saveAndFlush(any())).thenAnswer(i -> keep(i.getArgument(0)));
        when(snapshots.findFirstByShadowVaIdAndAsOfLessThanEqualOrderByAsOfDesc(any(), any())).thenAnswer(i -> snaps.stream()
                .filter(s -> s.getShadowVaId().equals(i.getArgument(0)) && !s.getAsOf().isAfter(i.getArgument(1)))
                .max(Comparator.comparing(ShadowBalanceSnapshot::getAsOf)));

        when(vas.findById(any())).thenAnswer(i -> Optional.ofNullable(accounts.get(i.getArgument(0))));
        when(banks.findById(any())).thenAnswer(i -> Optional.ofNullable(physicals.get(i.getArgument(0))));
        when(ledgerRepo.save(any())).thenAnswer(i -> {
            ledger.add(i.getArgument(0));
            return i.getArgument(0);
        });
        when(ledgerRepo.findByVaIdInAndSourceReferenceOrderByTransactionDateDesc(any(), any())).thenAnswer(i -> {
            List<Transaction> out = new ArrayList<>(ledger.stream()
                    .filter(t -> t.getSourceReference().equals(i.getArgument(1))).toList());
            Collections.reverse(out);
            return out;
        });
    }

    /** Acceptance 4: one movement per member; Σ postings = Σ daily pooled interest, residue on the header. */
    @Test
    void monthPosting_onePerMember_sumsTheDailyRuns_residueOnTheHeader_missingDatesRunFirst() {
        threeEqualMembersAndAnEmptyHeader();
        runService.runRange(pool.getId(), M.atDay(1), M.atDay(5)); // part of the month already run

        List<NotionalPoolDto.Posting> postings = service.postMonth(pool.getId(), M);

        int days = M.lengthOfMonth();
        assertThat(stored).hasSize(days).allMatch(r -> r.getStatus() == CalculationStatus.POSTED && r.getPostedAt() != null);
        BigDecimal pooled = stored.stream().map(PoolInterestCalculation::getPooledInterest).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(pooled).isEqualByComparingTo(new BigDecimal("25.48").multiply(BigDecimal.valueOf(days)));
        assertThat(ledger).hasSize(4);
        assertThat(ledger.stream().map(Transaction::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo(pooled);
        assertThat(row(A).getAmount()).isEqualByComparingTo(new BigDecimal("8.49").multiply(BigDecimal.valueOf(days)));
        assertThat(row(H).getAmount()).isEqualByComparingTo(new BigDecimal("0.01").multiply(BigDecimal.valueOf(days)));
        assertThat(ledger).allMatch(t -> t.getMovementType() == Transaction.MovementType.INTEREST
                && t.getDescription().equals("Pool interest NP-0099 " + M.format(java.time.format.DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH)))
                && t.getValueDate().equals(M.atEndOfMonth()));
        // every line carries its member's movement reference
        assertThat(stored.stream().flatMap(r -> r.getLines().stream()))
                .allMatch(l -> l.getPostingReference().equals(row(l.getAccountId()).getReferenceNumber()));
        assertThat(postings).singleElement().satisfies(p -> {
            assertThat(p.kind()).isEqualTo("MONTH");
            assertThat(p.month()).isEqualTo(M.toString());
            assertThat(p.total()).isEqualByComparingTo(pooled);
            assertThat(p.members()).hasSize(4);
        });
    }

    /** Acceptance 2: posting a month again posts nothing new. */
    @Test
    void postingTwice_changesNothing() {
        threeEqualMembersAndAnEmptyHeader();
        service.postMonth(pool.getId(), M);
        BigDecimal balanceA = accounts.get(A).getCurrentBalance();

        List<NotionalPoolDto.Posting> again = service.postMonth(pool.getId(), M);

        assertThat(ledger).hasSize(4);
        assertThat(again).hasSize(1);
        assertThat(accounts.get(A).getCurrentBalance()).isEqualByComparingTo(balanceA);
    }

    @Test
    void debitInterestPostsAsADebit_aMemberWithNothingGetsNoMovement() {
        member(A, "100000");
        member(H, "-300000"); // net −200,000: the debit interest is all the header's
        List<NotionalPoolDto.Posting> postings = service.postMonth(pool.getId(), M);

        assertThat(ledger).singleElement().satisfies(t -> {
            assertThat(t.getVaId()).isEqualTo(H);
            assertThat(t.getMovementType()).isEqualTo(Transaction.MovementType.DEBIT);
            assertThat(t.isDebit()).isTrue();
        });
        BigDecimal pooled = stored.stream().map(PoolInterestCalculation::getPooledInterest).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(pooled).isNegative();
        assertThat(row(H).getAmount()).isEqualByComparingTo(pooled.negate());
        assertThat(accounts.get(H).getCurrentBalance()).isEqualByComparingTo(new BigDecimal("-300000").add(pooled));
        assertThat(postings.get(0).total()).isEqualByComparingTo(pooled);
        assertThat(stored.stream().flatMap(r -> r.getLines().stream()).filter(l -> l.getAccountId().equals(A)))
                .allMatch(l -> l.getPostingReference() == null);
    }

    /** Every movement has its ledger row, and the shadow and the bank account behind it move together. */
    @Test
    void movementHasALedgerRow_shadowAndPhysicalMoveTogether() {
        threeEqualMembersAndAnEmptyHeader();
        VirtualAccount a = accounts.get(A);
        PhysicalAccount p = physicals.get(a.getLinkedPhysicalAccountId());

        service.postMonth(pool.getId(), M);

        BigDecimal posted = row(A).getAmount();
        assertThat(a.getCurrentBalance()).isEqualByComparingTo(new BigDecimal("100000").add(posted));
        assertThat(a.getAvailableBalance()).isEqualByComparingTo(a.getCurrentBalance());
        assertThat(a.getBankBalance()).isEqualByComparingTo(new BigDecimal("100000").add(posted));
        assertThat(p.getCurrentBalance()).isEqualByComparingTo(new BigDecimal("100000").add(posted));
        assertThat(p.getAvailableBalance()).isEqualByComparingTo(p.getCurrentBalance());
        assertThat(p.getLedgerBalance()).isEqualByComparingTo(p.getCurrentBalance());
        Transaction t = row(A);
        assertThat(t.getBalanceBefore()).isEqualByComparingTo("100000");
        assertThat(t.getBalanceAfter()).isEqualByComparingTo(a.getCurrentBalance());
        assertThat(t.getPhysicalAccountId()).isEqualTo(p.getId());
        assertThat(t.getSourceReference()).isEqualTo(pool.getId().toString());
    }

    /** R11a: a posted date is superseded, re-run, and only the difference is posted. */
    @Test
    void correction_supersedes_newRun_postsOnlyTheDifference() {
        threeEqualMembersAndAnEmptyHeader();
        service.postMonth(pool.getId(), M);
        LocalDate d = M.atDay(10);
        PoolInterestCalculation old = current(d);
        BigDecimal oldA = line(old, A).getAllocatedInterest();
        snap(A, d, "200000"); // a late balance for A on the 10th

        NotionalPoolDto.Run fresh = service.correct(pool.getId(), d);

        assertThat(old.getStatus()).isEqualTo(CalculationStatus.SUPERSEDED);
        assertThat(fresh.id()).isNotEqualTo(old.getId());
        assertThat(fresh.status()).isEqualTo("POSTED");
        assertThat(stored.stream().filter(r -> r.getValueDate().equals(d))).hasSize(2);
        List<Transaction> corrections = ledger.subList(4, ledger.size());
        BigDecimal diffA = fresh.lines().stream().filter(l -> l.accountId().equals(A)).findFirst().orElseThrow()
                .allocatedInterest().subtract(oldA);
        assertThat(diffA).isPositive();
        assertThat(corrections).allMatch(t -> t.getDescription().equals("Pool interest correction NP-0099 " + d));
        assertThat(corrections.stream().filter(t -> t.getVaId().equals(A))).singleElement()
                .satisfies(t -> assertThat(t.getAmount()).isEqualByComparingTo(diffA));
        BigDecimal net = corrections.stream().map(t -> t.isDebit() ? t.getAmount().negate() : t.getAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(net).isEqualByComparingTo(fresh.pooledInterest().subtract(old.getPooledInterest()));
        assertThat(service.history(pool.getId()).get(0).kind()).isEqualTo("CORRECTION");

        int rows = ledger.size();
        service.postMonth(pool.getId(), M); // the corrected run is posted: the month has nothing more
        assertThat(ledger).hasSize(rows);
    }

    /** R5: closing posts the partial month accrued through yesterday. */
    @Test
    void closing_postsTheAccruedPartialMonth() {
        threeEqualMembersAndAnEmptyHeader();
        LocalDate yesterday = LocalDate.now().minusDays(1);

        service.postAccrued(pool);

        assertThat(stored).isNotEmpty().allMatch(r -> r.getStatus() == CalculationStatus.POSTED);
        assertThat(stored).anyMatch(r -> r.getValueDate().equals(yesterday));
        assertThat(stored).noneMatch(r -> !r.getValueDate().isBefore(LocalDate.now()));
        assertThat(ledger).isNotEmpty().allMatch(t -> t.getValueDate().equals(yesterday));
    }

    @Test
    void refusesAMonthThatIsNotOver() {
        assertThatThrownBy(() -> service.postMonth(pool.getId(), YearMonth.now())).isInstanceOf(BusinessException.class);
    }

    // ---- helpers ----

    /** A, B, C at 100,000 each, header H at 0, 3.1% ACT/365: 25.48 a day, 8.49 each, 0.01 residue to H. */
    private void threeEqualMembersAndAnEmptyHeader() {
        member(A, "100000");
        member(B, "100000");
        member(C, "100000");
        member(H, "0");
    }

    /** A member whose shadow (CORE_BANKING, linked to a bank account) holds {@code balance} since well before M. */
    private void member(UUID accountId, String balance) {
        PoolMember m = new PoolMember();
        m.setPool(pool);
        m.setAccountId(accountId);
        m.setAccountNumber("ACC-" + accountId.toString().substring(0, 4));
        m.setJoinedDate(M.atDay(1).minusYears(1));
        pool.getMembers().add(m);
        snap(accountId, M.atDay(1).minusMonths(1), balance);

        BigDecimal b = new BigDecimal(balance);
        PhysicalAccount p = PhysicalAccount.builder().currentBalance(b).availableBalance(b).ledgerBalance(b).build();
        p.setId(UUID.randomUUID());
        physicals.put(p.getId(), p);
        VirtualAccount v = VirtualAccount.builder().vaNumber(m.getAccountNumber()).currencyCode("GBP")
                .accountCategory(VirtualAccount.AccountCategory.PHYSICAL_MIRROR)
                .balanceDataSource(VirtualAccount.BalanceDataSource.CORE_BANKING).linkedPhysicalAccountId(p.getId())
                .currentBalance(b).availableBalance(b).bankBalance(b).build();
        v.setId(accountId);
        accounts.put(accountId, v);
    }

    private void snap(UUID accountId, LocalDate asOf, String balance) {
        snaps.add(ShadowBalanceSnapshot.builder().shadowVaId(accountId).asOf(asOf).bankBalance(new BigDecimal(balance)).build());
    }

    private PoolInterestCalculation keep(PoolInterestCalculation r) {
        if (r.getId() == null) {
            r.setId(UUID.randomUUID());
            stored.add(r);
        }
        return r;
    }

    private PoolInterestCalculation current(LocalDate d) {
        return stored.stream().filter(r -> r.getValueDate().equals(d) && r.getStatus() != CalculationStatus.SUPERSEDED)
                .findFirst().orElseThrow();
    }

    private static PoolInterestLine line(PoolInterestCalculation r, UUID accountId) {
        return r.getLines().stream().filter(l -> l.getAccountId().equals(accountId)).findFirst().orElseThrow();
    }

    private Transaction row(UUID accountId) {
        return ledger.stream().filter(t -> t.getVaId().equals(accountId)).findFirst().orElseThrow();
    }
}
