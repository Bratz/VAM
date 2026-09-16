package com.bank.vam.service.fileingest;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The one hand-written, known CSV shape this app recognizes without any
 * agent involvement — proves the staging/reconciliation/quarantine/
 * processing pipeline end to end for a shape that needs no help. Anything
 * else falls through to the ticket-and-wait path (IngestOrchestrator).
 *
 * <p>Known format: a CSV with header
 * {@code amount,currency,viban,debtorName,debtorAccount,remittanceInfo,reference}.
 */
@Component
public class ReceivablesCsvTransform {

    private static final String EXPECTED_HEADER =
            "amount,currency,viban,debtorName,debtorAccount,remittanceInfo,reference";

    public TransformOutput transform(Path sourceFile) {
        List<String> lines;
        try {
            lines = Files.readAllLines(sourceFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read source file " + sourceFile, e);
        }
        if (lines.isEmpty()) {
            return new TransformOutput(0, BigDecimal.ZERO, List.of());
        }
        String header = lines.get(0).strip();
        if (!EXPECTED_HEADER.equalsIgnoreCase(header)) {
            throw new IllegalArgumentException(
                    "Unrecognized format — expected header \"" + EXPECTED_HEADER + "\", got \"" + header + "\". "
                            + "(This hand-written transform only understands this one shape; anything else is "
                            + "escalated to the file-ingest-agent-service worker via a Jira ticket.)");
        }

        List<TransformedRow> rows = new ArrayList<>();
        BigDecimal sourceTotal = BigDecimal.ZERO;
        int sourceRowCount = 0;
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.isEmpty()) {
                continue;
            }
            sourceRowCount++;
            String[] cols = line.split(",", -1);
            BigDecimal amount = new BigDecimal(cols[0].strip());
            sourceTotal = sourceTotal.add(amount);
            rows.add(new TransformedRow(
                    i, // 1-based, matches the source file's own line numbering (header is line 0)
                    amount,
                    cols[1].strip(),
                    cols[2].strip(),
                    cols.length > 3 ? cols[3].strip() : null,
                    cols.length > 4 ? cols[4].strip() : null,
                    cols.length > 5 ? cols[5].strip() : null,
                    cols.length > 6 ? cols[6].strip() : null
            ));
        }
        return new TransformOutput(sourceRowCount, sourceTotal, rows);
    }
}
