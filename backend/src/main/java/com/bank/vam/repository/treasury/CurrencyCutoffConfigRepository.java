package com.bank.vam.repository.treasury;

import com.bank.vam.entity.treasury.CurrencyCutoffConfig;
import com.bank.vam.entity.treasury.SweepRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CurrencyCutoffConfigRepository extends JpaRepository<CurrencyCutoffConfig, UUID> {

    Optional<CurrencyCutoffConfig> findByCurrencyCodeAndRail(String currencyCode, SweepRule.Rail rail);

    List<CurrencyCutoffConfig> findByCurrencyCode(String currencyCode);
}
