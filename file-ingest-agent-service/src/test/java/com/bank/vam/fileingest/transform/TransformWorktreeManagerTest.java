package com.bank.vam.fileingest.transform;

import com.bank.vam.fileingest.config.IngestProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises real git (init/worktree/merge) against a temp directory instead of mocking it — the
 * whole point of this class is orchestrating actual git commands correctly, which a mock can't
 * verify.
 */
class TransformWorktreeManagerTest {

    @TempDir
    Path workspace;

    private TransformWorktreeManager manager() {
        return new TransformWorktreeManager(new IngestProperties(null, null, null, workspace.toString()));
    }

    @Test
    void firstUseWritesSkeletonAndCommitsIt() throws Exception {
        TransformWorktreeManager manager = manager();

        manager.ensureBaseRepoReady();

        Path repo = workspace.resolve("transform-handlers");
        assertThat(repo.resolve(".git")).isDirectory();
        assertThat(repo.resolve("pom.xml")).isRegularFile();
        assertThat(repo.resolve("src/main/java/com/bank/vam/transformhandlers/RowTransform.java")).isRegularFile();
    }

    @Test
    void mergedWorktreeChangesReachMain() throws Exception {
        TransformWorktreeManager manager = manager();
        manager.ensureBaseRepoReady();

        Path worktree = manager.createWorktree("job-abc");
        Files.writeString(worktree.resolve("marker.txt"), "hello");

        String sha = manager.mergeToMain(worktree, "job-abc", "Add marker");
        manager.removeWorktree(worktree);

        assertThat(sha).matches("[0-9a-f]{40}");
        Path verifyWorktree = manager.createWorktree("verify");
        assertThat(verifyWorktree.resolve("marker.txt")).isRegularFile();
    }

    @Test
    void mergeReturnsNullWhenAgentMadeNoChanges() throws Exception {
        TransformWorktreeManager manager = manager();
        manager.ensureBaseRepoReady();

        Path worktree = manager.createWorktree("job-empty");

        assertThat(manager.mergeToMain(worktree, "job-empty", "Add nothing")).isNull();
    }
}
