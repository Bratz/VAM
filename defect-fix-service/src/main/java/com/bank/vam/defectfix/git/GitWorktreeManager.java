package com.bank.vam.defectfix.git;

import com.bank.vam.defectfix.config.PipelineProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Maintains one local clone of the target repo and hands out per-ticket git
 * worktrees on their own branch, so concurrent tickets (if concurrency is
 * ever raised above 1) never collide on the same working directory.
 */
@Component
public class GitWorktreeManager {

    private static final Logger log = LoggerFactory.getLogger(GitWorktreeManager.class);
    private static final Duration COMMAND_TIMEOUT = Duration.ofMinutes(5);

    private final PipelineProperties.GitHub githubConfig;
    private final Path baseRepoPath;
    private final Path worktreesRoot;

    public GitWorktreeManager(PipelineProperties properties) {
        this.githubConfig = properties.github();
        Path workspace = Path.of(properties.agent().workspaceDir());
        this.baseRepoPath = workspace.resolve("repo");
        this.worktreesRoot = workspace.resolve("worktrees");
    }

    /** Clones the repo on first use, otherwise fetches latest main. Call before creating a worktree. */
    public synchronized void ensureBaseRepoReady() throws IOException, InterruptedException {
        if (Files.isDirectory(baseRepoPath.resolve(".git"))) {
            run(baseRepoPath, "fetch", "origin", "main");
            return;
        }
        Files.createDirectories(baseRepoPath.getParent());
        run(baseRepoPath.getParent(), "clone", authenticatedRemoteUrl(), baseRepoPath.getFileName().toString());
    }

    /**
     * Creates a worktree checked out at the CURRENT tip of {@code sourceBranch} (detached, not a
     * new local branch — commitAndPush below pushes straight back to that same branch by name
     * regardless of local branch state, so there's nothing to name here). Caller is responsible
     * for cleanup.
     *
     * Deliberately the PR's OWN branch, not a fresh one off main: the defective code a ticket is
     * about lives only on the branch that failed CI (a PR that fails CI is, by definition, not
     * merged, so main never has it) — branching from main would leave the agent unable to see the
     * actual bug at all. Confirmed live: that was exactly what happened before this existed.
     *
     * Cleans up any same-named worktree left behind by a PRIOR attempt on this same ticket first —
     * a crash or restart mid-attempt (this service got rebuilt/killed mid-flight more than once
     * while first standing this up) leaves exactly that kind of debris.
     */
    public Path createWorktree(String sourceBranch) throws IOException, InterruptedException {
        Files.createDirectories(worktreesRoot);
        Path worktreePath = worktreesRoot.resolve(sanitize(sourceBranch));
        try {
            run(baseRepoPath, "worktree", "remove", worktreePath.toString(), "--force");
        } catch (Exception e) {
            // Expected in the common case: no stale worktree at this path.
        }
        run(baseRepoPath, "fetch", "origin", sourceBranch);
        run(baseRepoPath, "worktree", "add", "--detach", worktreePath.toString(), "origin/" + sourceBranch);
        return worktreePath;
    }

    /** @return true if there were changes to commit and they were pushed; false if the agent made no changes. */
    public boolean commitAndPush(Path worktreePath, String branchName, String commitMessage)
            throws IOException, InterruptedException {
        run(worktreePath, "add", "-A");
        CommandResult status = run(worktreePath, "status", "--porcelain");
        if (status.output().isBlank()) {
            log.warn("Coding agent made no file changes in {} — nothing to commit", worktreePath);
            return false;
        }
        run(worktreePath, "commit", "-m", commitMessage);
        // NOT --force: branchName here is the ORIGINAL PR's own branch — it may belong to a human
        // (or another process), not this pipeline exclusively, so a non-fast-forward push should
        // fail loudly (caught by the caller, which escalates to a human) rather than clobber
        // whatever someone else pushed there since this worktree was created.
        run(worktreePath, "push", authenticatedRemoteUrl(), "HEAD:refs/heads/" + branchName);
        return true;
    }

    public void removeWorktree(Path worktreePath) {
        try {
            run(baseRepoPath, "worktree", "remove", worktreePath.toString(), "--force");
        } catch (Exception e) {
            log.warn("Failed to clean up worktree {} — leaving for manual cleanup", worktreePath, e);
        }
    }

    private String authenticatedRemoteUrl() {
        return "https://x-access-token:" + githubConfig.token() + "@github.com/"
                + githubConfig.owner() + "/" + githubConfig.repo() + ".git";
    }

    private String sanitize(String branchName) {
        return branchName.replaceAll("[^a-zA-Z0-9._-]", "-");
    }

    private CommandResult run(Path cwd, String... gitArgs) throws IOException, InterruptedException {
        List<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.addAll(List.of(gitArgs));

        Process process = new ProcessBuilder(command)
                .directory(cwd.toFile())
                .redirectErrorStream(true)
                .start();
        boolean finished = process.waitFor(COMMAND_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        String output = redactToken(new String(process.getInputStream().readAllBytes()));
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("git " + String.join(" ", gitArgs) + " timed out after " + COMMAND_TIMEOUT);
        }
        if (process.exitValue() != 0) {
            throw new IOException("git " + String.join(" ", gitArgs) + " failed (exit " + process.exitValue() + "): " + output);
        }
        return new CommandResult(process.exitValue(), output);
    }

    /** git can echo the authenticated remote URL (with the embedded PAT) into its own error output. */
    private String redactToken(String text) {
        String token = githubConfig.token();
        return (token == null || token.isBlank()) ? text : text.replace(token, "***");
    }

    private record CommandResult(int exitCode, String output) {
    }
}
