package com.bank.vam.fileingest.transform;

// ponytail: fresh, simpler than defect-fix-service's GitWorktreeManager — see the design doc's
// reuse table. That one pushes to a GitHub remote because its worktree branches off an existing
// PR someone needs to see fixed; transform-handlers has no PR, no remote and no human review
// (decision 7), so everything happens in one local repo: branch off main, and a passing test gate
// merges straight back with a local `git merge` — nothing to push, nothing to open a PR against.

import com.bank.vam.fileingest.config.IngestProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class TransformWorktreeManager {

    private static final Logger log = LoggerFactory.getLogger(TransformWorktreeManager.class);
    private static final Duration COMMAND_TIMEOUT = Duration.ofMinutes(5);

    private final Path baseRepoPath;
    private final Path worktreesRoot;

    public TransformWorktreeManager(IngestProperties properties) {
        Path workspace = Path.of(properties.workspaceDir());
        this.baseRepoPath = workspace.resolve("transform-handlers");
        this.worktreesRoot = workspace.resolve("transform-worktrees");
    }

    /** The single local repo's own checkout of {@code main} — always has every signature's
     * generated package merged into it, so a format-signature cache hit (step 5) can run straight
     * off this path with no worktree and no agent call. */
    public Path baseRepoPath() {
        return baseRepoPath;
    }

    /** Writes the skeleton and does the first commit on first use; a no-op every time after. */
    public synchronized void ensureBaseRepoReady() throws IOException, InterruptedException {
        if (Files.isDirectory(baseRepoPath.resolve(".git"))) {
            return;
        }
        Files.createDirectories(baseRepoPath);
        TransformHandlersSkeleton.writeInto(baseRepoPath);
        run(baseRepoPath, "init", "-b", "main");
        run(baseRepoPath, "add", "-A");
        commit(baseRepoPath, "Initial transform-handlers skeleton");
        log.info("Initialized transform-handlers repo at {}", baseRepoPath);
    }

    /** Creates a worktree on a fresh branch off the current tip of main. Caller must removeWorktree it. */
    public Path createWorktree(String branchName) throws IOException, InterruptedException {
        Files.createDirectories(worktreesRoot);
        Path worktreePath = worktreesRoot.resolve(sanitize(branchName));
        try {
            run(baseRepoPath, "worktree", "remove", worktreePath.toString(), "--force");
        } catch (Exception e) {
            // Expected in the common case: no stale worktree left by a prior attempt on this job.
        }
        try {
            run(baseRepoPath, "branch", "-D", branchName);
        } catch (Exception e) {
            // Expected in the common case: the branch doesn't already exist.
        }
        run(baseRepoPath, "worktree", "add", "-b", branchName, worktreePath.toString(), "main");
        return worktreePath;
    }

    /**
     * Commits whatever the coding agent left in the worktree (if anything) and merges it into
     * main. @return the merge commit SHA, or null if the agent made no changes to commit.
     */
    public String mergeToMain(Path worktreePath, String branchName, String commitMessage)
            throws IOException, InterruptedException {
        run(worktreePath, "add", "-A");
        CommandResult status = run(worktreePath, "status", "--porcelain");
        if (status.output().isBlank()) {
            log.warn("Transform coding agent made no file changes in {} — nothing to merge", worktreePath);
            return null;
        }
        commit(worktreePath, commitMessage);
        // --no-ff forces a real merge commit, which needs a committer identity same as any other
        // commit -- confirmed live: this container has no git identity configured anywhere except
        // what `commit()` sets per-invocation, and this call never went through that helper.
        run(baseRepoPath, "-c", "user.email=file-ingest-agent@vam.local", "-c", "user.name=file-ingest-agent",
                "merge", "--no-ff", branchName, "-m", "Merge " + branchName);
        return run(baseRepoPath, "rev-parse", "HEAD").output().strip();
    }

    public void removeWorktree(Path worktreePath) {
        try {
            run(baseRepoPath, "worktree", "remove", worktreePath.toString(), "--force");
        } catch (Exception e) {
            log.warn("Failed to clean up worktree {} — leaving for manual cleanup", worktreePath, e);
        }
    }

    private void commit(Path cwd, String message) throws IOException, InterruptedException {
        run(cwd, "-c", "user.email=file-ingest-agent@vam.local", "-c", "user.name=file-ingest-agent",
                "commit", "-m", message);
    }

    private String sanitize(String branchName) {
        return branchName.replaceAll("[^a-zA-Z0-9._-]", "-");
    }

    private CommandResult run(Path cwd, String... gitArgs) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(gitArgs));

        Process process = new ProcessBuilder(command)
                .directory(cwd.toFile())
                .redirectErrorStream(true)
                .start();
        boolean finished = process.waitFor(COMMAND_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("git " + String.join(" ", gitArgs) + " timed out after " + COMMAND_TIMEOUT);
        }
        if (process.exitValue() != 0) {
            throw new IOException("git " + String.join(" ", gitArgs) + " failed (exit " + process.exitValue() + "): " + output);
        }
        return new CommandResult(process.exitValue(), output);
    }

    private record CommandResult(int exitCode, String output) {
    }
}
