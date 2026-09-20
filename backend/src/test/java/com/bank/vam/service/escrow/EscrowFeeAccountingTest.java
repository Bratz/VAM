package com.bank.vam.service.escrow;

import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.escrow.EscrowContract;
import com.bank.vam.entity.escrow.EscrowContract.EscrowStatus;
import com.bank.vam.repository.TransactionRepository;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.escrow.EscrowContractRepository;
import com.bank.vam.service.treasury.FeePostingService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression guard for the escrow fee double-charge.
 *
 * Fees are drawn from the escrow VA by {@link FeePostingService#postFee}, which
 * debits the source VA and credits the settlement VA. Funding also withheld the
 * fee from the amount it credited, and release debited amount + fee before
 * posting the fee on top — so every fee left the account twice. The contract's
 * currentBalance meanwhile only moved by the principal, drifting above the VA it
 * mirrors by the accumulated fees, which made getRemainingForRelease() promise
 * money that was no longer there.
 */
class EscrowFeeAccountingTest {

    private static final UUID VA_ID = UUID.randomUUID();

    private VirtualAccount va(BigDecimal balance) {
        VirtualAccount va = new VirtualAccount();
        va.setId(VA_ID);
        va.setCurrentBalance(balance);
        va.setAvailableBalance(balance);
        return va;
    }

    private EscrowContract contract(BigDecimal amount, BigDecimal setupFee, EscrowStatus status) {
        EscrowContract c = EscrowContract.builder()
            .escrowReference("ESC-TR-2026-00001")
            .contractAmount(amount)
            .setupFee(setupFee)
            .setupFeeCharged(false)
            .fundedAmount(BigDecimal.ZERO)
            .releasedAmount(BigDecimal.ZERO)
            .currentBalance(BigDecimal.ZERO)
            .totalFeesCharged(BigDecimal.ZERO)
            .currencyCode("GBP")
            .status(status)
            .build();
        c.setId(UUID.randomUUID());
        return c;
    }

    /** Builds a service whose fee posting really moves money out of the VA, as the live one does. */
    private EscrowService serviceWith(EscrowContract escrow, VirtualAccount escrowVa) {
        EscrowContractRepository escrowRepo = mock(EscrowContractRepository.class);
        VirtualAccountRepository vaRepo = mock(VirtualAccountRepository.class);
        TransactionRepository txnRepo = mock(TransactionRepository.class);
        FeePostingService fees = mock(FeePostingService.class);

        escrow.setEscrowVaId(VA_ID);
        when(escrowRepo.findById(escrow.getId())).thenReturn(Optional.of(escrow));
        when(escrowRepo.save(any(EscrowContract.class))).thenAnswer(i -> i.getArgument(0));
        when(vaRepo.findById(VA_ID)).thenReturn(Optional.of(escrowVa));
        when(vaRepo.save(any(VirtualAccount.class))).thenAnswer(i -> i.getArgument(0));
        when(txnRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        when(fees.postFee(eq(VA_ID), any(BigDecimal.class), anyString(), any(), anyString()))
            .thenAnswer(i -> {
                BigDecimal fee = i.getArgument(1);
                escrowVa.setCurrentBalance(escrowVa.getCurrentBalance().subtract(fee));
                return FeePostingService.FeePostingResult.builder().status("POSTED").amount(fee).build();
            });

        return new EscrowService(escrowRepo, vaRepo, txnRepo, fees, null, null);
    }

    @Test
    void fundingTakesTheSetupFeeExactlyOnceAndKeepsContractBalanceOnTheVa() {
        EscrowContract escrow = contract(new BigDecimal("250000"), new BigDecimal("500"), EscrowStatus.PENDING_FUNDING);
        VirtualAccount escrowVa = va(BigDecimal.ZERO);
        EscrowService service = serviceWith(escrow, escrowVa);

        service.fundEscrow(escrow.getId(), EscrowService.FundEscrowRequest.builder()
            .amount(new BigDecimal("150000"))
            .funderName("Buyer")
            .build());

        // 150,000 in, one 500 fee out.
        assertThat(escrowVa.getCurrentBalance()).isEqualByComparingTo("149500");
        assertThat(escrow.getTotalFeesCharged()).isEqualByComparingTo("500");
        assertThat(escrow.getSetupFeeCharged()).isTrue();
        // The contract must mirror the account, not sit 500 above it.
        assertThat(escrow.getCurrentBalance()).isEqualByComparingTo(escrowVa.getCurrentBalance());
    }

    @Test
    void releaseTakesTheReleaseFeeExactlyOnceAndKeepsContractBalanceOnTheVa() {
        EscrowContract escrow = contract(new BigDecimal("250000"), BigDecimal.ZERO, EscrowStatus.FUNDED);
        escrow.setSetupFeeCharged(true);
        escrow.setCurrentBalance(new BigDecimal("100000"));
        escrow.setFundedAmount(new BigDecimal("100000"));
        VirtualAccount escrowVa = va(new BigDecimal("100000"));
        EscrowService service = serviceWith(escrow, escrowVa);

        service.releaseFunds(escrow.getId(), EscrowService.ReleaseEscrowRequest.builder()
            .amount(new BigDecimal("40000"))
            .releaseTo("Seller")
            .approvedBy("tester")
            .build());

        // 40,000 out to the seller plus one 0.1% = 40.00 fee.
        assertThat(escrowVa.getCurrentBalance()).isEqualByComparingTo("59960");
        assertThat(escrow.getReleasedAmount()).isEqualByComparingTo("40000");
        assertThat(escrow.getTotalFeesCharged()).isEqualByComparingTo("40");
        assertThat(escrow.getCurrentBalance()).isEqualByComparingTo(escrowVa.getCurrentBalance());
    }

    @Test
    void aFeeThatFailsToPostIsNotChargedToTheContract() {
        EscrowContract escrow = contract(new BigDecimal("250000"), new BigDecimal("500"), EscrowStatus.PENDING_FUNDING);
        VirtualAccount escrowVa = va(BigDecimal.ZERO);

        EscrowContractRepository escrowRepo = mock(EscrowContractRepository.class);
        VirtualAccountRepository vaRepo = mock(VirtualAccountRepository.class);
        TransactionRepository txnRepo = mock(TransactionRepository.class);
        FeePostingService fees = mock(FeePostingService.class);
        escrow.setEscrowVaId(VA_ID);
        when(escrowRepo.findById(escrow.getId())).thenReturn(Optional.of(escrow));
        when(escrowRepo.save(any(EscrowContract.class))).thenAnswer(i -> i.getArgument(0));
        when(vaRepo.findById(VA_ID)).thenReturn(Optional.of(escrowVa));
        when(vaRepo.save(any(VirtualAccount.class))).thenAnswer(i -> i.getArgument(0));
        when(txnRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(fees.postFee(eq(VA_ID), any(BigDecimal.class), anyString(), any(), anyString()))
            .thenReturn(FeePostingService.FeePostingResult.failed(escrow.getId(), "no settlement VA"));

        new EscrowService(escrowRepo, vaRepo, txnRepo, fees, null, null)
            .fundEscrow(escrow.getId(), EscrowService.FundEscrowRequest.builder()
                .amount(new BigDecimal("150000"))
                .build());

        // The fee stayed in the account, so the contract still holds all of it
        // and the setup fee remains outstanding.
        assertThat(escrowVa.getCurrentBalance()).isEqualByComparingTo("150000");
        assertThat(escrow.getCurrentBalance()).isEqualByComparingTo("150000");
        assertThat(escrow.getTotalFeesCharged()).isEqualByComparingTo("0");
        assertThat(escrow.getSetupFeeCharged()).isFalse();
    }
}
