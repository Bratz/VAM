package com.bank.vam.service.fileingest;

import com.bank.vam.config.FileIngestProperties;
import com.bank.vam.entity.fileingest.FormatSignature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Runs an already-merged, already-tested transform against a real uploaded file and builds the
 * reconciled {@link TransformOutput} the rest of the pipeline (staging/reconciliation/quarantine/
 * processing) needs. This app never writes to the {@code transform-handlers} repo (that's the
 * separate agent worker's job — see FileIngestTicketService's doc comment); it only ever reads
 * the shared checkout's current {@code main} to run code that already passed a test gate.
 *
 * <p>Reconciliation is independently re-verified against the raw file wherever possible, using
 * {@link AnalysisProfile} persisted on the signature at generation time (see
 * FormatSignatureService.recordTransform on the agent-worker side) — not derived from the
 * generated transform's own output, which would make the check compare a value to itself.
 * <b>ponytail limitation</b> (same one already accepted for the generation-time agent-written
 * check): only works for a single-character delimiter with a known control-total column; anything
 * else (missing profile, multi-char delimiter, no control column, or a parse failure on any row)
 * falls back to trusting the transform's reported counts — so reconciliation can't catch a
 * dropped/miscounted row for those shapes yet.
 */
@Component
public class GeneratedTransformRunner {

    private static final Logger log = LoggerFactory.getLogger(GeneratedTransformRunner.class);
    private static final long RUN_TIMEOUT_SECONDS = 120;

    /** Placed here by the Dockerfile, alongside app.jar (WORKDIR /app) — see backend/Dockerfile. */
    private static final String SANDBOX_SCRIPT = "/app/sandbox-run.sh";

    private final Path transformHandlersDir;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GeneratedTransformRunner(FileIngestProperties properties) {
        this.transformHandlersDir = Path.of(properties.getWorkspaceDir()).resolve("transform-handlers");
    }

    public TransformOutput run(FormatSignature signature, Path sourceFile) throws IOException, InterruptedException {
        String packageName = "sig" + signature.getSignatureHash().substring(0, 16);
        String fqcn = "com.bank.vam.transformhandlers.generated." + packageName + ".GeneratedTransform";
        List<Map<String, Object>> rawRows = runGeneratedTransform(fqcn, sourceFile);
        List<TransformedRow> rows = toTransformedRows(rawRows);
        BigDecimal transformedTotal = rows.stream().map(TransformedRow::amount).reduce(BigDecimal.ZERO, BigDecimal::add);

        SourceTotals independent = independentSourceTotals(signature.getAnalysisProfileJson(), sourceFile);
        if (independent != null) {
            return new TransformOutput(independent.rowCount(), independent.controlTotal(), rows);
        }
        // Fallback: no usable profile — same behavior as before this check existed.
        return new TransformOutput(rows.size(), transformedTotal, rows);
    }

    record SourceTotals(int rowCount, BigDecimal controlTotal) {
    }

    /** @return an independent recount/resum of the raw file, or null if the profile is missing or
     * doesn't meet the single-char-delimiter/known-control-column precondition — the caller falls
     * back to trusting the transform's own counts in that case, not a hard failure.
     * Package-private (not private) so this is directly unit-testable without shelling out to mvn. */
    SourceTotals independentSourceTotals(String analysisProfileJson, Path sourceFile) {
        if (analysisProfileJson == null || analysisProfileJson.isBlank()) {
            return null;
        }
        AnalysisProfile profile;
        try {
            profile = objectMapper.readValue(analysisProfileJson, AnalysisProfile.class);
        } catch (Exception e) {
            log.warn("Could not parse stored analysis profile — falling back to trusting the transform's own counts", e);
            return null;
        }
        if (profile.delimiter() == null || profile.delimiter().length() != 1
                || profile.controlTotalColumn() == null || profile.controlTotalColumn().isBlank()) {
            return null;
        }

        List<String> lines;
        try {
            lines = Files.readAllLines(sourceFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read source file " + sourceFile + " for reconciliation", e);
        }
        if (lines.isEmpty()) {
            return new SourceTotals(0, BigDecimal.ZERO);
        }
        char delimiter = profile.delimiter().charAt(0);
        String[] header = lines.get(0).split(java.util.regex.Pattern.quote(String.valueOf(delimiter)), -1);
        int controlColumnIndex = -1;
        for (int i = 0; i < header.length; i++) {
            if (header[i].strip().equalsIgnoreCase(profile.controlTotalColumn())) {
                controlColumnIndex = i;
                break;
            }
        }
        if (controlColumnIndex < 0) {
            log.warn("Analysis profile's control-total column \"{}\" not found in {}'s header — "
                    + "falling back to trusting the transform's own counts", profile.controlTotalColumn(), sourceFile);
            return null;
        }

        int rowCount = 0;
        BigDecimal total = BigDecimal.ZERO;
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.isEmpty()) {
                continue;
            }
            String[] cols = line.split(java.util.regex.Pattern.quote(String.valueOf(delimiter)), -1);
            if (controlColumnIndex >= cols.length) {
                log.warn("Row {} of {} has fewer columns than the control-total column index — "
                        + "falling back to trusting the transform's own counts", i, sourceFile);
                return null;
            }
            try {
                total = total.add(new BigDecimal(cols[controlColumnIndex].strip()));
            } catch (NumberFormatException e) {
                log.warn("Row {} of {}'s control-total column isn't a plain number — "
                        + "falling back to trusting the transform's own counts", i, sourceFile);
                return null;
            }
            rowCount++;
        }
        return new SourceTotals(rowCount, total);
    }

    private List<Map<String, Object>> runGeneratedTransform(String fqcn, Path sourceFile)
            throws IOException, InterruptedException {
        // ponytail: single quotes, not double quotes, around -Dexec.args — Java's ProcessBuilder
        // on Windows mis-escapes a command string containing embedded double quotes. Forward
        // slashes on the source path sidestep a separate exec-maven-plugin arg-splitting wrinkle.
        // -o (offline): this run should never need the network — the .m2 cache is already warm
        // from the test-gate run that already compiled this exact code; a loud offline-mode
        // failure is better than silently discovering --net isolation (below) has a gap.
        String sourceFileArg = sourceFile.toString().replace('\\', '/');
        String command = "mvn -q -o compile org.codehaus.mojo:exec-maven-plugin:3.1.0:java "
                + "-Dexec.mainClass=com.bank.vam.transformhandlers.TransformRunnerMain "
                + "-Dexec.args='" + fqcn + " " + sourceFileArg + "'";
        // Sandboxed: this executes agent-generated, unreviewed code against a real uploaded file —
        // see backend/sandbox-run.sh for what this actually isolates (env clearing + network +
        // resource limits) and why Docker-in-Docker was rejected instead.
        ProcessBuilder builder = new ProcessBuilder(ShellCommands.bashExecutable(), SANDBOX_SCRIPT, command)
                .directory(transformHandlersDir.toFile());
        builder.environment().put("JAVA_HOME", System.getProperty("java.home"));
        Process process = builder.start();
        boolean finished = process.waitFor(RUN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("Generated transform run timed out after " + RUN_TIMEOUT_SECONDS + "s");
        }
        if (process.exitValue() != 0) {
            throw new IOException("Generated transform run failed (exit " + process.exitValue() + "): " + stderr);
        }
        return objectMapper.readValue(stdout, new TypeReference<List<Map<String, Object>>>() {
        });
    }

    private List<TransformedRow> toTransformedRows(List<Map<String, Object>> rawRows) {
        List<TransformedRow> rows = new ArrayList<>();
        for (int i = 0; i < rawRows.size(); i++) {
            Map<String, Object> r = rawRows.get(i);
            rows.add(new TransformedRow(
                    i + 1,
                    new BigDecimal(String.valueOf(r.getOrDefault("amount", "0"))),
                    String.valueOf(r.getOrDefault("currency", "")),
                    String.valueOf(r.getOrDefault("viban", "")),
                    String.valueOf(r.getOrDefault("debtorName", "")),
                    String.valueOf(r.getOrDefault("debtorAccount", "")),
                    String.valueOf(r.getOrDefault("creditorName", "")),
                    String.valueOf(r.getOrDefault("creditorAccount", "")),
                    String.valueOf(r.getOrDefault("remittanceInformation", "")),
                    String.valueOf(r.getOrDefault("endToEndId", ""))
            ));
        }
        return rows;
    }
}
