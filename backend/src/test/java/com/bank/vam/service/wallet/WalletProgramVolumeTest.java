package com.bank.vam.service.wallet;

import com.bank.vam.entity.Program;
import com.bank.vam.entity.Transaction;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.repository.TransactionRepository;
import com.bank.vam.service.treasury.FxRateService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/** A program's volumes and counts are the movements on its wallets, not constants. */
@ExtendWith(MockitoExtension.class)
class WalletProgramVolumeTest {

    @Mock TransactionRepository transactionRepository;
    @Mock FxRateService fxRateService;
    @InjectMocks WalletService service;

    @Test
    void volumesAreSummedFromTheWalletsTransactions() {
        UUID walletId = UUID.randomUUID();
        VirtualAccount wallet = VirtualAccount.builder().build();
        wallet.setId(walletId);
        Program program = Program.builder().currencyCode("USD").build();

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime threeDaysAgo = now.minusDays(3);
        when(transactionRepository.findByVaIdInAndTransactionDateGreaterThanEqual(eq(List.of(walletId)), any()))
            .thenReturn(List.of(
                txn("100", now),
                txn("-40", now),           // a debit counts by size
                txn("50", threeDaysAgo)));
        when(fxRateService.convert(any(), eq("USD"), eq("USD"))).thenAnswer(inv -> inv.getArgument(0));

        WalletService.ProgramVolume v = service.programVolume(program, List.of(wallet));

        assertEquals(0, new BigDecimal("140").compareTo(v.daily()));
        assertEquals(2, v.dailyCount());
        assertEquals(0, new BigDecimal("190").compareTo(v.weekly()));
        assertEquals(3, v.weeklyCount());
        boolean sameMonth = !threeDaysAgo.toLocalDate().isBefore(LocalDate.now().withDayOfMonth(1));
        assertEquals(0, new BigDecimal(sameMonth ? "190" : "140").compareTo(v.monthly()));
        assertEquals(sameMonth ? 3 : 2, v.monthlyCount());
    }

    private static Transaction txn(String amount, LocalDateTime at) {
        return Transaction.builder().amount(new BigDecimal(amount)).currencyCode("USD").transactionDate(at).build();
    }
}
