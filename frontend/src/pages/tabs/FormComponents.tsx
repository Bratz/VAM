// ============================================================================
// FORM COMPONENTS
// Shared form field components used across all tabs
// ============================================================================

import React from 'react';
import { AlertCircle } from 'lucide-react';
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
      {required && <span className="text-error-500 ml-1">*</span>}
    </label>
    {children}
    {hint && !error && (
      <p className="text-xs text-neutral-500">{hint}</p>
    )}
    {error && (
      <p className="text-xs text-error-600 flex items-center gap-1">
        <AlertCircle className="w-3 h-3" />
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
      'w-full px-3 py-2 border rounded-lg text-sm transition-colors appearance-none',
      'bg-white bg-no-repeat bg-right',
      'focus:outline-none focus:ring-2 focus:ring-primary-500 focus:border-primary-500',
      error ? 'border-error-500' : 'border-neutral-300',
      disabled && 'bg-neutral-100 cursor-not-allowed opacity-60',
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
      <span className="absolute left-3 top-1/2 -translate-y-1/2 text-sm text-neutral-500 pointer-events-none">
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
        'w-full px-3 py-2 border rounded-lg text-sm transition-colors',
        'focus:outline-none focus:ring-2 focus:ring-primary-500 focus:border-primary-500',
        '[appearance:textfield] [&::-webkit-outer-spin-button]:appearance-none [&::-webkit-inner-spin-button]:appearance-none',
        error ? 'border-error-500' : 'border-neutral-300',
        disabled && 'bg-neutral-100 cursor-not-allowed',
        prefix && 'pl-12',
        suffix && 'pr-12',
        className
      )}
    />
    {suffix && (
      <span className="absolute right-3 top-1/2 -translate-y-1/2 text-sm text-neutral-500 pointer-events-none">
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
      'w-full px-3 py-2 border rounded-lg text-sm transition-colors',
      'focus:outline-none focus:ring-2 focus:ring-primary-500 focus:border-primary-500',
      error ? 'border-error-500' : 'border-neutral-300',
      disabled && 'bg-neutral-100 cursor-not-allowed',
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
      'w-full px-3 py-2 border rounded-lg text-sm transition-colors resize-y min-h-[80px]',
      'focus:outline-none focus:ring-2 focus:ring-primary-500 focus:border-primary-500',
      error ? 'border-error-500' : 'border-neutral-300',
      disabled && 'bg-neutral-100 cursor-not-allowed',
      className
    )}
    {...props}
  />
);

// ============================================================================
// CHECKBOX
// ============================================================================

export interface CheckboxProps {
  checked: boolean;
  onChange: (checked: boolean) => void;
  label: string;
  description?: string;
  disabled?: boolean;
}

export const Checkbox: React.FC<CheckboxProps> = ({
  checked,
  onChange,
  label,
  description,
  disabled,
}) => (
  <label className={cn(
    'flex items-start gap-3 cursor-pointer',
    disabled && 'cursor-not-allowed opacity-60'
  )}>
    <input
      type="checkbox"
      checked={checked}
      onChange={(e) => onChange(e.target.checked)}
      disabled={disabled}
      className="mt-1 w-4 h-4 text-primary-600 border-neutral-300 rounded focus:ring-primary-500"
    />
    <div>
      <span className="text-sm font-medium text-neutral-900">{label}</span>
      {description && (
        <p className="text-xs text-neutral-500 mt-0.5">{description}</p>
      )}
    </div>
  </label>
);

// ============================================================================
// TOGGLE SWITCH
// ============================================================================

export interface ToggleProps {
  checked: boolean;
  onChange: (checked: boolean) => void;
  label?: string;
  description?: string;
  disabled?: boolean;
}

export const Toggle: React.FC<ToggleProps> = ({
  checked,
  onChange,
  label,
  description,
  disabled,
}) => (
  <label className={cn(
    'flex items-center justify-between gap-4',
    disabled && 'cursor-not-allowed opacity-60'
  )}>
    {(label || description) && (
      <div>
        {label && <span className="text-sm font-medium text-neutral-900">{label}</span>}
        {description && (
          <p className="text-xs text-neutral-500 mt-0.5">{description}</p>
        )}
      </div>
    )}
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      disabled={disabled}
      onClick={() => !disabled && onChange(!checked)}
      className={cn(
        'relative inline-flex h-6 w-11 items-center rounded-full transition-colors',
        'focus:outline-none focus:ring-2 focus:ring-primary-500 focus:ring-offset-2',
        checked ? 'bg-primary-600' : 'bg-neutral-200',
        disabled && 'cursor-not-allowed'
      )}
    >
      <span
        className={cn(
          'inline-block h-5 w-5 transform rounded-full bg-white transition-transform shadow-sm',
          checked ? 'translate-x-[22px]' : 'translate-x-[2px]'
        )}
      />
    </button>
  </label>
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
      'w-full px-3 py-2 border rounded-lg text-sm transition-colors',
      'focus:outline-none focus:ring-2 focus:ring-primary-500 focus:border-primary-500',
      error ? 'border-error-500' : 'border-neutral-300',
      disabled && 'bg-neutral-100 cursor-not-allowed',
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
          <h4 className="text-sm font-medium text-neutral-900">{title}</h4>
          {description && (
            <p className="text-xs text-neutral-500">{description}</p>
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
  Checkbox,
  Toggle,
  DateInput,
  FormSection,
  FormRow,
};