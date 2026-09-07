package com.bank.vam.mcp;

import lombok.Getter;

import java.util.List;
import java.util.Map;

/**
 * A JSON-RPC error paired with the HTTP status it must be returned under, per
 * MCP 2026-07-28's Streamable HTTP transport (e.g. a header/body mismatch is
 * always {@code 400 Bad Request} + JSON-RPC code {@code -32020}). Thrown by
 * validation and pipeline stages, caught once in {@link McpController}.
 */
@Getter
public class McpException extends RuntimeException {

    private final int httpStatus;
    private final int rpcCode;
    private final Object data;

    public McpException(int httpStatus, int rpcCode, String message, Object data) {
        super(message);
        this.httpStatus = httpStatus;
        this.rpcCode = rpcCode;
        this.data = data;
    }

    /** Standard JSON-RPC parse error — malformed JSON body. */
    public static McpException parseError(String message) {
        return new McpException(400, -32700, message, null);
    }

    /** Standard JSON-RPC invalid request — e.g. missing jsonrpc/method. */
    public static McpException invalidRequest(String message) {
        return new McpException(400, -32600, message, null);
    }

    /** Unknown method, or (per spec) a required _meta field missing/malformed. */
    public static McpException invalidParams(String message) {
        return new McpException(400, -32602, message, null);
    }

    /** Server does not implement the requested method. HTTP 404 per spec. */
    public static McpException methodNotFound(String method) {
        return new McpException(404, -32601, "Method not found: " + method, null);
    }

    public static McpException internalError(String message) {
        return new McpException(500, -32603, message, null);
    }

    /**
     * MCP-reserved -32020: a required standard header is missing, or a header
     * value doesn't match the corresponding request body value.
     */
    public static McpException headerMismatch(String message) {
        return new McpException(400, -32020, message, null);
    }

    /** MCP-reserved -32021: the request needs a client capability that wasn't declared. */
    public static McpException missingRequiredClientCapability(List<String> required) {
        return new McpException(400, -32021, "Missing required client capability",
                Map.of("requiredCapabilities", required));
    }

    /** MCP-reserved -32022: the requested protocol version isn't one this server speaks. */
    public static McpException unsupportedProtocolVersion(String requested, List<String> supported) {
        return new McpException(400, -32022, "Unsupported protocol version",
                Map.of("supported", supported, "requested", requested == null ? "" : requested));
    }

    /** Origin header failed DNS-rebinding validation — MUST be 403 per spec, not a JSON-RPC error. */
    public static McpException forbiddenOrigin(String origin) {
        return new McpException(403, -32600, "Origin not allowed: " + origin, null);
    }

    /**
     * Rate limit exceeded. Not an MCP-defined error — the spec reserves
     * -32000..-32099 for JSON-RPC/MCP use and says application-defined codes
     * "SHOULD be allocated outside the JSON-RPC reserved range (-32768 to
     * -32000)", so this uses a plain positive application code rather than
     * squatting in that range.
     */
    public static McpException rateLimited(String toolName) {
        return new McpException(429, 42900, "Rate limit exceeded for tool: " + toolName, null);
    }

    /**
     * An authenticated caller's verified entitlement doesn't cover the corporate
     * this call is scoped to. Also not an MCP-defined error, for the same
     * reason as {@link #rateLimited} — an app-defined code (HTTP 403), not one
     * from the JSON-RPC/MCP reserved ranges.
     */
    public static McpException forbiddenCorporateScope() {
        // Deliberately vague: "you're not entitled to this" and "this corporate
        // doesn't exist" should look identical to the caller. Distinguishing
        // them would let a caller enumerate valid corporate ids by trial and error.
        return new McpException(403, 40300, "Not authorized for the requested corporate scope", null);
    }

    /** Signed context present but fails signature/expiry verification. HTTP 401 per spec ("Invalid or expired tokens MUST receive a HTTP 401 response"). */
    public static McpException invalidSignedContext(String reason) {
        return new McpException(401, 40100, "Invalid signed context: " + reason, null);
    }

    /** {@code vam.mcp.require-signed-context=true} and the call carried no signed context at all. */
    public static McpException missingSignedContext() {
        return new McpException(401, 40101, "Missing required signed context", null);
    }

    /** A structurally valid, unexpired, correctly-signed context whose jti has already been used once. */
    public static McpException replayedSignedContext() {
        return new McpException(401, 40102, "Signed context has already been used (replay detected)", null);
    }

    /** The signed context was minted for a different tool than the one actually being called. */
    public static McpException signedContextToolMismatch() {
        return new McpException(401, 40103, "Signed context is not valid for this tool", null);
    }

    /**
     * No {@code resources/read} handler for the requested URI. Reuses
     * {@code -32002}, the original MCP spec's "Resource not found" code —
     * already reserved before the 2026-07-28 error-code partitioning, so this
     * is re-using a grandfathered code rather than allocating a new one in
     * the range the spec says servers MUST NOT add to.
     */
    public static McpException resourceNotFound(String uri) {
        return new McpException(404, -32002, "Resource not found: " + uri, null);
    }
}
