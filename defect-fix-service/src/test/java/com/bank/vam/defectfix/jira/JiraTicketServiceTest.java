package com.bank.vam.defectfix.jira;

import com.bank.vam.defectfix.detect.DefectSignature;
import com.bank.vam.defectfix.detect.DetectedDefect;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

class JiraTicketServiceTest {

    private final JiraClient jiraClient = mock(JiraClient.class);
    private final JiraTicketService ticketService = new JiraTicketService(jiraClient);

    private final DetectedDefect defect = new DetectedDefect(
            new DefectSignature("frontend-lint", "no-unused-vars:a.tsx"), "[lint] no-unused-vars in a.tsx", "details");

    @Test
    void belowChainDepthCapFilesNormallyIntoToDo() {
        when(jiraClient.createIssue(any(), any(), any(), any())).thenReturn("KAN-1");

        ticketService.fileIfNew(defect, 1, "some-branch", 2);

        verify(jiraClient, never()).transitionTo(any(), any());
        verify(jiraClient, never()).addLabel(eq("KAN-1"), eq("needs-human"));
    }

    @Test
    void atChainDepthCapEscalatesStraightToBlockedInsteadOfLeavingItForAutoPickup() {
        when(jiraClient.createIssue(any(), any(), any(), any())).thenReturn("KAN-2");

        ticketService.fileIfNew(defect, 1, "some-branch", 3);

        verify(jiraClient).addLabel("KAN-2", "needs-human");
        verify(jiraClient).transitionTo("KAN-2", "Blocked");
        verify(jiraClient).addComment(eq("KAN-2"), contains("3 consecutive automated fix commits"));
    }

    @Test
    void alreadyTicketedDefectIsSkippedRegardlessOfChainDepth() {
        when(jiraClient.existsWithLabel(any())).thenReturn(true);

        ticketService.fileIfNew(defect, 1, "some-branch", 5);

        verify(jiraClient, never()).createIssue(any(), any(), any(), any());
    }
}
