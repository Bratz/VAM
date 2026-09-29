import { createContext, useContext } from 'react';
import type { ChatMessage, Intent } from './types';

/**
 * Public API of the Copilot context.
 *
 * Kept narrow on purpose — the drawer consumes only what it needs.
 * Renaming consumers requires changing here.
 */
export interface CopilotContextValue {
  /** Whether the drawer is currently shown. */
  isOpen: boolean;
  open: () => void;
  close: () => void;
  toggle: () => void;

  /** Conversation currently being shown. {@code null} = brand-new turn coming. */
  conversationId: string | null;
  /** Reset to a fresh conversation (clears messages, drops conversationId). */
  newConversation: () => void;

  /** Ordered messages (USER + ASSISTANT + tool-call rows). */
  messages: ChatMessage[];

  /** True while a turn is in flight (between user send and {@code done} event). */
  isStreaming: boolean;
  /** Intent matched by the in-flight turn, surfaced as soon as the {@code done} event lands. */
  lastIntent: Intent | null;

  /** Submit a user message. No-op if already streaming. */
  send: (message: string) => Promise<void>;
}

export const CopilotContext = createContext<CopilotContextValue | null>(null);

/** Hook for drawer / launcher / suggested-prompts consumers. */
export function useCopilot(): CopilotContextValue {
  const ctx = useContext(CopilotContext);
  if (!ctx) throw new Error('useCopilot must be used inside <CopilotProvider>');
  return ctx;
}
