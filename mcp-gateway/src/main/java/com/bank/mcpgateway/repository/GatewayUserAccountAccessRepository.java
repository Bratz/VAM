package com.bank.mcpgateway.repository;

import com.bank.mcpgateway.entity.GatewayUserAccountAccess;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface GatewayUserAccountAccessRepository extends JpaRepository<GatewayUserAccountAccess, UUID> {
    List<GatewayUserAccountAccess> findByUserId(UUID userId);
}
