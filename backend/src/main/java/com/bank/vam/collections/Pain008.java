package com.bank.vam.collections;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * pain.008.001.08 (Customer Direct Debit Initiation) for a run: one message, one PmtInf per receiving account,
 * scheme, collection date, currency and sequence type — the fields a PmtInf fixes for all its transactions.
 * Scheme-neutral: the scheme is a proprietary local instrument.
 */
final class Pain008 {

    private Pain008() {
    }

    /** {@code accountNumbers}: receiving account id → its number, used as the creditor account. */
    static String build(String messageId, String creditorName, String creditorBic, List<CollectionLine> debits,
                        Map<UUID, String> accountNumbers, Map<UUID, LocalDate> signedDates) {
        Map<List<Object>, List<CollectionLine>> groups = debits.stream().collect(Collectors.groupingBy(
                l -> List.<Object>of(l.getCreditorVaId(), l.getSchemeCode(), l.getCollectionDate(), l.getCurrency(), l.getSequenceType()),
                LinkedHashMap::new, Collectors.toList()));

        StringBuilder x = new StringBuilder();
        x.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        x.append("<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:pain.008.001.08\">\n");
        x.append("  <CstmrDrctDbtInitn>\n");
        x.append("    <GrpHdr>\n");
        tag(x, 6, "MsgId", messageId);
        tag(x, 6, "CreDtTm", LocalDateTime.now().withNano(0).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        tag(x, 6, "NbOfTxs", String.valueOf(debits.size()));
        tag(x, 6, "CtrlSum", sum(debits).toPlainString());
        x.append("      <InitgPty>\n");
        tag(x, 8, "Nm", creditorName);
        x.append("      </InitgPty>\n");
        x.append("    </GrpHdr>\n");

        int n = 0;
        for (List<CollectionLine> group : groups.values()) {
            CollectionLine first = group.get(0);
            x.append("    <PmtInf>\n");
            tag(x, 6, "PmtInfId", messageId + "-" + (++n));
            tag(x, 6, "PmtMtd", "DD");
            tag(x, 6, "NbOfTxs", String.valueOf(group.size()));
            tag(x, 6, "CtrlSum", sum(group).toPlainString());
            x.append("      <PmtTpInf>\n");
            x.append("        <LclInstrm>\n");
            tag(x, 10, "Prtry", first.getSchemeCode());
            x.append("        </LclInstrm>\n");
            tag(x, 8, "SeqTp", first.getSequenceType());
            x.append("      </PmtTpInf>\n");
            tag(x, 6, "ReqdColltnDt", first.getCollectionDate().toString());
            x.append("      <Cdtr>\n");
            tag(x, 8, "Nm", creditorName);
            x.append("      </Cdtr>\n");
            x.append("      <CdtrAcct>\n        <Id>\n          <Othr>\n");
            tag(x, 12, "Id", accountNumbers.getOrDefault(first.getCreditorVaId(), String.valueOf(first.getCreditorVaId())));
            x.append("          </Othr>\n        </Id>\n");
            tag(x, 8, "Ccy", first.getCurrency());
            x.append("      </CdtrAcct>\n");
            x.append("      <CdtrAgt>\n        <FinInstnId>\n");
            tag(x, 10, "BICFI", creditorBic);
            x.append("        </FinInstnId>\n      </CdtrAgt>\n");
            for (CollectionLine l : group) {
                x.append("      <DrctDbtTxInf>\n");
                x.append("        <PmtId>\n");
                tag(x, 10, "EndToEndId", l.getEndToEndId());
                x.append("        </PmtId>\n");
                x.append("        <InstdAmt Ccy=\"").append(esc(l.getCurrency())).append("\">").append(l.getAmount().toPlainString()).append("</InstdAmt>\n");
                x.append("        <DrctDbtTx>\n          <MndtRltdInf>\n");
                tag(x, 12, "MndtId", l.getMandateReference());
                LocalDate signed = signedDates.get(l.getMandateId());
                if (signed != null) tag(x, 12, "DtOfSgntr", signed.toString());
                x.append("          </MndtRltdInf>\n        </DrctDbtTx>\n");
                x.append("        <DbtrAgt>\n          <FinInstnId>\n");
                if (l.getDebtorBic() != null) {
                    tag(x, 12, "BICFI", l.getDebtorBic());
                } else {
                    x.append("            <Othr>\n");
                    tag(x, 14, "Id", "NOTPROVIDED");
                    x.append("            </Othr>\n");
                }
                x.append("          </FinInstnId>\n        </DbtrAgt>\n");
                x.append("        <Dbtr>\n");
                tag(x, 10, "Nm", l.getDebtorName());
                x.append("        </Dbtr>\n");
                x.append("        <DbtrAcct>\n          <Id>\n");
                tag(x, 12, "IBAN", l.getDebtorIban());
                x.append("          </Id>\n        </DbtrAcct>\n");
                x.append("        <RmtInf>\n");
                tag(x, 10, "Ustrd", l.getReceivableNumber());
                x.append("        </RmtInf>\n");
                x.append("      </DrctDbtTxInf>\n");
            }
            x.append("    </PmtInf>\n");
        }
        x.append("  </CstmrDrctDbtInitn>\n");
        x.append("</Document>\n");
        return x.toString();
    }

    private static BigDecimal sum(List<CollectionLine> lines) {
        return lines.stream().map(CollectionLine::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static void tag(StringBuilder x, int indent, String name, String value) {
        x.append(" ".repeat(indent)).append('<').append(name).append('>').append(esc(value)).append("</").append(name).append(">\n");
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }
}
