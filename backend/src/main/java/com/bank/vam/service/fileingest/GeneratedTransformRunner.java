package com.bank.vam.service.fileingest;

import com.bank.vam.config.FileIngestProperties;
import com.bank.vam.entity.fileingest.FormatSignature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
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
 * <p>ponytail: unlike the original single-service design, this app never sees the AI analysis
 * profile (delimiter/columns/control-total column) that produced a signature — decision 3 keeps
 * hash computation exclusively on the agent-worker side. So there's no independent re-parse of
 * the raw file to cross-check against here; reconciliation for an agent-resolved transform trusts
 * the transform's own reported row count/total. The known-shape Receivables path (which does
 * parse the file itself) is unaffected.
 */
@Component
public class GeneratedTransformRunner {

    private static final Logger log = LoggerFactory.getLogger(GeneratedTransformRunner.class);
    private static final long RUN_TIMEOUT_SECONDS = 120;

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
        return new TransformOutput(rows.size(), transformedTotal, rows);
    }

    private List<Map<String, Object>> runGeneratedTransform(String fqcn, Path sourceFile)
            throws IOException, InterruptedException {
        // ponytail: single quotes, not double quotes, around -Dexec.args — Java's ProcessBuilder
        // on Windows mis-escapes a command string containing embedded double quotes. Forward
        // slashes on the source path sidestep a separate exec-maven-plugin arg-splitting wrinkle.
        String sourceFileArg = sourceFile.toString().replace('\\', '/');
        String command = "mvn -q compile org.codehaus.mojo:exec-maven-plugin:3.1.0:java "
                + "-Dexec.mainClass=com.bank.vam.transformhandlers.TransformRunnerMain "
                + "-Dexec.args='" + fqcn + " " + sourceFileArg + "'";
        ProcessBuilder builder = new ProcessBuilder(ShellCommands.bashExecutable(), "-c", command)
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
                    String.valueOf(r.getOrDefault("remittanceInfo", "")),
                    String.valueOf(r.getOrDefault("reference", ""))
            ));
        }
        return rows;
    }
}
