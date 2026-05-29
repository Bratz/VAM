package com.bank.vam.repository;

import com.bank.vam.entity.PhysicalAccount;
import com.bank.vam.entity.PhysicalAccount.AccountStatus;
import com.bank.vam.entity.PhysicalAccount.AccountType;
import com.bank.vam.entity.PhysicalAccount.SyncStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * PhysicalAccountRepository - Data access for physical bank accounts.
 * 
 * Supports:
 * - Basic CRUD operations
 * - Corporate-level queries (multi-tenancy)
 * - Bank and currency grouping
 * - Treasury feature queries (pooling, sweeping)
 * - Sync status management
 */
@Repository
public interface PhysicalAccountRepository extends JpaRepository<PhysicalAccount, UUID> {

    // ========================================================================
    // BASIC LOOKUPS
    // ========================================================================

    Optional<PhysicalAccount> findByAccountNumber(String accountNumber);

    Optional<PhysicalAccount> findByIban(String iban);

    boolean existsByAccountNumber(String accountNumber);

    boolean existsByIban(String iban);

    // ========================================================================
    // CORPORATE QUERIES (Multi-tenancy)
    // ========================================================================

    Page<PhysicalAccount> findByCorporateId(UUID corporateId, Pageable pageable);

    List<PhysicalAccount> findByCorporateId(UUID corporateId);

    long countByCorporateId(UUID corporateId);

    @Query("SELECT COALESCE(SUM(p.currentBalance), 0) FROM PhysicalAccount p WHERE p.corporateId = :corporateId")
    BigDecimal sumBalanceByCorporateId(@Param("corporateId") UUID corporateId);

    // ========================================================================
    // STATUS QUERIES
    // ========================================================================

    Page<PhysicalAccount> findByStatus(AccountStatus status, Pageable pageable);

    List<PhysicalAccount> findByStatus(AccountStatus status);

    long countByStatus(AccountStatus status);

    @Query("SELECT COUNT(p) FROM PhysicalAccount p WHERE p.status = :status")
    long countByStatusString(@Param("status") String status);

    Page<PhysicalAccount> findByCorporateIdAndStatus(UUID corporateId, AccountStatus status, Pageable pageable);

    // ========================================================================
    // BANK QUERIES
    // ========================================================================

    List<PhysicalAccount> findByBankCode(String bankCode);

    Page<PhysicalAccount> findByBankCode(String bankCode, Pageable pageable);

    long countByBankCode(String bankCode);

    @Query("SELECT DISTINCT p.bankCode FROM PhysicalAccount p WHERE p.bankCode IS NOT NULL")
    List<String> findDistinctBankCodes();

    @Query("SELECT DISTINCT p.bankName FROM PhysicalAccount p WHERE p.bankName IS NOT NULL")
    List<String> findDistinctBankNames();

    // ========================================================================
    // CURRENCY QUERIES
    // ========================================================================

    List<PhysicalAccount> findByCurrencyCode(String currencyCode);

    Page<PhysicalAccount> findByCurrencyCode(String currencyCode, Pageable pageable);

    long countByCurrencyCode(String currencyCode);

    @Query("SELECT DISTINCT p.currencyCode FROM PhysicalAccount p WHERE p.currencyCode IS NOT NULL ORDER BY p.currencyCode")
    List<String> findDistinctCurrencyCodes();

    List<PhysicalAccount> findByBankCodeAndCurrencyCode(String bankCode, String currencyCode);

    // ========================================================================
    // ACCOUNT TYPE QUERIES
    // ========================================================================

    List<PhysicalAccount> findByAccountType(AccountType accountType);

    long countByAccountType(AccountType accountType);

    // ========================================================================
    // TREASURY - NOTIONAL POOLING
    // ========================================================================

    List<PhysicalAccount> findByPoolingEnabled(Boolean poolingEnabled);

    long countByPoolingEnabled(Boolean poolingEnabled);

    List<PhysicalAccount> findByPoolId(UUID poolId);

    List<PhysicalAccount> findByPoolReference(String poolReference);

    @Query("SELECT p FROM PhysicalAccount p WHERE p.poolingEnabled = true AND p.status = 'ACTIVE'")
    List<PhysicalAccount> findActivePoolingAccounts();

    // ========================================================================
    // TREASURY - CASH CONCENTRATION (SWEEPING)
    // ========================================================================

    List<PhysicalAccount> findBySweepEnabled(Boolean sweepEnabled);

    long countBySweepEnabled(Boolean sweepEnabled);

    List<PhysicalAccount> findBySweepRuleId(UUID sweepRuleId);

    @Query("SELECT p FROM PhysicalAccount p WHERE p.sweepEnabled = true AND p.sweepRole = 'HEADER' AND p.status = 'ACTIVE'")
    List<PhysicalAccount> findSweepHeaderAccounts();

    @Query("SELECT p FROM PhysicalAccount p WHERE p.sweepEnabled = true AND p.sweepRole = 'PARTICIPANT' AND p.status = 'ACTIVE'")
    List<PhysicalAccount> findSweepParticipantAccounts();

    @Query("SELECT p FROM PhysicalAccount p WHERE p.sweepRuleId = :sweepRuleId AND p.sweepRole = 'HEADER'")
    Optional<PhysicalAccount> findSweepHeader(@Param("sweepRuleId") UUID sweepRuleId);

    @Query("SELECT p FROM PhysicalAccount p WHERE p.sweepRuleId = :sweepRuleId AND p.sweepRole = 'PARTICIPANT'")
    List<PhysicalAccount> findSweepParticipants(@Param("sweepRuleId") UUID sweepRuleId);

    // ========================================================================
    // SYNC STATUS
    // ========================================================================

    List<PhysicalAccount> findBySyncStatus(SyncStatus syncStatus);

    long countBySyncStatus(SyncStatus syncStatus);

    @Query("SELECT p FROM PhysicalAccount p WHERE p.syncStatus = 'PENDING' OR p.syncStatus = 'ERROR'")
    List<PhysicalAccount> findAccountsNeedingSync();

    @Query("SELECT p FROM PhysicalAccount p WHERE p.lastSyncAt < :before AND p.status = 'ACTIVE'")
    List<PhysicalAccount> findStaleAccounts(@Param("before") LocalDateTime before);

    // ========================================================================
    // BALANCE QUERIES
    // ========================================================================

    @Query("SELECT COALESCE(SUM(p.currentBalance), 0) FROM PhysicalAccount p")
    BigDecimal sumTotalBalance();

    @Query("SELECT COALESCE(SUM(p.currentBalance), 0) FROM PhysicalAccount p WHERE p.status = 'ACTIVE'")
    BigDecimal sumTotalActiveBalance();

    @Query("SELECT COALESCE(SUM(p.currentBalance), 0) FROM PhysicalAccount p WHERE p.bankCode = :bankCode")
    BigDecimal sumBalanceByBankCode(@Param("bankCode") String bankCode);

    @Query("SELECT COALESCE(SUM(p.currentBalance), 0) FROM PhysicalAccount p WHERE p.currencyCode = :currency")
    BigDecimal sumBalanceByCurrency(@Param("currency") String currency);

    @Query("SELECT p FROM PhysicalAccount p WHERE p.currentBalance > :minBalance ORDER BY p.currentBalance DESC")
    List<PhysicalAccount> findByMinBalance(@Param("minBalance") BigDecimal minBalance);

    @Query("SELECT p FROM PhysicalAccount p WHERE p.currentBalance < :maxBalance ORDER BY p.currentBalance ASC")
    List<PhysicalAccount> findByMaxBalance(@Param("maxBalance") BigDecimal maxBalance);

    // ========================================================================
    // SEARCH
    // ========================================================================

    @Query("SELECT p FROM PhysicalAccount p WHERE " +
           "LOWER(p.accountName) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(p.accountNumber) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(p.iban) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(p.entityName) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(p.bankName) LIKE LOWER(CONCAT('%', :query, '%'))")
    List<PhysicalAccount> search(@Param("query") String query);

    @Query("SELECT p FROM PhysicalAccount p WHERE p.corporateId = :corporateId AND (" +
           "LOWER(p.accountName) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(p.accountNumber) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(p.iban) LIKE LOWER(CONCAT('%', :query, '%')))")
    List<PhysicalAccount> searchByCorporate(@Param("corporateId") UUID corporateId, @Param("query") String query);

    // ========================================================================
    // COMPLEX FILTERS
    // ========================================================================

    @Query("SELECT p FROM PhysicalAccount p WHERE " +
           "(:corporateId IS NULL OR p.corporateId = :corporateId) AND " +
           "(:bankCode IS NULL OR p.bankCode = :bankCode) AND " +
           "(:currency IS NULL OR p.currencyCode = :currency) AND " +
           "(:status IS NULL OR p.status = :status) AND " +
           "(:poolingEnabled IS NULL OR p.poolingEnabled = :poolingEnabled) AND " +
           "(:sweepEnabled IS NULL OR p.sweepEnabled = :sweepEnabled)")
    List<PhysicalAccount> findWithFilters(
            @Param("corporateId") UUID corporateId,
            @Param("bankCode") String bankCode,
            @Param("currency") String currency,
            @Param("status") AccountStatus status,
            @Param("poolingEnabled") Boolean poolingEnabled,
            @Param("sweepEnabled") Boolean sweepEnabled
    );

    // ========================================================================
    // AGGREGATIONS
    // ========================================================================

    @Query("SELECT p.bankCode, p.bankName, COUNT(p), COALESCE(SUM(p.currentBalance), 0) " +
           "FROM PhysicalAccount p " +
           "WHERE p.bankCode IS NOT NULL " +
           "GROUP BY p.bankCode, p.bankName")
    List<Object[]> getBalanceByBank();

    @Query("SELECT p.currencyCode, COUNT(p), COALESCE(SUM(p.currentBalance), 0) " +
           "FROM PhysicalAccount p " +
           "GROUP BY p.currencyCode")
    List<Object[]> getBalanceByCurrency();

    @Query("SELECT p.status, COUNT(p) FROM PhysicalAccount p GROUP BY p.status")
    List<Object[]> countByStatusGroup();

    // ========================================================================
    // BATCH UPDATES
    // ========================================================================

    @Modifying
    @Query("UPDATE PhysicalAccount p SET p.syncStatus = :status, p.lastSyncAt = :syncTime WHERE p.id IN :ids")
    int updateSyncStatus(@Param("ids") List<UUID> ids, @Param("status") SyncStatus status, @Param("syncTime") LocalDateTime syncTime);

    @Modifying
    @Query("UPDATE PhysicalAccount p SET p.syncStatus = 'SYNCED', p.lastSyncAt = CURRENT_TIMESTAMP WHERE p.status = 'ACTIVE'")
    int markAllSynced();

    /**
     * Find physical accounts by legal entity ID.
     */
    List<PhysicalAccount> findByLegalEntityId(UUID legalEntityId);
    
    Page<PhysicalAccount> findByLegalEntityId(UUID legalEntityId, Pageable pageable);
    
    long countByLegalEntityId(UUID legalEntityId);

    /**
     * Find physical accounts by entity code (legacy support).
     */
    List<PhysicalAccount> findByEntityCode(String entityCode);

    /**
     * Find physical accounts by legal entity and status.
     */
    List<PhysicalAccount> findByLegalEntityIdAndStatus(UUID legalEntityId, AccountStatus status);

    /**
     * Find physical accounts for multiple legal entities (hierarchy descendants).
     */
    @Query("SELECT p FROM PhysicalAccount p WHERE p.legalEntityId IN :entityIds")
    List<PhysicalAccount> findByLegalEntityIdIn(@Param("entityIds") List<UUID> entityIds);

    /**
     * Sum balance by legal entity.
     */
    @Query("SELECT COALESCE(SUM(p.currentBalance), 0) FROM PhysicalAccount p WHERE p.legalEntityId = :legalEntityId")
    BigDecimal sumBalanceByLegalEntityId(@Param("legalEntityId") UUID legalEntityId);


}