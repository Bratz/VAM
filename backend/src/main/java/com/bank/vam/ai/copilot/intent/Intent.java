package com.bank.vam.ai.copilot.intent;

/**
 * The closed set of user intents the stub Copilot recognises.
 *
 * <p>Ordered roughly by demo importance — the IntentRouter matches them in
 * a specific priority (see {@code IntentRouter}), not in declaration order.
 *
 * <p>Read intents call one or more tools and produce a templated answer.
 * Write intents ({@link #PAUSE_RULE}, {@link #SET_ALERT}) match in P3 but
 * emit a placeholder reply until P5 wires up action cards.
 */
public enum Intent {

    /** "Hi", "Hello", "What can you do" — capability list reply. */
    GREETING,

    /** "What's our AED position?" — total + entity breakdown. */
    GET_POSITION,

    /** "AED position by bank" — total + bank breakdown, home bank first. */
    GET_POSITION_BY_BANK,

    /** "Show me failed sweeps in the last 24h" — list rejected sweep instructions. */
    FAILED_SWEEPS,

    /** "Why did SI-001 fail?" — single-instruction lookup + rejection-code explanation. */
    EXPLAIN_REJECTION,

    /** "Which accounts are idle?" — accounts with significant balance and no recent outflow. */
    IDLE_ACCOUNTS,

    /** "What happened today?" — last N audit-log events. */
    RECENT_ACTIVITY,

    /** "List sweep rules" — all rules + status histogram. */
    LIST_RULES,

    /** "Today's AED statement" — inflow/outflow summary + recent entries. */
    STATEMENT_SUMMARY,

    /** "Pause rule SR-101" — write intent; matched in P3, gated by action card in P5. */
    PAUSE_RULE,

    /** "Alert me if balance drops below 2M" — write intent; matched in P3, gated in P5. */
    SET_ALERT,

    /** Fallback when nothing matches — polite refusal with suggested prompts. */
    UNKNOWN
}
