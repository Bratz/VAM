package com.bank.vam.mcp;

import com.bank.vam.ai.copilot.tools.McpUiDescriptors;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The small, fixed set of Apps SDK widget resources this server serves under
 * {@code ui://widget/*}. Static HTML compiled into the jar — no template
 * engine, no per-request rendering — since each widget's own script does the
 * rendering client-side from {@code window.openai.toolOutput}.
 *
 * <p>Registered by URI (not per-tool) because {@code resources/read}
 * addresses a resource independently of which tool(s) point at it via {@code
 * ui.resourceUri}; today it happens to be a 1:1 mapping (see {@link
 * McpUiDescriptors}), but the MCP resource model doesn't require that.
 */
@Component
public class McpUiResourceRegistry {

    private final Map<String, McpUiResource> byUri = new LinkedHashMap<>();

    public McpUiResourceRegistry() {
        register(McpUiDescriptors.POSITION_SUMMARY, "Position summary card", McpWidgetHtml.positionSummary());
        register(McpUiDescriptors.ACCOUNT_LIST, "Account list card", McpWidgetHtml.accountList());
        register(McpUiDescriptors.SWEEP_STATUS, "Sweep status card", McpWidgetHtml.sweepStatus());
        register(McpUiDescriptors.EXCEPTION_LIST, "Exception / rejection code card", McpWidgetHtml.exceptionList());
    }

    private void register(String uri, String name, String html) {
        byUri.put(uri, new McpUiResource(uri, name, html));
    }

    public List<McpUiResource> all() {
        return List.copyOf(byUri.values());
    }

    public McpUiResource get(String uri) {
        return byUri.get(uri);
    }
}
