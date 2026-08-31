package com.bank.vam.forecast.repository;

import com.bank.vam.forecast.domain.ForecastCategory;
import com.bank.vam.forecast.domain.enums.ForecastDirection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ForecastCategoryRepository extends JpaRepository<ForecastCategory, UUID> {

    Optional<ForecastCategory> findByCode(String code);

    List<ForecastCategory> findAllByDirection(ForecastDirection direction);
}
