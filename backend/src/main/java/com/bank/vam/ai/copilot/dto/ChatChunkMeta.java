package com.bank.vam.ai.copilot.dto;

import java.util.UUID;

/**
 * First SSE event of a turn. Tells the frontend which conversation we landed
 * in (especially important when the request created a new one) and the ID of
 * the persisted user message so the UI can mark it as confirmed-saved.
 */
public record ChatChunkMeta(UUID conversationId, UUID userMessageId) {}
