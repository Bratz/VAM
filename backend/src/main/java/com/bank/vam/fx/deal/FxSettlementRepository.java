package com.bank.vam.fx.deal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface FxSettlementRepository extends JpaRepository<FxSettlement, UUID> {

    List<FxSettlement> findByDealIdOrderByCreatedAtAsc(UUID dealId);

    List<FxSettlement> findByDealIdAndKindAndStatus(UUID dealId, SettlementKind kind, SettlementStatus status);

    List<FxSettlement> findByStatusAndKindAndValueDateLessThanEqual(SettlementStatus status, SettlementKind kind, LocalDate date);

    boolean existsByDealIdAndStatus(UUID dealId, SettlementStatus status);
}
