package com.bank.vam.fx.deal;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface FxCreditLimitRepository extends JpaRepository<FxCreditLimit, UUID> {

    Optional<FxCreditLimit> findByCorporateId(UUID corporateId);

    /** Held while a deal is booked, so two accepts for one corporate cannot both use the same headroom. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT l FROM FxCreditLimit l WHERE l.corporateId = :corporateId")
    Optional<FxCreditLimit> lockByCorporateId(@Param("corporateId") UUID corporateId);
}
