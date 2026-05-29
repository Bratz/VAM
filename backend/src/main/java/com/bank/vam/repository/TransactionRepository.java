package com.bank.vam.repository;

import com.bank.vam.entity.Transaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, UUID> {
    
    // Find by reference
    Optional<Transaction> findByReferenceNumber(String referenceNumber);
    
    // Corporate-level queries (multi-tenancy)
    Page<Transaction> findByCorporateId(UUID corporateId, Pageable pageable);
    
    List<Transaction> findByCorporateId(UUID corporateId);
    
    long countByCorporateId(UUID corporateId);
    
    @Query("SELECT SUM(t.amount) FROM Transaction t WHERE t.corporateId = :corporateId AND t.movementType = 'CREDIT'")
    BigDecimal sumCreditsByCorporateId(@Param("corporateId") UUID corporateId);
    
    @Query("SELECT SUM(t.amount) FROM Transaction t WHERE t.corporateId = :corporateId AND t.movementType = 'DEBIT'")
    BigDecimal sumDebitsByCorporateId(@Param("corporateId") UUID corporateId);
    
    // VA-level queries
    Page<Transaction> findByVaId(UUID vaId, Pageable pageable);
    
    List<Transaction> findByVaId(UUID vaId);
    
    long countByVaId(UUID vaId);
    
    @Query("SELECT t FROM Transaction t WHERE t.vaId = :vaId ORDER BY t.createdAt DESC")
    Page<Transaction> findRecentByVaId(@Param("vaId") UUID vaId, Pageable pageable);
    
    // Date range queries
    @Query("SELECT t FROM Transaction t WHERE t.vaId = :vaId AND t.transactionDate BETWEEN :from AND :to ORDER BY t.transactionDate DESC")
    List<Transaction> findByVaIdAndDateRange(
        @Param("vaId") UUID vaId,
        @Param("from") LocalDateTime from,
        @Param("to") LocalDateTime to
    );
    
    @Query("SELECT t FROM Transaction t WHERE t.corporateId = :corporateId AND t.transactionDate BETWEEN :from AND :to ORDER BY t.transactionDate DESC")
    List<Transaction> findByCorporateIdAndDateRange(
        @Param("corporateId") UUID corporateId,
        @Param("from") LocalDateTime from,
        @Param("to") LocalDateTime to
    );
    
    // Status queries
    Page<Transaction> findByStatus(Transaction.TransactionStatus status, Pageable pageable);
    
    List<Transaction> findByStatus(Transaction.TransactionStatus status);
    
    long countByStatus(Transaction.TransactionStatus status);
    
    Page<Transaction> findByCorporateIdAndStatus(UUID corporateId, Transaction.TransactionStatus status, Pageable pageable);
    
    // Physical account queries
    Page<Transaction> findByPhysicalAccountId(UUID physicalAccountId, Pageable pageable);

    // ============================================================================
    // MOVEMENT TYPE QUERIES (REQUIRED FOR FRONTEND TransfersPage)
    // ============================================================================

    /**
     * Find transactions by movement type with pagination.
     * Used by frontend TransfersPage to filter by TRANSFER_OUT, DEBIT, POBO_DEBIT, etc.
     */
    Page<Transaction> findByMovementType(Transaction.MovementType movementType, Pageable pageable);

    /**
     * Find transactions by movement type (list version).
     */
    List<Transaction> findByMovementType(Transaction.MovementType movementType);

    /**
     * Count transactions by movement type.
     */
    long countByMovementType(Transaction.MovementType movementType);

    // ============================================================================
    // DASHBOARD METHODS
    // ============================================================================
    
    /**
     * Count transactions created after a specific time
     */
    @Query("SELECT COUNT(t) FROM Transaction t WHERE t.createdAt >= :since")
    long countByCreatedAtAfter(@Param("since") LocalDateTime since);
    
    /**
     * Get recent transactions ordered by creation time (use with Pageable)
     */
    @Query("SELECT t FROM Transaction t ORDER BY t.createdAt DESC")
    Page<Transaction> findRecentTransactions(Pageable pageable);
    
    /**
     * Get recent transactions with native query limit
     * Note: Table name is 'va_movements' as per entity @Table annotation
     */
    @Query(value = "SELECT * FROM va_movements ORDER BY created_at DESC LIMIT :limit", nativeQuery = true)
    List<Transaction> findRecentTransactionsNative(@Param("limit") int limit);
    
    /**
     * Sum transaction volume for today
     */
    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t WHERE t.createdAt >= :since")
    BigDecimal sumVolumeSince(@Param("since") LocalDateTime since);
    
    /**
     * Sum credits for today
     */
    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t WHERE t.movementType = 'CREDIT' AND t.createdAt >= :since")
    BigDecimal sumCreditsSince(@Param("since") LocalDateTime since);
    
    /**
     * Sum debits for today
     */
    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t WHERE t.movementType = 'DEBIT' AND t.createdAt >= :since")
    BigDecimal sumDebitsSince(@Param("since") LocalDateTime since);
    
    /**
     * Count transactions by status
     */
    @Query("SELECT t.status, COUNT(t) FROM Transaction t GROUP BY t.status")
    List<Object[]> countGroupByStatus();
    
    /**
     * Count transactions by movement type for date range
     */
    @Query("SELECT t.movementType, COUNT(t), COALESCE(SUM(t.amount), 0) FROM Transaction t " +
           "WHERE t.createdAt >= :since GROUP BY t.movementType")
    List<Object[]> summarizeByTypeSince(@Param("since") LocalDateTime since);
    
    /**
     * Get daily transaction counts for trend
     * Note: Table name is 'va_movements' as per entity @Table annotation
     */
    @Query(value = "SELECT DATE(created_at) as tx_date, COUNT(*) as tx_count, COALESCE(SUM(amount), 0) as tx_volume " +
                   "FROM va_movements WHERE created_at >= :since GROUP BY DATE(created_at) ORDER BY tx_date", 
           nativeQuery = true)
    List<Object[]> getDailyTrend(@Param("since") LocalDateTime since);
    
    /**
     * Get pending transactions count
     */
    @Query("SELECT COUNT(t) FROM Transaction t WHERE t.status = 'PENDING'")
    long countPending();
    
    /**
     * Get completed transactions count
     */
    @Query("SELECT COUNT(t) FROM Transaction t WHERE t.status = 'COMPLETED'")
    long countCompleted();
    
    /**
     * Find all ordered by created date descending
     */
    List<Transaction> findAllByOrderByCreatedAtDesc();

    // ============================================================================
    // ISO 20022 SUPPORT METHODS
    // ============================================================================

    /**
     * Find by correlation ID (endToEndId in ISO 20022) - returns single transaction
     */
    Optional<Transaction> findByCorrelationId(String correlationId);

    /**
     * Find by VA ID and date range with pagination
     */
    Page<Transaction> findByVaIdAndTransactionDateBetween(
        UUID vaId, LocalDateTime from, LocalDateTime to, Pageable pageable);

    /**
     * Count transactions by VA ID and date range.
     * Used for estimating async processing requirements for large statements.
     */
    @Query("SELECT COUNT(t) FROM Transaction t WHERE t.vaId = :vaId AND t.transactionDate BETWEEN :from AND :to")
    long countByVaIdAndTransactionDateBetween(
        @Param("vaId") UUID vaId,
        @Param("from") LocalDateTime from,
        @Param("to") LocalDateTime to);

    /**
     * Find ALL transactions by correlation ID (for linked transaction chains).
     * Unlike findByCorrelationId which returns Optional (single record),
     * this returns all transactions sharing the same correlation ID
     * (e.g., all 4 legs of a transfer plus fee transactions).
     */
    @Query("SELECT t FROM Transaction t WHERE t.correlationId = :correlationId ORDER BY t.createdAt ASC")
    List<Transaction> findAllByCorrelationId(@Param("correlationId") String correlationId);

    /**
     * Find by external reference (instructionId in ISO 20022)
     */
    Optional<Transaction> findByExternalReference(String externalReference);

    // ============================================================================
    // GROUPED TRANSACTION QUERIES (For Business View - Option B)
    // ============================================================================

    /**
     * Get distinct correlation IDs for a VA, ordered by most recent transaction date.
     * Used to paginate grouped business transactions.
     */
    @Query("SELECT DISTINCT t.correlationId FROM Transaction t " +
           "WHERE t.vaId = :vaId AND t.correlationId IS NOT NULL " +
           "ORDER BY MAX(t.transactionDate) DESC")
    List<String> findDistinctCorrelationIdsByVaId(@Param("vaId") UUID vaId, Pageable pageable);

    /**
     * Count distinct correlation IDs for a VA.
     */
    @Query("SELECT COUNT(DISTINCT t.correlationId) FROM Transaction t " +
           "WHERE t.vaId = :vaId AND t.correlationId IS NOT NULL")
    long countDistinctCorrelationIdsByVaId(@Param("vaId") UUID vaId);

    /**
     * Find the primary (first) transaction for each correlation ID for a VA.
     * The primary transaction is the one affecting the user's account.
     */
    @Query("SELECT t FROM Transaction t WHERE t.vaId = :vaId " +
           "AND t.correlationId IN :correlationIds ORDER BY t.transactionDate DESC")
    List<Transaction> findByVaIdAndCorrelationIdIn(
        @Param("vaId") UUID vaId,
        @Param("correlationIds") List<String> correlationIds);

    /**
     * Get distinct correlation IDs for corporate with pagination.
     */
    @Query("SELECT DISTINCT t.correlationId FROM Transaction t " +
           "WHERE t.corporateId = :corporateId AND t.correlationId IS NOT NULL " +
           "GROUP BY t.correlationId ORDER BY MAX(t.transactionDate) DESC")
    List<String> findDistinctCorrelationIdsByCorporateId(
        @Param("corporateId") UUID corporateId, Pageable pageable);

    /**
     * Count distinct correlation IDs for corporate.
     */
    @Query("SELECT COUNT(DISTINCT t.correlationId) FROM Transaction t " +
           "WHERE t.corporateId = :corporateId AND t.correlationId IS NOT NULL")
    long countDistinctCorrelationIdsByCorporateId(@Param("corporateId") UUID corporateId);

    /**
     * Find transactions by corporate and correlation IDs.
     */
    @Query("SELECT t FROM Transaction t WHERE t.corporateId = :corporateId " +
           "AND t.correlationId IN :correlationIds ORDER BY t.transactionDate DESC")
    List<Transaction> findByCorporateIdAndCorrelationIdIn(
        @Param("corporateId") UUID corporateId,
        @Param("correlationIds") List<String> correlationIds);

    /**
     * Find primary transactions (final leg affecting user's account) for grouped view.
     * For collections: the ROBO_CREDIT to Target VA
     * For payments: the initial DEBIT from Source VA
     */
    @Query("SELECT t FROM Transaction t WHERE t.vaId = :vaId " +
           "AND t.correlationId IS NOT NULL " +
           "AND (t.movementType IN ('ROBO_CREDIT', 'DEBIT', 'POBO_DEBIT', 'CREDIT', 'TRANSFER_IN', 'TRANSFER_OUT')) " +
           "ORDER BY t.transactionDate DESC")
    Page<Transaction> findPrimaryTransactionsByVaId(@Param("vaId") UUID vaId, Pageable pageable);

    /**
     * Find all transactions for a VA including fee movements.
     * Includes FEE, FEE_CREDIT, and all other movement types.
     */
    @Query("SELECT t FROM Transaction t WHERE t.vaId = :vaId " +
           "ORDER BY t.transactionDate DESC")
    Page<Transaction> findAllTransactionsByVaId(@Param("vaId") UUID vaId, Pageable pageable);

    /**
     * Find fee transactions by correlation ID prefix.
     * Used to retrieve fee entries associated with a POBO transaction.
     */
    @Query("SELECT t FROM Transaction t WHERE t.correlationId LIKE :correlationPrefix% " +
           "AND t.movementType IN ('FEE', 'FEE_CREDIT') " +
           "ORDER BY t.transactionDate DESC")
    List<Transaction> findFeeTransactionsByCorrelationPrefix(@Param("correlationPrefix") String correlationPrefix);

    /**
     * Find all transactions including fees for a given correlation ID.
     */
    @Query("SELECT t FROM Transaction t WHERE t.correlationId = :correlationId " +
           "OR t.correlationId LIKE :correlationId% " +
           "ORDER BY t.transactionDate DESC")
    List<Transaction> findAllTransactionsByCorrelationId(@Param("correlationId") String correlationId);
}