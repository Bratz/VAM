package com.bank.mcpgateway.service;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Mints the per-call signed context — the artefact at the actual trust
 * boundary in {@code docs/mcp-architecture.md}'s target architecture: "mint a
 * short-lived JWS per-call context ... attach as signed {@code _meta}
 * context ... forward to the backend".
 *
 * <p>Deliberately <b>not</b> the OAuth access token itself, and deliberately
 * <b>not</b> reused across calls. An access token is a session-lived
 * credential (minutes) the client holds and resends on every request — a
 * captured one is replayable for its whole lifetime. This context is minted
 * fresh here, per {@code tools/call}, with a {@link #ttlSeconds} short enough
 * that a captured one has almost no useful replay window, and a random
 * {@code jti} the backend tracks to reject a within-window replay outright
 * (see {@code McpSignedContextVerifier} on the backend side). The
 * {@code tool} claim additionally binds the context to one specific tool
 * name, so a captured context for {@code get_position} can't be replayed
 * against a different tool.
 *
 * <p>Own RSA keypair, separate from Spring Authorization Server's own
 * OAuth-token-signing key — the two are different concerns (session auth vs.
 * per-call entitlement assertion) and keeping them on separate keys means
 * rotating one never affects the other.
 */
@Slf4j
@Service
public class ContextJwtService {

    private final long ttlSeconds;
    private RSAKey rsaJwk;

    public ContextJwtService(@Value("${gateway.signed-context-ttl-seconds:60}") long ttlSeconds) {
        this.ttlSeconds = ttlSeconds;
    }

    @PostConstruct
    void generateKeyPair() {
        try {
            this.rsaJwk = new RSAKeyGenerator(2048)
                    .keyID(UUID.randomUUID().toString())
                    .keyUse(com.nimbusds.jose.jwk.KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256)
                    .generate();
            log.info("Generated context-signing RSA keypair, kid={}", rsaJwk.getKeyID());
        } catch (JOSEException e) {
            throw new IllegalStateException("Failed to generate context-signing keypair", e);
        }
    }

    /**
     * @param subject          the authenticated username
     * @param corporateIds     every corporate this subject is entitled to (the
     *                         caller's full entitlement, for {@code CallerContext})
     * @param activeCorporateId the ONE corporate this specific call is scoped to
     *                         (becomes {@code ToolContext.corporateId()})
     * @param accessLevel      VIEW/TRANSACT/ADMIN for {@code activeCorporateId}
     * @param toolName         the tool being called, or null for non-tools/call methods
     */
    public String mintContext(String subject, Set<UUID> corporateIds, UUID activeCorporateId,
                               String accessLevel, String toolName) {
        Instant now = Instant.now();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .subject(subject)
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(ttlSeconds, ChronoUnit.SECONDS)))
                .claim("corporateIds", corporateIds.stream().map(UUID::toString).toList())
                .claim("activeCorporateId", activeCorporateId == null ? null : activeCorporateId.toString())
                .claim("accessLevel", accessLevel);
        if (toolName != null) {
            claims.claim("tool", toolName);
        }

        try {
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsaJwk.getKeyID()).build(),
                    claims.build());
            jwt.sign(new RSASSASigner(rsaJwk));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("Failed to sign context JWT", e);
        }
    }

    /** Public JWK Set for the backend to verify against — see {@code /context-jwks}. */
    public Map<String, Object> publicJwkSet() {
        return new JWKSet(rsaJwk.toPublicJWK()).toJSONObject();
    }
}
