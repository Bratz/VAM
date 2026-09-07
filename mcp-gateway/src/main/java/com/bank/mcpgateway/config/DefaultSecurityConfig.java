package com.bank.mcpgateway.config;

import com.bank.mcpgateway.service.DemoUserDetailsService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The "everything else" filter chain: the login form Spring Authorization
 * Server redirects to during the authorization-code step, and a small
 * permit-all carve-out for the endpoints that authenticate themselves
 * differently (the {@code /mcp} proxy validates its own Bearer token inline;
 * {@code /context-jwks} is deliberately public — it's a public key set;
 * {@code /authorized} is this demo's stand-in redirect_uri, see
 * AuthorizedController; {@code /.well-known/oauth-protected-resource} is the
 * RFC 9728 document a real connector fetches before it has any credentials
 * at all, see ProtectedResourceMetadataController).
 */
@Configuration
@RequiredArgsConstructor
public class DefaultSecurityConfig {

    private final DemoUserDetailsService userDetailsService;

    @Bean
    @Order(2)
    public SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/mcp", "/context-jwks", "/authorized",
                                "/.well-known/oauth-protected-resource").permitAll()
                        .anyRequest().authenticated())
                // /mcp is a stateless bearer-token API, not a browser form post — it
                // carries no session/CSRF token, and the default anonymous-user
                // handling of a CSRF failure is a 302 to the login page (indistinguishable
                // from an auth failure) rather than a plain 403.
                .csrf(csrf -> csrf.ignoringRequestMatchers("/mcp"))
                .formLogin(Customizer.withDefaults());
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }
}
