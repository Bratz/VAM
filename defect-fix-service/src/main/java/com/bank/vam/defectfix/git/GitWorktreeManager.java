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
     * Creates a fresh worktree off origin/main on a new branch. Caller is responsible for cleanup.
     * Cleans up any same-named worktree/branch left behind by a PRIOR attempt on this same ticket
     * first — a crash or restart mid-attempt (this service got rebuilt/killed mid-flight more than
     * once while first standing this up) leaves exactly that kind of debris, and without this,
     * every retry after one would hit "a branch named ... already exists" and never get anywhere.
     */
    public Path createWorktree(String branchName) throws IOException, InterruptedException {
        Files.createDirectories(worktreesRoot);
        Path worktreePath = worktreesRoot.resolve(sanitize(branchName));
        cleanupStale(worktreePath, branchName);
        run(baseRepoPath, "worktree", "add", "-b", branchName, worktreePath.toString(), "origin/main");
        return worktreePath;
    }

    private void cleanupStale(Path worktreePath, String branchName) {
        try {
            run(baseRepoPath, "worktree", "remove", worktreePath.toString(), "--force");
        } catch (Exception e) {
            // Expected in the common case: no stale worktree at this path.
        }
        try {
            run(baseRepoPath, "branch", "-D", branchName);
        } catch (Exception e) {
            // Expected in the common case: no stale branch.
        }
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
        // --force: these fix/* branches are created and pushed to exclusively by this pipeline —
        // no human ever pushes to one directly — so this push IS the authoritative state for the
        // ticket, not a collaborative one. Confirmed live as necessary: a non-force push was
        // rejected non-fast-forward because an earlier, since-abandoned attempt on the same
        // ticket (interrupted before cleanupStale existed) had already pushed something to this
        // exact branch name on GitHub.
        run(worktreePath, "push", "--force", authenticatedRemoteUrl(), "HEAD:refs/heads/" + branchName);
        return true;
    }

    public void removeWorktree(Path worktreePath, String branchName) {
        try {
            run(baseRepoPath, "worktree", "remove", worktreePath.toString(), "--force");
            run(baseRepoPath, "branch", "-D", branchName);
        } catch (Exception e) {
            log.warn("Failed to clean up worktree {} (branch {}) — leaving for manual cleanup", worktreePath, branchName, e);
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
