package com.bank.vam.fileingest.agent;

// ponytail: duplicated from defect-fix-service/src/main/java/com/bank/vam/defectfix/agent/WorkspaceTools.java
// (the path-scoping/truncation logic is generic) — extract to a shared module
// if a third consumer needs this. Deliberately narrower than the original:
// no write_file, no run_command. This agent only ever reads the one file a
// customer uploaded; it must not be able to mutate anything.

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

final class AnalysisWorkspaceTools {

    private static final int MAX_OUTPUT_CHARS = 8_000;

    private AnalysisWorkspaceTools() {
    }

    static String readFile(Path workDir, String relativePath) throws IOException {
        Path resolved = resolveWithin(workDir, relativePath);
        if (!Files.isRegularFile(resolved)) {
            return "Error: no such file: " + relativePath;
        }
        return truncate(Files.readString(resolved, StandardCharsets.UTF_8));
    }

    static String sampleRows(Path workDir, String relativePath, int maxLines) throws IOException {
        Path resolved = resolveWithin(workDir, relativePath);
        if (!Files.isRegularFile(resolved)) {
            return "Error: no such file: " + relativePath;
        }
        List<String> lines = Files.readAllLines(resolved, StandardCharsets.UTF_8);
        int limit = Math.min(maxLines, lines.size());
        return truncate(String.join("\n", lines.subList(0, limit))
                + (lines.size() > limit ? "\n...[" + (lines.size() - limit) + " more lines]" : ""));
    }

    /** Rejects absolute paths and any ".." traversal that would escape workDir — same boundary as
     * defect-fix-service's WorkspaceTools, and just as real here: the model's tool arguments are
     * untrusted input, not just its file's content. */
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
