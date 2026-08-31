package com.bank.vam.repository.pobo;

import com.bank.vam.entity.pobo.PoboAuthorization;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for POBO Authorization entities.
 */
@Repository
public interface PoboAuthorizationRepository extends JpaRepository<PoboAuthorization, UUID> {

    Optional<PoboAuthorization> findByAuthorizationCode(String authorizationCode);
    
    List<PoboAuthorization> findByPayerEntityId(UUID payerEntityId);
    
    List<PoboAuthorization> findByBehalfEntityId(UUID behalfEntityId);
    
    List<PoboAuthorization> findByStatus(PoboAuthorization.AuthorizationStatus status);
    
    @Query("SELECT pa FROM PoboAuthorization pa WHERE pa.payerEntityId = :payerId " +
           "AND pa.behalfEntityId = :behalfId AND pa.status = 'ACTIVE'")
    Optional<PoboAuthorization> findActiveAuthorization(
        @Param("payerId") UUID payerEntityId,
        @Param("behalfId") UUID behalfEntityId);
    
    @Query("SELECT pa FROM PoboAuthorization pa WHERE pa.status = 'ACTIVE' " +
           "AND (pa.effectiveTo IS NULL OR pa.effectiveTo >= CURRENT_DATE)")
    List<PoboAuthorization> findAllActive();
    
    @Query("SELECT pa FROM PoboAuthorization pa WHERE pa.payerEntityId = :payerId " +
           "AND pa.status = 'ACTIVE' " +
           "AND (pa.effectiveTo IS NULL OR pa.effectiveTo >= CURRENT_DATE)")
    List<PoboAuthorization> findActiveByPayer(@Param("payerId") UUID payerEntityId);
    
    @Query("SELECT pa FROM PoboAuthorization pa WHERE pa.behalfEntityId = :behalfId " +
           "AND pa.status = 'ACTIVE' " +
           "AND (pa.effectiveTo IS NULL OR pa.effectiveTo >= CURRENT_DATE)")
    List<PoboAuthorization> findActiveByBehalf(@Param("behalfId") UUID behalfEntityId);
    
    @Query("SELECT pa FROM PoboAuthorization pa WHERE pa.effectiveTo < CURRENT_DATE " +
           "AND pa.status = 'ACTIVE'")
    List<PoboAuthorization> findExpiredAuthorizations();
    
    @Query("SELECT pa FROM PoboAuthorization pa WHERE pa.payerEntityCode = :payerCode " +
           "AND pa.behalfEntityCode = :behalfCode AND pa.status = 'ACTIVE'")
    Optional<PoboAuthorization> findActiveByEntityCodes(
        @Param("payerCode") String payerEntityCode,
        @Param("behalfCode") String behalfEntityCode);
    
    @Query("SELECT DISTINCT pa.behalfEntityCode FROM PoboAuthorization pa " +
           "WHERE pa.payerEntityId = :payerId AND pa.status = 'ACTIVE'")
    List<String> findAuthorizedBehalfEntities(@Param("payerId") UUID payerEntityId);
}