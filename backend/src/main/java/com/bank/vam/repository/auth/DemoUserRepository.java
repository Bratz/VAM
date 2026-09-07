package com.bank.vam.repository.auth;

import com.bank.vam.entity.auth.DemoUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface DemoUserRepository extends JpaRepository<DemoUser, UUID> {
    Optional<DemoUser> findByUsername(String username);
}
