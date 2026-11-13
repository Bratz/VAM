package com.bank.vam.service;

import com.bank.vam.dto.TransactionDto;
import com.bank.vam.entity.Transaction;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.VirtualAccount.AccountCategory;
import com.bank.vam.repository.TransactionRepository;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.hierarchy.LegalEntityRepository;
import com.bank.vam.repository.tax.ChargeConfigurationRepository;
import com.bank.vam.service.treasury.FundsAvailabilityService;
import com.bank.vam.service.treasury.IhbMirrorService;
import com.bank.vam.service.treasury.SettlementVaResolverService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A POBO payment moves real money out of the group: the bank account (its shadow) goes down by the
 * amount, and nothing else should be left over. The settlement accounts are only stops on the way, so
 * they must end where they started -- Treasury Settlement is credited (leg 4) and pays the amount out,
 * each with its own ledger row (spec R9). Treasury's IC receivable / IC payable follow the subsidiary's
 * IHB current position (R10), whatever moved it: the POBO posting, a later collection, an old drift.
 */
class PoboLegsBalanceTest {

    private final Map<Class<?>, Object> deps = new HashMap<>();
    private final UUID programId = UUID.randomUUID();
    private final UUID corporateId = UUID.randomUUID();
    private final List<Transaction> ledger = new ArrayList<>();

    private TransactionService service;
    private VirtualAccount ihbCurrent, ihbSettlement, treasurySettlement, icReceivable, icPayable, shadow, collectionSettlement;
    private List<VirtualAccount> all;

    private VirtualAccount account(String number, AccountCategory category, String balance) {
        VirtualAccount va = VirtualAccount.builder()
            .vaNumber(number)
            .accountCategory(category)
            .programId(programId)
            .corporateId(corporateId)
            .currencyCode("EUR")
            .status(VirtualAccount.VaStatus.ACTIVE)
            .currentBalance(new BigDecimal(balance))
            .availableBalance(new BigDecimal(balance))
            .build();
        va.setId(UUID.randomUUID());
        return va;
    }

    @SuppressWarnings("unchecked")
    private <T> T dep(Class<T> type) {
        return (T) deps.get(type);
    }

    /** Every dependency mocked except the mirror sync, which is real over the mocked repositories. */
    private TransactionService serviceWithMocks() throws Exception {
        Constructor<?> constructor = TransactionService.class.getDeclaredConstructors()[0];
        Object[] args = new Object[constructor.getParameterCount()];
        Class<?>[] types = constructor.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            args[i] = types[i] == IhbMirrorService.class
                ? new IhbMirrorService(dep(VirtualAccountRepository.class), dep(TransactionRepository.class))
                : mock(types[i]);
            deps.put(types[i], args[i]);
        }
        constructor.setAccessible(true);
        return (TransactionService) constructor.newInstance(args);
    }

    @BeforeEach
    void setUp() throws Exception {
        service = serviceWithMocks();
        VirtualAccountRepository vas = dep(VirtualAccountRepository.class);
        TransactionRepository txns = dep(TransactionRepository.class);

        ihbCurrent = account("IHB-UK-EUR", AccountCategory.TRANSACTION, "0");
        ihbSettlement = account("IHBS-UK-EUR", AccountCategory.TRANSACTION, "0");
        treasurySettlement = account("TSETT-EUR", AccountCategory.SETTLEMENT, "0");
        icReceivable = account("IC-REC-UK-EUR", AccountCategory.TRANSACTION, "0");
        icPayable = account("IC-PAY-UK-EUR", AccountCategory.TRANSACTION, "0");
        collectionSettlement = account("SETT-EUR", AccountCategory.SETTLEMENT, "0");
        shadow = account("SHADOW-EUR", AccountCategory.PHYSICAL_MIRROR, "1000000");
        shadow.setLinkedPhysicalAccountId(UUID.randomUUID());
        shadow.setBankBalance(new BigDecimal("1000000"));

        ihbCurrent.setOwningEntityCode("MNC-UK");
        ihbCurrent.setIhbSettlementVaId(ihbSettlement.getId());
        ihbCurrent.setTreasuryPoolVaId(treasurySettlement.getId());
        ihbCurrent.setIcReceivableVaId(icReceivable.getId());
        ihbCurrent.setIcPayableVaId(icPayable.getId());

        all = List.of(ihbCurrent, ihbSettlement, treasurySettlement, icReceivable, icPayable, shadow, collectionSettlement);
        for (VirtualAccount va : all) {
            when(vas.findById(va.getId())).thenReturn(Optional.of(va));
        }
        when(vas.findByProgramIdAndAccountCategory(programId, AccountCategory.ROOT)).thenReturn(List.of());
        when(vas.findByProgramIdAndAccountCategory(programId, AccountCategory.PHYSICAL_MIRROR)).thenReturn(List.of(shadow));
        when(vas.save(any(VirtualAccount.class))).thenAnswer(i -> i.getArgument(0));
        when(txns.save(any(Transaction.class))).thenAnswer(i -> {
            Transaction t = i.getArgument(0);
            if (t.getId() == null) t.setId(UUID.randomUUID());
            ledger.add(t);
            return t;
        });
        when(dep(LegalEntityRepository.class).findById(any())).thenReturn(Optional.empty());
        when(dep(ChargeConfigurationRepository.class).findActiveByCode(any())).thenReturn(Optional.empty()); // no fee
        FundsAvailabilityService.FundsCheckResult approved = new FundsAvailabilityService.FundsCheckResult();
        approved.setApproved(true);
        when(dep(FundsAvailabilityService.class).checkFundsAvailability(any(), any(), any())).thenReturn(approved);
        when(dep(SettlementVaResolverService.class).resolveSettlementVaWithResult(any(), any(), any()))
            .thenReturn(SettlementVaResolverService.SettlementVaResolutionResult.success(collectionSettlement));
    }

    private void pobo(String amount) {
        TransactionDto.PoboPaymentRequest request = new TransactionDto.PoboPaymentRequest();
        request.setOwnerVaId(ihbCurrent.getId());
        request.setPayerVaId(treasurySettlement.getId());
        request.setAmount(new BigDecimal(amount));
        request.setBeneficiaryName("Supplier Ltd");
        service.makeIhb6LegPoboPayment(request);
    }

    private Map<VirtualAccount, BigDecimal> balances() {
        Map<VirtualAccount, BigDecimal> m = new HashMap<>();
        all.forEach(va -> m.put(va, va.getCurrentBalance()));
        return m;
    }

    /** Every balance change has its rows: per account, the rows' after - before add up to the change, and each row's amount is its own movement. */
    private void assertEveryMovementHasALedgerRow(Map<VirtualAccount, BigDecimal> before) {
        for (VirtualAccount va : all) {
            List<Transaction> rows = ledger.stream().filter(t -> va.getId().equals(t.getVaId())).toList();
            BigDecimal recorded = rows.stream().map(t -> t.getBalanceAfter().subtract(t.getBalanceBefore()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(recorded).as("ledger rows of %s", va.getVaNumber())
                .isEqualByComparingTo(va.getCurrentBalance().subtract(before.get(va)));
            rows.forEach(t -> assertThat(t.getBalanceAfter().subtract(t.getBalanceBefore()).abs())
                .as("row amount on %s", va.getVaNumber()).isEqualByComparingTo(t.getAmount()));
        }
    }

    private void assertMirrorsFollowThePosition() {
        BigDecimal p = ihbCurrent.getCurrentBalance();
        assertThat(icReceivable.getCurrentBalance()).as("IC receivable at position %s", p)
            .isEqualByComparingTo(p.negate().max(BigDecimal.ZERO));
        assertThat(icPayable.getCurrentBalance()).as("IC payable at position %s", p)
            .isEqualByComparingTo(p.max(BigDecimal.ZERO));
    }

    @Test
    void theSixLegsOfAPoboPaymentNetToTheMoneyThatLeftTheBankAndEachMovementHasARow() {
        BigDecimal amount = new BigDecimal("20000");
        Map<VirtualAccount, BigDecimal> before = balances();

        pobo("20000");

        List<String> moved = new ArrayList<>();
        BigDecimal everything = BigDecimal.ZERO;
        BigDecimal exceptShadow = BigDecimal.ZERO;
        for (VirtualAccount va : all) {
            BigDecimal delta = va.getCurrentBalance().subtract(before.get(va));
            everything = everything.add(delta);
            if (va.getAccountCategory() != AccountCategory.PHYSICAL_MIRROR) {
                exceptShadow = exceptShadow.add(delta);
            }
            if (delta.signum() != 0) moved.add(va.getVaNumber() + " " + delta.toPlainString());
        }

        // The shadow carries the real outflow; everything together must equal it.
        assertThat(everything).as("all accounts, moved: %s", moved).isEqualByComparingTo(amount.negate());
        // The virtual side is a redistribution among the group's own accounts: it nets to zero.
        assertThat(exceptShadow).as("virtual accounts only, moved: %s", moved).isEqualByComparingTo(BigDecimal.ZERO);
        // The intercompany pair mirrors one position.
        assertThat(ihbCurrent.getCurrentBalance()).isEqualByComparingTo("-20000");
        assertMirrorsFollowThePosition();
        // Settlement accounts are stops on the way, not destinations.
        assertThat(ihbSettlement.getCurrentBalance()).as("IHB settlement VA").isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(treasurySettlement.getCurrentBalance()).as("Treasury settlement VA").isEqualByComparingTo(BigDecimal.ZERO);
        // R9: including Treasury Settlement's payout.
        assertEveryMovementHasALedgerRow(before);
        assertThat(ledger).allSatisfy(t -> assertThat(t.getCorrelationId()).startsWith("IHBPOBO-"));
        Transaction icrRow = ledger.stream().filter(t -> t.getVaId().equals(icReceivable.getId())).findFirst().orElseThrow();
        assertThat(icrRow.getMovementType()).isEqualTo(Transaction.MovementType.IC_RECEIVABLE);
        assertThat(icrRow.getDescription()).contains("MNC-UK").contains("Supplier Ltd");
    }

    @Test
    void aCollectionCreditedAfterAPoboPaymentMovesTheMirrorsToThePosition() {
        Map<VirtualAccount, BigDecimal> before = balances();
        pobo("20000");

        TransactionDto.CollectionRequest collection = TransactionDto.CollectionRequest.builder()
            .targetVaId(ihbCurrent.getId()).amount(new BigDecimal("25000")).currencyCode("EUR").remitterName("Customer plc").build();
        service.processCollection(collection);

        assertThat(ihbCurrent.getCurrentBalance()).isEqualByComparingTo("5000");
        assertMirrorsFollowThePosition();   // IC receivable 20000 -> 0, IC payable 0 -> 5000
        assertEveryMovementHasALedgerRow(before);
        Transaction down = ledger.stream().filter(t -> t.getVaId().equals(icReceivable.getId())
            && t.getMovementType() == Transaction.MovementType.IC_SETTLEMENT).findFirst().orElseThrow();
        assertThat(down.getAmount()).isEqualByComparingTo("20000");
        assertThat(down.getDescription()).contains("Customer plc");
    }

    @Test
    void driftedMirrorsAreSetFromThePositionOnTheNextMovement() {
        // MNC-UK locally: IHB current +100 against an IC payable of 706.
        ihbCurrent.setCurrentBalance(new BigDecimal("100"));
        icPayable.setCurrentBalance(new BigDecimal("706"));
        Map<VirtualAccount, BigDecimal> before = balances();

        pobo("300");

        assertThat(ihbCurrent.getCurrentBalance()).isEqualByComparingTo("-200");
        assertMirrorsFollowThePosition();   // IC receivable 200, IC payable 0
        assertEveryMovementHasALedgerRow(before);
    }

    @Test
    void aPoboPaymentCreatesNoRecharge() {
        pobo("20000");

        // TransactionService no longer has a recharge repository at all: the IHB current account's ledger rows are the
        // record (the legacy intercompany_transactions copies went in Phase 3, spec D3).
        assertThat(TransactionService.class.getDeclaredConstructors()[0].getParameterTypes())
            .doesNotContain(com.bank.vam.repository.pobo.IntercompanyRechargeRepository.class);
    }

    @Test
    void theEnrolmentsPaymentAccountPaysWhenTheRequestNamesItNotTheAccountsTreasuryLink() {
        VirtualAccount paymentAccount = account("TC-PAY-EUR", AccountCategory.TRANSACTION, "0");
        when(dep(VirtualAccountRepository.class).findById(paymentAccount.getId())).thenReturn(Optional.of(paymentAccount));

        TransactionDto.PoboPaymentRequest request = new TransactionDto.PoboPaymentRequest();
        request.setOwnerVaId(ihbCurrent.getId());
        request.setPayerVaId(paymentAccount.getId());
        request.setAmount(new BigDecimal("300"));
        request.setBeneficiaryName("Supplier Ltd");
        service.makeIhb6LegPoboPayment(request);

        List<Transaction> payerRows = ledger.stream().filter(t -> paymentAccount.getId().equals(t.getVaId())).toList();
        assertThat(payerRows).hasSize(2);                                       // credited, then pays out
        assertThat(paymentAccount.getCurrentBalance()).isEqualByComparingTo("0");
        assertThat(ledger).noneMatch(t -> treasurySettlement.getId().equals(t.getVaId()));
        assertThat(shadow.getCurrentBalance()).isEqualByComparingTo("999700");
    }

    @Test
    void noPoboFeeIsTakenEvenWhenOneIsConfigured() {
        com.bank.vam.entity.tax.ChargeConfiguration fee = mock(com.bank.vam.entity.tax.ChargeConfiguration.class);
        when(fee.calculateCharge(any())).thenReturn(new BigDecimal("25"));
        when(dep(ChargeConfigurationRepository.class).findActiveByCode("POBO_FEE")).thenReturn(Optional.of(fee));

        pobo("1000");

        assertThat(ihbCurrent.getCurrentBalance()).isEqualByComparingTo("-1000");
        assertThat(ledger).noneMatch(t -> t.getMovementType() == Transaction.MovementType.FEE);
        assertThat(ledger).allSatisfy(t -> assertThat(t.getFeeAmount() == null || t.getFeeAmount().signum() == 0).isTrue());
    }

    @Test
    void aPaymentOverTheIhbCreditHeadroomIsRefusedBeforeAnyMoneyMoves() {
        ihbCurrent.setIhbParticipant(true);
        ihbCurrent.setCurrentBalance(new BigDecimal("-200"));
        ihbCurrent.setEffectiveCreditLimit(new BigDecimal("1000"));
        Map<VirtualAccount, BigDecimal> before = balances();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> pobo("800.01"))
            .isInstanceOf(com.bank.vam.exception.BusinessException.class)
            .hasMessageContaining("over MNC-UK's IHB credit headroom of 800");

        assertThat(ledger).isEmpty();
        assertThat(balances()).isEqualTo(before);
        pobo("800");                                                            // exactly the headroom is fine
        assertThat(ihbCurrent.getCurrentBalance()).isEqualByComparingTo("-1000");
    }

    /**
     * ihb-fx Phase 2 (D3, D4, R7): a cross-currency POBO posts the conversion's AED debit; the treasury's cover settles
     * through its own payout row, and the subsidiary's IHB movement carries the instructed EUR, rate and deal.
     */
    @Test
    void aCrossCurrencyPoboDebitsEveryAccountTheConvertedAmountAndSettlesTheCoverThroughTheTreasurysPayout() {
        for (VirtualAccount va : all) va.setCurrencyCode("AED");
        com.bank.vam.fx.deal.FxDeal deal = new com.bank.vam.fx.deal.FxDeal();
        deal.setDealRef("FXD-7");
        com.bank.vam.fx.deal.FxPaymentService.Conversion cv = new com.bank.vam.fx.deal.FxPaymentService.Conversion(
            new BigDecimal("1000"), "EUR", new BigDecimal("4012.30"), "AED", new BigDecimal("4.0123"), "EUR/AED",
            List.of(new com.bank.vam.fx.deal.FxPaymentService.Part(com.bank.vam.fx.deal.FxPaymentService.Source.LIVE, deal,
                new BigDecimal("1000"), new BigDecimal("1000"), new BigDecimal("4012.30"), new BigDecimal("4.0123"))));
        when(dep(TransactionRepository.class).findAllByCorrelationId(any()))
            .thenAnswer(i -> ledger.stream().filter(t -> i.getArgument(0).equals(t.getCorrelationId())).toList());
        Map<VirtualAccount, BigDecimal> before = balances();

        TransactionDto.PoboPaymentRequest request = new TransactionDto.PoboPaymentRequest();
        request.setOwnerVaId(ihbCurrent.getId());
        request.setPayerVaId(treasurySettlement.getId());
        request.setAmount(cv.debitAmount());
        request.setBeneficiaryName("Supplier GmbH");
        Transaction ownerDebit = service.makeIhb6LegPoboPayment(request);
        service.settlePoboFx(cv, ownerDebit, treasurySettlement);

        Transaction payout = ledger.stream().filter(t -> t.getVaId().equals(treasurySettlement.getId())
            && t.getMovementType() == Transaction.MovementType.POBO_DEBIT).findFirst().orElseThrow();
        org.mockito.Mockito.verify(dep(com.bank.vam.fx.deal.FxPaymentService.class)).settle(cv, payout, treasurySettlement);
        assertThat(ownerDebit.getVaId()).isEqualTo(ihbCurrent.getId());
        assertThat(ownerDebit.getAmount()).as("X's AED debit").isEqualByComparingTo(cv.debitAmount());
        assertThat(payout.getAmount()).as("T's AED cash out").isEqualByComparingTo(cv.debitAmount());
        assertThat(ihbCurrent.getCurrentBalance().subtract(before.get(ihbCurrent))).isEqualByComparingTo("-4012.30");
        assertThat(shadow.getCurrentBalance().subtract(before.get(shadow))).isEqualByComparingTo("-4012.30");
        assertThat(treasurySettlement.getCurrentBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(ownerDebit.getInstructedAmount()).isEqualByComparingTo("1000");
        assertThat(ownerDebit.getInstructedCurrency()).isEqualTo("EUR");
        assertThat(ownerDebit.getFxRate()).isEqualByComparingTo("4.0123");
        assertThat(ownerDebit.getFxDealRef()).isEqualTo("FXD-7");
        // ihb-fx 4.3: X's statement line reads the conversion
        assertThat(ownerDebit.getDescription()).isEqualTo("EUR 1,000.00 to Supplier GmbH · rate 4.0123 · AED 4,012.30");
        assertMirrorsFollowThePosition();
        assertEveryMovementHasALedgerRow(before);
    }
}
