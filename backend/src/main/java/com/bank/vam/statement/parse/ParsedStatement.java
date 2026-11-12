package com.bank.vam.statement.parse;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * One bank statement or intraday report, whatever format it came in. Balances are signed (debit = negative) and carry
 * the currency's scale; lines carry a positive amount and a credit/debit mark. Account: IBAN when the bank gave one,
 * else account number (+ BIC when the bank gave one).
 */
public record ParsedStatement(
        Format format,
        Kind kind,
        String iban,
        String accountNumber,
        String bic,
        String currency,
        String statementId,        // MT :20:, camt Stmt/Id or Rpt/Id
        Long sequenceNumber,       // MT :28C: statement number, camt ElctrncSeqNb else LglSeqNb
        Integer pageNumber,        // MT :28C: /page, camt StmtPgntn|RptPgntn/PgNb
        LocalDate statementDate,   // end of day: closing booked balance date; intraday: report date
        LocalDateTime reportedAt,  // MT942 :13D:, camt CreDtTm — local time as stated by the bank, offset dropped
        Balance openingBooked,     // MT :60F:/:60M:, camt OPBD (else PRCD)
        Balance closingBooked,     // MT :62F:/:62M:, camt CLBD
        Balance closingAvailable,  // MT :64:, camt CLAV
        Balance interimBooked,     // camt ITBD
        Balance interimAvailable,  // camt ITAV
        Totals debits,             // MT942 :90D:, camt TxsSummry/TtlDbtNtries
        Totals credits,            // MT942 :90C:, camt TxsSummry/TtlCdtNtries
        List<ParsedLine> lines) {

    public enum Format { MT940, MT942, CAMT053, CAMT052 }

    public enum Kind { END_OF_DAY, INTRADAY }

    public record Balance(BigDecimal amount, LocalDate date) {
    }

    public record Totals(int count, BigDecimal sum) {
    }

    /** Filled field by field by the format parsers; {@link #build()} checks what every statement must have. */
    static final class Builder {
        final Format format;
        String iban, accountNumber, bic, currency, statementId;
        Long sequenceNumber;
        Integer pageNumber;
        LocalDateTime reportedAt;
        Balance openingBooked, closingBooked, closingAvailable, interimBooked, interimAvailable;
        Totals debits, credits;
        final List<ParsedLine> lines = new ArrayList<>();

        Builder(Format format) {
            this.format = format;
        }

        Kind kind() {
            return format == Format.MT940 || format == Format.CAMT053 ? Kind.END_OF_DAY : Kind.INTRADAY;
        }

        ParsedStatement build() {
            if (iban == null && accountNumber == null) throw new IllegalArgumentException("no account identification");
            if (currency == null) throw new IllegalArgumentException("no currency");
            Kind kind = kind();
            LocalDate date;
            if (kind == Kind.END_OF_DAY) {
                if (closingBooked == null) throw new IllegalArgumentException("no closing booked balance");
                date = closingBooked.date();
            } else {
                date = reportedAt != null ? reportedAt.toLocalDate()
                        : Stream.of(interimAvailable, interimBooked, closingAvailable, closingBooked)
                        .filter(b -> b != null && b.date() != null).map(Balance::date).findFirst().orElse(null);
            }
            if (date == null) throw new IllegalArgumentException("no statement date");
            return new ParsedStatement(format, kind, iban, accountNumber, bic, currency, statementId, sequenceNumber,
                    pageNumber, date, reportedAt, openingBooked, closingBooked, closingAvailable, interimBooked,
                    interimAvailable, debits, credits, List.copyOf(lines));
        }
    }
}
