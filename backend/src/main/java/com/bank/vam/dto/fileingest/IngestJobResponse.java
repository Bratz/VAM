package com.bank.vam.dto.fileingest;

import com.bank.vam.entity.fileingest.IngestDomain;
import com.bank.vam.entity.fileingest.IngestJob;
import com.bank.vam.entity.fileingest.IngestStage;

import java.time.Instant;
import java.util.UUID;

/**
 * Row counts travel with the job because the stage alone cannot say whether the upload worked.
 * DONE means the pipeline ran to completion, not that anything posted -- a file whose every row
 * failed reaches exactly the same terminal stage as one where every row succeeded. Without these
 * counts the uploads list showed both as "Done" and gave no way to tell them apart short of
 * opening each job.
 *
 * <p>Deliberately not a new IngestStage value: this column is written by two separate processes
 * (see IngestStage), so adding a terminal state means the agent worker has to understand it too.
 * The counts describe the outcome without changing the contract between them.
 */
public record IngestJobResponse(
        UUID id,
        String customerId,
        IngestDomain domain,
        String originalFilename,
        IngestStage stage,
        String jiraTicketKey,
        String blockedReason,
        Instant createdAt,
        Instant updatedAt,
        RowCounts rows
) {
    /** {@code total} is every staged row, so STAGED/READY rows in flight are total - the rest. */
    public record RowCounts(long total, long processed, long quarantined, long failed) {
        public static final RowCounts NONE = new RowCounts(0, 0, 0, 0);

        /** True once the pipeline has finished and not one row made it through. */
        public boolean nothingPosted() {
            return total > 0 && processed == 0;
        }
    }

    public static IngestJobResponse from(IngestJob job) {
        return from(job, RowCounts.NONE);
    }

    public static IngestJobResponse from(IngestJob job, RowCounts rows) {
        return new IngestJobResponse(
                job.getId(),
                job.getCustomerId(),
                job.getDomain(),
                job.getOriginalFilename(),
                job.getStage(),
                job.getJiraTicketKey(),
                job.getBlockedReason(),
                job.getCreatedAt(),
                job.getUpdatedAt(),
                rows == null ? RowCounts.NONE : rows
        );
    }
}
