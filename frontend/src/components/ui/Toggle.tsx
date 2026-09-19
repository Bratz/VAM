import React from 'react';
import { cn } from '../../utils';

export interface ToggleProps
  extends Omit<React.InputHTMLAttributes<HTMLInputElement>, 'size' | 'type' | 'onChange'> {
  checked?: boolean;
  onChange?: (checked: boolean) => void;
  label?: React.ReactNode;
  description?: React.ReactNode;
  error?: string;
  size?: 'sm' | 'md';
  /** `inline` (default): switch then label. `split`: full-width row, label left and switch right. */
  layout?: 'inline' | 'split';
}

export const Toggle = React.forwardRef<HTMLInputElement, ToggleProps>(
  ({ checked, onChange, label, description, error, disabled, size = 'md', layout = 'inline', className, id, ...props }, ref) => {
    const autoId = React.useId();
    const inputId = id ?? autoId;
    const errId = error ? `${inputId}-err` : undefined;
    const track =
      size === 'sm'
        ? 'h-5 w-9 after:h-4 after:w-4 peer-checked:after:translate-x-4'
        : 'h-6 w-11 after:h-5 after:w-5 peer-checked:after:translate-x-5';

    return (
      <div className={className}>
        <label
          htmlFor={inputId}
          className={cn(
            layout === 'split' ? 'flex w-full flex-row-reverse items-center justify-between gap-4' : 'inline-flex items-start gap-3',
            disabled ? 'cursor-not-allowed opacity-50' : 'cursor-pointer'
          )}
        >
          <span className="relative inline-flex shrink-0">
            <input
              ref={ref}
              id={inputId}
              type="checkbox"
              role="switch"
              checked={checked}
              disabled={disabled}
              aria-invalid={!!error || undefined}
              aria-describedby={errId}
              onChange={(e) => onChange?.(e.target.checked)}
              className="peer sr-only"
              {...props}
            />
            <span
              aria-hidden="true"
              className={cn(
                'relative rounded-full bg-neutral-300 transition-colors dark:bg-primary-700',
                "after:absolute after:left-0.5 after:top-0.5 after:rounded-full after:bg-white after:shadow-sm after:transition-transform after:content-['']",
                'peer-checked:bg-primary-900 dark:peer-checked:bg-accent-500',
                'peer-focus-visible:ring-2 peer-focus-visible:ring-offset-2 peer-focus-visible:ring-primary-500 dark:peer-focus-visible:ring-accent-400',
                error && 'ring-2 ring-error-500',
                track
              )}
            />
          </span>
          {(label || description) && (
            <span className="flex flex-col">
              {label && <span className="body-strong">{label}</span>}
              {description && <span className="body-sm text-neutral-500 dark:text-neutral-400">{description}</span>}
            </span>
          )}
        </label>
        {error && (
          <p id={errId} role="alert" className="mt-1 text-caption text-error-600 dark:text-error-300">
            {error}
          </p>
        )}
      </div>
    );
  }
);
Toggle.displayName = 'Toggle';
