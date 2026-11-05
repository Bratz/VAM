package com.bank.vam.fx.deal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface FxDealEventRepository extends JpaRepository<FxDealEvent, UUID> {

    List<FxDealEvent> findByEntityIdOrderByCreatedAtAsc(UUID entityId);
}
