import React, { useEffect, useRef, useState } from 'react';
import { Send, StopCircle } from 'lucide-react';
import { useCopilot } from '../CopilotProvider';

/**
 * Bottom-of-drawer textarea + send button.
 *
 * Behaviour:
 *  - Enter sends; Shift+Enter inserts a newline (chat convention).
 *  - Cmd/Ctrl+Enter also sends (power-user habit).
 *  - Auto-resizes between 1 and 6 rows.
 *  - Disabled while a turn is streaming.
 */
export const Composer: React.FC = () => {
  const { send, isStreaming } = useCopilot();
  const [value, setValue] = useState('');
  const ref = useRef<HTMLTextAreaElement | null>(null);

  // Auto-resize.
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    el.style.height = '0px';
    const max = parseFloat(getComputedStyle(el).lineHeight) * 6 + 16;
    el.style.height = `${Math.min(el.scrollHeight, max)}px`;
  }, [value]);

  const submit = async () => {
    const text = value.trim();
    if (!text || isStreaming) return;
    setValue('');
    await send(text);
  };

  const onKey = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      submit();
    } else if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) {
      e.preventDefault();
      submit();
    }
  };

  return (
    <form
      className={[
        'flex items-end gap-2 p-3',
        'border-t border-neutral-200 dark:border-primary-800',
        'bg-white/95 dark:bg-primary-950/80',
        'backdrop-blur-sm',
      ].join(' ')}
      onSubmit={(e) => {
        e.preventDefault();
        submit();
      }}
    >
      <textarea
        ref={ref}
        value={value}
        onChange={(e) => setValue(e.target.value)}
        onKeyDown={onKey}
        rows={1}
        disabled={isStreaming}
        placeholder={isStreaming ? 'Copilot is thinking…' : 'Ask about positions, sweeps, statements…'}
        className={[
          'flex-1 resize-none min-h-[40px]',
          'px-3 py-2 rounded-xl',
          'text-sm text-primary-900 dark:text-neutral-100',
          'bg-neutral-50 dark:bg-primary-900/60',
          'border border-neutral-200 dark:border-primary-700',
          'focus:outline-none focus:ring-2 focus:ring-accent-400/40 focus:border-accent-400/60',
          'placeholder:text-neutral-400 dark:placeholder:text-neutral-500',
          'disabled:opacity-60 disabled:cursor-not-allowed',
        ].join(' ')}
      />
      <button
        type="submit"
        disabled={isStreaming || !value.trim()}
        className={[
          'shrink-0 h-10 w-10 rounded-xl',
          'bg-primary-700 hover:bg-primary-800 dark:bg-primary-800 dark:hover:bg-primary-700',
          'text-white',
          'disabled:opacity-40 disabled:cursor-not-allowed',
          'transition-colors',
          'flex items-center justify-center',
        ].join(' ')}
        aria-label={isStreaming ? 'Copilot is streaming' : 'Send message'}
      >
        {isStreaming ? <StopCircle className="w-4 h-4 animate-pulse" /> : <Send className="w-4 h-4" />}
      </button>
    </form>
  );
};
