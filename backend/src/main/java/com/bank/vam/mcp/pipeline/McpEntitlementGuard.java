package com.bank.vam.mcp.pipeline;

import com.bank.vam.ai.copilot.tools.CopilotTool;
import com.bank.vam.ai.copilot.tools.ToolContext;
import com.bank.vam.ai.copilot.tools.ToolContext.CallerContext;
import com.bank.vam.mcp.McpException;
import org.springframework.stereotype.Component;

/**
 * Enforces {@link ToolContext#caller()}'s entitlement against the corporate
 * this call is scoped to.
 *
 * <p>{@code caller} is only ever non-null once Phase 3's gateway exists to
 * verify a signed context and populate it from {@link
 * com.bank.vam.service.auth.EntitlementService} — until then every call runs
 * with {@code caller == null}, and this stays a no-op, identical to how an
 * in-app copilot call with no auth behaves today. Once a caller IS present,
 * this is the real gate: an authenticated MCP call MUST have an explicit
 * active corporate scope (no "see everything" for an authenticated caller,
 * unlike the unauthenticated case) and that scope MUST be in the caller's
 * verified entitlement.
 *
 * <p>Enquiry-only (per the exposure decision): every MCP tool only ever needs
 * {@code VIEW}, which any grant at all satisfies — see {@code
 * Entitlement.canView}. There is no {@code TRANSACT}/{@code ADMIN} check here
 * because there is no mutating tool reachable through this pipeline to need one.
 */
@Component
public class McpEntitlementGuard {

    public void authorize(CopilotTool tool, ToolContext context) {
        CallerContext caller = context.caller();
        if (caller == null) {
            return;
        }
        if (context.corporateId() == null || !caller.corporateIds().contains(context.corporateId())) {
            throw McpException.forbiddenCorporateScope();
        }
    }
}
