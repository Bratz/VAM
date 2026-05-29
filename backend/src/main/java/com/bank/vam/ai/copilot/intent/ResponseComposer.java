package com.bank.vam.ai.copilot.intent;

import com.bank.vam.ai.copilot.tools.ToolResult;
import com.bank.vam.config.MarketProfileProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Builds the markdown reply for a matched intent.
 *
 * <p>Per-intent compose methods read the relevant {@link ToolResult}(s) and
 * produce well-formatted markdown (headings, bold, tables, blockquotes)
 * for the frontend to render. Currency values are formatted using the
 * locale from the active {@link MarketProfileProperties} so the same
 * intent demos correctly in UAE vs UK profiles.
 *
 * <p>Design choice: <b>programmatic templates inside Java</b> (not external
 * Mustache files) for the prototype. Trade-off: less flexible, but
 * easier to debug and ship. If non-engineers ever need to edit replies,
 * extract these to resource files behind a tiny renderer.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResponseComposer {

    private final MarketProfileProperties marketProfile;

    public ComposedReply compose(IntentMatch match, List<ToolResult> toolResults) {
        Intent intent = match.intent();
        String text = switch (intent) {
            case GREETING -> composeGreeting();
            case GET_POSITION -> composePosition(toolResults, false);
            case GET_POSITION_BY_BANK -> composePosition(toolResults, true);
            case FAILED_SWEEPS -> composeFailedSweeps(toolResults);
            case EXPLAIN_REJECTION -> composeExplainRejection(match, toolResults);
            case IDLE_ACCOUNTS -> composeIdleAccounts(toolResults);
            case RECENT_ACTIVITY -> composeRecentActivity(toolResults);
            case LIST_RULES -> composeListRules(toolResults);
            case STATEMENT_SUMMARY -> composeStatementSummary(toolResults);
            case PAUSE_RULE -> composePauseRule(match, toolResults);
            case SET_ALERT -> composeSetAlert(match, toolResults);
            case UNKNOWN -> composeUnknown(match);
        };
        return new ComposedReply(text, intent.name().toLowerCase());
    }

    // ========================================================================
    // GREETING
    // ========================================================================

    private String composeGreeting() {
        String ccy = marketProfile.getDefaultCurrency();
        return """
                Hi — I'm **Treasury Copilot**, the AI assistant inside **Aperture**.

                I can help with:

                - 💰 **Cash position** — "What's our %s position?" or "%s position by bank"
                - 🔁 **Sweep activity** — "List sweep rules", "Show failed sweeps in the last 24 hours"
                - ❓ **Failures** — "Why did SI-001 fail?", "Explain AM04"
                - 📜 **Statements** — "Today's %s statement"
                - 🕒 **Recent activity** — "What happened today?"
                - ⏸ **Actions** — "Pause rule IHB-MNC-UAE-AED", "Alert me if VA-DUBAI-001 drops below 50000"

                Try one of the suggestions above, or type your own.
                """.formatted(ccy, ccy, ccy);
    }

    // ========================================================================
    // GET_POSITION / GET_POSITION_BY_BANK
    // ========================================================================

    private String composePosition(List<ToolResult> results, boolean byBank) {
        Optional<ToolResult> result = findResult(results, "get_position");
        if (result.isEmpty() || !result.get().ok()) {
            return errorReply("position", result.map(ToolResult::errorMessage).orElse("no result"));
        }
        Map<String, Object> data = result.get().data();
        String currency = String.valueOf(data.getOrDefault("currency", marketProfile.getDefaultCurrency()));
        BigDecimal totalCurrent = asBigDecimal(data.get("totalCurrent"));
        BigDecimal totalAvailable = asBigDecimal(data.get("totalAvailable"));
        int accountCount = ((Number) data.getOrDefault("accountCount", 0)).intValue();

        StringBuilder sb = new StringBuilder();
        sb.append("Your **").append(currency).append("** position across all entities is **")
                .append(formatCurrency(totalCurrent, currency)).append("**");
        if (totalAvailable != null && !totalAvailable.equals(totalCurrent)) {
            sb.append(" (available: ").append(formatCurrency(totalAvailable, currency)).append(")");
        }
        sb.append(".\n\n");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> breakdown = (List<Map<String, Object>>) data.get("breakdown");
        if (breakdown != null && !breakdown.isEmpty()) {
            sb.append("By **").append(byBank ? "bank" : "entity").append("**:\n\n");
            sb.append("| ").append(byBank ? "Bank (BIC)" : "Entity").append(" | Accounts | Current | Available |\n");
            sb.append("|---|---:|---:|---:|\n");
            for (Map<String, Object> row : breakdown) {
                String key = String.valueOf(row.get("key"));
                if (byBank && Boolean.TRUE.equals(row.get("isHomeBank"))) {
                    key = "🏦 " + key + " *(home)*";
                }
                int count = ((Number) row.getOrDefault("count", 0)).intValue();
                BigDecimal current = asBigDecimal(row.get("current"));
                BigDecimal available = asBigDecimal(row.get("available"));
                sb.append("| ").append(key).append(" | ").append(count)
                        .append(" | ").append(formatCurrency(current, currency))
                        .append(" | ").append(formatCurrency(available, currency))
                        .append(" |\n");
            }
            sb.append("\n");
        }

        sb.append("_Pulled from ").append(accountCount).append(" account")
                .append(accountCount == 1 ? "" : "s").append("._");
        return sb.toString();
    }

    // ========================================================================
    // FAILED_SWEEPS
    // ========================================================================

    private String composeFailedSweeps(List<ToolResult> results) {
        Optional<ToolResult> result = findResult(results, "get_sweep_instructions");
        if (result.isEmpty() || !result.get().ok()) {
            return errorReply("failed sweeps", result.map(ToolResult::errorMessage).orElse("no result"));
        }
        Map<String, Object> data = result.get().data();
        int total = ((Number) data.getOrDefault("totalMatched", 0)).intValue();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("instructions");

        if (total == 0) {
            return "Good news — **no failed sweep instructions** in the window. ✅";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("**").append(total).append("** rejected sweep ").append(total == 1 ? "instruction" : "instructions")
                .append(" in the window:\n\n");
        sb.append("| Instruction | Amount | Rail | Code | Reason | Category |\n");
        sb.append("|---|---:|---|---|---|---|\n");
        for (Map<String, Object> row : rows) {
            sb.append("| `").append(safe(row.get("idempotencyKey"))).append("`")
                    .append(" | ").append(formatCurrency(asBigDecimal(row.get("amount")), safe(row.get("currency"))))
                    .append(" | ").append(safe(row.get("rail")))
                    .append(" | `").append(safe(row.get("rejectionCode"))).append("`")
                    .append(" | ").append(truncate(safe(row.get("rejectionReason")), 50))
                    .append(" | ").append(categoryBadge(safe(row.get("rejectionCategory"))))
                    .append(" |\n");
        }
        sb.append("\n_Try \"Why did ").append(safe(rows.get(0).get("idempotencyKey")))
                .append(" fail?\" for the full story on any one._");
        return sb.toString();
    }

    // ========================================================================
    // EXPLAIN_REJECTION
    // ========================================================================

    private String composeExplainRejection(IntentMatch match, List<ToolResult> results) {
        Optional<ToolResult> instructionRes = findResult(results, "get_sweep_instructions");
        Optional<ToolResult> rejectionRes = findResult(results, "get_rejection_codes");

        StringBuilder sb = new StringBuilder();

        // Instruction context (optional — may be absent if user only gave a code)
        if (instructionRes.isPresent() && instructionRes.get().ok()) {
            Map<String, Object> data = instructionRes.get().data();
            int total = ((Number) data.getOrDefault("totalMatched", 0)).intValue();
            if (total == 0) {
                sb.append("⚠️ No instruction found matching `").append(safe(match.slot(Slot.INSTRUCTION_ID)))
                        .append("`.\n\n");
            } else {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("instructions");
                Map<String, Object> row = rows.get(0);
                sb.append("### Instruction `").append(safe(row.get("idempotencyKey"))).append("`\n\n");
                sb.append("- **Status:** ").append(safe(row.get("status"))).append("\n");
                sb.append("- **Amount:** ").append(formatCurrency(asBigDecimal(row.get("amount")), safe(row.get("currency")))).append("\n");
                sb.append("- **Rail:** ").append(safe(row.get("rail"))).append("\n");
                if (row.get("rejectedAt") != null) {
                    sb.append("- **Rejected at:** ").append(safe(row.get("rejectedAt"))).append("\n");
                }
                if (row.get("rejectionReason") != null) {
                    sb.append("- **Reason:** ").append(safe(row.get("rejectionReason"))).append("\n");
                }
                sb.append("\n");
            }
        }

        // Rejection-code classification
        if (rejectionRes.isPresent() && rejectionRes.get().ok()) {
            Map<String, Object> data = rejectionRes.get().data();
            String code = safe(data.get("code"));
            String category = safe(data.get("category"));
            String description = safe(data.get("description"));
            boolean known = Boolean.TRUE.equals(data.get("known"));

            sb.append("### Rejection code `").append(code).append("`\n\n");
            if (known) {
                sb.append("> **").append(description).append("** — classified as **").append(category).append("**.\n\n");
            } else {
                sb.append("> Code is **not in the registry** — defaulted to **").append(category).append("**. Ops should triage and add a description.\n\n");
            }
            if ("RECOVERABLE".equalsIgnoreCase(category)) {
                sb.append("✅ This is a **recoverable** reject — the engine should retry with back-off. No rule pause needed.\n");
            } else if ("UNRECOVERABLE".equalsIgnoreCase(category)) {
                sb.append("⛔️ This is an **unrecoverable** reject — the rule will be auto-paused. Investigate the underlying mandate/account before re-activating.\n");
            }
        }

        if (sb.length() == 0) {
            return "I need either an instruction id (e.g. `SI-001`) or a rejection code (e.g. `AM04`) to explain a failure. Try \"Why did SI-001 fail?\".";
        }
        return sb.toString().trim();
    }

    // ========================================================================
    // IDLE_ACCOUNTS
    // ========================================================================

    private String composeIdleAccounts(List<ToolResult> results) {
        Optional<ToolResult> result = findResult(results, "get_accounts");
        if (result.isEmpty() || !result.get().ok()) {
            return errorReply("idle accounts", result.map(ToolResult::errorMessage).orElse("no result"));
        }
        Map<String, Object> data = result.get().data();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> accounts = (List<Map<String, Object>>) data.get("accounts");

        // Prototype heuristic — "potentially idle" = active accounts ordered by
        // current balance descending. A real query joins with transactions to
        // confirm no outflow in N days; tracked as a P4 follow-up.
        List<Map<String, Object>> ranked = accounts.stream()
                .filter(a -> asBigDecimal(a.get("currentBalance")).compareTo(BigDecimal.ZERO) > 0)
                .sorted((a, b) -> asBigDecimal(b.get("currentBalance"))
                        .compareTo(asBigDecimal(a.get("currentBalance"))))
                .limit(10)
                .toList();

        if (ranked.isEmpty()) {
            return "No accounts with positive balance found in the active scope. Nothing to flag as idle.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Top **").append(ranked.size())
                .append("** accounts ranked by current balance (potentially idle — a real query needs a transaction-level join, P4 follow-up):\n\n");
        sb.append("| Account | Currency | Current | Available |\n");
        sb.append("|---|---|---:|---:|\n");
        for (Map<String, Object> a : ranked) {
            sb.append("| `").append(safe(a.get("vaNumber"))).append("` ")
                    .append(safe(a.get("vaName"))).append(" | ")
                    .append(safe(a.get("currency"))).append(" | ")
                    .append(formatCurrency(asBigDecimal(a.get("currentBalance")), safe(a.get("currency")))).append(" | ")
                    .append(formatCurrency(asBigDecimal(a.get("availableBalance")), safe(a.get("currency"))))
                    .append(" |\n");
        }
        return sb.toString();
    }

    // ========================================================================
    // RECENT_ACTIVITY
    // ========================================================================

    private String composeRecentActivity(List<ToolResult> results) {
        Optional<ToolResult> result = findResult(results, "get_audit_trail");
        if (result.isEmpty() || !result.get().ok()) {
            return errorReply("recent activity", result.map(ToolResult::errorMessage).orElse("no result"));
        }
        Map<String, Object> data = result.get().data();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events = (List<Map<String, Object>>) data.get("events");

        if (events == null || events.isEmpty()) {
            return "No audit events recorded yet in the active scope. Treasury operations will start populating this once governance actions fire.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("**Last ").append(events.size()).append("** audit events:\n\n");
        for (Map<String, Object> e : events) {
            sb.append("- `").append(safe(e.get("createdAt"))).append("` ")
                    .append("**").append(safe(e.get("eventType"))).append("** ")
                    .append("by ").append(safe(e.get("actor")))
                    .append(" — ").append(safe(e.get("summary"))).append("\n");
        }
        return sb.toString();
    }

    // ========================================================================
    // LIST_RULES
    // ========================================================================

    private String composeListRules(List<ToolResult> results) {
        Optional<ToolResult> result = findResult(results, "get_sweep_status");
        if (result.isEmpty() || !result.get().ok()) {
            return errorReply("sweep rules", result.map(ToolResult::errorMessage).orElse("no result"));
        }
        Map<String, Object> data = result.get().data();
        int total = ((Number) data.getOrDefault("totalRules", 0)).intValue();
        int active = ((Number) data.getOrDefault("activeCount", 0)).intValue();
        int paused = ((Number) data.getOrDefault("pausedCount", 0)).intValue();
        int disabled = ((Number) data.getOrDefault("disabledCount", 0)).intValue();

        StringBuilder sb = new StringBuilder();
        sb.append("**").append(total).append("** sweep rule").append(total == 1 ? "" : "s")
                .append(" (active: ").append(active)
                .append(", paused: ").append(paused)
                .append(", disabled: ").append(disabled).append(").\n\n");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rules = (List<Map<String, Object>>) data.get("rules");
        if (rules != null && !rules.isEmpty()) {
            sb.append("| Reference | Name | Type | Status | Frequency | Currency |\n");
            sb.append("|---|---|---|---|---|---|\n");
            for (Map<String, Object> r : rules) {
                sb.append("| `").append(safe(r.get("ruleReference"))).append("`")
                        .append(" | ").append(truncate(safe(r.get("ruleName")), 35))
                        .append(" | ").append(safe(r.get("sweepType")))
                        .append(" | ").append(safe(r.get("status")))
                        .append(" | ").append(safe(r.get("frequency")))
                        .append(" | ").append(safe(r.get("currencyCode")))
                        .append(" |\n");
            }
        }
        return sb.toString();
    }

    // ========================================================================
    // STATEMENT_SUMMARY
    // ========================================================================

    private String composeStatementSummary(List<ToolResult> results) {
        Optional<ToolResult> result = findResult(results, "get_statement_lines");
        if (result.isEmpty() || !result.get().ok()) {
            return errorReply("statement", result.map(ToolResult::errorMessage).orElse("no result"));
        }
        Map<String, Object> data = result.get().data();
        String currency = safe(data.get("currency"));
        String since = safe(data.get("sinceLabel"));
        int entryCount = ((Number) data.getOrDefault("entryCount", 0)).intValue();
        int creditCount = ((Number) data.getOrDefault("creditCount", 0)).intValue();
        int debitCount = ((Number) data.getOrDefault("debitCount", 0)).intValue();
        BigDecimal creditTotal = asBigDecimal(data.get("creditTotal"));
        BigDecimal debitTotal = asBigDecimal(data.get("debitTotal"));
        BigDecimal net = asBigDecimal(data.get("netMovement"));

        StringBuilder sb = new StringBuilder();
        sb.append("**Statement summary** for ").append(currency == null ? "all currencies" : currency)
                .append(" (last ").append(since).append("):\n\n");
        sb.append("| | Count | Total |\n");
        sb.append("|---|---:|---:|\n");
        sb.append("| Credits | ").append(creditCount).append(" | ").append(formatCurrency(creditTotal, currency)).append(" |\n");
        sb.append("| Debits | ").append(debitCount).append(" | ").append(formatCurrency(debitTotal, currency)).append(" |\n");
        sb.append("| **Net** | **").append(entryCount).append("** | **").append(formatCurrency(net, currency)).append("** |\n");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) data.get("entries");
        if (entries != null && !entries.isEmpty()) {
            sb.append("\nRecent entries:\n\n");
            for (Map<String, Object> e : entries) {
                String dir = safe(e.get("direction"));
                String sign = "CRDT".equals(dir) ? "+" : "DBIT".equals(dir) ? "-" : "";
                sb.append("- `").append(safe(e.get("date"))).append("` ")
                        .append(sign).append(formatCurrency(asBigDecimal(e.get("amount")), safe(e.get("currency"))))
                        .append(" — ").append(safe(e.get("counterparty")))
                        .append(" (").append(safe(e.get("description"))).append(")\n");
            }
        }
        return sb.toString();
    }

    // ========================================================================
    // PAUSE_RULE / SET_ALERT — write intents
    //
    // The tool has already created an ActionProposal row and returned the
    // action-card payload under data._action. The frontend reads that and
    // renders an inline Confirm/Cancel card. This compose method writes the
    // accompanying narrative.
    // ========================================================================

    private String composePauseRule(IntentMatch match, List<ToolResult> results) {
        Optional<ToolResult> result = findResult(results, "pause_sweep_rule");
        if (result.isEmpty()) {
            return errorReply("pause-rule proposal", "tool did not run");
        }
        if (!result.get().ok()) {
            return "⚠️ " + result.get().errorMessage();
        }
        Map<String, Object> data = result.get().data();
        String ruleId = safe(data.get("ruleId"));
        String ruleName = safe(data.get("ruleName"));
        String currentStatus = safe(data.get("currentStatus"));

        return """
                I can pause sweep rule **`%s`** (%s). Current status: **%s**.

                Confirm the action below to apply. The mutation is reversible —
                resume the rule the same way.
                """.formatted(ruleId, ruleName, currentStatus);
    }

    private String composeSetAlert(IntentMatch match, List<ToolResult> results) {
        Optional<ToolResult> result = findResult(results, "set_balance_alert");
        if (result.isEmpty()) {
            return errorReply("balance-alert proposal", "tool did not run");
        }
        if (!result.get().ok()) {
            return "⚠️ " + result.get().errorMessage();
        }
        Map<String, Object> data = result.get().data();
        String account = safe(data.get("vaName"));
        if ("—".equals(account)) account = safe(data.get("vaNumber"));
        String direction = safe(data.get("direction")).toLowerCase();
        BigDecimal threshold = asBigDecimal(data.get("threshold"));
        String currency = safe(data.get("currency"));
        BigDecimal current = asBigDecimal(data.get("currentBalance"));

        return """
                I can create a balance alert on **%s**.

                - **Trigger:** when balance goes %s **%s**
                - **Current balance:** %s

                Confirm below to persist the alert.
                """.formatted(
                account,
                direction,
                formatCurrency(threshold, currency),
                formatCurrency(current, currency));
    }

    // ========================================================================
    // UNKNOWN
    // ========================================================================

    private String composeUnknown(IntentMatch match) {
        String ccy = marketProfile.getDefaultCurrency();
        return """
                I'm not sure how to answer that one. 🤔

                I can only handle treasury questions inside **Aperture**.
                Try one of these:

                - *"What's our %s position?"*
                - *"%s position by bank"*
                - *"Show failed sweeps in the last 24 hours"*
                - *"List sweep rules"*
                - *"Today's %s statement"*
                - *"What happened today?"*

                _You asked: "%s"_
                """.formatted(ccy, ccy, ccy, truncate(match.rawInput(), 200));
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private Optional<ToolResult> findResult(List<ToolResult> results, String name) {
        return results.stream().filter(r -> name.equals(r.toolName())).findFirst();
    }

    /**
     * Format a currency value deterministically as {@code "CCY 1,234,567.89"}.
     *
     * <p>Deliberately bypasses {@code NumberFormat.getCurrencyInstance(locale)}
     * because the JDK renders some locales (notably {@code en_AE}) with
     * European-style separators ({@code "3.837.714,78"} with periods for
     * thousands), which surprises users expecting Anglo conventions. The
     * stub demo prioritises predictability over locale fidelity. When real
     * localisation is needed, swap in {@code NumberFormat} again.
     */
    private String formatCurrency(BigDecimal amount, String currencyCode) {
        if (amount == null) amount = BigDecimal.ZERO;
        if (currencyCode == null || currencyCode.isBlank()) {
            currencyCode = marketProfile.getDefaultCurrency();
        }
        DecimalFormat fmt = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.US));
        return currencyCode + " " + fmt.format(amount);
    }

    private static BigDecimal asBigDecimal(Object v) {
        if (v == null) return BigDecimal.ZERO;
        if (v instanceof BigDecimal b) return b;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try { return new BigDecimal(v.toString()); } catch (NumberFormatException e) { return BigDecimal.ZERO; }
    }

    private static String safe(Object v) {
        return v == null ? "—" : v.toString();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "—";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static String categoryBadge(String category) {
        if (category == null) return "—";
        return switch (category.toUpperCase()) {
            case "RECOVERABLE" -> "🟡 recoverable";
            case "UNRECOVERABLE" -> "🔴 unrecoverable";
            default -> category;
        };
    }

    private static String errorReply(String topic, String msg) {
        return "I couldn't pull the " + topic + " right now (" + (msg == null ? "unknown error" : msg) + "). Try again, or pick another question.";
    }

    /**
     * Composed reply ready to stream. {@code intent} is the lowercase enum
     * name for telemetry / message-level tracking.
     */
    public record ComposedReply(String text, String intent) {}
}
