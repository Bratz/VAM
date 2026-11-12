package com.bank.vam.service.treasury;

import com.bank.vam.config.HomeBankProperties;
import com.bank.vam.config.MarketProfileProperties;
import com.bank.vam.dto.treasury.NotionalPoolDto;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.credit.InterestConfiguration;
import com.bank.vam.entity.treasury.NotionalPool;
import com.bank.vam.entity.treasury.PoolMember;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.repository.PhysicalAccountRepository;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.credit.InterestConfigurationRepository;
import com.bank.vam.repository.treasury.NotionalPoolRepository;
import com.bank.vam.repository.treasury.PoolMemberRepository;
import com.bank.vam.service.audit.AuditLogService;
import com.bank.vam.service.credit.InterestConfigurationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Notional pooling phase 1 (spec docs/specs/notional-pooling.md): reference from the database sequence (R17), no pool
 * fees (D9), membership rules on every add path (R1, R3, R4), leaving keeps the row, SUSPEND/CLOSE (R5), and the
 * retired "Calculate Interest" refuses instead of double-counting.
 */
class NotionalPoolServiceTest {

    private static final String HOME_BIC = "HBUKGB4BXXX";
    private static final UUID CORP = UUID.randomUUID();
    private static final UUID ENTITY = UUID.randomUUID();

    private final NotionalPoolRepository pools = mock(NotionalPoolRepository.class);
    private final PoolMemberRepository members = mock(PoolMemberRepository.class);
    private final VirtualAccountRepository vas = mock(VirtualAccountRepository.class);
    private final InterestConfigurationRepository configs = mock(InterestConfigurationRepository.class);
    private final PoolPostingService postings = mock(PoolPostingService.class);
    private final Map<UUID, VirtualAccount> accounts = new HashMap<>();
    private final Map<UUID, InterestConfiguration> savedConfigs = new HashMap<>();
    private NotionalPoolService service;

    @BeforeEach
    void setUp() {
        HomeBankProperties home = new HomeBankProperties();
        home.setBic(HOME_BIC);
        service = new NotionalPoolService(pools, members, vas, mock(PhysicalAccountRepository.class), home,
                mock(AuditLogService.class), mock(MarketProfileProperties.class),
                new InterestConfigurationService(configs), configs,
                mock(com.bank.vam.repository.treasury.PoolInterestCalculationRepository.class), postings,
                mock(PoolRunService.class));
        when(pools.save(any())).thenAnswer(i -> {
            NotionalPool p = i.getArgument(0);
            if (p.getId() == null) p.setId(UUID.randomUUID());
            return p;
        });
        // The real interest engine over a map: what it saves, findById returns.
        when(configs.save(any())).thenAnswer(i -> {
            InterestConfiguration c = i.getArgument(0);
            if (c.getId() == null) c.setId(UUID.randomUUID());
            savedConfigs.put(c.getId(), c);
            return c;
        });
        when(configs.findById(any())).thenAnswer(i -> Optional.ofNullable(savedConfigs.get(i.getArgument(0))));
        when(vas.findAllById(any())).thenAnswer(i -> {
            List<VirtualAccount> out = new ArrayList<>();
            for (Object id : new LinkedHashSet<Object>((Collection<?>) i.getArgument(0))) {
                if (accounts.containsKey(id)) out.add(accounts.get(id));
            }
            return out;
        });
        when(vas.findById(any())).thenAnswer(i -> Optional.ofNullable(accounts.get(i.getArgument(0))));
        when(members.findByAccountIdInAndStatus(anyCollection(), eq(PoolMember.MemberStatus.ACTIVE))).thenReturn(List.of());
    }

    // ---- 1.1 reference ----

    @Test
    void referenceComesFromTheDatabaseSequence() {
        when(pools.nextPoolNumber()).thenReturn(3L, 4L);

        assertThat(service.createPool(createRequest("GBP", account("GBP", HOME_BIC, CORP, ENTITY, "1")))
                .getPoolReference()).isEqualTo("NP-0003");
        assertThat(service.createPool(createRequest("GBP", account("GBP", HOME_BIC, CORP, ENTITY, "1")))
                .getPoolReference()).isEqualTo("NP-0004");
    }

    @Test
    void deletingAPoolDeletesItsRateConfig() {
        UUID configId = UUID.randomUUID();
        NotionalPool pool = new NotionalPool();
        pool.setId(UUID.randomUUID());
        pool.setStatus(NotionalPool.PoolStatus.CLOSED);
        pool.setInterestConfigurationId(configId);
        when(pools.findById(pool.getId())).thenReturn(Optional.of(pool));

        service.deletePool(pool.getId());

        verify(pools).delete(pool);
        verify(configs).deleteById(configId);
    }

    // ---- 1.2 fees ----

    @Test
    void noFeeIsPostedBecauseThePoolServiceHasNoFeePosting() {
        assertThat(Arrays.stream(NotionalPoolService.class.getDeclaredFields()))
                .noneMatch(f -> f.getType() == FeePostingService.class);
    }

    // ---- 1.3 calculate ----

    @Test
    void theRetiredCalculateInterestIsGone_poolInterestComesFromDailyRuns() {
        assertThat(Arrays.stream(NotionalPoolService.class.getMethods())).noneMatch(m -> m.getName().equals("calculateInterest"));
    }

    // ---- 1.4 membership ----

    @Test
    void addMemberAcceptsAnEligibleAccountAndTheFirstSetsThePoolsCorporate() {
        NotionalPool pool = pool("GBP", null);
        VirtualAccount va = account("GBP", HOME_BIC, CORP, ENTITY, "100.00");

        service.addMember(pool.getId(), memberRequest(va));

        assertThat(pool.getMembers()).hasSize(1);
        assertThat(pool.getCorporateId()).isEqualTo(CORP);
        assertThat(pool.getTotalBalance()).isEqualByComparingTo("100.00");
    }

    @Test
    void addMemberRefusesAnotherCurrency() {
        NotionalPool pool = pool("GBP", CORP);
        VirtualAccount va = account("EUR", HOME_BIC, CORP, ENTITY, "1");
        assertRefused(pool, va, "is in EUR");
    }

    @Test
    void addMemberRefusesAnotherBank() {
        NotionalPool pool = pool("GBP", CORP);
        VirtualAccount va = account("GBP", "LOYDGB2LXXX", CORP, ENTITY, "1");
        assertRefused(pool, va, "home-bank-held");
    }

    @Test
    void addMemberRefusesAnInactiveAccount() {
        NotionalPool pool = pool("GBP", CORP);
        VirtualAccount va = account("GBP", HOME_BIC, CORP, ENTITY, "1");
        va.setStatus(VirtualAccount.VaStatus.INACTIVE);
        assertRefused(pool, va, "active accounts");
    }

    @Test
    void addMemberRefusesAnotherCorporate() {
        NotionalPool pool = pool("GBP", CORP);
        VirtualAccount va = account("GBP", HOME_BIC, UUID.randomUUID(), ENTITY, "1");
        assertRefused(pool, va, "another corporate");
    }

    @Test
    void addMemberRefusesAnotherLegalEntity() {
        NotionalPool pool = pool("GBP", CORP);
        VirtualAccount first = account("GBP", HOME_BIC, CORP, ENTITY, "1");
        service.addMember(pool.getId(), memberRequest(first));

        VirtualAccount other = account("GBP", HOME_BIC, CORP, UUID.randomUUID(), "1");
        assertRefused(pool, other, "another legal entity");
    }

    @Test
    void addMemberRefusesAnAccountAlreadyInAPool() {
        NotionalPool pool = pool("GBP", CORP);
        VirtualAccount va = account("GBP", HOME_BIC, CORP, ENTITY, "1");
        PoolMember elsewhere = new PoolMember();
        elsewhere.setAccountId(va.getId());
        when(members.findByAccountIdInAndStatus(anyCollection(), eq(PoolMember.MemberStatus.ACTIVE)))
                .thenReturn(List.of(elsewhere));
        assertRefused(pool, va, "already a member");
    }

    @Test
    void bulkAddSkipsRefusedAccountsWithTheirReasonAndAddsTheRest() {
        NotionalPool pool = pool("GBP", CORP);
        VirtualAccount ok = account("GBP", HOME_BIC, CORP, ENTITY, "10");
        VirtualAccount euro = account("EUR", HOME_BIC, CORP, ENTITY, "10");
        VirtualAccount otherEntity = account("GBP", HOME_BIC, CORP, UUID.randomUUID(), "10");
        NotionalPoolDto.BulkAddMembersRequest req = new NotionalPoolDto.BulkAddMembersRequest();
        req.setAccountIds(List.of(ok.getId(), euro.getId(), otherEntity.getId(), ok.getId()));

        NotionalPoolDto.BulkAddMembersResponse res = service.addMembersBulk(pool.getId(), req);

        assertThat(res.getAdded()).isEqualTo(1);
        assertThat(res.getSkipped()).extracting(NotionalPoolDto.SkippedMember::getReason)
                .anyMatch(r -> r.contains("is in EUR"))
                .anyMatch(r -> r.contains("another legal entity"))
                .anyMatch(r -> r.contains("Duplicate"));
    }

    @Test
    void createPoolRefusesMembersOfTwoEntities() {
        when(pools.nextPoolNumber()).thenReturn(1L);
        VirtualAccount a = account("GBP", HOME_BIC, CORP, ENTITY, "1");
        VirtualAccount b = account("GBP", HOME_BIC, CORP, UUID.randomUUID(), "1");
        NotionalPoolDto.CreateRequest req = createRequest("GBP", a, b);

        assertThatThrownBy(() -> service.createPool(req)).hasMessageContaining("another legal entity");
    }

    @Test
    void leavingKeepsTheRowDatedAndRecomputesTheTotals() {
        NotionalPool pool = pool("GBP", CORP);
        VirtualAccount stays = account("GBP", HOME_BIC, CORP, ENTITY, "30");
        VirtualAccount leaves = account("GBP", HOME_BIC, CORP, ENTITY, "70");
        service.addMember(pool.getId(), memberRequest(stays));
        service.addMember(pool.getId(), memberRequest(leaves));
        PoolMember leaver = pool.getMembers().get(1);
        leaver.setId(UUID.randomUUID());

        service.removeMember(pool.getId(), leaver.getId());

        assertThat(pool.getMembers()).hasSize(2);
        assertThat(leaver.getStatus()).isEqualTo(PoolMember.MemberStatus.INACTIVE);
        assertThat(leaver.getLeftDate()).isEqualTo(LocalDate.now());
        assertThat(pool.getMemberCount()).isEqualTo(1);
        assertThat(pool.getTotalBalance()).isEqualByComparingTo("30");
        verify(members, never()).delete(any());
    }

    @Test
    void suspendThenCloseAndAClosedPoolTakesNoMembers() {
        NotionalPool pool = pool("GBP", CORP);
        VirtualAccount va = account("GBP", HOME_BIC, CORP, ENTITY, "5");
        service.addMember(pool.getId(), memberRequest(va));

        assertThat(service.suspendPool(pool.getId()).getStatus()).isEqualTo(NotionalPool.PoolStatus.SUSPENDED);
        assertThatThrownBy(() -> service.suspendPool(pool.getId())).isInstanceOf(BusinessException.class);

        assertThat(service.closePool(pool.getId()).getStatus()).isEqualTo(NotionalPool.PoolStatus.CLOSED);
        assertThat(pool.getMembers().get(0).getStatus()).isEqualTo(PoolMember.MemberStatus.INACTIVE);
        assertThat(pool.getMemberCount()).isZero();
        assertThatThrownBy(() -> service.closePool(pool.getId())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.addMember(pool.getId(), memberRequest(va))).hasMessageContaining("closed");
    }

    @Test
    void anActivePoolClosesDirectly_afterPostingWhatItAccrued() {
        NotionalPool pool = pool("GBP", CORP);
        assertThat(service.closePool(pool.getId()).getStatus()).isEqualTo(NotionalPool.PoolStatus.CLOSED);
        verify(postings).postAccrued(pool); // R5: accrued interest posted before the pool closes
    }

    // ---- phase 2: header (R2) ----

    @Test
    void createPoolNeedsAMemberAndItsHeaderIsOneOfThem() {
        when(pools.nextPoolNumber()).thenReturn(1L);
        assertThatThrownBy(() -> service.createPool(createRequest("GBP"))).hasMessageContaining("at least one member");

        VirtualAccount a = account("GBP", HOME_BIC, CORP, ENTITY, "1");
        VirtualAccount b = account("GBP", HOME_BIC, CORP, ENTITY, "1");
        NotionalPoolDto.CreateRequest req = createRequest("GBP", a, b);
        req.setHeaderAccountId(UUID.randomUUID());
        assertThatThrownBy(() -> service.createPool(req)).hasMessageContaining("header account must be an active member");

        req.setHeaderAccountId(b.getId());
        assertThat(service.createPool(req).getHeaderAccountId()).isEqualTo(b.getId());
        // Not picked: the first member.
        assertThat(service.createPool(createRequest("GBP", account("GBP", HOME_BIC, CORP, ENTITY, "1"),
                account("GBP", HOME_BIC, CORP, ENTITY, "1"))).getHeaderAccountId()).isNotNull();
    }

    @Test
    void theHeaderCantLeaveUntilAnotherIsChosen() {
        NotionalPool pool = pool("GBP", CORP);
        VirtualAccount header = account("GBP", HOME_BIC, CORP, ENTITY, "10");
        VirtualAccount other = account("GBP", HOME_BIC, CORP, ENTITY, "20");
        service.addMember(pool.getId(), memberRequest(header));
        service.addMember(pool.getId(), memberRequest(other));
        pool.getMembers().forEach(m -> m.setId(UUID.randomUUID()));
        pool.setHeaderAccountId(header.getId());
        UUID headerMember = pool.getMembers().get(0).getId();

        assertThatThrownBy(() -> service.removeMember(pool.getId(), headerMember))
                .hasMessageContaining("choose another header first");

        NotionalPoolDto.UpdateRequest toStranger = new NotionalPoolDto.UpdateRequest();
        toStranger.setHeaderAccountId(UUID.randomUUID());
        assertThatThrownBy(() -> service.updatePool(pool.getId(), toStranger))
                .hasMessageContaining("header account must be an active member");

        NotionalPoolDto.UpdateRequest toOther = new NotionalPoolDto.UpdateRequest();
        toOther.setHeaderAccountId(other.getId());
        service.updatePool(pool.getId(), toOther);
        service.removeMember(pool.getId(), headerMember);
        assertThat(pool.getMembers().get(0).getStatus()).isEqualTo(PoolMember.MemberStatus.INACTIVE);
    }

    // ---- phase 2: legal entity (R4) ----

    @Test
    void theLegalEntityIsSetAtCreateAndHoldsEvenWithoutMembers() {
        when(pools.nextPoolNumber()).thenReturn(1L);
        UUID other = UUID.randomUUID();
        NotionalPoolDto.CreateRequest req = createRequest("GBP", account("GBP", HOME_BIC, CORP, ENTITY, "1"));
        req.setLegalEntityId(other);
        assertThatThrownBy(() -> service.createPool(req)).hasMessageContaining("another legal entity");

        req.setLegalEntityId(ENTITY);
        assertThat(service.createPool(req).getLegalEntityId()).isEqualTo(ENTITY);
        // Not given: the first member's.
        assertThat(service.createPool(createRequest("GBP", account("GBP", HOME_BIC, CORP, other, "1")))
                .getLegalEntityId()).isEqualTo(other);

        // The column, not the members, decides: an empty pool of ENTITY still refuses another entity.
        NotionalPool empty = pool("GBP", CORP);
        empty.setLegalEntityId(ENTITY);
        assertRefused(empty, account("GBP", HOME_BIC, CORP, other, "1"), "another legal entity");
    }

    // ---- phase 2: rates (R7) and limits (R13) ----

    @Test
    void createPoolMakesAPoolConfigWithTheCurrencysDayCount() {
        when(pools.nextPoolNumber()).thenReturn(1L, 2L, 3L);

        InterestConfiguration gbp = configOf(service.createPool(createRequest("GBP", account("GBP", HOME_BIC, CORP, ENTITY, "1"))));
        InterestConfiguration usd = configOf(service.createPool(createRequest("USD", account("USD", HOME_BIC, CORP, ENTITY, "1"))));
        NotionalPoolDto.CreateRequest chosen = createRequest("USD", account("USD", HOME_BIC, CORP, ENTITY, "1"));
        chosen.getRates().setDayCountConvention("ACT/365");
        InterestConfiguration overridden = configOf(service.createPool(chosen));

        assertThat(gbp.getDayCountConvention()).isEqualTo("ACT/365");
        assertThat(usd.getDayCountConvention()).isEqualTo("ACT/360");
        assertThat(overridden.getDayCountConvention()).isEqualTo("ACT/365");
        assertThat(gbp.getTargetType()).isEqualTo(InterestConfiguration.TargetType.POOL);
        assertThat(gbp.getConfigType()).isEqualTo(InterestConfiguration.ConfigType.INTERNAL);
        assertThat(gbp.getCorporateId()).isEqualTo(CORP);
    }

    @Test
    void ratesAreBasePlusSpreadAndAnEditUpdatesTheSameConfig() {
        when(pools.nextPoolNumber()).thenReturn(1L);
        NotionalPoolDto.CreateRequest req = createRequest("AED", account("AED", HOME_BIC, CORP, ENTITY, "1"));
        req.getRates().setCreditSpread(new BigDecimal("-0.25"));
        req.getRates().setDebitBaseRate(new BigDecimal("4.00"));
        req.getRates().setDebitSpread(new BigDecimal("1.50"));
        NotionalPoolDto.Response created = service.createPool(req);

        assertThat(created.getRates().getEffectiveCreditRate()).isEqualByComparingTo("2.75");
        assertThat(created.getRates().getEffectiveDebitRate()).isEqualByComparingTo("5.50");
        assertThat(created.getInterestRate()).isEqualByComparingTo("2.75"); // the retired column's display copy

        NotionalPool pool = new NotionalPool();
        pool.setId(created.getId());
        pool.setPoolReference(created.getPoolReference());
        pool.setInterestConfigurationId(created.getInterestConfigurationId());
        when(pools.findByIdWithMembers(pool.getId())).thenReturn(Optional.of(pool));
        NotionalPoolDto.UpdateRequest edit = new NotionalPoolDto.UpdateRequest();
        edit.setRates(new NotionalPoolDto.Rates());
        edit.getRates().setDebitSpread(new BigDecimal("2.00"));

        NotionalPoolDto.Response edited = service.updatePool(pool.getId(), edit);

        assertThat(edited.getInterestConfigurationId()).isEqualTo(created.getInterestConfigurationId());
        assertThat(edited.getRates().getEffectiveDebitRate()).isEqualByComparingTo("6.00");
        assertThat(edited.getRates().getEffectiveCreditRate()).isEqualByComparingTo("2.75");
        assertThat(savedConfigs).hasSize(1);
    }

    @Test
    void debitLimitsAndThePoolFloorAreKeptAndNeverNegative() {
        when(pools.nextPoolNumber()).thenReturn(1L, 2L);
        VirtualAccount a = account("GBP", HOME_BIC, CORP, ENTITY, "1");
        NotionalPoolDto.CreateRequest req = createRequest("GBP", a);
        req.setPoolFloor(new BigDecimal("-1"));
        assertThatThrownBy(() -> service.createPool(req)).hasMessageContaining("can't be negative");

        req.setPoolFloor(new BigDecimal("500000"));
        req.getMembers().get(0).setDebitLimit(new BigDecimal("100000"));
        NotionalPoolDto.Response created = service.createPool(req);
        assertThat(created.getPoolFloor()).isEqualByComparingTo("500000");
        assertThat(created.getMembers().get(0).getDebitLimit()).isEqualByComparingTo("100000");

        NotionalPool pool = pool("GBP", CORP);
        service.addMember(pool.getId(), memberRequest(account("GBP", HOME_BIC, CORP, ENTITY, "1")));
        PoolMember m = pool.getMembers().get(0);
        m.setId(UUID.randomUUID());
        pool.setPoolFloor(new BigDecimal("1"));
        NotionalPoolDto.UpdateRequest edit = new NotionalPoolDto.UpdateRequest();
        edit.setMemberDebitLimits(Map.of(m.getId(), new BigDecimal("250")));
        service.updatePool(pool.getId(), edit);
        assertThat(m.getDebitLimit()).isEqualByComparingTo("250");
        assertThat(pool.getPoolFloor()).isNull(); // the edit form sends the floor; absent = removed
    }

    // ---- phase 2: existing pools ----

    @Test
    void anOldPoolIsBackfilledFromItsMembersAndItsOldRate() {
        NotionalPool pool = pool("AED", CORP);
        pool.setInterestRate(new BigDecimal("3.5000"));
        VirtualAccount later = account("AED", HOME_BIC, CORP, UUID.randomUUID(), "1");
        VirtualAccount first = account("AED", HOME_BIC, CORP, ENTITY, "1");
        pool.getMembers().add(member(pool, later, LocalDate.of(2026, 3, 1), PoolMember.MemberStatus.ACTIVE));
        pool.getMembers().add(member(pool, first, LocalDate.of(2026, 1, 1), PoolMember.MemberStatus.ACTIVE));
        pool.getMembers().add(member(pool, account("AED", HOME_BIC, CORP, ENTITY, "1"), LocalDate.of(2025, 1, 1),
                PoolMember.MemberStatus.INACTIVE));
        when(pools.findAll()).thenReturn(List.of(pool));

        service.backfillPoolStructure();

        assertThat(pool.getHeaderAccountId()).isEqualTo(first.getId());
        assertThat(pool.getLegalEntityId()).isEqualTo(ENTITY);
        InterestConfiguration c = savedConfigs.get(pool.getInterestConfigurationId());
        assertThat(c.getEffectiveCreditRate()).isEqualByComparingTo("3.50");
        assertThat(c.getEffectiveDebitRate()).isEqualByComparingTo("5.50");
        assertThat(c.getDayCountConvention()).isEqualTo("ACT/365");
        assertThat(c.isSystemDefault()).isTrue();
        assertThat(c.getConfigName()).contains("a default");

        // Idempotent: a second start leaves it alone.
        service.backfillPoolStructure();
        assertThat(savedConfigs).hasSize(1);
    }

    // ---- helpers ----

    private InterestConfiguration configOf(NotionalPoolDto.Response pool) {
        return savedConfigs.get(pool.getInterestConfigurationId());
    }

    private static PoolMember member(NotionalPool pool, VirtualAccount va, LocalDate joined, PoolMember.MemberStatus status) {
        PoolMember m = new PoolMember();
        m.setPool(pool);
        m.setAccountId(va.getId());
        m.setAccountNumber(va.getVaNumber());
        m.setJoinedDate(joined);
        m.setStatus(status);
        return m;
    }

    private void assertRefused(NotionalPool pool, VirtualAccount va, String reason) {
        int before = pool.getMembers().size();
        assertThatThrownBy(() -> service.addMember(pool.getId(), memberRequest(va)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(reason);
        assertThat(pool.getMembers()).hasSize(before);
    }

    private NotionalPool pool(String ccy, UUID corporateId) {
        NotionalPool pool = new NotionalPool();
        pool.setId(UUID.randomUUID());
        pool.setPoolReference("NP-0099");
        pool.setPoolName("Test pool");
        pool.setPoolCurrency(ccy);
        pool.setCorporateId(corporateId);
        pool.setStatus(NotionalPool.PoolStatus.ACTIVE);
        when(pools.findByIdWithMembers(pool.getId())).thenReturn(Optional.of(pool));
        return pool;
    }

    private VirtualAccount account(String ccy, String bic, UUID corporateId, UUID entityId, String bankBalance) {
        VirtualAccount va = VirtualAccount.builder()
                .accountCategory(VirtualAccount.AccountCategory.PHYSICAL_MIRROR)
                .status(VirtualAccount.VaStatus.ACTIVE)
                .currencyCode(ccy)
                .bankSwift(bic)
                .corporateId(corporateId)
                .owningEntityId(entityId)
                .owningEntityCode("E-" + entityId.toString().substring(0, 4))
                .vaNumber("VA-" + UUID.randomUUID().toString().substring(0, 8))
                .bankBalance(new BigDecimal(bankBalance))
                .build();
        va.setId(UUID.randomUUID());
        accounts.put(va.getId(), va);
        return va;
    }

    private static NotionalPoolDto.MemberRequest memberRequest(VirtualAccount va) {
        NotionalPoolDto.MemberRequest r = new NotionalPoolDto.MemberRequest();
        r.setAccountId(va.getId());
        r.setAccountNumber(va.getVaNumber());
        return r;
    }

    private static NotionalPoolDto.CreateRequest createRequest(String ccy, VirtualAccount... members) {
        NotionalPoolDto.CreateRequest r = new NotionalPoolDto.CreateRequest();
        r.setPoolName("Pool");
        r.setPoolCurrency(ccy);
        r.setCorporateId(CORP);
        r.setRates(rates("3.00", null));
        r.setMembers(Arrays.stream(members).map(NotionalPoolServiceTest::memberRequest).toList());
        return r;
    }

    private static NotionalPoolDto.Rates rates(String creditBase, String dayCount) {
        NotionalPoolDto.Rates r = new NotionalPoolDto.Rates();
        r.setCreditBaseRate(new BigDecimal(creditBase));
        r.setDayCountConvention(dayCount);
        return r;
    }
}
