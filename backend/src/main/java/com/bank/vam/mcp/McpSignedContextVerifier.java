package com.bank.vam.mcp;

import com.bank.vam.ai.copilot.tools.ToolContext.CallerContext;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Verifies the per-call signed context {@code mcp-gateway}'s {@code
 * ContextJwtService} mints, at the exact seam
 * {@code docs/mcp-architecture.md} names: "MCP server verifies the signature
 * and enforces it."
 *
 * <p>Three independent checks, each with its own {@link McpException}
 * outcome (see that class's javadoc for why they're distinct codes even
 * though all are HTTP 401):
 * <ol>
 *   <li>signature + expiry, via {@link JwtDecoder} against the gateway's
 *       published JWKS — catches both tampering and an expired context</li>
 *   <li>replay — the context's {@code jti} must not have been seen before
 *       (see {@link McpReplayGuard})</li>
 *   <li>tool binding — a context minted for one tool must not be usable
 *       against another</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
public class McpSignedContextVerifier {

    private final JwtDecoder jwtDecoder;
    private final McpReplayGuard replayGuard;

    public record VerifiedCaller(UUID activeCorporateId, CallerContext caller) {
    }

    /**
     * @param signedContext  the compact JWS from {@code params._meta}
     * @param expectedToolName the tool actually being invoked (null for
     *                       non-{@code tools/call} methods, which skips the
     *                       tool-binding check)
     */
    public VerifiedCaller verify(String signedContext, String expectedToolName) {
        Jwt jwt;
        try {
            jwt = jwtDecoder.decode(signedContext);
        } catch (JwtException e) {
            throw McpException.invalidSignedContext(e.getMessage());
        }

        String jti = jwt.getId();
        if (jti == null || !replayGuard.markUsedIfNew(jti)) {
            throw McpException.replayedSignedContext();
        }

        String claimedTool = jwt.getClaimAsString("tool");
        if (expectedToolName != null && !expectedToolName.equals(claimedTool)) {
            throw McpException.signedContextToolMismatch();
        }

        String subject = jwt.getSubject();
        String accessLevel = jwt.getClaimAsString("accessLevel");
        String activeCorporateIdRaw = jwt.getClaimAsString("activeCorporateId");
        List<String> corporateIdRaw = jwt.getClaimAsStringList("corporateIds");

        UUID activeCorporateId = parseUuidOrNull(activeCorporateIdRaw);
        Set<UUID> corporateIds = corporateIdRaw == null ? Set.of()
                : corporateIdRaw.stream()
                        .map(this::parseUuidOrNull)
                        .filter(java.util.Objects::nonNull)
                        .collect(java.util.stream.Collectors.toSet());

        CallerContext caller = new CallerContext(subject, corporateIds, accessLevel, List.of(), jti);
        return new VerifiedCaller(activeCorporateId, caller);
    }

    private UUID parseUuidOrNull(String value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
