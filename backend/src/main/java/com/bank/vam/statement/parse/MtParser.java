package com.bank.vam.statement.parse;

import com.bank.vam.statement.parse.ParseResult.ParseError;
import com.bank.vam.statement.parse.ParsedLine.CreditDebit;
import com.bank.vam.statement.parse.ParsedStatement.Balance;
import com.bank.vam.statement.parse.ParsedStatement.Builder;
import com.bank.vam.statement.parse.ParsedStatement.Format;
import com.bank.vam.statement.parse.ParsedStatement.Totals;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.MonthDay;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MT940 / MT942: SWIFT blocks ({1:}{2:}{4: ... -}) or bare text, several statements per file (one per :20:), CRLF or
 * LF. A field runs from its tag to the next line that starts with a tag, so :61: and :86: may span lines.
 */
final class MtParser {

    private static final Pattern TAG = Pattern.compile("^:(\\w{2,3}):(.*)$");
    private static final Pattern MESSAGE_TYPE = Pattern.compile("\\{2:[IO](\\d{3})");
    private static final Pattern BALANCE = Pattern.compile("([CD])(\\d{6})([A-Z]{3})([\\d,.]+)");
    private static final Pattern LINE = Pattern.compile(
            "(\\d{6})(\\d{4})?(RC|RD|C|D)([A-Z])?(\\d+[,.]?\\d*)([SNF][A-Z0-9]{3})(.*)");
    private static final Pattern DATE_TIME = Pattern.compile("(\\d{10})([+-]\\d{4})?");
    private static final Pattern TOTALS = Pattern.compile("(\\d+)([A-Z]{3})([\\d,.]+)");
    private static final DateTimeFormatter YYMMDD = DateTimeFormatter.ofPattern("yyMMdd");
    private static final DateTimeFormatter YYMMDDHHMM = DateTimeFormatter.ofPattern("yyMMddHHmm");
    private static final Pattern BIC = Pattern.compile("[A-Z]{6}[A-Z0-9]{2}([A-Z0-9]{3})?");
    private static final Pattern IBAN = Pattern.compile("[A-Z]{2}\\d{2}[A-Z0-9]{11,30}");

    private record Field(String tag, String value) {
    }

    private MtParser() {
    }

    static void parse(String text, List<ParsedStatement> out, List<ParseError> errors) {
        text = text.replace("\r\n", "\n").replace('\r', '\n');
        List<List<Field>> statements = new ArrayList<>();
        List<String> types = new ArrayList<>();
        if (text.contains("{4:")) {
            for (String message : text.split("(?=\\{1:)")) {
                int start = message.indexOf("{4:");
                if (start < 0) continue;
                int end = message.indexOf("\n-}", start);
                Matcher type = MESSAGE_TYPE.matcher(message);
                split(message.substring(start + 3, end < 0 ? message.length() : end), type.find() ? type.group(1) : null,
                        statements, types);
            }
        } else {
            split(text, null, statements, types);
        }
        for (int i = 0; i < statements.size(); i++) {
            List<Field> fields = statements.get(i);
            Format format = "942".equals(types.get(i)) || fields.stream().anyMatch(f -> f.tag.startsWith("13") || f.tag.equals("34F"))
                    ? Format.MT942 : Format.MT940;
            Builder b = new Builder(format);
            try {
                statement(fields, b);
                out.add(b.build());
            } catch (RuntimeException e) {
                errors.add(new ParseError(i + 1, b.statementId, StatementParser.message(e)));
            }
        }
    }

    /** The fields of {@code body}, one statement per :20:; lines before the first :20: are ignored. */
    private static void split(String body, String type, List<List<Field>> statements, List<String> types) {
        List<Field> current = null;
        String tag = null;
        StringBuilder value = new StringBuilder();
        for (String line : (body + "\n:20:").split("\n")) {   // sentinel :20: flushes the last field
            Matcher m = TAG.matcher(line);
            if (!m.matches()) {
                if (tag != null && !line.isBlank() && !line.strip().equals("-")) value.append('\n').append(line);
                continue;
            }
            if (tag != null && current != null) current.add(new Field(tag, value.toString().strip()));
            tag = m.group(1);
            value = new StringBuilder(m.group(2));
            if (tag.equals("20")) {
                if (current != null) {
                    statements.add(current);
                    types.add(type);
                }
                current = new ArrayList<>();
            }
        }
    }

    private static void statement(List<Field> fields, Builder b) {
        List<String[]> rawLines = new ArrayList<>();   // {:61:, :86: or null}, built once the currency is known
        String previous = null;
        for (Field f : fields) {
            String v = f.value;
            switch (f.tag) {
                case "20" -> b.statementId = v;
                case "25" -> account(b, v);
                case "28C", "28" -> sequence(b, v);
                case "60F", "60M", "60" -> b.openingBooked = balance(b, v);
                case "62F", "62M", "62" -> b.closingBooked = balance(b, v);
                case "64" -> b.closingAvailable = balance(b, v);
                case "13D", "13" -> b.reportedAt = dateTime(v);
                case "34F" -> {   // floor limit, not a balance: only its currency is used (an MT942 has no :60F:)
                    if (b.currency == null) b.currency = v.substring(0, 3);
                }
                case "90D" -> b.debits = totals(b, v);
                case "90C" -> b.credits = totals(b, v);
                case "61" -> rawLines.add(new String[]{v, null});
                case "86" -> {   // ponytail: an :86: not right after a :61: is statement-level information — ignored
                    if ("61".equals(previous)) rawLines.get(rawLines.size() - 1)[1] = v;
                }
                default -> {
                    // ponytail: :21: related reference, :65: forward available balance and bank-specific tags (:NS: ...) ignored
                }
            }
            previous = f.tag;
        }
        if (b.currency == null) throw new IllegalArgumentException("no currency (:60F:, :62F: or :34F:)");
        for (String[] raw : rawLines) b.lines.add(line(raw[0], raw[1], b.currency));
    }

    /**
     * :25: is an IBAN, "BIC/account", "bank code/account" or an account number, sometimes followed by " CCY".
     * ponytail: a domestic bank code before "/" (e.g. German BLZ) is dropped, matching is by account number then.
     */
    private static void account(Builder b, String v) {
        String s = v.strip().replaceFirst("\\s+[A-Z]{3}$", "");
        int slash = s.indexOf('/');
        if (slash > 0) {
            String left = s.substring(0, slash).strip();
            if (BIC.matcher(left).matches()) b.bic = left;
            s = s.substring(slash + 1);
        }
        String compact = s.replaceAll("\\s+", "").toUpperCase();
        if (isIban(compact)) b.iban = compact;
        else b.accountNumber = s.strip();
        // ponytail: an account number with the currency glued on ("0532013000EUR") is kept as is
    }

    /** Shape and ISO 13616 mod-97 check digits. */
    private static boolean isIban(String s) {
        if (!IBAN.matcher(s).matches()) return false;
        StringBuilder digits = new StringBuilder();
        for (char c : (s.substring(4) + s.substring(0, 4)).toCharArray()) digits.append(Character.getNumericValue(c));
        return new BigInteger(digits.toString()).mod(BigInteger.valueOf(97)).intValue() == 1;
    }

    /** :28C: statement number[/page]; a non-numeric value is left out rather than failing the statement. */
    private static void sequence(Builder b, String v) {
        String[] parts = v.strip().split("/");
        if (parts[0].matches("\\d{1,18}")) b.sequenceNumber = Long.parseLong(parts[0]);
        if (parts.length > 1 && parts[1].matches("\\d{1,9}")) b.pageNumber = Integer.parseInt(parts[1]);
    }

    private static Balance balance(Builder b, String v) {
        Matcher m = BALANCE.matcher(v.strip());
        if (!m.matches()) throw new IllegalArgumentException("unreadable balance: " + v);
        String ccy = m.group(3);
        if (b.currency == null) b.currency = ccy;
        else if (!b.currency.equals(ccy)) throw new IllegalArgumentException("balance in " + ccy + ", statement in " + b.currency);
        BigDecimal amount = StatementParser.money(m.group(4), ccy);
        return new Balance(m.group(1).equals("D") ? amount.negate() : amount, LocalDate.parse(m.group(2), YYMMDD));
    }

    private static Totals totals(Builder b, String v) {
        Matcher m = TOTALS.matcher(v.strip());
        if (!m.matches()) throw new IllegalArgumentException("unreadable :90: " + v);
        if (b.currency == null) b.currency = m.group(2);
        return new Totals(Integer.parseInt(m.group(1)), StatementParser.money(m.group(3), m.group(2)));
    }

    /** :13D: YYMMDDhhmm[+hhmm]; ponytail: the offset is dropped, the time is the bank's local time. */
    private static LocalDateTime dateTime(String v) {
        Matcher m = DATE_TIME.matcher(v.strip());
        if (!m.matches()) throw new IllegalArgumentException("unreadable :13D: " + v);
        return LocalDateTime.parse(m.group(1), YYMMDDHHMM);
    }

    /**
     * :61: value date YYMMDD, [entry date MMDD], C/D/RC/RD, [funds code], amount, type (S/N/F + 3), customer reference
     * [//bank reference]; a second line is supplementary details, used as remittance when there is no :86:.
     */
    private static ParsedLine line(String f61, String f86, String ccy) {
        String[] rows = f61.split("\n", 2);
        Matcher m = LINE.matcher(rows[0].strip());
        if (!m.matches()) throw new IllegalArgumentException("unreadable :61: " + rows[0]);
        LocalDate valueDate = LocalDate.parse(m.group(1), YYMMDD);
        LocalDate bookingDate = m.group(2) == null ? null : entryDate(valueDate, m.group(2));
        String mark = m.group(3);
        String rest = m.group(7);
        int slashes = rest.indexOf("//");
        String customerRef = blankToNull(slashes < 0 ? rest : rest.substring(0, slashes));
        if ("NONREF".equals(customerRef)) customerRef = null;
        String bankRef = slashes < 0 ? null : blankToNull(rest.substring(slashes + 2));
        String supplementary = rows.length > 1 ? blankToNull(rows[1].replace('\n', ' ')) : null;
        Mt86.Info info = f86 == null ? new Mt86.Info(null, null, null) : Mt86.parse(f86);
        return new ParsedLine(valueDate, bookingDate, StatementParser.money(m.group(5), ccy),
                mark.equals("C") || mark.equals("RD") ? CreditDebit.C : CreditDebit.D,   // RC = reversed credit = debit
                mark.startsWith("R"), bankRef, info.endToEndId(),
                customerRef != null ? customerRef : info.customerReference(),
                info.remittance() != null ? info.remittance() : supplementary, m.group(6));
    }

    /**
     * ponytail: the entry date has no year — take the value date's, moved a year when that lands more than six months
     * away (value 31 Dec, entry 02 Jan = next year). Wrong only for an entry booked half a year from its value date.
     */
    static LocalDate entryDate(LocalDate valueDate, String mmdd) {
        LocalDate d = MonthDay.of(Integer.parseInt(mmdd.substring(0, 2)), Integer.parseInt(mmdd.substring(2))).atYear(valueDate.getYear());
        long days = ChronoUnit.DAYS.between(valueDate, d);
        return days > 183 ? d.minusYears(1) : days < -183 ? d.plusYears(1) : d;
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
