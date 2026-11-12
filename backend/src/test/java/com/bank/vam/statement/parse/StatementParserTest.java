package com.bank.vam.statement.parse;

import com.bank.vam.statement.parse.ParseResult.ParseError;
import com.bank.vam.statement.parse.ParsedLine.CreditDebit;
import com.bank.vam.statement.parse.ParsedStatement.Balance;
import com.bank.vam.statement.parse.ParsedStatement.Format;
import com.bank.vam.statement.parse.ParsedStatement.Kind;
import com.bank.vam.statement.parse.ParsedStatement.Totals;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static com.bank.vam.statement.parse.ParsedLine.CreditDebit.C;
import static com.bank.vam.statement.parse.ParsedLine.CreditDebit.D;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Golden normalised output for each sample under src/test/resources/statements. */
class StatementParserTest {

    private static byte[] file(String name) throws Exception {
        try (InputStream in = StatementParserTest.class.getResourceAsStream("/statements/" + name)) {
            return in.readAllBytes();
        }
    }

    private static ParseResult parse(String name) throws Exception {
        return StatementParser.parse(file(name), name);
    }

    private static ParsedStatement only(ParseResult r) {
        assertEquals(List.of(), r.errors());
        assertEquals(1, r.statements().size());
        return r.statements().get(0);
    }

    private static Balance bal(String amount, String date) {
        return new Balance(new BigDecimal(amount), LocalDate.parse(date));
    }

    private static ParsedLine line(String value, String booking, String amount, CreditDebit cd, boolean reversal, String bankRef,
                                   String e2e, String customerRef, String remittance, String code) {
        return new ParsedLine(LocalDate.parse(value), booking == null ? null : LocalDate.parse(booking), new BigDecimal(amount),
                cd, reversal, bankRef, e2e, customerRef, remittance, code);
    }

    private static void account(ParsedStatement s, String iban, String accountNumber, String bic, String ccy) {
        assertEquals(iban, s.iban());
        assertEquals(accountNumber, s.accountNumber());
        assertEquals(bic, s.bic());
        assertEquals(ccy, s.currency());
    }

    @Test
    void mt940SingleBare() throws Exception {
        ParsedStatement s = only(parse("mt940-single.sta"));
        assertEquals(Format.MT940, s.format());
        assertEquals(Kind.END_OF_DAY, s.kind());
        account(s, "GB07LOYD30963412345678", null, null, "GBP");
        assertEquals("STMT261111GBP", s.statementId());
        assertEquals(312L, s.sequenceNumber());
        assertEquals(1, s.pageNumber());
        assertEquals(LocalDate.of(2026, 11, 11), s.statementDate());
        assertEquals(bal("125000.00", "2026-11-10"), s.openingBooked());
        assertEquals(bal("135330.25", "2026-11-11"), s.closingBooked());
        assertEquals(bal("134830.25", "2026-11-11"), s.closingAvailable());
        assertNull(s.interimBooked());
        assertEquals(List.of(
                line("2026-11-11", "2026-11-11", "15250.75", C, false, "LL2611110001", null, "INV-2026-0441",
                        "Helios Trading Ltd payment for INV-2026-0441", "NTRF"),
                line("2026-11-11", "2026-11-11", "4800.00", D, false, "LL2611110002", "E2E-PAY-20261111-01", "PAYROLL-NOV",
                        "November payroll batch 11", "NTRF"),
                line("2026-11-11", null, "120.50", D, false, "LL2611110003", null, null, "Account maintenance fee", "NCHG")
        ), s.lines());
    }

    @Test
    void mt940EnvelopeWithTwoAccountsCrlf() throws Exception {
        // git may check the sample out with LF; force CRLF so this test always covers it
        String crlf = new String(file("mt940-envelope-two-accounts.sta"), StandardCharsets.US_ASCII)
                .replace("\r\n", "\n").replace("\n", "\r\n");
        ParseResult r = StatementParser.parse(crlf.getBytes(StandardCharsets.US_ASCII), "two.sta");
        assertEquals(List.of(), r.errors());
        assertEquals(2, r.statements().size());

        ParsedStatement gbp = r.statements().get(0);
        assertEquals(Format.MT940, gbp.format());
        account(gbp, "GB46LOYD30963487654321", null, "LOYDGB2L", "GBP");
        assertEquals("LL940-20261111", gbp.statementId());
        assertEquals(45L, gbp.sequenceNumber());
        assertEquals(1, gbp.pageNumber());
        assertEquals(bal("52500.00", "2026-11-11"), gbp.closingBooked());
        assertNull(gbp.closingAvailable());
        assertEquals(List.of(line("2026-11-11", "2026-11-11", "2500.00", C, false, "LL9900001", null, null,
                "Mercator GmbH settlement", "NTRF")), gbp.lines());

        ParsedStatement usd = r.statements().get(1);
        account(usd, null, "000123456789", "CHASUS33", "USD");
        assertEquals("CH940-20261111", usd.statementId());
        assertEquals(78L, usd.sequenceNumber());
        assertEquals(bal("-1500.00", "2026-11-10"), usd.openingBooked());
        assertEquals(bal("8500.00", "2026-11-11"), usd.closingBooked());
        assertEquals(bal("8400.00", "2026-11-11"), usd.closingAvailable());
        assertEquals(List.of(line("2026-11-11", null, "10000.00", C, false, "CH20261111A1", "E2E-US-7781", "CUST-REF-77",
                "INCOMING WIRE FROM ALBION HOLDINGS INC EREF+E2E-US-7781 SVWZ+INVOICE 7781", "S103")), usd.lines());
    }

    @Test
    void mt940GermanStructured86InLatin1() throws Exception {
        ParsedStatement s = only(parse("mt940-german-structured.sta"));
        account(s, null, "0123456789", null, "EUR");   // BLZ/Konto: the bank code is dropped
        assertEquals(211L, s.sequenceNumber());
        assertEquals(LocalDate.of(2026, 11, 12), s.statementDate());
        assertEquals(bal("85150.40", "2026-11-12"), s.closingBooked());
        assertEquals(List.of(
                // funds code R after C; supplementary /OCMT/ line ignored because :86: is there
                line("2026-11-11", "2026-11-11", "1250.00", C, false, "DB0000123", "RE-2026-0815-E2E", "SAMMLER-0815",
                        "SEPA-GUTSCHRIFT Rechnung 2026-0815 vom 02.11.2026 Kühne Logistik GmbH", "NTRF"),
                line("2026-11-12", "2026-11-12", "310.00", D, false, "DB0000124", null, null,
                        "SEPA-LASTSCHRIFT Strom November 2026 Stadtwerke Frankfurt AG", "NDDT")
        ), s.lines());
    }

    @Test
    void mt940EntryDateAcrossYearEndAndReversals() throws Exception {
        ParsedStatement s = only(parse("mt940-year-end-reversal.sta"));
        account(s, "DE94500700100123456789", null, null, "EUR");   // trailing " EUR" after the IBAN dropped
        assertEquals(LocalDate.of(2026, 12, 31), s.statementDate());
        assertEquals(bal("10475.25", "2026-12-31"), s.closingBooked());
        assertEquals(List.of(
                line("2026-12-31", "2027-01-02", "500.00", C, false, "DB7001", null, null, "Year-end credit booked on 2 January", "NTRF"),
                line("2026-12-31", "2026-12-31", "200.00", D, true, "DB7002", null, null, "Reversal of credit DB6999", "NTRF"),
                line("2026-12-31", "2026-12-31", "75.25", C, true, "DB7003", null, null, "Reversal of fee charged twice", "NCHG"),
                line("2027-01-02", "2026-12-31", "100.00", C, false, "DB7004", null, null, "Forward-value credit", "NTRF")
        ), s.lines());
    }

    @Test
    void mt942Intraday() throws Exception {
        ParsedStatement s = only(parse("mt942-intraday.sta"));
        assertEquals(Format.MT942, s.format());
        assertEquals(Kind.INTRADAY, s.kind());
        account(s, "GB07LOYD30963412345678", null, "LOYDGB2L", "GBP");   // currency from :34F:, no balance tags
        assertEquals(313L, s.sequenceNumber());
        assertEquals(2, s.pageNumber());
        assertEquals(LocalDateTime.of(2026, 11, 12, 10, 30), s.reportedAt());
        assertEquals(LocalDate.of(2026, 11, 12), s.statementDate());
        assertNull(s.openingBooked());
        assertNull(s.closingBooked());
        assertEquals(new Totals(1, new BigDecimal("1250.40")), s.debits());
        assertEquals(new Totals(1, new BigDecimal("7300.00")), s.credits());
        assertEquals(List.of(
                line("2026-11-12", "2026-11-12", "7300.00", C, false, "LL2611120001", null, "INV-2026-0502",
                        "Helios Trading Ltd INV-2026-0502", "NTRF"),
                line("2026-11-12", "2026-11-12", "1250.40", D, false, "LL2611120002", "E2E-SUP-889", "SUPP-889",
                        "Supplier 889 payment", "NTRF")
        ), s.lines());
    }

    @Test
    void camt053v02BatchedEntrySplitByTxAmount() throws Exception {
        ParsedStatement s = only(parse("camt053-v02.xml"));
        assertEquals(Format.CAMT053, s.format());
        assertEquals(Kind.END_OF_DAY, s.kind());
        account(s, "DE94500700100123456789", null, "DEUTDEFF", "EUR");
        assertEquals("DB053-20261111-DE94", s.statementId());
        assertEquals(315L, s.sequenceNumber());
        assertNull(s.pageNumber());
        assertEquals(LocalDateTime.of(2026, 11, 11, 22, 15), s.reportedAt());
        assertEquals(LocalDate.of(2026, 11, 11), s.statementDate());
        assertEquals(bal("250000.00", "2026-11-10"), s.openingBooked());
        assertEquals(bal("262450.00", "2026-11-11"), s.closingBooked());
        assertEquals(bal("262000.00", "2026-11-11"), s.closingAvailable());
        assertEquals(new Totals(1, new BigDecimal("15000.00")), s.credits());
        assertEquals(new Totals(1, new BigDecimal("2550.00")), s.debits());
        assertEquals(List.of(
                line("2026-11-11", "2026-11-11", "15000.00", C, false, "DB-053-0001", "E2E-MERC-0815", "INSTR-0815",
                        "Kuehne Logistik invoice 0815 RF18539007547034", "PMNT/RCDT/ESCT"),
                line("2026-11-11", "2026-11-11", "1800.00", D, false, "DB-053-0002", "E2E-PAY-1", "BATCH-2026-11-11",
                        "Salary advance A. Novak", "PMNT/ICDT/ESCT"),
                line("2026-11-11", "2026-11-11", "750.00", D, false, "DB-053-0002", "E2E-PAY-2", "BATCH-2026-11-11",
                        "Supplier 4471", "PMNT/ICDT/ESCT")
        ), s.lines());
    }

    @Test
    void camt053v08TwoStatementsPrefixedNamespace() throws Exception {
        ParseResult r = parse("camt053-v08-two-accounts.xml");
        assertEquals(List.of(), r.errors());
        assertEquals(2, r.statements().size());

        ParsedStatement gbp = r.statements().get(0);
        account(gbp, "GB07LOYD30963412345678", null, "LOYDGB2L", "GBP");
        assertEquals(312L, gbp.sequenceNumber());
        assertEquals(1, gbp.pageNumber());
        assertEquals(LocalDateTime.of(2026, 11, 11, 23, 5, 12), gbp.reportedAt());
        assertEquals(bal("125000.00", "2026-11-10"), gbp.openingBooked());
        assertEquals(bal("127600.00", "2026-11-11"), gbp.closingBooked());   // not the CdtLine amount
        assertEquals(bal("127500.00", "2026-11-11"), gbp.closingAvailable());
        assertEquals(List.of(
                line("2026-11-11", "2026-11-11", "1000.00", C, false, "LL053-1-1", "E2E-HEL-1", null,
                        "Helios Trading INV-2026-0450", "PMNT/RCDT/BOOK"),
                line("2026-11-11", "2026-11-11", "2000.00", C, false, "LL053-1-2", null, null,
                        "Helios Trading INV-2026-0451", "PMNT/RCDT/BOOK"),
                line("2026-11-10", "2026-11-11", "400.00", D, true, "LL053-2", null, null, "Reversal of credit LL052-9", "NRVS")
        ), gbp.lines());

        ParsedStatement usd = r.statements().get(1);
        account(usd, null, "000123456789", "CHASUS33", "USD");
        assertEquals("CH053-20261111", usd.statementId());
        assertEquals(78L, usd.sequenceNumber());
        assertEquals(bal("-1500.00", "2026-11-10"), usd.openingBooked());   // PRCD stands in for OPBD
        assertEquals(bal("8500.00", "2026-11-11"), usd.closingBooked());
        assertEquals(List.of(line("2026-11-11", "2026-11-11", "10000.00", C, false, "CH20261111A1", "E2E-US-7781", "PMTINF-77",
                "INVOICE 7781", null)), usd.lines());
    }

    @Test
    void camt052IntradayWithInterimBalances() throws Exception {
        ParsedStatement s = only(parse("camt052-intraday.xml"));
        assertEquals(Format.CAMT052, s.format());
        assertEquals(Kind.INTRADAY, s.kind());
        account(s, "DE94500700100123456789", null, "DEUTDEFF", "EUR");
        assertEquals("DB052-20261112-1405-DE94", s.statementId());
        assertEquals(4L, s.sequenceNumber());
        assertEquals(1, s.pageNumber());
        assertEquals(LocalDateTime.of(2026, 11, 12, 14, 5), s.reportedAt());
        assertEquals(LocalDate.of(2026, 11, 12), s.statementDate());
        assertEquals(bal("262950.00", "2026-11-12"), s.interimBooked());
        assertEquals(bal("262400.00", "2026-11-12"), s.interimAvailable());
        assertNull(s.closingBooked());
        assertEquals(List.of(line("2026-11-12", null, "500.00", C, false, "DB052-1", "E2E-INTRA-1", null,
                "Kuehne Logistik advance", null)), s.lines());
    }

    @Test
    void brokenStatementsAreReportedAndTheOthersStillParse() throws Exception {
        ParseResult r = parse("mt940-one-broken.sta");
        assertEquals(List.of("LL940-A", "LL940-D"), r.statements().stream().map(ParsedStatement::statementId).toList());
        assertEquals(2, r.errors().size());
        ParseError bad61 = r.errors().get(0);
        assertEquals(2, bad61.statementIndex());
        assertEquals("LL940-B", bad61.statementId());
        assertTrue(bad61.message().startsWith("unreadable :61:"), bad61.message());
        assertEquals(new ParseError(3, "LL940-C", "no closing booked balance"), r.errors().get(1));
    }

    @Test
    void brokenCamtStatementAmongGoodOnes() {
        String xml = """
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:camt.053.001.04"><BkToCstmrStmt>
                <Stmt><Id>NO-ACCT</Id><Bal><Tp><CdOrPrtry><Cd>CLBD</Cd></CdOrPrtry></Tp><Amt Ccy="EUR">1.00</Amt>
                  <CdtDbtInd>CRDT</CdtDbtInd><Dt><Dt>2026-11-11</Dt></Dt></Bal></Stmt>
                <Stmt><Id>OK</Id><Acct><Id><IBAN>DE94500700100123456789</IBAN></Id></Acct>
                  <Bal><Tp><CdOrPrtry><Cd>CLBD</Cd></CdOrPrtry></Tp><Amt Ccy="EUR">1.5</Amt>
                  <CdtDbtInd>DBIT</CdtDbtInd><Dt><Dt>2026-11-11</Dt></Dt></Bal></Stmt>
                </BkToCstmrStmt></Document>""";
        ParseResult r = StatementParser.parse(xml.getBytes(StandardCharsets.UTF_8), "x.xml");
        assertEquals(List.of(new ParseError(1, "NO-ACCT", "no Acct")), r.errors());
        ParsedStatement ok = r.statements().get(0);
        assertEquals("EUR", ok.currency());   // from Bal/Amt@Ccy when Acct/Ccy is missing
        assertEquals(bal("-1.50", "2026-11-11"), ok.closingBooked());
    }

    @Test
    void unreadableFilesAreFileLevelErrors() {
        assertFileError("", "empty file");
        assertFileError("hello", "not a statement");
        assertFileError("<Document><Other/></Document>", "XML is neither camt.053");
        // DTDs refused outright (no XXE)
        assertFileError("<?xml version=\"1.0\"?><!DOCTYPE d [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
                + "<Document><BkToCstmrStmt>&x;</BkToCstmrStmt></Document>", "Not a readable ISO 20022 message");
        assertStatementError(":20:X\n:25:GB07LOYD30963412345678\n:62F:C261111GBP1,001\n", "amount 1,001 has more decimals than GBP allows", 1);
    }

    private static void assertFileError(String content, String messageStart) {
        ParseResult r = StatementParser.parse(content.getBytes(StandardCharsets.UTF_8), "f");
        assertEquals(List.of(), r.statements());
        assertEquals(1, r.errors().size());
        assertNull(r.errors().get(0).statementIndex());
        assertTrue(r.errors().get(0).message().startsWith(messageStart), r.errors().get(0).message());
    }

    private static void assertStatementError(String content, String messageStart, int statementIndex) {
        ParseResult r = StatementParser.parse(content.getBytes(StandardCharsets.UTF_8), "f");
        assertEquals(statementIndex, r.errors().get(0).statementIndex());
        assertTrue(r.errors().get(0).message().startsWith(messageStart), r.errors().get(0).message());
    }
}
