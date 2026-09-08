package com.bank.mcpgateway.controller;

import com.bank.mcpgateway.entity.GatewayUser;
import com.bank.mcpgateway.entity.GatewayUserAccountAccess.AccessLevel;
import com.bank.mcpgateway.repository.GatewayUserRepository;
import com.bank.mcpgateway.service.ContextJwtService;
import com.bank.mcpgateway.service.GatewayEntitlementService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The trust boundary: {@code POST /mcp} on this gateway is what a real MCP
 * client (or, in this phase, our manual test flow) actually calls. This is
 * the "gateway signs / server verifies" half of {@code
 * docs/mcp-architecture.md}'s target architecture:
 *
 * <ol>
 *   <li>Validate the caller's OAuth access token (this gateway's own, from
 *       {@link com.bank.mcpgateway.config.AuthorizationServerConfig})</li>
 *   <li>Resolve entitlement fresh, per call, from {@link GatewayEntitlementService}
 *       — never trust anything cached in the access token itself, since a
 *       corporate grant revoked after token issuance must take effect
 *       immediately, not only after the token expires</li>
 *   <li>Mint a fresh, single-use, short-TTL signed context via {@link
 *       ContextJwtService} and attach it to the outgoing request's
 *       {@code params._meta} under a non-MCP-reserved key</li>
 *   <li>Forward to the backend's real {@code /mcp} and return its response
 *       verbatim</li>
 * </ol>
 *
 * <p>Deliberately synchronous/blocking ({@link RestClient}, not WebFlux) —
 * every Aperture MCP tool is a fast read; there is nothing here to stream.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class McpProxyController {

    /** Non-MCP-reserved _meta key: prefix "com.aperture.gateway/" — second
     *  label is "aperture", not "modelcontextprotocol"/"mcp", so this is not
     *  in MCP's reserved namespace (see the _meta key-naming rules). */
    private static final String SIGNED_CONTEXT_META_KEY = "com.aperture.gateway/signedContext";

    private final JwtDecoder jwtDecoder;
    private final GatewayUserRepository userRepository;
    private final GatewayEntitlementService entitlementService;
    private final ContextJwtService contextJwtService;
    private final ObjectMapper objectMapper;

    @Value("${gateway.backend-mcp-url}")
    private String backendMcpUrl;

    /** Same value Spring AS uses as its own issuer — this gateway is its own
     *  authorization server, so the protected-resource-metadata document lives
     *  right next to it. See {@link ProtectedResourceMetadataController}. */
    @Value("${spring.security.oauth2.authorizationserver.issuer}")
    private String issuer;

    private RestClient restClient;

    @PostConstruct
    void initRestClient() {
        this.restClient = RestClient.create();
    }

    @PostMapping(value = "/mcp", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> proxy(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader,
            @RequestHeader(value = "MCP-Protocol-Version", required = false) String protocolVersionHeader,
            @RequestHeader(value = "Mcp-Method", required = false) String mcpMethodHeader,
            @RequestHeader(value = "Mcp-Name", required = false) String mcpNameHeader,
            @RequestHeader(value = "X-Active-Corporate", required = false) String activeCorporateOverride,
            @RequestBody(required = false) Map<String, Object> body) {

        Object requestId = body == null ? null : body.get("id");

        String accessToken = bearerToken(authorizationHeader);
        if (accessToken == null) {
            return unauthorized(requestId, "Missing bearer access token");
        }

        Jwt jwt;
        try {
            jwt = jwtDecoder.decode(accessToken);
        } catch (JwtException e) {
            log.warn("Rejected invalid/expired access token: {}", e.getMessage());
            return unauthorized(requestId, "Invalid or expired access token");
        }

        String username = jwt.getSubject();
        Optional<GatewayUser> user = userRepository.findByUsername(username).filter(GatewayUser::isActive);
        if (user.isEmpty()) {
            return unauthorized(requestId, "Unknown or inactive subject");
        }

        Map<UUID, AccessLevel> entitlement = entitlementService.resolve(user.get().getId());
        if (entitlement.isEmpty()) {
            return forbidden(requestId, "No corporate entitlement for this account");
        }

        UUID activeCorporateId = resolveActiveCorporate(entitlement, activeCorporateOverride);
        if (activeCorporateId == null) {
            return forbidden(requestId,
                    "Multiple entitled corporates — specify X-Active-Corporate, or contact support for a single-corporate account");
        }

        String toolName = extractToolName(body);
        String signedContext = contextJwtService.mintContext(
                username, entitlement.keySet(), activeCorporateId,
                entitlement.get(activeCorporateId).name(), toolName);

        Map<String, Object> outgoingBody = injectSignedContext(body, signedContext);

        try {
            ResponseEntity<String> backendResponse = restClient.post()
                    .uri(backendMcpUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> {
                        // required=false request headers are null when the caller omits
                        // them (true for every client that doesn't send these two custom
                        // headers) — the JDK HttpClient under RestClient throws
                        // NullPointerException on a null header value, so only forward
                        // what was actually sent.
                        if (protocolVersionHeader != null) headers.set("MCP-Protocol-Version", protocolVersionHeader);
                        if (mcpMethodHeader != null) headers.set("Mcp-Method", mcpMethodHeader);
                        if (mcpNameHeader != null) headers.set("Mcp-Name", mcpNameHeader);
                    })
                    .body(objectMapper.writeValueAsString(outgoingBody))
                    .retrieve()
                    .onStatus(status -> true, (req, res) -> {}) // don't throw on 4xx/5xx — relay them
                    .toEntity(String.class);

            return ResponseEntity.status(backendResponse.getStatusCode())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(backendResponse.getBody());
        } catch (Exception e) {
            log.error("Failed to proxy request to backend MCP server at {}", backendMcpUrl, e);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(errorBody(requestId, -32603, "Gateway failed to reach backend MCP server"));
        }
    }

    private UUID resolveActiveCorporate(Map<UUID, AccessLevel> entitlement, String override) {
        if (override != null) {
            UUID overrideId;
            try {
                overrideId = UUID.fromString(override);
            } catch (IllegalArgumentException e) {
                return null;
            }
            return entitlement.containsKey(overrideId) ? overrideId : null;
        }
        return entitlement.size() == 1 ? entitlement.keySet().iterator().next() : null;
    }

    @SuppressWarnings("unchecked")
    private String extractToolName(Map<String, Object> body) {
        if (body == null || !"tools/call".equals(body.get("method"))) {
            return null;
        }
        Object params = body.get("params");
        if (!(params instanceof Map)) {
            return null;
        }
        Object name = ((Map<String, Object>) params).get("name");
        return name instanceof String ? (String) name : null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> injectSignedContext(Map<String, Object> body, String signedContext) {
        Map<String, Object> outgoing = new LinkedHashMap<>(body);
        Map<String, Object> params = outgoing.get("params") instanceof Map
                ? new LinkedHashMap<>((Map<String, Object>) outgoing.get("params"))
                : new LinkedHashMap<>();
        Map<String, Object> meta = params.get("_meta") instanceof Map
                ? new LinkedHashMap<>((Map<String, Object>) params.get("_meta"))
                : new LinkedHashMap<>();
        meta.put(SIGNED_CONTEXT_META_KEY, signedContext);
        params.put("_meta", meta);
        outgoing.put("params", params);
        return outgoing;
    }

    private String bearerToken(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        return authorizationHeader.substring(7).trim();
    }

    /**
     * The {@code resource_metadata} parameter is what lets Claude/ChatGPT find
     * our RFC 9728 document from nothing but this {@code /mcp} URL — without
     * it, a client that only knows this endpoint has no path to discovering
     * that this same host is also the authorization server (see
     * {@link ProtectedResourceMetadataController}).
     */
    private ResponseEntity<Object> unauthorized(Object requestId, String message) {
        String resourceMetadataUrl = issuer + "/.well-known/oauth-protected-resource";
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE,
                        "Bearer error=\"invalid_token\", resource_metadata=\"" + resourceMetadataUrl + "\"")
                .body(errorBody(requestId, -32001, message));
    }

    private ResponseEntity<Object> forbidden(Object requestId, String message) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(errorBody(requestId, -32001, message));
    }

    private Map<String, Object> errorBody(Object requestId, int code, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("jsonrpc", "2.0");
        if (requestId != null) {
            envelope.put("id", requestId);
        }
        envelope.put("error", error);
        return envelope;
    }
}
