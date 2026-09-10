package com.bank.vam.defectfix.detect;

import com.bank.vam.defectfix.github.GitHubArtifactClient;
import com.bank.vam.defectfix.jira.JiraTicketService;
import com.bank.vam.defectfix.orchestrate.TriageOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Given a failed CI run on a PR, works out which of its defects are actually
 * NEW (not already present at the PR's merge-base) and files a ticket for
 * each. Pre-existing failures at the merge-base are left alone — see
 * tasks/defect-autofix-pipeline-design.md, "Backlog handling".
 */
@Service
public class DefectDetectionService {

    private static final Logger log = LoggerFactory.getLogger(DefectDetectionService.class);

    private final GitHubArtifactClient artifactClient;
    private final FrontendResultParser frontendParser;
    private final BackendResultParser backendParser;
    private final JiraTicketService ticketService;
    private final TriageOrchestrator triageOrchestrator;

    public DefectDetectionService(GitHubArtifactClient artifactClient,
                                   FrontendResultParser frontendParser,
                                   BackendResultParser backendParser,
                                   JiraTicketService ticketService,
                                   TriageOrchestrator triageOrchestrator) {
        this.artifactClient = artifactClient;
        this.frontendParser = frontendParser;
        this.backendParser = backendParser;
        this.ticketService = ticketService;
        this.triageOrchestrator = triageOrchestrator;
    }

    public enum Stack {
        FRONTEND("frontend-checks-"),
        BACKEND("backend-checks-");

        final String artifactPrefix;

        Stack(String artifactPrefix) {
            this.artifactPrefix = artifactPrefix;
        }
    }

    public void handleFailedPrRun(Stack stack, String baseSha, String headSha, int prNumber) throws Exception {
        List<DetectedDefect> headDefects = defectsAt(stack, headSha);
        if (headDefects.isEmpty()) {
            log.debug("No parseable defects in {} artifact at {}", stack, headSha);
            return;
        }

        String mergeBaseSha = artifactClient.findMergeBaseSha(baseSha, headSha);
        List<DetectedDefect> baselineDefects = defectsAt(stack, mergeBaseSha);
        if (baselineDefects.isEmpty()) {
            // ponytail: no baseline artifact found for this exact merge-base commit (e.g. it predates
            // this pipeline, or its own CI run hasn't finished yet) — everything at HEAD is treated as
            // new rather than silently skipping the PR. Ceiling: a genuinely pre-existing defect could
            // get ticketed once if its merge-base's own artifact isn't available yet; upgrade path is to
            // walk back to the nearest ancestor commit that DOES have an artifact, if this proves noisy.
            log.warn("No baseline artifact for {} at merge-base {} — treating all HEAD defects as new", stack, mergeBaseSha);
        }

        Set<DefectSignature> baselineSignatures = baselineDefects.stream()
                .map(DetectedDefect::signature)
                .collect(Collectors.toSet());

        List<DetectedDefect> newDefects = headDefects.stream()
                .filter(d -> !baselineSignatures.contains(d.signature()))
                .toList();

        log.info("{} defects at HEAD, {} of them new (PR #{})", headDefects.size(), newDefects.size(), prNumber);
        for (DetectedDefect defect : newDefects) {
            ticketService.fileIfNew(defect, prNumber);
        }
        if (!newDefects.isEmpty()) {
            triageOrchestrator.tryStartProcessing();
        }
    }

    private List<DetectedDefect> defectsAt(Stack stack, String sha) throws Exception {
        Optional<Long> artifactId = artifactClient.findArtifactId(stack.artifactPrefix + sha);
        if (artifactId.isEmpty()) {
            return List.of();
        }
        Map<String, String> files = artifactClient.downloadAndUnzip(artifactId.get());

        if (stack == Stack.FRONTEND) {
            List<DetectedDefect> defects = new java.util.ArrayList<>();
            defects.addAll(frontendParser.parseEslintJson(files.get("eslint.json")));
            defects.addAll(frontendParser.parseTscLog(files.get("tsc.log")));
            return defects;
        } else {
            Map<String, String> surefireFiles = files.entrySet().stream()
                    .filter(e -> e.getKey().endsWith(".xml"))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            return backendParser.parseSurefireReports(surefireFiles);
        }
    }
}
