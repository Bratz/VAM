import React, { useEffect, useRef, useState } from 'react';
import { HelpCircle } from 'lucide-react';
import { cn } from '../../utils';

/**
 * The "?" button next to the header page title. Replaces the description
 * paragraph that used to render permanently in the page body (`PageHeader`'s
 * old visible block) — the copy is unchanged, it's just shown on demand now.
 *
 * No existing Popover primitive fit this: `components/ui/enhanced.tsx`'s
 * `Tooltip` is hover-only, plain-text, and effectively unused elsewhere.
 */
export const HeaderHelpPopover: React.FC<{ description: React.ReactNode }> = ({ description }) => {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const onClick = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener('mousedown', onClick);
    return () => document.removeEventListener('mousedown', onClick);
  }, [open]);

  return (
    <div className="relative shrink-0" ref={ref}>
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
        aria-label="About this page"
        aria-expanded={open}
        className={cn(
          'p-1 rounded-full transition-colors',
          'text-neutral-400 hover:text-primary-700 hover:bg-neutral-100',
          'dark:text-neutral-500 dark:hover:text-neutral-50 dark:hover:bg-primary-800/60'
        )}
      >
        <HelpCircle className="w-4 h-4" />
      </button>

      {open && (
        <div
          className={cn(
            'absolute left-0 top-full mt-2 w-80 max-w-[calc(100vw-2rem)] p-4',
            'bg-white rounded-2xl shadow-2xl border border-neutral-200/60 z-20',
            'dark:bg-primary-900 dark:border-primary-800/60',
            'animate-scale-in origin-top-left body-sm'
          )}
        >
          {description}
        </div>
      )}
    </div>
  );
};
