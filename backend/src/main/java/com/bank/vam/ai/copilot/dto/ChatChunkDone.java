package com.bank.vam.ai.copilot.dto;

import java.util.UUID;

/**
 * Final SSE event of a turn. Carries the persisted assistant message ID and
 * the matched {@code Intent} (P3+) so the frontend can attach it to the
 * rendered message for action-card lookup or telemetry.
 */
public record ChatChunkDone(UUID assistantMessageId, String intent) {}
