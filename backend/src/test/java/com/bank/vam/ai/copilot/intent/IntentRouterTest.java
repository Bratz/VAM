package com.bank.vam.ai.copilot.intent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link IntentRouter}.
 *
 * <p>Two parts:
 * <ol>
 *   <li>Intent-classification coverage — 3-5 phrasings per intent (parameterised).</li>
 *   <li>Slot-extraction spot-checks — currency, ruleId, instructionId, amount, since.</li>
 * </ol>
 *
 * <p>No Spring context — IntentRouter has no collaborators.
 */
class IntentRouterTest {

    private final IntentRouter router = new IntentRouter();

    // ========================================================================
    // Intent classification — parameterised coverage
    // ========================================================================

    @ParameterizedTest(name = "{0} → GET_POSITION")
    @CsvSource({
            "What's our AED position?",
            "Show me the GBP position",
            "How much USD do we have",
            "Total cash in EUR",
            "Total funds"
    })
    @DisplayName("GET_POSITION — position/balance/how-much phrasings")
    void getPosition(String input) {
        assertThat(router.match(input).intent()).isEqualTo(Intent.GET_POSITION);
    }

    @ParameterizedTest(name = "{0} → GET_POSITION_BY_BANK")
    @CsvSource({
            "AED position by bank",
            "Show me USD across banks",
            "Where is our SAR position",
            "EUR position in each bank"
    })
    @DisplayName("GET_POSITION_BY_BANK — bank-breakdown phrasings")
    void positionByBank(String input) {
        assertThat(router.match(input).intent()).isEqualTo(Intent.GET_POSITION_BY_BANK);
    }

    @ParameterizedTest(name = "{0} → FAILED_SWEEPS")
    @CsvSource({
            "Show me failed sweeps in the last 24 hours",
            "Any rejected sweep instructions?",
            "Which sweeps failed today",
            "Sweep rejects from this week"
    })
    @DisplayName("FAILED_SWEEPS — sweep-failure phrasings")
    void failedSweeps(String input) {
        assertThat(router.match(input).intent()).isEqualTo(Intent.FAILED_SWEEPS);
    }

    @ParameterizedTest(name = "{0} → EXPLAIN_REJECTION")
    @CsvSource({
            "Why did SI-001 fail?",
            "Explain rejection on SI-042",
            "Why was SI-100 rejected"
    })
    @DisplayName("EXPLAIN_REJECTION — id + verb phrasings")
    void explainRejection(String input) {
        assertThat(router.match(input).intent()).isEqualTo(Intent.EXPLAIN_REJECTION);
    }

    @ParameterizedTest(name = "{0} → IDLE_ACCOUNTS")
    @CsvSource({
            "Which accounts are idle?",
            "Show me dormant accounts",
            "Inactive account list",
            "Any stale balances?"
    })
    @DisplayName("IDLE_ACCOUNTS — idle/dormant/stale phrasings")
    void idleAccounts(String input) {
        assertThat(router.match(input).intent()).isEqualTo(Intent.IDLE_ACCOUNTS);
    }

    @ParameterizedTest(name = "{0} → RECENT_ACTIVITY")
    @CsvSource({
            "What happened today",
            "Show me recent activity",
            "Audit trail please",
            "What's new"
    })
    @DisplayName("RECENT_ACTIVITY — audit / what-happened phrasings")
    void recentActivity(String input) {
        assertThat(router.match(input).intent()).isEqualTo(Intent.RECENT_ACTIVITY);
    }

    @ParameterizedTest(name = "{0} → LIST_RULES")
    @CsvSource({
            "List sweep rules",
            "Show me rules",
            "What rules are active",
            "All sweep rules"
    })
    @DisplayName("LIST_RULES — rule-listing phrasings")
    void listRules(String input) {
        assertThat(router.match(input).intent()).isEqualTo(Intent.LIST_RULES);
    }

    @ParameterizedTest(name = "{0} → STATEMENT_SUMMARY")
    @CsvSource({
            "Today's AED statement",
            "Show me the statement",
            "Generate camt 053 for today",
            "Today's transactions"
    })
    @DisplayName("STATEMENT_SUMMARY — statement / camt phrasings")
    void statementSummary(String input) {
        assertThat(router.match(input).intent()).isEqualTo(Intent.STATEMENT_SUMMARY);
    }

    @ParameterizedTest(name = "{0} → PAUSE_RULE")
    @CsvSource({
            "Pause rule SR-101",
            "Stop sweep SR-12",
            "Disable rule SR-101 please"
    })
    @DisplayName("PAUSE_RULE — pause/stop + rule id phrasings")
    void pauseRule(String input) {
        IntentMatch m = router.match(input);
        assertThat(m.intent()).isEqualTo(Intent.PAUSE_RULE);
        assertThat(m.slotString(Slot.RULE_ID)).startsWith("SR-");
    }

    @ParameterizedTest(name = "{0} → SET_ALERT")
    @CsvSource({
            "Alert me if Mirror-NBD-AED drops below 2m",
            "Notify me when VA-OPS-001 goes above 5 million",
            "Let me know if it drops below 100k"
    })
    @DisplayName("SET_ALERT — alert/notify + amount phrasings")
    void setAlert(String input) {
        IntentMatch m = router.match(input);
        assertThat(m.intent()).isEqualTo(Intent.SET_ALERT);
        assertThat(m.slot(Slot.AMOUNT)).isNotNull();
    }

    @ParameterizedTest(name = "{0} → GREETING")
    @CsvSource({
            "Hi",
            "Hello",
            "Hey",
            "What can you do",
            "Help"
    })
    @DisplayName("GREETING — short greeting / capability phrasings")
    void greeting(String input) {
        assertThat(router.match(input).intent()).isEqualTo(Intent.GREETING);
    }

    @ParameterizedTest(name = "{0} → UNKNOWN")
    @CsvSource({
            "What's the weather in Dubai?",
            "Tell me a joke",
            "How is the team doing today?",
            "Schedule a meeting"
    })
    @DisplayName("UNKNOWN — off-topic phrasings fall through")
    void unknown(String input) {
        assertThat(router.match(input).intent()).isEqualTo(Intent.UNKNOWN);
    }

    // ========================================================================
    // Slot extraction — spot checks
    // ========================================================================

    @Test
    @DisplayName("Currency slot — case-insensitive, uppercased in result")
    void currencySlot() {
        assertThat(router.match("aed position").slotString(Slot.CURRENCY)).isEqualTo("AED");
        assertThat(router.match("Show me USD").slotString(Slot.CURRENCY)).isEqualTo("USD");
        assertThat(router.match("hello").slot(Slot.CURRENCY)).isNull();
    }

    @Test
    @DisplayName("RuleId slot — SR-NNN normalised to uppercase")
    void ruleIdSlot() {
        assertThat(router.match("pause sr-101").slotString(Slot.RULE_ID)).isEqualTo("SR-101");
        assertThat(router.match("pause Rule SR-12 now").slotString(Slot.RULE_ID)).isEqualTo("SR-12");
    }

    @Test
    @DisplayName("InstructionId slot — SI-NNN parsed and uppercased")
    void instructionIdSlot() {
        assertThat(router.match("why did si-001 fail").slotString(Slot.INSTRUCTION_ID))
                .isEqualTo("SI-001");
    }

    @Test
    @DisplayName("Amount slot — 'm', 'k', 'million', 'bn' multipliers")
    void amountSlotMultipliers() {
        // Wrap in alert intent so the AMOUNT slot is actually used.
        assertThat((BigDecimal) router.match("alert me if it drops below 2m").slot(Slot.AMOUNT))
                .isEqualByComparingTo("2000000.00");
        assertThat((BigDecimal) router.match("alert me when it goes above 500k").slot(Slot.AMOUNT))
                .isEqualByComparingTo("500000.00");
        assertThat((BigDecimal) router.match("notify if below 1.5 million").slot(Slot.AMOUNT))
                .isEqualByComparingTo("1500000.00");
        assertThat((BigDecimal) router.match("alert me at 2 bn").slot(Slot.AMOUNT))
                .isEqualByComparingTo("2000000000.00");
    }

    @Test
    @DisplayName("Amount slot — bare small integers are NOT treated as amounts")
    void amountSlotIgnoresSmallIntegers() {
        // "24" in "last 24 hours" is a duration, not an amount.
        assertThat(router.match("Show failed sweeps in the last 24 hours").slot(Slot.AMOUNT))
                .isNull();
    }

    @Test
    @DisplayName("Since slot — 'today', 'last 24 hours', '7d' all normalised")
    void sinceSlot() {
        assertThat(router.match("show failed sweeps today").slotString(Slot.SINCE)).isEqualTo("24h");
        assertThat(router.match("audit trail in the last 24 hours").slotString(Slot.SINCE))
                .isEqualTo("24h");
        assertThat(router.match("show statement for 7d").slotString(Slot.SINCE)).isEqualTo("7d");
        assertThat(router.match("last 2 weeks of audit").slotString(Slot.SINCE)).isEqualTo("14d");
    }

    @Test
    @DisplayName("Direction slot — 'below' / 'drops below' / 'above'")
    void directionSlot() {
        assertThat(router.match("alert me if it drops below 2m").slotString(Slot.DIRECTION))
                .isEqualTo("below");
        assertThat(router.match("alert me if it goes above 5m").slotString(Slot.DIRECTION))
                .isEqualTo("above");
    }

    @Test
    @DisplayName("Account ref slot — VA-XXX and Mirror-XXX recognised")
    void accountRefSlot() {
        assertThat(router.match("alert me if VA-OPS-001 drops below 2m")
                .slotString(Slot.ACCOUNT_REF)).isEqualTo("VA-OPS-001");
        assertThat(router.match("alert me if Mirror-NBD-AED drops below 2m")
                .slotString(Slot.ACCOUNT_REF)).isEqualTo("Mirror-NBD-AED");
    }

    @Test
    @DisplayName("Empty / null input falls through to UNKNOWN cleanly")
    void emptyInput() {
        assertThat(router.match("").intent()).isEqualTo(Intent.UNKNOWN);
        assertThat(router.match(null).intent()).isEqualTo(Intent.UNKNOWN);
        assertThat(router.match("   ").intent()).isEqualTo(Intent.UNKNOWN);
    }

    @Test
    @DisplayName("Priority: write intents beat read intents when slot present")
    void writeIntentPriority() {
        // Phrase contains "sweep" (read keyword) but also "pause … SR-…" gating write.
        IntentMatch m = router.match("pause sweep rule SR-101 now");
        assertThat(m.intent()).isEqualTo(Intent.PAUSE_RULE);
    }

    @Test
    @DisplayName("Priority: alert without amount falls through to UNKNOWN")
    void alertWithoutAmount() {
        // No amount slot → cannot match SET_ALERT.
        IntentMatch m = router.match("alert me about things");
        assertThat(m.intent()).isEqualTo(Intent.UNKNOWN);
    }
}
