package com.bank.vam.service.treasury;

import com.bank.vam.config.HomeBankProperties;
import com.bank.vam.config.MarketProfileProperties;
import com.bank.vam.dto.treasury.NotionalPoolDto;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.treasury.NotionalPool;
import com.bank.vam.entity.treasury.PoolMember;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.repository.PhysicalAccountRepository;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.treasury.NotionalPoolRepository;
import com.bank.vam.repository.treasury.PoolMemberRepository;
import com.bank.vam.service.audit.AuditLogService;
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
    private final Map<UUID, VirtualAccount> accounts = new HashMap<>();
    private NotionalPoolService service;

    @BeforeEach
    void setUp() {
        HomeBankProperties home = new HomeBankProperties();
        home.setBic(HOME_BIC);
        service = new NotionalPoolService(pools, members, vas, mock(PhysicalAccountRepository.class), home,
                mock(AuditLogService.class), mock(MarketProfileProperties.class));
        when(pools.save(any())).thenAnswer(i -> i.getArgument(0));
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

        assertThat(service.createPool(createRequest("GBP")).getPoolReference()).isEqualTo("NP-0003");
        assertThat(service.createPool(createRequest("GBP")).getPoolReference()).isEqualTo("NP-0004");
    }

    // ---- 1.2 fees ----

    @Test
    void noFeeIsPostedBecauseThePoolServiceHasNoFeePosting() {
        assertThat(Arrays.stream(NotionalPoolService.class.getDeclaredFields()))
                .noneMatch(f -> f.getType() == FeePostingService.class);
    }

    // ---- 1.3 calculate ----

    @Test
    void calculateInterestRefusesInsteadOfAddingToTheYtd() {
        assertThatThrownBy(() -> service.calculateInterest(UUID.randomUUID()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("daily runs");
        verify(pools, never()).save(any());
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
        NotionalPoolDto.CreateRequest req = createRequest("GBP");
        req.setMembers(List.of(memberRequest(a), memberRequest(b)));

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
    void anActivePoolClosesDirectly() {
        NotionalPool pool = pool("GBP", CORP);
        assertThat(service.closePool(pool.getId()).getStatus()).isEqualTo(NotionalPool.PoolStatus.CLOSED);
    }

    // ---- helpers ----

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

    private static NotionalPoolDto.CreateRequest createRequest(String ccy) {
        NotionalPoolDto.CreateRequest r = new NotionalPoolDto.CreateRequest();
        r.setPoolName("Pool");
        r.setPoolCurrency(ccy);
        r.setCorporateId(CORP);
        r.setInterestRate(BigDecimal.ZERO);
        return r;
    }
}
