package com.bank.vam.controller.fileingest;

import com.bank.vam.dto.fileingest.IngestJobResponse;
import com.bank.vam.dto.fileingest.TimelineEventResponse;
import com.bank.vam.entity.fileingest.IngestDomain;
import com.bank.vam.entity.fileingest.IngestJob;
import com.bank.vam.repository.fileingest.IngestJobRepository;
import com.bank.vam.repository.fileingest.TimelineEventRepository;
import com.bank.vam.service.fileingest.IngestJobService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/** Upload + status/timeline reads for the file-ingest pipeline (same REST
 * surface the frontend already calls; formerly served by the now-retired
 * file-ingest-agent-service). */
@RestController
@RequestMapping("/api/v1/ingest")
public class FileIngestController {

    private final IngestJobService ingestJobService;
    private final IngestJobRepository ingestJobRepository;
    private final TimelineEventRepository timelineEventRepository;

    public FileIngestController(IngestJobService ingestJobService, IngestJobRepository ingestJobRepository,
                                 TimelineEventRepository timelineEventRepository) {
        this.ingestJobService = ingestJobService;
        this.ingestJobRepository = ingestJobRepository;
        this.timelineEventRepository = timelineEventRepository;
    }

    @PostMapping(value = "/{domain}/upload", consumes = "multipart/form-data")
    public ResponseEntity<IngestJobResponse> upload(
            @PathVariable IngestDomain domain,
            @RequestParam String customerId,
            @RequestParam("file") MultipartFile file) {
        IngestJob job = ingestJobService.receiveUpload(customerId, domain, file);
        return ResponseEntity.ok(IngestJobResponse.from(job));
    }

    @GetMapping("/jobs/{id}")
    public ResponseEntity<IngestJobResponse> getJob(@PathVariable UUID id) {
        IngestJob job = ingestJobRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("No ingest job " + id));
        return ResponseEntity.ok(IngestJobResponse.from(job));
    }

    @GetMapping("/jobs/{id}/timeline")
    public List<TimelineEventResponse> getTimeline(@PathVariable UUID id) {
        return timelineEventRepository.findByIngestJobIdOrderByOccurredAtAsc(id).stream()
                .map(TimelineEventResponse::from)
                .toList();
    }
}
