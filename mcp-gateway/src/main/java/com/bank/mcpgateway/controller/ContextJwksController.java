package com.bank.mcpgateway.controller;

import com.bank.mcpgateway.service.ContextJwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The backend points {@code spring.security.oauth2.resourceserver.jwt.jwk-set-uri}
 * here to verify the per-call signed context {@link ContextJwtService} mints.
 * Deliberately separate from Spring Authorization Server's own {@code
 * /oauth2/jwks} (which signs OAuth access tokens — a different key, a
 * different concern).
 */
@RestController
@RequiredArgsConstructor
public class ContextJwksController {

    private final ContextJwtService contextJwtService;

    @GetMapping("/context-jwks")
    public Map<String, Object> jwks() {
        return contextJwtService.publicJwkSet();
    }
}
