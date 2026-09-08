package com.bank.vam.mcp;

/**
 * One MCP resource served under {@code ui://widget/*} — a self-contained
 * Apps SDK / MCP Apps widget document (inline CSS/JS, no external requests).
 * {@code html} is the full document.
 *
 * <p>{@link #mimeType()} is {@code text/html;profile=mcp-app}, not plain
 * {@code text/html} — the ratified MCP Apps spec (2026-01-26) requires
 * exactly this value for a host to recognise the resource as an app and
 * activate its postMessage bridge at all. Missing this was a real live bug:
 * the resource served fine (200, correct bytes) and the widget's own
 * postMessage handshake code was correct, but Claude Desktop never wired up
 * its side of the bridge because the plain {@code text/html} mimeType never
 * triggered it — the {@code ui/initialize} message went out to nobody
 * listening.
 */
public record McpUiResource(String uri, String name, String html) {

    public String mimeType() {
        return "text/html;profile=mcp-app";
    }
}
