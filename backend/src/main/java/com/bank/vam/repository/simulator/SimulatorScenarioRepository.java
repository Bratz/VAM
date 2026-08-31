package com.bank.vam.repository.simulator;

import com.bank.vam.entity.simulator.SimulatorScenario;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface SimulatorScenarioRepository extends JpaRepository<SimulatorScenario, UUID> {

    /** Scenario list for a corporate, most-recently-touched first (page default). */
    List<SimulatorScenario> findByCorporateIdOrderByUpdatedAtDesc(UUID corporateId);

    /** All forks of a parent scenario (Phase 4 CompareView). */
    List<SimulatorScenario> findByParentScenarioId(UUID parentScenarioId);

    /** Daily-sequence support for the {@code SCN-yyyyMMdd-NNN} reference generator. */
    long countByScenarioReferenceStartingWith(String referencePrefix);
}
