package com.bank.mcpgateway.repository;

import com.bank.mcpgateway.entity.GatewayUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface GatewayUserRepository extends JpaRepository<GatewayUser, UUID> {
    Optional<GatewayUser> findByUsername(String username);
}
