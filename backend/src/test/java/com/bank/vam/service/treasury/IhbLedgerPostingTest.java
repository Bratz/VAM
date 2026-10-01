package com.bank.vam.service.treasury;

import com.bank.vam.dto.treasury.IhbDto;
import com.bank.vam.entity.Transaction;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.repository.PhysicalAccountRepository;
import com.bank.vam.repository.ProgramRepository;
import com.bank.vam.repository.TransactionRepository;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.hierarchy.HierarchyNodeRepository;
import com.bank.vam.repository.hierarchy.LegalEntityRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Every balance movement on an IHB account must leave a ledger row that explains it.
 *
 * <p>IhbUnifiedService moved money in six places and wrote no Transaction anywhere -- the balance
 * changed, a log line was emitted, and the statement could not account for it. The drift was
 * measurable on the deployed data: of the nine accounts carrying a non-zero balance, the only two
 * whose stored balance disagreed with their last {@code balanceAfter} were the two IHB current
 * accounts, each off by one day of interest, and the sign of each gap matched the sign of its
 * balance -- the overdrawn account had been charged 1.10, the one in credit paid 0.01.
 *
 * <p>So these tests assert the reconciliation property rather than the call: the row's
 * balanceBefore must be where the account started, its balanceAfter where the account ended, and
 * the two must differ by the amount. A row that merely exists is not enough.
 */
class IhbLedgerPostingTest {

    private final VirtualAccountRepository vas = mock(VirtualAccountRepository.class);
    private final TransactionRepository txns = mock(TransactionRepository.class);
    private final IhbUnifiedService service = new IhbUnifiedService(
        mock(LegalEntityRepository.class), vas, txns,
        mock(FeePostingService.class),
        mock(com.bank.vam.repository.treasury.SweepRuleRepository.class),
        mock(com.bank.vam.repository.credit.InterestConfigurationRepository.class),
        mock(com.bank.vam.service.tax.TaxService.class),
        new com.bank.vam.config.MarketProfileProperties(), mock(HierarchyNodeRepository.class),
        mock(ProgramRepository.class), mock(PhysicalAccountRepository.class));

    private VirtualAccount ihbAccount(String number, String cash) {
        VirtualAccount va = VirtualAccount.builder()
            .vaNumber(number)
            .currencyCode("AED")
            .corporateId(UUID.randomUUID())
            .accountCategory(VirtualAccount.AccountCategory.TRANSACTION)
            .ihbParticipant(true)
            .owningEntityId(UUID.randomUUID())   // isIhbCurrentAccount() = ihbParticipant + an owner
            .currentBalance(new BigDecimal(cash))
            .availableBalance(new BigDecimal(cash))
            .effectiveCreditLimit(new BigDecimal("1000000"))
            .build();
        va.setId(UUID.randomUUID());
        when(vas.findById(va.getId())).thenReturn(Optional.of(va));
        return va;
    }

    private List<Transaction> captureRows(int expected) {
        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        org.mockito.Mockito.verify(txns, org.mockito.Mockito.times(expected)).save(captor.capture());
        return captor.getAllValues();
    }

    /** The row must bridge the exact gap the balance moved -- not merely be present. */
    private void assertReconciles(Transaction row, String before, String after) {
        assertThat(row.getBalanceBefore()).isEqualByComparingTo(before);
        assertThat(row.getBalanceAfter()).isEqualByComparingTo(after);
        BigDecimal movement = row.getBalanceAfter().subtract(row.getBalanceBefore()).abs();
        assertThat(row.getAmount()).isEqualByComparingTo(movement);
        assertThat(row.getStatus()).isEqualTo(Transaction.TransactionStatus.COMPLETED);
    }

    private IhbDto.CurrentAccountTransactionRequest amount(String value) {
        IhbDto.CurrentAccountTransactionRequest r = new IhbDto.CurrentAccountTransactionRequest();
        r.setAmount(new BigDecimal(value));
        return r;
    }

    @Test
    void aDepositLeavesACreditRowThatExplainsTheBalance() {
        VirtualAccount va = ihbAccount("IHB-TEST-AED-0001", "100.00");
        when(vas.save(any())).thenAnswer(i -> i.getArgument(0));

        service.depositToCurrentAccount(va.getId(), amount("250.50"));

        Transaction row = captureRows(1).get(0);
        assertThat(row.getMovementType()).isEqualTo(Transaction.MovementType.CREDIT);
        assertReconciles(row, "100.00", "350.50");
        assertThat(va.getCurrentBalance()).isEqualByComparingTo("350.50");
    }

    @Test
    void aWithdrawalThatDrawsTheFacilityStillLeavesARowForTheNegativeBalance() {
        // This is the shape of the live account: cash goes negative against the facility, which is
        // legitimate, so the ledger has to carry it rather than quietly skipping the posting.
        VirtualAccount va = ihbAccount("IHB-MNC-UAE-AED-0001", "0.00");
        when(vas.save(any())).thenAnswer(i -> i.getArgument(0));

        service.withdrawFromCurrentAccount(va.getId(), amount("7212.00"));

        Transaction row = captureRows(1).get(0);
        assertThat(row.getMovementType()).isEqualTo(Transaction.MovementType.DEBIT);
        assertReconciles(row, "0.00", "-7212.00");
        assertThat(row.getDescription()).contains("facility");
    }

    @Test
    void aTransferWritesBothLegsUnderTheCorrelationIdTheCallerIsGiven() {
        VirtualAccount from = ihbAccount("IHB-A-AED", "5000.00");
        VirtualAccount to = ihbAccount("IHB-B-AED", "250.00");
        when(vas.save(any())).thenAnswer(i -> i.getArgument(0));

        IhbDto.CurrentAccountTransferRequest request = new IhbDto.CurrentAccountTransferRequest();
        request.setFromAccountId(from.getId());
        request.setToAccountId(to.getId());
        request.setAmount(new BigDecimal("1000.00"));

        IhbDto.CurrentAccountTransferResponse response = service.transferBetweenCurrentAccounts(request);

        List<Transaction> rows = captureRows(2);
        assertThat(rows).extracting(Transaction::getMovementType)
            .containsExactly(Transaction.MovementType.TRANSFER_OUT, Transaction.MovementType.TRANSFER_IN);
        assertReconciles(rows.get(0), "5000.00", "4000.00");
        assertReconciles(rows.get(1), "250.00", "1250.00");

        // The id the caller is handed must actually find the rows -- it used to match nothing.
        assertThat(response.getCorrelationId()).isNotBlank();
        assertThat(rows).allSatisfy(r ->
            assertThat(r.getCorrelationId()).isEqualTo(response.getCorrelationId()));
        assertThat(rows.get(0).getCounterpartyVaId()).isEqualTo(to.getId());
        assertThat(rows.get(1).getCounterpartyVaId()).isEqualTo(from.getId());
    }

    @Test
    void debitInterestOnAnOverdrawnAccountIsPostedAsOneRow() {
        // The live -1.10: an overdrawn participant owes debit interest, so the net is negative and
        // the balance moves down. The row carries the absolute amount; direction is in the balances.
        VirtualAccount va = ihbAccount("IHB-MNC-UAE-AED-0001", "-7212.00");
        va.accrueDebit(new BigDecimal("1.10"));
        when(vas.save(any())).thenAnswer(i -> i.getArgument(0));
        when(vas.findIhbParticipantsNeedingInterestPosting(any())).thenReturn(List.of(va));

        service.postInterestForCurrentAccounts();

        Transaction row = captureRows(1).get(0);
        assertThat(row.getMovementType()).isEqualTo(Transaction.MovementType.INTEREST);
        assertReconciles(row, "-7212.00", "-7213.10");
        assertThat(row.getAmount()).isEqualByComparingTo("1.10");
        assertThat(va.getAccruedDebitInterest()).isEqualByComparingTo("0");
    }

    @Test
    void creditInterestOnAnAccountInCreditMovesTheBalanceUp() {
        // The live +0.01, the same bug with the opposite sign.
        VirtualAccount va = ihbAccount("IHB-MNC-UK-AED-0002", "100.00");
        va.accrueCredit(new BigDecimal("0.01"));
        when(vas.save(any())).thenAnswer(i -> i.getArgument(0));
        when(vas.findIhbParticipantsNeedingInterestPosting(any())).thenReturn(List.of(va));

        service.postInterestForCurrentAccounts();

        Transaction row = captureRows(1).get(0);
        assertThat(row.getMovementType()).isEqualTo(Transaction.MovementType.INTEREST);
        assertReconciles(row, "100.00", "100.01");
    }

    @Test
    void anAccountWithNothingAccruedPostsNoRowAtAll() {
        VirtualAccount va = ihbAccount("IHB-QUIET-AED", "500.00");
        when(vas.save(any())).thenAnswer(i -> i.getArgument(0));
        when(vas.findIhbParticipantsNeedingInterestPosting(any())).thenReturn(List.of(va));

        service.postInterestForCurrentAccounts();

        org.mockito.Mockito.verify(txns, org.mockito.Mockito.never()).save(any());
        assertThat(va.getCurrentBalance()).isEqualByComparingTo("500.00");
    }
}
