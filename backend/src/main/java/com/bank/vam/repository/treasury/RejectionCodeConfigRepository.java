package com.bank.vam.repository.treasury;

import com.bank.vam.entity.treasury.RejectionCodeConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RejectionCodeConfigRepository extends JpaRepository<RejectionCodeConfig, UUID> {

    Optional<RejectionCodeConfig> findByCode(String code);

    List<RejectionCodeConfig> findByCategory(com.bank.vam.entity.treasury.SweepInstruction.RejectionCategory category);
}
