import React, { useEffect, useState } from 'react';
import { X, AlertTriangle, AlertCircle, MinusCircle, Clock } from 'lucide-react';
import { cn } from '../../utils';
import { Button } from '../ui';
import { AttentionItem, AttentionAction, AttentionSeverity } from '../../types/cockpit';

// ============================================================================
// Cockpit — attention drawer.
//
// Slide-in from the right edge with the full context for the row: severity +
// headline, the resolved context (bank/entity/currency/amount/reference/
// counterparty), an enlarged time-pressure indicator, the primary +
// secondary actions (Snooze, Dismiss), and a small audit preview block
// showing what will be recorded if the user confirms.
//
// V1 implementation: a controlled overlay (no third-party drawer dep). The
// existing `Modal` primitive in components/ui/enhanced.tsx does the
// centred-modal job; this is the adjacent slide-in pattern that doesn't
// exist there yet, so we hand-roll one here. Width caps at 480px on
// desktop and goes full-screen on mobile.
//
// Audit semantics: the preview block is read-only context — the actual
// audit write happens in the action handler (registered by the parent).
// We surface the planned `auditPreview` here so the user sees what's about
// to be recorded before confirming.
// ============================================================================

interface AttentionDrawerProps {
  open: boolean;
  item: AttentionItem | null;
  onClose: () => void;
  onAction: (item: AttentionItem, action: AttentionAction, payload?: unknown) => void;
  /** Snooze a row until end-of-day with a reason. */
  onSnooze: (item: AttentionItem, reason: string) => void;
  /** Optional related-items lookup — for V1 the parent passes a client-side filter. */
  relatedItems?: AttentionItem[];
}

const SEVERITY_TONES: Record<AttentionSeverity, { bg: string; icon: React.ReactNode; label: string }> = {
  critical: { bg: 'bg-error-100 text-error-800 dark:bg-error-500/20 dark:text-error-200',       icon: <AlertCircle className="w-3.5 h-3.5" />,    label: 'Critical' },
  high:     { bg: 'bg-warning-100 text-warning-800 dark:bg-warning-500/20 dark:text-warning-200', icon: <AlertTriangle className="w-3.5 h-3.5" />, label: 'High' },
  medium:   { bg: 'bg-neutral-100 text-neutral-700 dark:bg-primary-800/60 dark:text-neutral-200', icon: <MinusCircle className="w-3.5 h-3.5" />,   label: 'Medium' },
};

export const AttentionDrawer: React.FC<AttentionDrawerProps> = ({
  open,
  item,
  onClose,
  onAction,
  onSnooze,
  relatedItems,
}) => {
  const [snoozeReason, setSnoozeReason] = useState('');

  // Esc closes the drawer.
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open, onClose]);

  useEffect(() => { if (!open) setSnoozeReason(''); }, [open]);

  if (!open || !item) return null;
  const tone = SEVERITY_TONES[item.severity];
  const ctx = item.context;
  // Audit preview — what we'll record on confirm.
  const auditPreview = {
    action: 'cockpit.attention.action.executed',
    itemId: item.id,
    actionId: item.actions[0]?.actionId,
    severity: item.severity,
    category: item.category,
    headline: item.headline,
  };

  return (
    <div className="absolute inset-0 z-50">
      {/* Scrim — clicking it dismisses the drawer. Uses `inset-0` (not
          position: fixed) so the drawer stays scoped to the page area; the
          parent `<main>` already clips with overflow. */}
      <button
        aria-label="Close drawer"
        onClick={onClose}
        className="absolute inset-0 bg-primary-950/30 dark:bg-primary-950/50"
      />
      <div
        role="dialog"
        aria-modal="true"
        aria-label={item.headline}
        className={cn(
          'absolute inset-y-0 right-0 w-full md:w-[480px] bg-white dark:bg-primary-900',
          'border-l border-neutral-200 dark:border-primary-800 shadow-sm',
          'flex flex-col overflow-hidden',
        )}
      >
        {/* Header */}
        <div className="flex items-start justify-between gap-3 p-4 border-b border-neutral-100 dark:border-primary-800/60">
          <div className="min-w-0 flex-1">
            <span className={cn('inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[11px] font-medium', tone.bg)}>
              {tone.icon} {tone.label}
            </span>
            <h3 className="section-title mt-2">{item.headline}</h3>
            <p className="body-sm text-neutral-500 dark:text-neutral-400 mt-1">{item.detail}</p>
          </div>
          <button onClick={onClose} aria-label="Close" className="p-2 -m-2 text-neutral-500 hover:text-neutral-700 dark:hover:text-neutral-200">
            <X className="w-5 h-5" />
          </button>
        </div>

        <div className="flex-1 overflow-y-auto p-4 space-y-4">
          {/* Time pressure (large) */}
          <div className="flex items-center gap-2 p-3 rounded-lg bg-neutral-50 dark:bg-primary-800/40">
            <Clock className="w-5 h-5 text-neutral-500 dark:text-neutral-400" />
            <div>
              <p className="label">Time pressure</p>
              <p className="stat-value-xs">{item.timePressure.displayText}</p>
            </div>
          </div>

          {/* Context */}
          <div>
            <p className="label">Context</p>
            <dl className="mt-2 grid grid-cols-2 gap-2 body-sm">
              {ctx.bankName && <div><dt className="text-neutral-500 dark:text-neutral-400">Bank</dt><dd className="text-primary-900 dark:text-neutral-50">{ctx.bankName}</dd></div>}
              {ctx.bankBic && <div><dt className="text-neutral-500 dark:text-neutral-400">BIC</dt><dd className="font-mono text-primary-900 dark:text-neutral-50">{ctx.bankBic}</dd></div>}
              {ctx.entityName && <div><dt className="text-neutral-500 dark:text-neutral-400">Entity</dt><dd className="text-primary-900 dark:text-neutral-50">{ctx.entityName}</dd></div>}
              {ctx.currencyCode && <div><dt className="text-neutral-500 dark:text-neutral-400">Currency</dt><dd className="font-mono text-primary-900 dark:text-neutral-50">{ctx.currencyCode}</dd></div>}
              {ctx.nativeAmount && <div className="col-span-2"><dt className="text-neutral-500 dark:text-neutral-400">Amount</dt><dd className="font-mono text-primary-900 dark:text-neutral-50">{ctx.nativeAmount.value.toLocaleString()} {ctx.nativeAmount.currency}</dd></div>}
              {ctx.reference && <div className="col-span-2"><dt className="text-neutral-500 dark:text-neutral-400">Reference</dt><dd className="font-mono text-primary-900 dark:text-neutral-50">{ctx.reference}</dd></div>}
              {ctx.counterpartyName && <div className="col-span-2"><dt className="text-neutral-500 dark:text-neutral-400">Counterparty</dt><dd className="text-primary-900 dark:text-neutral-50">{ctx.counterpartyName}</dd></div>}
            </dl>
          </div>

          {/* Last 24h on this scope */}
          {relatedItems && relatedItems.length > 0 && (
            <div>
              <p className="label">Last 24h on this scope</p>
              <ul className="mt-2 space-y-1.5">
                {relatedItems.slice(0, 5).map((r) => (
                  <li key={r.id} className="body-sm text-neutral-600 dark:text-neutral-300 truncate">
                    · {r.headline}
                    <span className="ml-2 text-neutral-500 dark:text-neutral-400">{r.timePressure.displayText}</span>
                  </li>
                ))}
              </ul>
            </div>
          )}

          {/* Audit preview */}
          <div className="rounded-lg bg-neutral-100 dark:bg-primary-800/60 p-3">
            <p className="label mb-1.5">If you confirm, this is recorded:</p>
            <pre className="text-[11px] font-mono text-neutral-600 dark:text-neutral-300 whitespace-pre-wrap break-all">
{JSON.stringify(auditPreview, null, 2)}
            </pre>
          </div>
        </div>

        {/* Action footer */}
        <div className="border-t border-neutral-200 dark:border-primary-800 p-4 space-y-2">
          {item.actions.map((action, idx) => (
            <Button
              key={action.actionId}
              fullWidth
              variant={idx === 0 ? (item.severity === 'critical' ? 'primary' : 'outline') : 'ghost'}
              onClick={() => onAction(item, action)}
            >
              {action.label}
              {action.requiresSecondFactor ? ' · 2FA required' : action.requiresConfirmation ? ' · confirms' : ''}
            </Button>
          ))}
          <div className="flex items-center gap-2">
            <input
              type="text"
              value={snoozeReason}
              onChange={(e) => setSnoozeReason(e.target.value)}
              placeholder="Snooze reason…"
              className="flex-1 px-3 py-1.5 rounded-lg border border-neutral-300 dark:border-primary-700 bg-white dark:bg-primary-900 text-sm text-primary-900 dark:text-neutral-50 placeholder:text-neutral-400"
            />
            <Button
              variant="ghost"
              size="sm"
              disabled={!snoozeReason.trim()}
              onClick={() => { onSnooze(item, snoozeReason.trim()); }}
            >
              Snooze EOD
            </Button>
          </div>
        </div>
      </div>
    </div>
  );
};
