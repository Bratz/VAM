package com.bank.vam.ai.copilot.dto;

/**
 * One paced chunk of the assistant reply. Trailing whitespace is preserved on
 * each token so the frontend can concatenate naively.
 */
public record ChatChunkToken(String value) {}
