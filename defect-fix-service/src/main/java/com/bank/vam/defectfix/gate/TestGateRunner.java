package com.bank.vam.defectfix.gate;

import com.bank.vam.defectfix.detect.DefectDetectionService.Stack;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Runs the stack-appropriate check as the pass/fail gate on a coding agent's fix. */
@Component
public class TestGateRunner {

    private static final long GATE_TIMEOUT_SECONDS = 600;

    public record GateResult(boolean passed, String output) {
    }

    /** One-time setup before the first gate run in a worktree (e.g. installing node_modules). */
    public void prepare(Stack stack, Path workDir) throws IOException, InterruptedException {
        if (stack == Stack.FRONTEND) {
            run(workDir.resolve("frontend"), "npm ci");
        }
        // Backend needs no separate install step — Maven resolves dependencies on demand.
    }

    public GateResult runGate(Stack stack, Path workDir) throws IOException, InterruptedException {
        return switch (stack) {
            case FRONTEND -> run(workDir.resolve("frontend"), "npm run type-check && npm run lint");
            case BACKEND -> run(workDir.resolve("backend"), "mvn -B test");
        };
    }

    private GateResult run(Path cwd, String command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("bash", "-lc", command)
                .directory(cwd.toFile())
                .redirectErrorStream(true)
                .start();
        boolean finished = process.waitFor(GATE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!finished) {
            process.destroyForcibly();
            return new GateResult(false, "Gate command timed out after " + GATE_TIMEOUT_SECONDS + "s:\n" + output);
        }
        return new GateResult(process.exitValue() == 0, output);
    }
}
