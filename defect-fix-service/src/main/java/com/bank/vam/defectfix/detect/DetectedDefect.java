package com.bank.vam.defectfix.detect;

/** A defect with enough detail to file as a Jira ticket. */
public record DetectedDefect(DefectSignature signature, String summary, String details) {
}
