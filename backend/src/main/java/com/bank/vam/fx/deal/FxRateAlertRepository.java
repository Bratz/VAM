package com.bank.vam.fx.deal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface FxRateAlertRepository extends JpaRepository<FxRateAlert, UUID> {

    List<FxRateAlert> findByStatus(FxRateAlert.Status status);

    List<FxRateAlert> findByCorporateIdOrderByCreatedAtDesc(UUID corporateId);
}
