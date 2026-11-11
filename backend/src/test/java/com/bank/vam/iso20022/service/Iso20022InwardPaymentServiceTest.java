package com.bank.vam.iso20022.service;

import com.bank.vam.dto.TransactionDto;
import com.bank.vam.entity.Transaction;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.viban.Viban;
import com.bank.vam.fx.deal.FxPaymentService;
import com.bank.vam.iso20022.dto.Iso20022PaymentDto.InwardPaymentRequest;
import com.bank.vam.iso20022.dto.Iso20022PaymentDto.InwardPaymentResponse;
import com.bank.vam.iso20022.mapper.Iso20022MessageMapper;
import com.bank.vam.repository.TransactionRepository;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.viban.VibanRepository;
import com.bank.vam.service.TransactionService;
import com.bank.vam.service.receivables.ReconciliationService;
import com.bank.vam.service.treasury.FxRateService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Regression coverage for the idempotency guard: a re-delivered pacs.008/camt.054 message or a
 * re-uploaded bulk-ingest row must not post the same 4-leg collection twice. Also covers wiring
 * to ReconciliationService -- a VIBAN-routed payment must attempt real reconciliation so its
 * matching Receivable actually gets marked paid, not just the ledger posting.
 */
class Iso20022InwardPaymentServiceTest {

    private final VibanRepository vibanRepository = mock(VibanRepository.class);
    private final VirtualAccountRepository virtualAccountRepository = mock(VirtualAccountRepository.class);
    private final TransactionRepository transactionRepository = mock(TransactionRepository.class);
    private final TransactionService transactionService = mock(TransactionService.class);
    private final Iso20022MessageMapper messageMapper = mock(Iso20022MessageMapper.class);
    private final ReconciliationService reconciliationService = mock(ReconciliationService.class);
    private final FxPaymentService fxPayments = mock(FxPaymentService.class);
    private final FxRateService fxRates = mock(FxRateService.class);

    private final Iso20022InwardPaymentService service = new Iso20022InwardPaymentService(
            vibanRepository, virtualAccountRepository, transactionRepository, transactionService, messageMapper,
            reconciliationService, fxPayments, fxRates, mock(com.bank.vam.repository.receivables.ReceivableRepository.class),
            mock(com.bank.vam.repository.receivables.InwardFxHoldRepository.class));

    private static final UUID VA_ID = UUID.randomUUID();

    private VirtualAccount activeVa() {
        VirtualAccount va = VirtualAccount.builder()
                .vaNumber("VA-1000")
                .currencyCode("AED")
                .status(VirtualAccount.VaStatus.ACTIVE)
                .currentBalance(BigDecimal.valueOf(500))
                .build();
        va.setId(VA_ID);
        return va;
    }

    private InwardPaymentRequest request(String endToEndId, BigDecimal amount) {
        return InwardPaymentRequest.builder()
                .amount(amount)
                .currency("AED")
                .creditorAccount("VA-1000")
                .endToEndId(endToEndId)
                .build();
    }

    private void stubAccountLookup() {
        when(vibanRepository.findByViban(any())).thenReturn(Optional.empty());
        when(virtualAccountRepository.findByVaNumber("VA-1000")).thenReturn(Optional.of(activeVa()));
    }

    private void stubSuccessfulCollection() {
        when(transactionService.processCollection(any())).thenReturn(TransactionDto.CollectionResponse.builder()
                .transactionId(UUID.randomUUID())
                .referenceNumber("REF-1")
                .targetBalanceBefore(BigDecimal.valueOf(500))
                .targetBalanceAfter(BigDecimal.valueOf(600))
                .build());
    }

    @Test
    void firstCallProcessesNormally() {
        stubAccountLookup();
        stubSuccessfulCollection();
        when(transactionRepository.findAllByEndToEndIdAndVaId("E2E-1", VA_ID)).thenReturn(List.of());

        InwardPaymentResponse response = service.processInwardPayment(request("E2E-1", BigDecimal.valueOf(100)));

        assertTrue(response.isSuccess());
        verify(transactionService, times(1)).processCollection(any());
    }

    @Test
    void repeatedEndToEndIdSameAccountAndAmountIsSuppressedAsDuplicate() {
        stubAccountLookup();

        Transaction existing = Transaction.builder()
                .vaId(VA_ID)
                .amount(BigDecimal.valueOf(100))
                .currencyCode("AED")
                .referenceNumber("REF-1")
                .build();
        existing.setId(UUID.randomUUID());
        when(transactionRepository.findAllByEndToEndIdAndVaId("E2E-1", VA_ID)).thenReturn(List.of(existing));

        InwardPaymentResponse response = service.processInwardPayment(request("E2E-1", BigDecimal.valueOf(100)));

        assertTrue(response.isSuccess());
        assertEquals("REF-1", response.getTransactionReference());
        verify(transactionService, never()).processCollection(any());
    }

    @Test
    void repeatedEndToEndIdDifferentAmountIsNotTreatedAsDuplicate() {
        stubAccountLookup();
        stubSuccessfulCollection();

        Transaction existing = Transaction.builder()
                .vaId(VA_ID)
                .amount(BigDecimal.valueOf(100))
                .currencyCode("AED")
                .referenceNumber("REF-1")
                .build();
        existing.setId(UUID.randomUUID());
        when(transactionRepository.findAllByEndToEndIdAndVaId("E2E-1", VA_ID)).thenReturn(List.of(existing));

        // Same endToEndId, different amount than the existing transaction.
        InwardPaymentResponse response = service.processInwardPayment(request("E2E-1", BigDecimal.valueOf(250)));

        assertTrue(response.isSuccess());
        verify(transactionService, times(1)).processCollection(any());
    }

    @Test
    void blankEndToEndIdSkipsTheDuplicateLookupEntirely() {
        stubAccountLookup();
        stubSuccessfulCollection();

        service.processInwardPayment(request(null, BigDecimal.valueOf(100)));

        verify(transactionRepository, never()).findAllByEndToEndIdAndVaId(any(), any());
        verify(transactionService, times(1)).processCollection(any());
    }

    private static final UUID VIBAN_ID = UUID.randomUUID();
    private static final UUID RECEIVABLE_ID = UUID.randomUUID();

    private Viban activeViban() {
        Viban viban = Viban.builder()
                .viban("GB830410000000000000660643")
                .virtualAccountId(VA_ID)
                .status(Viban.STATUS_ACTIVE)
                .build();
        viban.setId(VIBAN_ID);
        return viban;
    }

    private InwardPaymentRequest vibanRequest(BigDecimal amount) {
        return InwardPaymentRequest.builder()
                .amount(amount)
                .currency("AED")
                .creditorAccount("GB830410000000000000660643")
                .debtorName("Acme Corp")
                .debtorAccount("DEBTOR-ACCT-1")
                .remittanceInfo("Invoice payment")
                .build();
    }

    @Test
    void vibanRoutedPaymentAttemptsRealReconciliation() {
        when(vibanRepository.findByViban("GB830410000000000000660643")).thenReturn(Optional.of(activeViban()));
        when(virtualAccountRepository.findById(VA_ID)).thenReturn(Optional.of(activeVa()));
        stubSuccessfulCollection();

        UUID transactionId = UUID.randomUUID();
        when(transactionService.processCollection(any())).thenReturn(TransactionDto.CollectionResponse.builder()
                .transactionId(transactionId)
                .referenceNumber("REF-2")
                .targetBalanceBefore(BigDecimal.valueOf(500))
                .targetBalanceAfter(BigDecimal.valueOf(600))
                .build());
        when(reconciliationService.attemptAutoReconcile(eq(transactionId), eq(VIBAN_ID), eq(BigDecimal.valueOf(100)),
                eq("AED"), eq("Acme Corp"), eq("DEBTOR-ACCT-1"), eq("Invoice payment")))
                .thenReturn(ReconciliationService.ReconciliationResult.builder()
                        .success(true)
                        .matchType(ReconciliationService.MatchType.DIRECT)
                        .receivableId(RECEIVABLE_ID)
                        .build());

        InwardPaymentResponse response = service.processInwardPayment(vibanRequest(BigDecimal.valueOf(100)));

        assertTrue(response.isSuccess());
        assertTrue(response.isAutoReconciled());
        assertEquals("DIRECT", response.getReconciledReferenceType());
        assertEquals(RECEIVABLE_ID.toString(), response.getReconciledReferenceId());
        verify(reconciliationService).attemptAutoReconcile(eq(transactionId), eq(VIBAN_ID), any(), any(), any(), any(), any());
    }

    @Test
    void vaNumberRoutedPaymentNeverAttemptsReconciliation() {
        stubAccountLookup();
        stubSuccessfulCollection();

        service.processInwardPayment(request("E2E-VA-DIRECT", BigDecimal.valueOf(100)));

        verify(reconciliationService, never()).attemptAutoReconcile(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void unmatchedReconciliationLeavesResponseSuccessfulButNotAutoReconciled() {
        when(vibanRepository.findByViban("GB830410000000000000660643")).thenReturn(Optional.of(activeViban()));
        when(virtualAccountRepository.findById(VA_ID)).thenReturn(Optional.of(activeVa()));
        stubSuccessfulCollection();
        when(reconciliationService.attemptAutoReconcile(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(ReconciliationService.ReconciliationResult.builder()
                        .success(false)
                        .matchType(ReconciliationService.MatchType.NONE)
                        .build());

        InwardPaymentResponse response = service.processInwardPayment(vibanRequest(BigDecimal.valueOf(100)));

        assertTrue(response.isSuccess());
        assertFalse(response.isAutoReconciled());
        assertNull(response.getReconciledReferenceId());
    }

    /** The credited leg holds the amount net of the collection fee; the guard must still see the gross. */
    @Test
    void repeatedEndToEndIdIsSuppressedWhenTheCreditedLegIsNetOfFee() {
        stubAccountLookup();
        Transaction existing = Transaction.builder().vaId(VA_ID).amount(BigDecimal.valueOf(98))
                .feeAmount(BigDecimal.valueOf(2)).currencyCode("AED").referenceNumber("REF-1").build();
        existing.setId(UUID.randomUUID());
        when(transactionRepository.findAllByEndToEndIdAndVaId("E2E-1", VA_ID)).thenReturn(List.of(existing));

        InwardPaymentResponse response = service.processInwardPayment(request("E2E-1", BigDecimal.valueOf(100)));

        assertEquals("REF-1", response.getTransactionReference());
        verify(transactionService, never()).processCollection(any());
    }

    /** A bulk camt.054 used to post only its first credit; every credited line must come back. */
    @Test
    void camt054ReturnsEveryCreditLine() {
        String xml = """
                <Document><BkToCstmrDbtCdtNtfctn><GrpHdr><MsgId>M1</MsgId></GrpHdr>
                <Ntfctn><Acct><Id><IBAN>AE070331234567890123456</IBAN></Id></Acct>
                  <Ntry><Amt Ccy="AED">300.00</Amt><CdtDbtInd>CRDT</CdtDbtInd><NtryDtls>
                    <TxDtls><Refs><EndToEndId>E1</EndToEndId></Refs><Amt Ccy="AED">100.00</Amt>
                      <RltdPties><CdtrAcct><Id><IBAN>AE11VIBAN1</IBAN></Id></CdtrAcct></RltdPties></TxDtls>
                    <TxDtls><Refs><EndToEndId>E2</EndToEndId></Refs>
                      <AmtDtls><InstdAmt><Amt Ccy="USD">54.45</Amt></InstdAmt><TxAmt><Amt Ccy="AED">200.00</Amt></TxAmt></AmtDtls></TxDtls>
                  </NtryDtls></Ntry>
                  <Ntry><Amt Ccy="AED">50.00</Amt><CdtDbtInd>DBIT</CdtDbtInd></Ntry>
                  <Ntry><Amt Ccy="AED">75.00</Amt><CdtDbtInd>CRDT</CdtDbtInd><NtryDtls>
                    <TxDtls><Refs><EndToEndId>E3</EndToEndId></Refs></TxDtls></NtryDtls></Ntry>
                </Ntfctn>
                <Ntfctn><Acct><Id><IBAN>AE22OTHER</IBAN></Id></Acct>
                  <Ntry><Amt Ccy="AED">10.00</Amt><CdtDbtInd>CRDT</CdtDbtInd></Ntry>
                </Ntfctn></BkToCstmrDbtCdtNtfctn></Document>""";

        List<InwardPaymentRequest> lines = service.parseCamt054(xml);

        assertEquals(4, lines.size());
        assertEquals(List.of("E1", "E2", "E3"), lines.subList(0, 3).stream().map(InwardPaymentRequest::getEndToEndId).toList());
        assertEquals(List.of("100.00", "200.00", "75.00", "10.00"), lines.stream().map(l -> l.getAmount().toPlainString()).toList());
        assertEquals("AED", lines.get(1).getCurrency());
        assertEquals(List.of("AE11VIBAN1", "AE070331234567890123456", "AE070331234567890123456", "AE22OTHER"),
                lines.stream().map(InwardPaymentRequest::getCreditorAccount).toList());
    }

    /** A USD credit to an AED account is converted on credit; it was booked as AED at the USD amount. */
    @Test
    void aPaymentInAnotherCurrencyIsConvertedBeforeItIsCredited() {
        when(vibanRepository.findByViban("GB830410000000000000660643")).thenReturn(Optional.of(activeViban()));
        when(virtualAccountRepository.findById(VA_ID)).thenReturn(Optional.of(activeVa()));
        when(fxRates.convert(BigDecimal.valueOf(100), "USD", "AED")).thenReturn(new BigDecimal("367.25"));
        FxPaymentService.Conversion cv = new FxPaymentService.Conversion(BigDecimal.valueOf(100), "USD",
                new BigDecimal("367.20"), "AED", new BigDecimal("3.672"), "USD/AED", List.of());
        when(fxPayments.convertIncoming(any(), eq("USD"), eq(BigDecimal.valueOf(100)))).thenReturn(cv);
        stubSuccessfulCollection();
        Transaction credit = Transaction.builder().build();
        when(transactionRepository.findById(any())).thenReturn(Optional.of(credit));

        InwardPaymentRequest req = vibanRequest(BigDecimal.valueOf(100));
        req.setCurrency("usd");
        assertTrue(service.processInwardPayment(req).isSuccess());

        verify(transactionService).processCollection(argThat(c ->
                c.getAmount().compareTo(new BigDecimal("367.20")) == 0 && "AED".equals(c.getCurrencyCode())));
        verify(fxPayments).settleIncoming(eq(cv), eq(credit), any());
        verify(reconciliationService).attemptAutoReconcile(any(), eq(VIBAN_ID), eq(new BigDecimal("367.20")), eq("AED"),
                any(), any(), any());
    }

    /** ihb-fx: a COBO invoice's EUR VIBAN on treasury's AED account; EUR 1,000 sent, AED 4,012.30 credited. */
    private Viban eurViban(String min, String max) {
        Viban viban = activeViban();
        viban.setCurrencyCode("EUR");
        viban.setExpectedAmount(new BigDecimal("1000"));
        viban.setMinAmount(new BigDecimal(min));
        viban.setMaxAmount(new BigDecimal(max));
        when(vibanRepository.findByViban("GB830410000000000000660643")).thenReturn(Optional.of(viban));
        when(virtualAccountRepository.findById(VA_ID)).thenReturn(Optional.of(activeVa()));
        return viban;
    }

    private InwardPaymentRequest eurRequest(String amount) {
        InwardPaymentRequest req = vibanRequest(new BigDecimal(amount));
        req.setCurrency("EUR");
        return req;
    }

    @Test
    void aEurVibanOnAnAedAccountChecksItsBoundsInEurAndRecordsEur() {
        Viban viban = eurViban("900", "1100");
        FxPaymentService.Conversion cv = new FxPaymentService.Conversion(new BigDecimal("1000"), "EUR",
                new BigDecimal("4012.30"), "AED", new BigDecimal("4.0123"), "EUR/AED", List.of());
        when(fxPayments.convertIncoming(any(), eq("EUR"), any())).thenReturn(cv);
        stubSuccessfulCollection();
        when(transactionRepository.findById(any())).thenReturn(Optional.of(Transaction.builder().build()));

        assertTrue(service.processInwardPayment(eurRequest("1000")).isSuccess());

        verify(fxRates, never()).convert(any(), any(), any());
        assertEquals(0, new BigDecimal("1000").compareTo(viban.getTotalAmountReceived()));
        // Reconciliation gets the credit as booked; it reads the EUR sent from the credit (ReconciliationService.paidOn).
        verify(reconciliationService).attemptAutoReconcile(any(), eq(VIBAN_ID), eq(new BigDecimal("4012.30")), eq("AED"),
                any(), any(), any());
    }

    @Test
    void aEurPaymentOutsideTheEurVibansBoundsIsRefusedBeforeAnythingIsBooked() {
        eurViban("900", "1100");

        InwardPaymentResponse response = service.processInwardPayment(eurRequest("1200"));

        assertFalse(response.isSuccess());
        verify(fxPayments, never()).convertIncoming(any(), any(), any());
        verify(transactionService, never()).processCollection(any());
    }

    @Test
    void aUsdPaymentToTheEurVibanIsComparedAtTheIndicativeEurAmount() {
        eurViban("900", "1100");
        when(fxRates.convert(new BigDecimal("1300"), "USD", "EUR")).thenReturn(new BigDecimal("1196.00"));
        InwardPaymentRequest req = vibanRequest(new BigDecimal("1300"));
        req.setCurrency("USD");

        assertFalse(service.processInwardPayment(req).isSuccess());
        verify(transactionService, never()).processCollection(any());
    }

    /** D6: treasury cannot sell the EUR (no FX sub-limit, no live price): refused, nothing posted or reconciled. */
    @Test
    void aEurCreditTreasuryCannotConvertIsRefusedAndNothingIsPosted() {
        Viban viban = eurViban("900", "1100");
        when(fxPayments.convertIncoming(any(), eq("EUR"), any())).thenThrow(new com.bank.vam.exception.BusinessException(
                "FX_COLLECTION_NEEDS_DEAL", "No FX sub-limit is allocated to MNC-TREASURY, so EUR 1000 cannot be converted"));

        InwardPaymentResponse response = service.processInwardPayment(eurRequest("1000"));

        assertFalse(response.isSuccess());
        assertEquals("RJCT", response.getStatusCode());
        verify(transactionService, never()).processCollection(any());
        verify(fxPayments, never()).settleIncoming(any(), any(), any());
        verify(reconciliationService, never()).attemptAutoReconcile(any(), any(), any(), any(), any(), any(), any());
        assertEquals(0, viban.getTotalAmountReceived().signum());
    }

    /** A converted credit is booked in AED; a re-delivery of the USD message is still recognised by what was sent. */
    @Test
    void aRedeliveredForeignCurrencyPaymentIsADuplicate() {
        stubAccountLookup();
        Transaction existing = Transaction.builder().vaId(VA_ID).amount(new BigDecimal("367.20")).currencyCode("AED")
                .instructedAmount(BigDecimal.valueOf(100)).instructedCurrency("USD").referenceNumber("REF-FX").build();
        existing.setId(UUID.randomUUID());
        when(transactionRepository.findAllByEndToEndIdAndVaId("E2E-FX", VA_ID)).thenReturn(List.of(existing));

        InwardPaymentRequest req = request("E2E-FX", BigDecimal.valueOf(100));
        req.setCurrency("USD");
        InwardPaymentResponse response = service.processInwardPayment(req);

        assertEquals("REF-FX", response.getTransactionReference());
        verify(fxPayments, never()).convertIncoming(any(), any(), any());
        verify(transactionService, never()).processCollection(any());
    }
}
