package com.bank.vam.defectfix.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * File/shell tools the coding agent can call, all scoped to one worktree.
 * Every entry point resolves its path argument against workDir and refuses
 * anything that escapes it — the agent's input is untrusted (it's model
 * output), so this is a real boundary, not a formality.
 */
final class WorkspaceTools {

    private static final int MAX_LISTED_FILES = 500;
    private static final int MAX_OUTPUT_CHARS = 8_000;
    private static final Set<String> EXCLUDED_DIRS = Set.of(".git", "node_modules", "target", "dist", "build");
    private static final long COMMAND_TIMEOUT_SECONDS = 60;

    private WorkspaceTools() {
    }

    static String readFile(Path workDir, String relativePath) throws IOException {
        Path resolved = resolveWithin(workDir, relativePath);
        if (!Files.isRegularFile(resolved)) {
            return "Error: no such file: " + relativePath;
        }
        String content = Files.readString(resolved, StandardCharsets.UTF_8);
        return truncate(content);
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
        Process process = new ProcessBuilder("bash", "-lc", command)
                .directory(workDir.toFile())
                .redirectErrorStream(true)
                .start();
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
