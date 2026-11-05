package com.bank.vam.fx.deal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface FxDealRepository extends JpaRepository<FxDeal, UUID> {

    List<FxDeal> findByCorporateIdOrderByCreatedAtDesc(UUID corporateId);

    List<FxDeal> findByCorporateIdAndStatusIn(UUID corporateId, Collection<DealStatus> statuses);

    List<FxDeal> findByStatusIn(Collection<DealStatus> statuses);

    @Query("SELECT COALESCE(SUM(d.limitCharge), 0) FROM FxDeal d "
            + "WHERE d.corporateId = :corporateId AND d.limitCurrency = :currency AND d.status IN :statuses")
    BigDecimal sumLimitCharge(@Param("corporateId") UUID corporateId, @Param("currency") String currency,
                              @Param("statuses") Collection<DealStatus> statuses);
}
