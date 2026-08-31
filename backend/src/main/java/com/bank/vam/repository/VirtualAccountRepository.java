package com.bank.vam.repository;

import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.VirtualAccount.VaStatus;
import com.bank.vam.entity.VirtualAccount.AccountCategory;
import com.bank.vam.entity.VirtualAccount.CollectionChannel;
import com.bank.vam.entity.VirtualAccount.VaSpecialType;
import com.bank.vam.entity.VirtualAccount.ValueType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Virtual Account Repository - UPDATED v5.1.0
 * 
 * UNIFIED ARCHITECTURE v5.0:
 * - Basic CRUD operations
 * - Corporate and Program queries
 * - Hierarchy path queries
 * - Currency Mirror VA queries
 * - Parent-child VA tree queries
 * - Wallet type and KYC queries
 * - Limit reset operations
 * - Balance operations
 * - Settlement/Exception VA queries
 * - Legal Entity ownership queries
 * - Hierarchy Operations support (Move, Merge, Limit Transfer)
 * 
 * NEW v5.1.0:
 * - Settlement VA auto-creation support queries
 * - Exception VA auto-creation support queries
 * - Enhanced lookup by program, currency, specialType, status
 */
@Repository
public interface VirtualAccountRepository extends JpaRepository<VirtualAccount, UUID> {
    
    // ========================================================================
    // BASIC LOOKUPS
    // ========================================================================
    
    Optional<VirtualAccount> findByVaNumber(String vaNumber);
    
    Optional<VirtualAccount> findByViban(String viban);
    
    boolean existsByViban(String viban);
    
    boolean existsByVaNumber(String vaNumber);
    
    // ========================================================================
    // CORPORATE QUERIES
    // ========================================================================
    
    Page<VirtualAccount> findByCorporateId(UUID corporateId, Pageable pageable);
    
    List<VirtualAccount> findByCorporateId(UUID corporateId);
    
    long countByCorporateId(UUID corporateId);
    
    int countByCorporateIdAndStatus(UUID corporateId, VaStatus status);
    
    int countByCorporateIdAndAccountCategory(UUID corporateId, AccountCategory accountCategory);
    
    List<VirtualAccount> findByCorporateIdAndAccountCategory(UUID corporateId, AccountCategory accountCategory);
    
    List<VirtualAccount> findByCorporateIdAndHierarchyLevel(UUID corporateId, Integer level);
    
    @Query("SELECT DISTINCT va.currencyCode FROM VirtualAccount va WHERE va.corporateId = :corporateId ORDER BY va.currencyCode")
    List<String> findDistinctCurrencies(@Param("corporateId") UUID corporateId);
    
    // ========================================================================
    // PROGRAM QUERIES
    // ========================================================================
    
    Page<VirtualAccount> findByProgramId(UUID programId, Pageable pageable);
    
    List<VirtualAccount> findByProgramId(UUID programId);
    
    long countByProgramId(UUID programId);
    
    long countByProgramIdAndStatus(UUID programId, VaStatus status);
    
    long countByProgramIdAndAccountCategory(UUID programId, AccountCategory accountCategory);
    
    List<VirtualAccount> findByProgramIdAndStatus(UUID programId, VaStatus status);
    
    List<VirtualAccount> findByProgramIdAndAccountCategory(UUID programId, AccountCategory accountCategory);
    
    List<VirtualAccount> findByProgramIdAndSpecialType(UUID programId, VaSpecialType specialType);
    
    boolean existsByProgramId(UUID programId);
    
    @Query("SELECT COALESCE(SUM(v.currentBalance), 0) FROM VirtualAccount v WHERE v.programId = :programId")
    BigDecimal sumBalanceByProgramId(@Param("programId") UUID programId);
    
    @Query("SELECT COALESCE(SUM(v.availableBalance), 0) FROM VirtualAccount v WHERE v.programId = :programId")
    BigDecimal sumAvailableBalanceByProgramId(@Param("programId") UUID programId);
    
    // ========================================================================
    // PHYSICAL ACCOUNT QUERIES
    // ========================================================================
    
    List<VirtualAccount> findByPhysicalAccountId(UUID physicalAccountId);
    
    Page<VirtualAccount> findByPhysicalAccountId(UUID physicalAccountId, Pageable pageable);
    
    long countByPhysicalAccountId(UUID physicalAccountId);
    
    @Query("SELECT COALESCE(SUM(v.currentBalance), 0) FROM VirtualAccount v WHERE v.physicalAccountId = :physicalAccountId")
    BigDecimal sumBalanceByPhysicalAccountId(@Param("physicalAccountId") UUID physicalAccountId);
    
    // ========================================================================
    // STATUS QUERIES
    // ========================================================================
    
    Page<VirtualAccount> findByStatus(VaStatus status, Pageable pageable);
    
    List<VirtualAccount> findByStatus(VaStatus status);
    
    long countByStatus(VaStatus status);
    
    @Query("SELECT COUNT(v) FROM VirtualAccount v WHERE v.status = 'ACTIVE'")
    long countActive();

    // ========================================================================
    // VA NUMBER AND STATUS LOOKUP (NEW v5.1.0 - Settlement VA Resolution)
    // ========================================================================

    /**
     * Find by VA number and status.
     * Used for naming convention-based Settlement/Exception VA lookup.
     * Uses LIMIT 1 to handle cases where duplicates exist.
     */
    @Query("SELECT va FROM VirtualAccount va WHERE va.vaNumber = :vaNumber AND va.status = :status ORDER BY va.createdAt ASC LIMIT 1")
    Optional<VirtualAccount> findByVaNumberAndStatus(@Param("vaNumber") String vaNumber, @Param("status") VaStatus status);

    /**
     * Resolve a bank's display name from its SWIFT/BIC, scope-independent
     * (any account at that bank, ignoring corporate/category filters). Used
     * to derive the home-bank display name from the configured BIC so it is
     * always consistent with the BIC and never depends on a static config
     * string. LIMIT 1 — any one account's bankName for that BIC suffices.
     */
    @Query("SELECT va.bankName FROM VirtualAccount va WHERE UPPER(va.bankSwift) = UPPER(:bic) AND va.bankName IS NOT NULL ORDER BY va.createdAt ASC LIMIT 1")
    Optional<String> findBankNameByBic(@Param("bic") String bic);
    
    // ========================================================================
    // CURRENCY QUERIES
    // ========================================================================
    
    List<VirtualAccount> findByCurrencyCode(String currencyCode);

    Page<VirtualAccount> findByCurrencyCode(String currencyCode, Pageable pageable);

    /**
     * Find VAs by currency code and account category.
     * Used for finding Exception VAs for a specific currency when no program context is available.
     */
    List<VirtualAccount> findByCurrencyCodeAndAccountCategory(String currencyCode, AccountCategory accountCategory);
    
    // ========================================================================
    // KYC QUERIES
    // ========================================================================
    
    @Query("SELECT COUNT(v) FROM VirtualAccount v WHERE v.kycVerified = true")
    long countKycVerified();
    
    List<VirtualAccount> findByKycVerified(Boolean kycVerified);
    
    List<VirtualAccount> findByKycExpiryDateBefore(LocalDate date);
    
    @Query("SELECT v FROM VirtualAccount v WHERE v.kycExpiryDate BETWEEN :startDate AND :endDate AND v.kycVerified = true")
    List<VirtualAccount> findByKycExpiryDateBetween(@Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);
    
    List<VirtualAccount> findByKycLevel(Integer kycLevel);
    
    long countByKycLevel(Integer kycLevel);
    
    // ========================================================================
    // BALANCE QUERIES
    // ========================================================================
    
    @Query("SELECT v FROM VirtualAccount v WHERE v.currentBalance >= :threshold ORDER BY v.currentBalance DESC")
    List<VirtualAccount> findByBalanceAbove(@Param("threshold") BigDecimal threshold);
    
    @Query("SELECT v FROM VirtualAccount v WHERE v.currentBalance = 0 OR v.currentBalance IS NULL")
    List<VirtualAccount> findZeroBalanceAccounts();
    
    @Query("SELECT COALESCE(SUM(v.currentBalance), 0) FROM VirtualAccount v")
    BigDecimal sumTotalBalance();
    
    @Query("SELECT COALESCE(SUM(v.availableBalance), 0) FROM VirtualAccount v")
    BigDecimal sumTotalAvailableBalance();
    
    @Query("SELECT COALESCE(SUM(v.currentBalance), 0) FROM VirtualAccount v")
    BigDecimal sumCurrentBalance();
    
    @Query("SELECT COALESCE(SUM(v.availableBalance), 0) FROM VirtualAccount v")
    BigDecimal sumAvailableBalance();
    
    @Query("SELECT COALESCE(SUM(v.currentBalance), 0) FROM VirtualAccount v WHERE v.status = 'ACTIVE'")
    BigDecimal sumActiveBalance();
    
    @Query("SELECT v FROM VirtualAccount v ORDER BY v.currentBalance DESC")
    Page<VirtualAccount> findTopByBalanceDesc(Pageable pageable);
    
    // ========================================================================
    // SEARCH
    // ========================================================================
    
    @Query("SELECT v FROM VirtualAccount v WHERE " +
           "LOWER(v.vaNumber) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(v.vaName) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(v.viban) LIKE LOWER(CONCAT('%', :query, '%'))")
    Page<VirtualAccount> search(@Param("query") String query, Pageable pageable);
    
    @Query("SELECT v FROM VirtualAccount v WHERE " +
           "LOWER(v.vaNumber) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(v.vaName) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(v.viban) LIKE LOWER(CONCAT('%', :query, '%'))")
    List<VirtualAccount> searchAll(@Param("query") String query);
    
    // ========================================================================
    // ADVANCED FILTERING
    // ========================================================================
    
    @Query("SELECT v FROM VirtualAccount v WHERE " +
           "(:status IS NULL OR v.status = :status) AND " +
           "(:currencyCode IS NULL OR v.currencyCode = :currencyCode) AND " +
           "(:corporateId IS NULL OR v.corporateId = :corporateId) AND " +
           "(:programId IS NULL OR v.programId = :programId)")
    Page<VirtualAccount> findWithFilters(
            @Param("status") VaStatus status,
            @Param("currencyCode") String currencyCode,
            @Param("corporateId") UUID corporateId,
            @Param("programId") UUID programId,
            Pageable pageable);
    
    @Query("SELECT v FROM VirtualAccount v WHERE " +
           "(:status IS NULL OR v.status = :status) AND " +
           "(:currencyCode IS NULL OR v.currencyCode = :currencyCode) AND " +
           "(:kycVerified IS NULL OR v.kycVerified = :kycVerified)")
    Page<VirtualAccount> findByFilters(
            @Param("status") VaStatus status,
            @Param("currencyCode") String currencyCode,
            @Param("kycVerified") Boolean kycVerified,
            Pageable pageable);

    // ========================================================================
    // HIERARCHY NODE QUERIES
    // ========================================================================

    /**
     * Find VA by hierarchy node ID.
     * Uses LIMIT 1 to handle cases where duplicates exist.
     */
    @Query("SELECT va FROM VirtualAccount va WHERE va.hierarchyNodeId = :hierarchyNodeId ORDER BY va.createdAt ASC LIMIT 1")
    Optional<VirtualAccount> findByHierarchyNodeId(@Param("hierarchyNodeId") UUID hierarchyNodeId);
    
    Page<VirtualAccount> findByHierarchyNodeId(UUID hierarchyNodeId, Pageable pageable);
    
    List<VirtualAccount> findAllByHierarchyNodeId(UUID hierarchyNodeId);

    @Query("SELECT v FROM VirtualAccount v WHERE v.hierarchyNodeId IS NOT NULL")
    List<VirtualAccount> findWithHierarchy();

    @Query("SELECT v FROM VirtualAccount v WHERE v.hierarchyPath LIKE CONCAT(:pathPrefix, '%')")
    List<VirtualAccount> findByHierarchyPathPrefix(@Param("pathPrefix") String pathPrefix);

    @Query("SELECT v FROM VirtualAccount v WHERE v.hierarchyPath LIKE CONCAT(:pathPrefix, '%')")
    Page<VirtualAccount> findByHierarchyPathPrefix(@Param("pathPrefix") String pathPrefix, Pageable pageable);
    
    @Query("SELECT v FROM VirtualAccount v WHERE v.hierarchyPath LIKE :pathPrefix")
    Page<VirtualAccount> findByHierarchyPathLike(@Param("pathPrefix") String pathPrefix, Pageable pageable);

    @Query("SELECT COALESCE(SUM(v.currentBalance), 0) FROM VirtualAccount v WHERE v.hierarchyPath LIKE CONCAT(:pathPrefix, '%')")
    BigDecimal sumBalanceByHierarchyPath(@Param("pathPrefix") String pathPrefix);

    @Query("SELECT COUNT(v) FROM VirtualAccount v WHERE v.hierarchyPath LIKE CONCAT(:pathPrefix, '%')")
    long countByHierarchyPath(@Param("pathPrefix") String pathPrefix);

    @Modifying
    @Query("UPDATE VirtualAccount v SET v.hierarchyPath = :newPath WHERE v.id = :vaId")
    void updateHierarchyPath(@Param("vaId") UUID vaId, @Param("newPath") String newPath);

    @Modifying
    @Query("UPDATE VirtualAccount v SET v.hierarchyPath = REPLACE(v.hierarchyPath, :oldPath, :newPath) " +
           "WHERE v.hierarchyPath LIKE CONCAT(:oldPath, '%')")
    int updateHierarchyPaths(@Param("oldPath") String oldPath, @Param("newPath") String newPath);

    // ========================================================================
    // PARENT-CHILD VA TREE QUERIES (Self-Referencing)
    // ========================================================================

    List<VirtualAccount> findByParentAccountId(UUID parentAccountId);
    
    List<VirtualAccount> findByParentAccountIdOrderByVaNumber(UUID parentAccountId);
    
    List<VirtualAccount> findByParentAccountIdOrderByVaName(UUID parentAccountId);
    
    List<VirtualAccount> findByParentAccountIdAndStatus(UUID parentAccountId, VaStatus status);
    
    List<VirtualAccount> findByParentAccountIdAndAccountCategory(UUID parentAccountId, AccountCategory accountCategory);

    long countByParentAccountId(UUID parentAccountId);
    
    boolean existsByParentAccountId(UUID parentAccountId);

    @Query("SELECT v FROM VirtualAccount v WHERE v.programId = :programId AND v.parentAccountId IS NULL ORDER BY v.accountCategory, v.currencyCode")
    List<VirtualAccount> findRootVasByProgram(@Param("programId") UUID programId);

    @Query("SELECT v FROM VirtualAccount v WHERE v.parentAccountId = :parentId AND NOT EXISTS (SELECT 1 FROM VirtualAccount c WHERE c.parentAccountId = v.id)")
    List<VirtualAccount> findLeafVasUnderParent(@Param("parentId") UUID parentId);

    @Query("SELECT COALESCE(SUM(v.currentBalance), 0) FROM VirtualAccount v WHERE v.parentAccountId = :parentId")
    BigDecimal sumChildrenBalance(@Param("parentId") UUID parentId);
    
    @Query("SELECT COALESCE(SUM(va.currentBalance), 0) FROM VirtualAccount va WHERE va.parentAccountId = :parentId AND va.status = 'ACTIVE'")
    BigDecimal sumChildBalances(@Param("parentId") UUID parentId);
    
    List<VirtualAccount> findByHierarchyPathVaStartingWith(String pathPrefix);
    
    @Query("SELECT v FROM VirtualAccount v WHERE v.hierarchyPathVa LIKE CONCAT(:pathPrefix, '%')")
    List<VirtualAccount> findByHierarchyPathVaPrefix(@Param("pathPrefix") String pathPrefix);

    // ========================================================================
    // ACCOUNT CATEGORY QUERIES
    // ========================================================================

    List<VirtualAccount> findByAccountCategory(AccountCategory accountCategory);
    
    List<VirtualAccount> findByAccountCategoryAndStatus(AccountCategory accountCategory, VaStatus status);

    // ========================================================================
    // SETTLEMENT/EXCEPTION VA RESOLUTION (NEW v5.1.0 - Auto-Creation Support)
    // ========================================================================

    /**
     * Find by program, currency, special type, and status.
     * Primary lookup for Settlement and Exception VA resolution.
     * Uses LIMIT 1 to handle cases where duplicates exist.
     */
    @Query("SELECT va FROM VirtualAccount va WHERE " +
           "(va.programId = :programId OR (:programId IS NULL AND va.programId IS NULL)) " +
           "AND va.currencyCode = :currencyCode " +
           "AND va.specialType = :specialType " +
           "AND va.status = :status ORDER BY va.createdAt ASC LIMIT 1")
    Optional<VirtualAccount> findByProgramIdAndCurrencyCodeAndSpecialTypeAndStatus(
        @Param("programId") UUID programId,
        @Param("currencyCode") String currencyCode,
        @Param("specialType") VaSpecialType specialType,
        @Param("status") VaStatus status
    );

    /**
     * Find by program, currency, account category, and status.
     * Alternative lookup for Settlement and Exception VAs.
     * Uses LIMIT 1 to handle cases where duplicates exist.
     */
    @Query("SELECT va FROM VirtualAccount va WHERE " +
           "(va.programId = :programId OR (:programId IS NULL AND va.programId IS NULL)) " +
           "AND va.currencyCode = :currencyCode " +
           "AND va.accountCategory = :accountCategory " +
           "AND va.status = :status ORDER BY va.createdAt ASC LIMIT 1")
    Optional<VirtualAccount> findByProgramIdAndCurrencyCodeAndAccountCategoryAndStatus(
        @Param("programId") UUID programId,
        @Param("currencyCode") String currencyCode,
        @Param("accountCategory") AccountCategory accountCategory,
        @Param("status") VaStatus status
    );

    /**
     * Find all Settlement VAs for a program.
     */
    @Query("SELECT va FROM VirtualAccount va WHERE va.programId = :programId " +
           "AND (va.specialType = 'SETTLEMENT' OR va.accountCategory = 'SETTLEMENT') " +
           "AND va.status = 'ACTIVE'")
    List<VirtualAccount> findAllSettlementVasByProgramId(@Param("programId") UUID programId);

    /**
     * Find all Exception VAs for a program.
     */
    @Query("SELECT va FROM VirtualAccount va WHERE va.programId = :programId " +
           "AND (va.specialType = 'EXCEPTION' OR va.accountCategory = 'EXCEPTION') " +
           "AND va.status = 'ACTIVE'")
    List<VirtualAccount> findAllExceptionVasByProgramId(@Param("programId") UUID programId);

    /**
     * Check if Settlement VA exists for program/currency.
     */
    @Query("SELECT COUNT(va) > 0 FROM VirtualAccount va WHERE " +
           "(va.programId = :programId OR (:programId IS NULL AND va.programId IS NULL)) " +
           "AND va.currencyCode = :currencyCode " +
           "AND (va.specialType = 'SETTLEMENT' OR va.accountCategory = 'SETTLEMENT') " +
           "AND va.status = 'ACTIVE'")
    boolean existsSettlementVaByProgramAndCurrency(
        @Param("programId") UUID programId,
        @Param("currencyCode") String currencyCode
    );

    /**
     * Check if Exception VA exists for program/currency.
     */
    @Query("SELECT COUNT(va) > 0 FROM VirtualAccount va WHERE " +
           "(va.programId = :programId OR (:programId IS NULL AND va.programId IS NULL)) " +
           "AND va.currencyCode = :currencyCode " +
           "AND (va.specialType = 'EXCEPTION' OR va.accountCategory = 'EXCEPTION') " +
           "AND va.status = 'ACTIVE'")
    boolean existsExceptionVaByProgramAndCurrency(
        @Param("programId") UUID programId,
        @Param("currencyCode") String currencyCode
    );

    /**
     * Find Settlement VA by corporate (fallback when no program).
     * Uses LIMIT 1 to handle cases where duplicates exist.
     */
    @Query("SELECT va FROM VirtualAccount va WHERE va.corporateId = :corporateId " +
           "AND va.currencyCode = :currencyCode " +
           "AND (va.specialType = 'SETTLEMENT' OR va.accountCategory = 'SETTLEMENT') " +
           "AND va.status = 'ACTIVE' ORDER BY va.createdAt ASC LIMIT 1")
    Optional<VirtualAccount> findSettlementVaByCorporateAndCurrency(
        @Param("corporateId") UUID corporateId,
        @Param("currencyCode") String currencyCode
    );

    /**
     * Find Exception VA by corporate (fallback when no program).
     * Uses LIMIT 1 to handle cases where duplicates exist.
     */
    @Query("SELECT va FROM VirtualAccount va WHERE va.corporateId = :corporateId " +
           "AND va.currencyCode = :currencyCode " +
           "AND (va.specialType = 'EXCEPTION' OR va.accountCategory = 'EXCEPTION') " +
           "AND va.status = 'ACTIVE' ORDER BY va.createdAt ASC LIMIT 1")
    Optional<VirtualAccount> findExceptionVaByCorporateAndCurrency(
        @Param("corporateId") UUID corporateId,
        @Param("currencyCode") String currencyCode
    );

    // ========================================================================
    // OWNING ENTITY QUERIES (Legal Entity Linkage)
    // ========================================================================
    
    List<VirtualAccount> findByOwningEntityId(UUID owningEntityId);
    
    Page<VirtualAccount> findByOwningEntityId(UUID owningEntityId, Pageable pageable);
    
    long countByOwningEntityId(UUID owningEntityId);
    
    boolean existsByOwningEntityId(UUID owningEntityId);
    
    List<VirtualAccount> findByOwningEntityIdAndStatus(UUID owningEntityId, VaStatus status);
    
    long countByOwningEntityIdAndStatus(UUID owningEntityId, VaStatus status);
    
    List<VirtualAccount> findByOwningEntityCode(String owningEntityCode);
    
    List<VirtualAccount> findByOwningEntityIdAndAccountCategory(UUID owningEntityId, AccountCategory accountCategory);
    
    int countByOwningEntityIdAndAccountCategory(UUID owningEntityId, AccountCategory accountCategory);
    
    @Query("SELECT COALESCE(SUM(v.currentBalance), 0) FROM VirtualAccount v WHERE v.owningEntityId = :owningEntityId")
    BigDecimal sumBalanceByOwningEntityId(@Param("owningEntityId") UUID owningEntityId);
    
    @Query("SELECT COALESCE(SUM(v.availableBalance), 0) FROM VirtualAccount v WHERE v.owningEntityId = :owningEntityId")
    BigDecimal sumAvailableBalanceByOwningEntityId(@Param("owningEntityId") UUID owningEntityId);
    
    @Modifying
    @Query("UPDATE VirtualAccount v SET v.owningEntityId = :newEntityId, v.owningEntityCode = :newEntityCode WHERE v.id = :vaId")
    int updateOwningEntity(@Param("vaId") UUID vaId, @Param("newEntityId") UUID newEntityId, @Param("newEntityCode") String newEntityCode);
    
    @Modifying
    @Query("UPDATE VirtualAccount v SET v.owningEntityId = :newEntityId, v.owningEntityCode = :newEntityCode WHERE v.owningEntityId = :oldEntityId")
    int bulkReassignOwningEntity(@Param("oldEntityId") UUID oldEntityId, @Param("newEntityId") UUID newEntityId, @Param("newEntityCode") String newEntityCode);

    // ========================================================================
    // VALID PARENT QUERIES (for VA Creation)
    // ========================================================================

    @Query("SELECT va FROM VirtualAccount va " +
           "WHERE va.owningEntityId = :entityId " +
           "AND va.accountCategory IN ('PHYSICAL_MIRROR', 'CURRENCY_MIRROR', 'ROOT', 'AGGREGATION') " +
           "AND va.status = 'ACTIVE' " +
           "ORDER BY va.accountCategory, va.currencyCode, va.vaNumber")
    List<VirtualAccount> findValidParentAccounts(@Param("entityId") UUID entityId);

    @Query("SELECT va FROM VirtualAccount va " +
           "WHERE va.corporateId = :corporateId " +
           "AND va.accountCategory IN ('PHYSICAL_MIRROR', 'CURRENCY_MIRROR', 'ROOT', 'AGGREGATION') " +
           "AND va.status = 'ACTIVE' " +
           "ORDER BY va.accountCategory, va.currencyCode, va.vaNumber")
    List<VirtualAccount> findValidParentAccountsByCorporate(@Param("corporateId") UUID corporateId);

    // ========================================================================
    // CURRENCY MIRROR VA QUERIES
    // ========================================================================

    @Query("SELECT va FROM VirtualAccount va WHERE va.hierarchyNodeId = :hierarchyNodeId AND va.accountCategory = :accountCategory AND va.currencyCode = :currencyCode ORDER BY va.createdAt ASC LIMIT 1")
    Optional<VirtualAccount> findByHierarchyNodeIdAndAccountCategoryAndCurrencyCode(
            @Param("hierarchyNodeId") UUID hierarchyNodeId,
            @Param("accountCategory") AccountCategory accountCategory,
            @Param("currencyCode") String currencyCode);

    List<VirtualAccount> findByHierarchyNodeIdAndAccountCategory(UUID hierarchyNodeId, AccountCategory accountCategory);

    @Query("SELECT v FROM VirtualAccount v WHERE v.programId = :programId AND v.accountCategory = 'CURRENCY_MIRROR' ORDER BY v.hierarchyLevel, v.currencyCode")
    List<VirtualAccount> findCurrencyMirrorsByProgram(@Param("programId") UUID programId);

    @Query("SELECT v FROM VirtualAccount v WHERE v.programId = :programId AND v.accountCategory = 'CURRENCY_MIRROR' AND v.currencyCode = :currency ORDER BY v.hierarchyLevel")
    List<VirtualAccount> findCurrencyMirrorsByProgramAndCurrency(@Param("programId") UUID programId, @Param("currency") String currency);

    @Query("SELECT v FROM VirtualAccount v WHERE v.programId = :programId AND v.accountCategory = 'CURRENCY_MIRROR' AND v.currencyCode = :currency AND v.parentAccountId IS NULL ORDER BY v.createdAt ASC LIMIT 1")
    Optional<VirtualAccount> findRootCurrencyMirror(@Param("programId") UUID programId, @Param("currency") String currency);

    @Query("SELECT COALESCE(SUM(v.mirrorBalance), 0) FROM VirtualAccount v WHERE v.programId = :programId AND v.accountCategory = 'CURRENCY_MIRROR' AND v.currencyCode = :currency AND v.parentAccountId IS NULL")
    BigDecimal sumRootMirrorBalance(@Param("programId") UUID programId, @Param("currency") String currency);

    @Query("SELECT DISTINCT v.currencyCode FROM VirtualAccount v WHERE v.programId = :programId AND v.accountCategory = 'CURRENCY_MIRROR' ORDER BY v.currencyCode")
    List<String> findDistinctCurrenciesInMirrors(@Param("programId") UUID programId);

    @Query("SELECT COALESCE(SUM(v.mirrorBalance), 0) FROM VirtualAccount v WHERE v.parentAccountId = :parentId AND v.accountCategory = 'CURRENCY_MIRROR'")
    BigDecimal sumChildMirrorBalance(@Param("parentId") UUID parentId);
    
    @Query("SELECT va FROM VirtualAccount va WHERE va.corporateId = :corporateId AND va.accountCategory = 'CURRENCY_MIRROR'")
    List<VirtualAccount> findCurrencyMirrors(@Param("corporateId") UUID corporateId);

    @Query("SELECT va FROM VirtualAccount va WHERE va.corporateId = :corporateId AND va.accountCategory = 'CURRENCY_MIRROR' AND va.currencyCode = :currency ORDER BY va.createdAt ASC LIMIT 1")
    Optional<VirtualAccount> findCurrencyMirrorByCurrency(@Param("corporateId") UUID corporateId, @Param("currency") String currency);

    @Query("SELECT va FROM VirtualAccount va WHERE va.owningEntityId = :entityId AND va.accountCategory = 'CURRENCY_MIRROR'")
    List<VirtualAccount> findCurrencyMirrorsByEntity(@Param("entityId") UUID entityId);
    
    @Query("SELECT v FROM VirtualAccount v WHERE v.corporateId = :corporateId AND v.accountCategory = 'CURRENCY_MIRROR' AND v.status = 'ACTIVE'")
    List<VirtualAccount> findCurrencyMirrorsByCorporate(@Param("corporateId") UUID corporateId);

    @Modifying
    @Query("UPDATE VirtualAccount v SET v.mirrorBalance = :balance, v.balanceInBase = :balanceInBase, v.currentBalance = :balance, v.availableBalance = :balance WHERE v.id = :vaId AND v.accountCategory = 'CURRENCY_MIRROR'")
    int updateMirrorBalance(@Param("vaId") UUID vaId, @Param("balance") BigDecimal balance, @Param("balanceInBase") BigDecimal balanceInBase);

    @Modifying
    @Query("UPDATE VirtualAccount v SET v.mirrorBalance = v.mirrorBalance + :delta, v.currentBalance = v.currentBalance + :delta, v.availableBalance = v.availableBalance + :delta WHERE v.id = :vaId AND v.accountCategory = 'CURRENCY_MIRROR'")
    int incrementMirrorBalance(@Param("vaId") UUID vaId, @Param("delta") BigDecimal delta);

    @Modifying
    @Query("UPDATE VirtualAccount v SET v.fxRate = :rate, v.fxRateAt = :rateAt, v.fxRateSource = :source, v.balanceInBase = v.mirrorBalance * :rate WHERE v.id = :vaId")
    int updateFxRate(@Param("vaId") UUID vaId, @Param("rate") BigDecimal rate, @Param("rateAt") LocalDateTime rateAt, @Param("source") String source);

    // ========================================================================
    // SHADOW ACCOUNT QUERIES
    // ========================================================================

    /**
     * Find VA by linked physical account ID.
     * Uses LIMIT 1 to handle cases where duplicates exist.
     */
    @Query("SELECT va FROM VirtualAccount va WHERE va.linkedPhysicalAccountId = :physicalAccountId ORDER BY va.createdAt ASC LIMIT 1")
    Optional<VirtualAccount> findByLinkedPhysicalAccountId(@Param("physicalAccountId") UUID physicalAccountId);

    /**
     * Find VA by linked physical account ID and account category.
     * Uses LIMIT 1 to handle cases where duplicates exist.
     */
    @Query("SELECT va FROM VirtualAccount va WHERE va.linkedPhysicalAccountId = :linkedPhysicalAccountId AND va.accountCategory = :category ORDER BY va.createdAt ASC LIMIT 1")
    Optional<VirtualAccount> findByLinkedPhysicalAccountIdAndAccountCategory(@Param("linkedPhysicalAccountId") UUID linkedPhysicalAccountId, @Param("category") AccountCategory category);

    @Query("SELECT va FROM VirtualAccount va WHERE va.corporateId = :corporateId AND va.accountCategory = 'PHYSICAL_MIRROR' AND va.currencyCode = :currency")
    List<VirtualAccount> findShadowAccountsByCurrency(@Param("corporateId") UUID corporateId, @Param("currency") String currency);
    
    @Query("SELECT v FROM VirtualAccount v WHERE v.corporateId = :corporateId AND v.accountCategory = 'PHYSICAL_MIRROR' AND v.status = 'ACTIVE'")
    List<VirtualAccount> findShadowAccountsByCorporate(@Param("corporateId") UUID corporateId);
    
    @Query("SELECT COALESCE(SUM(va.bankBalance), 0) FROM VirtualAccount va WHERE va.corporateId = :corporateId AND va.accountCategory = 'PHYSICAL_MIRROR' AND va.status = 'ACTIVE'")
    BigDecimal sumShadowBankBalances(@Param("corporateId") UUID corporateId);

    @Query("SELECT COALESCE(SUM(va.bankBalance), 0) FROM VirtualAccount va WHERE va.corporateId = :corporateId AND va.accountCategory = 'PHYSICAL_MIRROR' AND va.currencyCode = :currency AND va.status = 'ACTIVE'")
    BigDecimal sumShadowBankBalancesByCurrency(@Param("corporateId") UUID corporateId, @Param("currency") String currency);

    // ========================================================================
    // ROOT & SPECIAL ACCOUNT QUERIES
    // ========================================================================

    /**
     * Find the first (oldest) ROOT account for a corporate.
     * Uses LIMIT 1 to handle cases where multiple ROOTs exist (data integrity issue).
     */
    @Query("SELECT va FROM VirtualAccount va WHERE va.corporateId = :corporateId AND va.accountCategory = 'ROOT' ORDER BY va.createdAt ASC LIMIT 1")
    Optional<VirtualAccount> findRootAccount(@Param("corporateId") UUID corporateId);

    // Settlement VA - by corporate (LIMIT 1 to handle duplicates)
    @Query("SELECT va FROM VirtualAccount va WHERE va.corporateId = :corporateId AND va.accountCategory = 'SETTLEMENT' AND va.currencyCode = :currency ORDER BY va.createdAt ASC LIMIT 1")
    Optional<VirtualAccount> findSettlementVa(@Param("corporateId") UUID corporateId, @Param("currency") String currency);

    // Settlement VA - by program (LIMIT 1 to handle duplicates)
    @Query("SELECT v FROM VirtualAccount v WHERE v.programId = :programId AND v.accountCategory = 'SETTLEMENT' AND v.currencyCode = :currency ORDER BY v.createdAt ASC LIMIT 1")
    Optional<VirtualAccount> findSettlementVaByCurrency(@Param("programId") UUID programId, @Param("currency") String currency);
    
    @Query("SELECT v FROM VirtualAccount v WHERE v.hierarchyNodeId = :nodeId AND v.specialType = 'SETTLEMENT' ORDER BY v.createdAt ASC LIMIT 1")
    Optional<VirtualAccount> findSettlementVaByHierarchyNode(@Param("nodeId") UUID nodeId);

    // Exception VA - by corporate (LIMIT 1 to handle duplicates)
    @Query("SELECT va FROM VirtualAccount va WHERE va.corporateId = :corporateId AND va.accountCategory = 'EXCEPTION' AND va.currencyCode = :currency ORDER BY va.createdAt ASC LIMIT 1")
    Optional<VirtualAccount> findExceptionVa(@Param("corporateId") UUID corporateId, @Param("currency") String currency);

    // Exception VA - by program (LIMIT 1 to handle duplicates)
    @Query("SELECT v FROM VirtualAccount v WHERE v.programId = :programId AND v.accountCategory = 'EXCEPTION' AND v.currencyCode = :currency ORDER BY v.createdAt ASC LIMIT 1")
    Optional<VirtualAccount> findExceptionVaByCurrency(@Param("programId") UUID programId, @Param("currency") String currency);
    
    @Query("SELECT COUNT(v) > 0 FROM VirtualAccount v WHERE v.programId = :programId AND v.accountCategory = 'EXCEPTION' AND v.currencyCode = :currency")
    boolean existsExceptionVaByCurrency(@Param("programId") UUID programId, @Param("currency") String currency);
    
    @Query("SELECT COUNT(v) > 0 FROM VirtualAccount v WHERE v.programId = :programId AND v.currencyCode = :currency AND v.specialType = 'EXCEPTION'")
    boolean existsExceptionVa(@Param("programId") UUID programId, @Param("currency") String currency);

    @Query("SELECT v FROM VirtualAccount v WHERE v.programId = :programId AND v.currencyCode = :currency AND v.specialType = 'SETTLEMENT' " +
           "AND v.hierarchyNodeId IN (SELECT n.id FROM HierarchyNode n WHERE n.parentId = :parentId) ORDER BY v.createdAt ASC LIMIT 1")
    Optional<VirtualAccount> findSiblingSettlementVa(@Param("programId") UUID programId, @Param("currency") String currency, @Param("parentId") UUID parentId);

    // ========================================================================
    // PRIMARY VIBAN QUERIES
    // ========================================================================

    /**
     * Find VA by primary VIBAN ID.
     * Uses LIMIT 1 to handle cases where duplicates exist.
     */
    @Query("SELECT va FROM VirtualAccount va WHERE va.primaryVibanId = :primaryVibanId ORDER BY va.createdAt ASC LIMIT 1")
    Optional<VirtualAccount> findByPrimaryVibanId(@Param("primaryVibanId") UUID primaryVibanId);

    @Query("SELECT v FROM VirtualAccount v WHERE v.primaryVibanId IS NULL AND v.viban IS NULL AND v.status = 'ACTIVE'")
    List<VirtualAccount> findWithoutPrimaryViban();

    @Modifying
    @Query("UPDATE VirtualAccount v SET v.primaryVibanId = :vibanId, v.viban = :vibanString WHERE v.id = :vaId")
    void updatePrimaryViban(@Param("vaId") UUID vaId, @Param("vibanId") UUID vibanId, @Param("vibanString") String vibanString);

    // ========================================================================
    // COLLECTION CHANNEL QUERIES
    // ========================================================================

    List<VirtualAccount> findByCollectionChannel(CollectionChannel collectionChannel);

    List<VirtualAccount> findByCollectionChannelAndStatus(CollectionChannel collectionChannel, VaStatus status);

    List<VirtualAccount> findByProgramIdAndCollectionChannel(UUID programId, CollectionChannel collectionChannel);

    // ========================================================================
    // VALUE TYPE QUERIES
    // ========================================================================

    List<VirtualAccount> findByValueType(ValueType valueType);

    @Query("SELECT v FROM VirtualAccount v WHERE v.valueType = 'POINTS' AND v.status = 'ACTIVE'")
    List<VirtualAccount> findActivePointsWallets();

    @Query("SELECT COALESCE(SUM(v.pointsBalance), 0) FROM VirtualAccount v WHERE v.valueType = 'POINTS'")
    BigDecimal sumPointsBalance();

    // ========================================================================
    // WALLET TYPE QUERIES
    // ========================================================================

    List<VirtualAccount> findByWalletType(String walletType);
    
    Page<VirtualAccount> findByWalletType(String walletType, Pageable pageable);

    List<VirtualAccount> findByWalletTypeAndStatus(String walletType, VaStatus status);

    long countByWalletType(String walletType);

    // ========================================================================
    // EXPIRY QUERIES
    // ========================================================================

    @Query("SELECT v FROM VirtualAccount v WHERE v.balanceExpiryDate < :date AND v.status = 'ACTIVE'")
    List<VirtualAccount> findWithExpiredBalance(@Param("date") LocalDate date);

    @Query("SELECT v FROM VirtualAccount v WHERE v.balanceExpiryDate BETWEEN :startDate AND :endDate AND v.status = 'ACTIVE'")
    List<VirtualAccount> findExpiringSoon(@Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

    @Query("SELECT v FROM VirtualAccount v WHERE v.walletExpiryDate < :date AND v.status = 'ACTIVE'")
    List<VirtualAccount> findExpiredWallets(@Param("date") LocalDate date);

    @Query("SELECT v FROM VirtualAccount v WHERE v.lastActivityDate < :date AND v.status = 'ACTIVE'")
    List<VirtualAccount> findInactiveWallets(@Param("date") LocalDate date);

    // ========================================================================
    // LIMIT RESET QUERIES
    // ========================================================================

    @Query("SELECT v FROM VirtualAccount v WHERE v.lastLimitResetDate < :date AND v.dailyUsed > 0")
    List<VirtualAccount> findNeedingDailyReset(@Param("date") LocalDate date);

    @Modifying
    @Query("UPDATE VirtualAccount v SET v.dailyUsed = 0, v.dailyTopupUsed = 0, v.lastLimitResetDate = :date WHERE v.lastLimitResetDate < :date")
    int resetDailyLimits(@Param("date") LocalDate date);

    @Modifying
    @Query("UPDATE VirtualAccount v SET v.weeklyUsed = 0")
    int resetWeeklyLimits();

    @Modifying
    @Query("UPDATE VirtualAccount v SET v.monthlyUsed = 0, v.monthlyTopupUsed = 0")
    int resetMonthlyLimits();

    @Modifying
    @Query("UPDATE VirtualAccount v SET v.annualUsed = 0")
    int resetAnnualLimits();

    // ========================================================================
    // CARD PROGRAM QUERIES
    // ========================================================================

    List<VirtualAccount> findByCardProgramType(VirtualAccount.CardProgramType cardProgramType);

    List<VirtualAccount> findByBudgetOwnerId(UUID budgetOwnerId);

    List<VirtualAccount> findByCostCenter(String costCenter);

    List<VirtualAccount> findByDepartment(String department);

    // ========================================================================
    // LOYALTY QUERIES
    // ========================================================================

    List<VirtualAccount> findByLoyaltyTier(VirtualAccount.LoyaltyTier loyaltyTier);

    List<VirtualAccount> findByLoyaltyProgramId(UUID loyaltyProgramId);

    @Query("SELECT v.loyaltyTier, COALESCE(SUM(v.pointsBalance), 0) FROM VirtualAccount v " +
           "WHERE v.loyaltyTier IS NOT NULL GROUP BY v.loyaltyTier")
    List<Object[]> sumPointsByLoyaltyTier();

    // ========================================================================
    // BALANCE UPDATE QUERIES
    // ========================================================================

    @Modifying
    @Query("UPDATE VirtualAccount v SET v.currentBalance = :currentBalance, v.availableBalance = :availableBalance WHERE v.id = :vaId")
    void updateBalance(@Param("vaId") UUID vaId, @Param("currentBalance") BigDecimal currentBalance, @Param("availableBalance") BigDecimal availableBalance);

    @Modifying
    @Query("UPDATE VirtualAccount v SET v.currentBalance = v.currentBalance + :delta, v.availableBalance = v.availableBalance + :delta WHERE v.id = :vaId")
    void incrementBalance(@Param("vaId") UUID vaId, @Param("delta") BigDecimal delta);

    @Modifying
    @Query("UPDATE VirtualAccount v SET v.heldBalance = v.heldBalance + :amount, v.availableBalance = v.availableBalance - :amount WHERE v.id = :vaId")
    void holdBalance(@Param("vaId") UUID vaId, @Param("amount") BigDecimal amount);

    @Modifying
    @Query("UPDATE VirtualAccount v SET v.heldBalance = v.heldBalance - :amount, v.availableBalance = v.availableBalance + :amount WHERE v.id = :vaId")
    void releaseHold(@Param("vaId") UUID vaId, @Param("amount") BigDecimal amount);
    
    @Query("SELECT v.currencyCode, COALESCE(SUM(v.currentBalance), 0) FROM VirtualAccount v WHERE v.parentAccountId = :parentId GROUP BY v.currencyCode")
    List<Object[]> sumBalanceByParentGroupedByCurrency(@Param("parentId") UUID parentId);

    // ========================================================================
    // HOLDER PARTY QUERIES
    // ========================================================================
    
    List<VirtualAccount> findByHolderPartyId(UUID holderPartyId);
    
    Page<VirtualAccount> findByHolderPartyId(UUID holderPartyId, Pageable pageable);
    
    // ========================================================================
    // LINKED CARD QUERIES
    // ========================================================================

    /**
     * Find VA by linked card ID.
     * Uses LIMIT 1 to handle cases where duplicates exist.
     */
    @Query("SELECT va FROM VirtualAccount va WHERE va.linkedCardId = :linkedCardId ORDER BY va.createdAt ASC LIMIT 1")
    Optional<VirtualAccount> findByLinkedCardId(@Param("linkedCardId") UUID linkedCardId);
    
    boolean existsByLinkedCardId(UUID linkedCardId);

    // ========================================================================
    // HIERARCHY OPERATIONS - ROOT QUERIES
    // ========================================================================

    /**
     * Find all ROOT accounts for a corporate.
     * Note: There should be exactly one ROOT per corporate, but this returns a List
     * to handle data integrity issues gracefully.
     */
    @Query("SELECT va FROM VirtualAccount va WHERE va.corporateId = :corporateId AND va.accountCategory = 'ROOT' ORDER BY va.createdAt ASC")
    List<VirtualAccount> findRootsByCorporateId(@Param("corporateId") UUID corporateId);

    /**
     * Find the first (oldest) ROOT account for a corporate.
     * Use this when you expect exactly one ROOT but need to handle duplicates gracefully.
     */
    @Query("SELECT va FROM VirtualAccount va WHERE va.corporateId = :corporateId AND va.accountCategory = 'ROOT' ORDER BY va.createdAt ASC LIMIT 1")
    Optional<VirtualAccount> findRootByCorporateId(@Param("corporateId") UUID corporateId);

    @Query("SELECT CASE WHEN COUNT(va) > 0 THEN true ELSE false END FROM VirtualAccount va " +
           "WHERE va.corporateId = :corporateId AND va.accountCategory = 'ROOT'")
    boolean existsRootByCorporateId(@Param("corporateId") UUID corporateId);

    /**
     * Count ROOT accounts for a corporate.
     * Expected to return 1. If > 1, indicates data integrity issue.
     */
    @Query("SELECT COUNT(va) FROM VirtualAccount va WHERE va.corporateId = :corporateId AND va.accountCategory = 'ROOT'")
    long countRootsByCorporateId(@Param("corporateId") UUID corporateId);

    // ========================================================================
    // HIERARCHY OPERATIONS - CURRENCY MIRROR BY PARENT
    // ========================================================================

    @Query("SELECT va FROM VirtualAccount va " +
           "WHERE va.parentAccountId = :parentId " +
           "AND va.currencyCode = :currency " +
           "AND va.accountCategory = 'CURRENCY_MIRROR' ORDER BY va.createdAt ASC LIMIT 1")
    Optional<VirtualAccount> findCurrencyMirrorByParentAndCurrency(
        @Param("parentId") UUID parentId,
        @Param("currency") String currency);

    @Query("SELECT va FROM VirtualAccount va " +
           "WHERE va.parentAccountId = :parentId " +
           "AND va.accountCategory = 'CURRENCY_MIRROR'")
    List<VirtualAccount> findCurrencyMirrorsByParent(@Param("parentId") UUID parentId);

    @Query("SELECT CASE WHEN COUNT(va) > 0 THEN true ELSE false END " +
           "FROM VirtualAccount va " +
           "WHERE va.parentAccountId = :parentId " +
           "AND va.currencyCode = :currency " +
           "AND va.accountCategory = 'CURRENCY_MIRROR'")
    boolean existsCurrencyMirror(
        @Param("parentId") UUID parentId,
        @Param("currency") String currency);

    // ========================================================================
    // HIERARCHY OPERATIONS - TRANSACTION-TYPE CHILDREN
    // ========================================================================

    @Query("SELECT COUNT(va) FROM VirtualAccount va " +
           "WHERE va.parentAccountId = :parentId " +
           "AND va.currencyCode = :currency " +
           "AND va.accountCategory IN ('TRANSACTION', 'COLLECTION', 'DISBURSEMENT', 'PHYSICAL_MIRROR', 'INTERCOMPANY')")
    long countTransactionTypeChildrenByCurrency(
        @Param("parentId") UUID parentId,
        @Param("currency") String currency);

    @Query("SELECT va FROM VirtualAccount va " +
           "WHERE va.parentAccountId = :parentId " +
           "AND va.accountCategory IN ('TRANSACTION', 'COLLECTION', 'DISBURSEMENT', 'PHYSICAL_MIRROR', 'INTERCOMPANY')")
    List<VirtualAccount> findTransactionTypeChildren(@Param("parentId") UUID parentId);

    @Query("SELECT va FROM VirtualAccount va " +
           "WHERE va.parentAccountId = :parentId " +
           "AND va.currencyCode = :currency " +
           "AND va.accountCategory IN ('TRANSACTION', 'COLLECTION', 'DISBURSEMENT', 'PHYSICAL_MIRROR', 'INTERCOMPANY')")
    List<VirtualAccount> findTransactionTypeChildrenByCurrency(
        @Param("parentId") UUID parentId,
        @Param("currency") String currency);

    @Query("SELECT COALESCE(SUM(va.currentBalance), 0) FROM VirtualAccount va " +
           "WHERE va.parentAccountId = :parentId " +
           "AND va.currencyCode = :currency " +
           "AND va.accountCategory IN ('TRANSACTION', 'COLLECTION', 'DISBURSEMENT', 'PHYSICAL_MIRROR', 'INTERCOMPANY')")
    BigDecimal sumTransactionBalancesByCurrency(
        @Param("parentId") UUID parentId,
        @Param("currency") String currency);

    // ========================================================================
    // HIERARCHY OPERATIONS - DISTINCT CURRENCIES BY PARENT
    // ========================================================================

    @Query("SELECT DISTINCT va.currencyCode FROM VirtualAccount va " +
           "WHERE va.parentAccountId = :parentId " +
           "AND va.currencyCode IS NOT NULL " +
           "AND va.accountCategory IN ('TRANSACTION', 'COLLECTION', 'DISBURSEMENT', 'PHYSICAL_MIRROR', 'INTERCOMPANY')")
    List<String> findDistinctCurrenciesByParent(@Param("parentId") UUID parentId);

    // ========================================================================
    // HIERARCHY OPERATIONS - AGGREGATION QUERIES
    // ========================================================================

    @Query("SELECT va FROM VirtualAccount va " +
           "WHERE va.corporateId = :corporateId " +
           "AND va.accountCategory = 'AGGREGATION'")
    List<VirtualAccount> findAggregationsByCorporate(@Param("corporateId") UUID corporateId);

    @Query("SELECT va FROM VirtualAccount va " +
           "WHERE va.parentAccountId = :parentId " +
           "AND va.accountCategory = 'AGGREGATION'")
    List<VirtualAccount> findAggregationsByParent(@Param("parentId") UUID parentId);

    // ========================================================================
    // HIERARCHY OPERATIONS - SETTLEMENT VA BY PARENT
    // ========================================================================

    @Query("SELECT va FROM VirtualAccount va " +
           "WHERE va.parentAccountId = :parentId " +
           "AND va.currencyCode = :currency " +
           "AND va.accountCategory = 'SETTLEMENT' ORDER BY va.createdAt ASC LIMIT 1")
    Optional<VirtualAccount> findSettlementVaByParentAndCurrency(
        @Param("parentId") UUID parentId,
        @Param("currency") String currency);

    // ========================================================================
    // HIERARCHY OPERATIONS - SUM ALL CURRENCY MIRROR BALANCES
    // ========================================================================

    @Query("SELECT COALESCE(SUM(va.mirrorBalance), 0) FROM VirtualAccount va " +
           "WHERE va.parentAccountId = :parentId " +
           "AND va.accountCategory = 'CURRENCY_MIRROR'")
    BigDecimal sumAllCurrencyMirrorBalances(@Param("parentId") UUID parentId);

    // ========================================================================
    // INTEREST CONFIG QUERIES
    // ========================================================================

    List<VirtualAccount> findByExternalInterestConfigId(UUID externalInterestConfigId);

    List<VirtualAccount> findByInternalInterestConfigId(UUID internalInterestConfigId);

    long countByExternalInterestConfigId(UUID externalInterestConfigId);

    long countByInternalInterestConfigId(UUID internalInterestConfigId);

    @Query("SELECT v FROM VirtualAccount v " +
           "WHERE v.corporateId = :corporateId " +
           "AND v.currencyCode = :currency " +
           "AND v.externalInterestConfigId IS NULL " +
           "AND v.accountCategory NOT IN ('ROOT', 'AGGREGATION', 'CURRENCY_MIRROR', 'PHYSICAL_MIRROR', 'EXTERNAL_MIRROR')")
    List<VirtualAccount> findVasWithoutExternalConfig(
        @Param("corporateId") UUID corporateId, 
        @Param("currency") String currency);

    @Query("SELECT v FROM VirtualAccount v " +
           "WHERE v.corporateId = :corporateId " +
           "AND v.currencyCode = :currency " +
           "AND v.internalInterestConfigId IS NULL " +
           "AND v.accountCategory NOT IN ('ROOT', 'AGGREGATION', 'CURRENCY_MIRROR', 'PHYSICAL_MIRROR', 'EXTERNAL_MIRROR')")
    List<VirtualAccount> findVasWithoutInternalConfig(
        @Param("corporateId") UUID corporateId, 
        @Param("currency") String currency);

    @Modifying
    @Query("UPDATE VirtualAccount v SET v.externalInterestConfigId = :configId " +
           "WHERE v.corporateId = :corporateId " +
           "AND v.currencyCode = :currency " +
           "AND v.externalInterestConfigId IS NULL " +
           "AND v.accountCategory NOT IN ('ROOT', 'AGGREGATION', 'CURRENCY_MIRROR', 'PHYSICAL_MIRROR', 'EXTERNAL_MIRROR')")
    int bulkAttachExternalConfig(
        @Param("corporateId") UUID corporateId, 
        @Param("currency") String currency, 
        @Param("configId") UUID configId);

    @Modifying
    @Query("UPDATE VirtualAccount v SET v.internalInterestConfigId = :configId " +
           "WHERE v.corporateId = :corporateId " +
           "AND v.currencyCode = :currency " +
           "AND v.internalInterestConfigId IS NULL " +
           "AND v.accountCategory NOT IN ('ROOT', 'AGGREGATION', 'CURRENCY_MIRROR', 'PHYSICAL_MIRROR', 'EXTERNAL_MIRROR')")
    int bulkAttachInternalConfig(
        @Param("corporateId") UUID corporateId, 
        @Param("currency") String currency, 
        @Param("configId") UUID configId);

    @Modifying
    @Query("UPDATE VirtualAccount v SET " +
           "v.effectiveCreditRate = :creditRate, " +
           "v.effectiveDebitRate = :debitRate " +
           "WHERE v.internalInterestConfigId = :configId")
    int bulkSyncEffectiveRatesFromConfig(
        @Param("configId") UUID configId, 
        @Param("creditRate") BigDecimal creditRate, 
        @Param("debitRate") BigDecimal debitRate);

    @Query("SELECT v FROM VirtualAccount v " +
           "WHERE v.owningEntityId = :entityId " +
           "AND v.currencyCode = :currency " +
           "AND v.accountCategory IN ('TRANSACTION', 'COLLECTION', 'DISBURSEMENT', 'INTERCOMPANY')")
    List<VirtualAccount> findOperationalVasByEntityAndCurrency(
        @Param("entityId") UUID entityId, 
        @Param("currency") String currency);

    // ========================================================================
    // IHB VA RESOLUTION METHODS
    // ========================================================================

    List<VirtualAccount> findByOwningEntityIdAndCurrencyCodeAndAccountCategory(
        UUID owningEntityId,
        String currencyCode,
        VirtualAccount.AccountCategory accountCategory
    );

    /**
     * Find IHB current account by owning entity, category, and currency.
     * Returns Optional for single-account lookup.
     */
    @Query("SELECT v FROM VirtualAccount v WHERE v.owningEntityId = :owningEntityId " +
           "AND v.accountCategory = :category AND v.currencyCode = :currency " +
           "AND v.status = 'ACTIVE' ORDER BY v.createdAt ASC")
    Optional<VirtualAccount> findByOwningEntityIdAndAccountCategoryAndCurrencyCode(
        @Param("owningEntityId") UUID owningEntityId,
        @Param("category") VirtualAccount.AccountCategory category,
        @Param("currency") String currency
    );

    /**
     * Count VAs by account category.
     */
    long countByAccountCategory(VirtualAccount.AccountCategory accountCategory);

    /**
     * Find all IHB current accounts (INTERCOMPANY category).
     */
    @Query("SELECT v FROM VirtualAccount v WHERE v.accountCategory = 'INTERCOMPANY' " +
           "AND v.status = 'ACTIVE' ORDER BY v.vaNumber")
    List<VirtualAccount> findAllIhbCurrentAccounts();

    /**
     * Find IHB current accounts by corporate.
     */
    @Query("SELECT v FROM VirtualAccount v WHERE v.corporateId = :corporateId " +
           "AND v.accountCategory = 'INTERCOMPANY' AND v.status = 'ACTIVE'")
    List<VirtualAccount> findIhbCurrentAccountsByCorporate(@Param("corporateId") UUID corporateId);

    /**
     * Find IHB current accounts needing interest calculation.
     * Accounts where lastInterestCalcDate is null or before today.
     */
    @Query("SELECT v FROM VirtualAccount v WHERE v.accountCategory = 'INTERCOMPANY' " +
           "AND v.status = 'ACTIVE' " +
           "AND (v.lastInterestCalcDate IS NULL OR v.lastInterestCalcDate < :today)")
    List<VirtualAccount> findIhbAccountsNeedingInterestCalc(@Param("today") LocalDate today);

    /**
     * Find IHB current accounts needing interest posting.
     * Accounts where lastInterestPostingDate is before the posting cutoff date.
     */
    @Query("SELECT v FROM VirtualAccount v WHERE v.accountCategory = 'INTERCOMPANY' " +
           "AND v.status = 'ACTIVE' " +
           "AND (v.lastInterestPostingDate IS NULL OR v.lastInterestPostingDate < :cutoffDate) " +
           "AND (v.accruedCreditInterest <> 0 OR v.accruedDebitInterest <> 0)")
    List<VirtualAccount> findIhbAccountsNeedingInterestPosting(@Param("cutoffDate") LocalDate cutoffDate);

    List<VirtualAccount> findByOwningEntityIdAndCurrencyCode(UUID owningEntityId, String currencyCode);

    @Query("SELECT v FROM VirtualAccount v WHERE v.owningEntityId = :entityId " +
           "AND v.accountCategory = 'TRANSACTION' AND v.status = 'ACTIVE'")
    List<VirtualAccount> findOperationalVasByEntity(@Param("entityId") UUID entityId);

    @Query("SELECT v FROM VirtualAccount v WHERE v.owningEntityId = :entityId " +
           "AND v.currencyCode = :currency AND v.accountCategory = 'TRANSACTION' " +
           "AND v.status = 'ACTIVE' ORDER BY v.createdAt ASC")
    List<VirtualAccount> findByEntityAndCurrency(
        @Param("entityId") UUID entityId, 
        @Param("currency") String currency
    );

    @Query("SELECT v FROM VirtualAccount v WHERE v.owningEntityId = :entityId AND v.status = 'ACTIVE'")
    List<VirtualAccount> findActiveByOwningEntityId(@Param("entityId") UUID entityId);

    // ========================================================================
    // IHB PARTICIPANT QUERIES (NEW - ihbParticipant flag based)
    // ========================================================================

    /**
     * Find IHB participant account by owning entity and currency.
     * Uses the ihbParticipant flag instead of INTERCOMPANY category.
     */
    @Query("SELECT v FROM VirtualAccount v WHERE v.owningEntityId = :owningEntityId " +
           "AND v.ihbParticipant = true AND v.currencyCode = :currency " +
           "AND v.status = 'ACTIVE' ORDER BY v.createdAt ASC")
    Optional<VirtualAccount> findByOwningEntityIdAndIhbParticipantTrueAndCurrencyCode(
        @Param("owningEntityId") UUID owningEntityId,
        @Param("currency") String currency
    );

    /**
     * Count IHB Current Accounts for an entity in a specific currency.
     * Used to generate unique names for multiple IHB accounts.
     */
    @Query("SELECT COUNT(v) FROM VirtualAccount v WHERE v.owningEntityId = :owningEntityId " +
           "AND v.ihbParticipant = true AND v.currencyCode = :currency " +
           "AND v.status = 'ACTIVE'")
    long countByOwningEntityIdAndIhbParticipantTrueAndCurrencyCode(
        @Param("owningEntityId") UUID owningEntityId,
        @Param("currency") String currency
    );

    /**
     * Find all IHB participant accounts (ihbParticipant = true).
     */
    @Query("SELECT v FROM VirtualAccount v WHERE v.ihbParticipant = true " +
           "AND v.status = 'ACTIVE' ORDER BY v.vaNumber")
    List<VirtualAccount> findAllIhbParticipants();

    /**
     * Find IHB participant accounts by corporate.
     */
    @Query("SELECT v FROM VirtualAccount v WHERE v.corporateId = :corporateId " +
           "AND v.ihbParticipant = true AND v.status = 'ACTIVE'")
    List<VirtualAccount> findIhbParticipantsByCorporate(@Param("corporateId") UUID corporateId);

    /**
     * Find IHB participant accounts by owning entity.
     */
    @Query("SELECT v FROM VirtualAccount v WHERE v.owningEntityId = :entityId " +
           "AND v.ihbParticipant = true AND v.status = 'ACTIVE'")
    List<VirtualAccount> findIhbParticipantsByEntity(@Param("entityId") UUID entityId);

    /**
     * Find IHB participant accounts needing interest calculation.
     * Uses ihbParticipant flag instead of INTERCOMPANY category.
     */
    @Query("SELECT v FROM VirtualAccount v WHERE v.ihbParticipant = true " +
           "AND v.status = 'ACTIVE' " +
           "AND (v.lastInterestCalcDate IS NULL OR v.lastInterestCalcDate < :today)")
    List<VirtualAccount> findIhbParticipantsNeedingInterestCalc(@Param("today") LocalDate today);

    /**
     * Find IHB participant accounts needing interest posting.
     * Uses ihbParticipant flag instead of INTERCOMPANY category.
     */
    @Query("SELECT v FROM VirtualAccount v WHERE v.ihbParticipant = true " +
           "AND v.status = 'ACTIVE' " +
           "AND (v.lastInterestPostingDate IS NULL OR v.lastInterestPostingDate < :cutoffDate) " +
           "AND (v.accruedCreditInterest <> 0 OR v.accruedDebitInterest <> 0)")
    List<VirtualAccount> findIhbParticipantsNeedingInterestPosting(@Param("cutoffDate") LocalDate cutoffDate);

    /**
     * Count IHB participant accounts.
     */
    @Query("SELECT COUNT(v) FROM VirtualAccount v WHERE v.ihbParticipant = true AND v.status = 'ACTIVE'")
    long countIhbParticipants();

    /**
     * Count IHB participant accounts by corporate.
     */
    @Query("SELECT COUNT(v) FROM VirtualAccount v WHERE v.corporateId = :corporateId " +
           "AND v.ihbParticipant = true AND v.status = 'ACTIVE'")
    long countIhbParticipantsByCorporate(@Param("corporateId") UUID corporateId);

    /**
     * Sum balances of all IHB participant accounts by corporate.
     */
    @Query("SELECT COALESCE(SUM(v.currentBalance), 0) FROM VirtualAccount v " +
           "WHERE v.corporateId = :corporateId AND v.ihbParticipant = true AND v.status = 'ACTIVE'")
    BigDecimal sumIhbParticipantBalancesByCorporate(@Param("corporateId") UUID corporateId);

    /**
     * Sum accrued credit interest for all IHB participants by corporate.
     */
    @Query("SELECT COALESCE(SUM(v.accruedCreditInterest), 0) FROM VirtualAccount v " +
           "WHERE v.corporateId = :corporateId AND v.ihbParticipant = true AND v.status = 'ACTIVE'")
    BigDecimal sumIhbAccruedCreditInterestByCorporate(@Param("corporateId") UUID corporateId);

    /**
     * Sum accrued debit interest for all IHB participants by corporate.
     */
    @Query("SELECT COALESCE(SUM(v.accruedDebitInterest), 0) FROM VirtualAccount v " +
           "WHERE v.corporateId = :corporateId AND v.ihbParticipant = true AND v.status = 'ACTIVE'")
    BigDecimal sumIhbAccruedDebitInterestByCorporate(@Param("corporateId") UUID corporateId);

    // ========================================================================
    // TREASURY HIERARCHY QUERIES (VA-based aggregation)
    // ========================================================================

    /**
     * Find IHB participant accounts by parent account ID.
     * Used for treasury-level aggregation where IHB Current Accounts
     * have parentAccountId pointing to Treasury's Settlement VA.
     */
    @Query("SELECT v FROM VirtualAccount v WHERE v.parentAccountId = :parentAccountId " +
           "AND v.ihbParticipant = true AND v.status = 'ACTIVE'")
    List<VirtualAccount> findIhbParticipantsByParentAccount(@Param("parentAccountId") UUID parentAccountId);

    /**
     * Sum balances of IHB participants under a parent account (Treasury's Settlement VA).
     */
    @Query("SELECT COALESCE(SUM(v.currentBalance), 0) FROM VirtualAccount v " +
           "WHERE v.parentAccountId = :parentAccountId AND v.ihbParticipant = true AND v.status = 'ACTIVE'")
    BigDecimal sumIhbParticipantBalancesByParent(@Param("parentAccountId") UUID parentAccountId);

    /**
     * Count IHB participants under a parent account.
     */
    @Query("SELECT COUNT(v) FROM VirtualAccount v WHERE v.parentAccountId = :parentAccountId " +
           "AND v.ihbParticipant = true AND v.status = 'ACTIVE'")
    long countIhbParticipantsByParent(@Param("parentAccountId") UUID parentAccountId);

    /**
     * Find all child accounts under a parent (for multi-level treasury aggregation).
     * Includes both IHB participants and aggregation accounts.
     */
    @Query("SELECT v FROM VirtualAccount v WHERE v.parentAccountId = :parentAccountId " +
           "AND v.status = 'ACTIVE' ORDER BY v.accountCategory, v.vaNumber")
    List<VirtualAccount> findChildrenByParentAccount(@Param("parentAccountId") UUID parentAccountId);

    /**
     * Sum all child balances under a parent account.
     */
    @Query("SELECT COALESCE(SUM(v.currentBalance), 0) FROM VirtualAccount v " +
           "WHERE v.parentAccountId = :parentAccountId AND v.status = 'ACTIVE'")
    BigDecimal sumChildBalancesByParent(@Param("parentAccountId") UUID parentAccountId);
}