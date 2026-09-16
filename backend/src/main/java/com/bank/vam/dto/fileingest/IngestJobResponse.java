package com.bank.vam.dto.fileingest;

import com.bank.vam.entity.fileingest.IngestDomain;
import com.bank.vam.entity.fileingest.IngestJob;
import com.bank.vam.entity.fileingest.IngestStage;

import java.time.Instant;
import java.util.UUID;

public record IngestJobResponse(
        UUID id,
        String customerId,
        IngestDomain domain,
        String originalFilename,
        IngestStage stage,
        String jiraTicketKey,
        String blockedReason,
        Instant createdAt,
        Instant updatedAt
) {
    public static IngestJobResponse from(IngestJob job) {
        return new IngestJobResponse(
                job.getId(),
                job.getCustomerId(),
                job.getDomain(),
                job.getOriginalFilename(),
                job.getStage(),
                job.getJiraTicketKey(),
                job.getBlockedReason(),
                job.getCreatedAt(),
                job.getUpdatedAt()
        );
    }
}
