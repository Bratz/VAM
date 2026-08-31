package com.bank.vam.repository.treasury;

import com.bank.vam.entity.treasury.SweepRuleSource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Repository for SweepRuleSource entities.
 *
 * Provides methods for managing sweep rule source accounts,
 * including cleanup of orphaned sources.
 */
@Repository
public interface SweepRuleSourceRepository extends JpaRepository<SweepRuleSource, UUID> {

    /**
     * Find all sources for a specific rule.
     */
    List<SweepRuleSource> findByRuleId(UUID ruleId);

    /**
     * Find sources by account ID.
     */
    List<SweepRuleSource> findByAccountId(UUID accountId);

    /**
     * Find orphaned sources (sources referencing non-existent VAs).
     */
    @Query(value = """
        SELECT srs.* FROM sweep_rule_sources srs
        LEFT JOIN virtual_accounts va ON srs.account_id = va.id
        WHERE va.id IS NULL
        """, nativeQuery = true)
    List<SweepRuleSource> findOrphanedSources();

    /**
     * Delete orphaned sources (sources referencing non-existent VAs).
     * Returns the count of deleted records.
     */
    @Modifying
    @Query(value = """
        DELETE FROM sweep_rule_sources
        WHERE account_id NOT IN (SELECT id FROM virtual_accounts)
        """, nativeQuery = true)
    int deleteOrphanedSources();

    /**
     * Delete source by account ID.
     */
    @Modifying
    void deleteByAccountId(UUID accountId);

    /**
     * Delete a specific source.
     */
    @Modifying
    @Query("DELETE FROM SweepRuleSource s WHERE s.id = :sourceId")
    void deleteSourceById(@Param("sourceId") UUID sourceId);

    /**
     * Check if a VA is referenced by any sweep rule source.
     */
    boolean existsByAccountId(UUID accountId);
}
