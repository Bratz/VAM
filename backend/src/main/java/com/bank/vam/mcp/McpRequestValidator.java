package com.bank.vam.mcp;

import java.util.List;
import java.util.Map;

/**
 * Header/body cross-validation for MCP 2026-07-28's Streamable HTTP transport.
 * Pure — no Spring dependencies — so it's unit-testable without a web context,
 * matching {@code IntentRouter}'s style in this codebase.
 *
 * <p>Enforces, in order (each throwing {@link McpException} with the exact
 * HTTP status + JSON-RPC code the spec requires):
 * <ol>
 *   <li>{@code Mcp-Method} header present and equal to the body's {@code method}</li>
 *   <li>{@code Mcp-Name} header present and equal to {@code params.name}
 *       ({@code tools/call}) or {@code params.uri} ({@code resources/read})</li>
 *   <li>{@code params._meta} present with the two required fields
 *       ({@link McpMeta#PROTOCOL_VERSION}, {@link McpMeta#CLIENT_CAPABILITIES})</li>
 *   <li>{@code MCP-Protocol-Version} header present and equal to the
 *       {@code _meta} protocol version</li>
 *   <li>that protocol version is the one this server actually speaks</li>
 * </ol>
 */
public class McpRequestValidator {

    private final String supportedProtocolVersion;

    public McpRequestValidator(String supportedProtocolVersion) {
        this.supportedProtocolVersion = supportedProtocolVersion;
    }

    @SuppressWarnings("unchecked")
    public void validate(String method, Map<String, Object> params,
                          String protocolVersionHeader, String mcpMethodHeader, String mcpNameHeader) {

        if (mcpMethodHeader == null) {
            throw McpException.headerMismatch("Missing required header: Mcp-Method");
        }
        if (!mcpMethodHeader.equals(method)) {
            throw McpException.headerMismatch(
                    "Mcp-Method header value '" + mcpMethodHeader + "' does not match body method '" + method + "'");
        }

        String mcpNameBodyField = switch (method) {
            case "tools/call" -> "name";
            case "resources/read" -> "uri";
            default -> null;
        };
        if (mcpNameBodyField != null) {
            Object nameParam = params.get(mcpNameBodyField);
            if (mcpNameHeader == null) {
                throw McpException.headerMismatch("Missing required header: Mcp-Name");
            }
            if (!mcpNameHeader.equals(nameParam)) {
                throw McpException.headerMismatch(
                        "Mcp-Name header value '" + mcpNameHeader + "' does not match body value '" + nameParam + "'");
            }
        }

        Object metaRaw = params.get("_meta");
        if (!(metaRaw instanceof Map)) {
            throw McpException.invalidParams("Missing required params._meta");
        }
        Map<String, Object> meta = (Map<String, Object>) metaRaw;

        Object bodyProtocolVersion = meta.get(McpMeta.PROTOCOL_VERSION);
        if (!(bodyProtocolVersion instanceof String) || ((String) bodyProtocolVersion).isBlank()) {
            throw McpException.invalidParams("Missing required _meta field: " + McpMeta.PROTOCOL_VERSION);
        }
        if (!meta.containsKey(McpMeta.CLIENT_CAPABILITIES)) {
            throw McpException.invalidParams("Missing required _meta field: " + McpMeta.CLIENT_CAPABILITIES);
        }

        if (protocolVersionHeader == null) {
            throw McpException.headerMismatch("Missing required header: MCP-Protocol-Version");
        }
        if (!protocolVersionHeader.equals(bodyProtocolVersion)) {
            throw McpException.headerMismatch(
                    "MCP-Protocol-Version header '" + protocolVersionHeader
                            + "' does not match _meta value '" + bodyProtocolVersion + "'");
        }

        if (!supportedProtocolVersion.equals(protocolVersionHeader)) {
            throw McpException.unsupportedProtocolVersion(protocolVersionHeader, List.of(supportedProtocolVersion));
        }
    }
}
