package com.bank.vam.fx.deal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FxMtmRepository extends JpaRepository<FxMtm, UUID> {

    Optional<FxMtm> findByDealIdAndAsOf(UUID dealId, LocalDate asOf);

    List<FxMtm> findByDealIdOrderByAsOfAsc(UUID dealId);
}
