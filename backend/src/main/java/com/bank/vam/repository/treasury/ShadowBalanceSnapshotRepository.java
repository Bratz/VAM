package com.bank.vam.repository.treasury;

import com.bank.vam.entity.treasury.ShadowBalanceSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ShadowBalanceSnapshotRepository extends JpaRepository<ShadowBalanceSnapshot, UUID> {

    Optional<ShadowBalanceSnapshot> findByShadowVaIdAndAsOf(UUID shadowVaId, LocalDate asOf);

    /** Projection for {@link #findTrend}. */
    interface TrendRow {
        LocalDate getAsOf();
        String getCurrencyCode();
        BigDecimal getTotalBankBalance();
        BigDecimal getTotalEffective();
    }

    @Query(value = "SELECT as_of AS asOf, currency_code AS currencyCode, "
            + "SUM(bank_balance) AS totalBankBalance, SUM(bank_balance_effective) AS totalEffective "
            + "FROM shadow_balance_snapshot "
            + "WHERE (:corporateId IS NULL OR corporate_id = :corporateId) AND as_of >= :fromDate "
            + "GROUP BY as_of, currency_code ORDER BY as_of, currency_code", nativeQuery = true)
    List<TrendRow> findTrend(@Param("corporateId") UUID corporateId, @Param("fromDate") LocalDate fromDate);
}
