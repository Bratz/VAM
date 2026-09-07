package com.bank.vam.mcp;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Finally gives {@code spring-boot-starter-oauth2-resource-server} — a
 * dependency declared in {@code pom.xml} since long before this MCP work,
 * with zero source references anywhere until now — an actual job: verifying
 * the gateway's per-call signed context against its published JWKS.
 */
@Configuration
@RequiredArgsConstructor
public class McpJwtConfig {

    private final McpProperties properties;

    @Bean
    public JwtDecoder mcpSignedContextJwtDecoder() {
        return NimbusJwtDecoder.withJwkSetUri(properties.getGatewaySignedContextJwksUri()).build();
    }
}
