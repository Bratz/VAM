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

    @Query("SELECT r FROM SweepRule r LEFT JOIN FETCH r.sourceAccounts WHERE r.id = :id")
    Optional<SweepRule> findByIdWithSources(@Param("id") UUID id);
    
    @Query("SELECT r FROM SweepRule r LEFT JOIN FETCH r.sourceAccounts WHERE r.status = 'ACTIVE'")
    List<SweepRule> findAllActiveWithSources();
    
    List<SweepRule> findByStatus(SweepRule.SweepStatus status);
    
    @Query("SELECT COUNT(r) FROM SweepRule r WHERE r.status = :status")
    long countByStatus(@Param("status") String status);
    
    @Query("SELECT COUNT(r) FROM SweepRule r WHERE r.status = 'ACTIVE'")
    long countActive();
}