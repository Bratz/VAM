package com.bank.vam.ai.copilot.action;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface ActionProposalRepository extends JpaRepository<ActionProposal, UUID> {

    List<ActionProposal> findByConversationIdOrderByCreatedAtDesc(UUID conversationId);

    /** Sweeper for expiring stale PENDING proposals (called by a future scheduled task). */
    List<ActionProposal> findByStatusAndExpiresAtBefore(
            ActionProposal.ProposalStatus status, LocalDateTime cutoff);
}
