package com.bank.vam.fileingest.events;

import com.bank.vam.fileingest.entity.IngestStage;
import com.bank.vam.fileingest.entity.TimelineEvent;
import com.bank.vam.fileingest.repository.TimelineEventRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * One call per pipeline stage transition — this is what the frontend's
 * Stepper/EventTimeline polling reads (design doc "Timeline UI" section).
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
