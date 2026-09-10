package com.bank.vam.defectfix.detect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the artifacts uploaded by .github/workflows/frontend-ci.yml:
 * eslint.json (ESLint's --format json) and tsc.log (tsc --noEmit's plain
 * "file(line,col): error TSxxxx: message" output).
 */
@Component
public class FrontendResultParser {

    // e.g. src/pages/Foo.tsx(622,10): error TS6133: 'processing' is declared but its value is never read.
    private static final Pattern TSC_LINE =
            Pattern.compile("^(?<file>.+?)\\((?<line>\\d+),(?<col>\\d+)\\): error (?<code>TS\\d+): (?<message>.+)$");

    private final ObjectMapper objectMapper = new ObjectMapper();

    public List<DetectedDefect> parseEslintJson(String json) throws IOException {
        List<DetectedDefect> defects = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return defects;
        }
        JsonNode files = objectMapper.readTree(json);
        for (JsonNode file : files) {
            // ESLint's JSON formatter always emits an ABSOLUTE path, and that absolute prefix
            // differs between where detection runs (a GitHub Actions runner,
            // /home/runner/work/VAM/VAM/frontend/...) and where the test gate re-runs the same
            // check (a worktree on the VM, /data/.../worktrees/.../frontend/...). Left as-is, the
            // signature for the "same" defect would never match across the two, so the gate could
            // never see a fix as resolved — normalize to repo-relative (frontend/src/...) so both
            // environments produce the identical signature for the identical defect.
            String filePath = relativizeToFrontend(file.path("filePath").asText());
            for (JsonNode msg : file.path("messages")) {
                // Both severities are real defects here: this repo's `npm run lint` runs with
                // --max-warnings 0, so a severity-1 warning fails the build same as an error.
                String ruleId = msg.path("ruleId").asText("unknown-rule");
                int line = msg.path("line").asInt(0);
                String message = msg.path("message").asText("");
                DefectSignature sig = new DefectSignature("frontend-lint", ruleId + ":" + filePath);
                defects.add(new DetectedDefect(sig,
                        "[lint] " + ruleId + " in " + filePath,
                        filePath + ":" + line + " — " + message));
            }
        }
        return defects;
    }

    /** "/anything/.../frontend/src/pages/Foo.tsx" -> "frontend/src/pages/Foo.tsx"; unchanged if "frontend/" isn't found. */
    private String relativizeToFrontend(String absoluteOrAnyPath) {
        String normalized = absoluteOrAnyPath.replace('\\', '/');
        int marker = normalized.lastIndexOf("/frontend/");
        return marker < 0 ? normalized : normalized.substring(marker + 1);
    }

    public List<DetectedDefect> parseTscLog(String log) {
        List<DetectedDefect> defects = new ArrayList<>();
        if (log == null || log.isBlank()) {
            return defects;
        }
        for (String rawLine : log.split("\\R")) {
            Matcher m = TSC_LINE.matcher(rawLine.strip());
            if (!m.matches()) {
                continue;
            }
            String file = m.group("file");
            String code = m.group("code");
            String message = m.group("message");
            DefectSignature sig = new DefectSignature("frontend-typecheck", code + ":" + file);
            defects.add(new DetectedDefect(sig,
                    "[type-check] " + code + " in " + file,
                    file + ":" + m.group("line") + " — " + message));
        }
        return defects;
    }
}
