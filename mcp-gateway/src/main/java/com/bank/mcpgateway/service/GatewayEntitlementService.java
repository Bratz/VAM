package com.bank.mcpgateway.service;

import com.bank.mcpgateway.entity.GatewayUserAccountAccess;
import com.bank.mcpgateway.entity.GatewayUserAccountAccess.AccessLevel;
import com.bank.mcpgateway.repository.GatewayUserAccountAccessRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Gateway-side counterpart of the backend's {@code EntitlementService}
 * ({@code com.bank.vam.service.auth}) — same {@code user_account_access}
 * table, read fresh on every {@code tools/call} so a corporate grant revoked
 * after token issuance takes effect immediately (see {@link
 * com.bank.mcpgateway.controller.McpProxyController}, which re-resolves per
 * call rather than trusting anything cached in the OAuth access token).
 *
 * <p><b>Simplification vs. the backend's version:</b> only resolves
 * corporate-wide grants ({@code corporateId} set directly on the row) — not
 * single-VA grants requiring a VA→corporate lookup. Every seeded demo
 * treasurer uses a corporate-wide grant (see the Phase 2 seed data), so this
 * doesn't lose anything today; a VA-scoped grant is logged and skipped rather
 * than silently mis-resolved. Pulling in a full {@code VirtualAccount} mirror
 * entity here — just to resolve a case with zero live rows — isn't worth it
 * yet; add it if a real VA-level grant is ever seeded.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GatewayEntitlementService {

    private final GatewayUserAccountAccessRepository accessRepository;

    public Map<UUID, AccessLevel> resolve(UUID userId) {
        Map<UUID, AccessLevel> accessLevelByCorporate = new HashMap<>();
        for (GatewayUserAccountAccess grant : accessRepository.findByUserId(userId)) {
            if (grant.getCorporateId() == null) {
                log.warn("Skipping VA-scoped grant {} for user {} — gateway only resolves corporate-wide grants",
                        grant.getId(), userId);
                continue;
            }
            accessLevelByCorporate.merge(grant.getCorporateId(), grant.getAccessLevel(),
                    (existing, incoming) -> existing.atLeast(incoming) ? existing : incoming);
        }
        return accessLevelByCorporate;
    }
}
