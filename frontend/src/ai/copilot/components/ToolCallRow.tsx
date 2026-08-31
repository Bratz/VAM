import React, { useState } from 'react';
import { ChevronRight, Database, AlertCircle } from 'lucide-react';
import type { ToolCall } from '../types';

interface ToolCallRowProps {
  call: ToolCall;
}

/**
 * Collapsible "Looked up X" row underneath an assistant message.
 *
 * Collapsed shows the one-line summary the tool returned. Expanding reveals
 * the JSON-formatted {@code data} block so the user can see exactly what
 * the answer was grounded in — important trust signal for a stub.
 */
export const ToolCallRow: React.FC<ToolCallRowProps> = ({ call }) => {
  const [expanded, setExpanded] = useState(false);
  const ok = call.ok;

  return (
    <div className={[
      'rounded-lg text-xs',
      ok
        ? 'bg-primary-50 dark:bg-primary-900/40 border border-primary-100 dark:border-primary-800'
        : 'bg-error-50 dark:bg-error-900/30 border border-error-100 dark:border-error-800',
    ].join(' ')}>
      <button
        type="button"
        onClick={() => setExpanded((v) => !v)}
        className="w-full flex items-center gap-2 px-2.5 py-1.5 text-left"
      >
        <ChevronRight
          className={[
            'w-3 h-3 transition-transform shrink-0',
            'text-neutral-400 dark:text-neutral-500',
            expanded && 'rotate-90',
          ].filter(Boolean).join(' ')}
        />
        {ok
          ? <Database className="w-3.5 h-3.5 text-primary-500 dark:text-primary-300 shrink-0" />
          : <AlertCircle className="w-3.5 h-3.5 text-error-500 shrink-0" />}
        <span className="font-mono text-primary-700 dark:text-primary-200 shrink-0">
          {call.toolName}
        </span>
        <span className="text-neutral-600 dark:text-neutral-300 truncate">
          {call.summary}
        </span>
      </button>

      {expanded && (
        <div className="px-2.5 pb-2 pt-1 border-t border-primary-100/60 dark:border-primary-800/60">
          {call.errorMessage && (
            <div className="text-error-600 dark:text-error-300 mb-1.5">
              {call.errorMessage}
            </div>
          )}
          <pre className="font-mono text-[11px] whitespace-pre-wrap break-all text-neutral-700 dark:text-neutral-300 max-h-64 overflow-y-auto">
            {JSON.stringify(call.data, null, 2)}
          </pre>
        </div>
      )}
    </div>
  );
};
