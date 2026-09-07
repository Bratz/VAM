package com.bank.vam.repository.auth;

import com.bank.vam.entity.auth.UserAccountAccess;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface UserAccountAccessRepository extends JpaRepository<UserAccountAccess, UUID> {
    List<UserAccountAccess> findByUserId(UUID userId);
}
