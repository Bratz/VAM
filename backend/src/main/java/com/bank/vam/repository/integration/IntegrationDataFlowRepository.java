package com.bank.vam.repository.integration;

import com.bank.vam.entity.integration.IntegrationDataFlow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface IntegrationDataFlowRepository extends JpaRepository<IntegrationDataFlow, UUID> {
    
    List<IntegrationDataFlow> findByConnectionId(UUID connectionId);

    @Query("SELECT f FROM IntegrationDataFlow f JOIN FETCH f.fieldMappings WHERE f.id = :id")
    java.util.Optional<IntegrationDataFlow> findByIdWithMappings(@Param("id") UUID id);

    @Query("SELECT COUNT(f) FROM IntegrationDataFlow f")
    long countAllFlows();
}
