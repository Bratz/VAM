package com.bank.vam.defectfix.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceToolsTest {

    @TempDir
    Path workDir;

    @Test
    void writeThenReadRoundTrips() throws Exception {
        assertThat(WorkspaceTools.writeFile(workDir, "src/Foo.java", "class Foo {}")).isEqualTo("ok");
        assertThat(WorkspaceTools.readFile(workDir, "src/Foo.java")).isEqualTo("class Foo {}");
    }

    @Test
    void readMissingFileReportsErrorInsteadOfThrowing() throws Exception {
        assertThat(WorkspaceTools.readFile(workDir, "nope.txt")).contains("Error");
    }

    @Test
    void rejectsPathTraversalEscapingTheWorkDir() {
        assertThatEscapeIsRejected(() -> WorkspaceTools.readFile(workDir, "../../etc/passwd"));
        assertThatEscapeIsRejected(() -> WorkspaceTools.writeFile(workDir, "../outside.txt", "pwned"));
    }

    @Test
    void rejectsAbsolutePathsOutsideTheWorkDir() {
        String outside = workDir.getRoot().resolve("elsewhere.txt").toString();
        assertThatEscapeIsRejected(() -> WorkspaceTools.readFile(workDir, outside));
    }

    private interface ThrowingCall {
        void run() throws Exception;
    }

    private void assertThatEscapeIsRejected(ThrowingCall call) {
        try {
            call.run();
        } catch (Exception e) {
            assertThat(e.getMessage()).contains("escapes the working directory");
            return;
        }
        throw new AssertionError("Expected a path-escape rejection but none was thrown");
    }

    @Test
    void listFilesExcludesGitAndNodeModules() throws Exception {
        WorkspaceTools.writeFile(workDir, "src/App.tsx", "content");
        WorkspaceTools.writeFile(workDir, "node_modules/lib/index.js", "content");
        WorkspaceTools.writeFile(workDir, ".git/HEAD", "ref: refs/heads/main");

        String listing = WorkspaceTools.listFiles(workDir, ".").replace('\\', '/');

        assertThat(listing).contains("src/App.tsx");
        assertThat(listing).doesNotContain("node_modules");
        assertThat(listing).doesNotContain(".git/HEAD");
    }
}
