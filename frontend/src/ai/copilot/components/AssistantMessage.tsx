import React from 'react';
import { Sparkles } from 'lucide-react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import type { ActionProposal, ChatMessage, ToolCall } from '../types';
import { ToolCallRow } from './ToolCallRow';
import { ActionCard } from './ActionCard';

interface AssistantMessageProps {
  message: ChatMessage;
}

/** Extract an embedded action proposal from a tool call, if present. */
function actionFromCall(call: ToolCall): ActionProposal | null {
  const raw = call.data?._action;
  if (!raw || typeof raw !== 'object') return null;
  const obj = raw as Record<string, unknown>;
  if (!obj.proposalId || !obj.title) return null;
  return obj as unknown as ActionProposal;
}

/**
 * Assistant-side message. Left-aligned, markdown rendered (incl. GFM tables),
 * with a small Sparkles avatar and an expandable list of tool calls below
 * the body so the user can audit which data the answer came from.
 */
export const AssistantMessage: React.FC<AssistantMessageProps> = ({ message }) => (
  <div className="flex gap-3">
    <div className="shrink-0 w-7 h-7 mt-0.5 rounded-full bg-gradient-to-br from-primary-700 to-primary-900 dark:from-primary-800 dark:to-primary-950 flex items-center justify-center shadow-sm">
      <Sparkles className="w-3.5 h-3.5 text-accent-300" />
    </div>
    <div className="flex-1 min-w-0">
      <div
        className={[
          'copilot-prose',
          'text-sm leading-relaxed',
          'text-primary-900 dark:text-neutral-100',
        ].join(' ')}
      >
        {message.content ? (
          <ReactMarkdown remarkPlugins={[remarkGfm]}>{message.content}</ReactMarkdown>
        ) : (
          <ThinkingIndicator />
        )}
        {message.isStreaming && message.content && (
          <span
            className="inline-block w-1.5 h-4 ml-0.5 align-text-bottom bg-primary-500 dark:bg-accent-400 animate-pulse"
            aria-hidden="true"
          />
        )}
      </div>

      {message.toolCalls && message.toolCalls.length > 0 && (
        <div className="mt-3 space-y-1.5">
          {message.toolCalls.map((tc, i) => {
            const action = actionFromCall(tc);
            return (
              <React.Fragment key={`${message.id}-tc-${i}`}>
                <ToolCallRow call={tc} />
                {action && <ActionCard action={action} />}
              </React.Fragment>
            );
          })}
        </div>
      )}
    </div>
  </div>
);

/** Three-dot pulse shown before any tokens arrive. */
const ThinkingIndicator: React.FC = () => (
  <span className="inline-flex items-center gap-1 text-neutral-400 dark:text-neutral-500">
    <span className="w-1.5 h-1.5 rounded-full bg-current animate-bounce" style={{ animationDelay: '0ms' }} />
    <span className="w-1.5 h-1.5 rounded-full bg-current animate-bounce" style={{ animationDelay: '120ms' }} />
    <span className="w-1.5 h-1.5 rounded-full bg-current animate-bounce" style={{ animationDelay: '240ms' }} />
  </span>
);
