package com.bank.vam.repository.party;

import com.bank.vam.entity.party.Party;
import com.bank.vam.entity.party.Party.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Party Repository - Enhanced with POBO/IC query methods
 * 
 * Phase 1: Adds queries for Payment Factory capabilities:
 * - Find parties by owning entity
 * - Find POBO-eligible vendors
 * - Find intercompany parties
 * - Find netting-eligible parties
 */
@Repository
public interface PartyRepository extends JpaRepository<Party, UUID> {

    // ========================================================================
    // EXISTING METHODS (Preserved for backward compatibility)
    // ========================================================================

    Optional<Party> findByPartyCode(String partyCode);

    Page<Party> findByCorporateId(UUID corporateId, Pageable pageable);

    List<Party> findByCorporateId(UUID corporateId);

    Page<Party> findByCorporateIdAndStatus(UUID corporateId, PartyStatus status, Pageable pageable);

    Page<Party> findByCorporateIdAndPartyType(UUID corporateId, PartyType partyType, Pageable pageable);

    Page<Party> findByCorporateIdAndKycStatus(UUID corporateId, KycStatus kycStatus, Pageable pageable);

    Page<Party> findByCorporateIdAndRiskRating(UUID corporateId, RiskRating riskRating, Pageable pageable);

    // Search by name, code, or tax ID
    @Query("SELECT p FROM Party p WHERE p.corporateId = :corporateId AND " +
           "(LOWER(p.legalName) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(p.displayName) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(p.partyCode) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(p.taxId) LIKE LOWER(CONCAT('%', :query, '%')))")
    Page<Party> search(@Param("corporateId") UUID corporateId, @Param("query") String query, Pageable pageable);

    // Find by role (roles is comma-separated)
    @Query("SELECT p FROM Party p WHERE p.corporateId = :corporateId AND p.roles LIKE CONCAT('%', :role, '%')")
    Page<Party> findByRole(@Param("corporateId") UUID corporateId, @Param("role") String role, Pageable pageable);

    // Combined search with filters
    @Query("SELECT p FROM Party p WHERE p.corporateId = :corporateId " +
           "AND (:query IS NULL OR :query = '' OR " +
           "    LOWER(p.legalName) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "    LOWER(p.displayName) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "    LOWER(p.partyCode) LIKE LOWER(CONCAT('%', :query, '%'))) " +
           "AND (:partyType IS NULL OR p.partyType = :partyType) " +
           "AND (:kycStatus IS NULL OR p.kycStatus = :kycStatus) " +
           "AND (:riskRating IS NULL OR p.riskRating = :riskRating) " +
           "AND (:status IS NULL OR p.status = :status) " +
           "AND (:role IS NULL OR :role = '' OR p.roles LIKE CONCAT('%', :role, '%'))")
    Page<Party> searchWithFilters(
            @Param("corporateId") UUID corporateId,
            @Param("query") String query,
            @Param("partyType") PartyType partyType,
            @Param("kycStatus") KycStatus kycStatus,
            @Param("riskRating") RiskRating riskRating,
            @Param("status") PartyStatus status,
            @Param("role") String role,
            Pageable pageable);

    // Counts
    long countByCorporateId(UUID corporateId);

    long countByCorporateIdAndStatus(UUID corporateId, PartyStatus status);

    long countByCorporateIdAndKycStatus(UUID corporateId, KycStatus kycStatus);

    long countByCorporateIdAndRiskRating(UUID corporateId, RiskRating riskRating);

    @Query("SELECT COUNT(p) FROM Party p WHERE p.corporateId = :corporateId AND p.roles LIKE CONCAT('%', :role, '%')")
    long countByRole(@Param("corporateId") UUID corporateId, @Param("role") String role);

    // KYC expiring soon
    @Query("SELECT p FROM Party p WHERE p.corporateId = :corporateId " +
           "AND p.kycExpiresAt IS NOT NULL AND p.kycExpiresAt <= :date " +
           "AND p.kycStatus = 'VERIFIED'")
    List<Party> findKycExpiringSoon(@Param("corporateId") UUID corporateId, @Param("date") LocalDate date);

    // Sanctions alerts
    @Query("SELECT p FROM Party p WHERE p.corporateId = :corporateId " +
           "AND p.sanctionsStatus = 'POTENTIAL_MATCH'")
    List<Party> findWithSanctionsAlerts(@Param("corporateId") UUID corporateId);

    // High risk parties
    @Query("SELECT p FROM Party p WHERE p.corporateId = :corporateId " +
           "AND (p.riskRating = 'HIGH' OR p.riskRating = 'PROHIBITED')")
    List<Party> findHighRisk(@Param("corporateId") UUID corporateId);

    // Stats query
    @Query("SELECT " +
           "COUNT(p), " +
           "SUM(CASE WHEN p.roles LIKE '%CUSTOMER%' THEN 1 ELSE 0 END), " +
           "SUM(CASE WHEN p.roles LIKE '%VENDOR%' THEN 1 ELSE 0 END), " +
           "SUM(CASE WHEN p.roles LIKE '%EMPLOYEE%' THEN 1 ELSE 0 END), " +
           "SUM(CASE WHEN p.roles LIKE '%GOVERNMENT%' THEN 1 ELSE 0 END), " +
           "SUM(CASE WHEN p.roles LIKE '%FINANCIAL%' THEN 1 ELSE 0 END), " +
           "SUM(CASE WHEN p.kycStatus = 'PENDING' THEN 1 ELSE 0 END), " +
           "SUM(CASE WHEN p.kycStatus = 'EXPIRED' THEN 1 ELSE 0 END), " +
           "SUM(CASE WHEN p.riskRating = 'HIGH' OR p.riskRating = 'PROHIBITED' THEN 1 ELSE 0 END), " +
           "SUM(CASE WHEN p.sanctionsStatus = 'POTENTIAL_MATCH' THEN 1 ELSE 0 END) " +
           "FROM Party p WHERE p.corporateId = :corporateId")
    List<Object[]> getStats(@Param("corporateId") UUID corporateId);

    // ========================================================================
    // PHASE 1: ENTITY CONTEXT QUERIES
    // ========================================================================
    
    /**
     * Find all parties owned by a specific legal entity.
     * Used when a subsidiary needs to see "their" vendors/customers.
     */
    @Query("SELECT p FROM Party p WHERE p.corporateId = :corporateId " +
           "AND p.owningEntityId = :owningEntityId " +
           "AND p.status = 'ACTIVE'")
    List<Party> findByOwningEntityId(
            @Param("corporateId") UUID corporateId,
            @Param("owningEntityId") UUID owningEntityId);
    
    /**
     * Find parties by owning entity with pagination and role filter.
     */
    @Query("SELECT p FROM Party p WHERE p.corporateId = :corporateId " +
           "AND p.owningEntityId = :owningEntityId " +
           "AND (:role IS NULL OR :role = '' OR p.roles LIKE CONCAT('%', :role, '%')) " +
           "AND p.status = 'ACTIVE'")
    Page<Party> findByOwningEntityIdAndRole(
            @Param("corporateId") UUID corporateId,
            @Param("owningEntityId") UUID owningEntityId,
            @Param("role") String role,
            Pageable pageable);
    
    /**
     * Count parties by owning entity.
     */
    @Query("SELECT COUNT(p) FROM Party p WHERE p.corporateId = :corporateId " +
           "AND p.owningEntityId = :owningEntityId " +
           "AND p.status = 'ACTIVE'")
    long countByOwningEntityId(
            @Param("corporateId") UUID corporateId,
            @Param("owningEntityId") UUID owningEntityId);

    // ========================================================================
    // PHASE 1: POBO ELIGIBILITY QUERIES
    // ========================================================================
    
    /**
     * Find all POBO-eligible vendors for a corporate.
     * Used by treasury to see which vendors can receive POBO payments.
     */
    @Query("SELECT p FROM Party p WHERE p.corporateId = :corporateId " +
           "AND p.poboEligible = TRUE " +
           "AND p.roles LIKE '%VENDOR%' " +
           "AND p.status = 'ACTIVE' " +
           "AND (p.kycStatus = 'VERIFIED' OR p.kycStatus = 'EXEMPTED')")
    List<Party> findPoboEligibleVendors(@Param("corporateId") UUID corporateId);
    
    /**
     * Find POBO-eligible vendors for a specific owning entity.
     * Used when subsidiary wants to see their vendors that can be paid via POBO.
     */
    @Query("SELECT p FROM Party p WHERE p.corporateId = :corporateId " +
           "AND p.owningEntityId = :owningEntityId " +
           "AND p.poboEligible = TRUE " +
           "AND p.roles LIKE '%VENDOR%' " +
           "AND p.status = 'ACTIVE' " +
           "AND (p.kycStatus = 'VERIFIED' OR p.kycStatus = 'EXEMPTED')")
    List<Party> findPoboEligibleVendorsByOwningEntity(
            @Param("corporateId") UUID corporateId,
            @Param("owningEntityId") UUID owningEntityId);
    
    /**
     * Find POBO-eligible vendors with pagination and search.
     */
    @Query("SELECT p FROM Party p WHERE p.corporateId = :corporateId " +
           "AND p.poboEligible = TRUE " +
           "AND p.roles LIKE '%VENDOR%' " +
           "AND p.status = 'ACTIVE' " +
           "AND (p.kycStatus = 'VERIFIED' OR p.kycStatus = 'EXEMPTED') " +
           "AND (:query IS NULL OR :query = '' OR " +
           "    LOWER(p.legalName) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "    LOWER(p.displayName) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "    LOWER(p.partyCode) LIKE LOWER(CONCAT('%', :query, '%')))")
    Page<Party> findPoboEligibleVendorsWithSearch(
            @Param("corporateId") UUID corporateId,
            @Param("query") String query,
            Pageable pageable);
    
    /**
     * Find vendors with a specific default POBO payer entity.
     * Used when treasury wants to see all vendors they typically pay for.
     */
    @Query("SELECT p FROM Party p WHERE p.corporateId = :corporateId " +
           "AND p.poboDefaultPayerEntityId = :payerEntityId " +
           "AND p.poboEligible = TRUE " +
           "AND p.status = 'ACTIVE'")
    List<Party> findByDefaultPoboPayerEntity(
            @Param("corporateId") UUID corporateId,
            @Param("payerEntityId") UUID payerEntityId);
    
    /**
     * Count POBO-eligible vendors.
     */
    @Query("SELECT COUNT(p) FROM Party p WHERE p.corporateId = :corporateId " +
           "AND p.poboEligible = TRUE " +
           "AND p.roles LIKE '%VENDOR%' " +
           "AND p.status = 'ACTIVE'")
    long countPoboEligibleVendors(@Param("corporateId") UUID corporateId);

    // ========================================================================
    // PHASE 1: INTERCOMPANY QUERIES
    // ========================================================================
    
    /**
     * Find all intercompany parties (group entities as vendors/customers).
     */
    @Query("SELECT p FROM Party p WHERE p.corporateId = :corporateId " +
           "AND p.isIntercompany = TRUE " +
           "AND p.linkedLegalEntityId IS NOT NULL " +
           "AND p.status = 'ACTIVE'")
    List<Party> findIntercompanyParties(@Param("corporateId") UUID corporateId);
    
    /**
     * Find intercompany parties by owning entity.
     * Used to see which group entities a subsidiary has relationships with.
     */
    @Query("SELECT p FROM Party p WHERE p.corporateId = :corporateId " +
           "AND p.owningEntityId = :owningEntityId " +
           "AND p.isIntercompany = TRUE " +
           "AND p.linkedLegalEntityId IS NOT NULL " +
           "AND p.status = 'ACTIVE'")
    List<Party> findIntercompanyPartiesByOwningEntity(
            @Param("corporateId") UUID corporateId,
            @Param("owningEntityId") UUID owningEntityId);
    
    /**
     * Find the party record representing a specific legal entity.
     * Used for reverse lookup: "Give me the party record for SUB-SINGAPORE".
     */
    @Query("SELECT p FROM Party p WHERE p.corporateId = :corporateId " +
           "AND p.linkedLegalEntityId = :linkedEntityId " +
           "AND p.owningEntityId = :owningEntityId " +
           "AND p.isIntercompany = TRUE " +
           "AND p.status = 'ACTIVE'")
    Optional<Party> findIntercompanyPartyByLinkedEntity(
            @Param("corporateId") UUID corporateId,
            @Param("linkedEntityId") UUID linkedEntityId,
            @Param("owningEntityId") UUID owningEntityId);
    
    /**
     * Find all party records for a linked legal entity (across all owning entities).
     */
    @Query("SELECT p FROM Party p WHERE p.corporateId = :corporateId " +
           "AND p.linkedLegalEntityId = :linkedEntityId " +
           "AND p.isIntercompany = TRUE " +
           "AND p.status = 'ACTIVE'")
    List<Party> findAllIntercompanyPartiesForLinkedEntity(
            @Param("corporateId") UUID corporateId,
            @Param("linkedEntityId") UUID linkedEntityId);
    
    /**
     * Find intercompany vendors (group entities we buy from).
     */
    @Query("SELECT p FROM Party p WHERE p.corporateId = :corporateId " +
           "AND p.isIntercompany = TRUE " +
           "AND p.roles LIKE '%VENDOR%' " +
           "AND p.linkedLegalEntityId IS NOT NULL " +
           "AND p.status = 'ACTIVE'")
    List<Party> findIntercompanyVendors(@Param("corporateId") UUID corporateId);
    
    /**
     * Find intercompany customers (group entities we sell to).
     */
    @Query("SELECT p FROM Party p WHERE p.corporateId = :corporateId " +
           "AND p.isIntercompany = TRUE " +
           "AND p.roles LIKE '%CUSTOMER%' " +
           "AND p.linkedLegalEntityId IS NOT NULL " +
           "AND p.status = 'ACTIVE'")
    List<Party> findIntercompanyCustomers(@Param("corporateId") UUID corporateId);
    
    /**
     * Count intercompany parties.
     */
    @Query("SELECT COUNT(p) FROM Party p WHERE p.corporateId = :corporateId " +
           "AND p.isIntercompany = TRUE " +
           "AND p.status = 'ACTIVE'")
    long countIntercompanyParties(@Param("corporateId") UUID corporateId);

    // ========================================================================
    // PHASE 1: NETTING ELIGIBILITY QUERIES
    // ========================================================================
    
    /**
     * Find all netting-eligible parties.
     * Includes both explicitly netting-eligible AND intercompany parties.
     */
    @Query("SELECT p FROM Party p WHERE p.corporateId = :corporateId " +
           "AND (p.nettingEligible = TRUE OR (p.isIntercompany = TRUE AND p.linkedLegalEntityId IS NOT NULL)) " +
           "AND p.status = 'ACTIVE'")
    List<Party> findNettingEligibleParties(@Param("corporateId") UUID corporateId);
    
    /**
     * Find netting-eligible parties for a specific owning entity.
     */
    @Query("SELECT p FROM Party p WHERE p.corporateId = :corporateId " +
           "AND p.owningEntityId = :owningEntityId " +
           "AND (p.nettingEligible = TRUE OR (p.isIntercompany = TRUE AND p.linkedLegalEntityId IS NOT NULL)) " +
           "AND p.status = 'ACTIVE'")
    List<Party> findNettingEligiblePartiesByOwningEntity(
            @Param("corporateId") UUID corporateId,
            @Param("owningEntityId") UUID owningEntityId);
    
    /**
     * Count netting-eligible parties.
     */
    @Query("SELECT COUNT(p) FROM Party p WHERE p.corporateId = :corporateId " +
           "AND (p.nettingEligible = TRUE OR (p.isIntercompany = TRUE AND p.linkedLegalEntityId IS NOT NULL)) " +
           "AND p.status = 'ACTIVE'")
    long countNettingEligibleParties(@Param("corporateId") UUID corporateId);

    // ========================================================================
    // PHASE 1: IC CREDIT MANAGEMENT QUERIES
    // ========================================================================
    
    /**
     * Find intercompany parties with credit limit defined.
     */
    @Query("SELECT p FROM Party p WHERE p.corporateId = :corporateId " +
           "AND p.isIntercompany = TRUE " +
           "AND p.icCreditLimit IS NOT NULL " +
           "AND p.status = 'ACTIVE'")
    List<Party> findIntercompanyPartiesWithCreditLimit(@Param("corporateId") UUID corporateId);
    
    /**
     * Find intercompany parties with credit utilization above threshold.
     */
    @Query("SELECT p FROM Party p WHERE p.corporateId = :corporateId " +
           "AND p.isIntercompany = TRUE " +
           "AND p.icCreditLimit IS NOT NULL " +
           "AND p.icCreditLimit > 0 " +
           "AND p.icCurrentExposure IS NOT NULL " +
           "AND (p.icCurrentExposure / p.icCreditLimit) >= :thresholdPercent / 100.0 " +
           "AND p.status = 'ACTIVE'")
    List<Party> findIntercompanyPartiesAboveCreditThreshold(
            @Param("corporateId") UUID corporateId,
            @Param("thresholdPercent") BigDecimal thresholdPercent);
    
    /**
     * Find intercompany parties exceeding credit limit.
     */
    @Query("SELECT p FROM Party p WHERE p.corporateId = :corporateId " +
           "AND p.isIntercompany = TRUE " +
           "AND p.icCreditLimit IS NOT NULL " +
           "AND p.icCurrentExposure IS NOT NULL " +
           "AND p.icCurrentExposure > p.icCreditLimit " +
           "AND p.status = 'ACTIVE'")
    List<Party> findIntercompanyPartiesExceedingCreditLimit(@Param("corporateId") UUID corporateId);

    // ========================================================================
    // PHASE 1: UPDATE METHODS
    // ========================================================================
    
    /**
     * Update POBO eligibility for a party.
     */
    @Modifying
    @Query("UPDATE Party p SET " +
           "p.poboEligible = :eligible, " +
           "p.poboDefaultPayerEntityId = :defaultPayerEntityId, " +
           "p.poboDefaultPayerEntityCode = :defaultPayerEntityCode " +
           "WHERE p.id = :partyId")
    int updatePoboEligibility(
            @Param("partyId") UUID partyId,
            @Param("eligible") Boolean eligible,
            @Param("defaultPayerEntityId") UUID defaultPayerEntityId,
            @Param("defaultPayerEntityCode") String defaultPayerEntityCode);
    
    /**
     * Update intercompany settings for a party.
     */
    @Modifying
    @Query("UPDATE Party p SET " +
           "p.isIntercompany = :isIntercompany, " +
           "p.linkedLegalEntityId = :linkedEntityId, " +
           "p.linkedLegalEntityCode = :linkedEntityCode, " +
           "p.nettingEligible = :nettingEligible, " +
           "p.icSettlementMethod = :settlementMethod " +
           "WHERE p.id = :partyId")
    int updateIntercompanySettings(
            @Param("partyId") UUID partyId,
            @Param("isIntercompany") Boolean isIntercompany,
            @Param("linkedEntityId") UUID linkedEntityId,
            @Param("linkedEntityCode") String linkedEntityCode,
            @Param("nettingEligible") Boolean nettingEligible,
            @Param("settlementMethod") IntercompanySettlementMethod settlementMethod);
    
    /**
     * Update IC credit limit for a party.
     */
    @Modifying
    @Query("UPDATE Party p SET " +
           "p.icCreditLimit = :creditLimit, " +
           "p.icCurrency = :currency " +
           "WHERE p.id = :partyId AND p.isIntercompany = TRUE")
    int updateIcCreditLimit(
            @Param("partyId") UUID partyId,
            @Param("creditLimit") BigDecimal creditLimit,
            @Param("currency") String currency);
    
    /**
     * Update IC current exposure for a party.
     */
    @Modifying
    @Query("UPDATE Party p SET p.icCurrentExposure = :exposure WHERE p.id = :partyId")
    int updateIcCurrentExposure(
            @Param("partyId") UUID partyId,
            @Param("exposure") BigDecimal exposure);

    // ========================================================================
    // PHASE 1: ENHANCED STATS QUERY
    // ========================================================================
    
    /**
     * Get extended stats including POBO/IC counts.
     */
    @Query("SELECT " +
           "COUNT(p), " +
           "SUM(CASE WHEN p.roles LIKE '%CUSTOMER%' THEN 1 ELSE 0 END), " +
           "SUM(CASE WHEN p.roles LIKE '%VENDOR%' THEN 1 ELSE 0 END), " +
           "SUM(CASE WHEN p.roles LIKE '%EMPLOYEE%' THEN 1 ELSE 0 END), " +
           "SUM(CASE WHEN p.roles LIKE '%GOVERNMENT%' THEN 1 ELSE 0 END), " +
           "SUM(CASE WHEN p.roles LIKE '%FINANCIAL%' THEN 1 ELSE 0 END), " +
           "SUM(CASE WHEN p.kycStatus = 'PENDING' THEN 1 ELSE 0 END), " +
           "SUM(CASE WHEN p.kycStatus = 'EXPIRED' THEN 1 ELSE 0 END), " +
           "SUM(CASE WHEN p.riskRating = 'HIGH' OR p.riskRating = 'PROHIBITED' THEN 1 ELSE 0 END), " +
           "SUM(CASE WHEN p.sanctionsStatus = 'POTENTIAL_MATCH' THEN 1 ELSE 0 END), " +
           // Phase 1 stats
           "SUM(CASE WHEN p.poboEligible = TRUE THEN 1 ELSE 0 END), " +
           "SUM(CASE WHEN p.isIntercompany = TRUE THEN 1 ELSE 0 END), " +
           "SUM(CASE WHEN p.nettingEligible = TRUE OR p.isIntercompany = TRUE THEN 1 ELSE 0 END) " +
           "FROM Party p WHERE p.corporateId = :corporateId")
    List<Object[]> getExtendedStats(@Param("corporateId") UUID corporateId);
}