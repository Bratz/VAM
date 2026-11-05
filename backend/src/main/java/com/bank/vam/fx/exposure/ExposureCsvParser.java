package com.bank.vam.fx.exposure;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Reads the upload: {@code kind,currency,amount,valueDate[,reference[,description]]}. The whole
 * file is rejected if any row is bad, so a corporate never ends up with half a forecast loaded.
 */
final class ExposureCsvParser {

    static final int MAX_ROWS = 5000;
    private static final int MAX_ERRORS = 20;

    record Result(List<FxExposureEntry> entries, List<String> errors) {
    }

    private ExposureCsvParser() {
    }

    static Result parse(Reader reader, UUID corporateId) throws IOException {
        List<FxExposureEntry> entries = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        BufferedReader in = new BufferedReader(reader);

        String headerLine = in.readLine();
        if (headerLine == null) {
            return new Result(entries, List.of("The file is empty"));
        }
        List<String> header = split(headerLine.replace("﻿", "")).stream().map(h -> h.trim().toLowerCase(Locale.ROOT)).toList();
        for (String required : List.of("kind", "currency", "amount", "valuedate")) {
            if (!header.contains(required)) {
                return new Result(entries, List.of("Header must include kind, currency, amount, valueDate (missing " + required + ")"));
            }
        }

        String line;
        int lineNo = 1;
        while ((line = in.readLine()) != null) {
            lineNo++;
            if (line.isBlank()) {
                continue;
            }
            if (entries.size() >= MAX_ROWS) {
                errors.add("More than " + MAX_ROWS + " rows; split the file");
                break;
            }
            try {
                entries.add(row(split(line), header, corporateId));
            } catch (IllegalArgumentException | DateTimeParseException e) {
                if (errors.size() < MAX_ERRORS) {
                    errors.add("Line " + lineNo + ": " + e.getMessage());
                }
            }
        }
        if (entries.isEmpty() && errors.isEmpty()) {
            errors.add("The file has a header but no rows");
        }
        return new Result(entries, errors);
    }

    private static FxExposureEntry row(List<String> cells, List<String> header, UUID corporateId) {
        FxExposureEntry e = new FxExposureEntry();
        e.setCorporateId(corporateId);
        String kind = cell(cells, header, "kind").toUpperCase(Locale.ROOT);
        if (!kind.equals("EXPOSURE") && !kind.equals("HEDGE")) {
            throw new IllegalArgumentException("kind must be EXPOSURE or HEDGE: '" + kind + "'");
        }
        e.setKind(FxExposureEntry.Kind.valueOf(kind));

        String currency = cell(cells, header, "currency").toUpperCase(Locale.ROOT);
        if (!currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("currency must be a 3-letter code: '" + currency + "'");
        }
        e.setCurrency(currency);

        BigDecimal amount;
        try {
            amount = new BigDecimal(cell(cells, header, "amount"));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("amount is not a number: '" + cell(cells, header, "amount") + "'");
        }
        if (amount.signum() == 0) {
            throw new IllegalArgumentException("amount must not be zero");
        }
        e.setAmount(amount.setScale(2, java.math.RoundingMode.HALF_UP));

        e.setValueDate(LocalDate.parse(cell(cells, header, "valuedate")));

        String reference = cell(cells, header, "reference");
        String description = cell(cells, header, "description");
        if (reference.length() > 100 || description.length() > 200) {
            throw new IllegalArgumentException("reference is limited to 100 characters and description to 200");
        }
        e.setReference(reference.isEmpty() ? null : reference);
        e.setDescription(description.isEmpty() ? null : description);
        return e;
    }

    /** A missing optional column reads as empty; a missing required value throws with the column name. */
    private static String cell(List<String> cells, List<String> header, String column) {
        int i = header.indexOf(column);
        String v = i >= 0 && i < cells.size() ? cells.get(i).trim() : "";
        if (v.isEmpty() && List.of("kind", "currency", "amount", "valuedate").contains(column)) {
            throw new IllegalArgumentException(column + " is empty");
        }
        return v;
    }

    /** Comma-separated with double-quoted fields and "" as an escaped quote. */
    static List<String> split(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }
}
