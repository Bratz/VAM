package com.bank.vam.mcp;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * MCP server feature configuration. Bound from {@code vam.mcp.*}.
 *
 * <p>Phase 1: the server runs with no caller identity at all — every call is
 * treated the same way an in-app copilot call with no auth is treated today
 * ({@code ToolContext.corporateId() == null}). {@link #requireSignedContext}
 * exists now so Phase 3 (the gateway) is a config flip, not a code change: once
 * the gateway mints a signed per-call context and the server can verify it,
 * turning this on makes an unsigned/unverifiable call a hard 401 instead of a
 * silently-unscoped one.
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "vam.mcp")
public class McpProperties {

    /** Master kill-switch. When false, {@code POST /mcp} responds 503. */
    private boolean enabled = false;

    /**
     * The single protocol version this server speaks. MCP 2026-07-28 removed
     * the initialize handshake and protocol-level sessions — every request
     * declares its version in {@code _meta}, matched against this value.
     * See docs/mcp-architecture.md's "Open items" note: re-verify against the
     * live spec before bumping, don't assume the next dated revision is a
     * drop-in replacement.
     */
    private String protocolVersion = "2026-07-28";

    /**
     * Phase 3 flips this on once the gateway exists and can sign a per-call
     * context. Phase 1/2 leave it false: there's no gateway yet, so requiring
     * a signature would make the server unusable rather than unscoped.
     */
    private boolean requireSignedContext = false;

    /**
     * The gateway's per-call-context JWKS endpoint (see {@code
     * mcp-gateway}'s {@code ContextJwksController} / {@code /context-jwks}) —
     * separate from any OAuth-access-token JWKS. A signed context, if
     * present, is always verified against this regardless of {@link
     * #requireSignedContext}; that flag only controls whether a call with NO
     * signed context at all is rejected or allowed through unscoped.
     */
    private String gatewaySignedContextJwksUri = "http://localhost:9443/context-jwks";

    /**
     * DNS-rebinding guard the spec requires ("Servers MUST validate the Origin
     * header on all incoming connections"). An {@code Origin} header absent
     * entirely is allowed through unconditionally — MCP HTTP clients are
     * typically server-to-server (ChatGPT/Claude backends, not browser JS)
     * and legitimately don't send one. When present, it must be in this list.
     * Phase 1 default covers local testing only; Phase 4 MUST add the real
     * Cloudflare Tunnel / ChatGPT / Claude origins before public exposure —
     * don't assume this default is sufficient once the tunnel goes up.
     */
    private List<String> allowedOrigins = List.of("http://localhost:8053", "http://127.0.0.1:8053");

    private final RateLimit rateLimit = new RateLimit();

    @Getter
    @Setter
    public static class RateLimit {
        /** Calls allowed per tool name per refresh period. */
        private int limitForPeriod = 30;

        private int limitRefreshPeriodSeconds = 60;

        /** How long a call waits for a rate-limit permit before failing. 0 = fail immediately. */
        private long timeoutMillis = 0;
    }
}
