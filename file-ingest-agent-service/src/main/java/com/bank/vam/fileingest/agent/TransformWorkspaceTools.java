package com.bank.vam.fileingest.agent;

// ponytail: duplicated from defect-fix-service/src/main/java/com/bank/vam/defectfix/agent/WorkspaceTools.java
// as-is (the path-scoping/truncation/shell-out logic is generic) — extract to a shared module if a
// third consumer needs this. Unlike AnalysisWorkspaceTools, this one keeps write_file and
// run_command: the transform coding agent (build order step 4) actually writes and tests code in
// its worktree, not just reads a customer's file.

import com.bank.vam.fileingest.util.ShellCommands;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

final class TransformWorkspaceTools {

    private static final int MAX_LISTED_FILES = 500;
    private static final int MAX_OUTPUT_CHARS = 8_000;
    private static final Set<String> EXCLUDED_DIRS = Set.of(".git", "target");
    private static final long COMMAND_TIMEOUT_SECONDS = 120;

    private TransformWorkspaceTools() {
    }

    static String readFile(Path workDir, String relativePath) throws IOException {
        Path resolved = resolveWithin(workDir, relativePath);
        if (!Files.isRegularFile(resolved)) {
            return "Error: no such file: " + relativePath;
        }
        return truncate(Files.readString(resolved, StandardCharsets.UTF_8));
    }

    static String writeFile(Path workDir, String relativePath, String content) throws IOException {
        Path resolved = resolveWithin(workDir, relativePath);
        Files.createDirectories(resolved.getParent());
        Files.writeString(resolved, content, StandardCharsets.UTF_8);
        return "ok";
    }

    static String listFiles(Path workDir, String relativePath) throws IOException {
        Path resolved = resolveWithin(workDir, relativePath);
        if (!Files.isDirectory(resolved)) {
            return "Error: no such directory: " + relativePath;
        }
        try (Stream<Path> walk = Files.walk(resolved)) {
            List<String> paths = walk
                    .filter(p -> EXCLUDED_DIRS.stream().noneMatch(dir -> p.toString().contains(dir + java.io.File.separator)))
                    .filter(Files::isRegularFile)
                    .limit(MAX_LISTED_FILES)
                    .map(p -> workDir.relativize(p).toString())
                    .sorted()
                    .collect(Collectors.toList());
            return String.join("\n", paths);
        }
    }

    static String runCommand(Path workDir, String command) throws IOException, InterruptedException {
        // ponytail: written to a script file and run as `bash scriptfile`, not `bash -lc "<command>"`
        // — confirmed by hand that Java's ProcessBuilder on Windows mis-escapes a single command
        // STRING containing embedded double quotes (it applies MSVCRT-style requoting that bash, a
        // non-MSVCRT program, doesn't expect). The agent's own commands routinely contain quotes
        // (grep, find -name "*.java", etc.), so this has to handle arbitrary quoting, not just avoid
        // one particular character.
        Path scriptFile = Files.createTempFile(workDir, ".run-command-", ".sh");
        try {
            Files.writeString(scriptFile, command, StandardCharsets.UTF_8);
            ProcessBuilder builder = new ProcessBuilder(ShellCommands.bashExecutable(), scriptFile.toString())
                    .directory(workDir.toFile())
                    .redirectErrorStream(true);
            builder.environment().put("JAVA_HOME", System.getProperty("java.home"));
            return runAndCapture(builder);
        } finally {
            Files.deleteIfExists(scriptFile);
        }
    }

    private static String runAndCapture(ProcessBuilder builder) throws IOException, InterruptedException {
        Process process = builder.start();
        boolean finished = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!finished) {
            process.destroyForcibly();
            return "Error: command timed out after " + COMMAND_TIMEOUT_SECONDS + "s. Partial output:\n" + truncate(output);
        }
        return "exit code " + process.exitValue() + "\n" + truncate(output);
    }

    /** Rejects absolute paths and any ".." traversal that would escape workDir. */
    private static Path resolveWithin(Path workDir, String relativePath) throws IOException {
        Path resolved = workDir.resolve(relativePath).normalize();
        if (!resolved.startsWith(workDir.normalize())) {
            throw new IOException("Path escapes the working directory: " + relativePath);
        }
        return resolved;
    }

    private static String truncate(String text) {
        if (text.length() <= MAX_OUTPUT_CHARS) {
            return text;
        }
        return text.substring(0, MAX_OUTPUT_CHARS) + "\n...[truncated, " + text.length() + " chars total]";
    }
}
