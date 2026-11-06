package com.bank.vam.service.treasury;

import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.VirtualAccount.AccountCategory;
import com.bank.vam.repository.VirtualAccountRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A payment and the corporate's balance recalculation used to deadlock: the recalculation was started inside the
 * payment's transaction (before it committed) and rewrote the whole hierarchy in one transaction, locking in another
 * order the mirrors and aggregations the payment had locked. Now it starts after the commit and takes one node at a
 * time, each in a transaction of its own.
 */
class BalanceRefreshAfterCommitTest {

    private final VirtualAccountRepository vas = mock(VirtualAccountRepository.class);
    private final FxRateService fx = mock(FxRateService.class);
    private final BalanceAggregationServiceEnhanced service = new BalanceAggregationServiceEnhanced(vas, fx);
    private final BalanceAggregationServiceEnhanced self = mock(BalanceAggregationServiceEnhanced.class);
    private final UUID corporate = UUID.randomUUID();

    @AfterEach
    void clear() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.unbindResourceIfPossible("balance-refresh:" + corporate);
    }

    private VirtualAccount account(AccountCategory category, int level) {
        VirtualAccount v = VirtualAccount.builder().vaNumber(category + "-" + level).accountCategory(category).currencyCode("AED")
                .baseCurrency("AED").corporateId(corporate).hierarchyLevel(level).currentBalance(BigDecimal.TEN).build();
        v.setId(UUID.randomUUID());
        return v;
    }

    @Test
    void insideATransactionTheRecalculationWaitsForTheCommitAndRunsOncePerCorporate() {
        ReflectionTestUtils.setField(service, "self", self);
        VirtualAccount a = account(AccountCategory.TRANSACTION, 3);
        VirtualAccount b = account(AccountCategory.TRANSACTION, 3);
        when(vas.findById(a.getId())).thenReturn(Optional.of(a));
        when(vas.findById(b.getId())).thenReturn(Optional.of(b));
        TransactionSynchronizationManager.initSynchronization();

        service.propagateBalanceChange(a.getId(), new BigDecimal("-25"));
        service.propagateBalanceChange(b.getId(), new BigDecimal("25"));

        verify(self, never()).fullRefreshAfterTransaction(any(), any());
        List<TransactionSynchronization> registered = TransactionSynchronizationManager.getSynchronizations();
        assertThat(registered).hasSize(1);
        registered.forEach(TransactionSynchronization::afterCommit);
        registered.forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
        verify(self, times(1)).fullRefreshAfterTransaction(eq(corporate), eq(a.getId()));
        assertThat(TransactionSynchronizationManager.hasResource("balance-refresh:" + corporate)).isFalse();
    }

    @Test
    void theRecalculationTakesEachNodeInATransactionOfItsOwnThroughItsRowLock() {
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        ReflectionTestUtils.setField(service, "transactionManager", tm);
        VirtualAccount root = account(AccountCategory.ROOT, 0);
        VirtualAccount agg = account(AccountCategory.AGGREGATION, 1);
        VirtualAccount mirror = account(AccountCategory.CURRENCY_MIRROR, 2);
        mirror.setParentAccountId(agg.getId());
        when(vas.findByCorporateId(corporate)).thenReturn(new java.util.ArrayList<>(List.of(root, agg, mirror)));
        for (VirtualAccount v : List.of(root, agg, mirror)) {
            when(vas.lockById(v.getId())).thenReturn(Optional.of(v));
        }

        assertThat(service.aggregateForCorporate(corporate)).isEqualTo(3);

        verify(tm, times(3)).getTransaction(argThat(d -> d.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW));
        verify(tm, times(3)).commit(any());
        verify(vas).lockById(mirror.getId());
        verify(vas).lockById(agg.getId());
        verify(vas).lockById(root.getId());
    }
}
