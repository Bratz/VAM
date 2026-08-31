package com.bank.vam.repository.tax;

import com.bank.vam.entity.tax.ProgramChargeOverride;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for Program Charge Override entities.
 */
@Repository
public interface ProgramChargeOverrideRepository extends JpaRepository<ProgramChargeOverride, UUID> {

    // ========================================================================
    // BASIC QUERIES
    // ========================================================================

    List<ProgramChargeOverride> findByProgramId(UUID programId);
    
    List<ProgramChargeOverride> findByChargeCode(String chargeCode);
    
    Optional<ProgramChargeOverride> findByProgramIdAndChargeCode(UUID programId, String chargeCode);

    // ========================================================================
    // ACTIVE OVERRIDES
    // ========================================================================

    @Query("SELECT o FROM ProgramChargeOverride o WHERE o.programId = :programId " +
           "AND o.status = 'ACTIVE' " +
           "AND (o.effectiveTo IS NULL OR o.effectiveTo >= CURRENT_DATE)")
    List<ProgramChargeOverride> findActiveByProgramId(@Param("programId") UUID programId);

    @Query("SELECT o FROM ProgramChargeOverride o WHERE o.programId = :programId " +
           "AND o.chargeCode = :chargeCode " +
           "AND o.status = 'ACTIVE' " +
           "AND (o.effectiveTo IS NULL OR o.effectiveTo >= CURRENT_DATE)")
    Optional<ProgramChargeOverride> findActiveByProgramIdAndChargeCode(
        @Param("programId") UUID programId, 
        @Param("chargeCode") String chargeCode);

    // ========================================================================
    // WAIVER QUERIES
    // ========================================================================

    @Query("SELECT o FROM ProgramChargeOverride o WHERE o.programId = :programId " +
           "AND o.isWaived = true " +
           "AND o.status = 'ACTIVE' " +
           "AND (o.effectiveTo IS NULL OR o.effectiveTo >= CURRENT_DATE)")
    List<ProgramChargeOverride> findWaivedByProgramId(@Param("programId") UUID programId);

    @Query("SELECT o FROM ProgramChargeOverride o WHERE o.chargeCode = :chargeCode " +
           "AND o.isWaived = true " +
           "AND o.status = 'ACTIVE'")
    List<ProgramChargeOverride> findWaivedByChargeCode(@Param("chargeCode") String chargeCode);

    // ========================================================================
    // WALLET-SPECIFIC QUERIES
    // ========================================================================

    @Query("SELECT o FROM ProgramChargeOverride o WHERE o.programId = :programId " +
           "AND o.chargeCode LIKE 'WALLET_%' " +
           "AND o.status = 'ACTIVE' " +
           "AND (o.effectiveTo IS NULL OR o.effectiveTo >= CURRENT_DATE)")
    List<ProgramChargeOverride> findActiveWalletOverridesByProgramId(@Param("programId") UUID programId);

    @Query("SELECT o FROM ProgramChargeOverride o WHERE o.programId = :programId " +
           "AND o.chargeCode IN ('WALLET_TOPUP', 'WALLET_WITHDRAWAL', 'WALLET_TRANSFER', " +
           "'WALLET_ISSUANCE', 'WALLET_MONTHLY', 'WALLET_INACTIVITY') " +
           "AND o.status = 'ACTIVE' " +
           "AND (o.effectiveTo IS NULL OR o.effectiveTo >= CURRENT_DATE)")
    List<ProgramChargeOverride> findStandardWalletOverridesByProgramId(@Param("programId") UUID programId);

    // ========================================================================
    // COUNT QUERIES
    // ========================================================================

    long countByProgramId(UUID programId);
    
    long countByChargeCode(String chargeCode);

    @Query("SELECT COUNT(o) FROM ProgramChargeOverride o WHERE o.programId = :programId " +
           "AND o.status = 'ACTIVE'")
    long countActiveByProgramId(@Param("programId") UUID programId);

    // ========================================================================
    // EXISTENCE CHECKS
    // ========================================================================

    boolean existsByProgramIdAndChargeCode(UUID programId, String chargeCode);

    @Query("SELECT CASE WHEN COUNT(o) > 0 THEN true ELSE false END " +
           "FROM ProgramChargeOverride o WHERE o.programId = :programId " +
           "AND o.chargeCode = :chargeCode " +
           "AND o.status = 'ACTIVE' " +
           "AND (o.effectiveTo IS NULL OR o.effectiveTo >= CURRENT_DATE)")
    boolean existsActiveByProgramIdAndChargeCode(
        @Param("programId") UUID programId, 
        @Param("chargeCode") String chargeCode);

    // ========================================================================
    // BATCH OPERATIONS
    // ========================================================================

    @Query("UPDATE ProgramChargeOverride o SET o.status = 'EXPIRED' " +
           "WHERE o.effectiveTo < CURRENT_DATE AND o.status = 'ACTIVE'")
    void expireOldOverrides();

    void deleteByProgramId(UUID programId);
    
    void deleteByProgramIdAndChargeCode(UUID programId, String chargeCode);
}