package com.bank.vam.service.receivables;

import com.bank.vam.entity.Transaction;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.VirtualAccount.MirrorAccountType;
import com.bank.vam.entity.VirtualAccount.VaStatus;
import com.bank.vam.entity.receivables.Receivable;
import com.bank.vam.entity.receivables.Receivable.CoboRequestStatus;
import com.bank.vam.entity.receivables.Receivable.ReceivableStatus;
import com.bank.vam.entity.receivables.ReceivablePayment;
import com.bank.vam.entity.receivables.UnmatchedPayment;
import com.bank.vam.entity.viban.Viban;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.fx.deal.FxDeal;
import com.bank.vam.fx.deal.FxPaymentService;
import com.bank.vam.iso20022.dto.Iso20022PaymentDto.InwardPaymentRequest;
import com.bank.vam.iso20022.mapper.Iso20022MessageMapper;
import com.bank.vam.iso20022.service.Iso20022InwardPaymentService;
import com.bank.vam.repository.ProgramRepository;
import com.bank.vam.repository.tax.ChargeConfigurationRepository;
import com.bank.vam.service.treasury.FeePostingService;
import com.bank.vam.service.treasury.FxRateService;
import com.bank.vam.service.treasury.SettlementVaResolverService;
import com.bank.vam.repository.TransactionRepository;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.hierarchy.LegalEntityRepository;
import com.bank.vam.repository.receivables.ReceivablePaymentRepository;
import com.bank.vam.repository.receivables.ReceivableRepository;
import com.bank.vam.repository.receivables.UnmatchedPaymentRepository;
import com.bank.vam.repository.viban.VibanRepository;
import com.bank.vam.service.TransactionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * COBO phase 1: every payment reaches an invoice through ReconciliationService.applyPayment, which for a COBO
 * invoice paid on the collector's account credits the subsidiary in-house (IC payable +, IHB current +), gross,
 * once per bank transaction, without booking the cash again; and Collect can only apply an existing bank credit.
 */
class CoboPaymentApplicationTest {

    private static final UUID TREASURY = UUID.randomUUID();
    private static final UUID SUBSIDIARY = UUID.randomUUID();

    private final ReceivableRepository receivables = mock(ReceivableRepository.class);
    private final ReceivablePaymentRepository payments = mock(ReceivablePaymentRepository.class);
    private final UnmatchedPaymentRepository unmatched = mock(UnmatchedPaymentRepository.class);
    private final VibanRepository vibans = mock(VibanRepository.class);
    private final VirtualAccountRepository vaRepo = mock(VirtualAccountRepository.class);
    private final TransactionRepository txnRepo = mock(TransactionRepository.class);

    private final ChargeConfigurationRepository charges = mock(ChargeConfigurationRepository.class);
    private final FeePostingService feePosting = mock(FeePostingService.class);
    private final SettlementVaResolverService settlementResolver = mock(SettlementVaResolverService.class);
    private final FxPaymentService fx = mock(FxPaymentService.class);
    private final com.bank.vam.repository.receivables.InwardFxHoldRepository holds = mock(com.bank.vam.repository.receivables.InwardFxHoldRepository.class);

    private final TransactionService transactionService = new TransactionService(txnRepo, vaRepo,
        mock(com.bank.vam.repository.PhysicalAccountRepository.class), mock(LegalEntityRepository.class),
        charges,
        mock(com.bank.vam.repository.payables.PayableRepository.class),
        feePosting,
        mock(com.bank.vam.service.treasury.FundsAvailabilityService.class),
        settlementResolver,
        mock(com.bank.vam.service.treasury.BalanceAggregationServiceEnhanced.class),
        new com.fasterxml.jackson.databind.ObjectMapper(),
        mock(com.bank.vam.repository.intercompany.IntercompanyTransactionRepository.class),
        new com.bank.vam.service.treasury.IhbMirrorService(vaRepo, txnRepo),
        fx, mock(com.bank.vam.service.treasury.IhbUnifiedService.class));

    private final ReconciliationService reconciliation = new ReconciliationService(receivables, payments, unmatched,
        vibans, null, null, vaRepo, txnRepo, mock(ProgramRepository.class), transactionService);

    private final CoboReceivableService cobo = new CoboReceivableService(receivables, vaRepo, unmatched, reconciliation);

    private final Iso20022InwardPaymentService inward = new Iso20022InwardPaymentService(vibans, vaRepo, txnRepo,
        transactionService, mock(Iso20022MessageMapper.class), reconciliation, fx, mock(FxRateService.class), receivables, holds);

    private final Map<UUID, VirtualAccount> vas = new HashMap<>();
    private final List<Transaction> ledger = new ArrayList<>();
    private final List<UnmatchedPayment> unmatchedSaved = new ArrayList<>();

    private VirtualAccount treasuryCollection;
    private VirtualAccount subsidiaryOwn;
    private VirtualAccount ihbCurrent;
    private VirtualAccount icPayable;
    private Receivable invoice;
    private Viban viban;

    @BeforeEach
    void setUp() {
        treasuryCollection = va(TREASURY, null);
        subsidiaryOwn = va(SUBSIDIARY, null);
        icPayable = va(TREASURY, MirrorAccountType.IC_PAYABLE);
        ihbCurrent = va(SUBSIDIARY, MirrorAccountType.IHB_CURRENT);
        ihbCurrent.setIcPayableVaId(icPayable.getId());
        ihbCurrent.setOwningEntityCode("SUB-X");

        invoice = new Receivable();
        invoice.setId(UUID.randomUUID());
        invoice.setReceivableNumber("INV-0042");
        invoice.setCustomerName("Acme Trading");
        invoice.setCurrencyCode("AED");
        invoice.setNetAmount(new BigDecimal("1000"));
        invoice.setGrossAmount(new BigDecimal("1000"));
        invoice.setPaidAmount(BigDecimal.ZERO);
        invoice.setOutstandingAmount(new BigDecimal("1000"));
        invoice.setOwningEntityId(SUBSIDIARY);
        invoice.setOwningEntityCode("SUB-X");
        // COBO by enrolment, as ReceivablesService.createInvoice sets it
        invoice.setIsCobo(true);
        invoice.setCollectionRoute(Receivable.CollectionRoute.COBO);
        invoice.setCoboCollectorEntityId(TREASURY);
        invoice.setCoboCollectorEntityCode("TREASURY-T");
        invoice.setCoboCollectorEntityName("Treasury Centre");
        invoice.setCoboRequestStatus(CoboRequestStatus.APPROVED);
        invoice.setStatus(ReceivableStatus.OPEN);

        viban = new Viban();
        viban.setId(UUID.randomUUID());
        viban.setReferenceType("INVOICE");
        viban.setReferenceId(invoice.getId().toString());
        viban.setVirtualAccountId(treasuryCollection.getId());

        when(vaRepo.findById(any())).thenAnswer(i -> Optional.ofNullable(vas.get(i.<UUID>getArgument(0))));
        when(vaRepo.findByOwningEntityIdAndStatus(any(), any())).thenAnswer(i -> vas.values().stream()
            .filter(v -> v.getOwningEntityId().equals(i.getArgument(0)) && v.getStatus() == i.getArgument(1)).toList());
        when(vaRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(txnRepo.save(any())).thenAnswer(i -> {
            Transaction t = i.getArgument(0);
            if (t.getId() == null) t.setId(UUID.randomUUID());
            ledger.add(t);
            return t;
        });
        when(txnRepo.findById(any())).thenAnswer(i -> ledger.stream().filter(t -> t.getId().equals(i.getArgument(0))).findFirst());
        when(txnRepo.existsByCorrelationId(anyString())).thenAnswer(i -> ledger.stream().anyMatch(t -> i.getArgument(0).equals(t.getCorrelationId())));
        when(txnRepo.findAllByCorrelationId(anyString())).thenAnswer(i -> ledger.stream().filter(t -> i.getArgument(0).equals(t.getCorrelationId())).toList());
        when(receivables.findById(invoice.getId())).thenReturn(Optional.of(invoice));
        when(receivables.save(any())).thenAnswer(i -> i.getArgument(0));
        when(payments.save(any())).thenAnswer(i -> {
            ReceivablePayment p = i.getArgument(0);
            p.setId(UUID.randomUUID());
            return p;
        });
        when(unmatched.save(any())).thenAnswer(i -> {
            UnmatchedPayment u = i.getArgument(0);
            if (u.getId() == null) u.setId(UUID.randomUUID());
            unmatchedSaved.add(u);
            return u;
        });
        when(unmatched.findById(any())).thenAnswer(i -> unmatchedSaved.stream().filter(u -> u.getId().equals(i.getArgument(0))).findFirst());
        when(unmatched.findByVirtualAccountId(any())).thenAnswer(i -> unmatchedSaved.stream()
            .filter(u -> u.getVirtualAccountId().equals(i.getArgument(0))).toList());
        when(vibans.findById(viban.getId())).thenReturn(Optional.of(viban));
    }

    private VirtualAccount va(UUID owner, MirrorAccountType mirror) {
        VirtualAccount v = new VirtualAccount();
        v.setId(UUID.randomUUID());
        v.setVaNumber("VA-" + vas.size());
        v.setOwningEntityId(owner);
        v.setMirrorAccountType(mirror);
        v.setCurrencyCode("AED");
        v.setStatus(VaStatus.ACTIVE);
        vas.put(v.getId(), v);
        return v;
    }

    /** The cash credit the bank statement / pacs.008 already booked (TransactionService.processCollection). */
    private UUID cashArrivedOn(VirtualAccount account) {
        return txnRepo.save(Transaction.builder().vaId(account.getId()).amount(new BigDecimal("1000"))
            .correlationId("CASH-" + UUID.randomUUID()).build()).getId();
    }

    private List<Transaction> postedAfterCash() {
        return ledger.subList(1, ledger.size());
    }

    @Test
    void coboInvoicePaidByVibanCreditsTheSubsidiaryInHouseGrossWithoutBookingTheCashAgain() {
        UUID bankTxn = cashArrivedOn(treasuryCollection);

        var result = reconciliation.attemptAutoReconcile(bankTxn, viban.getId(), new BigDecimal("1000"), "AED",
            "Acme Trading", "AE001", "INV-0042");

        assertThat(result.isSuccess()).isTrue();
        verify(payments, times(1)).save(any(ReceivablePayment.class));
        assertThat(ihbCurrent.getCurrentBalance()).isEqualByComparingTo("1000");
        assertThat(icPayable.getCurrentBalance()).isEqualByComparingTo("1000");
        assertThat(treasuryCollection.getCurrentBalance()).isEqualByComparingTo("0");
        assertThat(postedAfterCash()).hasSize(2)
            .allSatisfy(t -> {
                assertThat(t.getVaId()).isIn(ihbCurrent.getId(), icPayable.getId());
                assertThat(t.getDescription()).contains("INV-0042").contains("Acme Trading");
                assertThat(t.getAmount()).isEqualByComparingTo("1000");
            });
        assertThat(invoice.getStatus()).isEqualTo(ReceivableStatus.PAID);
        assertThat(invoice.getCoboRequestStatus()).isEqualTo(CoboRequestStatus.COLLECTED);
    }

    @Test
    void onANegativePositionTheCreditLowersTreasurysIcReceivableInstead() {
        // POBO spec R10: the subsidiary owes treasury 1,500 (IHB current -1,500, IC receivable 1,500).
        VirtualAccount icReceivable = va(TREASURY, MirrorAccountType.IC_RECEIVABLE);
        ihbCurrent.setIcReceivableVaId(icReceivable.getId());
        ihbCurrent.setCurrentBalance(new BigDecimal("-1500"));
        icReceivable.setCurrentBalance(new BigDecimal("1500"));
        UUID bankTxn = cashArrivedOn(treasuryCollection);

        reconciliation.applyPayment(invoice, new BigDecimal("1000"), new BigDecimal("1000"), bankTxn, "AUTO");

        assertThat(ihbCurrent.getCurrentBalance()).isEqualByComparingTo("-500");
        assertThat(icReceivable.getCurrentBalance()).isEqualByComparingTo("500");
        assertThat(icPayable.getCurrentBalance()).isEqualByComparingTo("0");
        Transaction icr = postedAfterCash().stream().filter(t -> t.getVaId().equals(icReceivable.getId())).findFirst().orElseThrow();
        assertThat(icr.getMovementType()).isEqualTo(Transaction.MovementType.IC_SETTLEMENT);
        assertThat(icr.getAmount()).isEqualByComparingTo("1000");
        assertThat(icr.getDescription()).contains("INV-0042");
        assertThat(postedAfterCash()).hasSize(2);

        // A return puts the position, and the receivable, back.
        transactionService.reverseCoboInHouseCredit(bankTxn, "AM04");
        assertThat(ihbCurrent.getCurrentBalance()).isEqualByComparingTo("-1500");
        assertThat(icReceivable.getCurrentBalance()).isEqualByComparingTo("1500");
    }

    @Test
    void theSameBankTransactionIsCreditedInHouseOnce() {
        UUID bankTxn = cashArrivedOn(treasuryCollection);

        reconciliation.applyPayment(invoice, new BigDecimal("400"), new BigDecimal("400"), bankTxn, "AUTO");
        reconciliation.applyPayment(invoice, new BigDecimal("400"), new BigDecimal("400"), bankTxn, "AUTO");

        assertThat(postedAfterCash()).hasSize(2);
        assertThat(ihbCurrent.getCurrentBalance()).isEqualByComparingTo("400");
        assertThat(icPayable.getCurrentBalance()).isEqualByComparingTo("400");
    }

    @Test
    void aNonCoboInvoiceIsPaidAsBeforeWithNothingPostedInHouse() {
        invoice.rejectCobo("treasurer");
        invoice.setStatus(ReceivableStatus.OPEN);
        UUID bankTxn = cashArrivedOn(subsidiaryOwn);

        var result = reconciliation.attemptAutoReconcile(bankTxn, viban.getId(), new BigDecimal("1000"), "AED",
            "Acme Trading", "AE001", "INV-0042");

        assertThat(result.isSuccess()).isTrue();
        assertThat(postedAfterCash()).isEmpty();
        assertThat(ihbCurrent.getCurrentBalance()).isEqualByComparingTo("0");
        assertThat(invoice.getStatus()).isEqualTo(ReceivableStatus.PAID);
    }

    @Test
    void cashPaidToTheSubsidiarysOwnAccountIsAppliedWithoutAnInHouseCredit() {
        UUID bankTxn = cashArrivedOn(subsidiaryOwn);

        reconciliation.applyPayment(invoice, new BigDecimal("1000"), new BigDecimal("1000"), bankTxn, "AUTO");

        assertThat(postedAfterCash()).isEmpty();
        assertThat(invoice.getStatus()).isEqualTo(ReceivableStatus.PAID);
    }

    @Test
    void aSubsidiaryWithoutAnIhbCurrentAccountLeavesThePaymentUnmatchedOrRefused() {
        vas.remove(ihbCurrent.getId());
        UUID bankTxn = cashArrivedOn(treasuryCollection);

        var result = reconciliation.attemptAutoReconcile(bankTxn, viban.getId(), new BigDecimal("1000"), "AED",
            "Acme Trading", "AE001", "INV-0042");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getUnmatchedPaymentId()).isNotNull();
        assertThat(unmatchedSaved.get(0).getResolutionNotes()).contains("IHB current account");
        verify(payments, never()).save(any());
        assertThat(invoice.getOutstandingAmount()).isEqualByComparingTo("1000");
        assertThat(postedAfterCash()).isEmpty();

        // manual match of that credit: refused, nothing written
        assertThatThrownBy(() -> reconciliation.manualMatch(result.getUnmatchedPaymentId(), invoice.getId(), "ops"))
            .isInstanceOf(BusinessException.class).hasMessageContaining("IHB current account");
        verify(payments, never()).save(any());
    }

    @Test
    void collectWithoutABankCreditIsRefusedAndCreditsNothing() {
        var request = CoboReceivableService.CoboCollectionRequest.builder().receivableId(invoice.getId()).build();

        assertThatThrownBy(() -> cobo.executeCoboCollection(request))
            .isInstanceOf(BusinessException.class).hasMessage("Choose the bank credit that paid this invoice");
        assertThat(ledger).isEmpty();
        verify(vaRepo, never()).save(any());
        assertThat(invoice.getOutstandingAmount()).isEqualByComparingTo("1000");
    }

    @Test
    void collectAppliesAnUnmatchedCreditOnTheCollectorsAccount() {
        UUID bankTxn = cashArrivedOn(treasuryCollection);
        UnmatchedPayment credit = unmatched.save(UnmatchedPayment.fromTransaction(bankTxn, null, treasuryCollection.getId(),
            new BigDecimal("1000"), "AED", "Acme Trading", "AE001", "cheque 7781"));
        assertThat(cobo.collectCredits(invoice.getId())).extracting(CoboReceivableService.CollectCredit::id).containsExactly(credit.getId());

        var result = cobo.executeCoboCollection(CoboReceivableService.CoboCollectionRequest.builder()
            .receivableId(invoice.getId()).unmatchedPaymentId(credit.getId()).build());

        assertThat(result.getCoboFee()).isEqualByComparingTo("0");
        assertThat(result.getGrossAmount()).isEqualByComparingTo("1000");
        assertThat(result.getRechargeId()).isNull();
        assertThat(ihbCurrent.getCurrentBalance()).isEqualByComparingTo("1000");
        assertThat(icPayable.getCurrentBalance()).isEqualByComparingTo("1000");
        assertThat(postedAfterCash()).hasSize(2);
        assertThat(credit.isPending()).isFalse();
        assertThat(invoice.getStatus()).isEqualTo(ReceivableStatus.PAID);
    }

    @Test
    void aSettledCoboDirectDebitCreditsTheSubsidiaryOnceAndItsStatementLineNamesInvoiceCustomerAndCollector() {
        UUID settlement = cashArrivedOn(treasuryCollection); // CollectionSettlementService: processCollection on T's account

        // recordPayment(matchType DIRECT_DEBIT, transactionId = the settlement credit) -> applyPayment; a retry posts nothing
        reconciliation.applyPayment(invoice, new BigDecimal("1000"), new BigDecimal("1000"), settlement, "DIRECT_DEBIT");
        reconciliation.applyPayment(invoice, BigDecimal.ZERO, new BigDecimal("1000"), settlement, "DIRECT_DEBIT");

        assertThat(postedAfterCash()).hasSize(2).allSatisfy(t -> assertThat(t.getCorrelationId()).isEqualTo("COBO-" + settlement));
        assertThat(ihbCurrent.getCurrentBalance()).isEqualByComparingTo("1000");
        assertThat(icPayable.getCurrentBalance()).isEqualByComparingTo("1000");
        Transaction line = postedAfterCash().stream().filter(t -> t.getVaId().equals(ihbCurrent.getId())).findFirst().orElseThrow();
        assertThat(line.getDescription()).isEqualTo("COBO INV-0042 from Acme Trading, collected by Treasury Centre");
        assertThat(line.getExternalReference()).isEqualTo("INV-0042");
        assertThat(line.getRemitterName()).isEqualTo("Acme Trading");
        assertThat(line.isCredit()).isTrue();
    }

    @Test
    void aReturnedDebitReversesTheInHouseCreditOnce() {
        UUID settlement = cashArrivedOn(treasuryCollection);
        reconciliation.applyPayment(invoice, new BigDecimal("1000"), new BigDecimal("1000"), settlement, "DIRECT_DEBIT");

        Transaction debit = transactionService.reverseCoboInHouseCredit(settlement, "AM04 Insufficient funds");
        assertThat(transactionService.reverseCoboInHouseCredit(settlement, "AM04 Insufficient funds")).isNull();

        assertThat(ihbCurrent.getCurrentBalance()).isEqualByComparingTo("0");
        assertThat(icPayable.getCurrentBalance()).isEqualByComparingTo("0");
        assertThat(ledger.stream().filter(t -> ("COBO-REV-" + settlement).equals(t.getCorrelationId()))).hasSize(2);
        assertThat(debit.getVaId()).isEqualTo(ihbCurrent.getId());
        assertThat(debit.isDebit()).isTrue();
        assertThat(debit.getDescription()).contains("INV-0042").contains("AM04");
    }

    @Test
    void reversingABankCreditWithNoInHouseCreditPostsNothing() {
        UUID settlement = cashArrivedOn(subsidiaryOwn);

        assertThat(transactionService.reverseCoboInHouseCredit(settlement, "AM04")).isNull();
        assertThat(postedAfterCash()).isEmpty();
    }

    @Test
    void theInvoicesNamedIhbAccountIsCreditedNotTheOneFoundByCurrency() {
        VirtualAccount named = va(SUBSIDIARY, MirrorAccountType.IHB_CURRENT);
        named.setIcPayableVaId(icPayable.getId());
        invoice.setCoboIhbAccountId(named.getId());

        reconciliation.applyPayment(invoice, new BigDecimal("1000"), new BigDecimal("1000"), cashArrivedOn(treasuryCollection), "AUTO");

        assertThat(named.getCurrentBalance()).isEqualByComparingTo("1000");
        assertThat(ihbCurrent.getCurrentBalance()).isEqualByComparingTo("0");
    }

    // ---- ihb-fx Phase 3: X enrolled for EUR, settled on its AED IHB current account; T collects into AED ----

    /** The invoice in EUR settled on X's AED IHB account, its VIBAN in EUR on T's AED collection account (createInvoice). */
    private void crossCurrency() {
        invoice.setCurrencyCode("EUR");
        invoice.setCoboIhbAccountId(ihbCurrent.getId());
        viban.setCurrencyCode("EUR");
        viban.setExpectedAmount(new BigDecimal("1000"));
        viban.setStatus(Viban.STATUS_ACTIVE);
    }

    /** EUR sent, converted on arrival on {@code account} by T's FX (convertIncoming + settleIncoming's note). */
    private UUID eurArrivedOn(VirtualAccount account, String eur, String aed) {
        return txnRepo.save(Transaction.builder().vaId(account.getId()).amount(new BigDecimal(aed)).currencyCode("AED")
            .instructedAmount(new BigDecimal(eur)).instructedCurrency("EUR").fxRate(new BigDecimal("4.0123")).fxDealRef("FXD-9")
            .correlationId("CASH-" + UUID.randomUUID()).build()).getId();
    }

    private Transaction ihbLine() {
        return postedAfterCash().stream().filter(t -> t.getVaId().equals(ihbCurrent.getId())).findFirst().orElseThrow();
    }

    @Test
    void aEurPaymentPaysTheEurInvoiceAndCreditsTheSubsidiaryTheConvertedAedWithItsFx() {
        crossCurrency();
        UUID bankTxn = eurArrivedOn(treasuryCollection, "1000", "4012.30");

        var result = reconciliation.attemptAutoReconcile(bankTxn, viban.getId(), new BigDecimal("4012.30"), "AED",
            "Acme Trading", "DE001", "INV-0042");

        assertThat(result.isSuccess()).isTrue();
        var paymentRow = org.mockito.ArgumentCaptor.forClass(ReceivablePayment.class);
        verify(payments).save(paymentRow.capture());
        assertThat(paymentRow.getValue().getPaymentAmount()).isEqualByComparingTo("1000");
        assertThat(paymentRow.getValue().getCurrencyCode()).isEqualTo("EUR");
        assertThat(invoice.getStatus()).isEqualTo(ReceivableStatus.PAID);
        assertThat(invoice.getOutstandingAmount()).isEqualByComparingTo("0");
        assertThat(viban.getTotalAmountReceived()).isEqualByComparingTo("1000");
        assertThat(ihbCurrent.getCurrentBalance()).isEqualByComparingTo("4012.30");
        assertThat(icPayable.getCurrentBalance()).isEqualByComparingTo("4012.30");
        assertThat(postedAfterCash()).hasSize(2);
        Transaction line = ihbLine();
        assertThat(line.getAmount()).isEqualByComparingTo("4012.30");
        assertThat(line.getCurrencyCode()).isEqualTo("AED");
        assertThat(line.getInstructedAmount()).isEqualByComparingTo("1000");
        assertThat(line.getInstructedCurrency()).isEqualTo("EUR");
        assertThat(line.getFxRate()).isEqualByComparingTo("4.0123");
        assertThat(line.getFxDealRef()).isEqualTo("FXD-9");
        // ihb-fx 4.3: X's statement line reads the conversion
        assertThat(line.getDescription()).isEqualTo("EUR 1,000.00 from " + invoice.getCustomerName() + " for "
            + invoice.getReceivableNumber() + " · rate 4.0123 · AED 4,012.30");
    }

    @Test
    void aPartEurPaymentCreditsTheAedItBoughtAndLeavesTheRestOutstandingInEur() {
        crossCurrency();
        invoice.setAllowPartialPayment(true);

        reconciliation.attemptAutoReconcile(eurArrivedOn(treasuryCollection, "400", "1604.92"), viban.getId(),
            new BigDecimal("1604.92"), "AED", "Acme Trading", "DE001", "INV-0042");

        assertThat(invoice.getOutstandingAmount()).isEqualByComparingTo("600");
        assertThat(ihbCurrent.getCurrentBalance()).isEqualByComparingTo("1604.92");
        assertThat(ihbLine().getInstructedAmount()).isEqualByComparingTo("400");
    }

    @Test
    void anOverpaymentCreditsTheSubsidiaryTheAedOfTheAppliedPartProRataTheRestStaysWithTreasury() {
        crossCurrency();
        invoice.setAllowOverpayment(true);

        // EUR 1,100 converted to AED 4,413.57: X gets 4,413.57 x 1,000 / 1,100 = 4,012.3363... -> 4,012.34
        reconciliation.attemptAutoReconcile(eurArrivedOn(treasuryCollection, "1100", "4413.57"), viban.getId(),
            new BigDecimal("4413.57"), "AED", "Acme Trading", "DE001", "INV-0042");

        assertThat(invoice.getStatus()).isEqualTo(ReceivableStatus.PAID);
        assertThat(ihbCurrent.getCurrentBalance()).isEqualByComparingTo("4012.34");
        assertThat(ihbLine().getInstructedAmount()).isEqualByComparingTo("1000");
        var paymentRow = org.mockito.ArgumentCaptor.forClass(ReceivablePayment.class);
        verify(payments).save(paymentRow.capture());
        assertThat(paymentRow.getValue().getUnappliedAmount()).isEqualByComparingTo("100");
    }

    @Test
    void aSettledEurDirectDebitCreditsTheSubsidiaryTheConvertedAed() {
        crossCurrency();
        // CollectionSettlementService: convertIncoming on T's AED account, then recordPayment(EUR line amount)
        UUID settlement = eurArrivedOn(treasuryCollection, "1000", "4012.30");

        reconciliation.applyPayment(invoice, new BigDecimal("1000"), new BigDecimal("1000"), settlement, "DIRECT_DEBIT");

        assertThat(invoice.getStatus()).isEqualTo(ReceivableStatus.PAID);
        assertThat(ihbCurrent.getCurrentBalance()).isEqualByComparingTo("4012.30");
        assertThat(ihbLine().getFxDealRef()).isEqualTo("FXD-9");
    }

    @Test
    void collectAppliesAConvertedEurCreditAtItsEurAmountAndCreditsTheConvertedAed() {
        crossCurrency();
        UUID eurTxn = eurArrivedOn(treasuryCollection, "1000", "4012.30");
        UnmatchedPayment eurCredit = unmatched.save(UnmatchedPayment.fromTransaction(eurTxn, null, treasuryCollection.getId(),
            new BigDecimal("4012.30"), "AED", "Acme Trading", "DE001", "wrong reference"));
        unmatched.save(UnmatchedPayment.fromTransaction(cashArrivedOn(treasuryCollection), null, treasuryCollection.getId(),
            new BigDecimal("1000"), "AED", "Other Co", "AE009", "an AED credit"));

        assertThat(cobo.collectCredits(invoice.getId())).singleElement().satisfies(c -> {
            assertThat(c.id()).isEqualTo(eurCredit.getId());
            assertThat(c.amount()).isEqualByComparingTo("1000");
            assertThat(c.currencyCode()).isEqualTo("EUR");
        });

        cobo.executeCoboCollection(CoboReceivableService.CoboCollectionRequest.builder()
            .receivableId(invoice.getId()).unmatchedPaymentId(eurCredit.getId()).build());

        assertThat(invoice.getStatus()).isEqualTo(ReceivableStatus.PAID);
        assertThat(ihbCurrent.getCurrentBalance()).isEqualByComparingTo("4012.30");
        assertThat(eurCredit.isPending()).isFalse();
    }

    @Test
    void aCreditNotSentInTheInvoiceCurrencyCannotPayACrossCurrencyInvoice() {
        crossCurrency();
        UUID aedTxn = cashArrivedOn(treasuryCollection); // AED 1,000 paid as AED, nothing to say what EUR it is

        var result = reconciliation.attemptAutoReconcile(aedTxn, viban.getId(), new BigDecimal("1000"), "AED",
            "Acme Trading", "AE001", "INV-0042");

        assertThat(result.isSuccess()).isFalse();
        assertThat(unmatchedSaved.get(0).getResolutionNotes()).contains("only a credit sent in EUR and converted to AED");
        assertThat(unmatchedSaved.get(0).getAmount()).isEqualByComparingTo("1000");
        verify(payments, never()).save(any());
        assertThat(invoice.getPaidAmount()).isEqualByComparingTo("0");
        assertThat(postedAfterCash()).isEmpty();
    }

    /**
     * Account impact of the whole inward path (Iso20022InwardPaymentService -> processCollection -> settleIncoming ->
     * reconciliation): T's AED collection account and its bank account take the converted AED gross (the fee pair is
     * posted from T's account), X's AED IHB account and T's IC payable +converted, the settlement account passes it
     * through, no EUR account moves, the deal settles through settleIncoming on T's credit.
     */
    @Test
    void aEurCreditToTheVibanMovesOnlyTreasurysAedAccountsAndTheSubsidiarysIhbAccount() {
        crossCurrency();
        UUID bank = UUID.randomUUID();
        UUID program = UUID.randomUUID();
        VirtualAccount settlement = va(bank, null);
        VirtualAccount shadow = va(bank, null);
        shadow.setAccountCategory(VirtualAccount.AccountCategory.PHYSICAL_MIRROR);
        shadow.setLinkedPhysicalAccountId(UUID.randomUUID());
        VirtualAccount treasuryEur = va(TREASURY, null);
        treasuryEur.setCurrencyCode("EUR");
        vas.values().forEach(v -> v.setProgramId(program));
        viban.setViban("AE11VIBAN0042");
        when(vibans.findByViban("AE11VIBAN0042")).thenReturn(Optional.of(viban));
        when(vaRepo.findByProgramIdAndAccountCategory(program, VirtualAccount.AccountCategory.ROOT)).thenReturn(List.of());
        when(vaRepo.findByProgramIdAndAccountCategory(program, VirtualAccount.AccountCategory.PHYSICAL_MIRROR)).thenReturn(List.of(shadow));
        when(settlementResolver.resolveSettlementVaWithResult(any(), any(), any()))
            .thenReturn(SettlementVaResolverService.SettlementVaResolutionResult.success(settlement));
        com.bank.vam.entity.tax.ChargeConfiguration vibanFee = mock(com.bank.vam.entity.tax.ChargeConfiguration.class);
        when(vibanFee.calculateCharge(any())).thenReturn(new BigDecimal("5.00"));
        when(charges.findActiveByCode(any())).thenReturn(Optional.of(vibanFee));
        FxDeal deal = new FxDeal();
        deal.setDealRef("FXD-9");
        FxPaymentService.Conversion cv = new FxPaymentService.Conversion(new BigDecimal("1000"), "EUR",
            new BigDecimal("4012.30"), "AED", new BigDecimal("4.0123"), "EUR/AED",
            List.of(new FxPaymentService.Part(FxPaymentService.Source.LIVE, deal, new BigDecimal("1000"),
                new BigDecimal("1000"), new BigDecimal("4012.30"), new BigDecimal("4.0123"))));
        when(fx.convertIncoming(treasuryCollection, "EUR", new BigDecimal("1000"))).thenReturn(cv);
        doAnswer(i -> {
            FxPaymentService.note(cv, i.getArgument(1));
            return null;
        }).when(fx).settleIncoming(any(), any(), any());
        Map<VirtualAccount, BigDecimal> before = new HashMap<>();
        vas.values().forEach(v -> before.put(v, v.getCurrentBalance()));

        var response = inward.processInwardPayment(InwardPaymentRequest.builder().amount(new BigDecimal("1000"))
            .currency("EUR").creditorAccount("AE11VIBAN0042").debtorName("Acme Trading").debtorAccount("DE001")
            .remittanceInfo("INV-0042").build());

        assertThat(response.isSuccess()).as(response.getStatusReason()).isTrue();
        assertThat(response.isAutoReconciled()).isTrue();
        Map<String, String> moved = new TreeMap<>();
        vas.values().forEach(v -> {
            BigDecimal delta = v.getCurrentBalance().subtract(before.get(v));
            if (delta.signum() != 0) moved.put(v.getVaNumber(), delta.setScale(2).toPlainString());
        });
        assertThat(moved).isEqualTo(Map.of(
            treasuryCollection.getVaNumber(), "4012.30",
            shadow.getVaNumber(), "4012.30",
            ihbCurrent.getVaNumber(), "4012.30",
            icPayable.getVaNumber(), "4012.30"));
        assertThat(treasuryEur.getCurrentBalance()).isEqualByComparingTo("0");
        verify(feePosting).postFeePairToTarget(eq(treasuryCollection.getId()), eq(settlement.getId()),
            eq(new BigDecimal("5.00")), any(), any(), any(), any());
        verify(fx).settleIncoming(eq(cv), argThat(t -> treasuryCollection.getId().equals(t.getVaId())), eq(treasuryCollection));
        assertThat(invoice.getStatus()).isEqualTo(ReceivableStatus.PAID);
        // In EUR, the VIBAN's currency, by the inward service and again by the DIRECT match tier (a known double count).
        assertThat(viban.getTotalAmountReceived()).isEqualByComparingTo("2000");
        assertThat(ihbLine().getInstructedAmount()).isEqualByComparingTo("1000");
        assertThat(ihbLine().getFxDealRef()).isEqualTo("FXD-9");
    }

    // ---- ihb-fx 3a (D8): EUR 300,000 to the COBO VIBAN, above the EUR/AED streaming limit, T's deals leave all of it ----

    private static final String ABOVE = "Above 250,000 EUR in EUR/AED the desk prices on request";
    private static final BigDecimal EUR_300K = new BigDecimal("300000");
    private final List<com.bank.vam.entity.receivables.InwardFxHold> holdsSaved = new ArrayList<>();

    /** The whole inward path wired as in the account-impact test above, for a EUR 300,000 invoice; returns T's EUR account. */
    private VirtualAccount inwardPath() {
        crossCurrency();
        invoice.setNetAmount(EUR_300K);
        invoice.setGrossAmount(EUR_300K);
        invoice.setOutstandingAmount(EUR_300K);
        viban.setExpectedAmount(EUR_300K);
        UUID bank = UUID.randomUUID();
        UUID program = UUID.randomUUID();
        VirtualAccount settlement = va(bank, null);
        VirtualAccount shadow = va(bank, null);
        shadow.setAccountCategory(VirtualAccount.AccountCategory.PHYSICAL_MIRROR);
        shadow.setLinkedPhysicalAccountId(UUID.randomUUID());
        shadow.setVaNumber("SHADOW");
        VirtualAccount treasuryEur = va(TREASURY, null);
        treasuryEur.setCurrencyCode("EUR");
        vas.values().forEach(v -> v.setProgramId(program));
        viban.setViban("AE11VIBAN0042");
        when(vibans.findByViban("AE11VIBAN0042")).thenReturn(Optional.of(viban));
        when(vaRepo.findByProgramIdAndAccountCategory(program, VirtualAccount.AccountCategory.ROOT)).thenReturn(List.of());
        when(vaRepo.findByProgramIdAndAccountCategory(program, VirtualAccount.AccountCategory.PHYSICAL_MIRROR)).thenReturn(List.of(shadow));
        when(settlementResolver.resolveSettlementVaWithResult(any(), any(), any()))
            .thenReturn(SettlementVaResolverService.SettlementVaResolutionResult.success(settlement));
        com.bank.vam.entity.tax.ChargeConfiguration vibanFee = mock(com.bank.vam.entity.tax.ChargeConfiguration.class);
        when(vibanFee.calculateCharge(any())).thenReturn(new BigDecimal("5.00"));
        when(charges.findActiveByCode(any())).thenReturn(Optional.of(vibanFee));
        when(holds.save(any())).thenAnswer(i -> {
            com.bank.vam.entity.receivables.InwardFxHold h = i.getArgument(0);
            if (h.getId() == null) {
                h.setId(UUID.randomUUID());
                holdsSaved.add(h);
            }
            return h;
        });
        when(holds.findByVirtualAccountIdAndEndToEndId(any(), any())).thenAnswer(i -> holdsSaved.stream()
            .filter(h -> h.getVirtualAccountId().equals(i.getArgument(0)) && h.getEndToEndId().equals(i.getArgument(1))).toList());
        when(holds.lockById(any())).thenAnswer(i -> holdsSaved.stream().filter(h -> h.getId().equals(i.getArgument(0))).findFirst());
        return treasuryEur;
    }

    private InwardPaymentRequest eur300k() {
        return InwardPaymentRequest.builder().amount(EUR_300K).currency("EUR").creditorAccount("AE11VIBAN0042")
            .endToEndId("E2E-300K").messageId("MSG-300K").debtorName("Acme Trading").debtorAccount("DE001")
            .remittanceInfo("INV-0042").build();
    }

    private Map<VirtualAccount, BigDecimal> balances() {
        Map<VirtualAccount, BigDecimal> m = new HashMap<>();
        vas.values().forEach(v -> m.put(v, v.getCurrentBalance()));
        return m;
    }

    private Map<String, String> moved(Map<VirtualAccount, BigDecimal> before) {
        Map<String, String> m = new TreeMap<>();
        vas.values().forEach(v -> {
            BigDecimal delta = v.getCurrentBalance().subtract(before.get(v));
            if (delta.signum() != 0) m.put(v.getVaNumber(), delta.setScale(2).toPlainString());
        });
        return m;
    }

    @Test
    void aEurCreditAboveTheLiveLimitIsHeldPdngWithNothingPostedAndARedeliveryAnswersWithTheSameHold() {
        inwardPath();
        when(fx.needsQuote(treasuryCollection, "EUR", EUR_300K, com.bank.vam.fx.deal.Side.SELL)).thenReturn(ABOVE);
        Map<VirtualAccount, BigDecimal> before = balances();

        var first = inward.processInwardPayment(eur300k());

        assertThat(first.isSuccess()).as("accepted, never bounced").isTrue();
        assertThat(first.getStatusCode()).isEqualTo("PDNG");
        assertThat(first.getStatusReason()).contains(ABOVE);
        assertThat(first.getTransactionId()).isNull();
        assertThat(holdsSaved).singleElement().satisfies(h -> {
            assertThat(h.getId()).isEqualTo(first.getFxHoldId());
            assertThat(h.getStatus()).isEqualTo("AWAITING_FX");
            assertThat(h.getAmount()).isEqualByComparingTo("300000");
            assertThat(h.getCurrency()).isEqualTo("EUR");
            assertThat(h.getReceivableNumber()).isEqualTo("INV-0042");
            assertThat(h.getSubsidiaryCode()).isEqualTo("SUB-X");
            assertThat(h.getCollectorEntityId()).isEqualTo(TREASURY);
            assertThat(h.getSettleCurrency()).isEqualTo("AED");
            assertThat(h.getReason()).isEqualTo(ABOVE);
        });
        assertThat(ledger).isEmpty();
        assertThat(moved(before)).isEmpty();
        verify(fx, never()).convertIncoming(any(), any(), any());
        verify(fx, never()).convertIncoming(any(), any(), any(), any());
        assertThat(invoice.getStatus()).isEqualTo(ReceivableStatus.OPEN);

        var again = inward.processInwardPayment(eur300k());

        assertThat(again.getStatusCode()).isEqualTo("PDNG");
        assertThat(again.getFxHoldId()).isEqualTo(first.getFxHoldId());
        assertThat(holdsSaved).hasSize(1);
        assertThat(ledger).isEmpty();
    }

    /**
     * Account impact of the queued path: treasury's accept posts the held credit through the normal inward path at its
     * quote -- T's AED collection account and its bank account take the AED, X's AED IHB account and T's IC payable
     * the same, no EUR account moves; the invoice is paid in EUR; the hold is POSTED and cannot be posted twice.
     */
    @Test
    void treasurysAcceptPostsTheHeldCreditAtItsQuotePaysTheInvoiceAndCreditsTheSubsidiaryTheAedLeg() {
        VirtualAccount treasuryEur = inwardPath();
        when(fx.needsQuote(treasuryCollection, "EUR", EUR_300K, com.bank.vam.fx.deal.Side.SELL)).thenReturn(ABOVE);
        UUID holdId = inward.processInwardPayment(eur300k()).getFxHoldId();
        UUID quote = UUID.randomUUID();
        FxDeal deal = new FxDeal();
        deal.setDealRef("FXD-Q1");
        FxPaymentService.Conversion cv = new FxPaymentService.Conversion(EUR_300K, "EUR", new BigDecimal("1203690.00"), "AED",
            new BigDecimal("4.0123"), "EUR/AED", List.of(new FxPaymentService.Part(FxPaymentService.Source.FIRM, deal, EUR_300K,
                EUR_300K, new BigDecimal("1203690.00"), new BigDecimal("4.0123"))));
        when(fx.convertIncoming(treasuryCollection, "EUR", EUR_300K, quote)).thenReturn(cv);
        doAnswer(i -> {
            FxPaymentService.note(cv, i.getArgument(1));
            return null;
        }).when(fx).settleIncoming(any(), any(), any());
        Map<VirtualAccount, BigDecimal> before = balances();

        var posted = inward.postHeld(holdId, quote);

        assertThat(posted.getStatusCode()).isEqualTo("ACCP");
        assertThat(posted.isAutoReconciled()).isTrue();
        assertThat(moved(before)).isEqualTo(Map.of(
            treasuryCollection.getVaNumber(), "1203690.00",
            "SHADOW", "1203690.00",
            ihbCurrent.getVaNumber(), "1203690.00",
            icPayable.getVaNumber(), "1203690.00"));
        assertThat(treasuryEur.getCurrentBalance()).isEqualByComparingTo("0");
        verify(fx, never()).convertIncoming(any(), any(), any()); // never at the live price
        verify(fx).settleIncoming(eq(cv), argThat(t -> treasuryCollection.getId().equals(t.getVaId())), eq(treasuryCollection));
        assertThat(invoice.getStatus()).isEqualTo(ReceivableStatus.PAID);
        assertThat(ihbLine().getInstructedAmount()).isEqualByComparingTo("300000");
        assertThat(ihbLine().getFxRate()).isEqualByComparingTo("4.0123");
        assertThat(ihbLine().getFxDealRef()).isEqualTo("FXD-Q1");
        com.bank.vam.entity.receivables.InwardFxHold hold = holdsSaved.get(0);
        assertThat(hold.getStatus()).isEqualTo("POSTED");
        assertThat(hold.getTransactionId()).isEqualTo(posted.getTransactionId());
        assertThat(hold.getTransactionRef()).isEqualTo(posted.getTransactionReference());

        assertThatThrownBy(() -> inward.postHeld(holdId, quote)).hasMessageContaining("already posted");
    }

    @Test
    void aQuoteThatCannotBeUsedLeavesTheCreditHeldWithNothingPosted() {
        inwardPath();
        when(fx.needsQuote(treasuryCollection, "EUR", EUR_300K, com.bank.vam.fx.deal.Side.SELL)).thenReturn(ABOVE);
        UUID holdId = inward.processInwardPayment(eur300k()).getFxHoldId();
        UUID quote = UUID.randomUUID();
        when(fx.convertIncoming(treasuryCollection, "EUR", EUR_300K, quote))
            .thenThrow(new BusinessException("FX_PAYMENT_DECLINED", "The price is no longer available (EXPIRED); get a new one"));

        assertThatThrownBy(() -> inward.postHeld(holdId, quote)).hasMessageContaining("EXPIRED");
        assertThat(holdsSaved.get(0).getStatus()).isEqualTo("AWAITING_FX");
        assertThat(ledger).isEmpty();
    }

    /** 3a leftover: treasury will not convert it -- returned to the payer with the reason, nothing posted, never postable. */
    @Test
    void aHeldCreditTreasuryWillNotConvertIsReturnedToThePayerWithNothingPosted() {
        inwardPath();
        when(fx.needsQuote(treasuryCollection, "EUR", EUR_300K, com.bank.vam.fx.deal.Side.SELL)).thenReturn(ABOVE);
        UUID holdId = inward.processInwardPayment(eur300k()).getFxHoldId();
        Map<VirtualAccount, BigDecimal> before = balances();

        assertThatThrownBy(() -> inward.returnHeld(holdId, " ")).hasMessageContaining("why");
        var returned = inward.returnHeld(holdId, "Customer paid the wrong currency; agreed to resend in AED");

        assertThat(returned.getStatus()).isEqualTo("RETURNED");
        assertThat(returned.getReturnReason()).isEqualTo("Customer paid the wrong currency; agreed to resend in AED");
        assertThat(returned.getReturnedBy()).isNotBlank();
        assertThat(returned.getReturnedAt()).isNotNull();
        assertThat(moved(before)).isEmpty();
        assertThat(ledger).isEmpty();
        assertThat(invoice.getStatus()).isEqualTo(ReceivableStatus.OPEN);
        assertThatThrownBy(() -> inward.postHeld(holdId, UUID.randomUUID())).hasMessageContaining("already returned");
        assertThatThrownBy(() -> inward.returnHeld(holdId, "again")).hasMessageContaining("already returned");
        verify(fx, never()).convertIncoming(any(), any(), any(), any());
    }

    @Test
    void belowTheLimitTheCreditStillConvertsAutomaticallyAndNothingIsHeld() {
        inwardPath();
        FxPaymentService.Conversion cv = new FxPaymentService.Conversion(EUR_300K, "EUR", new BigDecimal("1203690.00"), "AED",
            new BigDecimal("4.0123"), "EUR/AED", List.of());
        when(fx.convertIncoming(treasuryCollection, "EUR", EUR_300K)).thenReturn(cv);
        doAnswer(i -> {
            FxPaymentService.note(cv, i.getArgument(1));
            return null;
        }).when(fx).settleIncoming(any(), any(), any());

        var response = inward.processInwardPayment(eur300k());

        assertThat(response.getStatusCode()).isEqualTo("ACCP");
        verify(fx).needsQuote(treasuryCollection, "EUR", EUR_300K, com.bank.vam.fx.deal.Side.SELL);
        verify(holds, never()).save(any());
        assertThat(invoice.getStatus()).isEqualTo(ReceivableStatus.PAID);
    }

    @Test
    void withoutAnFxSubLimitTheCreditIsRefusedNotHeld() {
        inwardPath();
        when(fx.needsQuote(treasuryCollection, "EUR", EUR_300K, com.bank.vam.fx.deal.Side.SELL))
            .thenThrow(new BusinessException("FX_LINE_REFUSED", "No FX sub-limit is allocated to Treasury Centre"));

        var response = inward.processInwardPayment(eur300k());

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getStatusReason()).isEqualTo("No FX sub-limit is allocated to Treasury Centre");
        verify(holds, never()).save(any());
        assertThat(ledger).isEmpty();
    }

    @Test
    void aCreditToAVibanThatIsNotCoboKeepsTodaysRefusal() {
        inwardPath();
        invoice.setIsCobo(false);
        when(fx.convertIncoming(treasuryCollection, "EUR", EUR_300K))
            .thenThrow(new BusinessException("FX_COLLECTION_NEEDS_DEAL", ABOVE + ", so EUR 300000 cannot be converted at the live price"));

        var response = inward.processInwardPayment(eur300k());

        assertThat(response.isSuccess()).isFalse();
        verify(fx, never()).needsQuote(any(), any(), any(), any());
        verify(holds, never()).save(any());
    }
}
