import React, { useState } from 'react';
import { Check, X, Loader2, ShieldAlert, Clock, ListTree } from 'lucide-react';
import toast from 'react-hot-toast';
import { cancelAction, executeAction } from '../../../services/copilotApi';
import type { ActionProposal } from '../types';

interface ActionCardProps {
  action: ActionProposal;
}

type CardState =
  | { kind: 'idle' }
  | { kind: 'submitting' }
  | { kind: 'executed'; message: string }
  | { kind: 'cancelled'; message: string }
  | { kind: 'failed'; message: string };

/**
 * Confirm / Cancel card for a write action proposed by Copilot.
 *
 * Always inline with the assistant reply (rendered by {@link AssistantMessage}
 * via toolCalls). Clicking Confirm hits the execute endpoint; the card
 * collapses to a single-line status pill on success or failure.
 *
 * Idempotency is enforced server-side — clicking Confirm twice is safe and
 * shows the same success result. Cancel cancels in-place.
 */
export const ActionCard: React.FC<ActionCardProps> = ({ action }) => {
  const [state, setState] = useState<CardState>({ kind: 'idle' });

  const isMutating = state.kind === 'submitting';
  const isDone = state.kind === 'executed' || state.kind === 'cancelled';

  const onConfirm = async () => {
    setState({ kind: 'submitting' });
    try {
      const r = await executeAction(action.proposalId);
      if (r.ok) {
        setState({ kind: 'executed', message: r.message });
        toast.success(r.message || 'Action executed');
      } else {
        setState({ kind: 'failed', message: r.message });
        toast.error(r.message || 'Action failed');
      }
    } catch (err) {
      const msg = (err as Error).message || 'Network error';
      setState({ kind: 'failed', message: msg });
      toast.error(msg);
    }
  };

  const onCancel = async () => {
    setState({ kind: 'submitting' });
    try {
      const r = await cancelAction(action.proposalId);
      setState({ kind: 'cancelled', message: r.message || 'Cancelled' });
    } catch (err) {
      // Cancel is best-effort — if the network fails, mark cancelled locally
      // since the user clearly wants out.
      setState({
        kind: 'cancelled',
        message: 'Cancelled (network error — proposal will expire on its own)',
      });
    }
  };

  return (
    <div
      className={[
        'mt-2 rounded-xl border overflow-hidden',
        'bg-warning-50/60 dark:bg-warning-900/20',
        'border-warning-200 dark:border-warning-800',
      ].join(' ')}
    >
      {/* Header */}
      <div className="flex items-center gap-2 px-3.5 py-2.5 border-b border-warning-200 dark:border-warning-800/60">
        <ShieldAlert className="w-4 h-4 text-warning-600 dark:text-warning-300 shrink-0" />
        <span className="text-xs font-semibold uppercase tracking-wider text-warning-700 dark:text-warning-200">
          Action requires confirmation
        </span>
        <span className="ml-auto text-[10px] text-warning-700/70 dark:text-warning-300/70 flex items-center gap-1">
          <Clock className="w-3 h-3" />
          expires {formatExpiry(action.expiresAt)}
        </span>
      </div>

      {/* Body */}
      <div className="px-3.5 py-3 space-y-2">
        <h4 className="font-display text-sm font-semibold text-primary-900 dark:text-neutral-50">
          {action.title}
        </h4>
        <p className="text-xs text-neutral-700 dark:text-neutral-300 leading-relaxed">
          {action.description}
        </p>
        <ParamsTable params={action.params} />
      </div>

      {/* Footer — buttons or status */}
      <div className="px-3.5 py-2.5 border-t border-warning-200 dark:border-warning-800/60 bg-white/40 dark:bg-primary-950/30">
        {state.kind === 'idle' && (
          <div className="flex items-center gap-2 justify-end">
            <button
              type="button"
              onClick={onCancel}
              className="px-3 py-1.5 text-xs rounded-lg border border-neutral-300 dark:border-primary-700 text-neutral-700 dark:text-neutral-200 hover:bg-neutral-50 dark:hover:bg-primary-900 transition-colors"
            >
              Cancel
            </button>
            <button
              type="button"
              onClick={onConfirm}
              className="px-3 py-1.5 text-xs rounded-lg bg-primary-700 hover:bg-primary-800 text-white font-medium flex items-center gap-1.5 transition-colors"
            >
              <Check className="w-3.5 h-3.5" />
              Confirm
            </button>
          </div>
        )}

        {state.kind === 'submitting' && (
          <div className="flex items-center gap-2 text-xs text-neutral-600 dark:text-neutral-300 justify-end">
            <Loader2 className="w-3.5 h-3.5 animate-spin" />
            Executing…
          </div>
        )}

        {state.kind === 'executed' && (
          <div className="flex items-start gap-2 text-xs text-success-700 dark:text-success-300">
            <Check className="w-3.5 h-3.5 mt-0.5 shrink-0" />
            <span>{state.message}</span>
          </div>
        )}

        {state.kind === 'cancelled' && (
          <div className="flex items-start gap-2 text-xs text-neutral-600 dark:text-neutral-400">
            <X className="w-3.5 h-3.5 mt-0.5 shrink-0" />
            <span>{state.message}</span>
          </div>
        )}

        {state.kind === 'failed' && (
          <div className="flex items-center justify-between gap-2 text-xs text-error-600 dark:text-error-300">
            <div className="flex items-start gap-2">
              <X className="w-3.5 h-3.5 mt-0.5 shrink-0" />
              <span>{state.message}</span>
            </div>
            {!isDone && (
              <button
                type="button"
                onClick={onConfirm}
                disabled={isMutating}
                className="px-2 py-1 text-[11px] rounded border border-error-300 hover:bg-error-50 dark:border-error-700 dark:hover:bg-error-900/40"
              >
                Retry
              </button>
            )}
          </div>
        )}
      </div>
    </div>
  );
};

/** Tiny key/value list for the proposal's params. */
const ParamsTable: React.FC<{ params: Record<string, unknown> }> = ({ params }) => {
  const entries = Object.entries(params).filter(
    ([k]) => k !== 'ruleEntityId' && k !== 'vaId'
  );
  if (entries.length === 0) return null;
  return (
    <div className="rounded-md bg-white/70 dark:bg-primary-950/40 border border-warning-100/80 dark:border-warning-800/40 p-2">
      <div className="flex items-center gap-1.5 mb-1 text-[10px] uppercase tracking-wider text-neutral-500 dark:text-neutral-400">
        <ListTree className="w-3 h-3" /> Parameters
      </div>
      <div className="grid grid-cols-[auto,1fr] gap-x-3 gap-y-1 text-xs font-mono">
        {entries.map(([k, v]) => (
          <React.Fragment key={k}>
            <span className="text-neutral-500 dark:text-neutral-400">{k}</span>
            <span className="text-primary-900 dark:text-neutral-100 break-all">
              {String(v)}
            </span>
          </React.Fragment>
        ))}
      </div>
    </div>
  );
};

/** "in 9m", "in 2h", or the absolute time if more than 24h out. */
function formatExpiry(iso: string): string {
  const target = new Date(iso).getTime();
  const now = Date.now();
  const dMs = target - now;
  if (dMs <= 0) return 'now';
  const dMin = Math.round(dMs / 60_000);
  if (dMin < 60) return `in ${dMin}m`;
  const dH = Math.round(dMin / 60);
  if (dH < 24) return `in ${dH}h`;
  return new Date(iso).toLocaleString();
}
