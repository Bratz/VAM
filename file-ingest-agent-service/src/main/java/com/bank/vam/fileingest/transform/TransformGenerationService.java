package com.bank.vam.fileingest.transform;

import com.bank.vam.fileingest.agent.FileStructureProfile;
import com.bank.vam.fileingest.agent.TransformCodingAgentClient;
import com.bank.vam.fileingest.config.IngestProperties;
import com.bank.vam.fileingest.entity.IngestJob;
import com.bank.vam.fileingest.signature.FormatSignatureService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Proves the generate -&gt; test -&gt; merge loop for a customer format nothing recognizes yet.
 * One shot per job (bounded retries, feeding the previous failure back to the agent) — mirrors
 * TriageOrchestrator's attempt loop, minus the defect-signature/CI-noise handling that doesn't
 * apply here.
 *
 * <p>Trimmed for the decoupled architecture (see tasks/file-ingest-pipeline-design.md's "Revised
 * architecture"): this worker never runs the generated code against real customer data anymore —
 * backend's own GeneratedTransformRunner does that, since backend is the only side that ever
 * touches real business data. This class's job ends the moment a signature has a transform_ref.
 */
@Component
public class TransformGenerationService {

    private static final Logger log = LoggerFactory.getLogger(TransformGenerationService.class);

    private final TransformWorktreeManager worktreeManager;
    private final TransformCodingAgentClient codingAgentClient;
    private final TransformTestGateRunner testGateRunner;
    private final FormatSignatureService formatSignatureService;
    private final int maxRetries;

    public TransformGenerationService(TransformWorktreeManager worktreeManager,
                                       TransformCodingAgentClient codingAgentClient,
                                       TransformTestGateRunner testGateRunner,
                                       FormatSignatureService formatSignatureService,
                                       IngestProperties properties) {
        this.worktreeManager = worktreeManager;
        this.codingAgentClient = codingAgentClient;
        this.testGateRunner = testGateRunner;
        this.formatSignatureService = formatSignatureService;
        this.maxRetries = properties.agent().maxRetries();
    }

    public record GenerationResult(boolean succeeded, String commitSha, String failureReason) {
    }

    public GenerationResult generateAndRun(IngestJob job, Path sourceFile, String fileName, FileStructureProfile profile) {
        // ponytail: the package name is derived from the format signature hash, not the job id —
        // that's what lets a cache hit reconstruct the same class to invoke later without a new
        // column. Known gap: two DIFFERENT jobs racing a genuine first-sighting of the exact same
        // new shape would both target this same package from separate worktrees/branches; the
        // second merge then fails on an add/add conflict and that job is blocked rather than
        // corrupting the repo. Accepted for now — same single-instance assumption TriageOrchestrator
        // already makes elsewhere in this codebase; upgrade path is a per-signature lock if this
        // service is ever scaled beyond one instance.
        String packageName = formatSignatureService.packageNameFor(job.getCustomerId(), job.getDomain(), profile);
        String branchName = "job-" + job.getId();
        Path worktreePath = null;
        try {
            worktreeManager.ensureBaseRepoReady();
            worktreePath = worktreeManager.createWorktree(branchName);

            Path fixtureDir = worktreePath.resolve("src/test/resources").resolve(packageName);
            Files.createDirectories(fixtureDir);
            Files.copy(sourceFile, fixtureDir.resolve(fileName), StandardCopyOption.REPLACE_EXISTING);
            String fixtureRelativePath = "src/test/resources/" + packageName + "/" + fileName;

            String taskBrief = buildTaskBrief(packageName, fixtureRelativePath, profile);
            TransformTestGateRunner.GateResult lastGate = null;

            for (int attempt = 0; attempt <= maxRetries; attempt++) {
                String prompt = attempt == 0 ? taskBrief
                        : taskBrief + "\n\nYour previous attempt did not pass `mvn test`. Output:\n" + lastGate.output();
                codingAgentClient.generate(worktreePath, prompt);
                lastGate = testGateRunner.runGate(worktreePath);
                if (lastGate.passed()) {
                    String commitSha = worktreeManager.mergeToMain(worktreePath, branchName,
                            "Add transform for job " + job.getId());
                    if (commitSha == null) {
                        return new GenerationResult(false, null,
                                "Test gate passed but the coding agent made no file changes — nothing to merge.");
                    }
                    formatSignatureService.recordTransform(job.getCustomerId(), job.getDomain(), profile, commitSha);
                    return new GenerationResult(true, commitSha, null);
                }
                log.info("Transform generation attempt {} for job {} failed the test gate", attempt + 1, job.getId());
            }
            return new GenerationResult(false, null,
                    "Exhausted " + (maxRetries + 1) + " attempt(s); last test gate output:\n" + lastGate.output());
        } catch (Exception e) {
            log.error("Transform generation failed for job {}", job.getId(), e);
            // This catch swallows the exception into a GenerationResult instead of rethrowing, so
            // IngestTriageOrchestrator's own catch (which does report to Sentry) never sees it —
            // needs its own explicit capture here.
            io.sentry.Sentry.captureException(e);
            return new GenerationResult(false, null, "Pipeline error generating transform: " + e.getMessage());
        } finally {
            if (worktreePath != null) {
                worktreeManager.removeWorktree(worktreePath);
            }
        }
    }

    private String buildTaskBrief(String packageName, String fixtureRelativePath, FileStructureProfile profile) {
        return """
                Analyze and implement a transform for the sample file at %s.

                Package to use: com.bank.vam.transformhandlers.generated.%s

                An analysis agent already profiled this file's structure:
                - columns: %s
                - delimiter: %s
                - control-total column: %s
                - notes: %s
                """.formatted(fixtureRelativePath, packageName, profile.columns(), profile.delimiter(),
                profile.controlTotalColumn(), profile.notes());
    }
}
