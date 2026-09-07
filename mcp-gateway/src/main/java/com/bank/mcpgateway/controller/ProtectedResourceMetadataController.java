package com.bank.mcpgateway.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RFC 9728 OAuth 2.0 Protected Resource Metadata for this gateway's {@code
 * /mcp} endpoint.
 *
 * <p>Both Claude and ChatGPT's connector auth discover the authorization
 * server by fetching this document — either via the {@code
 * resource_metadata} pointer on {@link McpProxyController}'s {@code 401}
 * {@code WWW-Authenticate} header, or by probing this well-known path
 * directly. Without it, a client that only knows our {@code /mcp} URL has no
 * way to find out that {@code this same host} is also the authorization
 * server — confirmed against Claude's current connector-building docs
 * (claude.com/docs/connectors/building/authentication) and OpenAI's Apps SDK
 * docs, both fetched while wiring up Phase 4's real connector registration.
 */
@RestController
public class ProtectedResourceMetadataController {

    @Value("${spring.security.oauth2.authorizationserver.issuer}")
    private String issuer;

    @GetMapping(value = "/.well-known/oauth-protected-resource", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> metadata() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("resource", issuer + "/mcp");
        // This gateway is its own authorization server — a cross-host AS would
        // list a different issuer here instead.
        body.put("authorization_servers", List.of(issuer));
        body.put("scopes_supported", List.of("openid", "mcp:tools"));
        body.put("bearer_methods_supported", List.of("header"));
        return body;
    }
}
