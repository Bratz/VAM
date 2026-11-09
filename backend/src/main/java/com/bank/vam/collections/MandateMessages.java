package com.bank.vam.collections;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * The electronic-mandate messages a creditor sends the payer's bank: pain.009 (MandateInitiationRequest),
 * pain.010 (MandateAmendmentRequest) and pain.011 (MandateCancellationRequest). The bank answers the first two
 * with a pain.012 (MandateAcceptanceReport), read by {@link MandateService#acceptanceReport}.
 */
final class MandateMessages {

    private static final Map<CustomerMandate.Period, String> FREQUENCY = Map.of(
            CustomerMandate.Period.WEEK, "WEEK", CustomerMandate.Period.MONTH, "MNTH",
            CustomerMandate.Period.QUARTER, "QURT", CustomerMandate.Period.YEAR, "YEAR");

    private MandateMessages() {
    }

    static String initiation(CustomerMandate m, String creditorName, String creditorAccount, String creditorBic) {
        StringBuilder x = head("pain.009.001.07", "MndtInitnReq", m.getRequestId());
        x.append("    <Mndt>\n");
        mandate(x, m, m.getDebtorIban(), m.getDebtorBic(), m.getMaxPerCollection(), creditorName, creditorAccount, creditorBic);
        x.append("    </Mndt>\n");
        return tail(x, "MndtInitnReq");
    }

    /** The mandate as it would read after the amendment, with the original's identification. */
    static String amendment(CustomerMandate m, String creditorName, String creditorAccount, String creditorBic) {
        StringBuilder x = head("pain.010.001.07", "MndtAmdmntReq", m.getRequestId());
        x.append("    <UndrlygAmdmntDtls>\n");
        x.append("      <AmdmntRsn><Rsn><Cd>MD17</Cd></Rsn></AmdmntRsn>\n"); // MD17: requested by the initiating party
        x.append("      <Mndt>\n");
        mandate(x, m, or(m.getPendingDebtorIban(), m.getDebtorIban()), or(m.getPendingDebtorBic(), m.getDebtorBic()),
                m.getPendingMaxPerCollection() != null ? m.getPendingMaxPerCollection() : m.getMaxPerCollection(),
                creditorName, creditorAccount, creditorBic);
        x.append("      </Mndt>\n");
        x.append("      <OrgnlMndt><OrgnlMndtId>").append(esc(m.getMandateReference())).append("</OrgnlMndtId></OrgnlMndt>\n");
        x.append("    </UndrlygAmdmntDtls>\n");
        return tail(x, "MndtAmdmntReq");
    }

    static String cancellation(CustomerMandate m, String messageId, String reasonCode) {
        StringBuilder x = head("pain.011.001.07", "MndtCxlReq", messageId);
        x.append("    <UndrlygCxlDtls>\n");
        x.append("      <CxlRsn><Rsn><Cd>").append(esc(reasonCode)).append("</Cd></Rsn></CxlRsn>\n");
        x.append("      <OrgnlMndt><OrgnlMndtId>").append(esc(m.getMandateReference())).append("</OrgnlMndtId></OrgnlMndt>\n");
        x.append("    </UndrlygCxlDtls>\n");
        return tail(x, "MndtCxlReq");
    }

    private static void mandate(StringBuilder x, CustomerMandate m, String iban, String bic, BigDecimal max,
                                String creditorName, String creditorAccount, String creditorBic) {
        x.append("      <MndtId>").append(esc(m.getMandateReference())).append("</MndtId>\n");
        x.append("      <MndtReqId>").append(esc(m.getRequestId())).append("</MndtReqId>\n");
        x.append("      <Tp><LclInstrm><Prtry>").append(esc(m.getSchemeCode())).append("</Prtry></LclInstrm></Tp>\n");
        x.append("      <Ocrncs>\n");
        x.append("        <SeqTp>").append(m.getMandateType() == CustomerMandate.Type.ONE_OFF ? "OOFF" : "RCUR").append("</SeqTp>\n");
        if (m.getFrequencyPeriod() != null) {
            x.append("        <Frqcy><PrdcAndCnt><Tp>").append(FREQUENCY.get(m.getFrequencyPeriod()))
                    .append("</Tp><CntPerPrd>").append(m.getFrequencyCount()).append("</CntPerPrd></PrdcAndCnt></Frqcy>\n");
        }
        x.append("        <Drtn><FrDt>").append(m.getValidFrom()).append("</FrDt>");
        if (m.getValidTo() != null || m.getPendingValidTo() != null) {
            x.append("<ToDt>").append(m.getPendingValidTo() != null ? m.getPendingValidTo() : m.getValidTo()).append("</ToDt>");
        }
        x.append("</Drtn>\n");
        x.append("      </Ocrncs>\n");
        if (max != null) {
            x.append("      <MaxAmt Ccy=\"").append(esc(m.getCurrency())).append("\">").append(max.toPlainString()).append("</MaxAmt>\n");
        }
        x.append("      <Cdtr><Nm>").append(esc(creditorName)).append("</Nm></Cdtr>\n");
        x.append("      <CdtrAcct><Id><Othr><Id>").append(esc(creditorAccount)).append("</Id></Othr></Id></CdtrAcct>\n");
        x.append("      <CdtrAgt><FinInstnId><BICFI>").append(esc(creditorBic)).append("</BICFI></FinInstnId></CdtrAgt>\n");
        x.append("      <Dbtr><Nm>").append(esc(m.getDebtorName())).append("</Nm></Dbtr>\n");
        x.append("      <DbtrAcct><Id><IBAN>").append(esc(iban)).append("</IBAN></Id></DbtrAcct>\n");
        x.append("      <DbtrAgt><FinInstnId>").append(bic != null ? "<BICFI>" + esc(bic) + "</BICFI>" : "<Othr><Id>NOTPROVIDED</Id></Othr>")
                .append("</FinInstnId></DbtrAgt>\n");
    }

    private static StringBuilder head(String schema, String root, String messageId) {
        StringBuilder x = new StringBuilder();
        x.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        x.append("<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:").append(schema).append("\">\n");
        x.append("  <").append(root).append(">\n");
        x.append("    <GrpHdr><MsgId>").append(esc(messageId)).append("</MsgId><CreDtTm>")
                .append(LocalDateTime.now().withNano(0).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)).append("</CreDtTm></GrpHdr>\n");
        return x;
    }

    private static String tail(StringBuilder x, String root) {
        return x.append("  </").append(root).append(">\n</Document>\n").toString();
    }

    private static String or(String a, String b) {
        return a != null ? a : b;
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }
}
