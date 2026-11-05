package com.bank.vam.fx.deal;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface FxQuoteRequestRepository extends JpaRepository<FxQuoteRequest, UUID> {

    /** Serialises concurrent accepts or cancels of the same quote: the second waits and sees the result. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT q FROM FxQuoteRequest q WHERE q.id = :id")
    Optional<FxQuoteRequest> lockById(@Param("id") UUID id);
}
