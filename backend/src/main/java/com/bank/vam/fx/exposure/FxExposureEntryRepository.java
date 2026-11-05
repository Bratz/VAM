package com.bank.vam.fx.exposure;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface FxExposureEntryRepository extends JpaRepository<FxExposureEntry, UUID> {

    List<FxExposureEntry> findByCorporateIdOrderByValueDateAsc(UUID corporateId);

    void deleteByCorporateIdAndSource(UUID corporateId, String source);
}
