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
                Map.of(ACCOUNT, "VA-AED-1"), Map.of());

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
    }
}
