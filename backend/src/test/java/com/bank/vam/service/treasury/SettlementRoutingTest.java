package com.bank.vam.service.treasury;

import com.bank.vam.entity.Transaction;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.VirtualAccount.AccountCategory;
import com.bank.vam.entity.treasury.ExceptionTransaction;
import com.bank.vam.repository.ProgramRepository;
import com.bank.vam.repository.TransactionRepository;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.treasury.ExceptionTransactionRepository;
import com.bank.vam.service.receivables.ReconciliationService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Settlement routing as in Tieto VAM: the contra is found on the account's own path only (never
 * another branch or program), a container's results settle on the nearest settlement VA below it,
 * and the bank's charges on the real account post to the program's settlement VA.
 */
class SettlementRoutingTest {

    private final VirtualAccountRepository vas = mock(VirtualAccountRepository.class);
    private final ExceptionTransactionRepository exceptions = mock(ExceptionTransactionRepository.class);
    private final SettlementVaResolverService resolver =
        new SettlementVaResolverService(vas, mock(ProgramRepository.class), exceptions);
    private final UUID programId = UUID.randomUUID();

    private VirtualAccount va(AccountCategory category, UUID parentId) {
        VirtualAccount v = VirtualAccount.builder().vaNumber(category + "-" + UUID.randomUUID())
            .accountCategory(category).parentAccountId(parentId).programId(programId)
            .corporateId(UUID.randomUUID()).currencyCode("EUR").status(VirtualAccount.VaStatus.ACTIVE)
            .currentBalance(BigDecimal.ZERO).build();
        v.setId(UUID.randomUUID());
        return v;
    }

    @Test
    void contraNeverComesFromAnotherBranchOrProgram() {
        VirtualAccount root = va(AccountCategory.ROOT, null);
        VirtualAccount uk = va(AccountCategory.AGGREGATION, root.getId());
        VirtualAccount de = va(AccountCategory.AGGREGATION, root.getId());
        VirtualAccount ukOps = va(AccountCategory.TRANSACTION, uk.getId());
        VirtualAccount deSettlement = va(AccountCategory.SETTLEMENT, de.getId());   // other branch
        when(vas.findById(root.getId())).thenReturn(Optional.of(root));
        when(vas.findById(uk.getId())).thenReturn(Optional.of(uk));
        when(vas.findByProgramIdAndAccountCategory(programId, AccountCategory.SETTLEMENT)).thenReturn(List.of(deSettlement));
        when(vas.findSettlementVa(any(), any())).thenReturn(Optional.of(deSettlement));      // corporate-wide
        when(vas.save(any(VirtualAccount.class))).thenAnswer(i -> { VirtualAccount v = i.getArgument(0); if (v.getId() == null) v.setId(UUID.randomUUID()); return v; });
        when(exceptions.save(any(ExceptionTransaction.class))).thenAnswer(i -> i.getArgument(0));

        var result = resolver.resolveSettlementVaWithResult(ukOps, BigDecimal.TEN, null);

        assertThat(result.isSuccess()).isFalse();                       // parked, as Tieto: exception account
        assertThat(result.getResolvedVa()).isNotEqualTo(deSettlement);
    }

    @Test
    void theProgramsTopLevelSettlementVaIsTheTopOfEveryPath() {
        VirtualAccount root = va(AccountCategory.ROOT, null);
        VirtualAccount ukOps = va(AccountCategory.TRANSACTION, root.getId());
        VirtualAccount top = va(AccountCategory.SETTLEMENT, null);
        when(vas.findById(root.getId())).thenReturn(Optional.of(root));
        when(vas.findByProgramIdAndAccountCategory(programId, AccountCategory.SETTLEMENT)).thenReturn(List.of(top));

        var result = resolver.resolveSettlementVaWithResult(ukOps, BigDecimal.TEN, null);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResolvedVa()).isSameAs(top);
    }

    @Test
    void aContainersResultsSettleOnTheNearestSettlementVaBelowIt() {
        VirtualAccount emea = va(AccountCategory.AGGREGATION, null);
        VirtualAccount uk = va(AccountCategory.AGGREGATION, emea.getId());
        VirtualAccount ukSettlement = va(AccountCategory.SETTLEMENT, uk.getId());
        VirtualAccount deeper = va(AccountCategory.AGGREGATION, uk.getId());
        VirtualAccount deeperSettlement = va(AccountCategory.SETTLEMENT, deeper.getId());
        when(vas.findByParentAccountId(emea.getId())).thenReturn(List.of(uk));
        when(vas.findByParentAccountId(uk.getId())).thenReturn(List.of(ukSettlement, deeper));
        when(vas.findByParentAccountIdAndAccountCategory(uk.getId(), AccountCategory.SETTLEMENT)).thenReturn(List.of(ukSettlement));
        when(vas.findByParentAccountIdAndAccountCategory(deeper.getId(), AccountCategory.SETTLEMENT)).thenReturn(List.of(deeperSettlement));

        assertThat(resolver.findSettlementVaBelow(emea, "EUR")).contains(ukSettlement);   // closest level wins
    }

    @Test
    void bankChargesPostToTheProgramsSettlementVaOncePerStatementLine() {
        VirtualAccount top = va(AccountCategory.SETTLEMENT, null);
        top.setCurrentBalance(new BigDecimal("1000"));
        when(vas.findByProgramIdAndAccountCategory(programId, AccountCategory.SETTLEMENT)).thenReturn(List.of(top));
        TransactionRepository txns = mock(TransactionRepository.class);
        when(txns.save(any(Transaction.class))).thenAnswer(i -> { Transaction t = i.getArgument(0); t.setId(UUID.randomUUID()); return t; });
        ReconciliationService reconciliation = new ReconciliationService(null, null, null, null,
            resolver, exceptions, vas, txns, mock(ProgramRepository.class));

        var first = reconciliation.processBankCharge(programId, UUID.randomUUID(), null,
            new BigDecimal("300"), "EUR", LocalDate.now(), "STMT-42", "Account maintenance");

        assertThat(first.isProcessed()).isTrue();
        assertThat(first.getSettlementVaId()).isEqualTo(top.getId());
        assertThat(top.getCurrentBalance()).isEqualByComparingTo("700");
        verify(exceptions, never()).save(any());

        when(txns.findByReferenceNumber("BANKCHG-STMT-42")).thenReturn(Optional.of(new Transaction()));
        reconciliation.processBankCharge(programId, UUID.randomUUID(), null,
            new BigDecimal("300"), "EUR", LocalDate.now(), "STMT-42", "Account maintenance");
        assertThat(top.getCurrentBalance()).isEqualByComparingTo("700");                   // not charged twice
    }
}
