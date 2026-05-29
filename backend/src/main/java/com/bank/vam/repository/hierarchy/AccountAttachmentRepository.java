package com.bank.vam.repository.hierarchy;

import com.bank.vam.entity.hierarchy.AccountAttachment;
import com.bank.vam.entity.hierarchy.AccountAttachment.AttachmentStatus;
import com.bank.vam.entity.hierarchy.AccountAttachment.RelationshipType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for AccountAttachment operations.
 * 
 * UNIFIED ARCHITECTURE v4.2:
 * Enhanced with methods needed by AccountAttachmentService.
 */
@Repository
public interface AccountAttachmentRepository extends JpaRepository<AccountAttachment, UUID> {

    // ========================================================================
    // BASIC FINDERS
    // ========================================================================

    List<AccountAttachment> findByVirtualAccountId(UUID virtualAccountId);

    List<AccountAttachment> findByLegalEntityId(UUID legalEntityId);

    Optional<AccountAttachment> findByVirtualAccountIdAndLegalEntityIdAndRelationshipType(
        UUID virtualAccountId, UUID legalEntityId, RelationshipType relationshipType);

    List<AccountAttachment> findByVirtualAccountIdAndRelationshipType(
        UUID virtualAccountId, RelationshipType relationshipType);

    Optional<AccountAttachment> findByVirtualAccountIdAndIsPrimaryTrue(UUID virtualAccountId);

    /**
     * Find primary owner for VA.
     */
    Optional<AccountAttachment> findByVirtualAccountIdAndRelationshipTypeAndIsPrimary(
        UUID virtualAccountId, RelationshipType relationshipType, Boolean isPrimary);

    @Query("SELECT aa FROM AccountAttachment aa WHERE aa.virtualAccountId = :vaId AND aa.relationshipType = 'OWNER' AND aa.isPrimary = true")
    Optional<AccountAttachment> findOwner(@Param("vaId") UUID virtualAccountId);

    // ========================================================================
    // STATUS FILTERS
    // ========================================================================

    List<AccountAttachment> findByVirtualAccountIdAndStatus(UUID virtualAccountId, AttachmentStatus status);

    List<AccountAttachment> findByLegalEntityIdAndStatus(UUID legalEntityId, AttachmentStatus status);

    @Query("""
        SELECT aa FROM AccountAttachment aa 
        WHERE aa.virtualAccountId = :vaId 
        AND aa.status = 'ACTIVE'
        AND aa.effectiveFrom <= :today
        AND (aa.effectiveTo IS NULL OR aa.effectiveTo >= :today)
        """)
    List<AccountAttachment> findEffectiveAttachments(@Param("vaId") UUID virtualAccountId, 
                                                      @Param("today") LocalDate today);

    List<AccountAttachment> findByStatus(AttachmentStatus status);

    // ========================================================================
    // ACTIVE BY TYPE QUERIES (for AccountAttachmentService)
    // ========================================================================

    /**
     * Find active attachments by VA and type.
     */
    @Query("""
        SELECT aa FROM AccountAttachment aa 
        WHERE aa.virtualAccountId = :vaId 
        AND aa.relationshipType = :type 
        AND aa.status = 'ACTIVE'
        AND (aa.effectiveFrom IS NULL OR aa.effectiveFrom <= CURRENT_DATE)
        AND (aa.effectiveTo IS NULL OR aa.effectiveTo >= CURRENT_DATE)
        """)
    List<AccountAttachment> findActiveByVaAndType(@Param("vaId") UUID virtualAccountId,
                                                   @Param("type") RelationshipType relationshipType);

    /**
     * Find active attachments by VA and entity.
     */
    @Query("""
        SELECT aa FROM AccountAttachment aa 
        WHERE aa.virtualAccountId = :vaId 
        AND aa.legalEntityId = :entityId 
        AND aa.status = 'ACTIVE'
        AND (aa.effectiveFrom IS NULL OR aa.effectiveFrom <= CURRENT_DATE)
        AND (aa.effectiveTo IS NULL OR aa.effectiveTo >= CURRENT_DATE)
        """)
    List<AccountAttachment> findActiveByVaAndEntity(@Param("vaId") UUID virtualAccountId,
                                                     @Param("entityId") UUID legalEntityId);

    /**
     * Check if active attachment exists.
     */
    @Query("""
        SELECT COUNT(aa) > 0 FROM AccountAttachment aa 
        WHERE aa.virtualAccountId = :vaId 
        AND aa.legalEntityId = :entityId 
        AND aa.status = :status
        """)
    boolean existsActiveAttachment(@Param("vaId") UUID virtualAccountId,
                                   @Param("entityId") UUID legalEntityId,
                                   @Param("status") AttachmentStatus status);

    /**
     * Check if VA-Entity-Type combination exists.
     */
    @Query("""
        SELECT COUNT(aa) > 0 FROM AccountAttachment aa 
        WHERE aa.virtualAccountId = :vaId 
        AND aa.legalEntityId = :entityId 
        AND aa.relationshipType = :type
        AND aa.status != 'TERMINATED'
        """)
    boolean existsByVaEntityAndType(@Param("vaId") UUID virtualAccountId,
                                    @Param("entityId") UUID legalEntityId,
                                    @Param("type") RelationshipType relationshipType);

    // ========================================================================
    // FACILITY/COLLATERAL QUERIES
    // ========================================================================

    List<AccountAttachment> findBySecuredFacilityIdAndStatus(UUID facilityId, AttachmentStatus status);

    List<AccountAttachment> findBySecuredFacilityIdAndRelationshipType(UUID facilityId, RelationshipType type);

    // ========================================================================
    // EXPIRY QUERIES
    // ========================================================================

    @Query("""
        SELECT aa FROM AccountAttachment aa 
        WHERE aa.status = :status
        AND aa.effectiveTo IS NOT NULL
        AND aa.effectiveTo <= :expiryDate
        """)
    List<AccountAttachment> findExpiringBefore(@Param("expiryDate") LocalDate expiryDate,
                                                @Param("status") AttachmentStatus status);

    @Query("""
        SELECT aa FROM AccountAttachment aa 
        WHERE aa.status = 'ACTIVE'
        AND aa.effectiveTo IS NOT NULL
        AND aa.effectiveTo < :today
        """)
    List<AccountAttachment> findExpiredActiveAttachments(@Param("today") LocalDate today);

    // ========================================================================
    // ENTITY RELATIONSHIP QUERIES
    // ========================================================================

    @Query("SELECT aa FROM AccountAttachment aa WHERE aa.legalEntityId = :entityId AND aa.relationshipType = 'OWNER' AND aa.status = 'ACTIVE'")
    List<AccountAttachment> findOwnedAccounts(@Param("entityId") UUID legalEntityId);

    @Query("SELECT aa FROM AccountAttachment aa WHERE aa.legalEntityId = :entityId AND aa.relationshipType = 'BENEFICIARY' AND aa.status = 'ACTIVE'")
    List<AccountAttachment> findBeneficiaryAccounts(@Param("entityId") UUID legalEntityId);

    @Query("SELECT aa FROM AccountAttachment aa WHERE aa.legalEntityId = :entityId AND aa.relationshipType = 'AUTHORIZED' AND aa.status = 'ACTIVE'")
    List<AccountAttachment> findAuthorizedAccounts(@Param("entityId") UUID legalEntityId);

    @Query("SELECT aa FROM AccountAttachment aa WHERE aa.legalEntityId = :entityId AND aa.relationshipType = 'GUARANTOR' AND aa.status = 'ACTIVE'")
    List<AccountAttachment> findGuarantorRelationships(@Param("entityId") UUID legalEntityId);

    @Query("SELECT aa FROM AccountAttachment aa WHERE aa.legalEntityId = :entityId AND aa.relationshipType = 'COLLATERAL' AND aa.status = 'ACTIVE'")
    List<AccountAttachment> findCollateralAccounts(@Param("entityId") UUID legalEntityId);

    // ========================================================================
    // COUNT QUERIES
    // ========================================================================

    long countByVirtualAccountId(UUID virtualAccountId);
    long countByLegalEntityId(UUID legalEntityId);
    long countByLegalEntityIdAndStatus(UUID legalEntityId, AttachmentStatus status);
    long countByLegalEntityIdAndRelationshipType(UUID legalEntityId, RelationshipType relationshipType);

    // ========================================================================
    // EXISTENCE CHECKS
    // ========================================================================

    boolean existsByVirtualAccountIdAndLegalEntityIdAndRelationshipType(
        UUID virtualAccountId, UUID legalEntityId, RelationshipType relationshipType);

    @Query("SELECT COUNT(aa) > 0 FROM AccountAttachment aa WHERE aa.virtualAccountId = :vaId AND aa.relationshipType = 'OWNER' AND aa.status = 'ACTIVE'")
    boolean hasOwner(@Param("vaId") UUID virtualAccountId);

    @Query("""
        SELECT COUNT(aa) > 0 FROM AccountAttachment aa 
        WHERE aa.virtualAccountId = :vaId 
        AND aa.legalEntityId = :entityId 
        AND aa.relationshipType IN ('OWNER', 'AUTHORIZED')
        AND aa.status = 'ACTIVE'
        """)
    boolean isAuthorized(@Param("vaId") UUID virtualAccountId, @Param("entityId") UUID legalEntityId);

    // ========================================================================
    // UPDATE OPERATIONS
    // ========================================================================

    @Modifying
    @Query("UPDATE AccountAttachment aa SET aa.status = :status, aa.updatedAt = CURRENT_TIMESTAMP WHERE aa.id = :id")
    int updateStatus(@Param("id") UUID id, @Param("status") AttachmentStatus status);

    @Modifying
    @Query("UPDATE AccountAttachment aa SET aa.status = 'EXPIRED', aa.effectiveTo = :today, aa.updatedAt = CURRENT_TIMESTAMP WHERE aa.id = :id")
    int expireAttachment(@Param("id") UUID id, @Param("today") LocalDate today);

    @Modifying
    @Query("""
        UPDATE AccountAttachment aa 
        SET aa.status = 'EXPIRED', aa.updatedAt = CURRENT_TIMESTAMP 
        WHERE aa.status = 'ACTIVE' 
        AND aa.effectiveTo IS NOT NULL 
        AND aa.effectiveTo < :today
        """)
    int expireAllExpired(@Param("today") LocalDate today);

    @Modifying
    @Query("""
        UPDATE AccountAttachment aa 
        SET aa.status = 'ACTIVE', 
            aa.approvedBy = :approver, 
            aa.approvedAt = CURRENT_TIMESTAMP,
            aa.updatedAt = CURRENT_TIMESTAMP 
        WHERE aa.id = :id AND aa.status = 'PENDING_APPROVAL'
        """)
    int approveAttachment(@Param("id") UUID id, @Param("approver") String approver);

    // ========================================================================
    // DELETE OPERATIONS
    // ========================================================================

    void deleteByVirtualAccountId(UUID virtualAccountId);
    void deleteByLegalEntityId(UUID legalEntityId);

    // ========================================================================
    // ID LOOKUPS
    // ========================================================================

    @Query("SELECT aa.virtualAccountId FROM AccountAttachment aa WHERE aa.legalEntityId = :entityId AND aa.relationshipType = 'OWNER' AND aa.status = 'ACTIVE'")
    List<UUID> findOwnedVirtualAccountIds(@Param("entityId") UUID legalEntityId);

    @Query("""
        SELECT aa.virtualAccountId FROM AccountAttachment aa 
        WHERE aa.legalEntityId = :entityId 
        AND aa.relationshipType IN ('OWNER', 'AUTHORIZED') 
        AND aa.status = 'ACTIVE'
        """)
    List<UUID> findAccessibleVirtualAccountIds(@Param("entityId") UUID legalEntityId);

    @Query("SELECT aa.legalEntityId FROM AccountAttachment aa WHERE aa.virtualAccountId = :vaId AND aa.relationshipType = 'OWNER' AND aa.isPrimary = true AND aa.status = 'ACTIVE'")
    Optional<UUID> findOwnerEntityId(@Param("vaId") UUID virtualAccountId);

    @Query("""
        SELECT aa.legalEntityId FROM AccountAttachment aa 
        WHERE aa.virtualAccountId = :vaId 
        AND aa.relationshipType IN ('OWNER', 'AUTHORIZED') 
        AND aa.status = 'ACTIVE'
        """)
    List<UUID> findAuthorizedEntityIds(@Param("vaId") UUID virtualAccountId);
}