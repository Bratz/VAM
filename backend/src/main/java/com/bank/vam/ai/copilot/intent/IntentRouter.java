package com.bank.vam.ai.copilot.intent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic intent router — the stub's "brain".
 *
 * <p>Two-stage pipeline per turn:
 * <ol>
 *   <li><b>Slot extraction</b> — runs every regex against the raw input and
 *       collects values into a map keyed by {@link Slot} constants. Slots
 *       are extracted once up front so intent rules can pivot on their
 *       presence/absence.</li>
 *   <li><b>Intent matching</b> — a priority-ordered chain of predicates.
 *       Write intents win when their gating slot is present; the most
 *       specific read intents are tested before the more generic ones;
 *       {@link Intent#UNKNOWN} is the floor.</li>
 * </ol>
 *
 * <p>Trade-offs vs an LLM:
 * <ul>
 *   <li>Predictable, debuggable, zero cost, no rate limits.</li>
 *   <li>Inflexible to phrasings we didn't anticipate. Mitigation: suggested
 *       prompts in the drawer + a friendly UNKNOWN reply listing what we
 *       <i>can</i> answer.</li>
 * </ul>
 */
@Slf4j
@Component
public class IntentRouter {

    // ------------------------------------------------------------------------
    // Slot patterns — kept as static fields so they compile once.
    // ------------------------------------------------------------------------

    /** Common ISO-4217 codes — extend as needed. */
    private static final Pattern CURRENCY = Pattern.compile(
            "\\b(AED|USD|EUR|GBP|SAR|SGD|KWD|BHD|QAR|OMR|JPY|CHF|CAD|AUD|NZD|INR|CNY|HKD|THB|MYR|IDR|PHP|ZAR)\\b",
            Pattern.CASE_INSENSITIVE);

    /** Canonical SR-NNN form documented in CLAUDE.md. */
    private static final Pattern RULE_ID = Pattern.compile("\\b(SR-\\d+)\\b", Pattern.CASE_INSENSITIVE);
    /**
     * Positional fallback — captures any code-like token following "rule" or "sweep".
     * Picks up the IHB-style references the demo data ships with (IHB-MNC-UAE-AED, IHB2026801173).
     *
     * <p>Keywords intentionally exclude "pause"/"stop"/"disable" so that a phrase
     * like "pause rule IHB-..." captures "IHB-..." (after "rule") rather than
     * "RULE" (after "pause").
     */
    private static final Pattern RULE_REFERENCE_POSITIONAL = Pattern.compile(
            "\\b(?:rule|sweep)\\s+([A-Z][A-Z0-9_\\-]{2,})\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern INSTRUCTION_ID = Pattern.compile("\\b(SI-\\d+)\\b", Pattern.CASE_INSENSITIVE);

    /** ISO external rejection codes — 2-4 letters + 2-3 digits. Conservative to avoid false matches on words like "SR101" (already caught by ruleId). */
    private static final Pattern REJECTION_CODE = Pattern.compile("\\b([A-Z]{2,4}\\d{2,3})\\b");

    /** Money amounts: "2m", "500k", "1.5 million", "1,234,567.89", "2 bn". */
    private static final Pattern AMOUNT = Pattern.compile(
            "\\b(\\d[\\d,]*(?:\\.\\d+)?)\\s*(m|mn|million|k|thousand|bn|billion)?\\b",
            Pattern.CASE_INSENSITIVE);

    /** "below", "drops below", "less than", "<" → below; "above", "exceeds", ">" → above. */
    private static final Pattern DIRECTION_BELOW = Pattern.compile(
            "\\b(below|under|less\\s+than|drops?\\s+below|<)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern DIRECTION_ABOVE = Pattern.compile(
            "\\b(above|over|greater\\s+than|exceeds?|>)\\b", Pattern.CASE_INSENSITIVE);

    /** "in the last 24 hours", "last 7 days", "in 30 minutes". */
    private static final Pattern SINCE_LAST_N = Pattern.compile(
            "\\blast\\s+(\\d+)\\s*(second|minute|hour|day|week)s?\\b", Pattern.CASE_INSENSITIVE);
    /** Compact form: "24h", "7d", "30m", "60s". */
    private static final Pattern SINCE_COMPACT = Pattern.compile(
            "\\b(\\d+)\\s*(s|m|h|d)\\b");

    /** "5 days", "7-day", "for 14 days". */
    private static final Pattern DAYS = Pattern.compile(
            "\\b(\\d+)[-\\s]+days?\\b", Pattern.CASE_INSENSITIVE);

    /** Account references: VA-..., AE/GB/SA IBAN starts, or "Mirror-NBD-AED". */
    private static final Pattern ACCOUNT_REF = Pattern.compile(
            "\\b(VA-[A-Za-z0-9\\-]+|Mirror-[A-Za-z0-9\\-]+|[A-Z]{2}\\d{2}[A-Z0-9]{10,30})\\b");

    // ------------------------------------------------------------------------
    // Public entry point
    // ------------------------------------------------------------------------

    public IntentMatch match(String input) {
        if (input == null || input.isBlank()) {
            return new IntentMatch(Intent.UNKNOWN, Map.of(), "");
        }
        String text = input.trim();
        String lower = text.toLowerCase();
        Map<String, Object> slots = extractSlots(text, lower);
        Intent intent = chooseIntent(lower, slots);
        log.debug("IntentRouter: intent={} slots={} input='{}'", intent, slots, text);
        return new IntentMatch(intent, slots, text);
    }

    // ------------------------------------------------------------------------
    // Slot extraction
    // ------------------------------------------------------------------------

    private Map<String, Object> extractSlots(String text, String lower) {
        Map<String, Object> slots = new LinkedHashMap<>();

        Matcher ccy = CURRENCY.matcher(text);
        if (ccy.find()) slots.put(Slot.CURRENCY, ccy.group(1).toUpperCase());

        // Rule ID: canonical SR-NNN first, then positional fallback for the
        // bank-specific references in the seeded data ("rule IHB-MNC-UAE-AED").
        Matcher rule = RULE_ID.matcher(text);
        if (rule.find()) {
            slots.put(Slot.RULE_ID, rule.group(1).toUpperCase());
        } else {
            Matcher posRule = RULE_REFERENCE_POSITIONAL.matcher(text);
            if (posRule.find()) {
                slots.put(Slot.RULE_ID, posRule.group(1).toUpperCase());
            }
        }

        Matcher instr = INSTRUCTION_ID.matcher(text);
        if (instr.find()) slots.put(Slot.INSTRUCTION_ID, instr.group(1).toUpperCase());

        // Rejection codes — only consider when an explanation-style verb is nearby
        // OR the input is a single token like "AM04". Avoids false-matching account
        // numbers like "AE150410…" where the leading "AE15" looks like a code.
        if (lower.contains("rejection") || lower.contains("explain") || lower.contains("code")
                || lower.startsWith("am") || lower.startsWith("ac") || lower.startsWith("ms")) {
            Matcher rej = REJECTION_CODE.matcher(text);
            // Skip if it overlaps with a ruleId or instructionId we already grabbed.
            while (rej.find()) {
                String c = rej.group(1);
                if (c.startsWith("SR") || c.startsWith("SI")) continue;
                slots.put(Slot.REJECTION_CODE, c);
                break;
            }
        }

        Integer days = matchDays(text);
        if (days != null) slots.put(Slot.DAYS, days);

        String since = matchSince(text);
        if (since != null) slots.put(Slot.SINCE, since);

        BigDecimal amount = matchAmount(text);
        if (amount != null) slots.put(Slot.AMOUNT, amount);

        if (DIRECTION_BELOW.matcher(text).find()) {
            slots.put(Slot.DIRECTION, "below");
        } else if (DIRECTION_ABOVE.matcher(text).find()) {
            slots.put(Slot.DIRECTION, "above");
        }

        Matcher acct = ACCOUNT_REF.matcher(text);
        if (acct.find()) slots.put(Slot.ACCOUNT_REF, acct.group(1));

        return slots;
    }

    private Integer matchDays(String text) {
        Matcher m = DAYS.matcher(text);
        if (m.find()) {
            try { return Integer.parseInt(m.group(1)); } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    /** Returns the compact form the tools expect ("24h", "7d", …) or null. */
    private String matchSince(String text) {
        String lower = text.toLowerCase();
        if (lower.contains("today")) return "24h";
        if (lower.contains("yesterday")) return "48h";

        Matcher last = SINCE_LAST_N.matcher(text);
        if (last.find()) {
            int n = Integer.parseInt(last.group(1));
            return switch (last.group(2).toLowerCase()) {
                case "second" -> n + "s";
                case "minute" -> n + "m";
                case "hour" -> n + "h";
                case "day" -> n + "d";
                case "week" -> (n * 7) + "d";
                default -> null;
            };
        }
        Matcher compact = SINCE_COMPACT.matcher(text);
        if (compact.find()) {
            // Avoid false-matching "1.5m" amounts — only accept when preceded by space/start.
            int start = compact.start();
            if (start == 0 || !Character.isLetterOrDigit(text.charAt(start - 1))) {
                return compact.group(1) + compact.group(2).toLowerCase();
            }
        }
        return null;
    }

    /**
     * Parses money amounts including suffix multipliers. Returns null for
     * bare integers that are likely IDs ({@code 101}, {@code 24}) rather
     * than amounts.
     */
    private BigDecimal matchAmount(String text) {
        Matcher m = AMOUNT.matcher(text);
        while (m.find()) {
            String num = m.group(1).replace(",", "");
            String suffix = m.group(2);
            BigDecimal val;
            try {
                val = new BigDecimal(num);
            } catch (NumberFormatException e) {
                continue;
            }
            if (suffix != null) {
                String s = suffix.toLowerCase();
                BigDecimal mult = switch (s) {
                    case "m", "mn", "million" -> new BigDecimal("1000000");
                    case "k", "thousand" -> new BigDecimal("1000");
                    case "bn", "billion" -> new BigDecimal("1000000000");
                    default -> BigDecimal.ONE;
                };
                return val.multiply(mult).setScale(2, RoundingMode.HALF_UP);
            }
            // Heuristic: a bare integer with no decimal/comma is probably a count or
            // an id (e.g. "SR-101", "in the last 24 hours"). Demand at least 4 digits
            // or a thousands separator before treating it as money.
            if (num.length() >= 4 || m.group(1).contains(",") || num.contains(".")) {
                return val.setScale(2, RoundingMode.HALF_UP);
            }
        }
        return null;
    }

    // ------------------------------------------------------------------------
    // Intent selection — priority-ordered, first match wins
    // ------------------------------------------------------------------------

    private Intent chooseIntent(String lower, Map<String, Object> slots) {
        // (1) Write intents — gated on their required slot so we don't try to
        //     "pause" a rule the user didn't name.
        if (containsAny(lower, "pause rule", "pause sweep", "stop sweep", "stop rule",
                "disable rule", "disable sweep") && slots.containsKey(Slot.RULE_ID)) {
            return Intent.PAUSE_RULE;
        }
        if ((containsAny(lower, "alert me", "notify me", "notify when", "let me know",
                "send me an alert"))
                && slots.containsKey(Slot.AMOUNT)) {
            return Intent.SET_ALERT;
        }

        // (2) EXPLAIN_REJECTION — needs an instruction id OR a rejection code, plus
        //     an explanation verb ("why", "explain", "fail", "reject").
        boolean hasExplainVerb = containsAny(lower, "why", "explain", "fail", "reject");
        if ((slots.containsKey(Slot.INSTRUCTION_ID) || slots.containsKey(Slot.REJECTION_CODE))
                && hasExplainVerb) {
            return Intent.EXPLAIN_REJECTION;
        }

        // (3) Idle accounts — checked BEFORE position because "stale balance"
        //     contains "balance" which would otherwise greedy-match GET_POSITION.
        if (containsAny(lower, "idle", "no activity", "stale balance", "stale account",
                "stale balances", "dormant", "unused account", "inactive account")) {
            return Intent.IDLE_ACCOUNTS;
        }

        // (4) Position-by-bank — standalone bank-breakdown phrasing.
        //     "USD across banks" should match even without the word "position".
        boolean isByBank = containsAny(lower, "by bank", "across banks", "in each bank",
                "per bank") || (containsAny(lower, "where is", "where are") && slots.containsKey(Slot.CURRENCY));
        if (isByBank) {
            return Intent.GET_POSITION_BY_BANK;
        }

        // (5) Generic position query.
        if (containsAny(lower, "position", "balance", "how much", "total cash", "total funds")) {
            return Intent.GET_POSITION;
        }

        // (6) Failed sweeps — looser keyword combo since this is a common ask.
        if (containsAny(lower, "failed sweep", "failed instruction", "sweep reject",
                "sweep failure", "rejected sweep", "rejected instruction", "sweeps that failed")
                || (hasExplainVerb && containsAny(lower, "sweep", "sweeps", "instruction"))) {
            return Intent.FAILED_SWEEPS;
        }

        // (6) Recent activity
        if (containsAny(lower, "what happened", "recent activity", "audit trail",
                "audit log", "today's activity", "recent events", "what's new")) {
            return Intent.RECENT_ACTIVITY;
        }

        // (7) List rules
        if (containsAny(lower, "list rules", "show me rules", "what rules", "sweep rules",
                "list sweep", "all rules", "rules are active", "active rules")) {
            return Intent.LIST_RULES;
        }

        // (8) Statement
        if (containsAny(lower, "statement", "camt 053", "camt053", "camt.053",
                "camt 054", "camt054", "transactions today", "today's transactions")) {
            return Intent.STATEMENT_SUMMARY;
        }

        // (9) Greeting
        if (isGreeting(lower)) {
            return Intent.GREETING;
        }

        return Intent.UNKNOWN;
    }

    private boolean isGreeting(String lower) {
        // Very short messages that look like greetings.
        if (lower.length() > 40) return false;
        return containsAny(lower, "hello", "hi ", "hi!", "hey", "good morning",
                "good afternoon", "good evening", "what can you do", "what do you do",
                "help me", "what can i ask", "how do you work")
                || lower.equals("hi") || lower.equals("hey") || lower.equals("yo")
                || lower.equals("help") || lower.equals("?");
    }

    private boolean containsAny(String haystack, String... needles) {
        for (String n : needles) {
            if (haystack.contains(n)) return true;
        }
        return false;
    }
}
