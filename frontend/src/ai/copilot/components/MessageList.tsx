import React, { useEffect, useRef } from 'react';
import { useCopilot } from '../CopilotProvider';
import { AssistantMessage } from './AssistantMessage';
import { UserMessage } from './UserMessage';
import { SuggestedPrompts } from './SuggestedPrompts';

/**
 * Scrollable message column.
 *
 * Empty state shows {@link SuggestedPrompts}. Otherwise renders messages in
 * sequence and auto-scrolls to the bottom whenever new tokens stream in.
 */
export const MessageList: React.FC = () => {
  const { messages, isStreaming } = useCopilot();
  const bottomRef = useRef<HTMLDivElement | null>(null);

  // Sticky-bottom: scroll to the latest message on every change. Cheap; the
  // list is small and only the drawer scrolls.
  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: isStreaming ? 'auto' : 'smooth', block: 'end' });
  }, [messages, isStreaming]);

  if (messages.length === 0) {
    return (
      <div className="flex-1 overflow-y-auto">
        <SuggestedPrompts />
      </div>
    );
  }

  return (
    <div className="flex-1 overflow-y-auto px-4 py-5 space-y-5">
      {messages.map((m) =>
        m.role === 'USER'
          ? <UserMessage key={m.id} message={m} />
          : <AssistantMessage key={m.id} message={m} />
      )}
      <div ref={bottomRef} />
    </div>
  );
};
