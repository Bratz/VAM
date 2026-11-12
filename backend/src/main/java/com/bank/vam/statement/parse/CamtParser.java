package com.bank.vam.statement.parse;

import com.bank.vam.collections.IsoXml;
import com.bank.vam.statement.parse.ParseResult.ParseError;
import com.bank.vam.statement.parse.ParsedLine.CreditDebit;
import com.bank.vam.statement.parse.ParsedStatement.Balance;
import com.bank.vam.statement.parse.ParsedStatement.Builder;
import com.bank.vam.statement.parse.ParsedStatement.Format;
import com.bank.vam.statement.parse.ParsedStatement.Totals;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.bank.vam.collections.IsoXml.all;
import static com.bank.vam.collections.IsoXml.child;
import static com.bank.vam.collections.IsoXml.direct;

/**
 * camt.053 (BkToCstmrStmt/Stmt) and camt.052 (BkToCstmrAcctRpt/Rpt), read by element local name so any namespace
 * version (.001.02–.001.08 seen) works. Hardened DOM (no DTDs, no external entities) via {@link IsoXml}.
 * ponytail: entry status (BOOK/PDNG/INFO) is not looked at — every Ntry becomes a line; intraday lines are provisional anyway.
 */
final class CamtParser {

    private CamtParser() {
    }

    static void parse(byte[] content, List<ParsedStatement> out, List<ParseError> errors) {
        Document doc = IsoXml.parse(content);
        Format format;
        List<Element> statements;
        if (!all(doc, "BkToCstmrStmt").isEmpty()) {
            format = Format.CAMT053;
            statements = all(doc, "Stmt");
        } else if (!all(doc, "BkToCstmrAcctRpt").isEmpty()) {
            format = Format.CAMT052;
            statements = all(doc, "Rpt");
        } else {
            throw new IllegalArgumentException("XML is neither camt.053 (BkToCstmrStmt) nor camt.052 (BkToCstmrAcctRpt)");
        }
        for (int i = 0; i < statements.size(); i++) {
            Builder b = new Builder(format);
            try {
                statement(statements.get(i), b);
                out.add(b.build());
            } catch (RuntimeException e) {
                errors.add(new ParseError(i + 1, b.statementId, StatementParser.message(e)));
            }
        }
    }

    private static void statement(Element stmt, Builder b) {
        b.statementId = direct(stmt, "Id");
        b.sequenceNumber = number(direct(stmt, "ElctrncSeqNb"));
        if (b.sequenceNumber == null) b.sequenceNumber = number(direct(stmt, "LglSeqNb"));
        Element page = child(stmt, b.format == Format.CAMT053 ? "StmtPgntn" : "RptPgntn", "PgNb");
        Long pageNumber = number(text(page));
        b.pageNumber = pageNumber == null ? null : pageNumber.intValue();
        b.reportedAt = dateTime(direct(stmt, "CreDtTm"));

        Element acct = child(stmt, "Acct");
        if (acct == null) throw new IllegalArgumentException("no Acct");
        b.iban = text(child(acct, "Id", "IBAN"));
        if (b.iban == null) b.accountNumber = text(child(acct, "Id", "Othr", "Id"));
        b.bic = Stream.of(child(acct, "Svcr", "FinInstnId", "BICFI"), child(acct, "Svcr", "FinInstnId", "BIC"))
                .map(CamtParser::text).filter(Objects::nonNull).findFirst().orElse(null);
        b.currency = direct(acct, "Ccy");

        List<Element> balances = all(stmt, "Bal");
        if (b.currency == null && !balances.isEmpty()) b.currency = child(balances.get(0), "Amt").getAttribute("Ccy");
        if (b.currency == null || b.currency.isEmpty()) throw new IllegalArgumentException("no currency (Acct/Ccy or Bal/Amt@Ccy)");
        Balance previousClosing = null;
        for (Element bal : balances) {
            Balance balance = new Balance(signed(amount(child(bal, "Amt"), b.currency), direct(bal, "CdtDbtInd")), date(bal, "Dt"));
            switch (Objects.requireNonNullElse(text(child(bal, "Tp", "CdOrPrtry", "Cd")), "")) {
                case "OPBD" -> b.openingBooked = balance;
                case "PRCD" -> previousClosing = balance;
                case "CLBD" -> b.closingBooked = balance;
                case "CLAV" -> b.closingAvailable = balance;
                case "ITBD" -> b.interimBooked = balance;
                case "ITAV" -> b.interimAvailable = balance;
                default -> {
                    // ponytail: OPAV, FWAV, INFO and proprietary balance types ignored
                }
            }
        }
        if (b.openingBooked == null) b.openingBooked = previousClosing;   // PRCD = yesterday's close = today's opening

        b.credits = totals(child(stmt, "TxsSummry", "TtlCdtNtries"), b.currency);
        b.debits = totals(child(stmt, "TxsSummry", "TtlDbtNtries"), b.currency);
        for (Element ntry : all(stmt, "Ntry")) entry(ntry, b);
    }

    /**
     * One line per entry; a batched entry (several TxDtls, each with its own amount) gives one line per TxDtls.
     * TxDtls/Amt from .001.04, AmtDtls/TxAmt/Amt before.
     */
    private static void entry(Element ntry, Builder b) {
        BigDecimal amount = amount(child(ntry, "Amt"), b.currency);
        CreditDebit cd = "DBIT".equals(direct(ntry, "CdtDbtInd")) ? CreditDebit.D : CreditDebit.C;
        boolean reversal = "true".equalsIgnoreCase(direct(ntry, "RvslInd"));
        LocalDate booking = date(ntry, "BookgDt");
        LocalDate value = date(ntry, "ValDt");
        if (value == null) value = booking;
        if (value == null) throw new IllegalArgumentException("entry without ValDt or BookgDt");
        String bankRef = direct(ntry, "AcctSvcrRef");
        String code = transactionCode(ntry);
        List<Element> txs = all(ntry, "TxDtls");
        if (txs.size() > 1 && txs.stream().allMatch(t -> txAmount(t) != null)) {
            for (Element tx : txs) {
                String txCd = direct(tx, "CdtDbtInd");
                String txCode = transactionCode(tx);
                b.lines.add(new ParsedLine(value, booking, amount(txAmount(tx), b.currency),
                        txCd == null ? cd : "DBIT".equals(txCd) ? CreditDebit.D : CreditDebit.C, reversal,
                        Objects.requireNonNullElse(text(child(tx, "Refs", "AcctSvcrRef")), bankRef),
                        endToEndId(tx), customerReference(tx), remittance(tx, "AddtlTxInf"), txCode != null ? txCode : code));
            }
            return;
        }
        Element only = txs.size() == 1 ? txs.get(0) : null;
        b.lines.add(new ParsedLine(value, booking, amount, cd, reversal, bankRef,
                only == null ? null : endToEndId(only), only == null ? null : customerReference(only),
                remittance(ntry, "AddtlNtryInf"), code));
    }

    private static Element txAmount(Element tx) {
        Element amt = child(tx, "Amt");
        return amt != null ? amt : child(tx, "AmtDtls", "TxAmt", "Amt");
    }

    private static String endToEndId(Element tx) {
        String e2e = text(child(tx, "Refs", "EndToEndId"));
        return "NOTPROVIDED".equals(e2e) ? null : e2e;
    }

    private static String customerReference(Element tx) {
        String ref = text(child(tx, "Refs", "InstrId"));
        return ref != null ? ref : text(child(tx, "Refs", "PmtInfId"));
    }

    /** Every RmtInf/Ustrd and Strd/CdtrRefInf/Ref under {@code scope}, else its additional-information element. */
    private static String remittance(Element scope, String additionalInfo) {
        String text = Stream.concat(all(scope, "Ustrd").stream(), all(scope, "CdtrRefInf").stream().map(r -> child(r, "Ref")))
                .map(CamtParser::text).filter(Objects::nonNull).collect(Collectors.joining(" "));
        return text.isEmpty() ? direct(scope, additionalInfo) : text;
    }

    /** BkTxCd as Domn/Fmly/SubFmly (e.g. PMNT/RCDT/ESCT), else the proprietary code. */
    private static String transactionCode(Element x) {
        Element domain = child(x, "BkTxCd", "Domn");
        if (domain != null) {
            return Stream.of(child(domain, "Cd"), child(domain, "Fmly", "Cd"), child(domain, "Fmly", "SubFmlyCd"))
                    .map(CamtParser::text).filter(Objects::nonNull).collect(Collectors.joining("/"));
        }
        return text(child(x, "BkTxCd", "Prtry", "Cd"));
    }

    private static Totals totals(Element t, String ccy) {
        if (t == null) return null;
        Long count = number(direct(t, "NbOfNtries"));
        String sum = direct(t, "Sum");
        return count == null || sum == null ? null : new Totals(count.intValue(), StatementParser.money(sum, ccy));
    }

    private static BigDecimal amount(Element amt, String ccy) {
        if (amt == null) throw new IllegalArgumentException("missing Amt");
        String amtCcy = amt.getAttribute("Ccy");
        if (!amtCcy.isEmpty() && !amtCcy.equals(ccy)) throw new IllegalArgumentException("amount in " + amtCcy + ", account in " + ccy);
        return StatementParser.money(amt.getTextContent(), ccy);
    }

    private static BigDecimal signed(BigDecimal amount, String cdtDbtInd) {
        return "DBIT".equals(cdtDbtInd) ? amount.negate() : amount;
    }

    /** {@code parent/tag/Dt} or {@code parent/tag/DtTm}; ponytail: date part only, any offset ignored. */
    private static LocalDate date(Element parent, String tag) {
        Element d = child(parent, tag);
        if (d == null) return null;
        String s = Objects.requireNonNullElse(direct(d, "Dt"), Objects.requireNonNullElse(direct(d, "DtTm"), ""));
        return s.length() < 10 ? null : LocalDate.parse(s.substring(0, 10));
    }

    /** ISODateTime to the second; ponytail: fraction and offset dropped, the bank's local time. */
    private static LocalDateTime dateTime(String s) {
        return s == null || s.length() < 19 ? null : LocalDateTime.parse(s.substring(0, 19));
    }

    private static Long number(String s) {
        return s != null && s.matches("\\d{1,18}") ? Long.parseLong(s) : null;
    }

    private static String text(Element e) {
        if (e == null) return null;
        String s = e.getTextContent().strip();
        return s.isEmpty() ? null : s;
    }
}
