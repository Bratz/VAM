/**
 * Shared types for the Treasury Copilot frontend.
 *
 * Mirrors the backend DTOs in `com.bank.vam.ai.copilot.dto.*` and the
 * `Intent` enum so the UI can render type-safely without bringing in a
 * codegen step for the prototype.
 */

export type Role = 'USER' | 'ASSISTANT' | 'TOOL';

export type Intent =
  | 'greeting'
  | 'get_position'
  | 'get_position_by_bank'
  | 'failed_sweeps'
  | 'explain_rejection'
  | 'idle_accounts'
  | 'recent_activity'
  | 'list_rules'
  | 'statement_summary'
  | 'pause_rule'
  | 'set_alert'
  | 'unknown';

/** One persisted message. Streamed messages live as {@link ChatMessage} with isStreaming=true. */
export interface MessageView {
  id: string;
  role: Role;
  content: string;
  intent: Intent | null;
  sequenceNumber: number;
  createdAt: string;
  toolCalls: ToolCall[] | null;
}

/** UI-side message shape (richer than the backend view; adds streaming state). */
export interface ChatMessage {
  id: string;
  role: Role;
  content: string;
  intent?: Intent | null;
  toolCalls?: ToolCall[];
  isStreaming?: boolean;
  /** Only set on USER messages while the network round-trip is in flight. */
  pending?: boolean;
}

/** Result of one tool invocation; serialised into `copilot_message.tool_calls`. */
export interface ToolCall {
  toolName: string;
  ok: boolean;
  summary: string;
  data: Record<string, unknown>;
  errorMessage: string | null;
}

/**
 * Action card embedded in a write-tool's {@link ToolCall#data}, under the
 * stable key `_action`. The drawer reads it and renders Confirm / Cancel
 * inline with the assistant reply.
 */
export interface ActionProposal {
  proposalId: string;
  type: 'pause_sweep_rule' | 'set_balance_alert' | string;
  title: string;
  description: string;
  expiresAt: string;
  params: Record<string, unknown>;
}

/** Outcome of POST /actions/{id}/{execute|cancel}. */
export interface ActionResult {
  ok: boolean;
  proposalId: string | null;
  message: string;
}

/** Conversation summary returned by `GET /conversations`. */
export interface ConversationSummary {
  id: string;
  title: string | null;
  lastMessageAt: string | null;
  messageCount: number;
}

// SSE event payloads — keep aligned with backend dto records.

export interface ChatChunkMeta {
  conversationId: string;
  userMessageId: string;
}

export interface ChatChunkToken {
  value: string;
}

export interface ChatChunkDone {
  assistantMessageId: string;
  intent: Intent;
}

export type ChatChunk =
  | { type: 'meta'; payload: ChatChunkMeta }
  | { type: 'token'; payload: ChatChunkToken }
  | { type: 'done'; payload: ChatChunkDone }
  | { type: 'error'; payload: { message: string } };
