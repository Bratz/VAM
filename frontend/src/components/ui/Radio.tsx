/* eslint-disable no-restricted-syntax -- native input primitive: the one place raw checkbox/radio inputs are allowed */
import React from 'react';
import { cn } from '../../utils';

export interface RadioProps
  extends Omit<React.InputHTMLAttributes<HTMLInputElement>, 'size' | 'type' | 'onChange'> {
  onChange?: (checked: boolean) => void;
  label?: React.ReactNode;
  description?: React.ReactNode;
  error?: string;
  size?: 'sm' | 'md';
}

export const Radio = React.forwardRef<HTMLInputElement, RadioProps>(
  ({ onChange, label, description, error, disabled, size = 'md', className, id, ...props }, ref) => {
    const autoId = React.useId();
    const inputId = id ?? autoId;
    return (
      <label
        htmlFor={inputId}
        className={cn('inline-flex items-start gap-3', disabled ? 'cursor-not-allowed opacity-50' : 'cursor-pointer', className)}
      >
        <span className="relative inline-flex shrink-0">
          <input
            ref={ref}
            id={inputId}
            type="radio"
            disabled={disabled}
            aria-invalid={!!error || undefined}
            onChange={(e) => onChange?.(e.target.checked)}
            className="peer sr-only"
            {...props}
          />
          <span
            aria-hidden="true"
            className={cn(
              'flex items-center justify-center rounded-full border border-edge-strong bg-surface-card transition-colors',
              'peer-checked:border-primary-900 dark:peer-checked:border-accent-500',
              'peer-focus-visible:ring-2 peer-focus-visible:ring-offset-2 peer-focus-visible:ring-primary-500 dark:peer-focus-visible:ring-accent-400',
              '[&>span]:scale-0 peer-checked:[&>span]:scale-100',
              error && 'border-error-500 dark:border-error-500',
              size === 'sm' ? 'h-4 w-4' : 'h-5 w-5'
            )}
          >
            <span
              className={cn(
                'rounded-full bg-primary-900 transition-transform dark:bg-accent-500',
                size === 'sm' ? 'h-2 w-2' : 'h-2.5 w-2.5'
              )}
            />
          </span>
        </span>
        {(label || description) && (
          <span className="flex flex-col">
            {label && <span className="body-strong">{label}</span>}
            {description && <span className="body-sm text-neutral-500 dark:text-neutral-400">{description}</span>}
          </span>
        )}
      </label>
    );
  }
);
Radio.displayName = 'Radio';

export interface RadioOption {
  value: string;
  label: React.ReactNode;
  description?: React.ReactNode;
  disabled?: boolean;
}

export interface RadioGroupProps {
  legend?: React.ReactNode;
  description?: React.ReactNode;
  error?: string;
  name?: string;
  value?: string;
  onChange?: (value: string) => void;
  options: RadioOption[];
  disabled?: boolean;
  size?: 'sm' | 'md';
  orientation?: 'vertical' | 'horizontal';
  className?: string;
}

export const RadioGroup = React.forwardRef<HTMLFieldSetElement, RadioGroupProps>(
  ({ legend, description, error, name, value, onChange, options, disabled, size = 'md', orientation = 'vertical', className }, ref) => {
    const autoName = React.useId();
    const groupName = name ?? autoName;
    const descId = `${groupName}-desc`;
    return (
      <fieldset
        ref={ref}
        disabled={disabled}
        aria-describedby={description || error ? descId : undefined}
        className={cn('min-w-0 border-0 p-0', className)}
      >
        {legend && <legend className="field-label mb-2">{legend}</legend>}
        <div className={cn('flex gap-3', orientation === 'vertical' ? 'flex-col' : 'flex-row flex-wrap gap-6')}>
          {options.map((o) => (
            <Radio
              key={o.value}
              name={groupName}
              value={o.value}
              checked={value === o.value}
              onChange={() => onChange?.(o.value)}
              label={o.label}
              description={o.description}
              disabled={disabled || o.disabled}
              size={size}
            />
          ))}
        </div>
        {(description || error) && (
          <p
            id={descId}
            role={error ? 'alert' : undefined}
            className={cn('mt-2 text-caption', error ? 'text-error-600 dark:text-error-300' : 'text-neutral-500 dark:text-neutral-400')}
          >
            {error ?? description}
          </p>
        )}
      </fieldset>
    );
  }
);
RadioGroup.displayName = 'RadioGroup';
