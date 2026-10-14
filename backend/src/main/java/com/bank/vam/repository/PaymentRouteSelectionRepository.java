package com.bank.vam.repository;

import com.bank.vam.entity.PaymentRouteSelection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PaymentRouteSelectionRepository extends JpaRepository<PaymentRouteSelection, UUID> {

    Optional<PaymentRouteSelection> findByTransactionId(UUID transactionId);

    List<PaymentRouteSelection> findByFromVaIdOrderByCreatedAtDesc(UUID fromVaId);
}
