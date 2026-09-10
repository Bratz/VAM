package com.bank.vam.defectfix.detect;

import com.bank.vam.defectfix.github.GitHubArtifactClient;
import com.bank.vam.defectfix.jira.JiraTicketService;
import com.bank.vam.defectfix.orchestrate.TriageOrchestrator;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class DefectDetectionServiceTest {

    private final GitHubArtifactClient artifactClient = mock(GitHubArtifactClient.class);
    private final JiraTicketService ticketService = mock(JiraTicketService.class);
    private final TriageOrchestrator triageOrchestrator = mock(TriageOrchestrator.class);
    private final DefectDetectionService service = new DefectDetectionService(
            artifactClient, new FrontendResultParser(), new BackendResultParser(), ticketService, triageOrchestrator);

    @Test
    void onlyFilesDefectsThatAreNewComparedToTheMergeBase() throws Exception {
        String headEslint = """
                [{"filePath": "a.tsx", "messages": [
                    {"ruleId": "ruleA", "severity": 2, "line": 1, "message": "already existed"},
                    {"ruleId": "ruleB", "severity": 2, "line": 2, "message": "brand new"}
                ]}]
                """;
        String baselineEslint = """
                [{"filePath": "a.tsx", "messages": [
                    {"ruleId": "ruleA", "severity": 2, "line": 1, "message": "already existed"}
                ]}]
                """;

        when(artifactClient.findArtifactId("frontend-checks-head1")).thenReturn(Optional.of(1L));
        when(artifactClient.downloadAndUnzip(1L)).thenReturn(Map.of("eslint.json", headEslint));
        when(artifactClient.findMergeBaseSha("base1", "head1")).thenReturn("merge1");
        when(artifactClient.findArtifactId("frontend-checks-merge1")).thenReturn(Optional.of(2L));
        when(artifactClient.downloadAndUnzip(2L)).thenReturn(Map.of("eslint.json", baselineEslint));

        service.handleFailedPrRun(DefectDetectionService.Stack.FRONTEND, "base1", "head1", "some-branch", 42);

        ArgumentCaptor<DetectedDefect> filed = ArgumentCaptor.forClass(DetectedDefect.class);
        verify(ticketService, times(1)).fileIfNew(filed.capture(), eq(42), eq("some-branch"));
        assertThat(filed.getValue().signature().key()).isEqualTo("ruleB:a.tsx");
    }

    @Test
    void noBaselineArtifactMeansEverythingAtHeadIsTreatedAsNew() throws Exception {
        String headEslint = """
                [{"filePath": "a.tsx", "messages": [{"ruleId": "ruleA", "severity": 2, "line": 1, "message": "m"}]}]
                """;

        when(artifactClient.findArtifactId("frontend-checks-head1")).thenReturn(Optional.of(1L));
        when(artifactClient.downloadAndUnzip(1L)).thenReturn(Map.of("eslint.json", headEslint));
        when(artifactClient.findMergeBaseSha("base1", "head1")).thenReturn("merge1");
        when(artifactClient.findArtifactId("frontend-checks-merge1")).thenReturn(Optional.empty());

        service.handleFailedPrRun(DefectDetectionService.Stack.FRONTEND, "base1", "head1", "some-branch", 7);

        verify(ticketService, times(1)).fileIfNew(any(), eq(7), eq("some-branch"));
    }
}
