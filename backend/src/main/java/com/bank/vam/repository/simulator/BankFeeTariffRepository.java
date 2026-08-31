package com.bank.vam.repository.simulator;

import com.bank.vam.entity.simulator.BankFeeTariff;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
/** @deprecated 2026-05-16 — see {@link BankFeeTariff}. Unwired. */
@Deprecated
public interface BankFeeTariffRepository extends JpaRepository<BankFeeTariff, UUID> {

    /**
     * Effective tariffs for the given banks as of a date. Multiple rows per
     * (bank, rail) may match if effective windows overlap — the service picks
     * the most recent {@code effectiveFrom}.
     */
    @Query("""
            SELECT t FROM BankFeeTariff t
            WHERE t.bankCode IN :codes
              AND t.effectiveFrom <= :asOf
              AND (t.effectiveTo IS NULL OR t.effectiveTo >= :asOf)
            ORDER BY t.bankCode, t.paymentRail, t.effectiveFrom DESC
            """)
    List<BankFeeTariff> findEffective(
            @Param("codes") Collection<String> codes,
            @Param("asOf") LocalDate asOf);
}
