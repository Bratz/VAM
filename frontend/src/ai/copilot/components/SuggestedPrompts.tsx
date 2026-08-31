import React from 'react';
import { TrendingUp, AlertTriangle, ListChecks, Banknote, Activity, BellRing } from 'lucide-react';
import { useDefaultCurrency } from '../../../context/MarketContext';
import { useCopilot } from '../CopilotProvider';

/**
 * Six starter prompts shown when the conversation is empty.
 *
 * Each is keyed to a known intent so the demo always works without typing.
 * Clicking calls {@code send} directly — no need to populate the composer.
 *
 * The two currency-bearing prompts pick up the active market profile so a
 * UK deployment sees "GBP position" and a UAE one sees "AED position".
 */
export const SuggestedPrompts: React.FC = () => {
  const ccy = useDefaultCurrency();
  const { send, isStreaming } = useCopilot();

  const prompts: Array<{ icon: React.ReactNode; label: string; text: string; tone: string }> = [
    {
      icon: <TrendingUp className="w-4 h-4" />,
      tone: 'text-success-600 dark:text-success-300 bg-success-50 dark:bg-success-900/30 border-success-100 dark:border-success-800',
      label: `${ccy} position`,
      text: `What's our ${ccy} position?`,
    },
    {
      icon: <Banknote className="w-4 h-4" />,
      tone: 'text-info-600 dark:text-info-300 bg-info-50 dark:bg-info-900/30 border-info-100 dark:border-info-800',
      label: 'by bank',
      text: `${ccy} position by bank`,
    },
    {
      icon: <ListChecks className="w-4 h-4" />,
      tone: 'text-primary-700 dark:text-primary-200 bg-primary-50 dark:bg-primary-900/40 border-primary-100 dark:border-primary-800',
      label: 'sweep rules',
      text: 'List sweep rules',
    },
    {
      icon: <AlertTriangle className="w-4 h-4" />,
      tone: 'text-warning-600 dark:text-warning-300 bg-warning-50 dark:bg-warning-900/30 border-warning-100 dark:border-warning-800',
      label: 'failed sweeps',
      text: 'Show failed sweeps in the last 24 hours',
    },
    {
      icon: <Activity className="w-4 h-4" />,
      tone: 'text-accent-700 dark:text-accent-300 bg-accent-50 dark:bg-accent-900/30 border-accent-100 dark:border-accent-800',
      label: 'recent activity',
      text: 'What happened today?',
    },
    {
      icon: <BellRing className="w-4 h-4" />,
      tone: 'text-error-600 dark:text-error-300 bg-error-50 dark:bg-error-900/30 border-error-100 dark:border-error-800',
      label: 'alert',
      // Uses a real seeded account so the action card path works out of the box.
      // Swap to a corporate-specific reference for tailored demos.
      text: 'Alert me if VA-DUBAI-001 drops below 50000',
    },
  ];

  return (
    <div className="p-5">
      <p className="text-xs uppercase tracking-wider text-neutral-500 dark:text-neutral-400 mb-3">
        Try one of these
      </p>
      <div className="grid grid-cols-1 gap-2">
        {prompts.map((p) => (
          <button
            key={p.label}
            type="button"
            disabled={isStreaming}
            onClick={() => send(p.text)}
            className={[
              'w-full flex items-center gap-3 px-3 py-2.5 rounded-xl',
              'border text-left',
              p.tone,
              'hover:shadow-sm hover:scale-[1.005] active:scale-[0.99]',
              'transition-all duration-150',
              'disabled:opacity-50 disabled:cursor-not-allowed disabled:hover:shadow-none disabled:hover:scale-100',
            ].join(' ')}
          >
            <span className="shrink-0">{p.icon}</span>
            <span className="text-sm flex-1 min-w-0">
              <span className="font-medium">{p.label}</span>
              <span className="block text-xs opacity-80 truncate">{p.text}</span>
            </span>
          </button>
        ))}
      </div>
    </div>
  );
};
