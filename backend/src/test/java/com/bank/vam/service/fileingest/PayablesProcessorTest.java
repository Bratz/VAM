package com.bank.vam.service.fileingest;

import com.bank.vam.config.MarketProfileProperties;
import com.bank.vam.dto.TransactionDto;
import com.bank.vam.entity.Transaction;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.fileingest.RowStatus;
import com.bank.vam.entity.fileingest.StagedTransaction;
import com.bank.vam.entity.hierarchy.LegalEntity;
import com.bank.vam.entity.payables.Payable;
import com.bank.vam.fx.deal.FxPaymentService;
import com.bank.vam.repository.TransactionRepository;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.fileingest.StagedTransactionRepository;
import com.bank.vam.repository.hierarchy.LegalEntityRepository;
import com.bank.vam.repository.party.PartyRepository;
import com.bank.vam.repository.payables.PayableRepository;
import com.bank.vam.repository.treasury.PaymentRequestRepository;
import com.bank.vam.service.TransactionService;
import com.bank.vam.service.VirtualAccountService;
import com.bank.vam.service.payables.PayablesService;
import com.bank.vam.service.pobo.PoboEnrolmentService;
import com.bank.vam.service.treasury.NettingService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * An ingested payables row pays (spec R15): the processor used to ask for channel "BANK_TRANSFER" (a payment method)
 * and method "WIRE" (not one), so executePayment's valueOf threw and every row failed.
 */
class PayablesProcessorTest {

    private final PayableRepository payables = mock(PayableRepository.class);
    private final VirtualAccountRepository vas = mock(VirtualAccountRepository.class);
    private final TransactionService transactions = mock(TransactionService.class);
    private final MarketProfileProperties market = mock(MarketProfileProperties.class);
    private final LegalEntityRepository entities = mock(LegalEntityRepository.class);
    private final PoboEnrolmentService enrolments = mock(PoboEnrolmentService.class);
    private final PaymentRequestRepository paymentRequests = mock(PaymentRequestRepository.class);
    private final com.bank.vam.repository.party.PartyBankAccountRepository partyBankAccounts =
        mock(com.bank.vam.repository.party.PartyBankAccountRepository.class);
    private final PayablesService payablesService = new PayablesService(payables, mock(PartyRepository.class), vas,
        mock(TransactionRepository.class), paymentRequests, transactions, market,
        mock(NettingService.class), entities, enrolments, mock(FxPaymentService.class),
        mock(com.bank.vam.repository.receivables.ReceivableRepository.class), mock(com.bank.vam.service.treasury.FxRateService.class), partyBankAccounts);

    private final StagedTransactionRepository staging = mock(StagedTransactionRepository.class);
    private final VirtualAccountService accounts = mock(VirtualAccountService.class);
    private final IngestFx ingestFx = mock(IngestFx.class);
    private final PayablesProcessor processor = new PayablesProcessor(staging, accounts, payablesService, ingestFx);

    @Test
    void anIngestedRowIsCreatedApprovedAndPaid() {
        UUID job = UUID.randomUUID();
        LegalEntity owner = new LegalEntity();
        owner.setId(UUID.randomUUID());
        owner.setEntityName("SUB-X LLC");
        when(entities.findById(owner.getId())).thenReturn(Optional.of(owner));
        VirtualAccount source = VirtualAccount.builder().vaNumber("VA-1").viban("AE000000000000000000077")
            .currencyCode("AED").corporateId(UUID.randomUUID()).owningEntityId(owner.getId())
            .currentBalance(new BigDecimal("5000")).availableBalance(new BigDecimal("5000")).build();
        source.setId(UUID.randomUUID());
        when(accounts.getByViban("AE000000000000000000077")).thenReturn(source);
        when(vas.findById(source.getId())).thenReturn(Optional.of(source));
        when(enrolments.debtorIban(source)).thenReturn("AE000000000000000000077");
        when(market.getActiveHomeBankBic()).thenReturn("HBUKGB4BXXX");

        Map<UUID, Payable> stored = new HashMap<>();
        when(payables.save(any())).thenAnswer(i -> {
            Payable p = i.getArgument(0);
            if (p.getId() == null) p.setId(UUID.randomUUID());
            stored.put(p.getId(), p);
            return p;
        });
        when(payables.findById(any())).thenAnswer(i -> Optional.ofNullable(stored.get(i.<UUID>getArgument(0))));
        when(paymentRequests.save(any())).thenAnswer(i -> i.getArgument(0));
        Transaction posted = Transaction.builder().referenceNumber("PAY-TXN-1").build();
        posted.setId(UUID.randomUUID());
        when(transactions.makePayment(any())).thenReturn(posted);

        StagedTransaction staged = new StagedTransaction();
        staged.setSourceRowNumber(1);
        staged.setStatus(RowStatus.READY);
        when(staging.findByIngestJobIdAndStatus(job, RowStatus.READY)).thenReturn(List.of(staged));
        when(ingestFx.of(any(), any())).thenReturn(new FxPaymentService.BatchFx(
            new FxPaymentService.Instruction(null, null, List.of(), false), null));

        processor.process(job, List.of(new TransformedRow(1, new BigDecimal("250"), "AED", "AE000000000000000000077",
            null, null, "Supplier Ltd", "GB00SUPP", "August rent", "INV-500")));

        assertThat(staged.getReason()).isNull();
        assertThat(staged.getStatus()).isEqualTo(RowStatus.PROCESSED);
        Payable paid = stored.get(staged.getProcessedEntityId());
        assertThat(paid.getStatus()).isEqualTo(Payable.PayableStatus.PAID);
        assertThat(paid.getPaymentChannel()).isEqualTo(Payable.PaymentChannel.BATCH);
        assertThat(paid.getPaymentMethod()).isEqualTo(Payable.PaymentMethod.BANK_TRANSFER);
        ArgumentCaptor<TransactionDto.PaymentRequest> payment = ArgumentCaptor.forClass(TransactionDto.PaymentRequest.class);
        verify(transactions).makePayment(payment.capture());
        assertThat(payment.getValue().getFromVaId()).isEqualTo(source.getId());
        assertThat(payment.getValue().getAmount()).isEqualByComparingTo("250");
    }

    /**
     * ihb-fx 3a leftover: a cross-currency POBO line the file's FX cannot cover becomes an approved payable flagged
     * Awaiting FX (treasury's POBO tab), not a held row; a line of anyone else is still held for release.
     */
    @Test
    void aPoboLineAboveTheLiveLimitBecomesAPayableAwaitingTreasurysRateNotAHeldRow() {
        UUID job = UUID.randomUUID();
        LegalEntity sub = new LegalEntity();
        sub.setId(UUID.randomUUID());
        sub.setEntityName("SUB-X LLC");
        LegalEntity treasury = new LegalEntity();
        treasury.setId(UUID.randomUUID());
        treasury.setEntityCode("TC-1");
        when(entities.findById(sub.getId())).thenReturn(Optional.of(sub));
        VirtualAccount source = VirtualAccount.builder().vaNumber("VA-1").viban("AE000000000000000000077")
            .currencyCode("AED").corporateId(UUID.randomUUID()).owningEntityId(sub.getId()).build();
        source.setId(UUID.randomUUID());
        VirtualAccount ihbCurrent = VirtualAccount.builder().vaNumber("IHB-AED").currencyCode("AED").build();
        ihbCurrent.setId(UUID.randomUUID());
        VirtualAccount payment = VirtualAccount.builder().vaNumber("TC-AED").currencyCode("AED").build();
        payment.setId(UUID.randomUUID());
        com.bank.vam.entity.pobo.PoboEnrolment enrolment = new com.bank.vam.entity.pobo.PoboEnrolment();
        enrolment.setCurrency("EUR");
        when(enrolments.route(eq(sub.getId()), eq("EUR"), any())).thenReturn(Optional.of(
            new PoboEnrolmentService.Route(enrolment, sub, treasury, payment, ihbCurrent)));
        when(accounts.getByViban("AE000000000000000000077")).thenReturn(source);
        Map<UUID, Payable> stored = new HashMap<>();
        when(payables.save(any())).thenAnswer(i -> {
            Payable p = i.getArgument(0);
            if (p.getId() == null) p.setId(UUID.randomUUID());
            stored.put(p.getId(), p);
            return p;
        });
        when(payables.findById(any())).thenAnswer(i -> Optional.ofNullable(stored.get(i.<UUID>getArgument(0))));

        StagedTransaction pobo = new StagedTransaction();
        pobo.setSourceRowNumber(1);
        pobo.setStatus(RowStatus.READY);
        StagedTransaction other = new StagedTransaction();
        other.setSourceRowNumber(2);
        other.setStatus(RowStatus.READY);
        when(staging.findByIngestJobIdAndStatus(job, RowStatus.READY)).thenReturn(List.of(pobo, other));
        when(ingestFx.of(any(), any())).thenReturn(new FxPaymentService.BatchFx(null, "Waiting for FX: above the limit"));
        TransformedRow poboRow = new TransformedRow(1, new BigDecimal("300000"), "EUR", "AE000000000000000000077",
            null, null, "Supplier GmbH", "DE00SUPP", "Machinery", "INV-900");
        TransformedRow otherRow = new TransformedRow(2, new BigDecimal("300000"), "EUR", "AE000000000000000000088",
            null, null, "Supplier GmbH", "DE00SUPP", "Machinery", "INV-901");
        when(ingestFx.crossCurrencyPobo(poboRow)).thenReturn(true);

        processor.process(job, List.of(poboRow, otherRow));

        assertThat(pobo.getStatus()).isEqualTo(RowStatus.PROCESSED);
        assertThat(pobo.getReason()).startsWith("Awaiting FX (treasury)");
        Payable waiting = stored.get(pobo.getProcessedEntityId());
        assertThat(waiting.getStatus()).isEqualTo(Payable.PayableStatus.APPROVED);
        assertThat(waiting.getPaymentRoute()).isEqualTo(Payable.PaymentRoute.POBO);
        assertThat(waiting.getFxStatus()).isEqualTo(Payable.AWAITING_FX);
        assertThat(waiting.getFxStatusReason()).contains("live-price limit");
        verify(ingestFx, never()).hold(eq(pobo), any(), any());
        verify(ingestFx).hold(other, otherRow, "Waiting for FX: above the limit");
        verify(transactions, never()).makeIhb6LegPoboPayment(any());
    }
}
