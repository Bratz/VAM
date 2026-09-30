package com.bank.vam.controller.fileingest;

import com.bank.vam.dto.fileingest.IngestJobResponse;
import com.bank.vam.dto.fileingest.StagedRowResponse;
import com.bank.vam.dto.fileingest.TimelineEventResponse;
import com.bank.vam.entity.fileingest.IngestDomain;
import com.bank.vam.entity.fileingest.IngestJob;
import com.bank.vam.repository.fileingest.IngestJobRepository;
import com.bank.vam.repository.fileingest.StagedTransactionRepository;
import com.bank.vam.repository.fileingest.TimelineEventRepository;
import com.bank.vam.service.fileingest.IngestJobService;
import com.bank.vam.entity.fileingest.RowStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/** Upload + status/timeline/row reads for the file-ingest pipeline (same REST
 * surface the frontend already calls; formerly served by the now-retired
 * file-ingest-agent-service). */
@RestController
@RequestMapping("/api/v1/ingest")
public class FileIngestController {

    private static final int MAX_JOBS_LISTED = 100;
    private static final int MAX_ROWS_PER_PAGE = 500;

    private final IngestJobService ingestJobService;
    private final IngestJobRepository ingestJobRepository;
    private final TimelineEventRepository timelineEventRepository;
    private final StagedTransactionRepository stagedTransactionRepository;

    public FileIngestController(IngestJobService ingestJobService, IngestJobRepository ingestJobRepository,
                                 TimelineEventRepository timelineEventRepository,
                                 StagedTransactionRepository stagedTransactionRepository) {
        this.ingestJobService = ingestJobService;
        this.ingestJobRepository = ingestJobRepository;
        this.timelineEventRepository = timelineEventRepository;
        this.stagedTransactionRepository = stagedTransactionRepository;
    }

    /** Most recent uploads first, capped rather than paginated — this is an ops/support view,
     * not a customer-facing list expected to grow into the thousands. */
    @GetMapping("/jobs")
    public List<IngestJobResponse> listJobs() {
        List<IngestJob> jobs = ingestJobRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt")).stream()
                .limit(MAX_JOBS_LISTED)
                .toList();
        Map<UUID, IngestJobResponse.RowCounts> counts = rowCountsFor(jobs.stream().map(IngestJob::getId).toList());
        return jobs.stream()
                .map(job -> IngestJobResponse.from(job, counts.get(job.getId())))
                .toList();
    }

    /**
     * Row counts keyed by job. One grouped query for the whole page of jobs: the list shows up to
     * MAX_JOBS_LISTED of them and every one needs counts, which per-job would be a query each.
     */
    private Map<UUID, IngestJobResponse.RowCounts> rowCountsFor(List<UUID> jobIds) {
        if (jobIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Map<RowStatus, Long>> byJob = new HashMap<>();
        for (Object[] row : stagedTransactionRepository.countByStatusForJobs(jobIds)) {
            byJob.computeIfAbsent((UUID) row[0], k -> new EnumMap<>(RowStatus.class))
                    .put((RowStatus) row[1], (Long) row[2]);
        }
        Map<UUID, IngestJobResponse.RowCounts> out = new HashMap<>();
        byJob.forEach((jobId, statuses) -> {
            long total = statuses.values().stream().mapToLong(Long::longValue).sum();
            out.put(jobId, new IngestJobResponse.RowCounts(
                    total,
                    statuses.getOrDefault(RowStatus.PROCESSED, 0L),
                    statuses.getOrDefault(RowStatus.QUARANTINED, 0L),
                    statuses.getOrDefault(RowStatus.FAILED, 0L)));
        });
        return out;
    }

    @PostMapping(value = "/{domain}/upload", consumes = "multipart/form-data")
    public ResponseEntity<IngestJobResponse> upload(
            @PathVariable IngestDomain domain,
            @RequestParam String customerId,
            @RequestParam("file") MultipartFile file) {
        IngestJob job = ingestJobService.receiveUpload(customerId, domain, file);
        // With counts: a recognised format runs the whole pipeline before this returns, so the
        // rows already exist and answering with zeros would misreport the upload the caller just
        // made. An unrecognised format stops at AWAITING_TRANSFORM with no rows, and zeros are
        // then the truth.
        return ResponseEntity.ok(IngestJobResponse.from(job, rowCountsFor(List.of(job.getId())).get(job.getId())));
    }

    @GetMapping("/jobs/{id}")
    public ResponseEntity<IngestJobResponse> getJob(@PathVariable UUID id) {
        IngestJob job = ingestJobRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("No ingest job " + id));
        return ResponseEntity.ok(IngestJobResponse.from(job, rowCountsFor(List.of(id)).get(id)));
    }

    @GetMapping("/jobs/{id}/timeline")
    public List<TimelineEventResponse> getTimeline(@PathVariable UUID id) {
        return timelineEventRepository.findByIngestJobIdOrderByOccurredAtAsc(id).stream()
                .map(TimelineEventResponse::from)
                .toList();
    }

    /** Per-row detail (status, failure reason, the real entity a PROCESSED row became) — the
     * record-wise audit trail StagedTransaction was already built for, just never exposed. */
    /**
     * Paged, unlike its first version. Bulk ingest is the point of this feature, so returning and
     * rendering every row of a large file was a shape that only held while the test files were
     * three lines long. The jobs list above has always been capped for the same reason.
     *
     * <p>Kept as a bare array rather than a Page envelope so the existing caller keeps working;
     * total row counts are on the job itself now, which is what the page needs for its scorecard.
     */
    @GetMapping("/jobs/{id}/rows")
    public List<StagedRowResponse> getRows(@PathVariable UUID id,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "200") int size) {
        int capped = Math.min(Math.max(size, 1), MAX_ROWS_PER_PAGE);
        return stagedTransactionRepository
                .findByIngestJobIdOrderBySourceRowNumberAsc(id, PageRequest.of(Math.max(page, 0), capped))
                .getContent().stream()
                .map(StagedRowResponse::from)
                .toList();
    }
}
