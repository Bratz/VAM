package com.bank.vam.repository.treasury;

import com.bank.vam.entity.treasury.SweepInstruction;
import com.bank.vam.entity.treasury.SweepInstruction.InstructionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
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

    /** External sweeps R10: a source with an instruction still in flight is not swept again. */
    boolean existsBySourceShadowVaIdAndStatusIn(UUID sourceShadowVaId, Collection<InstructionStatus> statuses);

    /** External sweeps R15: the instructions an end-of-day statement for the source may confirm, oldest first. */
    List<SweepInstruction> findBySourceShadowVaIdAndStatusInOrderByInstructedAtAsc(UUID sourceShadowVaId,
                                                                                   Collection<InstructionStatus> statuses);

    /** Instructed under an authority on a date and not failed: the {@code instructedToday} of its daily limit (R3). */
    @Query("select coalesce(sum(i.amount), 0) from SweepInstruction i where i.mandateId = :mandateId"
            + " and i.executionDate = :date and i.status not in :failed")
    BigDecimal instructedOn(@Param("mandateId") UUID mandateId, @Param("date") LocalDate date,
                            @Param("failed") Collection<InstructionStatus> failed);

    /**
     * External sweeps: move an instruction on only if it is still in one of {@code from}; 1 = this caller owns the move,
     * 0 = someone else (the daily job, Mark rejected, a statement) already moved it. Used before confirming or failing,
     * so a leg is never reversed or settled twice (@Version is off on BaseEntity).
     */
    @Modifying
    @Query("update SweepInstruction i set i.status = :to where i.id = :id and i.status in :from")
    int claim(@Param("id") UUID id, @Param("to") InstructionStatus to, @Param("from") Collection<InstructionStatus> from);

    /** Of these statement lines, the ones that already confirm an instruction (a line confirms one at most). */
    @Query("select i.confirmedByLineId from SweepInstruction i where i.confirmedByLineId in :lineIds")
    List<UUID> usedLines(@Param("lineIds") Collection<UUID> lineIds);

    /** The execution view: one instruction per execution (external-bank sweeps R19). */
    Optional<SweepInstruction> findFirstBySweepExecutionId(UUID sweepExecutionId);
}
