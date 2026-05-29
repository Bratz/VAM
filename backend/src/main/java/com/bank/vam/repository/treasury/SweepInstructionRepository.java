package com.bank.vam.repository.treasury;

import com.bank.vam.entity.treasury.SweepInstruction;
import com.bank.vam.entity.treasury.SweepInstruction.InstructionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SweepInstructionRepository extends JpaRepository<SweepInstruction, UUID> {

    Optional<SweepInstruction> findByIdempotencyKey(String idempotencyKey);

    Optional<SweepInstruction> findByExternalReference(String externalReference);

    List<SweepInstruction> findByStatus(InstructionStatus status);

    List<SweepInstruction> findByStatusIn(List<InstructionStatus> statuses);

    /** In-flight = INSTRUCTED or ACK_RECEIVED, ordered for the dashboard. */
    List<SweepInstruction> findByStatusInOrderByInstructedAtDesc(List<InstructionStatus> statuses);

    /** Retryable rejects with next_retry_at due. */
    List<SweepInstruction> findByStatusAndRejectionCategoryAndNextRetryAtBefore(
            InstructionStatus status,
            SweepInstruction.RejectionCategory category,
            LocalDateTime now);

    Page<SweepInstruction> findBySourceShadowVaIdOrderByCreatedAtDesc(UUID shadowVaId, Pageable pageable);
}
