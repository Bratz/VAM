package com.bank.vam.service.treasury;

import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.VirtualAccount.AccountCategory;
import com.bank.vam.entity.VirtualAccount.VaStatus;
import com.bank.vam.repository.ProgramRepository;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.treasury.ExceptionTransactionRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the downward rule: which account actually bears a result charged to a given account.
 *
 * A transaction account settles on itself. A container -- root, aggregation, currency mirror --
 * cannot, because its balance is the sum of the transaction accounts beneath it, so the result
 * goes to the nearest settlement VA *below* it, and to its exception VA when there is none. For a
 * currency mirror the search starts from its mother, not from the mirror.
 *
 * This is the counterpart to the upward search used for a contra leg. The two answer different
 * questions and must not be chained: a container with a settlement VA above it but none below it
 * still settles on its exception VA, which the last test here holds in place.
 *
 * The logic used to live as a private helper inside FeePostingService, so it applied to fees and
 * nothing else. These tests cover it where it now lives.
 */
class SettlementResultAccountTest {

    private final VirtualAccountRepository vaRepository = mock(VirtualAccountRepository.class);
    private final ProgramRepository programRepository = mock(ProgramRepository.class);
    private final ExceptionTransactionRepository exceptionRepository = mock(ExceptionTransactionRepository.class);

    private SettlementVaResolverService resolver() {
        when(vaRepository.save(any(VirtualAccount.class))).thenAnswer(inv -> {
            VirtualAccount va = inv.getArgument(0);
            if (va.getId() == null) {
                va.setId(UUID.randomUUID());
            }
            return va;
        });
        return new SettlementVaResolverService(vaRepository, programRepository, exceptionRepository);
    }

    private static VirtualAccount va(String number, AccountCategory category, UUID parent) {
        VirtualAccount account = VirtualAccount.builder()
                .vaNumber(number)
                .accountCategory(category)
                .parentAccountId(parent)
                .currencyCode("AED")
                .programId(UUID.randomUUID())
                .status(VaStatus.ACTIVE)
                .build();
        account.setId(UUID.randomUUID());
        return account;
    }

    @Test
    void aTransactionAccountBearsItsOwnResult() {
        VirtualAccount txn = va("TXN-1", AccountCategory.TRANSACTION, UUID.randomUUID());
        assertThat(resolver().settlementAccountForResultOn(txn)).isSameAs(txn);
    }

    @Test
    void anAccountWithNoCategoryBearsItsOwnResult() {
        VirtualAccount unknown = va("TXN-2", null, null);
        assertThat(resolver().settlementAccountForResultOn(unknown)).isSameAs(unknown);
    }

    @Test
    void anAggregationSettlesOnTheSettlementVaBelowIt() {
        VirtualAccount agg = va("AGG-1", AccountCategory.AGGREGATION, UUID.randomUUID());
        VirtualAccount below = va("SETTLE-1", AccountCategory.SETTLEMENT, agg.getId());
        when(vaRepository.findByParentAccountIdAndAccountCategory(agg.getId(), AccountCategory.SETTLEMENT))
                .thenReturn(List.of(below));

        assertThat(resolver().settlementAccountForResultOn(agg)).isSameAs(below);
    }

    @Test
    void aCurrencyMirrorSearchesFromItsMotherNotFromItself() {
        UUID motherId = UUID.randomUUID();
        VirtualAccount mirror = va("M-AED", AccountCategory.CURRENCY_MIRROR, motherId);
        VirtualAccount below = va("SETTLE-2", AccountCategory.SETTLEMENT, motherId);
        // Only the mother has a settlement child. Searching from the mirror itself finds nothing,
        // so this passing is what proves the start point is the mother.
        when(vaRepository.findByParentAccountIdAndAccountCategory(motherId, AccountCategory.SETTLEMENT))
                .thenReturn(List.of(below));

        assertThat(resolver().settlementAccountForResultOn(mirror)).isSameAs(below);
    }

    @Test
    void theNearestSettlementVaWinsOverADeeperOne() {
        VirtualAccount root = va("ROOT-1", AccountCategory.ROOT, null);
        VirtualAccount child = va("AGG-2", AccountCategory.AGGREGATION, root.getId());
        VirtualAccount near = va("SETTLE-NEAR", AccountCategory.SETTLEMENT, root.getId());
        VirtualAccount deep = va("SETTLE-DEEP", AccountCategory.SETTLEMENT, child.getId());
        when(vaRepository.findByParentAccountIdAndAccountCategory(root.getId(), AccountCategory.SETTLEMENT))
                .thenReturn(List.of(near));
        when(vaRepository.findByParentAccountIdAndAccountCategory(child.getId(), AccountCategory.SETTLEMENT))
                .thenReturn(List.of(deep));
        when(vaRepository.findByParentAccountId(root.getId())).thenReturn(List.of(child, near));

        assertThat(resolver().settlementAccountForResultOn(root)).isSameAs(near);
    }

    @Test
    void aContainerWithNothingBelowFallsBackToItsExceptionVa() {
        VirtualAccount agg = va("AGG-3", AccountCategory.AGGREGATION, UUID.randomUUID());
        // Every settlement lookup returns empty (Mockito's default for List), so the only way out
        // is the exception VA -- created here because none exists.
        VirtualAccount borne = resolver().settlementAccountForResultOn(agg);

        assertThat(borne).isNotSameAs(agg);
        assertThat(borne.getAccountCategory()).isEqualTo(AccountCategory.EXCEPTION);
    }

    @Test
    void aSettlementVaAboveDoesNotSatisfyAResultOnAContainer() {
        // The two searches must not be chained. A container whose settlement VA sits ABOVE it has
        // nothing below, so its result belongs on the exception VA -- taking the upward match here
        // would post a result onto an account that is not beneath the one being charged.
        UUID grandparentId = UUID.randomUUID();
        VirtualAccount agg = va("AGG-4", AccountCategory.AGGREGATION, grandparentId);
        VirtualAccount above = va("SETTLE-ABOVE", AccountCategory.SETTLEMENT, grandparentId);
        when(vaRepository.findByParentAccountIdAndAccountCategory(grandparentId, AccountCategory.SETTLEMENT))
                .thenReturn(List.of(above));

        VirtualAccount borne = resolver().settlementAccountForResultOn(agg);

        assertThat(borne).isNotSameAs(above);
        assertThat(borne.getAccountCategory()).isEqualTo(AccountCategory.EXCEPTION);
    }
}
