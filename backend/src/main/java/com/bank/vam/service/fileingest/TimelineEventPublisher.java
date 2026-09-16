package com.bank.vam.service.fileingest;

import com.bank.vam.entity.fileingest.IngestStage;
import com.bank.vam.entity.fileingest.TimelineEvent;
import com.bank.vam.repository.fileingest.TimelineEventRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * One call per pipeline stage transition — this is what the frontend's
 * Stepper/EventTimeline polling reads. The separate agent worker writes to
 * this same table directly for its own stages (ANALYZED, SIGNATURE_NEW/
 * MATCHED, CODING_AGENT_RUNNING, TEST_GATE).
 */
@Component
public class TimelineEventPublisher {

    private final TimelineEventRepository repository;

    public TimelineEventPublisher(TimelineEventRepository repository) {
        this.repository = repository;
    }

    public void emit(UUID ingestJobId, IngestStage stage, String status, String detail) {
        TimelineEvent event = new TimelineEvent();
        event.setIngestJobId(ingestJobId);
        event.setStage(stage);
        event.setStatus(status);
        event.setDetail(detail);
        repository.save(event);
    }
}
