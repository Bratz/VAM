package com.bank.vam.service.auth;

import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.auth.UserAccountAccess;
import com.bank.vam.entity.auth.UserAccountAccess.AccessLevel;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.auth.DemoUserRepository;
import com.bank.vam.repository.auth.UserAccountAccessRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Resolves a user's entitlement from {@code user_account_access} — the table
 * {@code database/original-schema.sql} designed for exactly this and that was
 * never wired to anything until now.
 *
 * <p>Two kinds of grant, both rolled up to the same corporate-level answer:
 * <ul>
 *   <li>corporate-wide ({@code corporateId} set directly on the row)</li>
 *   <li>single-VA ({@code virtualAccountId} set) — resolved to the VA's owning
 *       corporate via one batch {@code findAllById}, not a lookup per row</li>
 * </ul>
 * When a user holds more than one grant touching the same corporate (e.g. a
 * corporate-wide VIEW plus a single-VA ADMIN override), the higher
 * {@link AccessLevel} wins — a narrower grant should never accidentally
 * downgrade a broader one already held.
 */
@Service
@RequiredArgsConstructor
public class EntitlementService {

    private final DemoUserRepository userRepository;
    private final UserAccountAccessRepository accessRepository;
    private final VirtualAccountRepository virtualAccountRepository;

    @Transactional(readOnly = true)
    public Entitlement resolve(String username) {
        if (username == null || username.isBlank()) {
            return Entitlement.NONE;
        }
        return userRepository.findByUsername(username)
                .filter(user -> user.isActive())
                .map(user -> resolveForUserId(username, user.getId()))
                .orElse(Entitlement.NONE);
    }

    private Entitlement resolveForUserId(String username, UUID userId) {
        List<UserAccountAccess> grants = accessRepository.findByUserId(userId);
        if (grants.isEmpty()) {
            return Entitlement.NONE;
        }

        Set<UUID> vaLevelGrantIds = grants.stream()
                .map(UserAccountAccess::getVirtualAccountId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, UUID> vaIdToCorporateId = vaLevelGrantIds.isEmpty() ? Map.of()
                : virtualAccountRepository.findAllById(vaLevelGrantIds).stream()
                        .collect(Collectors.toMap(VirtualAccount::getId, VirtualAccount::getCorporateId));

        Map<UUID, AccessLevel> accessLevelByCorporate = new HashMap<>();
        for (UserAccountAccess grant : grants) {
            UUID corporateId = grant.getCorporateId() != null
                    ? grant.getCorporateId()
                    : vaIdToCorporateId.get(grant.getVirtualAccountId());
            if (corporateId == null) {
                continue; // dangling VA-level grant (VA deleted, or not found) — skip, don't fail the whole resolution
            }
            accessLevelByCorporate.merge(corporateId, grant.getAccessLevel(),
                    (existing, incoming) -> existing.atLeast(incoming) ? existing : incoming);
        }
        return new Entitlement(username, Map.copyOf(accessLevelByCorporate));
    }
}
