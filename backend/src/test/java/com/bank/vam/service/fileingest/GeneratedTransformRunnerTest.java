package com.bank.vam.service.fileingest;

import com.bank.vam.config.FileIngestProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers independentSourceTotals directly — the actual fix for the reconciliation-is-a-tautology
 * bug (GeneratedTransformRunner previously derived both sides of the check from the same
 * generated-row list). Doesn't exercise run() itself, which shells out to a real mvn build of the
 * transform-handlers repo — TransformWorktreeManagerTest already covers that class of concern.
 */
class GeneratedTransformRunnerTest {

    private final GeneratedTransformRunner runner = new GeneratedTransformRunner(properties());
    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    private FileIngestProperties properties() {
        FileIngestProperties properties = new FileIngestProperties();
        properties.setWorkspaceDir("/tmp/file-ingest-workspace");
        return properties;
    }

    private String profileJson(String delimiter, String controlTotalColumn) throws Exception {
        return objectMapper.writeValueAsString(
                new AnalysisProfile(List.of("amount", "currency"), delimiter, controlTotalColumn, null));
    }

    @Test
    void independentlyRecomputesRowCountAndControlTotalFromTheRawFile() throws Exception {
        Path file = tempDir.resolve("source.csv");
        Files.writeString(file, "amount,currency\n100.00,AED\n50.50,AED\n");

        GeneratedTransformRunner.SourceTotals totals =
                runner.independentSourceTotals(profileJson(",", "amount"), file);

        assertThat(totals).isNotNull();
        assertThat(totals.rowCount()).isEqualTo(2);
        assertThat(totals.controlTotal()).isEqualByComparingTo("150.50");
    }

    @Test
    void catchesAWrongGeneratedTotalThatTheOldTautologicalCheckCouldNeverCatch() throws Exception {
        // The bug: previously both "source" and "transformed" totals were derived from the SAME
        // generated-row list, so a transform that silently dropped or miscounted a row could never
        // be caught. Here the raw file genuinely has 2 rows/150.50 total -- a generated transform
        // that (incorrectly) reported only 1 row/100.00 would now be caught, because this method
        // never looks at the generated output at all, only the raw file.
        Path file = tempDir.resolve("source.csv");
        Files.writeString(file, "amount,currency\n100.00,AED\n50.50,AED\n");

        GeneratedTransformRunner.SourceTotals independentTruth =
                runner.independentSourceTotals(profileJson(",", "amount"), file);

        assertThat(independentTruth.rowCount()).isEqualTo(2);
        assertThat(independentTruth.controlTotal()).isNotEqualByComparingTo(new BigDecimal("100.00"));
    }

    @Test
    void fallsBackToNullOnAMultiCharDelimiter() throws Exception {
        Path file = tempDir.resolve("source.csv");
        Files.writeString(file, "amount|~|currency\n100.00|~|AED\n");

        assertThat(runner.independentSourceTotals(profileJson("|~|", "amount"), file)).isNull();
    }

    @Test
    void fallsBackToNullWhenNoAnalysisProfileIsStored() {
        assertThat(runner.independentSourceTotals(null, tempDir.resolve("irrelevant.csv"))).isNull();
    }

    @Test
    void fallsBackToNullWhenTheControlTotalColumnIsMissingFromTheHeader() throws Exception {
        Path file = tempDir.resolve("source.csv");
        Files.writeString(file, "amount,currency\n100.00,AED\n");

        assertThat(runner.independentSourceTotals(profileJson(",", "notAColumn"), file)).isNull();
    }

    private Map<String, Object> fullRow() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("amount", "100.00");
        row.put("currency", "AED");
        row.put("viban", "VIBAN001");
        row.put("debtorName", "John Doe");
        row.put("debtorAccount", "ACC1");
        row.put("creditorName", "");
        row.put("creditorAccount", "");
        row.put("remittanceInformation", "Invoice 1");
        row.put("endToEndId", "REF-1");
        return row;
    }

    @Test
    void toTransformedRowsAcceptsARowWithAllNineIso20022Keys() {
        List<TransformedRow> rows = runner.toTransformedRows(List.of(fullRow()));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).remittanceInformation()).isEqualTo("Invoice 1");
        assertThat(rows.get(0).endToEndId()).isEqualTo("REF-1");
    }

    /**
     * Regression test for a real bug found while reviewing the coding agent's own prompt:
     * TransformCodingAgentClient's SYSTEM_PROMPT drifted back to the pre-ISO-20022-rename 7-key
     * row shape (remittanceInfo/reference, no creditor fields) after the rename shipped, which
     * would have silently produced rows missing remittanceInformation/endToEndId/creditorName/
     * creditorAccount -- getOrDefault(key, "") swallowed the mismatch into empty strings with zero
     * visibility. A missing required key must now fail loudly instead.
     */
    @Test
    void toTransformedRowsFailsLoudlyOnARowMissingAnIso20022Key() {
        Map<String, Object> driftedRow = fullRow();
        driftedRow.remove("remittanceInformation");
        driftedRow.put("remittanceInfo", "Invoice 1"); // the old, pre-rename key name

        assertThatThrownBy(() -> runner.toTransformedRows(List.of(driftedRow)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("remittanceInformation");
    }
}
