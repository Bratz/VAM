import React from 'react';
import { Check, Minus } from 'lucide-react';
import { cn } from '../../utils';

export interface CheckboxProps
  extends Omit<React.InputHTMLAttributes<HTMLInputElement>, 'size' | 'type' | 'onChange'> {
  onChange?: (checked: boolean) => void;
  label?: React.ReactNode;
  description?: React.ReactNode;
  error?: string;
  indeterminate?: boolean;
  size?: 'sm' | 'md';
  /** `plain` (default) or `card`: a bordered, tinted-when-checked row for option lists. */
  variant?: 'plain' | 'card';
}

export const Checkbox = React.forwardRef<HTMLInputElement, CheckboxProps>(
  ({ checked, onChange, label, description, error, indeterminate = false, disabled, size = 'md', variant = 'plain', className, id, ...props }, ref) => {
    const autoId = React.useId();
    const inputId = id ?? autoId;
    const errId = error ? `${inputId}-err` : undefined;
    const innerRef = React.useRef<HTMLInputElement | null>(null);

    React.useEffect(() => {
      if (innerRef.current) innerRef.current.indeterminate = indeterminate;
    }, [indeterminate]);

    const setRefs = (el: HTMLInputElement | null) => {
      innerRef.current = el;
      if (typeof ref === 'function') ref(el);
      else if (ref) (ref as React.MutableRefObject<HTMLInputElement | null>).current = el;
    };
    const icon = size === 'sm' ? 'w-3 h-3' : 'w-4 h-4';

    return (
      <div className={className}>
        <label
          htmlFor={inputId}
          className={cn(
            variant === 'card'
              ? 'flex w-full items-start gap-3 rounded-lg border border-neutral-200 bg-white p-3 transition-colors dark:border-primary-800 dark:bg-primary-900 has-[:checked]:border-primary-300 has-[:checked]:bg-primary-50 dark:has-[:checked]:border-primary-600 dark:has-[:checked]:bg-primary-800/40'
              : 'inline-flex items-start gap-3',
            disabled ? 'cursor-not-allowed opacity-50' : 'cursor-pointer'
          )}
        >
          <span className="relative inline-flex shrink-0">
            <input
              ref={setRefs}
              id={inputId}
              type="checkbox"
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
                'flex items-center justify-center rounded-sm border border-neutral-300 bg-white text-white transition-colors dark:border-primary-700 dark:bg-primary-900',
                'peer-checked:border-primary-900 peer-checked:bg-primary-900 dark:peer-checked:border-accent-500 dark:peer-checked:bg-accent-500',
                'peer-focus-visible:ring-2 peer-focus-visible:ring-offset-2 peer-focus-visible:ring-primary-500 dark:peer-focus-visible:ring-accent-400',
                '[&>svg]:opacity-0 peer-checked:[&>svg]:opacity-100',
                indeterminate && 'border-primary-900 bg-primary-900 dark:border-accent-500 dark:bg-accent-500 [&>svg]:opacity-100',
                error && 'border-error-500 dark:border-error-500',
                size === 'sm' ? 'h-4 w-4' : 'h-5 w-5'
              )}
            >
              {indeterminate ? <Minus className={icon} /> : <Check className={icon} />}
            </span>
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
Checkbox.displayName = 'Checkbox';
