package com.bank.vam.repository.fileingest;

import com.bank.vam.entity.fileingest.TimelineEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TimelineEventRepository extends JpaRepository<TimelineEvent, UUID> {

    List<TimelineEvent> findByIngestJobIdOrderByOccurredAtAsc(UUID ingestJobId);
}
