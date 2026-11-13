package com.bank.vam.service.treasury;

import com.bank.vam.entity.Transaction;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.VirtualAccount.MirrorAccountType;
import com.bank.vam.entity.treasury.NettingCycle;
import com.bank.vam.entity.treasury.NettingSettlement;
import com.bank.vam.repository.TransactionRepository;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.treasury.NettingCycleRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Netting settlement moves only real accounts (POBO spec R10): the RECEIVE leg credits the receiver's own account and
 * never debits treasury's IC receivable; the mirrors follow the IHB current position through IhbMirrorService.
 */
class NettingReceiveLegTest {

    private final NettingCycleRepository cycles = mock(NettingCycleRepository.class);
    private final VirtualAccountRepository vas = mock(VirtualAccountRepository.class);
    private final TransactionRepository ledger = mock(TransactionRepository.class);
    private final com.bank.vam.service.TransactionService transactions = mock(com.bank.vam.service.TransactionService.class);
    private final NettingService service = new NettingService(cycles, mock(com.bank.vam.repository.treasury.NettingEntryRepository.class),
        null, null, null, vas, ledger, mock(FeePostingService.class), null, mock(FxRateService.class),
        new IhbMirrorService(vas, ledger), null, transactions, null, null, null,
        mock(org.springframework.transaction.PlatformTransactionManager.class));

    private VirtualAccount va(String number, UUID owner, String balance) {
        VirtualAccount va = VirtualAccount.builder().vaNumber(number).currencyCode("AED").owningEntityId(owner)
            .status(VirtualAccount.VaStatus.ACTIVE).currentBalance(new BigDecimal(balance)).build();
        va.setId(UUID.randomUUID());
        when(vas.findById(va.getId())).thenReturn(Optional.of(va));
        return va;
    }

    private NettingSettlement settlement(UUID entity, String code, String receivables, String payables) {
        NettingSettlement s = NettingSettlement.builder().entityId(entity).entityCode(code)
            .grossReceivables(new BigDecimal(receivables)).grossPayables(new BigDecimal(payables)).build();
        s.setId(UUID.randomUUID());
        s.calculateNetPosition();
        return s;
    }

    @Test
    void theReceiveLegCreditsTheReceiversOwnAccountAndTheMirrorOnlyFollowsThePosition() {
        UUID treasury = UUID.randomUUID();
        UUID sub = UUID.randomUUID();
        // Treasury's IC receivable listed first: the old RECEIVE leg picked it (INTERCOMPANY / "receivable") and debited it.
        VirtualAccount icReceivable = va("ICR-SUB", treasury, "500");
        icReceivable.setMirrorAccountType(MirrorAccountType.IC_RECEIVABLE);
        icReceivable.setAccountCategory(VirtualAccount.AccountCategory.INTERCOMPANY);
        icReceivable.setVaName("IC Receivable - SUB");
        VirtualAccount settlementVa = va("TC-SETTLE", treasury, "1000");
        settlementVa.setMirrorAccountType(MirrorAccountType.TREASURY_SETTLEMENT);
        settlementVa.setSettlementMark(true);
        VirtualAccount ihbCurrent = va("IHB-SUB", sub, "-500");
        ihbCurrent.setIhbParticipant(true);
        ihbCurrent.setEffectiveCreditLimit(new BigDecimal("1000")); // headroom 500 covers the 300 leg
        ihbCurrent.setMirrorAccountType(MirrorAccountType.IHB_CURRENT);
        ihbCurrent.setIcReceivableVaId(icReceivable.getId());
        when(vas.findAll()).thenReturn(List.of(icReceivable, settlementVa, ihbCurrent));
        VirtualAccount bank = va("SHADOW-AED", treasury, "0"); // both accounts' program bank account: a ledger leg (D5)
        when(transactions.payoutShadow(any())).thenReturn(bank);
        when(vas.save(any())).thenAnswer(i -> i.getArgument(0));
        when(ledger.save(any())).thenAnswer(i -> i.getArgument(0));

        NettingCycle cycle = new NettingCycle();
        cycle.setId(UUID.randomUUID());
        cycle.setCycleReference("NET-1");
        cycle.setStatus(NettingCycle.CycleStatus.APPROVED);
        cycle.getSettlements().add(settlement(sub, "SUB", "0", "300"));        // pays 300
        cycle.getSettlements().add(settlement(treasury, "TC", "300", "0"));    // receives 300
        when(cycles.findByIdWithSettlements(cycle.getId())).thenReturn(Optional.of(cycle));

        service.settleCycle(cycle.getId());

        assertThat(ihbCurrent.getCurrentBalance()).isEqualByComparingTo("-800");
        assertThat(settlementVa.getCurrentBalance()).as("the receiver's own account is credited").isEqualByComparingTo("1300");
        assertThat(icReceivable.getCurrentBalance()).as("R10: follows the position, -(-800)").isEqualByComparingTo("800");
        ArgumentCaptor<Transaction> rows = ArgumentCaptor.forClass(Transaction.class);
        verify(ledger, atLeastOnce()).save(rows.capture());
        assertThat(rows.getAllValues()).filteredOn(t -> icReceivable.getId().equals(t.getVaId()))
            .as("the only IC receivable row is the mirror sync")
            .singleElement().satisfies(t -> assertThat(t.getChannel()).isEqualTo("IHB"));
    }
}
