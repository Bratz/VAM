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
import com.bank.vam.repository.ProgramRepository;
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

    private final TransactionService transactionService = new TransactionService(txnRepo, vaRepo,
        mock(com.bank.vam.repository.PhysicalAccountRepository.class), mock(LegalEntityRepository.class),
        mock(com.bank.vam.repository.tax.ChargeConfigurationRepository.class),
        mock(com.bank.vam.repository.payables.PayableRepository.class),
        mock(com.bank.vam.service.treasury.FeePostingService.class),
        mock(com.bank.vam.service.treasury.FundsAvailabilityService.class),
        mock(com.bank.vam.service.treasury.SettlementVaResolverService.class),
        mock(com.bank.vam.service.treasury.BalanceAggregationServiceEnhanced.class),
        new com.fasterxml.jackson.databind.ObjectMapper(),
        mock(com.bank.vam.repository.intercompany.IntercompanyTransactionRepository.class),
        new com.bank.vam.service.treasury.IhbMirrorService(vaRepo, txnRepo),
        mock(com.bank.vam.fx.deal.FxPaymentService.class));

    private final ReconciliationService reconciliation = new ReconciliationService(receivables, payments, unmatched,
        vibans, null, null, vaRepo, txnRepo, mock(ProgramRepository.class), transactionService);

    private final CoboReceivableService cobo = new CoboReceivableService(receivables, vaRepo, unmatched, reconciliation);

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
}
