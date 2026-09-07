package com.bank.vam.mcp;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link McpRequestValidator} — no Spring context, matching
 * {@code IntentRouterTest}'s style. Covers every rejection path MCP 2026-07-28's
 * Streamable HTTP transport requires (see the transport spec's "Server
 * Validation" section) plus the two happy paths (tools/list, tools/call).
 */
class McpRequestValidatorTest {

    private static final String SUPPORTED_VERSION = "2026-07-28";

    private final McpRequestValidator validator = new McpRequestValidator(SUPPORTED_VERSION);

    private Map<String, Object> validParams(String toolName) {
        Map<String, Object> meta = new HashMap<>();
        meta.put(McpMeta.PROTOCOL_VERSION, SUPPORTED_VERSION);
        meta.put(McpMeta.CLIENT_CAPABILITIES, Map.of());
        Map<String, Object> params = new HashMap<>();
        params.put("_meta", meta);
        if (toolName != null) {
            params.put("name", toolName);
        }
        return params;
    }

    @Test
    void toolsList_validRequest_passes() {
        Map<String, Object> params = validParams(null);
        assertDoesNotThrow(() -> validator.validate("tools/list", params, SUPPORTED_VERSION, "tools/list", null));
    }

    @Test
    void toolsCall_validRequest_passes() {
        Map<String, Object> params = validParams("get_position");
        assertDoesNotThrow(() ->
                validator.validate("tools/call", params, SUPPORTED_VERSION, "tools/call", "get_position"));
    }

    @Test
    void missingMcpMethodHeader_throwsHeaderMismatch() {
        Map<String, Object> params = validParams(null);
        assertThatThrownBy(() -> validator.validate("tools/list", params, SUPPORTED_VERSION, null, null))
                .isInstanceOf(McpException.class)
                .extracting(e -> ((McpException) e).getRpcCode()).isEqualTo(-32020);
    }

    @Test
    void mcpMethodHeaderMismatchesBody_throwsHeaderMismatch() {
        Map<String, Object> params = validParams(null);
        McpException e = catchMcpException(() ->
                validator.validate("tools/list", params, SUPPORTED_VERSION, "tools/call", null));
        assertThat(e.getRpcCode()).isEqualTo(-32020);
        assertThat(e.getHttpStatus()).isEqualTo(400);
    }

    @Test
    void toolsCall_missingMcpNameHeader_throwsHeaderMismatch() {
        Map<String, Object> params = validParams("get_position");
        McpException e = catchMcpException(() ->
                validator.validate("tools/call", params, SUPPORTED_VERSION, "tools/call", null));
        assertThat(e.getRpcCode()).isEqualTo(-32020);
    }

    @Test
    void toolsCall_mcpNameHeaderMismatchesBody_throwsHeaderMismatch() {
        Map<String, Object> params = validParams("get_position");
        McpException e = catchMcpException(() ->
                validator.validate("tools/call", params, SUPPORTED_VERSION, "tools/call", "get_accounts"));
        assertThat(e.getRpcCode()).isEqualTo(-32020);
    }

    @Test
    void resourcesRead_validRequest_passes() {
        Map<String, Object> meta = new HashMap<>();
        meta.put(McpMeta.PROTOCOL_VERSION, SUPPORTED_VERSION);
        meta.put(McpMeta.CLIENT_CAPABILITIES, Map.of());
        Map<String, Object> params = new HashMap<>();
        params.put("_meta", meta);
        params.put("uri", "ui://widget/position-summary.html");

        assertDoesNotThrow(() -> validator.validate("resources/read", params, SUPPORTED_VERSION,
                "resources/read", "ui://widget/position-summary.html"));
    }

    @Test
    void resourcesRead_missingMcpNameHeader_throwsHeaderMismatch() {
        Map<String, Object> meta = new HashMap<>();
        meta.put(McpMeta.PROTOCOL_VERSION, SUPPORTED_VERSION);
        meta.put(McpMeta.CLIENT_CAPABILITIES, Map.of());
        Map<String, Object> params = new HashMap<>();
        params.put("_meta", meta);
        params.put("uri", "ui://widget/position-summary.html");

        McpException e = catchMcpException(() ->
                validator.validate("resources/read", params, SUPPORTED_VERSION, "resources/read", null));
        assertThat(e.getRpcCode()).isEqualTo(-32020);
    }

    @Test
    void resourcesRead_mcpNameHeaderMismatchesUri_throwsHeaderMismatch() {
        Map<String, Object> meta = new HashMap<>();
        meta.put(McpMeta.PROTOCOL_VERSION, SUPPORTED_VERSION);
        meta.put(McpMeta.CLIENT_CAPABILITIES, Map.of());
        Map<String, Object> params = new HashMap<>();
        params.put("_meta", meta);
        params.put("uri", "ui://widget/position-summary.html");

        McpException e = catchMcpException(() -> validator.validate("resources/read", params, SUPPORTED_VERSION,
                "resources/read", "ui://widget/account-list.html"));
        assertThat(e.getRpcCode()).isEqualTo(-32020);
    }

    @Test
    void resourcesList_doesNotRequireMcpNameHeader() {
        Map<String, Object> params = validParams(null);
        assertDoesNotThrow(() ->
                validator.validate("resources/list", params, SUPPORTED_VERSION, "resources/list", null));
    }

    @Test
    void missingMeta_throwsInvalidParams() {
        Map<String, Object> params = new HashMap<>();
        McpException e = catchMcpException(() ->
                validator.validate("tools/list", params, SUPPORTED_VERSION, "tools/list", null));
        assertThat(e.getRpcCode()).isEqualTo(-32602);
    }

    @Test
    void metaMissingProtocolVersion_throwsInvalidParams() {
        Map<String, Object> meta = new HashMap<>();
        meta.put(McpMeta.CLIENT_CAPABILITIES, Map.of());
        Map<String, Object> params = new HashMap<>();
        params.put("_meta", meta);

        McpException e = catchMcpException(() ->
                validator.validate("tools/list", params, SUPPORTED_VERSION, "tools/list", null));
        assertThat(e.getRpcCode()).isEqualTo(-32602);
    }

    @Test
    void metaMissingClientCapabilities_throwsInvalidParams() {
        Map<String, Object> meta = new HashMap<>();
        meta.put(McpMeta.PROTOCOL_VERSION, SUPPORTED_VERSION);
        Map<String, Object> params = new HashMap<>();
        params.put("_meta", meta);

        McpException e = catchMcpException(() ->
                validator.validate("tools/list", params, SUPPORTED_VERSION, "tools/list", null));
        assertThat(e.getRpcCode()).isEqualTo(-32602);
    }

    @Test
    void missingProtocolVersionHeader_throwsHeaderMismatch() {
        Map<String, Object> params = validParams(null);
        McpException e = catchMcpException(() ->
                validator.validate("tools/list", params, null, "tools/list", null));
        assertThat(e.getRpcCode()).isEqualTo(-32020);
    }

    @Test
    void protocolVersionHeaderMismatchesMeta_throwsHeaderMismatch() {
        Map<String, Object> params = validParams(null);
        McpException e = catchMcpException(() ->
                validator.validate("tools/list", params, "2025-06-18", "tools/list", null));
        assertThat(e.getRpcCode()).isEqualTo(-32020);
    }

    @Test
    void unsupportedProtocolVersion_throwsUnsupportedProtocolVersionError() {
        Map<String, Object> meta = new HashMap<>();
        meta.put(McpMeta.PROTOCOL_VERSION, "2025-06-18");
        meta.put(McpMeta.CLIENT_CAPABILITIES, Map.of());
        Map<String, Object> params = new HashMap<>();
        params.put("_meta", meta);

        // Header/body agree with each other (both "2025-06-18") but neither is
        // what this server actually supports — the version-negotiation failure,
        // distinct from a header/body mismatch.
        McpException e = catchMcpException(() ->
                validator.validate("tools/list", params, "2025-06-18", "tools/list", null));
        assertThat(e.getRpcCode()).isEqualTo(-32022);
        assertThat(e.getData()).isEqualTo(Map.of("supported", java.util.List.of(SUPPORTED_VERSION), "requested", "2025-06-18"));
    }

    private static void assertDoesNotThrow(Runnable runnable) {
        runnable.run();
    }

    private static McpException catchMcpException(Runnable runnable) {
        try {
            runnable.run();
        } catch (McpException e) {
            return e;
        }
        throw new AssertionError("Expected McpException but none was thrown");
    }
}
