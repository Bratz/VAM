/**
 * Treasury Copilot API client.
 *
 * Two notable things:
 *  - `POST /chat` returns a Server-Sent Events stream. EventSource doesn't
 *    support POST + body, so we use `fetch` + a manual SSE parser over the
 *    response body's `ReadableStream`.
 *  - The dev server proxies `/api/*` to the Spring Boot backend on :8053,
 *    so this code uses relative URLs and works both in dev and prod.
 */

import type {
  ActionResult,
  ChatChunk,
  ConversationSummary,
  MessageView,
} from '../ai/copilot/types';

const BASE = '/api/ai/copilot';

export interface CopilotHealth {
  enabled: boolean;
  mode: string;
  phase: string;
  streamTokenDelayMs: number;
  actionProposalTtlMinutes: number;
}

export async function copilotHealth(signal?: AbortSignal): Promise<CopilotHealth> {
  const r = await fetch(`${BASE}/health`, { signal });
  if (!r.ok) throw new Error(`Copilot health ${r.status}`);
  const body = await r.json();
  return body.data as CopilotHealth;
}

export async function listConversations(
  userId = 'demo-user',
  signal?: AbortSignal
): Promise<ConversationSummary[]> {
  const r = await fetch(`${BASE}/conversations?userId=${encodeURIComponent(userId)}`, { signal });
  if (!r.ok) throw new Error(`listConversations ${r.status}`);
  const body = await r.json();
  return body.data as ConversationSummary[];
}

export async function listMessages(
  conversationId: string,
  signal?: AbortSignal
): Promise<MessageView[]> {
  const r = await fetch(`${BASE}/conversations/${conversationId}/messages`, { signal });
  if (!r.ok) throw new Error(`listMessages ${r.status}`);
  const body = await r.json();
  return body.data as MessageView[];
}

export interface ChatStreamOptions {
  conversationId?: string | null;
  message: string;
  userId?: string;
  onChunk: (chunk: ChatChunk) => void;
  signal?: AbortSignal;
}

/**
 * Stream a chat turn. Resolves when the stream ends; rejects on network
 * error or abort. Each parsed SSE event lands on {@link onChunk}.
 *
 * SSE parsing: per spec, an event is one or more `field: value\n` lines
 * terminated by a blank line. We accumulate by-line off the response
 * stream and flush on each blank.
 */
export async function streamChat(opts: ChatStreamOptions): Promise<void> {
  const { conversationId, message, userId, onChunk, signal } = opts;

  const response = await fetch(`${BASE}/chat`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
    },
    body: JSON.stringify({
      conversationId: conversationId ?? null,
      message,
      userId: userId ?? 'demo-user',
    }),
    signal,
  });

  if (!response.ok || !response.body) {
    throw new Error(`streamChat failed: HTTP ${response.status}`);
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';

  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });

    // Each SSE event ends with a blank line (\n\n). Process complete events
    // and keep the trailing partial in the buffer for the next chunk.
    let sep: number;
    while ((sep = buffer.indexOf('\n\n')) >= 0) {
      const eventText = buffer.slice(0, sep);
      buffer = buffer.slice(sep + 2);
      const chunk = parseSseEvent(eventText);
      if (chunk) onChunk(chunk);
    }
  }
  // Flush any trailing event without the closing blank line.
  if (buffer.trim().length > 0) {
    const chunk = parseSseEvent(buffer);
    if (chunk) onChunk(chunk);
  }
}

function parseSseEvent(eventText: string): ChatChunk | null {
  let event = '';
  let data = '';
  for (const line of eventText.split(/\r?\n/)) {
    if (line.startsWith('event:')) event = line.slice(6).trim();
    else if (line.startsWith('data:')) data += line.slice(5).trim();
  }
  if (!event || !data) return null;
  try {
    const payload = JSON.parse(data);
    switch (event) {
      case 'meta':
        return { type: 'meta', payload };
      case 'token':
        return { type: 'token', payload };
      case 'done':
        return { type: 'done', payload };
      case 'error':
        return { type: 'error', payload };
      default:
        return null;
    }
  } catch {
    return null;
  }
}

// =========================================================================
// Tool diagnostics — used in dev / future power-user mode
// =========================================================================

export interface ToolDescriptor {
  name: string;
  description: string;
  mutating: boolean;
  parameters: Record<string, Record<string, unknown>>;
}

export async function listTools(signal?: AbortSignal): Promise<ToolDescriptor[]> {
  const r = await fetch(`${BASE}/tools`, { signal });
  if (!r.ok) throw new Error(`listTools ${r.status}`);
  const body = await r.json();
  return body.data as ToolDescriptor[];
}

// =========================================================================
// Action proposals — Confirm / Cancel buttons on the assistant message
// =========================================================================

export async function executeAction(proposalId: string): Promise<ActionResult> {
  const r = await fetch(`${BASE}/actions/${proposalId}/execute`, { method: 'POST' });
  if (!r.ok) throw new Error(`executeAction ${r.status}`);
  const body = await r.json();
  return body.data as ActionResult;
}

export async function cancelAction(proposalId: string): Promise<ActionResult> {
  const r = await fetch(`${BASE}/actions/${proposalId}/cancel`, { method: 'POST' });
  if (!r.ok) throw new Error(`cancelAction ${r.status}`);
  const body = await r.json();
  return body.data as ActionResult;
}
