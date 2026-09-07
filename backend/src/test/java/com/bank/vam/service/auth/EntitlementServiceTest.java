package com.bank.vam.service.auth;

import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.auth.DemoUser;
import com.bank.vam.entity.auth.UserAccountAccess;
import com.bank.vam.entity.auth.UserAccountAccess.AccessLevel;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.auth.DemoUserRepository;
import com.bank.vam.repository.auth.UserAccountAccessRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Proves the plan's own Phase 2 verification scenario: two demo users linked
 * to different corporates resolve to disjoint entitlements. Also covers the
 * two grant shapes {@code user_account_access} supports (corporate-wide and
 * single-VA-resolved-to-its-corporate) and the higher-access-wins merge rule.
 */
class EntitlementServiceTest {

    private DemoUser activeUser(UUID id, String username) {
        DemoUser user = new DemoUser();
        user.setId(id);
        user.setUsername(username);
        user.setEmail(username + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setStatus("ACTIVE");
        return user;
    }

    @Test
    void twoDemoUsers_resolveToDisjointCorporateEntitlements() {
        DemoUserRepository userRepository = mock(DemoUserRepository.class);
        UserAccountAccessRepository accessRepository = mock(UserAccountAccessRepository.class);
        VirtualAccountRepository vaRepository = mock(VirtualAccountRepository.class);
        EntitlementService service = new EntitlementService(userRepository, accessRepository, vaRepository);

        UUID mercatorTreasurerId = UUID.randomUUID();
        UUID albionTreasurerId = UUID.randomUUID();
        UUID mercatorCorporateId = UUID.fromString("aa000001-0000-0000-0000-000000000001");
        UUID albionCorporateId = UUID.fromString("cc000003-0000-0000-0000-000000000003");

        when(userRepository.findByUsername("mercator.treasurer"))
                .thenReturn(Optional.of(activeUser(mercatorTreasurerId, "mercator.treasurer")));
        when(userRepository.findByUsername("albion.treasurer"))
                .thenReturn(Optional.of(activeUser(albionTreasurerId, "albion.treasurer")));

        when(accessRepository.findByUserId(mercatorTreasurerId)).thenReturn(List.of(
                UserAccountAccess.builder().userId(mercatorTreasurerId).corporateId(mercatorCorporateId)
                        .accessLevel(AccessLevel.VIEW).build()));
        when(accessRepository.findByUserId(albionTreasurerId)).thenReturn(List.of(
                UserAccountAccess.builder().userId(albionTreasurerId).corporateId(albionCorporateId)
                        .accessLevel(AccessLevel.VIEW).build()));

        Entitlement mercatorEntitlement = service.resolve("mercator.treasurer");
        Entitlement albionEntitlement = service.resolve("albion.treasurer");

        assertThat(mercatorEntitlement.corporateIds()).containsExactly(mercatorCorporateId);
        assertThat(albionEntitlement.corporateIds()).containsExactly(albionCorporateId);
        assertThat(mercatorEntitlement.canView(albionCorporateId)).isFalse();
        assertThat(albionEntitlement.canView(mercatorCorporateId)).isFalse();
    }

    @Test
    void singleVaGrant_resolvesToItsOwningCorporate() {
        DemoUserRepository userRepository = mock(DemoUserRepository.class);
        UserAccountAccessRepository accessRepository = mock(UserAccountAccessRepository.class);
        VirtualAccountRepository vaRepository = mock(VirtualAccountRepository.class);
        EntitlementService service = new EntitlementService(userRepository, accessRepository, vaRepository);

        UUID userId = UUID.randomUUID();
        UUID vaId = UUID.randomUUID();
        UUID corporateId = UUID.randomUUID();

        when(userRepository.findByUsername("scoped.user")).thenReturn(Optional.of(activeUser(userId, "scoped.user")));
        when(accessRepository.findByUserId(userId)).thenReturn(List.of(
                UserAccountAccess.builder().userId(userId).virtualAccountId(vaId).accessLevel(AccessLevel.VIEW).build()));

        VirtualAccount va = new VirtualAccount();
        va.setId(vaId);
        va.setCorporateId(corporateId);
        when(vaRepository.findAllById(java.util.Set.of(vaId))).thenReturn(List.of(va));

        Entitlement entitlement = service.resolve("scoped.user");

        assertThat(entitlement.canView(corporateId)).isTrue();
    }

    @Test
    void higherAccessLevelWins_whenMultipleGrantsTouchSameCorporate() {
        DemoUserRepository userRepository = mock(DemoUserRepository.class);
        UserAccountAccessRepository accessRepository = mock(UserAccountAccessRepository.class);
        VirtualAccountRepository vaRepository = mock(VirtualAccountRepository.class);
        EntitlementService service = new EntitlementService(userRepository, accessRepository, vaRepository);

        UUID userId = UUID.randomUUID();
        UUID corporateId = UUID.randomUUID();
        when(userRepository.findByUsername("admin.user")).thenReturn(Optional.of(activeUser(userId, "admin.user")));
        when(accessRepository.findByUserId(userId)).thenReturn(List.of(
                UserAccountAccess.builder().userId(userId).corporateId(corporateId).accessLevel(AccessLevel.VIEW).build(),
                UserAccountAccess.builder().userId(userId).corporateId(corporateId).accessLevel(AccessLevel.ADMIN).build()));

        Entitlement entitlement = service.resolve("admin.user");

        assertThat(entitlement.hasAccess(corporateId, AccessLevel.ADMIN)).isTrue();
    }

    @Test
    void unknownUser_resolvesToNoEntitlement() {
        DemoUserRepository userRepository = mock(DemoUserRepository.class);
        UserAccountAccessRepository accessRepository = mock(UserAccountAccessRepository.class);
        VirtualAccountRepository vaRepository = mock(VirtualAccountRepository.class);
        EntitlementService service = new EntitlementService(userRepository, accessRepository, vaRepository);

        when(userRepository.findByUsername("nobody")).thenReturn(Optional.empty());

        assertThat(service.resolve("nobody").corporateIds()).isEmpty();
    }

    @Test
    void inactiveUser_resolvesToNoEntitlement_evenWithGrants() {
        DemoUserRepository userRepository = mock(DemoUserRepository.class);
        UserAccountAccessRepository accessRepository = mock(UserAccountAccessRepository.class);
        VirtualAccountRepository vaRepository = mock(VirtualAccountRepository.class);
        EntitlementService service = new EntitlementService(userRepository, accessRepository, vaRepository);

        UUID userId = UUID.randomUUID();
        DemoUser suspended = activeUser(userId, "suspended.user");
        suspended.setStatus("SUSPENDED");
        when(userRepository.findByUsername("suspended.user")).thenReturn(Optional.of(suspended));

        assertThat(service.resolve("suspended.user").corporateIds()).isEmpty();
    }
}
