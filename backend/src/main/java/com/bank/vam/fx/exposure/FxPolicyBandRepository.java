package com.bank.vam.fx.exposure;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface FxPolicyBandRepository extends JpaRepository<FxPolicyBand, UUID> {

    List<FxPolicyBand> findByCorporateId(UUID corporateId);

    boolean existsByCorporateId(UUID corporateId);
}
