package com.bank.vam.service.wallet;

import com.bank.vam.dto.WalletDto;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.repository.VirtualAccountRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Guards the wallet spend limits that were modelled but never checked.
 *
 * Program defaults, VA columns, usage counters and the isWithin*Limit helpers
 * all existed; only two of them were ever called, both on the withdrawal path.
 * The weekly, monthly and annual checks were reachable solely through
 * isWithinAllLimits(), which had no callers, and transferFunds() checked the
 * balance and nothing else — so the same money leaving the same wallet was
 * capped as a withdrawal and uncapped as a transfer, while transferOut() still
 * consumed the usage counters a later withdrawal would be measured against.
 *
 * programId is left null throughout so the fee lookups short-circuit to zero
 * and these tests exercise the limit checks rather than the fee engine.
 */
class WalletSpendLimitTest {

    private VirtualAccount wallet(BigDecimal balance) {
        VirtualAccount w = new VirtualAccount();
        w.setId(UUID.randomUUID());
        w.setVaNumber("WAL-" + w.getId());
        w.setStatus(VirtualAccount.VaStatus.ACTIVE);
        w.setCurrentBalance(balance);
        w.setAvailableBalance(balance);
        return w;
    }

    private WalletService serviceWith(VirtualAccount... wallets) {
        VirtualAccountRepository vaRepo = mock(VirtualAccountRepository.class);
        for (VirtualAccount w : wallets) {
            when(vaRepo.findById(w.getId())).thenReturn(Optional.of(w));
        }
        when(vaRepo.save(any(VirtualAccount.class))).thenAnswer(i -> i.getArgument(0));
        return new WalletService(null, vaRepo, null, null, null, null, null, null, null, null);
    }

    @Test
    void withdrawalIsRejectedWhenItWouldBreachTheMonthlyLimit() {
        VirtualAccount w = wallet(new BigDecimal("10000"));
        w.setMonthlyLimit(new BigDecimal("1000"));
        w.setMonthlyUsed(new BigDecimal("900"));

        // 900 + 200 > 1000. Funds are ample and the daily limit is unset, so
        // before the fix this withdrawal went through.
        assertThatThrownBy(() -> serviceWith(w).withdrawFunds(w.getId(),
                WalletDto.WithdrawRequest.builder().amount(new BigDecimal("200")).build()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("monthly");
    }

    @Test
    void transferIsRejectedWhenItWouldBreachTheSourceDailyLimit() {
        VirtualAccount from = wallet(new BigDecimal("10000"));
        from.setDailyLimit(new BigDecimal("500"));
        from.setDailyUsed(new BigDecimal("400"));
        VirtualAccount to = wallet(BigDecimal.ZERO);

        // The same 200 would be refused as a withdrawal; a transfer must not be
        // the way around it.
        assertThatThrownBy(() -> serviceWith(from, to).transferFunds(from.getId(),
                WalletDto.TransferRequest.builder()
                    .toWalletId(to.getId()).amount(new BigDecimal("200")).build()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("daily");
    }

    @Test
    void transferIsRejectedWhenTheDestinationWouldExceedItsMaxBalance() {
        VirtualAccount from = wallet(new BigDecimal("10000"));
        VirtualAccount to = wallet(new BigDecimal("900"));
        to.setMaxBalance(new BigDecimal("1000"));

        // loadFunds() refuses to push a wallet past maxBalance; crediting it by
        // transfer instead must refuse too.
        assertThatThrownBy(() -> serviceWith(from, to).transferFunds(from.getId(),
                WalletDto.TransferRequest.builder()
                    .toWalletId(to.getId()).amount(new BigDecimal("200")).build()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("destination");
    }
}
