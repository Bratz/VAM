package com.bank.vam.ai.copilot.intent;

/**
 * Slot keys used in {@link IntentMatch#slots()}.
 *
 * <p>Constants rather than an enum so the slot map can carry strongly-typed
 * values (BigDecimal for amounts, Integer for days) under string keys.
 */
public final class Slot {

    /** ISO-4217 code extracted from the input, uppercased ("AED", "GBP"). */
    public static final String CURRENCY = "currency";

    /** Sweep rule reference, e.g. {@code "SR-101"}. */
    public static final String RULE_ID = "ruleId";

    /** Sweep instruction reference, e.g. {@code "SI-001"}. */
    public static final String INSTRUCTION_ID = "instructionId";

    /** ISO rejection code, e.g. {@code "AM04"}, {@code "MS03"}. */
    public static final String REJECTION_CODE = "rejectionCode";

    /** Normalised amount as {@link java.math.BigDecimal} ("2m" → 2000000). */
    public static final String AMOUNT = "amount";

    /** {@code "above"} or {@code "below"} for alert direction. */
    public static final String DIRECTION = "direction";

    /** Relative time window as the duration string the tools accept ("24h", "7d"). */
    public static final String SINCE = "since";

    /** Day count for idle-accounts queries (Integer). */
    public static final String DAYS = "days";

    /** Account reference — vaNumber, viban, or partial name. */
    public static final String ACCOUNT_REF = "accountRef";

    private Slot() {}
}
