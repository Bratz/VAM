package com.bank.vam.mcp;

/**
 * One MCP resource served under {@code ui://widget/*} — a self-contained
 * Apps SDK widget document (inline CSS/JS, no external requests). {@code
 * html} is the full document; {@link #mimeType()} is always {@code
 * text/html}, since that's the only kind of resource this server serves.
 */
public record McpUiResource(String uri, String name, String html) {

    public String mimeType() {
        return "text/html";
    }
}
