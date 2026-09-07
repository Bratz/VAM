package com.bank.mcpgateway.service;

import com.bank.mcpgateway.entity.GatewayUser;
import com.bank.mcpgateway.repository.GatewayUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Backs the login form Spring Authorization Server shows during the
 * authorization-code step — authenticates against the same {@code users}
 * table Phase 2 seeded (the 5 demo treasurers), not a separate identity
 * store. No roles/authorities beyond a single generic one: this module
 * doesn't gate anything by role, only by the {@code user_account_access}
 * entitlement resolved later in {@link GatewayEntitlementService}.
 */
@Service
@RequiredArgsConstructor
public class DemoUserDetailsService implements UserDetailsService {

    private final GatewayUserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String username) {
        GatewayUser user = userRepository.findByUsername(username)
                .filter(GatewayUser::isActive)
                .orElseThrow(() -> new UsernameNotFoundException("Unknown or inactive user: " + username));

        return User.builder()
                .username(user.getUsername())
                .password(user.getPasswordHash())
                .authorities(List.of())
                .build();
    }
}
