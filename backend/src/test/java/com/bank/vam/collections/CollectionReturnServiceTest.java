package com.bank.vam.collections;

import com.bank.vam.collections.CollectionLine.Status;
import com.bank.vam.collections.CollectionReturnService.Outcome;
import com.bank.vam.collections.CollectionReturnService.Return;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.receivables.Receivable;
import com.bank.vam.entity.receivables.Receivable.ReceivableStatus;
import com.bank.vam.iso20022.service.Iso20022RequestToPayService;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.receivables.ReceivableRepository;
import com.bank.vam.service.TransactionService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class CollectionReturnServiceTest {

    private final CollectionLineRepository lines = mock(CollectionLineRepository.class);
    private final CollectionRunRepository runs = mock(CollectionRunRepository.class);
    private final CustomerMandateRepository mandates = mock(CustomerMandateRepository.class);
    private final ReceivableRepository receivables = mock(ReceivableRepository.class);
    private final VirtualAccountRepository accounts = mock(VirtualAccountRepository.class);
    private final TransactionService transactions = mock(TransactionService.class);
    private final Iso20022RequestToPayService requestToPay = mock(Iso20022RequestToPayService.class);
    private final com.bank.vam.repository.TransactionRepository ledger = mock(com.bank.vam.repository.TransactionRepository.class);
    private final CollectionReturnService service = new CollectionReturnService(lines, runs, mandates, receivables, accounts,
            transactions, ledger, requestToPay, mock(PlatformTransactionManager.class));

    private final CollectionLine line = new CollectionLine();
    private final CustomerMandate mandate = new CustomerMandate();
    private final Receivable invoice = new Receivable();
    private final VirtualAccount account = VirtualAccount.builder().currentBalance(new BigDecimal("1098"))
            .availableBalance(new BigDecimal("1000")).heldBalance(new BigDecimal("98")).build();

    CollectionReturnServiceTest() {
        line.setId(UUID.randomUUID());
        line.setReceivableId(UUID.randomUUID());
        line.setMandateId(UUID.randomUUID());
        line.setCreditorVaId(UUID.randomUUID());
        line.setEndToEndId("DD-1");
        line.setAmount(new BigDecimal("100.00"));
        line.setCreditedAmount(new BigDecimal("98.00"));
        line.setCurrency("AED");
        line.setReceivableNumber("INV-1");
        mandate.setStatus(CustomerMandate.Status.ACTIVE);
        invoice.setNetAmount(new BigDecimal("100.00"));
        invoice.setPaidAmount(new BigDecimal("100.00"));
        invoice.setOutstandingAmount(BigDecimal.ZERO);
        invoice.setStatus(ReceivableStatus.PAID);
        invoice.setDueDate(LocalDate.now().plusDays(10));
        when(lines.findByEndToEndId("DD-1")).thenReturn(Optional.of(line));
        when(lines.findById(line.getId())).thenReturn(Optional.of(line));
        when(mandates.findById(line.getMandateId())).thenReturn(Optional.of(mandate));
        when(receivables.findById(line.getReceivableId())).thenReturn(Optional.of(invoice));
        when(accounts.findById(line.getCreditorVaId())).thenReturn(Optional.of(account));
    }

    @Test
    void aHeldCollectionReturnedComesOffHoldIsReversedAndTheInvoiceReopens() {
        line.setStatus(Status.SETTLED);

        Outcome o = service.apply(List.of(new Return("DD-1", "AM04"))).get(0);

        assertEquals("RETURNED", o.result());
        assertEquals(0, account.getHeldBalance().signum()); // released before the reversal takes the money out
        verify(transactions).reverseCollection(eq(line.getCreditorVaId()), eq(new BigDecimal("100.00")), eq("AED"), any(),
                eq("AM04 Insufficient funds"), any(), any(), eq("DIRECT_DEBIT"));
        assertEquals(ReceivableStatus.OPEN, invoice.getStatus());
        assertEquals(new BigDecimal("100.00"), invoice.getOutstandingAmount());
        assertEquals(Status.RETURNED, line.getStatus());
        assertEquals("AM04", line.getReturnCode());
        verify(requestToPay).sendRequestToPay(line.getReceivableId());
        assertTrue(line.getRtpAfterReturn());
        assertEquals("Returned after collection: AM04 Insufficient funds; Request to Pay sent", line.getReason());
        assertEquals(CustomerMandate.Status.ACTIVE, mandate.getStatus()); // insufficient funds: the mandate stands
    }

    @Test
    void aRejectionBeforeSettlementMovesNoMoneyAndADeadMandateIsSuspended() {
        line.setStatus(Status.SENT);

        Outcome o = service.apply(List.of(new Return("DD-1", "MD01"))).get(0);

        assertEquals("REJECTED", o.result());
        verify(transactions, never()).reverseCollection(any(), any(), any(), any(), any(), any(), any(), any());
        assertEquals(CustomerMandate.Status.SUSPENDED, mandate.getStatus());
        assertEquals("Suspended after MD01 No valid mandate on INV-1", mandate.getStatusReason());
        verify(requestToPay).sendRequestToPay(line.getReceivableId());
    }

    @Test
    void aReturnedCoboDebitAlsoReversesTheSubsidiarysInHouseCredit() {
        line.setStatus(Status.SETTLED);
        line.setTransactionRef("COL-1");
        invoice.setIsCobo(true);
        invoice.setCoboRequestStatus(Receivable.CoboRequestStatus.COLLECTED);
        com.bank.vam.entity.Transaction credit = new com.bank.vam.entity.Transaction();
        credit.setId(UUID.randomUUID());
        when(ledger.findByReferenceNumber("COL-1")).thenReturn(Optional.of(credit));

        assertEquals("RETURNED", service.apply(List.of(new Return("DD-1", "AM04"))).get(0).result());

        verify(transactions).reverseCollection(eq(line.getCreditorVaId()), eq(new BigDecimal("100.00")), eq("AED"), eq("COL-1"),
                any(), any(), any(), eq("DIRECT_DEBIT"));
        verify(transactions, times(1)).reverseCoboInHouseCredit(eq(credit.getId()), any());
        assertEquals(ReceivableStatus.OPEN, invoice.getStatus());
        assertEquals(new BigDecimal("100.00"), invoice.getOutstandingAmount());
        assertNull(invoice.getCoboRequestStatus());
    }

    @Test
    void aCoboDebitRejectedBeforeSettlementHasNoInHouseCreditToReverse() {
        line.setStatus(Status.SENT);
        invoice.setIsCobo(true);

        assertEquals("REJECTED", service.apply(List.of(new Return("DD-1", "AM04"))).get(0).result());

        verify(transactions, never()).reverseCoboInHouseCredit(any(), any());
        verify(transactions, never()).reverseCollection(any(), any(), any(), any(), any(), any(), any(), any());
        verifyNoInteractions(ledger);
    }

    @Test
    void aReturnedOneOffMandateCanBeUsedAgain() {
        line.setStatus(Status.AVAILABLE);
        mandate.setStatus(CustomerMandate.Status.USED);

        service.apply(List.of(new Return("DD-1", "MS02")));

        assertEquals(CustomerMandate.Status.ACTIVE, mandate.getStatus());
        assertEquals(new BigDecimal("98"), account.getHeldBalance()); // AVAILABLE: no hold left to release
    }

    @Test
    void unknownAndAlreadyReturnedDebitsChangeNothing() {
        line.setStatus(Status.RETURNED);
        when(lines.findByEndToEndId("NOPE")).thenReturn(Optional.empty());

        List<Outcome> o = service.apply(List.of(new Return("DD-1", "AM04"), new Return("NOPE", "AM04")));

        assertEquals("UNCHANGED", o.get(0).result());
        assertEquals("UNKNOWN", o.get(1).result());
        verifyNoInteractions(transactions, requestToPay);
    }

    @Test
    void bankMessagesAreRead() {
        String pacs004 = """
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:pacs.004.001.09"><PmtRtr>
                <TxInf><OrgnlEndToEndId>DD-1</OrgnlEndToEndId><RtrRsnInf><Rsn><Cd>AM04</Cd></Rsn></RtrRsnInf></TxInf>
                <TxInf><OrgnlEndToEndId>DD-2</OrgnlEndToEndId><RtrRsnInf><Rsn><Cd>MD06</Cd></Rsn></RtrRsnInf></TxInf>
                </PmtRtr></Document>""";
        assertEquals(List.of(new Return("DD-1", "AM04"), new Return("DD-2", "MD06")), CollectionReturnService.parsePacs004(pacs004));

        String pain002 = """
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:pain.002.001.10"><CstmrPmtStsRpt>
                <OrgnlGrpInfAndSts><OrgnlMsgId>CR-1</OrgnlMsgId><GrpSts>PART</GrpSts></OrgnlGrpInfAndSts>
                <OrgnlPmtInfAndSts><PmtInfSts>PART</PmtInfSts>
                  <TxInfAndSts><OrgnlEndToEndId>DD-1</OrgnlEndToEndId><TxSts>RJCT</TxSts><StsRsnInf><Rsn><Cd>AC04</Cd></Rsn></StsRsnInf></TxInfAndSts>
                  <TxInfAndSts><OrgnlEndToEndId>DD-2</OrgnlEndToEndId><TxSts>ACCP</TxSts></TxInfAndSts>
                </OrgnlPmtInfAndSts></CstmrPmtStsRpt></Document>""";
        assertEquals(List.of(new Return("DD-1", "AC04")), service.parsePain002(pain002));

        String camt054 = """
                <Document><BkToCstmrDbtCdtNtfctn><Ntfctn>
                <Ntry><CdtDbtInd>DBIT</CdtDbtInd><NtryDtls><TxDtls><Refs><EndToEndId>DD-3</EndToEndId></Refs>
                  <RtrInf><Rsn><Cd>MS02</Cd></Rsn></RtrInf></TxDtls></NtryDtls></Ntry>
                <Ntry><CdtDbtInd>CRDT</CdtDbtInd><NtryDtls><TxDtls><Refs><EndToEndId>X</EndToEndId></Refs></TxDtls></NtryDtls></Ntry>
                </Ntfctn></BkToCstmrDbtCdtNtfctn></Document>""";
        assertEquals(List.of(new Return("DD-3", "MS02")), CollectionReturnService.parseCamt054Returns(camt054));
    }

    @Test
    void aWholeRunRejectedRejectsEveryDebitStillWaiting() {
        CollectionRun run = new CollectionRun();
        run.setId(UUID.randomUUID());
        when(runs.findByRunRef("CR-9")).thenReturn(Optional.of(run));
        CollectionLine waiting = new CollectionLine();
        waiting.setStatus(Status.SENT);
        waiting.setEndToEndId("DD-W");
        CollectionLine settled = new CollectionLine();
        settled.setStatus(Status.SETTLED);
        settled.setEndToEndId("DD-S");
        when(lines.findByRunIdOrderByCreatedAtAsc(run.getId())).thenReturn(List.of(waiting, settled));

        String pain002 = "<Document><CstmrPmtStsRpt><OrgnlGrpInfAndSts><OrgnlMsgId>CR-9</OrgnlMsgId><GrpSts>RJCT</GrpSts>"
                + "<StsRsnInf><Rsn><Cd>BE05</Cd></Rsn></StsRsnInf></OrgnlGrpInfAndSts></CstmrPmtStsRpt></Document>";

        assertEquals(List.of(new Return("DD-W", "BE05")), service.parsePain002(pain002));
    }
}
