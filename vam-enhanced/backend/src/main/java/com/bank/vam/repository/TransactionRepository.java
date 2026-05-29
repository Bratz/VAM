package com.bank.vam.repository;

import com.bank.vam.entity.Transaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    Optional<Transaction> findByReferenceNumber(String referenceNumber);

    Page<Transaction> findByVaId(UUID vaId, Pageable pageable);

    List<Transaction> findByVaIdAndTransactionDateBetween(UUID vaId, LocalDateTime start, LocalDateTime end);

    Page<Transaction> findByPhysicalAccountId(UUID physicalAccountId, Pageable pageable);

    List<Transaction> findByPhysicalAccountIdAndTransactionDateBetween(
            UUID physicalAccountId, LocalDateTime start, LocalDateTime end);

    @Query("SELECT SUM(t.amount) FROM Transaction t WHERE t.vaId = :vaId AND t.movementType = :type AND t.status = 'COMPLETED'")
    BigDecimal sumAmountByVaIdAndType(UUID vaId, Transaction.MovementType type);

    @Query("SELECT t FROM Transaction t WHERE t.vaId = :vaId ORDER BY t.transactionDate DESC")
    List<Transaction> findRecentByVaId(UUID vaId, Pageable pageable);

    long countByVaId(UUID vaId);

    boolean existsByReferenceNumber(String referenceNumber);
}
