package com.bank.vam.service.treasury;

import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.VirtualAccount.AccountCategory;
import com.bank.vam.repository.VirtualAccountRepository;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A currency mirror is a derived view, so it must never carry cash of its own.
 *
 * <p>Its balance is the sum of the same-currency transaction accounts beside it, and that figure
 * belongs in {@code mirrorBalance}. {@code currentBalance} on a mirror is a second encoding of the
 * same fact, and it drifted: the recompute has always written mirrorBalance, fxRate, fxRateAt and
 * balanceInBase and never currentBalance, so whatever an older path left there stayed. Locally that
 * was 231,381.00 across 15 of 46 mirrors against a true mirrorBalance of 20,368.76.
 *
 * <p>The damage was double counting rather than display. Several balance queries carry no category
 * filter -- {@code sumBalanceByParent}, {@code sumBalanceByParentAndCurrency}, the whole-table totals
 * -- and a mirror is a child of its aggregation, so summing an aggregation's children added the
 * mirror's cash to the very accounts it mirrors. A mirror whose currentBalance happened to equal its
 * mirrorBalance was double counted just the same, which is why agreement never proved correctness.
 */
class CurrencyMirrorHoldsNoCashTest {

    private final VirtualAccountRepository vas = mock(VirtualAccountRepository.class);
    private final FxRateService fx = mock(FxRateService.class);
    private final BalanceAggregationServiceEnhanced service =
        new BalanceAggregationServiceEnhanced(vas, fx);

    private VirtualAccount account(String number, AccountCategory category, String balance) {
        VirtualAccount va = VirtualAccount.builder()
            .vaNumber(number)
            .accountCategory(category)
            .currencyCode("AED")
            .baseCurrency("AED")
            .currentBalance(new BigDecimal(balance))
            .availableBalance(new BigDecimal(balance))
            .heldBalance(BigDecimal.ZERO)
            .build();
        va.setId(UUID.randomUUID());
        return va;
    }

    /**
     * The mirror starts with stale cash on it, exactly as the live rows did, and a recompute must
     * leave it at zero while putting the real figure in mirrorBalance.
     */
    @Test
    void aRecomputeClearsStaleCashFromTheMirrorAndLeavesTheFigureInMirrorBalance() {
        VirtualAccount parent = account("AGG-1", AccountCategory.AGGREGATION, "0");
        VirtualAccount mirror = account("M-AED-1", AccountCategory.CURRENCY_MIRROR, "228981.00");
        mirror.setParentAccountId(parent.getId());
        VirtualAccount txnA = account("TXN-1", AccountCategory.TRANSACTION, "4966.00");
        VirtualAccount txnB = account("TXN-2", AccountCategory.TRANSACTION, "0.96");
        txnA.setParentAccountId(parent.getId());
        txnB.setParentAccountId(parent.getId());

        when(vas.findByParentAccountId(parent.getId())).thenReturn(List.of(mirror, txnA, txnB));
        when(vas.findByParentAccountIdAndAccountCategory(any(), any())).thenReturn(List.of());
        when(vas.save(any())).thenAnswer(i -> i.getArgument(0));

        service.recalculateCurrencyMirror(mirror, LocalDateTime.now());

        // the derived figure is the sum of its operational siblings, and it lives in mirrorBalance
        assertThat(mirror.getMirrorBalance()).isEqualByComparingTo("4966.96");
        // and the mirror itself holds no cash, so summing the parent's children cannot double count
        assertThat(mirror.getCurrentBalance()).isEqualByComparingTo("0");
        assertThat(mirror.getAvailableBalance()).isEqualByComparingTo("0");
    }

    /** available = current - held must still hold once the cash columns are pinned. */
    @Test
    void theBalanceInvariantSurvivesPinning() {
        VirtualAccount parent = account("AGG-2", AccountCategory.AGGREGATION, "0");
        VirtualAccount mirror = account("M-AED-2", AccountCategory.CURRENCY_MIRROR, "500.00");
        mirror.setParentAccountId(parent.getId());

        when(vas.findByParentAccountId(parent.getId())).thenReturn(List.of(mirror));
        when(vas.findByParentAccountIdAndAccountCategory(any(), any())).thenReturn(List.of());
        when(vas.save(any())).thenAnswer(i -> i.getArgument(0));

        service.recalculateCurrencyMirror(mirror, LocalDateTime.now());

        BigDecimal held = mirror.getHeldBalance() == null ? BigDecimal.ZERO : mirror.getHeldBalance();
        assertThat(mirror.getAvailableBalance())
            .isEqualByComparingTo(mirror.getCurrentBalance().subtract(held));
    }
}
