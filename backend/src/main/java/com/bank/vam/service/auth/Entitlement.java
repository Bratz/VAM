package com.bank.vam.service.auth;

import com.bank.vam.entity.auth.UserAccountAccess.AccessLevel;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The resolved answer to "what can this user see?" — every corporate they're
 * entitled to (whether via a corporate-wide grant or a single-VA grant that
 * resolved up to its owning corporate) and their access level in each.
 *
 * <p>Enquiry-only MCP tools need nothing more than {@link #canView}: any grant
 * at all — VIEW, TRANSACT, or ADMIN — satisfies a read. TRANSACT/ADMIN checks
 * belong to whatever eventually exposes mutating actions (not this phase; see
 * the exposure decision in docs/mcp-architecture.md — enquiry-only, no
 * mutation path on the MCP surface).
 */
public record Entitlement(String userId, Map<UUID, AccessLevel> accessLevelByCorporate) {

    public static final Entitlement NONE = new Entitlement(null, Map.of());

    public Set<UUID> corporateIds() {
        return accessLevelByCorporate.keySet();
    }

    public boolean canView(UUID corporateId) {
        return corporateId != null && accessLevelByCorporate.containsKey(corporateId);
    }

    public boolean hasAccess(UUID corporateId, AccessLevel required) {
        AccessLevel granted = accessLevelByCorporate.get(corporateId);
        return granted != null && granted.atLeast(required);
    }
}
