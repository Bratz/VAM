package com.bank.vam.ai.copilot.intent;

import java.util.Map;

/**
 * Result of routing one user turn through {@code IntentRouter}.
 *
 * @param intent     the matched intent (or {@link Intent#UNKNOWN})
 * @param slots      extracted slots keyed by {@link Slot} constants;
 *                   absent slots mean "the input didn't specify this"
 * @param rawInput   the user's original message, preserved for the
 *                   {@code rawInput} echo in {@link Intent#UNKNOWN} replies
 */
public record IntentMatch(
        Intent intent,
        Map<String, Object> slots,
        String rawInput
) {

    public boolean hasSlot(String key) {
        return slots != null && slots.get(key) != null;
    }

    public Object slot(String key) {
        return slots == null ? null : slots.get(key);
    }

    public String slotString(String key) {
        Object v = slot(key);
        return v == null ? null : v.toString();
    }
}
