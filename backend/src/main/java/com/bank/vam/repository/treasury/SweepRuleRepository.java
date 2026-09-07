package com.bank.vam.repository.treasury;

import com.bank.vam.entity.treasury.SweepRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SweepRuleRepository extends JpaRepository<SweepRule, UUID> {
    
    Optional<SweepRule> findByRuleReference(String ruleReference);

    boolean existsByRuleReference(String ruleReference);

    /** Corporate-scoped listing — used by get_sweep_status so a scoped MCP caller
     *  only ever sees their own corporate's rules. */
    List<SweepRule> findByCorporateId(UUID corporateId);

    @Query("SELECT r FROM SweepRule r LEFT JOIN FETCH r.sourceAccounts WHERE r.id = :id")
    Optional<SweepRule> findByIdWithSources(@Param("id") UUID id);
    
    @Query("SELECT r FROM SweepRule r LEFT JOIN FETCH r.sourceAccounts WHERE r.status = 'ACTIVE'")
    List<SweepRule> findAllActiveWithSources();

    /**
     * Same eager-fetch shape as {@link #findAllActiveWithSources()} but for
     * an explicit id list — used by {@code runSweeps}/{@code runDeficitFunding}
     * when the caller targets specific rules. Needed so the async sweep path
     * (running on a plain executor thread, no Open-Session-In-View) can
     * iterate {@code sourceAccounts} without a LazyInitializationException.
     */
    @Query("SELECT r FROM SweepRule r LEFT JOIN FETCH r.sourceAccounts WHERE r.id IN :ids")
    List<SweepRule> findAllByIdWithSources(@Param("ids") List<UUID> ids);
    
    List<SweepRule> findByStatus(SweepRule.SweepStatus status);
    
    @Query("SELECT COUNT(r) FROM SweepRule r WHERE r.status = :status")
    long countByStatus(@Param("status") String status);
    
    @Query("SELECT COUNT(r) FROM SweepRule r WHERE r.status = 'ACTIVE'")
    long countActive();
}