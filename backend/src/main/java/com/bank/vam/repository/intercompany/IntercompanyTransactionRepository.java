package com.bank.vam.repository.intercompany;

import com.bank.vam.entity.intercompany.IntercompanyTransaction;
import com.bank.vam.entity.intercompany.IntercompanyTransaction.TransactionType;
import com.bank.vam.entity.intercompany.IntercompanyTransaction.TransactionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for IntercompanyTransaction entity.
 * 
 * Aligned with IntercompanyService V2 method calls.
 */
@Repository
public interface IntercompanyTransactionRepository extends JpaRepository<IntercompanyTransaction, UUID> {

    // ========================================================================
    // BASIC LOOKUPS
    // ========================================================================

    Optional<IntercompanyTransaction> findByTransactionRef(String transactionRef);
    
    boolean existsByTransactionRef(String transactionRef);

    // ========================================================================
    // STATUS-BASED QUERIES
    // ========================================================================

    List<IntercompanyTransaction> findByStatus(TransactionStatus status);
    
    List<IntercompanyTransaction> findByStatusIn(List<TransactionStatus> statuses);

    // ========================================================================
    // DATE RANGE QUERIES
    // ========================================================================

    @Query("SELECT t FROM IntercompanyTransaction t WHERE " +
           "t.createdAt >= :fromDate AND t.createdAt < :toDate " +
           "ORDER BY t.createdAt DESC")
    List<IntercompanyTransaction> findByDateRange(
            @Param("fromDate") LocalDateTime fromDate,
            @Param("toDate") LocalDateTime toDate);

    // ========================================================================
    // SETTLEMENT QUERIES
    // ========================================================================

    List<IntercompanyTransaction> findBySettlementRef(String settlementRef);

    // ========================================================================
    // ENTITY-BASED QUERIES
    // ========================================================================

    List<IntercompanyTransaction> findByPayingEntityIdOrderByCreatedAtDesc(UUID payingEntityId);

    List<IntercompanyTransaction> findByBehalfEntityIdOrderByCreatedAtDesc(UUID behalfEntityId);

    // ========================================================================
    // TRANSACTION TYPE FILTERING QUERIES
    // ========================================================================

    /**
     * Find all transactions by transaction type.
     */
    List<IntercompanyTransaction> findByTransactionType(IntercompanyTransaction.TransactionType transactionType);

    /**
     * Find all transactions by transaction type, ordered by creation date descending.
     */
    List<IntercompanyTransaction> findByTransactionTypeOrderByCreatedAtDesc(IntercompanyTransaction.TransactionType transactionType);

    /**
     * Find transactions by transaction type and paying entity, ordered by creation date descending.
     */
    List<IntercompanyTransaction> findByTransactionTypeAndPayingEntityIdOrderByCreatedAtDesc(
        IntercompanyTransaction.TransactionType transactionType, UUID payingEntityId);

    /**
     * Find transactions by transaction type and behalf entity, ordered by creation date descending.
     */
    List<IntercompanyTransaction> findByTransactionTypeAndBehalfEntityIdOrderByCreatedAtDesc(
        IntercompanyTransaction.TransactionType transactionType, UUID behalfEntityId);

    /**
     * Find transactions by multiple transaction types, ordered by creation date descending.
     */
    List<IntercompanyTransaction> findByTransactionTypeInOrderByCreatedAtDesc(
        List<IntercompanyTransaction.TransactionType> transactionTypes);

    /**
     * Find transactions by transaction type and status, ordered by creation date descending.
     */
    List<IntercompanyTransaction> findByTransactionTypeAndStatusOrderByCreatedAtDesc(
        IntercompanyTransaction.TransactionType transactionType,
        IntercompanyTransaction.TransactionStatus status);
}