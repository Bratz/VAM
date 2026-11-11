package com.bank.vam.collections;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Pain008Test {

    private static final UUID ACCOUNT = UUID.randomUUID();

    private CollectionLine debit(String e2e, String amount, LocalDate on, String seq) {
        CollectionLine l = new CollectionLine();
        l.setEndToEndId(e2e);
        l.setAmount(new BigDecimal(amount));
        l.setCurrency("AED");
        l.setCollectionDate(on);
        l.setSequenceType(seq);
        l.setSchemeCode("UAE_DDS");
        l.setCreditorVaId(ACCOUNT);
        l.setMandateId(UUID.randomUUID());
        l.setMandateReference("MND-" + e2e);
        l.setDebtorName("Gulf & Sons <LLC>");
        l.setDebtorIban("AE070331234567890123456");
        l.setReceivableNumber("INV-" + e2e);
        return l;
    }

    @Test
    void oneMessageWithAPaymentBlockPerDateAndSequence() throws Exception {
        LocalDate d1 = LocalDate.of(2026, 11, 10);
        LocalDate d2 = LocalDate.of(2026, 11, 20);
        String xml = Pain008.build("CR-1", "Test Corp", "EBILAEADXXX",
                List.of(debit("A", "100.00", d1, "FRST"), debit("B", "50.50", d1, "FRST"), debit("C", "10.00", d2, "RCUR")),
                Map.of(ACCOUNT, "VA-AED-1"), Map.of(), Map.of());

        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        Element hdr = (Element) doc.getElementsByTagName("GrpHdr").item(0);
        assertEquals("3", hdr.getElementsByTagName("NbOfTxs").item(0).getTextContent());
        assertEquals("160.50", hdr.getElementsByTagName("CtrlSum").item(0).getTextContent());

        assertEquals(2, doc.getElementsByTagName("PmtInf").getLength());
        Element first = (Element) doc.getElementsByTagName("PmtInf").item(0);
        assertEquals("2", first.getElementsByTagName("NbOfTxs").item(0).getTextContent());
        assertEquals("150.50", first.getElementsByTagName("CtrlSum").item(0).getTextContent());
        assertEquals("FRST", first.getElementsByTagName("SeqTp").item(0).getTextContent());
        assertEquals("2026-11-10", first.getElementsByTagName("ReqdColltnDt").item(0).getTextContent());
        assertEquals("VA-AED-1", ((Element) first.getElementsByTagName("CdtrAcct").item(0)).getElementsByTagName("Id").item(1).getTextContent());
        // no debtor BIC: the agent is NOTPROVIDED; names with markup characters stay well-formed
        assertEquals("NOTPROVIDED", ((Element) first.getElementsByTagName("DbtrAgt").item(0)).getElementsByTagName("Id").item(0).getTextContent());
        assertEquals("Gulf & Sons <LLC>", ((Element) first.getElementsByTagName("Dbtr").item(0)).getElementsByTagName("Nm").item(0).getTextContent());
        assertEquals(0, doc.getElementsByTagName("UltmtCdtr").getLength());
    }

    @Test
    void aDebitCollectedOnBehalfHasTheTreasuryAsCreditorAndTheSubsidiaryAsUltimateCreditor() throws Exception {
        LocalDate d = LocalDate.of(2026, 11, 10);
        UUID treasuryAccount = UUID.randomUUID();
        CollectionLine direct = debit("A", "100.00", d, "FRST");
        direct.setReceivableId(UUID.randomUUID());
        CollectionLine cobo = debit("B", "50.00", d, "FRST");
        cobo.setReceivableId(UUID.randomUUID());
        cobo.setCreditorVaId(treasuryAccount);

        String xml = Pain008.build("CR-2", "Test Corp", "EBILAEADXXX", List.of(direct, cobo),
                Map.of(ACCOUNT, "VA-AED-1", treasuryAccount, "VA-TRS-1"), Map.of(),
                Map.of(cobo.getReceivableId(), new Pain008.OnBehalf("Treasury Centre FZE", "Sub <X> LLC")));

        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        assertEquals(2, doc.getElementsByTagName("PmtInf").getLength());
        Element directBlock = (Element) doc.getElementsByTagName("PmtInf").item(0);
        assertEquals("Test Corp", ((Element) directBlock.getElementsByTagName("Cdtr").item(0)).getElementsByTagName("Nm").item(0).getTextContent());
        assertEquals("VA-AED-1", ((Element) directBlock.getElementsByTagName("CdtrAcct").item(0)).getElementsByTagName("Id").item(1).getTextContent());
        assertEquals(0, directBlock.getElementsByTagName("UltmtCdtr").getLength());

        Element coboBlock = (Element) doc.getElementsByTagName("PmtInf").item(1);
        assertEquals("1", coboBlock.getElementsByTagName("NbOfTxs").item(0).getTextContent());
        assertEquals("Treasury Centre FZE", ((Element) coboBlock.getElementsByTagName("Cdtr").item(0)).getElementsByTagName("Nm").item(0).getTextContent());
        assertEquals("VA-TRS-1", ((Element) coboBlock.getElementsByTagName("CdtrAcct").item(0)).getElementsByTagName("Id").item(1).getTextContent());
        Element tx = (Element) coboBlock.getElementsByTagName("DrctDbtTxInf").item(0);
        assertEquals("Sub <X> LLC", ((Element) tx.getElementsByTagName("UltmtCdtr").item(0)).getElementsByTagName("Nm").item(0).getTextContent());
        // ISO 20022 order: UltmtCdtr follows DrctDbtTx and precedes DbtrAgt
        List<String> order = new java.util.ArrayList<>();
        for (org.w3c.dom.Node n = tx.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e) order.add(e.getTagName());
        }
        assertEquals(order.indexOf("DrctDbtTx") + 1, order.indexOf("UltmtCdtr"));
        assertEquals(order.indexOf("UltmtCdtr") + 1, order.indexOf("DbtrAgt"));
    }
}
