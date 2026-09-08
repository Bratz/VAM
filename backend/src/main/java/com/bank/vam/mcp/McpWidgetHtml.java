package com.bank.vam.mcp;

/**
 * Static Apps SDK / MCP Apps widget HTML for this server's {@code
 * ui://widget/*} resources. Each document is fully self-contained (inline
 * CSS/JS, no external requests) so no {@code _meta.ui.csp} allow-list
 * entries are needed.
 *
 * <p>Speaks two independent bridges, since real-world hosts don't agree on
 * one: ChatGPT's {@code window.openai.toolOutput} global (the
 * openai-apps-sdk-examples pattern), and the actual cross-host MCP Apps
 * standard — a JSON-RPC-over-postMessage handshake ({@code ui/initialize} →
 * {@code ui/notifications/initialized} → {@code
 * ui/notifications/tool-result}) that Claude Desktop, VS Code, and other
 * MCP-Apps-compliant hosts use instead. Confirmed live: Claude fetches these
 * resources (contrary to an earlier assumption that only ChatGPT would), but
 * a widget speaking only {@code window.openai} sits on "Loading…" forever
 * under Claude and the host times out with a generic connector error.
 *
 * <p>Theming (UX audit fix): every color is a CSS custom property, dark by
 * default, overridden under {@code @media (prefers-color-scheme: light)} —
 * a zero-JS fallback that works for any host, including ChatGPT, which has
 * no confirmed theme API of its own to read. A true MCP Apps host's {@code
 * ui/initialize} response carries an explicit {@code hostContext.theme},
 * which — when present — sets a {@code data-theme} attribute that wins over
 * the media-query guess in both directions. {@code
 * hostContext.containerDimensions.maxHeight} drives the one scrollable
 * region (the card itself) instead of letting the host's own page scroll
 * take over.
 */
final class McpWidgetHtml {

    private McpWidgetHtml() {
    }

    private static final String STYLE = """
            :root{
              color-scheme:light dark;
              --bg:#0f1115;
              --card-1:#151822; --card-2:#0c0e13;
              --border:#242938;
              --text:#e8eaed;
              --label:#7c8aa5;
              --avail:#8fd9a8;
              --row-border:#1e2330;
              --empty:#7c8aa5;
              --pill-active-bg:#173829; --pill-active-fg:#8fd9a8;
              --pill-paused-bg:#3a2f14; --pill-paused-fg:#ffcf7a;
              --pill-suspended-bg:#3a2410; --pill-suspended-fg:#ffa94d;
              --pill-pending-bg:#132a3a; --pill-pending-fg:#7ec8ff;
              --pill-inactive-bg:#242938; --pill-inactive-fg:#9aa3b2;
              --pill-disabled-bg:#331a1a; --pill-disabled-fg:#ff9b9b;
              --host-max-height:70vh;
            }
            @media (prefers-color-scheme: light){
              :root:not([data-theme="dark"]){
                --bg:#f7f8fa; --card-1:#ffffff; --card-2:#eef0f4; --border:#dde1e8;
                --text:#1a1d24; --label:#5b6577; --avail:#1a7f4b; --row-border:#e4e7ec; --empty:#8791a1;
                --pill-active-bg:#e3f6ea; --pill-active-fg:#1a7f4b;
                --pill-paused-bg:#fdf1de; --pill-paused-fg:#a15c07;
                --pill-suspended-bg:#fde8d8; --pill-suspended-fg:#a14b07;
                --pill-pending-bg:#e2f0fc; --pill-pending-fg:#155a91;
                --pill-inactive-bg:#eceef2; --pill-inactive-fg:#5b6577;
                --pill-disabled-bg:#fbe4e4; --pill-disabled-fg:#a12727;
              }
            }
            :root[data-theme="light"]{
              --bg:#f7f8fa; --card-1:#ffffff; --card-2:#eef0f4; --border:#dde1e8;
              --text:#1a1d24; --label:#5b6577; --avail:#1a7f4b; --row-border:#e4e7ec; --empty:#8791a1;
              --pill-active-bg:#e3f6ea; --pill-active-fg:#1a7f4b;
              --pill-paused-bg:#fdf1de; --pill-paused-fg:#a15c07;
              --pill-suspended-bg:#fde8d8; --pill-suspended-fg:#a14b07;
              --pill-pending-bg:#e2f0fc; --pill-pending-fg:#155a91;
              --pill-inactive-bg:#eceef2; --pill-inactive-fg:#5b6577;
              --pill-disabled-bg:#fbe4e4; --pill-disabled-fg:#a12727;
            }
            body{margin:0;font:14px/1.45 ui-sans-serif,system-ui,-apple-system,Segoe UI,Roboto,sans-serif;background:var(--bg);color:var(--text);padding:16px}
            .card{border-radius:14px;background:linear-gradient(160deg,var(--card-1),var(--card-2));border:1px solid var(--border);padding:18px 20px;max-height:var(--host-max-height);overflow-y:auto;box-sizing:border-box}
            .label{font-size:11px;letter-spacing:.08em;text-transform:uppercase;color:var(--label)}
            .total{font-size:32px;font-weight:600;margin:4px 0 2px;font-variant-numeric:tabular-nums}
            .avail{color:var(--avail);font-size:13px}
            table{width:100%;table-layout:fixed;border-collapse:collapse;margin-top:14px;font-size:13px}
            th,td{text-align:left;padding:6px 4px;border-bottom:1px solid var(--row-border);overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
            th{color:var(--label);font-weight:500;font-size:11px;text-transform:uppercase;letter-spacing:.05em}
            td.num{text-align:right;font-variant-numeric:tabular-nums}
            .pill{display:inline-block;padding:1px 8px;border-radius:999px;font-size:11px;font-weight:600;white-space:nowrap}
            .pill.ACTIVE{background:var(--pill-active-bg);color:var(--pill-active-fg)}
            .pill.PAUSED{background:var(--pill-paused-bg);color:var(--pill-paused-fg)}
            .pill.SUSPENDED{background:var(--pill-suspended-bg);color:var(--pill-suspended-fg)}
            .pill.PENDING_ACTIVATION{background:var(--pill-pending-bg);color:var(--pill-pending-fg)}
            .pill.INACTIVE{background:var(--pill-inactive-bg);color:var(--pill-inactive-fg)}
            .pill.DISABLED,.pill.CLOSED,.pill.BLOCKED,.pill.EXPIRED{background:var(--pill-disabled-bg);color:var(--pill-disabled-fg)}
            .empty{color:var(--empty);padding:8px 0}
            """;

    // Plain concatenation, not String.formatted() — the CSS in STYLE contains literal
    // '%' characters (e.g. "width:100%") that .formatted() would try to parse as
    // format specifiers and throw on.
    private static String document(String renderFn) {
        return "<!doctype html>\n<meta charset=\"utf-8\">\n<style>" + STYLE + "</style>\n"
                + "<div class=\"card\" id=\"root\">Loading…</div>\n<script>\n"
                + "function attr(s){ return String(s==null?'':s).replace(/\"/g,'&quot;'); }\n"
                + renderFn
                + "\nfunction __renderOnce(d){ if (window.__apertureRendered) return; window.__apertureRendered = true; try { render(d); } catch (e) { document.getElementById('root').textContent = 'Unable to render.'; } }\n"
                + "function __applyHostContext(hostContext){\n"
                + "  if (!hostContext) return;\n"
                + "  if (hostContext.theme === 'light' || hostContext.theme === 'dark') {\n"
                + "    document.documentElement.setAttribute('data-theme', hostContext.theme);\n"
                + "  }\n"
                + "  var maxH = hostContext.containerDimensions && hostContext.containerDimensions.maxHeight;\n"
                + "  if (maxH) { document.documentElement.style.setProperty('--host-max-height', maxH + 'px'); }\n"
                + "}\n"
                // ChatGPT Apps SDK bridge — no confirmed theme/dimensions API here, so this
                // path relies on the @media (prefers-color-scheme) CSS fallback only.
                + "function __bootOpenAi(){ if (window.openai) { __renderOnce(window.openai.toolOutput); } }\n"
                + "if (window.openai) { __bootOpenAi(); } else { window.addEventListener('openai:set_globals', __bootOpenAi); }\n"
                // MCP Apps postMessage bridge (Claude Desktop, VS Code, and other
                // ui/initialize-speaking hosts) — see McpWidgetHtml's class javadoc.
                + "(function(){\n"
                + "  window.addEventListener('message', function(event){\n"
                + "    var msg = event.data;\n"
                + "    if (!msg) return;\n"
                + "    if (msg.id === 1 && msg.result) {\n"
                + "      __applyHostContext(msg.result.hostContext);\n"
                + "      window.parent.postMessage({ jsonrpc: '2.0', method: 'ui/notifications/initialized', params: {} }, '*');\n"
                + "    } else if (msg.method === 'ui/notifications/tool-result') {\n"
                + "      __renderOnce(msg.params && msg.params.structuredContent);\n"
                + "    }\n"
                + "  });\n"
                + "  try {\n"
                + "    window.parent.postMessage({\n"
                + "      jsonrpc: '2.0', id: 1, method: 'ui/initialize',\n"
                + "      params: {\n"
                + "        appCapabilities: { availableDisplayModes: ['inline'] },\n"
                + "        clientInfo: { name: 'aperture-widget', version: '0.1.0' },\n"
                + "        protocolVersion: '2026-01-26'\n"
                + "      }\n"
                + "    }, '*');\n"
                + "  } catch (e) {}\n"
                + "})();\n</script>\n";
    }

    static String positionSummary() {
        return document("""
                function fmt(n){ return Number(n||0).toLocaleString(undefined,{minimumFractionDigits:2,maximumFractionDigits:2}); }
                function render(d){
                  var root=document.getElementById('root');
                  if(!d){ root.textContent='No position data.'; return; }
                  var rows=(d.breakdown||[]).map(function(b){
                    return '<tr><td title="'+attr(b.key)+'">'+b.key+(b.isHomeBank?' <span class="label">HOME</span>':'')+'</td><td class="num">'+b.count+'</td><td class="num">'+fmt(b.current)+'</td><td class="num">'+fmt(b.available)+'</td></tr>';
                  }).join('');
                  root.innerHTML =
                    '<div class="label">'+d.currency+' position &middot; '+d.accountCount+' account(s)</div>'+
                    '<div class="total">'+fmt(d.totalCurrent)+'</div>'+
                    '<div class="avail">'+fmt(d.totalAvailable)+' available</div>'+
                    (rows ? '<table><colgroup><col style="width:40%"><col style="width:12%"><col style="width:24%"><col style="width:24%"></colgroup><thead><tr><th>'+(d.groupBy==='bank'?'Bank':'Entity')+'</th><th>Accts</th><th>Current</th><th>Available</th></tr></thead><tbody>'+rows+'</tbody></table>' : '<div class="empty">No accounts in this currency.</div>');
                }
                """);
    }

    static String accountList() {
        return document("""
                function fmt(n){ return Number(n||0).toLocaleString(undefined,{minimumFractionDigits:2,maximumFractionDigits:2}); }
                function render(d){
                  var root=document.getElementById('root');
                  var accounts=(d&&d.accounts)||[];
                  if(!accounts.length){ root.innerHTML='<div class="empty">No accounts matched.</div>'; return; }
                  var rows=accounts.map(function(a){
                    return '<tr><td title="'+attr(a.vaName)+'">'+(a.vaNumber||'')+'<div class="label">'+(a.vaName||'')+'</div></td><td>'+(a.currency||'')+'</td><td><span class="pill '+(a.status||'')+'">'+(a.status||'')+'</span></td><td class="num">'+fmt(a.currentBalance)+'</td><td class="num">'+fmt(a.availableBalance)+'</td></tr>';
                  }).join('');
                  root.innerHTML =
                    '<div class="label">'+d.returned+' of '+d.totalMatched+' account(s)'+(d.currency?' &middot; '+d.currency:'')+'</div>'+
                    '<table><colgroup><col style="width:32%"><col style="width:10%"><col style="width:16%"><col style="width:21%"><col style="width:21%"></colgroup><thead><tr><th>Account</th><th>Ccy</th><th>Status</th><th>Current</th><th>Available</th></tr></thead><tbody>'+rows+'</tbody></table>';
                }
                """);
    }

    static String sweepStatus() {
        return document("""
                function fmt(n){ return Number(n||0).toLocaleString(undefined,{minimumFractionDigits:2,maximumFractionDigits:2}); }
                function render(d){
                  var root=document.getElementById('root');
                  if(!d){ root.textContent='No sweep data.'; return; }
                  var rules=d.rules||[];
                  var rows=rules.map(function(r){
                    var name=r.ruleName||r.ruleReference||'';
                    return '<tr><td title="'+attr(name)+'">'+name+'</td><td><span class="pill '+(r.status||'')+'">'+(r.status||'')+'</span></td><td>'+(r.sweepType||'')+'</td><td>'+(r.currencyCode||'')+'</td><td class="num">'+fmt(r.totalSwept)+'</td></tr>';
                  }).join('');
                  root.innerHTML =
                    '<div class="label">'+d.totalRules+' rule(s) &middot; '+d.activeCount+' active, '+d.pausedCount+' paused, '+d.disabledCount+' disabled</div>'+
                    (rows ? '<table><colgroup><col style="width:30%"><col style="width:14%"><col style="width:18%"><col style="width:10%"><col style="width:28%"></colgroup><thead><tr><th>Rule</th><th>Status</th><th>Type</th><th>Ccy</th><th>Swept</th></tr></thead><tbody>'+rows+'</tbody></table>' : '<div class="empty">No sweep rules.</div>');
                }
                """);
    }

    static String exceptionList() {
        return document("""
                function render(d){
                  var root=document.getElementById('root');
                  if(!d){ root.textContent='No rejection code data.'; return; }
                  if(d.codes){
                    var rows=d.codes.map(function(c){
                      return '<tr><td>'+c.code+'</td><td>'+(c.category||'')+'</td><td title="'+attr(c.description)+'">'+(c.description||'')+'</td></tr>';
                    }).join('');
                    root.innerHTML =
                      '<div class="label">'+d.totalMatched+' rejection code(s)'+(d.category?' &middot; '+d.category:'')+'</div>'+
                      (rows ? '<table><colgroup><col style="width:16%"><col style="width:20%"><col style="width:64%"></colgroup><thead><tr><th>Code</th><th>Category</th><th>Description</th></tr></thead><tbody>'+rows+'</tbody></table>' : '<div class="empty">No codes matched.</div>');
                  } else {
                    root.innerHTML =
                      '<div class="label">Rejection code</div>'+
                      '<div class="total" style="font-size:28px">'+d.code+'</div>'+
                      '<div class="label">'+(d.category||'')+'</div>'+
                      '<div style="margin-top:8px">'+(d.description||(d.known===false ? 'Unknown code &mdash; not in the reference table.' : ''))+'</div>';
                  }
                }
                """);
    }
}
