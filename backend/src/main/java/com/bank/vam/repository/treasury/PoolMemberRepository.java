package com.bank.vam.repository.treasury;

import com.bank.vam.entity.treasury.PoolMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.UUID;

@Repository
public interface PoolMemberRepository extends JpaRepository<PoolMember, UUID> {
    List<PoolMember> findByPoolId(UUID poolId);
    List<PoolMember> findByAccountId(UUID accountId);

    /** Active members of a corporate's active notional pools, with their pool (rate, currency). */
    @org.springframework.data.jpa.repository.Query("SELECT m FROM PoolMember m JOIN FETCH m.pool p WHERE p.corporateId = :corporateId AND p.status = 'ACTIVE' AND m.status = 'ACTIVE'")
    List<PoolMember> findActiveByCorporate(@org.springframework.data.repository.query.Param("corporateId") UUID corporateId);
}
