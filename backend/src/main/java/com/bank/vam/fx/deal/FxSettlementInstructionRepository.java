package com.bank.vam.fx.deal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FxSettlementInstructionRepository extends JpaRepository<FxSettlementInstruction, UUID> {

    Optional<FxSettlementInstruction> findByEntityIdAndCurrency(UUID entityId, String currency);

    List<FxSettlementInstruction> findByCorporateId(UUID corporateId);
}
