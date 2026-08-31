package com.bank.vam.repository.treasury;

import com.bank.vam.entity.treasury.IhbTransaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface IhbTransactionRepository extends JpaRepository<IhbTransaction, UUID> {

    Optional<IhbTransaction> findByTransactionRef(String transactionRef);

    List<IhbTransaction> findByStatus(IhbTransaction.TransactionStatus status);

    List<IhbTransaction> findByTransactionType(IhbTransaction.TransactionType type);

    @Query("SELECT t FROM IhbTransaction t WHERE t.fromEntity.id = :entityId OR t.toEntity.id = :entityId ORDER BY t.transactionDate DESC")
    List<IhbTransaction> findByEntityId(@Param("entityId") UUID entityId);

    @Query("SELECT t FROM IhbTransaction t WHERE t.transactionDate BETWEEN :startDate AND :endDate ORDER BY t.transactionDate DESC")
    List<IhbTransaction> findByDateRange(@Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

    Page<IhbTransaction> findAllByOrderByTransactionDateDesc(Pageable pageable);

    @Query("SELECT t FROM IhbTransaction t ORDER BY t.transactionDate DESC, t.createdAt DESC")
    List<IhbTransaction> findRecentTransactions(Pageable pageable);

    long countByTransactionType(IhbTransaction.TransactionType type);
}
