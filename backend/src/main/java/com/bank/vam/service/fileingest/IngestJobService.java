package com.bank.vam.service.fileingest;

import com.bank.vam.config.FileIngestProperties;
import com.bank.vam.entity.fileingest.IngestDomain;
import com.bank.vam.entity.fileingest.IngestJob;
import com.bank.vam.entity.fileingest.IngestStage;
import com.bank.vam.repository.fileingest.IngestJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Stage a new upload end to end — save the file, create the ingest_job row,
 * emit the first timeline event, then run the standard pipeline
 * (IngestOrchestrator). Ticket filing only happens later, on the
 * unrecognized-format error path, not on every upload.
 */
@Service
public class IngestJobService {

    private static final Logger log = LoggerFactory.getLogger(IngestJobService.class);

    private final IngestJobRepository jobRepository;
    private final TimelineEventPublisher timelinePublisher;
    private final IngestOrchestrator orchestrator;
    private final Path workspaceRoot;

    public IngestJobService(IngestJobRepository jobRepository,
                             TimelineEventPublisher timelinePublisher,
                             IngestOrchestrator orchestrator,
                             FileIngestProperties properties) {
        this.jobRepository = jobRepository;
        this.timelinePublisher = timelinePublisher;
        this.orchestrator = orchestrator;
        this.workspaceRoot = Path.of(properties.getWorkspaceDir());
    }

    public IngestJob receiveUpload(String customerId, IngestDomain domain, MultipartFile file) {
        IngestJob job = new IngestJob();
        job.setCustomerId(customerId);
        job.setDomain(domain);
        job.setOriginalFilename(file.getOriginalFilename());
        job.setStage(IngestStage.RECEIVED);
        // Save first so the job has an id to build the workspace path from — workspacePath is
        // genuinely nullable for exactly this window (see the entity's own doc comment).
        job = jobRepository.save(job);

        String storedFilename = "source" + extensionOf(file.getOriginalFilename());
        Path jobDir = workspaceRoot.resolve(job.getId().toString());
        Path destination = jobDir.resolve(storedFilename);
        try {
            Files.createDirectories(jobDir);
            file.transferTo(destination);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to store uploaded file for job " + job.getId(), e);
        }
        job.setWorkspacePath(job.getId() + "/" + storedFilename);
        job = jobRepository.save(job);

        timelinePublisher.emit(job.getId(), IngestStage.RECEIVED, "COMPLETE", "File received.");

        try {
            orchestrator.runJob(job.getId());
        } catch (Exception e) {
            log.error("Ingest pipeline failed for job {}", job.getId(), e);
            job.setStage(IngestStage.BLOCKED);
            job.setBlockedReason("Pipeline error: " + e.getMessage());
            jobRepository.save(job);
            timelinePublisher.emit(job.getId(), IngestStage.BLOCKED, "COMPLETE", job.getBlockedReason());
        }

        return jobRepository.findById(job.getId()).orElse(job);
    }

    private String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot) : "";
    }
}
