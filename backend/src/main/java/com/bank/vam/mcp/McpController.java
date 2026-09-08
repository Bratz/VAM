package com.bank.vam.mcp;

import com.bank.vam.ai.copilot.tools.CopilotTool;
import com.bank.vam.ai.copilot.tools.ToolContext;
import com.bank.vam.ai.copilot.tools.ToolRegistry;
import com.bank.vam.mcp.McpSignedContextVerifier.VerifiedCaller;
import com.bank.vam.mcp.pipeline.McpToolPipeline;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The MCP endpoint: {@code POST /mcp}. Speaks MCP protocol 2026-07-28 only —
 * stateless (no {@code initialize} handshake, no session), per-request
 * {@code _meta}, {@code Mcp-Method}/{@code Mcp-Name}/{@code MCP-Protocol-Version}
 * headers cross-validated against the body by {@link McpRequestValidator}.
 *
 * <p>Implements exactly the RPCs an enquiry-only server needs: {@code
 * server/discover} (required by spec), {@code tools/list}, {@code
 * tools/call}, and — for the small set of tools with an Apps SDK card (see
 * {@link com.bank.vam.ai.copilot.tools.McpUiDescriptors}) — {@code
 * resources/list} and {@code resources/read}. Every other method —
 * including anything from an earlier, handshake-based protocol era — gets
 * {@code Method not found}; this server does not attempt dual-era backward
 * compatibility (see docs/mcp-architecture.md's Phase 1 notes on why: both
 * target clients, ChatGPT and Claude, are confirmed on 2026-07-28).
 *
 * <p>Responses are always a single JSON object ({@code Content-Type:
 * application/json}), never an SSE stream — every tool here is a fast,
 * synchronous read with nothing to stream progress on.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class McpController {

    private static final String SERVER_NAME = "aperture-mcp-server";
    private static final String SERVER_VERSION = "0.1.0";

    private static final String SIGNED_CONTEXT_META_KEY = "com.aperture.gateway/signedContext";

    private final McpProperties properties;
    private final ToolRegistry toolRegistry;
    private final McpToolPipeline pipeline;
    private final McpSignedContextVerifier signedContextVerifier;
    private final McpUiResourceRegistry uiResources;

    @PostMapping(value = "/mcp", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> handle(
            @RequestHeader(value = HttpHeaders.ORIGIN, required = false) String origin,
            @RequestHeader(value = "MCP-Protocol-Version", required = false) String protocolVersionHeader,
            @RequestHeader(value = "Mcp-Method", required = false) String mcpMethodHeader,
            @RequestHeader(value = "Mcp-Name", required = false) String mcpNameHeader,
            @RequestBody(required = false) Map<String, Object> body) {

        if (!properties.isEnabled()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }

        Object requestId = body == null ? null : body.get("id");
        try {
            validateOrigin(origin);

            if (body == null) {
                throw McpException.parseError("Request body must be a JSON-RPC object");
            }

            // A JSON-RPC *notification* has no "id" key at all — accept and stop.
            // No client-to-server notification is defined by the core protocol
            // over Streamable HTTP (see transport spec), so there is nothing to
            // process; 202 with no body is the spec-compliant response either way.
            if (!body.containsKey("id")) {
                return ResponseEntity.status(HttpStatus.ACCEPTED).build();
            }

            String method = asString(body.get("method"));
            if (method == null || method.isBlank()) {
                throw McpException.invalidRequest("Missing required field: method");
            }
            Map<String, Object> params = asMap(body.get("params"));

            new McpRequestValidator(properties.getProtocolVersion())
                    .validate(method, params, protocolVersionHeader, mcpMethodHeader, mcpNameHeader);

            Map<String, Object> result = dispatch(method, params);
            return ResponseEntity.ok(successEnvelope(requestId, result));

        } catch (McpException e) {
            return ResponseEntity.status(e.getHttpStatus()).body(errorEnvelope(requestId, e));
        } catch (Exception e) {
            log.error("Unhandled MCP request error", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(errorEnvelope(requestId, McpException.internalError("Internal server error")));
        }
    }

    // ========================================================================
    // METHOD DISPATCH
    // ========================================================================

    private Map<String, Object> dispatch(String method, Map<String, Object> params) {
        return switch (method) {
            case "server/discover" -> discoverResult();
            case "tools/list" -> toolsListResult();
            case "tools/call" -> callTool(params);
            case "resources/list" -> resourcesListResult();
            case "resources/read" -> resourcesReadResult(params);
            default -> throw McpException.methodNotFound(method);
        };
    }

    private Map<String, Object> callTool(Map<String, Object> params) {
        String toolName = asString(params.get("name"));
        if (toolName == null || toolName.isBlank()) {
            throw McpException.invalidParams("Missing required field: params.name");
        }
        Map<String, Object> arguments = asMap(params.get("arguments"));
        ToolContext context = buildContextForCall(params, toolName);
        return pipeline.call(toolName, arguments, context);
    }

    /**
     * A present signed context is always verified, regardless of {@link
     * McpProperties#isRequireSignedContext()} — that flag only decides what
     * happens when there is NONE at all: reject (once the gateway is
     * actually deployed and required) or fall back to today's unscoped
     * behaviour (no gateway yet / in-app copilot parity).
     */
    private ToolContext buildContextForCall(Map<String, Object> params, String toolName) {
        String signedContext = extractSignedContext(params);
        if (signedContext == null) {
            if (properties.isRequireSignedContext()) {
                throw McpException.missingSignedContext();
            }
            return toolRegistry.buildContext(null, "mcp-unauthenticated", null);
        }
        VerifiedCaller verified = signedContextVerifier.verify(signedContext, toolName);
        return toolRegistry.buildContext(verified.activeCorporateId(), verified.caller().subject(), null)
                .withCaller(verified.caller());
    }

    @SuppressWarnings("unchecked")
    private String extractSignedContext(Map<String, Object> params) {
        Object metaRaw = params.get("_meta");
        if (!(metaRaw instanceof Map)) {
            return null;
        }
        Object value = ((Map<String, Object>) metaRaw).get(SIGNED_CONTEXT_META_KEY);
        return value instanceof String ? (String) value : null;
    }

    private Map<String, Object> toolsListResult() {
        List<Map<String, Object>> tools = toolRegistry.all().stream()
                .filter(tool -> !tool.isMutating())
                .sorted(Comparator.comparing(CopilotTool::name))
                .map(this::toolDescriptor)
                .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("resultType", "complete");
        result.put("tools", tools);
        // Short TTL and "private" scope: Phase 2 onward, the set a caller sees can
        // narrow by entitlement, so an intermediary must not serve this response
        // to a different caller from cache.
        result.put("ttlMs", 60_000);
        result.put("cacheScope", "private");
        result.put("_meta", serverInfoMeta());
        return result;
    }

    private Map<String, Object> toolDescriptor(CopilotTool tool) {
        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("name", tool.name());
        descriptor.put("description", tool.description());
        descriptor.put("inputSchema", tool.inputSchema());
        Map<String, Object> uiMeta = tool.uiComponent();
        if (uiMeta != null) {
            descriptor.put("_meta", uiMeta);
        }
        return descriptor;
    }

    // ========================================================================
    // RESOURCES (Apps SDK UI widgets — see McpUiResourceRegistry)
    // ========================================================================

    private Map<String, Object> resourcesListResult() {
        List<Map<String, Object>> resources = uiResources.all().stream()
                .map(this::resourceDescriptor)
                .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("resultType", "complete");
        result.put("resources", resources);
        // Same reasoning as tools/list: this set never changes at runtime, but keep
        // it short-lived/private rather than assuming a static-forever contract.
        result.put("ttlMs", 3_600_000);
        result.put("cacheScope", "public");
        result.put("_meta", serverInfoMeta());
        return result;
    }

    private Map<String, Object> resourceDescriptor(McpUiResource resource) {
        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("uri", resource.uri());
        descriptor.put("name", resource.name());
        descriptor.put("mimeType", resource.mimeType());
        return descriptor;
    }

    private Map<String, Object> resourcesReadResult(Map<String, Object> params) {
        String uri = asString(params.get("uri"));
        if (uri == null || uri.isBlank()) {
            throw McpException.invalidParams("Missing required field: params.uri");
        }
        McpUiResource resource = uiResources.get(uri);
        if (resource == null) {
            throw McpException.resourceNotFound(uri);
        }

        Map<String, Object> content = new LinkedHashMap<>();
        content.put("uri", resource.uri());
        content.put("mimeType", resource.mimeType());
        content.put("text", resource.html());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("resultType", "complete");
        result.put("contents", List.of(content));
        // Same CacheableResult fields as resources/list and tools/list — missing
        // here specifically broke a live Claude Desktop session with a client-side
        // schema validation error ("expected number, received undefined" on
        // ttlMs), since Claude's MCP client validates resources/read results
        // against the same CacheableResult shape, not just list endpoints. The
        // widget HTML is compiled into the jar and never changes at runtime, so
        // the same long TTL / public scope as resources/list applies.
        result.put("ttlMs", 3_600_000);
        result.put("cacheScope", "public");
        result.put("_meta", serverInfoMeta());
        return result;
    }

    private Map<String, Object> discoverResult() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("resultType", "complete");
        result.put("supportedVersions", List.of(properties.getProtocolVersion()));
        // Enquiry-only: tools + the fixed set of Apps SDK UI resources those tools
        // point at. No prompts capability — this server doesn't implement prompts/get.
        result.put("capabilities", Map.of("tools", Map.of(), "resources", Map.of()));
        result.put("instructions",
                "Aperture treasury enquiry tools: read-only balance, pooling, sweep, and "
                        + "audit data for the caller's entitled corporate(s). No tool here mutates state. "
                        + "Some tools point at a ui://widget/* resource (see resources/list) for richer "
                        + "rendering on hosts that support it; all tools also return plain structuredContent.");
        result.put("ttlMs", 3_600_000);
        result.put("cacheScope", "public");
        result.put("_meta", serverInfoMeta());
        return result;
    }

    private Map<String, Object> serverInfoMeta() {
        Map<String, Object> serverInfo = new LinkedHashMap<>();
        serverInfo.put("name", SERVER_NAME);
        serverInfo.put("version", SERVER_VERSION);
        return Map.of(McpMeta.SERVER_INFO, serverInfo);
    }

    // ========================================================================
    // ENVELOPE / VALIDATION HELPERS
    // ========================================================================

    private void validateOrigin(String origin) {
        if (origin == null) {
            return; // server-to-server MCP clients typically don't send one — nothing to check
        }
        if (!properties.getAllowedOrigins().contains(origin)) {
            throw McpException.forbiddenOrigin(origin);
        }
    }

    private Map<String, Object> successEnvelope(Object id, Map<String, Object> result) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("jsonrpc", "2.0");
        envelope.put("id", id);
        envelope.put("result", result);
        return envelope;
    }

    private Map<String, Object> errorEnvelope(Object id, McpException e) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", e.getRpcCode());
        error.put("message", e.getMessage());
        if (e.getData() != null) {
            error.put("data", e.getData());
        }
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("jsonrpc", "2.0");
        if (id != null) {
            envelope.put("id", id);
        }
        envelope.put("error", error);
        return envelope;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    private String asString(Object value) {
        return value instanceof String s ? s : null;
    }
}
