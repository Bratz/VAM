package com.bank.vam.statement;

import com.bank.vam.statement.BankStatement.Status;
import com.bank.vam.statement.parse.ParsedStatement.Kind;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BankStatementRepository extends JpaRepository<BankStatement, UUID> {

    /** Intake log: the statements of the listed files, in file order. */
    List<BankStatement> findByFileIdInOrderByCreatedAtAsc(Collection<UUID> fileIds);

    /** Account view: the account's latest statements of a status, newest first. */
    List<BankStatement> findByPhysicalAccountIdAndStatusOrderByStatementDateDescCreatedAtDesc(UUID physicalAccountId, Status status, Pageable page);

    boolean existsByDuplicateKeyAndStatus(String duplicateKey, Status status);

    /** The latest statement of a kind for the account, by statement date (the older check uses END_OF_DAY, APPLIED). */
    Optional<BankStatement> findFirstByPhysicalAccountIdAndKindAndStatusOrderByStatementDateDescCreatedAtDesc(
            UUID physicalAccountId, Kind kind, Status status);

    /** The latest statement of a kind for a shadow (external sweeps size on the last APPLIED END_OF_DAY one, D6). */
    Optional<BankStatement> findFirstByShadowVaIdAndKindAndStatusOrderByStatementDateDescCreatedAtDesc(
            UUID shadowVaId, Kind kind, Status status);

    /** The previous statement of a kind before {@code date} (gap and opening-balance checks). */
    Optional<BankStatement> findFirstByPhysicalAccountIdAndKindAndStatusAndStatementDateLessThanOrderByStatementDateDescCreatedAtDesc(
            UUID physicalAccountId, Kind kind, Status status, LocalDate date);

    /**
     * An end-of-day statement taken in for the shadow after {@code after} that reset its ledger (APPLIED, not older than
     * the latest, not CBS-fed): a failed external sweep then leaves the source's ledger as the statement set it.
     */
    boolean existsByShadowVaIdAndKindAndStatusAndOlderFalseAndCbsFedFalseAndCreatedAtAfter(
            UUID shadowVaId, Kind kind, Status status, java.time.LocalDateTime after);

    /** External sweeps: the shadow's applied, not CBS-fed statements of a kind dated on or after {@code from}. */
    List<BankStatement> findByShadowVaIdAndKindAndStatusAndCbsFedFalseAndStatementDateGreaterThanEqual(
            UUID shadowVaId, Kind kind, Status status, LocalDate from);

    List<BankStatement> findByPhysicalAccountIdAndKindAndStatementDateLessThanEqual(UUID physicalAccountId, Kind kind, LocalDate date);

    /** [shadowVaId, latest statement date] of the given kind and status, per shadow (freshness, D6). */
    @Query("select s.shadowVaId, max(s.statementDate) from BankStatement s where s.shadowVaId in :shadowIds"
            + " and s.kind = :kind and s.status = :status group by s.shadowVaId")
    List<Object[]> latestDates(@Param("shadowIds") Collection<UUID> shadowIds, @Param("kind") Kind kind, @Param("status") Status status);
}
