package com.bank.mcpgateway.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stand-in {@code redirect_uri} for manual/demo testing — a real MCP client
 * (ChatGPT, Claude, or a proper test harness) has its own redirect handling
 * and would never hit this. Just echoes the authorization code back so it can
 * be read off the page and exchanged for a token by hand while proving the
 * flow works end-to-end.
 */
@RestController
public class AuthorizedController {

    @GetMapping("/authorized")
    public String authorized(@RequestParam(required = false) String code,
                              @RequestParam(required = false) String state,
                              @RequestParam(required = false) String error) {
        if (error != null) {
            return "Authorization failed: " + error;
        }
        return "Authorization code: " + code + "\nstate: " + state
                + "\n\nExchange this at POST /oauth2/token with grant_type=authorization_code, "
                + "code_verifier, and this redirect_uri.";
    }
}
