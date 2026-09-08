package com.bank.vam.repository.treasury;

import com.bank.vam.entity.treasury.ExceptionTransaction;
import com.bank.vam.entity.treasury.ExceptionTransaction.ExceptionStatus;
import com.bank.vam.entity.treasury.ExceptionTransaction.ExceptionType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for ExceptionTransaction entity.
 * Provides queries for managing exception transactions in the Exception VA.
 */
@Repository
public interface ExceptionTransactionRepository extends JpaRepository<ExceptionTransaction, UUID> {

    // ========================================================================
    // BASIC LOOKUPS
    // ========================================================================

    /**
     * Find by exception number.
     */
    Optional<ExceptionTransaction> findByExceptionNumber(String exceptionNumber);

    /**
     * Find by status.
     */
    List<ExceptionTransaction> findByStatus(ExceptionStatus status);

    /**
     * Find by status with pagination.
     */
    Page<ExceptionTransaction> findByStatus(ExceptionStatus status, Pageable pageable);

    /**
     * Find by exception type.
     */
    List<ExceptionTransaction> findByExceptionType(ExceptionType exceptionType);

    /**
     * Find by exception type with pagination.
     */
    Page<ExceptionTransaction> findByExceptionType(ExceptionType exceptionType, Pageable pageable);

    // ========================================================================
    // EXCEPTION VA QUERIES
    // ========================================================================

    /**
     * Find all exceptions for an Exception VA.
     */
    List<ExceptionTransaction> findByExceptionVaId(UUID exceptionVaId);

    /**
     * Find all exceptions for an Exception VA with pagination.
     */
    Page<ExceptionTransaction> findByExceptionVaId(UUID exceptionVaId, Pageable pageable);

    /**
     * Find exceptions by Exception VA and status.
     */
    List<ExceptionTransaction> findByExceptionVaIdAndStatus(UUID exceptionVaId, ExceptionStatus status);

    /**
     * Find exceptions by Exception VA and status with pagination.
     */
    Page<ExceptionTransaction> findByExceptionVaIdAndStatus(
        UUID exceptionVaId, ExceptionStatus status, Pageable pageable);

    /**
     * Find open exceptions for an Exception VA.
     */
    @Query("SELECT e FROM ExceptionTransaction e WHERE e.exceptionVaId = :vaId " +
           "AND e.status IN ('OPEN', 'IN_PROGRESS') ORDER BY e.createdAt DESC")
    List<ExceptionTransaction> findOpenByExceptionVaId(@Param("vaId") UUID vaId);

    // ========================================================================
    // PROGRAM QUERIES
    // ========================================================================

    /**
     * Find open exceptions for a program (via Exception VA).
     */
    @Query("SELECT e FROM ExceptionTransaction e " +
           "JOIN VirtualAccount v ON e.exceptionVaId = v.id " +
           "WHERE v.programId = :programId AND e.status IN ('OPEN', 'IN_PROGRESS') " +
           "ORDER BY e.createdAt DESC")
    List<ExceptionTransaction> findOpenByProgramId(@Param("programId") UUID programId);

    /**
     * Find open exceptions for a program with pagination.
     */
    @Query("SELECT e FROM ExceptionTransaction e " +
           "JOIN VirtualAccount v ON e.exceptionVaId = v.id " +
           "WHERE v.programId = :programId AND e.status IN ('OPEN', 'IN_PROGRESS') " +
           "ORDER BY e.createdAt DESC")
    Page<ExceptionTransaction> findOpenByProgramId(@Param("programId") UUID programId, Pageable pageable);

    /**
     * Find all exceptions for a program.
     */
    @Query("SELECT e FROM ExceptionTransaction e " +
           "JOIN VirtualAccount v ON e.exceptionVaId = v.id " +
           "WHERE v.programId = :programId ORDER BY e.createdAt DESC")
    Page<ExceptionTransaction> findByProgramId(@Param("programId") UUID programId, Pageable pageable);

    // ========================================================================
    // ORIGINAL VA QUERIES
    // ========================================================================

    /**
     * Find exceptions by original VA.
     */
    List<ExceptionTransaction> findByOriginalVaId(UUID originalVaId);

    /**
     * Find exceptions by original transaction.
     */
    List<ExceptionTransaction> findByOriginalTransactionId(UUID originalTransactionId);

    // ========================================================================
    // CURRENCY QUERIES
    // ========================================================================

    /**
     * Find exceptions by currency.
     */
    List<ExceptionTransaction> findByCurrencyCode(String currencyCode);

    /**
     * Find open exceptions by currency.
     */
    @Query("SELECT e FROM ExceptionTransaction e WHERE e.currencyCode = :currency " +
           "AND e.status IN ('OPEN', 'IN_PROGRESS') ORDER BY e.createdAt DESC")
    List<ExceptionTransaction> findOpenByCurrency(@Param("currency") String currency);

    // ========================================================================
    // COUNT QUERIES
    // ========================================================================

    /**
     * Count by status.
     */
    long countByStatus(ExceptionStatus status);

    /**
     * Count by Exception VA and status.
     */
    long countByExceptionVaIdAndStatus(UUID exceptionVaId, ExceptionStatus status);

    /**
     * Count open exceptions by Exception VA.
     */
    @Query("SELECT COUNT(e) FROM ExceptionTransaction e WHERE e.exceptionVaId = :vaId " +
           "AND e.status IN ('OPEN', 'IN_PROGRESS')")
    long countOpenByExceptionVaId(@Param("vaId") UUID vaId);

    /**
     * Count by exception type.
     */
    long countByExceptionType(ExceptionType exceptionType);

    /**
     * Count open exceptions by program.
     */
    @Query("SELECT COUNT(e) FROM ExceptionTransaction e " +
           "JOIN VirtualAccount v ON e.exceptionVaId = v.id " +
           "WHERE v.programId = :programId AND e.status IN ('OPEN', 'IN_PROGRESS')")
    long countOpenByProgramId(@Param("programId") UUID programId);

    // ========================================================================
    // SUM QUERIES
    // ========================================================================

    /**
     * Sum open exception amounts by Exception VA.
     */
    @Query("SELECT COALESCE(SUM(e.amount), 0) FROM ExceptionTransaction e " +
           "WHERE e.exceptionVaId = :vaId AND e.status IN ('OPEN', 'IN_PROGRESS')")
    BigDecimal sumOpenAmountByExceptionVaId(@Param("vaId") UUID vaId);

    /**
     * Sum open exception amounts by program.
     */
    @Query("SELECT COALESCE(SUM(e.amount), 0) FROM ExceptionTransaction e " +
           "JOIN VirtualAccount v ON e.exceptionVaId = v.id " +
           "WHERE v.programId = :programId AND e.status IN ('OPEN', 'IN_PROGRESS')")
    BigDecimal sumOpenAmountByProgramId(@Param("programId") UUID programId);

    /**
     * Sum amounts by exception type.
     */
    @Query("SELECT COALESCE(SUM(e.amount), 0) FROM ExceptionTransaction e " +
           "WHERE e.exceptionType = :type AND e.status IN ('OPEN', 'IN_PROGRESS')")
    BigDecimal sumOpenAmountByType(@Param("type") ExceptionType type);

    // ========================================================================
    // AGING QUERIES
    // ========================================================================

    /**
     * Find exceptions older than specified days.
     */
    @Query("SELECT e FROM ExceptionTransaction e WHERE e.status IN ('OPEN', 'IN_PROGRESS') " +
           "AND e.createdAt < :threshold ORDER BY e.createdAt ASC")
    List<ExceptionTransaction> findAgedExceptions(@Param("threshold") LocalDateTime threshold);

    /**
     * Find exceptions created in date range.
     */
    @Query("SELECT e FROM ExceptionTransaction e WHERE e.createdAt BETWEEN :startDate AND :endDate " +
           "ORDER BY e.createdAt DESC")
    List<ExceptionTransaction> findByCreatedAtBetween(
        @Param("startDate") LocalDateTime startDate, 
        @Param("endDate") LocalDateTime endDate);

    // ========================================================================
    // SUMMARY QUERIES
    // ========================================================================

    /**
     * Get exception summary by status.
     */
    @Query("SELECT e.status, COUNT(e), COALESCE(SUM(e.amount), 0) FROM ExceptionTransaction e " +
           "GROUP BY e.status ORDER BY e.status")
    List<Object[]> getSummaryByStatus();

    /**
     * Get exception summary by type for open exceptions.
     */
    @Query("SELECT e.exceptionType, COUNT(e), COALESCE(SUM(e.amount), 0) FROM ExceptionTransaction e " +
           "WHERE e.status IN ('OPEN', 'IN_PROGRESS') GROUP BY e.exceptionType ORDER BY e.exceptionType")
    List<Object[]> getOpenSummaryByType();

    /**
     * Get exception summary by Exception VA.
     */
    @Query("SELECT e.exceptionVaId, COUNT(e), COALESCE(SUM(e.amount), 0) FROM ExceptionTransaction e " +
           "WHERE e.status IN ('OPEN', 'IN_PROGRESS') GROUP BY e.exceptionVaId")
    List<Object[]> getOpenSummaryByExceptionVa();

    // ========================================================================
    // UPDATE QUERIES
    // ========================================================================

    /**
     * Update status.
     */
    @Modifying
    @Query("UPDATE ExceptionTransaction e SET e.status = :status WHERE e.id = :id")
    int updateStatus(@Param("id") UUID id, @Param("status") ExceptionStatus status);

    /**
     * Start investigation (update status to IN_PROGRESS).
     */
    @Modifying
    @Query("UPDATE ExceptionTransaction e SET e.status = 'IN_PROGRESS', e.resolvedBy = :investigator " +
           "WHERE e.id = :id AND e.status = 'OPEN'")
    int startInvestigation(@Param("id") UUID id, @Param("investigator") String investigator);

    // ========================================================================
    // SEARCH QUERIES
    // ========================================================================

    /**
     * Search by bank reference.
     */
    @Query("SELECT e FROM ExceptionTransaction e WHERE LOWER(e.bankReference) LIKE LOWER(CONCAT('%', :query, '%'))")
    List<ExceptionTransaction> searchByBankReference(@Param("query") String query);

    /**
     * Search by remitter info.
     */
    @Query("SELECT e FROM ExceptionTransaction e WHERE LOWER(e.remitterInfo) LIKE LOWER(CONCAT('%', :query, '%'))")
    List<ExceptionTransaction> searchByRemitterInfo(@Param("query") String query);

    /**
     * General search.
     */
    @Query("SELECT e FROM ExceptionTransaction e WHERE " +
           "LOWER(e.exceptionNumber) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(e.bankReference) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(e.remitterInfo) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(e.description) LIKE LOWER(CONCAT('%', :query, '%'))")
    Page<ExceptionTransaction> search(@Param("query") String query, Pageable pageable);

    // ========================================================================
    // FILTER QUERIES
    // ========================================================================

    /**
     * Find with multiple filters.
     */
    @Query("SELECT e FROM ExceptionTransaction e " +
           "JOIN VirtualAccount v ON e.exceptionVaId = v.id " +
           "WHERE (:programId IS NULL OR v.programId = :programId) " +
           "AND (:status IS NULL OR e.status = :status) " +
           "AND (:type IS NULL OR e.exceptionType = :type) " +
           "AND (:currency IS NULL OR e.currencyCode = :currency) " +
           "ORDER BY e.createdAt DESC")
    Page<ExceptionTransaction> findWithFilters(
        @Param("programId") UUID programId,
        @Param("status") ExceptionStatus status,
        @Param("type") ExceptionType type,
        @Param("currency") String currency,
        Pageable pageable);

    /**
     * Corporate-scoped equivalent of {@link #findWithFilters} — {@code
     * ExceptionTransaction} has no {@code corporateId} column, only reachable
     * via {@code exceptionVaId -> VirtualAccount.corporateId}. Added for
     * get_exceptions (MCP tool): a caller entitled to one corporate must never
     * see another's exceptions, and the existing programId-based filter can't
     * express that.
     */
    @Query("SELECT e FROM ExceptionTransaction e " +
           "JOIN VirtualAccount v ON e.exceptionVaId = v.id " +
           "WHERE v.corporateId = :corporateId " +
           "AND (:status IS NULL OR e.status = :status) " +
           "AND (:type IS NULL OR e.exceptionType = :type) " +
           "AND (:currency IS NULL OR e.currencyCode = :currency) " +
           "ORDER BY e.createdAt DESC")
    Page<ExceptionTransaction> findWithFiltersByCorporate(
        @Param("corporateId") UUID corporateId,
        @Param("status") ExceptionStatus status,
        @Param("type") ExceptionType type,
        @Param("currency") String currency,
        Pageable pageable);
}