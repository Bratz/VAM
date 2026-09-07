package com.bank.vam.ai.copilot.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Apps SDK tool-descriptor {@code _meta} builder, plus the fixed URIs of this
 * server's {@code ui://widget/*} resources.
 *
 * <p>The URIs live here — in the tools package — rather than in {@code
 * com.bank.vam.mcp}, so a tool's {@link CopilotTool#uiComponent()} doesn't
 * need the lower-level tools package to depend back on the MCP transport
 * layer. {@code McpUiResourceRegistry} (in {@code com.bank.vam.mcp}) imports
 * these same constants instead of redeclaring them, so the two sides can't
 * drift apart.
 *
 * <p>Shape confirmed against the current Apps SDK reference (developers.openai.com/apps-sdk/reference,
 * Sept 2026): {@code _meta.ui.resourceUri} is the standard MCP Apps key;
 * {@code _meta["openai/outputTemplate"]} is ChatGPT's compatibility alias for
 * the same URI. Claude and other generic MCP clients ignore both and fall
 * back to the tool's text summary + {@code structuredContent}.
 */
public final class McpUiDescriptors {

    public static final String POSITION_SUMMARY = "ui://widget/position-summary.html";
    public static final String ACCOUNT_LIST = "ui://widget/account-list.html";
    public static final String SWEEP_STATUS = "ui://widget/sweep-status.html";
    public static final String EXCEPTION_LIST = "ui://widget/exception-list.html";

    private McpUiDescriptors() {
    }

    public static Map<String, Object> widget(String resourceUri, String invoking, String invoked) {
        Map<String, Object> ui = new LinkedHashMap<>();
        ui.put("resourceUri", resourceUri);
        ui.put("visibility", List.of("model", "app"));

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("ui", ui);
        meta.put("openai/outputTemplate", resourceUri);
        meta.put("openai/toolInvocation/invoking", invoking);
        meta.put("openai/toolInvocation/invoked", invoked);
        meta.put("openai/widgetAccessible", true);
        return meta;
    }
}
