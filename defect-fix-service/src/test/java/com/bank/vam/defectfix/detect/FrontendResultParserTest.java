package com.bank.vam.defectfix.detect;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FrontendResultParserTest {

    private final FrontendResultParser parser = new FrontendResultParser();

    @Test
    void parsesEslintJsonIncludingWarnings() throws Exception {
        String json = """
                [
                  {
                    "filePath": "src/pages/Foo.tsx",
                    "messages": [
                      {"ruleId": "no-unused-vars", "severity": 1, "line": 12, "message": "'x' is unused"},
                      {"ruleId": "no-console", "severity": 2, "line": 30, "message": "no console"}
                    ]
                  }
                ]
                """;

        List<DetectedDefect> defects = parser.parseEslintJson(json);

        assertThat(defects).hasSize(2);
        assertThat(defects).extracting(d -> d.signature().key())
                .containsExactlyInAnyOrder(
                        "no-unused-vars:src/pages/Foo.tsx",
                        "no-console:src/pages/Foo.tsx");
        assertThat(defects).allMatch(d -> d.signature().source().equals("frontend-lint"));
    }

    @Test
    void parsesTscLog() {
        String log = """
                src/pages/Foo.tsx(622,10): error TS6133: 'processing' is declared but its value is never read.
                some unrelated build output line
                src/pages/Bar.tsx(1,1): error TS2304: Cannot find name 'X'.
                """;

        List<DetectedDefect> defects = parser.parseTscLog(log);

        assertThat(defects).hasSize(2);
        assertThat(defects).extracting(d -> d.signature().key())
                .containsExactlyInAnyOrder(
                        "TS6133:src/pages/Foo.tsx",
                        "TS2304:src/pages/Bar.tsx");
    }

    @Test
    void sameDefectProducesTheSameSignatureFromDifferentAbsolutePathPrefixes() throws Exception {
        // ESLint's JSON formatter always emits an absolute filePath, but detection runs it on a
        // GitHub Actions runner and the test gate re-runs it inside a VM worktree — two totally
        // different absolute prefixes for the identical file. Without normalizing to a
        // repo-relative path, the gate could never recognize the "same" defect as resolved.
        String onCiRunner = """
                [{"filePath": "/home/runner/work/VAM/VAM/frontend/src/pages/Foo.tsx",
                  "messages": [{"ruleId": "no-unused-vars", "severity": 1, "line": 1, "message": "m"}]}]
                """;
        String onVmWorktree = """
                [{"filePath": "/data/defect-fix-workspace/worktrees/fix-kan-4/frontend/src/pages/Foo.tsx",
                  "messages": [{"ruleId": "no-unused-vars", "severity": 1, "line": 1, "message": "m"}]}]
                """;

        DefectSignature fromCi = parser.parseEslintJson(onCiRunner).get(0).signature();
        DefectSignature fromVm = parser.parseEslintJson(onVmWorktree).get(0).signature();

        assertThat(fromCi).isEqualTo(fromVm);
        assertThat(fromCi.key()).isEqualTo("no-unused-vars:frontend/src/pages/Foo.tsx");
    }

    @Test
    void emptyInputsYieldNoDefects() throws Exception {
        assertThat(parser.parseEslintJson("")).isEmpty();
        assertThat(parser.parseEslintJson(null)).isEmpty();
        assertThat(parser.parseTscLog("")).isEmpty();
        assertThat(parser.parseTscLog(null)).isEmpty();
    }
}
