package com.bank.mcpgateway.controller;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks down the RFC 9728 field names real clients (Claude, ChatGPT) parse —
 * a typo here (e.g. "authorization_server" instead of "authorization_servers")
 * fails silently at connector-registration time, not at compile time.
 */
class ProtectedResourceMetadataControllerTest {

    @Test
    void metadata_pointsAtThisGatewayAsItsOwnAuthorizationServer() {
        ProtectedResourceMetadataController controller = new ProtectedResourceMetadataController();
        ReflectionTestUtils.setField(controller, "issuer", "http://161.33.9.182:9443");

        Map<String, Object> body = controller.metadata();

        assertThat(body.get("resource")).isEqualTo("http://161.33.9.182:9443/mcp");
        assertThat(body.get("authorization_servers")).isEqualTo(List.of("http://161.33.9.182:9443"));
        assertThat(body.get("scopes_supported")).isEqualTo(List.of("openid", "mcp:tools"));
        assertThat(body.get("bearer_methods_supported")).isEqualTo(List.of("header"));
    }
}
