package com.bank.vam.fx.deal;

import com.bank.vam.entity.credit.CreditLimit;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The FX rows of {@code credit_limits}: the corporate's FX line (target type FX_LINE, external, from the FX
 * facility) and the entity sub-limits under it (FX_ENTITY, internal). The shared credit-limit repository keeps
 * them out of every cash-credit query; FX reads them only here.
 */
public interface FxCreditLineRepository extends JpaRepository<CreditLimit, UUID> {

    @Query("SELECT cl FROM CreditLimit cl WHERE cl.corporateId = :corporateId AND cl.targetType = 'FX_LINE' "
            + "ORDER BY cl.createdAt DESC")
    List<CreditLimit> findLines(@Param("corporateId") UUID corporateId);

    @Query("SELECT cl FROM CreditLimit cl WHERE cl.parentLimitId = :lineId AND cl.targetType = 'FX_ENTITY' "
            + "ORDER BY cl.limitName")
    List<CreditLimit> findAllocations(@Param("lineId") UUID lineId);

    @Query("SELECT cl FROM CreditLimit cl WHERE cl.parentLimitId = :lineId AND cl.targetType = 'FX_ENTITY' "
            + "AND cl.targetId = :entityId")
    Optional<CreditLimit> findAllocation(@Param("lineId") UUID lineId, @Param("entityId") UUID entityId);

    /** Holds the row until the transaction ends, so two bookings cannot spend the same headroom. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT cl FROM CreditLimit cl WHERE cl.id = :id")
    Optional<CreditLimit> lockById(@Param("id") UUID id);
}
