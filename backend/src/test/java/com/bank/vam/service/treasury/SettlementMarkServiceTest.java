package com.bank.vam.service.treasury;

import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.VirtualAccount.AccountCategory;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.repository.VirtualAccountRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The two settlement transitions, and the rules that make them safe.
 *
 * <p>Setting needs a category that permits the mark and no marked sibling already serving the same
 * currency under the same parent -- two accounts both claiming to be *the* settlement account for a
 * currency under one aggregation is an ambiguity where whichever the resolver returned first would
 * decide where money went.
 *
 * <p>Clearing is checked against the resolver rather than against a restatement of its rules, because a
 * re-implemented check is how a settlement account came to exist that satisfied every plausible rule
 * while the resolver never found it. Refusals block rather than warn: an account left unable to resolve
 * parks its next payment to the exception account with no signal.
 */
class SettlementMarkServiceTest {

    private final VirtualAccountRepository vas = mock(VirtualAccountRepository.class);
    private final SettlementVaResolverService resolver = mock(SettlementVaResolverService.class);
    private final SettlementMarkService service = new SettlementMarkService(vas, resolver);

    private static final UUID PROGRAM = UUID.randomUUID();
    private static final UUID PARENT = UUID.randomUUID();

    private VirtualAccount va(String number, AccountCategory category, boolean marked) {
        VirtualAccount v = VirtualAccount.builder()
                .vaNumber(number).accountCategory(category).settlementMark(marked)
                .currencyCode("AED").programId(PROGRAM).parentAccountId(PARENT)
                .currentBalance(BigDecimal.ZERO)
                .build();
        v.setId(UUID.randomUUID());
        when(vas.findById(v.getId())).thenReturn(Optional.of(v));
        return v;
    }

    private void stubSaves() {
        when(vas.save(any())).thenAnswer(i -> i.getArgument(0));
        when(vas.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
    }

    // ---------------------------------------------------------------- setting

    @Test
    void anOperationalAccountCanBeMarked() {
        stubSaves();
        VirtualAccount va = va("VA-TXN-1", AccountCategory.TRANSACTION, false);
        when(vas.findByParentAccountIdAndSettlementMarkTrue(PARENT)).thenReturn(List.of());

        VirtualAccount marked = service.set(va.getId());

        assertThat(marked.isSettlementVa()).isTrue();
        // still a transaction account: the mark is a role, not a kind
        assertThat(marked.getAccountCategory()).isEqualTo(AccountCategory.TRANSACTION);
    }

    @Test
    void aContainerCannotBeMarkedHoweverConvenientItsPositionLooks() {
        VirtualAccount agg = va("AGG-1", AccountCategory.AGGREGATION, false);

        assertThatThrownBy(() -> service.set(agg.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("only an operational account");
        verify(vas, never()).save(any());
    }

    @Test
    void aSecondMarkInTheSameCurrencyUnderOneParentIsRefused() {
        VirtualAccount existing = va("SETTLE-AED-1", AccountCategory.TRANSACTION, true);
        VirtualAccount candidate = va("VA-TXN-2", AccountCategory.TRANSACTION, false);
        when(vas.findByParentAccountIdAndSettlementMarkTrue(PARENT)).thenReturn(List.of(existing));

        assertThatThrownBy(() -> service.set(candidate.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("SETTLE-AED-1")
                .hasMessageContaining("already carries the mark");
        verify(vas, never()).save(any());
    }

    @Test
    void aSiblingInAnotherCurrencyIsNoObstacle() {
        stubSaves();
        VirtualAccount gbp = VirtualAccount.builder()
                .vaNumber("SETTLE-GBP-1").accountCategory(AccountCategory.TRANSACTION)
                .settlementMark(true).currencyCode("GBP").programId(PROGRAM).parentAccountId(PARENT)
                .build();
        gbp.setId(UUID.randomUUID());
        VirtualAccount aed = va("VA-TXN-3", AccountCategory.TRANSACTION, false);
        when(vas.findByParentAccountIdAndSettlementMarkTrue(PARENT)).thenReturn(List.of(gbp));

        assertThat(service.set(aed.getId()).isSettlementVa()).isTrue();
    }

    @Test
    void markingAnAlreadyMarkedAccountIsAQuietNoOp() {
        VirtualAccount va = va("SETTLE-AED-2", AccountCategory.TRANSACTION, true);

        assertThat(service.set(va.getId()).isSettlementVa()).isTrue();
        verify(vas, never()).save(any());
    }

    // --------------------------------------------------------------- clearing

    /** Nothing else depends on it, so releasing it strands no one. */
    @Test
    void theMarkClearsWhenEveryAccountStillResolves() {
        stubSaves();
        VirtualAccount settlement = va("SETTLE-AED-3", AccountCategory.TRANSACTION, true);
        VirtualAccount other = va("VA-TXN-4", AccountCategory.TRANSACTION, false);
        when(vas.findByProgramId(PROGRAM)).thenReturn(List.of(settlement, other));
        when(resolver.resolveSettlementVaWithResult(other))
                .thenReturn(SettlementVaResolverService.SettlementVaResolutionResult.success(settlement));

        VirtualAccount cleared = service.clear(settlement.getId());

        assertThat(cleared.isSettlementVa()).isFalse();
    }

    /** The upward search loses: a priced account would have no contra settlement account. */
    @Test
    void clearingIsRefusedWhenAContraLegWouldBeStranded() {
        stubSaves();
        VirtualAccount settlement = va("SETTLE-AED-4", AccountCategory.TRANSACTION, true);
        VirtualAccount orphaned = va("VA-TXN-5", AccountCategory.TRANSACTION, false);
        when(vas.findByProgramId(PROGRAM)).thenReturn(List.of(settlement, orphaned));
        when(resolver.resolveSettlementVaWithResult(orphaned))
                .thenReturn(SettlementVaResolverService.SettlementVaResolutionResult
                        .failedWithException(null, null, "no settlement VA"));

        assertThatThrownBy(() -> service.clear(settlement.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("customer-internal prices")
                .hasMessageContaining("VA-TXN-5");
    }

    /** The downward search loses: a container's results would fall through to the exception account. */
    @Test
    void clearingIsRefusedWhenAContainersResultWouldFallToException() {
        stubSaves();
        VirtualAccount settlement = va("SETTLE-AED-5", AccountCategory.TRANSACTION, true);
        VirtualAccount container = va("AGG-2", AccountCategory.AGGREGATION, false);
        VirtualAccount exceptionVa = va("EXCEPT-AED", AccountCategory.EXCEPTION, false);
        when(vas.findByProgramId(PROGRAM)).thenReturn(List.of(settlement, container));
        // the resolver falls back to the exception account rather than failing, so the test is what it
        // returned, not whether it threw
        when(resolver.settlementAccountForResultOn(container)).thenReturn(exceptionVa);

        assertThatThrownBy(() -> service.clear(settlement.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("nowhere to settle their")
                .hasMessageContaining("AGG-2");
    }

    /** Both conditions are independent and both are named, not just the first to fail. */
    @Test
    void bothReleaseConditionsAreReportedSeparately() {
        stubSaves();
        VirtualAccount settlement = va("SETTLE-AED-6", AccountCategory.TRANSACTION, true);
        VirtualAccount orphaned = va("VA-TXN-6", AccountCategory.TRANSACTION, false);
        VirtualAccount container = va("AGG-3", AccountCategory.AGGREGATION, false);
        VirtualAccount exceptionVa = va("EXCEPT-AED-2", AccountCategory.EXCEPTION, false);
        when(vas.findByProgramId(PROGRAM)).thenReturn(List.of(settlement, orphaned, container));
        when(resolver.resolveSettlementVaWithResult(orphaned))
                .thenReturn(SettlementVaResolverService.SettlementVaResolutionResult
                        .failedWithException(null, null, "none"));
        when(resolver.settlementAccountForResultOn(container)).thenReturn(exceptionVa);

        assertThatThrownBy(() -> service.clear(settlement.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("customer-internal prices")
                .hasMessageContaining("nowhere to settle their");
    }

    @Test
    void clearingAnUnmarkedAccountIsAQuietNoOp() {
        VirtualAccount va = va("VA-TXN-7", AccountCategory.TRANSACTION, false);

        assertThat(service.clear(va.getId()).isSettlementVa()).isFalse();
        verify(vas, never()).saveAndFlush(any());
    }
}
