package com.bank.vam.repository.treasury;

import com.bank.vam.entity.treasury.NotionalPool;
import com.bank.vam.entity.treasury.NotionalPool.PoolStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface NotionalPoolRepository extends JpaRepository<NotionalPool, UUID> {
    
    Optional<NotionalPool> findByPoolReference(String poolReference);
    
    @Query("SELECT p FROM NotionalPool p LEFT JOIN FETCH p.members WHERE p.id = :id")
    Optional<NotionalPool> findByIdWithMembers(@Param("id") UUID id);
    
    @Query("SELECT p FROM NotionalPool p LEFT JOIN FETCH p.members WHERE p.status = 'ACTIVE'")
    List<NotionalPool> findAllActiveWithMembers();
    
    /**
     * Find pools by status using enum type
     */
    List<NotionalPool> findByStatus(PoolStatus status);
    
    /**
     * Count pools by status using enum type
     */
    long countByStatus(PoolStatus status);
    
    /**
     * Count active pools
     */
    @Query("SELECT COUNT(p) FROM NotionalPool p WHERE p.status = 'ACTIVE'")
    long countActive();
    
    /**
     * Sum total pooled balance for active pools
     */
    @Query("SELECT COALESCE(SUM(p.totalBalance), 0) FROM NotionalPool p WHERE p.status = 'ACTIVE'")
    BigDecimal sumTotalPooledBalance();
    
    /**
     * Find pools ordered by creation date
     */
    List<NotionalPool> findByStatusOrderByCreatedAtDesc(PoolStatus status);
}