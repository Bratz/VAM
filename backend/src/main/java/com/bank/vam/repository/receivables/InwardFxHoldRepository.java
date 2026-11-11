package com.bank.vam.repository.receivables;

import com.bank.vam.entity.receivables.InwardFxHold;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InwardFxHoldRepository extends JpaRepository<InwardFxHold, UUID> {

    List<InwardFxHold> findByCorporateIdAndStatusOrderByCreatedAtAsc(UUID corporateId, String status);

    List<InwardFxHold> findByVirtualAccountIdAndEndToEndId(UUID virtualAccountId, String endToEndId);

    /** Locked, so two accepts of the same held credit cannot both post it. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT h FROM InwardFxHold h WHERE h.id = :id")
    Optional<InwardFxHold> lockById(@Param("id") UUID id);
}
