package com.bank.vam.dto.fileingest;

import com.bank.vam.entity.fileingest.IngestStage;
import com.bank.vam.entity.fileingest.TimelineEvent;

import java.time.Instant;

public record TimelineEventResponse(
        IngestStage stage,
        String status,
        String detail,
        String actor,
        Instant occurredAt
) {
    public static TimelineEventResponse from(TimelineEvent event) {
        return new TimelineEventResponse(
                event.getStage(),
                event.getStatus(),
                event.getDetail(),
                event.getActor(),
                event.getOccurredAt()
        );
    }
}
