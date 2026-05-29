import React from 'react';
import { Sparkles } from 'lucide-react';
import { useCopilot } from './CopilotProvider';

/**
 * Floating action button that opens the Copilot drawer.
 *
 * Bottom-right, gold accent + navy gradient — consistent with the rest of
 * the design system's "primary action" treatment.
 *
 * Hidden when the drawer is open so it doesn't clip the close button.
 */
export const CopilotLauncher: React.FC = () => {
  const { isOpen, open, isStreaming } = useCopilot();

  if (isOpen) return null;

  return (
    <button
      type="button"
      aria-label="Open Treasury Copilot"
      onClick={open}
      className={[
        'fixed z-40 bottom-6 right-6',
        'flex items-center gap-2',
        'pl-4 pr-5 py-3 rounded-full',
        'bg-gradient-to-br from-primary-700 to-primary-900',
        'dark:from-primary-800 dark:to-primary-950',
        'text-white shadow-xl shadow-primary-900/30',
        'border border-accent-400/40',
        'hover:shadow-2xl hover:shadow-primary-900/40',
        'hover:scale-[1.02] active:scale-[0.98]',
        'transition-all duration-200',
        'group',
      ].join(' ')}
    >
      <span className={[
        'flex items-center justify-center w-7 h-7 rounded-full',
        'bg-accent-400/20 group-hover:bg-accent-400/30 transition-colors',
        isStreaming && 'animate-pulse',
      ].filter(Boolean).join(' ')}>
        <Sparkles className="w-4 h-4 text-accent-300" strokeWidth={2.5} />
      </span>
      <span className="text-sm font-medium tracking-tight">
        Treasury Copilot
      </span>
    </button>
  );
};
