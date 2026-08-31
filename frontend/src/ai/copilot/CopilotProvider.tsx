import React, {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useRef,
  useState,
} from 'react';
import { listMessages, streamChat } from '../../services/copilotApi';
import type { ChatMessage, Intent } from './types';

/**
 * Public API of the Copilot context.
 *
 * Kept narrow on purpose — the drawer consumes only what it needs.
 * Renaming consumers requires changing here.
 */
interface CopilotContextValue {
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

const CopilotContext = createContext<CopilotContextValue | null>(null);

/** Hook for drawer / launcher / suggested-prompts consumers. */
export function useCopilot(): CopilotContextValue {
  const ctx = useContext(CopilotContext);
  if (!ctx) throw new Error('useCopilot must be used inside <CopilotProvider>');
  return ctx;
}

/**
 * Stateful provider. Wraps the app once (above {@code Layout}) and owns:
 *  - open/close
 *  - current conversation id + persisted history
 *  - the live streaming buffer for the in-flight assistant message
 *
 * Design notes:
 *  - The streaming message is held under a synthetic id (`streaming-{ts}`)
 *    and replaced atomically with the persisted view on `done` so the
 *    drawer doesn't blink.
 *  - We do not auto-restore a prior conversation across page loads — the
 *    drawer always opens fresh. Conversation history can be restored later
 *    via a sidebar list (out of scope for P4).
 */
export const CopilotProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [isOpen, setIsOpen] = useState(false);
  const [conversationId, setConversationId] = useState<string | null>(null);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [isStreaming, setIsStreaming] = useState(false);
  const [lastIntent, setLastIntent] = useState<Intent | null>(null);
  const abortRef = useRef<AbortController | null>(null);

  // Abort any in-flight stream if the provider unmounts (HMR, route change).
  useEffect(() => () => abortRef.current?.abort(), []);

  const open = useCallback(() => setIsOpen(true), []);
  const close = useCallback(() => setIsOpen(false), []);
  const toggle = useCallback(() => setIsOpen((o) => !o), []);

  const newConversation = useCallback(() => {
    abortRef.current?.abort();
    setConversationId(null);
    setMessages([]);
    setIsStreaming(false);
    setLastIntent(null);
  }, []);

  const send = useCallback(
    async (message: string) => {
      if (!message.trim() || isStreaming) return;

      const userTempId = `user-${Date.now()}`;
      const streamingId = `streaming-${Date.now()}`;

      setMessages((prev) => [
        ...prev,
        { id: userTempId, role: 'USER', content: message, pending: true },
        { id: streamingId, role: 'ASSISTANT', content: '', isStreaming: true },
      ]);
      setIsStreaming(true);
      setLastIntent(null);

      const ac = new AbortController();
      abortRef.current = ac;

      try {
        await streamChat({
          conversationId,
          message,
          onChunk: (chunk) => {
            if (chunk.type === 'meta') {
              // Persist the resolved conversation id + user message id.
              setConversationId(chunk.payload.conversationId);
              setMessages((prev) =>
                prev.map((m) =>
                  m.id === userTempId
                    ? { ...m, id: chunk.payload.userMessageId, pending: false }
                    : m
                )
              );
            } else if (chunk.type === 'token') {
              setMessages((prev) =>
                prev.map((m) =>
                  m.id === streamingId ? { ...m, content: m.content + chunk.payload.value } : m
                )
              );
            } else if (chunk.type === 'done') {
              const { assistantMessageId, intent } = chunk.payload;
              setLastIntent(intent);
              // Mark the streamed assistant as final under its real id —
              // toolCalls get filled below via a one-shot refetch.
              setMessages((prev) =>
                prev.map((m) =>
                  m.id === streamingId
                    ? { ...m, id: assistantMessageId, intent, isStreaming: false }
                    : m
                )
              );
            } else if (chunk.type === 'error') {
              setMessages((prev) =>
                prev.map((m) =>
                  m.id === streamingId
                    ? { ...m, content: `⚠️ ${chunk.payload.message}`, isStreaming: false }
                    : m
                )
              );
            }
          },
          signal: ac.signal,
        });
      } catch (err) {
        if ((err as { name?: string }).name !== 'AbortError') {
          setMessages((prev) =>
            prev.map((m) =>
              m.id === streamingId
                ? {
                    ...m,
                    content: `⚠️ Copilot is offline (${(err as Error).message}). Try again in a moment.`,
                    isStreaming: false,
                  }
                : m
            )
          );
        }
      } finally {
        setIsStreaming(false);
        abortRef.current = null;
      }

      // After the stream ends successfully, fetch the assistant message
      // again to pick up its tool_calls jsonb (which the SSE stream doesn't
      // carry). Best-effort — UI works without it.
      // Note: conversationId comes from the meta event so it'll be set by now.
      const idForFetch = conversationIdRef.current;
      if (idForFetch && !ac.signal.aborted) {
        try {
          const full = await listMessages(idForFetch);
          setMessages((prev) => mergeToolCalls(prev, full));
        } catch {
          /* non-fatal */
        }
      }
    },
    [isStreaming, conversationId]
  );

  // Keep a ref so the closure above always sees the latest conversationId
  // (which is set during the stream by the meta event).
  const conversationIdRef = useRef<string | null>(null);
  useEffect(() => {
    conversationIdRef.current = conversationId;
  }, [conversationId]);

  const value: CopilotContextValue = {
    isOpen,
    open,
    close,
    toggle,
    conversationId,
    newConversation,
    messages,
    isStreaming,
    lastIntent,
    send,
  };

  return <CopilotContext.Provider value={value}>{children}</CopilotContext.Provider>;
};

/**
 * Walks the local UI messages and copies {@code toolCalls} from the
 * persisted view onto matching ids. Doesn't touch streamed content so the
 * user never sees a flicker.
 */
function mergeToolCalls(
  current: ChatMessage[],
  persisted: { id: string; toolCalls: unknown }[]
): ChatMessage[] {
  const byId = new Map(persisted.map((p) => [p.id, p.toolCalls]));
  return current.map((m) => {
    const tc = byId.get(m.id);
    if (Array.isArray(tc) && tc.length > 0) {
      return { ...m, toolCalls: tc as ChatMessage['toolCalls'] };
    }
    return m;
  });
}
