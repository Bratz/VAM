package com.bank.vam.ai.copilot.tools;

import java.util.List;
import java.util.Set;
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
 * <p>{@code caller} is populated by the MCP pipeline's entitlement stage (see
 * {@code com.bank.vam.mcp}) from a signed, gateway-verified token — never by
 * a tool itself. It is {@code null} for every existing in-app copilot call
 * site, which keeps working unchanged. A tool that wants defense-in-depth
 * beyond {@code corporateId} (e.g. checking {@code accessLevel}) should treat
 * a {@code null caller} as "in-app / not entitlement-checked", not as
 * "authorized for everything".
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
 * @param caller           verified caller identity from the MCP gateway's signed
 *                         context, or null for in-app copilot calls (see above)
 */
public record ToolContext(
        UUID corporateId,
        String userId,
        String defaultCurrency,
        String marketProfile,
        UUID conversationId,
        CallerContext caller
) {

    /** Preserves the pre-MCP 5-arg shape — every existing call site keeps compiling. */
    public ToolContext(UUID corporateId, String userId, String defaultCurrency,
                        String marketProfile, UUID conversationId) {
        this(corporateId, userId, defaultCurrency, marketProfile, conversationId, null);
    }

    /** Copy with a verified caller attached — used once {@code McpSignedContextVerifier} succeeds. */
    public ToolContext withCaller(CallerContext caller) {
        return new ToolContext(corporateId, userId, defaultCurrency, marketProfile, conversationId, caller);
    }

    public boolean hasCorporateScope() {
        return corporateId != null;
    }

    public String userIdOrDefault() {
        return (userId == null || userId.isBlank()) ? "demo-user" : userId;
    }

    /**
     * The verified caller identity attached by the MCP gateway's signed per-call
     * context (see the target architecture in {@code docs/mcp-architecture.md}).
     *
     * @param subject      the caller's user id, from the verified token's {@code sub}
     * @param corporateIds every corporate this caller is entitled to see —
     *                     the broader entitlement; {@link ToolContext#corporateId}
     *                     is the one *active* scope for this call and must be a
     *                     member of this set (enforced by the entitlement stage,
     *                     not by tools)
     * @param accessLevel  VIEW / TRANSACT / ADMIN, from {@code user_account_access}
     *                     — enquiry-only tools require at least VIEW
     * @param scopes       OAuth scopes granted to the linked account
     * @param tokenId      the signed context's {@code jti}, for audit correlation
     */
    public record CallerContext(
            String subject,
            Set<UUID> corporateIds,
            String accessLevel,
            List<String> scopes,
            String tokenId
    ) {
    }
}
