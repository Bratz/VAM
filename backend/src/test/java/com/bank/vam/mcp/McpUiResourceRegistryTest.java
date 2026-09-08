package com.bank.vam.mcp;

import com.bank.vam.ai.copilot.tools.McpUiDescriptors;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every URI a tool can point at via {@link McpUiDescriptors} must actually be
 * registered here — otherwise a host that calls {@code resources/read} on it
 * gets a 404 for a widget the tool descriptor advertised as available.
 */
class McpUiResourceRegistryTest {

    private final McpUiResourceRegistry registry = new McpUiResourceRegistry();

    @Test
    void everyDescriptorUri_resolvesToARegisteredResource() {
        assertThat(registry.get(McpUiDescriptors.POSITION_SUMMARY)).isNotNull();
        assertThat(registry.get(McpUiDescriptors.ACCOUNT_LIST)).isNotNull();
        assertThat(registry.get(McpUiDescriptors.SWEEP_STATUS)).isNotNull();
        assertThat(registry.get(McpUiDescriptors.EXCEPTION_LIST)).isNotNull();
    }

    @Test
    void unknownUri_resolvesToNull() {
        assertThat(registry.get("ui://widget/does-not-exist.html")).isNull();
    }

    @Test
    void everyResource_isSelfContainedHtml() {
        registry.all().forEach(resource -> {
            assertThat(resource.mimeType()).isEqualTo("text/html;profile=mcp-app");
            assertThat(resource.html()).contains("<!doctype html>", "window.openai");
            // No external network calls — every resource must be renderable with
            // no CSP allow-list entries.
            assertThat(resource.html()).doesNotContain("http://", "https://");
        });
    }

    @Test
    void all_returnsExactlyFourWidgets() {
        assertThat(registry.all()).hasSize(4);
    }
}
