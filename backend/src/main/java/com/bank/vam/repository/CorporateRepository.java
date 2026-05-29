package com.bank.vam.repository;

import com.bank.vam.entity.Corporate;
import com.bank.vam.entity.Corporate.CorporateStatus;
import com.bank.vam.entity.Corporate.KycStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CorporateRepository extends JpaRepository<Corporate, UUID> {
    
    // Entity has 'corporateId' field, not 'corporateCode'
    Optional<Corporate> findByCorporateId(String corporateId);
    
    Page<Corporate> findByStatus(CorporateStatus status, Pageable pageable);
    
    List<Corporate> findByStatus(CorporateStatus status);
    
    @Query("SELECT c FROM Corporate c WHERE c.status = :status")
    Page<Corporate> findByStatusString(@Param("status") String status, Pageable pageable);
    
    long countByStatus(CorporateStatus status);
    
    @Query("SELECT COUNT(c) FROM Corporate c WHERE c.status = :status")
    long countByStatusString(@Param("status") String status);
    
    long countByKycStatus(KycStatus kycStatus);
    
    @Query("SELECT COUNT(c) FROM Corporate c WHERE c.kycStatus = :kycStatus")
    long countByKycStatusString(@Param("kycStatus") String kycStatus);
    
    @Query("SELECT c FROM Corporate c WHERE c.status = 'ACTIVE' ORDER BY c.createdAt DESC")
    Page<Corporate> findAllActive(Pageable pageable);
    
    // ============================================================================
    // DASHBOARD METHODS
    // ============================================================================
    
    /**
     * Find corporates by KYC status
     */
    List<Corporate> findByKycStatus(KycStatus kycStatus);
    
    /**
     * Find corporates by KYC status with pagination
     */
    Page<Corporate> findByKycStatus(KycStatus kycStatus, Pageable pageable);
    
    /**
     * Find pending KYC corporates ordered by creation date
     */
    @Query("SELECT c FROM Corporate c WHERE c.kycStatus = 'PENDING' ORDER BY c.createdAt ASC")
    List<Corporate> findPendingKyc();
    
    /**
     * Count by status group
     */
    @Query("SELECT c.status, COUNT(c) FROM Corporate c GROUP BY c.status")
    List<Object[]> countByStatusGroup();
    
    /**
     * Count by KYC status group
     */
    @Query("SELECT c.kycStatus, COUNT(c) FROM Corporate c GROUP BY c.kycStatus")
    List<Object[]> countByKycStatusGroup();
    
    /**
     * Find recently onboarded corporates with pagination
     */
    @Query("SELECT c FROM Corporate c ORDER BY c.createdAt DESC")
    Page<Corporate> findRecentlyOnboarded(Pageable pageable);
    
    /**
     * Search corporates
     */
    @Query("SELECT c FROM Corporate c WHERE " +
           "LOWER(c.corporateId) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(c.legalName) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(c.tradeName) LIKE LOWER(CONCAT('%', :query, '%'))")
    Page<Corporate> search(@Param("query") String query, Pageable pageable);
    
    /**
     * Find corporates with EXPIRED KYC status
     * Note: Using kycStatus enum since kycExpiryDate field doesn't exist in entity
     */
    @Query("SELECT c FROM Corporate c WHERE c.kycStatus = 'EXPIRED'")
    List<Corporate> findWithExpiredKyc();
    
    /**
     * Find corporates needing KYC review (pending or under review)
     */
    @Query("SELECT c FROM Corporate c WHERE c.kycStatus IN ('PENDING', 'UNDER_REVIEW') ORDER BY c.createdAt ASC")
    List<Corporate> findNeedingKycReview();
}