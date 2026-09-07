package com.bank.vam.mcp;

import com.bank.vam.mcp.McpSignedContextVerifier.VerifiedCaller;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Proves the plan's own Phase 3 verification scenarios: a tampered claim, a
 * replayed jti, and an expired context are each rejected with {@code
 * McpException} carrying HTTP 401. Signs real JWTs with an in-test RSA
 * keypair and decodes them with a real {@link NimbusJwtDecoder} bound to
 * that same key — no network JWKS fetch, but the exact same signature/expiry
 * verification code path the live {@code mcp-gateway}/{@code JwkSetUri}
 * wiring uses.
 */
class McpSignedContextVerifierTest {

    private RSAKey rsaKey;
    private RSAKey otherPartysRsaKey; // simulates an attacker signing with a different key
    private McpSignedContextVerifier verifier;
    private McpReplayGuard replayGuard;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        rsaKey = new RSAKeyGenerator(2048).keyID("test-kid").generate();
        otherPartysRsaKey = new RSAKeyGenerator(2048).keyID("attacker-kid").generate();

        JwtDecoder jwtDecoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) rsaKey.toPublicKey()).build();

        ObjectProvider<StringRedisTemplate> noRedis = mock(ObjectProvider.class);
        when(noRedis.getIfAvailable()).thenReturn(null);
        replayGuard = new McpReplayGuard(noRedis);

        verifier = new McpSignedContextVerifier(jwtDecoder, replayGuard);
    }

    private String sign(RSAKey signingKey, String jti, Instant issuedAt, Instant expiresAt,
                         String tool, UUID activeCorporateId, UUID... entitledCorporateIds) throws Exception {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .subject("mercator.treasurer")
                .jwtID(jti)
                .issueTime(Date.from(issuedAt))
                .expirationTime(Date.from(expiresAt))
                .claim("activeCorporateId", activeCorporateId == null ? null : activeCorporateId.toString())
                .claim("corporateIds", List.of(entitledCorporateIds).stream().map(UUID::toString).toList())
                .claim("accessLevel", "VIEW");
        if (tool != null) {
            claims.claim("tool", tool);
        }
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(),
                claims.build());
        jwt.sign(new RSASSASigner(signingKey));
        return jwt.serialize();
    }

    @Test
    void validContext_verifiesAndReturnsCorporateAndCaller() throws Exception {
        UUID corporateId = UUID.randomUUID();
        String token = sign(rsaKey, UUID.randomUUID().toString(),
                Instant.now(), Instant.now().plus(60, ChronoUnit.SECONDS),
                "get_position", corporateId, corporateId);

        VerifiedCaller result = verifier.verify(token, "get_position");

        assertThat(result.activeCorporateId()).isEqualTo(corporateId);
        assertThat(result.caller().subject()).isEqualTo("mercator.treasurer");
        assertThat(result.caller().corporateIds()).containsExactly(corporateId);
    }

    @Test
    void tamperedSignature_isRejectedWith401() throws Exception {
        UUID corporateId = UUID.randomUUID();
        // "Tamper a claim": signed by a different key entirely — the backend has no
        // way to distinguish "re-signed with an attacker's key" from "signature
        // doesn't match the claims anymore"; both fail the same signature check.
        String token = sign(otherPartysRsaKey, UUID.randomUUID().toString(),
                Instant.now(), Instant.now().plus(60, ChronoUnit.SECONDS),
                "get_position", corporateId, corporateId);

        assertThatThrownBy(() -> verifier.verify(token, "get_position"))
                .isInstanceOf(McpException.class)
                .extracting(e -> ((McpException) e).getHttpStatus())
                .isEqualTo(401);
    }

    @Test
    void expiredContext_isRejectedWith401() throws Exception {
        UUID corporateId = UUID.randomUUID();
        String token = sign(rsaKey, UUID.randomUUID().toString(),
                Instant.now().minus(120, ChronoUnit.SECONDS), Instant.now().minus(60, ChronoUnit.SECONDS),
                "get_position", corporateId, corporateId);

        assertThatThrownBy(() -> verifier.verify(token, "get_position"))
                .isInstanceOf(McpException.class)
                .extracting(e -> ((McpException) e).getHttpStatus())
                .isEqualTo(401);
    }

    @Test
    void replayedJti_isRejectedOnSecondUse() throws Exception {
        UUID corporateId = UUID.randomUUID();
        String jti = UUID.randomUUID().toString();
        String token = sign(rsaKey, jti, Instant.now(), Instant.now().plus(60, ChronoUnit.SECONDS),
                "get_position", corporateId, corporateId);

        // First use succeeds.
        verifier.verify(token, "get_position");

        // Second use of the SAME token (same jti) is a replay.
        assertThatThrownBy(() -> verifier.verify(token, "get_position"))
                .isInstanceOf(McpException.class)
                .satisfies(e -> {
                    McpException mcpException = (McpException) e;
                    assertThat(mcpException.getHttpStatus()).isEqualTo(401);
                    assertThat(mcpException.getRpcCode()).isEqualTo(40102);
                });
    }

    @Test
    void toolBindingMismatch_isRejected() throws Exception {
        UUID corporateId = UUID.randomUUID();
        String token = sign(rsaKey, UUID.randomUUID().toString(),
                Instant.now(), Instant.now().plus(60, ChronoUnit.SECONDS),
                "get_position", corporateId, corporateId);

        // Context was minted for get_position — using it against a different tool must fail.
        assertThatThrownBy(() -> verifier.verify(token, "get_accounts"))
                .isInstanceOf(McpException.class)
                .extracting(e -> ((McpException) e).getRpcCode())
                .isEqualTo(40103);
    }
}
