import React, { useEffect } from 'react';
import { X } from 'lucide-react';
import { cn } from '../../utils';

/**
 * Slide-in side drawer.
 *
 * Distinct from <Modal> in that it preserves the page's table/list context
 * — used when the user expects to keep scanning the list while looking at
 * one item's detail, or when they expect to flip between items without
 * a full overlay reset.
 *
 * Width: 'md' (28rem / 448px) | 'lg' (36rem / 576px) | 'xl' (44rem / 704px).
 * Backdrop click + Escape close. Body scroll is locked while open.
 * Right-aligned (slides in from the right edge).
 *
 * Visual chrome is deliberately aligned with <Modal>: same `shadow-strong`
 * elevation token and `bg-primary-950/60 backdrop-blur-sm` scrim, so the two
 * overlay primitives feel like one system. (`shadow-modal` is NOT a
 * configured token — `shadow-strong` is the design-system overlay elevation.)
 */
export interface DrawerProps {
  isOpen: boolean;
  onClose: () => void;
  title?: React.ReactNode;
  subtitle?: React.ReactNode;
  size?: 'md' | 'lg' | 'xl';
  footer?: React.ReactNode;
  children: React.ReactNode;
}

const sizes: Record<string, string> = {
  md: 'max-w-md',
  lg: 'max-w-xl',
  xl: 'max-w-3xl',
};

export const Drawer: React.FC<DrawerProps> = ({
  isOpen, onClose, title, subtitle, size = 'lg', footer, children,
}) => {
  useEffect(() => {
    if (!isOpen) return;
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    document.addEventListener('keydown', onKey);
    const prev = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      document.removeEventListener('keydown', onKey);
      document.body.style.overflow = prev;
    };
  }, [isOpen, onClose]);

  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 z-50 flex justify-end">
      <div
        className="absolute inset-0 bg-primary-950/60 backdrop-blur-sm animate-fade-in"
        onClick={onClose}
      />
      <div
        className={cn(
          'relative bg-white dark:bg-primary-900 w-full h-full flex flex-col',
          'shadow-strong animate-slide-in-right',
          sizes[size],
        )}
      >
        {/* Header always renders so the close affordance is always present. */}
        <div className="flex items-start justify-between p-6 border-b border-neutral-200 dark:border-primary-800 shrink-0">
          <div className="min-w-0">
            {title && (
              <h2 className="section-title">{title}</h2>
            )}
            {subtitle && (
              <div className="body-sm mt-1">{subtitle}</div>
            )}
          </div>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close"
            className="p-2 -m-2 text-neutral-500 hover:text-primary-900 dark:text-neutral-400 dark:hover:text-neutral-50 rounded focus:outline-none focus-visible:ring-2 focus-visible:ring-accent-400"
          >
            <X className="w-5 h-5" />
          </button>
        </div>
        <div className="flex-1 overflow-y-auto">
          {children}
        </div>
        {footer && (
          <div className="border-t border-neutral-200 dark:border-primary-800 p-4 shrink-0">
            {footer}
          </div>
        )}
      </div>
    </div>
  );
};
