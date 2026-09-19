// ============================================================================
// FORM COMPONENTS
// Shared form field components used across all tabs
// ============================================================================

import React from 'react';
import { XCircle } from 'lucide-react';
import { cn } from '../../utils';

// ============================================================================
// FORM FIELD WRAPPER
// ============================================================================

export interface FormFieldProps {
  label: string;
  required?: boolean;
  error?: string;
  hint?: string;
  children: React.ReactNode;
  className?: string;
}

export const FormField: React.FC<FormFieldProps> = ({ 
  label, 
  required, 
  error, 
  hint, 
  children,
  className,
}) => (
  <div className={cn('space-y-1.5', className)}>
    <label className="field-label block">
      {label}
      {required && <span className="text-error-500 dark:text-error-300 ml-1">*</span>}
    </label>
    {children}
    {hint && !error && (
      <p className="caption">{hint}</p>
    )}
    {error && (
      <p className="caption-error flex items-center gap-1">
        <XCircle className="w-3 h-3" />
        {error}
      </p>
    )}
  </div>
);

// ============================================================================
// SELECT FIELD
// ============================================================================

export interface SelectOption {
  value: string;
  label: string;
  disabled?: boolean;
  description?: string;
}

export interface SelectFieldProps {
  value: string;
  onChange: (value: string) => void;
  options: SelectOption[];
  placeholder?: string;
  disabled?: boolean;
  error?: boolean;
  className?: string;
}

export const SelectField: React.FC<SelectFieldProps> = ({
  value,
  onChange,
  options,
  placeholder = 'Select...',
  disabled,
  error,
  className,
}) => (
  <select
    value={value}
    onChange={(e) => onChange(e.target.value)}
    disabled={disabled}
    className={cn(
      'w-full px-3 py-2 border rounded-lg text-body-sm transition-colors appearance-none',
      'bg-white bg-no-repeat bg-right',
      'focus:outline-none focus:ring-2 focus:ring-primary-500 focus:border-primary-500',
      error ? 'border-error-500' : 'border-neutral-300',
      disabled && 'bg-surface-muted cursor-not-allowed opacity-60',
      className
    )}
    style={{
      backgroundImage: `url("data:image/svg+xml,%3csvg xmlns='http://www.w3.org/2000/svg' fill='none' viewBox='0 0 20 20'%3e%3cpath stroke='%236b7280' stroke-linecap='round' stroke-linejoin='round' stroke-width='1.5' d='M6 8l4 4 4-4'/%3e%3c/svg%3e")`,
      backgroundPosition: 'right 0.5rem center',
      backgroundSize: '1.5em 1.5em',
      paddingRight: '2.5rem',
    }}
  >
    <option value="">{placeholder}</option>
    {options.map((opt) => (
      <option key={opt.value} value={opt.value} disabled={opt.disabled}>
        {opt.label}
      </option>
    ))}
  </select>
);

// ============================================================================
// NUMBER INPUT
// ============================================================================

export interface NumberInputProps {
  value: number | undefined;
  onChange: (value: number | undefined) => void;
  min?: number;
  max?: number;
  step?: number;
  placeholder?: string;
  disabled?: boolean;
  error?: boolean;
  prefix?: string;
  suffix?: string;
  className?: string;
}

export const NumberInput: React.FC<NumberInputProps> = ({
  value,
  onChange,
  min,
  max,
  step = 1,
  placeholder,
  disabled,
  error,
  prefix,
  suffix,
  className,
}) => (
  <div className="relative">
    {prefix && (
      <span className="absolute left-3 top-1/2 -translate-y-1/2 body-sm pointer-events-none">
        {prefix}
      </span>
    )}
    <input
      type="number"
      value={value ?? ''}
      onChange={(e) => {
        const val = e.target.value;
        onChange(val === '' ? undefined : Number(val));
      }}
      min={min}
      max={max}
      step={step}
      placeholder={placeholder}
      disabled={disabled}
      className={cn(
        'w-full px-3 py-2 border rounded-lg text-body-sm transition-colors',
        'focus:outline-none focus:ring-2 focus:ring-primary-500 focus:border-primary-500',
        '[appearance:textfield] [&::-webkit-outer-spin-button]:appearance-none [&::-webkit-inner-spin-button]:appearance-none',
        error ? 'border-error-500' : 'border-neutral-300',
        disabled && 'bg-surface-muted cursor-not-allowed',
        prefix && 'pl-12',
        suffix && 'pr-12',
        className
      )}
    />
    {suffix && (
      <span className="absolute right-3 top-1/2 -translate-y-1/2 body-sm pointer-events-none">
        {suffix}
      </span>
    )}
  </div>
);

// ============================================================================
// TEXT INPUT
// ============================================================================

export interface TextInputProps extends React.InputHTMLAttributes<HTMLInputElement> {
  error?: boolean;
}

export const TextInput: React.FC<TextInputProps> = ({
  error,
  className,
  disabled,
  ...props
}) => (
  <input
    type="text"
    disabled={disabled}
    className={cn(
      'w-full px-3 py-2 border rounded-lg text-body-sm transition-colors',
      'focus:outline-none focus:ring-2 focus:ring-primary-500 focus:border-primary-500',
      error ? 'border-error-500' : 'border-neutral-300',
      disabled && 'bg-surface-muted cursor-not-allowed',
      className
    )}
    {...props}
  />
);

// ============================================================================
// TEXTAREA
// ============================================================================

export interface TextAreaProps extends React.TextareaHTMLAttributes<HTMLTextAreaElement> {
  error?: boolean;
}

export const TextArea: React.FC<TextAreaProps> = ({
  error,
  className,
  disabled,
  ...props
}) => (
  <textarea
    disabled={disabled}
    className={cn(
      'w-full px-3 py-2 border rounded-lg text-body-sm transition-colors resize-y min-h-[80px]',
      'focus:outline-none focus:ring-2 focus:ring-primary-500 focus:border-primary-500',
      error ? 'border-error-500' : 'border-neutral-300',
      disabled && 'bg-surface-muted cursor-not-allowed',
      className
    )}
    {...props}
  />
);

// ============================================================================
// DATE INPUT
// ============================================================================

export interface DateInputProps {
  value: string;
  onChange: (value: string) => void;
  min?: string;
  max?: string;
  disabled?: boolean;
  error?: boolean;
  className?: string;
}

export const DateInput: React.FC<DateInputProps> = ({
  value,
  onChange,
  min,
  max,
  disabled,
  error,
  className,
}) => (
  <input
    type="date"
    value={value}
    onChange={(e) => onChange(e.target.value)}
    min={min}
    max={max}
    disabled={disabled}
    className={cn(
      'w-full px-3 py-2 border rounded-lg text-body-sm transition-colors',
      'focus:outline-none focus:ring-2 focus:ring-primary-500 focus:border-primary-500',
      error ? 'border-error-500' : 'border-neutral-300',
      disabled && 'bg-surface-muted cursor-not-allowed',
      className
    )}
  />
);

// ============================================================================
// FORM SECTION
// ============================================================================

export interface FormSectionProps {
  title: string;
  description?: string;
  icon?: React.ReactNode;
  badge?: React.ReactNode;
  children: React.ReactNode;
  className?: string;
}

export const FormSection: React.FC<FormSectionProps> = ({
  title,
  description,
  icon,
  badge,
  children,
  className,
}) => (
  <div className={cn('border-t border-neutral-200 pt-6 first:border-t-0 first:pt-0', className)}>
    <div className="flex items-center justify-between mb-4">
      <div className="flex items-center gap-2">
        {icon}
        <div>
          <h4 className="body-strong">{title}</h4>
          {description && (
            <p className="caption">{description}</p>
          )}
        </div>
      </div>
      {badge}
    </div>
    {children}
  </div>
);

// ============================================================================
// FORM ROW (Grid Helper)
// ============================================================================

export interface FormRowProps {
  cols?: 1 | 2 | 3 | 4;
  children: React.ReactNode;
  className?: string;
}

export const FormRow: React.FC<FormRowProps> = ({
  cols = 2,
  children,
  className,
}) => {
  const colsClass = {
    1: 'grid-cols-1',
    2: 'grid-cols-1 md:grid-cols-2',
    3: 'grid-cols-1 md:grid-cols-2 lg:grid-cols-3',
    4: 'grid-cols-1 md:grid-cols-2 lg:grid-cols-4',
  };

  return (
    <div className={cn('grid gap-4', colsClass[cols], className)}>
      {children}
    </div>
  );
};

export default {
  FormField,
  SelectField,
  NumberInput,
  TextInput,
  TextArea,
  DateInput,
  FormSection,
  FormRow,
};