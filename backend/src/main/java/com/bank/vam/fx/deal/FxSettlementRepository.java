package com.bank.vam.fx.deal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface FxSettlementRepository extends JpaRepository<FxSettlement, UUID> {

    List<FxSettlement> findByDealIdOrderByCreatedAtAsc(UUID dealId);

    List<FxSettlement> findByDealIdAndKindAndStatus(UUID dealId, SettlementKind kind, SettlementStatus status);

    List<FxSettlement> findByStatusAndKindAndValueDateLessThanEqual(SettlementStatus status, SettlementKind kind, LocalDate date);

    boolean existsByDealIdAndStatus(UUID dealId, SettlementStatus status);

    /** Settlements whose debit account is below zero now, with that balance and the deal's ref; latest first. */
    @Query("SELECT s, v.bankBalance, d.dealRef FROM FxSettlement s, VirtualAccount v, FxDeal d WHERE v.id = s.debitAccountId "
            + "AND d.id = s.dealId AND s.corporateId = :corporateId AND v.bankBalance < 0 ORDER BY s.settledAt DESC")
    List<Object[]> overdrawn(@Param("corporateId") UUID corporateId);
}
