import React, { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { MoreHorizontal } from 'lucide-react';
import { cn } from '../../utils';

// ============================================================================
// RowActions — the "..." menu at the end of a table row. AccountsPage, BeneficiariesPage, PartiesPage and
// others each hand-rolled this; new lists use this one. The row's own click still opens its detail, so the
// trigger stops propagation. Items that do not apply to a row are left out by the caller (pass `hidden`), so
// the menu only ever shows what can be done to that row.
//
// The menu is drawn in a portal at fixed coordinates, not inside the cell: DataTable scrolls horizontally,
// which clips anything absolutely positioned inside it (a menu on the last row of a short table showed as a
// sliver). It opens upward when there is no room below, and closes on scroll or resize rather than drift.
// ============================================================================

export interface RowAction {
  label: string;
  icon?: React.ReactNode;
  onClick: () => void;
  /** Error tone, for an action that cannot be undone. */
  danger?: boolean;
  hidden?: boolean;
  /** Draws a rule above this item. */
  divider?: boolean;
}

const MENU_WIDTH = 224;
const ITEM_HEIGHT = 40;

export const RowActions: React.FC<{ actions: RowAction[]; label?: string }> = ({ actions, label = 'Row actions' }) => {
  const [pos, setPos] = useState<{ top: number; left: number } | null>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const menu = useRef<HTMLDivElement>(null);
  const visible = actions.filter((a) => !a.hidden);
  const open = pos !== null;

  useEffect(() => {
    if (!open) return;
    const close = () => setPos(null);
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') close(); };
    const onDown = (e: MouseEvent) => {
      const t = e.target as Node;
      if (!menu.current?.contains(t) && !trigger.current?.contains(t)) close();
    };
    document.addEventListener('keydown', onKey);
    document.addEventListener('mousedown', onDown);
    window.addEventListener('resize', close);
    window.addEventListener('scroll', close, true);
    return () => {
      document.removeEventListener('keydown', onKey);
      document.removeEventListener('mousedown', onDown);
      window.removeEventListener('resize', close);
      window.removeEventListener('scroll', close, true);
    };
  }, [open]);

  if (!visible.length) return null;

  const toggle = () => {
    if (open) { setPos(null); return; }
    const r = trigger.current?.getBoundingClientRect();
    if (!r) return;
    const height = visible.length * ITEM_HEIGHT + 8;
    const below = r.bottom + 4;
    setPos({
      top: below + height > window.innerHeight ? Math.max(8, r.top - 4 - height) : below,
      left: Math.max(8, Math.min(r.right - MENU_WIDTH, window.innerWidth - MENU_WIDTH - 8)),
    });
  };

  return (
    <div className="relative text-left" onClick={(e) => e.stopPropagation()}>
      <button
        ref={trigger}
        type="button"
        aria-label={label}
        aria-haspopup="menu"
        aria-expanded={open}
        onClick={toggle}
        className="p-2 hover:bg-neutral-100 rounded-lg transition-colors dark:hover:bg-primary-800"
      >
        <MoreHorizontal className="w-4 h-4 text-neutral-500 dark:text-neutral-400" />
      </button>
      {pos && createPortal(
        <div
          ref={menu}
          role="menu"
          style={{ position: 'fixed', top: pos.top, left: pos.left, width: MENU_WIDTH }}
          className="bg-surface-card rounded-lg shadow-lg border border-edge py-1 z-50 animate-fade-in"
          onClick={(e) => e.stopPropagation()}
        >
          {visible.map((a, i) => (
            <React.Fragment key={a.label}>
              {a.divider && i > 0 && <hr className="my-1 border-edge-subtle" />}
              <button
                type="button"
                role="menuitem"
                onClick={() => { setPos(null); a.onClick(); }}
                className={cn(
                  'w-full flex items-center gap-2 px-4 py-2.5 text-body-sm transition-colors',
                  a.danger
                    ? 'text-error-600 hover:bg-error-50 dark:text-error-300 dark:hover:bg-error-500/10'
                    : 'text-primary-900 hover:bg-neutral-50 dark:text-neutral-50 dark:hover:bg-primary-800/50',
                )}
              >
                {a.icon && <span className={cn('w-4 h-4 shrink-0', !a.danger && 'text-neutral-500 dark:text-neutral-400')}>{a.icon}</span>}
                {a.label}
              </button>
            </React.Fragment>
          ))}
        </div>,
        document.body,
      )}
    </div>
  );
};
