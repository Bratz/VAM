package com.bank.vam.mcp;

/**
 * {@code _meta} key names reserved by MCP 2026-07-28 (see
 * {@code /specification/2026-07-28/basic/index#meta}). Centralised so a future
 * spec bump touches one file, not every call site.
 */
public final class McpMeta {

    public static final String PROTOCOL_VERSION = "io.modelcontextprotocol/protocolVersion";
    public static final String CLIENT_INFO = "io.modelcontextprotocol/clientInfo";
    public static final String CLIENT_CAPABILITIES = "io.modelcontextprotocol/clientCapabilities";
    public static final String SERVER_INFO = "io.modelcontextprotocol/serverInfo";
    public static final String LOG_LEVEL = "io.modelcontextprotocol/logLevel";

    private McpMeta() {
    }
}
