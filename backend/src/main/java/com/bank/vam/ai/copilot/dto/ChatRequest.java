package com.bank.vam.ai.copilot.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Inbound chat turn from the frontend.
 *
 * @param conversationId  existing conversation to append to; {@code null} starts a new one
 * @param message         the user's message text (required, max 4 KB)
 * @param userId          authenticated user identifier; defaults to {@code "demo-user"}
 *                        in the prototype since auth is permitAll in dev
 * @param corporateId     corporate scope; nullable in the prototype
 */
public record ChatRequest(
        UUID conversationId,
        @NotBlank @Size(max = 4096) String message,
        String userId,
        UUID corporateId
) {
    public String userIdOrDefault() {
        return (userId == null || userId.isBlank()) ? "demo-user" : userId;
    }
}
