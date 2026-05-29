package com.bank.vam.ai.copilot.tools;

import java.util.UUID;

/**
 * Carries the principal + market context through a Copilot tool invocation.
 *
 * <p>In the prototype, {@code corporateId} and {@code userId} are nullable
 * because auth is permitAll in dev. When auth lands, the controller layer
 * will populate these from the {@code SecurityContext}.
 *
 * <p>{@code conversationId} is populated by {@code CopilotService} for the
 * tools that need to attach side-effect rows to the originating conversation —
 * notably write tools, which create {@code ActionProposal} rows scoped to
 * the conversation they were proposed in.
 *
 * @param corporateId      corporate scope (null = all corporates, prototype only)
 * @param userId           authenticated user id (defaults to "demo-user")
 * @param defaultCurrency  currency from the active {@link
 *                         com.bank.vam.config.MarketProfileProperties} —
 *                         tools use this when the caller didn't supply one
 * @param marketProfile    the active profile name (UAE/KSA/UK/EU/US/SG) —
 *                         used by templates for locale-correct formatting
 * @param conversationId   the conversation this turn belongs to; null for
 *                         out-of-band invocations (the {@code /tools/{name}/execute}
 *                         diagnostic endpoint)
 */
public record ToolContext(
        UUID corporateId,
        String userId,
        String defaultCurrency,
        String marketProfile,
        UUID conversationId
) {

    public boolean hasCorporateScope() {
        return corporateId != null;
    }

    public String userIdOrDefault() {
        return (userId == null || userId.isBlank()) ? "demo-user" : userId;
    }
}
