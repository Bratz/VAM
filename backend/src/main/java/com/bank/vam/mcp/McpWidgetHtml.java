package com.bank.vam.mcp;

/**
 * Static Apps SDK widget HTML for this server's {@code ui://widget/*}
 * resources. Each document is fully self-contained (inline CSS/JS, no
 * external requests) so no {@code _meta.ui.csp} allow-list entries are
 * needed — the widget's own script reads {@code window.openai.toolOutput}
 * at render time and renders client-side, the same pattern every sample in
 * {@code openai/openai-apps-sdk-examples} uses.
 *
 * <p>Generic MCP clients (Claude, MCP Inspector) never fetch these — they
 * ignore {@code _meta} entirely and render the tool's text summary plus
 * {@code structuredContent} instead. Only a host that recognises {@code
 * ui.resourceUri} / {@code openai/outputTemplate} (ChatGPT's Apps SDK today)
 * calls {@code resources/read} for one of these.
 */
final class McpWidgetHtml {

    private McpWidgetHtml() {
    }

    private static final String STYLE = """
            :root{color-scheme:light dark}
            body{margin:0;font:14px/1.45 ui-sans-serif,system-ui,-apple-system,Segoe UI,Roboto,sans-serif;background:#0f1115;color:#e8eaed;padding:16px}
            .card{border-radius:14px;background:linear-gradient(160deg,#151822,#0c0e13);border:1px solid #242938;padding:18px 20px}
            .label{font-size:11px;letter-spacing:.08em;text-transform:uppercase;color:#7c8aa5}
            .total{font-size:32px;font-weight:600;margin:4px 0 2px;font-variant-numeric:tabular-nums}
            .avail{color:#8fd9a8;font-size:13px}
            table{width:100%;border-collapse:collapse;margin-top:14px;font-size:13px}
            th,td{text-align:left;padding:6px 4px;border-bottom:1px solid #1e2330}
            th{color:#7c8aa5;font-weight:500;font-size:11px;text-transform:uppercase;letter-spacing:.05em}
            td.num{text-align:right;font-variant-numeric:tabular-nums}
            .pill{display:inline-block;padding:1px 8px;border-radius:999px;font-size:11px;font-weight:600}
            .pill.ACTIVE{background:#173829;color:#8fd9a8}
            .pill.PAUSED{background:#3a2f14;color:#ffcf7a}
            .pill.DISABLED,.pill.CLOSED,.pill.BLOCKED{background:#331a1a;color:#ff9b9b}
            .empty{color:#7c8aa5;padding:8px 0}
            """;

    // Plain concatenation, not String.formatted() — the CSS in STYLE contains literal
    // '%' characters (e.g. "width:100%") that .formatted() would try to parse as
    // format specifiers and throw on.
    private static String document(String renderFn) {
        return "<!doctype html>\n<meta charset=\"utf-8\">\n<style>" + STYLE + "</style>\n"
                + "<div class=\"card\" id=\"root\">Loading…</div>\n<script>\n"
                + renderFn
                + "\nfunction boot(){ try { render(window.openai && window.openai.toolOutput); } catch (e) { document.getElementById('root').textContent = 'Unable to render.'; } }\n"
                + "if (window.openai) { boot(); } else { window.addEventListener('openai:set_globals', boot); }\n"
                + "document.addEventListener('DOMContentLoaded', boot);\n</script>\n";
    }

    static String positionSummary() {
        return document("""
                function fmt(n){ return Number(n||0).toLocaleString(undefined,{minimumFractionDigits:2,maximumFractionDigits:2}); }
                function render(d){
                  var root=document.getElementById('root');
                  if(!d){ root.textContent='No position data.'; return; }
                  var rows=(d.breakdown||[]).map(function(b){
                    return '<tr><td>'+b.key+(b.isHomeBank?' <span class="label">HOME</span>':'')+'</td><td class="num">'+b.count+'</td><td class="num">'+fmt(b.current)+'</td><td class="num">'+fmt(b.available)+'</td></tr>';
                  }).join('');
                  root.innerHTML =
                    '<div class="label">'+d.currency+' position &middot; '+d.accountCount+' account(s)</div>'+
                    '<div class="total">'+fmt(d.totalCurrent)+'</div>'+
                    '<div class="avail">'+fmt(d.totalAvailable)+' available</div>'+
                    (rows ? '<table><thead><tr><th>'+(d.groupBy==='bank'?'Bank':'Entity')+'</th><th>Accts</th><th>Current</th><th>Available</th></tr></thead><tbody>'+rows+'</tbody></table>' : '<div class="empty">No accounts in this currency.</div>');
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
                    return '<tr><td>'+(a.vaNumber||'')+'<div class="label">'+(a.vaName||'')+'</div></td><td>'+(a.currency||'')+'</td><td><span class="pill '+(a.status||'')+'">'+(a.status||'')+'</span></td><td class="num">'+fmt(a.currentBalance)+'</td><td class="num">'+fmt(a.availableBalance)+'</td></tr>';
                  }).join('');
                  root.innerHTML =
                    '<div class="label">'+d.returned+' of '+d.totalMatched+' account(s)'+(d.currency?' &middot; '+d.currency:'')+'</div>'+
                    '<table><thead><tr><th>Account</th><th>Ccy</th><th>Status</th><th>Current</th><th>Available</th></tr></thead><tbody>'+rows+'</tbody></table>';
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
                    return '<tr><td>'+(r.ruleName||r.ruleReference||'')+'</td><td><span class="pill '+(r.status||'')+'">'+(r.status||'')+'</span></td><td>'+(r.sweepType||'')+'</td><td>'+(r.currencyCode||'')+'</td><td class="num">'+fmt(r.totalSwept)+'</td></tr>';
                  }).join('');
                  root.innerHTML =
                    '<div class="label">'+d.totalRules+' rule(s) &middot; '+d.activeCount+' active, '+d.pausedCount+' paused, '+d.disabledCount+' disabled</div>'+
                    (rows ? '<table><thead><tr><th>Rule</th><th>Status</th><th>Type</th><th>Ccy</th><th>Swept</th></tr></thead><tbody>'+rows+'</tbody></table>' : '<div class="empty">No sweep rules.</div>');
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
                      return '<tr><td>'+c.code+'</td><td>'+(c.category||'')+'</td><td>'+(c.description||'')+'</td></tr>';
                    }).join('');
                    root.innerHTML =
                      '<div class="label">'+d.totalMatched+' rejection code(s)'+(d.category?' &middot; '+d.category:'')+'</div>'+
                      (rows ? '<table><thead><tr><th>Code</th><th>Category</th><th>Description</th></tr></thead><tbody>'+rows+'</tbody></table>' : '<div class="empty">No codes matched.</div>');
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
