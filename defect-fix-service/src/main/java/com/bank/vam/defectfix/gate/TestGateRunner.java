package com.bank.vam.defectfix.gate;

import com.bank.vam.defectfix.detect.BackendResultParser;
import com.bank.vam.defectfix.detect.DefectDetectionService.Stack;
import com.bank.vam.defectfix.detect.DefectSignature;
import com.bank.vam.defectfix.detect.DetectedDefect;
import com.bank.vam.defectfix.detect.FrontendResultParser;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Runs the stack-appropriate checks and decides pass/fail by whether the ONE specific defect a
 * ticket is about is still present — not by the raw exit code of the whole project's lint/test
 * command. That command's exit code reflects the project's entire pre-existing state (this repo
 * has ~900 unrelated frontend warnings/errors already), so gating on it would make the test gate
 * unwinnable for any single-defect fix. See TriageOrchestrator, which extracts the target
 * DefectSignature out of the ticket description JiraTicketService wrote it into.
 */
@Component
public class TestGateRunner {

    private static final long GATE_TIMEOUT_SECONDS = 600;

    private final FrontendResultParser frontendParser;
    private final BackendResultParser backendParser;

    public TestGateRunner(FrontendResultParser frontendParser, BackendResultParser backendParser) {
        this.frontendParser = frontendParser;
        this.backendParser = backendParser;
    }

    public record GateResult(boolean passed, String output) {
    }

    /** One-time setup before the first gate run in a worktree (e.g. installing node_modules). */
    public void prepare(Stack stack, Path workDir) throws IOException, InterruptedException {
        if (stack == Stack.FRONTEND) {
            run(workDir.resolve("frontend"), "npm ci");
        }
        // Backend needs no separate install step — Maven resolves dependencies on demand.
    }

    /**
     * ponytail: this only confirms the target defect is gone — it does NOT also verify the fix
     * introduced no new defects elsewhere. A broader "diff post-fix defects against the pre-fix
     * set, fail on anything new" check would close that gap; not built yet since it needs the
     * pre-fix set threaded through (currently only the single target signature is), and the
     * project-wide-empty-result guard below already catches the sharpest version of this failure
     * mode (a fix that breaks the file badly enough to make the linter/compiler choke entirely).
     */
    public GateResult runGate(Stack stack, Path workDir, DefectSignature targetDefect) throws Exception {
        List<DetectedDefect> defects = stack == Stack.FRONTEND
                ? runFrontendChecks(workDir)
                : runBackendChecks(workDir);
        return decide(stack, defects, targetDefect);
    }

    /** The actual pass/fail decision, split out from shelling out so it's directly testable. */
    GateResult decide(Stack stack, List<DetectedDefect> defects, DefectSignature targetDefect) {
        if (stack == Stack.FRONTEND && defects.isEmpty()) {
            // Suspicious, not clean: this codebase has hundreds of pre-existing frontend
            // warnings, so a truly empty result means the check tooling itself didn't actually
            // run (crashed, npm ci never completed, eslint/tsc produced no output) rather than
            // "everything is fine now" — fail closed instead of a false pass.
            return new GateResult(false, "Frontend checks produced no output at all — treating as a failed run, not a clean one.");
        }

        Set<DefectSignature> signatures = defects.stream().map(DetectedDefect::signature).collect(Collectors.toSet());
        boolean resolved = !signatures.contains(targetDefect);
        if (resolved) {
            return new GateResult(true, "Target defect (" + targetDefect + ") no longer present.");
        }
        String stillPresent = defects.stream()
                .filter(d -> d.signature().equals(targetDefect))
                .map(d -> d.summary() + " — " + d.details())
                .collect(Collectors.joining("\n"));
        return new GateResult(false, "Target defect still present:\n" + stillPresent);
    }

    private List<DetectedDefect> runFrontendChecks(Path workDir) throws IOException, InterruptedException {
        Path frontendDir = workDir.resolve("frontend");
        run(frontendDir, "npm run type-check > tsc.log 2>&1; npx eslint . --ext ts,tsx --format json --output-file eslint.json");
        List<DetectedDefect> defects = new ArrayList<>();
        defects.addAll(frontendParser.parseTscLog(readIfExists(frontendDir.resolve("tsc.log"))));
        defects.addAll(frontendParser.parseEslintJson(readIfExists(frontendDir.resolve("eslint.json"))));
        return defects;
    }

    private List<DetectedDefect> runBackendChecks(Path workDir) throws Exception {
        Path backendDir = workDir.resolve("backend");
        run(backendDir, "mvn -B test");
        Path reportsDir = backendDir.resolve("target/surefire-reports");
        Map<String, String> reports = new LinkedHashMap<>();
        if (Files.isDirectory(reportsDir)) {
            try (var files = Files.list(reportsDir)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".xml")).toList()) {
                    reports.put(f.getFileName().toString(), Files.readString(f, StandardCharsets.UTF_8));
                }
            }
        }
        return backendParser.parseSurefireReports(reports);
    }

    private String readIfExists(Path path) throws IOException {
        return Files.exists(path) ? Files.readString(path, StandardCharsets.UTF_8) : "";
    }

    /** Deliberately ignores the process exit code — pass/fail is decided by the parsed defect set, not raw status. */
    private void run(Path cwd, String command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("bash", "-lc", command)
                .directory(cwd.toFile())
                .redirectErrorStream(true)
                .start();
        boolean finished = process.waitFor(GATE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        process.getInputStream().readAllBytes();
        if (!finished) {
            process.destroyForcibly();
        }
    }
}
