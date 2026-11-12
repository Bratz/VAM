package com.bank.vam.statement.parse;

import com.bank.vam.statement.parse.ParseResult.ParseError;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;

/**
 * Reads a bank statement file — MT940 / MT942 (bare or in SWIFT blocks) or camt.053 / camt.052 (any .001.xx version,
 * by element local name) — into {@link ParsedStatement}s. The format comes from the content, not the file name. A
 * statement that cannot be read is reported in {@link ParseResult#errors()} and the others in the file still parse.
 */
public final class StatementParser {

    private StatementParser() {
    }

    public static ParseResult parse(byte[] content, String fileName) {
        if (content == null || content.length == 0) return ParseResult.fileError(fileName, "empty file");
        String text = decode(content);
        if (!text.isEmpty() && text.charAt(0) == 0xFEFF) text = text.substring(1);   // byte order mark
        text = text.strip();
        List<ParsedStatement> statements = new ArrayList<>();
        List<ParseError> errors = new ArrayList<>();
        try {
            if (text.startsWith("<")) CamtParser.parse(content, statements, errors);
            else if (text.contains(":20:")) MtParser.parse(text, statements, errors);
            else return ParseResult.fileError(fileName, "not a statement: neither MT940/MT942 nor camt.052/camt.053");
        } catch (RuntimeException e) {
            return ParseResult.fileError(fileName, message(e));
        }
        if (statements.isEmpty() && errors.isEmpty()) return ParseResult.fileError(fileName, "no statement in the file");
        return new ParseResult(fileName, List.copyOf(statements), List.copyOf(errors));
    }

    /** UTF-8 when the bytes are valid UTF-8, else ISO-8859-1 (what MT files from older bank systems come in). */
    private static String decode(byte[] content) {
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(content)).toString();
        } catch (CharacterCodingException e) {
            return new String(content, StandardCharsets.ISO_8859_1);
        }
    }

    static String message(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.toString();
    }

    /** {@code raw} (comma or point decimal) at {@code ccy}'s scale; more decimals than the currency has is an error. */
    static BigDecimal money(String raw, String ccy) {
        BigDecimal v = new BigDecimal(raw.trim().replace(',', '.'));
        try {
            return v.setScale(Math.max(0, Currency.getInstance(ccy).getDefaultFractionDigits()), RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("amount " + raw + " has more decimals than " + ccy + " allows");
        }
    }
}
