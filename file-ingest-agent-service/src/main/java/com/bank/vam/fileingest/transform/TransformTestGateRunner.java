package com.bank.vam.fileingest.transform;

// ponytail: fresh, simpler than defect-fix-service's TestGateRunner — that one has to diff a
// specific target defect signature out of ~900 pre-existing warnings in the shared monorepo's
// noisy CI output. A small, purpose-built transform-handlers repo has no such noise: whether the
// one generated test (and the rest of the always-clean repo) passes is exactly whether `mvn test`
// exits zero.

import com.bank.vam.fileingest.util.ShellCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

@Component
public class TransformTestGateRunner {

    private static final Logger log = LoggerFactory.getLogger(TransformTestGateRunner.class);
    private static final long GATE_TIMEOUT_SECONDS = 300;
    private static final int MAX_OUTPUT_CHARS = 8_000;

    public record GateResult(boolean passed, String output) {
    }

    public GateResult runGate(Path workDir) throws IOException, InterruptedException {
        log.info("Running transform test gate in {}", workDir);
        ProcessBuilder builder = new ProcessBuilder(ShellCommands.bashExecutable(), "-lc", "mvn -q test")
                .directory(workDir.toFile())
                .redirectErrorStream(true);
        builder.environment().put("JAVA_HOME", System.getProperty("java.home"));
        Process process = builder.start();
        boolean finished = process.waitFor(GATE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!finished) {
            process.destroyForcibly();
            return decide(-1, "Timed out after " + GATE_TIMEOUT_SECONDS + "s. Partial output:\n" + output);
        }
        GateResult result = decide(process.exitValue(), output);
        log.info("Transform test gate {}", result.passed() ? "PASSED" : "FAILED");
        return result;
    }

    /** Split out from the shell-out above so the pass/fail decision is directly unit-testable. */
    GateResult decide(int exitCode, String output) {
        return new GateResult(exitCode == 0, truncate(output));
    }

    private String truncate(String text) {
        if (text.length() <= MAX_OUTPUT_CHARS) {
            return text;
        }
        return text.substring(0, MAX_OUTPUT_CHARS) + "\n...[truncated, " + text.length() + " chars total]";
    }
}
