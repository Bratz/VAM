package com.bank.vam.collections;

import com.bank.vam.collections.CollectionRoutingService.Line;
import com.bank.vam.collections.CollectionRoutingService.Method;
import com.bank.vam.collections.CollectionRoutingService.Preview;
import com.bank.vam.config.HomeBankProperties;
import com.bank.vam.entity.Corporate;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.receivables.Receivable;
import com.bank.vam.iso20022.service.Iso20022RequestToPayService;
import com.bank.vam.repository.CorporateRepository;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.receivables.ReceivableRepository;
import com.bank.vam.service.pobo.PoboEnrolmentService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** COBO (spec D8): in a mixed run a debit collected on behalf goes to the treasury's account with the subsidiary as UltmtCdtr. */
class CollectionRunServiceTest {

    private final CollectionRoutingService routing = mock(CollectionRoutingService.class);
    private final CollectionRunRepository runs = mock(CollectionRunRepository.class);
    private final CollectionLineRepository lines = mock(CollectionLineRepository.class);
    private final CustomerMandateRepository mandates = mock(CustomerMandateRepository.class);
    private final VirtualAccountRepository accounts = mock(VirtualAccountRepository.class);
    private final CorporateRepository corporates = mock(CorporateRepository.class);
    private final HomeBankProperties homeBank = mock(HomeBankProperties.class);
    private final ReceivableRepository receivables = mock(ReceivableRepository.class);
    private final PoboEnrolmentService ibans = mock(PoboEnrolmentService.class);
    private final CollectionRunService service = new CollectionRunService(routing, runs, lines, mandates, accounts, corporates,
            mock(Iso20022RequestToPayService.class), homeBank, receivables, mock(PlatformTransactionManager.class), ibans);

    @Test
    void aCoboDebitIsCollectedIntoTheTreasurysAccountOnBehalfOfTheSubsidiary() {
        UUID corporateId = UUID.randomUUID();
        LocalDate on = LocalDate.of(2026, 11, 16);
        VirtualAccount subsidiaryAccount = account("VA-SUB-1");
        VirtualAccount treasuryAccount = account("VA-TRS-1");

        Receivable direct = invoice("INV-1");
        Receivable cobo = invoice("INV-2");
        cobo.setIsCobo(true);
        cobo.setCoboCollectorEntityId(UUID.randomUUID());
        cobo.setCoboCollectorEntityName("Treasury Centre FZE");
        cobo.setOwningEntityName("Subsidiary X LLC");
        cobo.setVirtualAccountId(treasuryAccount.getId());

        CustomerMandate m1 = mandate(subsidiaryAccount);
        CustomerMandate m2 = mandate(subsidiaryAccount); // the subsidiary's mandate, unchanged

        when(corporates.findById(corporateId)).thenReturn(Optional.of(Corporate.builder().legalName("Group Corp").build()));
        when(routing.preview(corporateId, List.of(direct.getId(), cobo.getId()))).thenReturn(new Preview(List.of(
                debit(direct, m1, on), debit(cobo, m2, on)), List.of()));
        CollectionRun[] saved = new CollectionRun[1]; // pain.008 is set on the saved run
        when(runs.save(any())).thenAnswer(i -> saved[0] = i.getArgument(0));
        when(lines.saveAll(any())).thenAnswer(i -> i.getArgument(0));
        when(mandates.findAllById(any())).thenReturn(List.of(m1, m2));
        when(receivables.findAllById(any())).thenReturn(List.of(direct, cobo));
        when(accounts.findAllById(any())).thenReturn(List.of(subsidiaryAccount, treasuryAccount));
        when(homeBank.getBic()).thenReturn("EBILAEADXXX");

        CollectionRunService.RunDetail run = service.submit(corporateId, List.of(direct.getId(), cobo.getId()), CollectionRun.Source.INVOICES);

        assertEquals(subsidiaryAccount.getId(), run.lines().get(0).getCreditorVaId());
        assertEquals(treasuryAccount.getId(), run.lines().get(1).getCreditorVaId());
        assertEquals(m2.getMandateReference(), run.lines().get(1).getMandateReference());

        String xml = saved[0].getPain008Xml();
        String[] blocks = xml.split("<PmtInf>");
        assertEquals(3, blocks.length); // header + one block per creditor account
        assertTrue(blocks[1].contains("<Nm>Group Corp</Nm>") && blocks[1].contains("VA-SUB-1") && !blocks[1].contains("UltmtCdtr"));
        assertTrue(blocks[2].contains("<Cdtr>\n        <Nm>Treasury Centre FZE</Nm>") && blocks[2].contains("VA-TRS-1"));
        assertTrue(blocks[2].contains("<UltmtCdtr>\n          <Nm>Subsidiary X LLC</Nm>"));
    }

    /** ihb-fx 4.4: a EUR invoice collected into treasury's AED account names that account's IBAN and AED, the debit EUR. */
    @Test
    void theCreditorAccountIsTheReceivingAccountsIbanInItsOwnCurrency() {
        UUID corporateId = UUID.randomUUID();
        LocalDate on = LocalDate.of(2026, 11, 16);
        VirtualAccount treasuryAccount = account("VA-TRS-AED");
        Receivable cobo = invoice("INV-EUR");
        cobo.setIsCobo(true);
        cobo.setCoboCollectorEntityName("Treasury Centre FZE");
        cobo.setOwningEntityName("Subsidiary X LLC");
        cobo.setVirtualAccountId(treasuryAccount.getId());
        CustomerMandate m = mandate(treasuryAccount);
        Line eur = new Line(cobo.getId(), "INV-EUR", "Acme Trading", new BigDecimal("1000.00"), "EUR", on,
                Method.DIRECT_DEBIT, on, m.getId(), m.getMandateReference(), m.getSchemeCode(), "FRST", null);

        when(corporates.findById(corporateId)).thenReturn(Optional.of(Corporate.builder().legalName("Group Corp").build()));
        when(routing.preview(corporateId, List.of(cobo.getId()))).thenReturn(new Preview(List.of(eur), List.of()));
        CollectionRun[] saved = new CollectionRun[1];
        when(runs.save(any())).thenAnswer(i -> saved[0] = i.getArgument(0));
        when(lines.saveAll(any())).thenAnswer(i -> i.getArgument(0));
        when(mandates.findAllById(any())).thenReturn(List.of(m));
        when(receivables.findAllById(any())).thenReturn(List.of(cobo));
        when(accounts.findAllById(any())).thenReturn(List.of(treasuryAccount));
        when(ibans.accountIban(treasuryAccount)).thenReturn("AE460260001015555555555");
        when(homeBank.getBic()).thenReturn("EBILAEADXXX");

        service.submit(corporateId, List.of(cobo.getId()), CollectionRun.Source.INVOICES);

        String xml = saved[0].getPain008Xml();
        String cdtrAcct = xml.substring(xml.indexOf("<CdtrAcct>"), xml.indexOf("</CdtrAcct>"));
        assertTrue(cdtrAcct.contains("<IBAN>AE460260001015555555555</IBAN>") && !cdtrAcct.contains("VA-TRS-AED"), cdtrAcct);
        assertTrue(cdtrAcct.contains("<Ccy>AED</Ccy>"), cdtrAcct);
        assertTrue(xml.contains("<InstdAmt Ccy=\"EUR\">1000.00</InstdAmt>"));
    }

    private static VirtualAccount account(String number) {
        VirtualAccount v = new VirtualAccount();
        v.setId(UUID.randomUUID());
        v.setVaNumber(number);
        v.setCurrencyCode("AED");
        return v;
    }

    private static Receivable invoice(String number) {
        Receivable r = new Receivable();
        r.setId(UUID.randomUUID());
        r.setReceivableNumber(number);
        return r;
    }

    private static CustomerMandate mandate(VirtualAccount creditor) {
        CustomerMandate m = new CustomerMandate();
        m.setId(UUID.randomUUID());
        m.setMandateReference("MND-" + m.getId().toString().substring(0, 8));
        m.setSchemeCode("UAE_DDS");
        m.setCreditorVaId(creditor.getId());
        m.setDebtorName("Acme Trading");
        m.setDebtorIban("AE070331234567890123456");
        return m;
    }

    private static Line debit(Receivable r, CustomerMandate m, LocalDate on) {
        return new Line(r.getId(), r.getReceivableNumber(), "Acme Trading", new BigDecimal("100.00"), "AED", on,
                Method.DIRECT_DEBIT, on, m.getId(), m.getMandateReference(), m.getSchemeCode(), "FRST", null);
    }
}
