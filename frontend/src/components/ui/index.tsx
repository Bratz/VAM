import React from 'react';
import { cn } from '../../utils';
import { Loader2, Check, AlertCircle, ChevronDown } from 'lucide-react';

// Tier 3 Design System Unification: extracted medallion component.
// Re-exported from this barrel so callers can `import { StatusIconBadge }
// from '../components/ui'` consistently with the rest of the UI library.
export { StatusIconBadge } from './StatusIconBadge';

// Slide-in side drawer. Context-preserving counterpart to <Modal> (same
// shadow-strong / scrim chrome). Used for detail panels and tools that
// should keep the underlying table/list in view.
export { Drawer } from './Drawer';
export type { DrawerProps } from './Drawer';

// Phase 12 Task E: canonical small stat / metric tile (promoted from
// components/multiBank/MetricCard). Pages must use this (or the
// .stat-value-* utilities) instead of hand-rolled `text-xl font-bold` cards.
export { StatTile } from './StatTile';
export type { StatTileProps } from './StatTile';

// ============================================================================
// WORLD-CLASS UI COMPONENTS - Swiss Minimalism + Premium Polish
// ============================================================================

// ==================== Button Component ====================

interface ButtonProps extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: 'primary' | 'secondary' | 'outline' | 'ghost' | 'danger' | 'success' | 'accent';
  size?: 'xs' | 'sm' | 'md' | 'lg' | 'xl';
  loading?: boolean;
  leftIcon?: React.ReactNode;
  rightIcon?: React.ReactNode;
  fullWidth?: boolean;
}

export const Button = React.forwardRef<HTMLButtonElement, ButtonProps>(
  (
    {
      className,
      variant = 'primary',
      size = 'md',
      loading = false,
      leftIcon,
      rightIcon,
      disabled,
      fullWidth,
      children,
      ...props
    },
    ref
  ) => {
    const baseStyles = cn(
      // Base layout & typography
      'inline-flex items-center justify-center font-medium',
      'rounded-xl whitespace-nowrap select-none',
      // Premium transitions
      'transition-all duration-200 ease-out',
      'transform-gpu will-change-transform',
      // Focus ring
      'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-offset-2',
      // Disabled state
      'disabled:opacity-50 disabled:cursor-not-allowed disabled:transform-none',
      // Active press effect
      'active:scale-[0.98]',
      // Full width option
      fullWidth && 'w-full'
    );

    const variants: Record<string, string> = {
      primary: cn(
        'bg-primary-900 text-white',
        'hover:bg-primary-800 hover:shadow-lg',
        'active:bg-primary-950',
        'focus-visible:ring-primary-500'
      ),
      secondary: cn(
        'bg-primary-100 text-primary-900 dark:bg-primary-700 dark:text-neutral-50',
        'hover:bg-primary-200 hover:shadow-md',
        'active:bg-primary-300',
        'focus-visible:ring-primary-500'
      ),
      outline: cn(
        'border-2 border-primary-200 bg-white text-primary-900 dark:bg-primary-900 dark:text-neutral-50',
        'hover:border-primary-300 hover:bg-primary-50 hover:shadow-md',
        'active:bg-primary-100',
        'focus-visible:ring-primary-500'
      ),
      ghost: cn(
        'bg-transparent text-primary-900 dark:text-neutral-50',
        'hover:bg-primary-50',
        'active:bg-primary-100',
        'focus-visible:ring-primary-500'
      ),
      danger: cn(
        'bg-error-600 text-white',
        'hover:bg-error-700 hover:shadow-lg',
        'active:bg-error-800',
        'focus-visible:ring-error-500'
      ),
      success: cn(
        'bg-success-600 text-white',
        'hover:bg-success-700 hover:shadow-lg',
        'active:bg-success-800',
        'focus-visible:ring-success-500'
      ),
      accent: cn(
        'bg-accent-500 text-white',
        'hover:bg-accent-600 hover:shadow-lg',
        'active:bg-accent-700',
        'focus-visible:ring-accent-500'
      ),
    };

    const sizes: Record<string, string> = {
      xs: 'h-7 px-2.5 text-xs gap-1',
      sm: 'h-9 px-3.5 text-sm gap-1.5',
      md: 'h-11 px-5 text-sm gap-2',
      lg: 'h-12 px-6 text-base gap-2.5',
      xl: 'h-14 px-8 text-base gap-3',
    };

    // Icon-only button sizes
    const isIconOnly = !children && (leftIcon || rightIcon);
    const hasOnlyIconChildren = React.Children.count(children) === 1 &&
      React.isValidElement(children) &&
      typeof children.type !== 'string';

    const iconOnlySizes: Record<string, string> = {
      xs: 'h-7 w-7 p-0',
      sm: 'h-9 w-9 p-0',
      md: 'h-11 w-11 p-0',
      lg: 'h-12 w-12 p-0',
      xl: 'h-14 w-14 p-0',
    };

    const shouldUseIconPadding = isIconOnly || hasOnlyIconChildren;

    // Icon sizes based on button size
    const iconSizes: Record<string, string> = {
      xs: 'w-3.5 h-3.5',
      sm: 'w-4 h-4',
      md: 'w-4 h-4',
      lg: 'w-5 h-5',
      xl: 'w-5 h-5',
    };

    return (
      <button
        ref={ref}
        disabled={disabled || loading}
        className={cn(
          baseStyles,
          variants[variant],
          shouldUseIconPadding ? iconOnlySizes[size] : sizes[size],
          className
        )}
        {...props}
      >
        {loading ? (
          <Loader2 className={cn('animate-spin', iconSizes[size])} />
        ) : (
          <>
            {leftIcon && (
              <span className={cn('inline-flex shrink-0', iconSizes[size])}>
                {leftIcon}
              </span>
            )}
            {children}
            {rightIcon && (
              <span className={cn('inline-flex shrink-0', iconSizes[size])}>
                {rightIcon}
              </span>
            )}
          </>
        )}
      </button>
    );
  }
);

Button.displayName = 'Button';

// ==================== Badge Component ====================

interface BadgeProps {
  variant?: 'success' | 'warning' | 'error' | 'info' | 'neutral' | 'primary' | 'accent';
  size?: 'xs' | 'sm' | 'md';
  children: React.ReactNode;
  className?: string;
  dot?: boolean;
  icon?: React.ReactNode;
}

export const Badge: React.FC<BadgeProps> = ({
  variant = 'neutral',
  size = 'md',
  children,
  className,
  dot,
  icon,
}) => {
  const variants: Record<string, string> = {
    success: 'bg-success-50 text-success-700 border-success-200/60 dark:bg-success-500/15 dark:text-success-300 dark:border-success-500/30',
    warning: 'bg-warning-50 text-warning-700 border-warning-200/60 dark:bg-warning-500/15 dark:text-warning-300 dark:border-warning-500/30',
    error: 'bg-error-50 text-error-700 border-error-200/60 dark:bg-error-500/15 dark:text-error-300 dark:border-error-500/30',
    info: 'bg-info-50 text-info-700 border-info-200/60 dark:bg-info-500/15 dark:text-info-300 dark:border-info-500/30',
    neutral: 'bg-neutral-100 text-neutral-700 border-neutral-200/60 dark:bg-primary-800/60 dark:text-neutral-200 dark:border-primary-700/60',
    primary: 'bg-primary-50 text-primary-700 border-primary-200/60 dark:bg-primary-800/60 dark:text-primary-200 dark:border-primary-700/60 dark:text-neutral-200',
    accent: 'bg-accent-50 text-accent-700 border-accent-200/60 dark:bg-accent-500/15 dark:text-accent-300 dark:border-accent-500/30',
  };

  const dotColors: Record<string, string> = {
    success: 'bg-success-500',
    warning: 'bg-warning-500',
    error: 'bg-error-500',
    info: 'bg-info-500',
    neutral: 'bg-neutral-400',
    primary: 'bg-primary-500',
    accent: 'bg-accent-500',
  };

  const sizes: Record<string, string> = {
    xs: 'px-1.5 py-0.5 text-[10px] gap-1',
    sm: 'px-2 py-0.5 text-xs gap-1',
    md: 'px-2.5 py-1 text-sm gap-1.5',
  };

  return (
    <span
      className={cn(
        'inline-flex items-center font-medium rounded-full border',
        // Badges are atomic labels: never wrap their text or shrink below
        // content — in tight flex rows they truncate the neighbor (which
        // handles it) rather than folding into multi-line pills.
        'whitespace-nowrap shrink-0',
        'transition-all duration-150',
        variants[variant],
        sizes[size],
        className
      )}
    >
      {dot && (
        <span className={cn(
          'rounded-full shrink-0',
          dotColors[variant],
          size === 'xs' ? 'w-1.5 h-1.5' : size === 'sm' ? 'w-1.5 h-1.5' : 'w-2 h-2'
        )} />
      )}
      {icon && <span className="shrink-0">{icon}</span>}
      {children}
    </span>
  );
};

// ==================== Card Component ====================

interface CardProps {
  children: React.ReactNode;
  className?: string;
  padding?: 'none' | 'xs' | 'sm' | 'md' | 'lg' | 'xl';
  hover?: boolean;
  interactive?: boolean;
  gradient?: boolean;
  bordered?: boolean;
  onClick?: () => void;
  style?: React.CSSProperties;
}

export const Card: React.FC<CardProps> = ({
  children,
  className,
  padding = 'md',
  hover = false,
  interactive = false,
  gradient = false,
  bordered = true,
  onClick,
  style,
}) => {
  const paddingStyles: Record<string, string> = {
    none: '',
    xs: 'p-3',
    sm: 'p-4',
    md: 'p-6',
    lg: 'p-8',
    xl: 'p-10',
  };

  return (
    <div
      onClick={onClick}
      style={style}
      className={cn(
        // Base styles
        'rounded-2xl',
        bordered && 'border border-neutral-200/80 dark:border-primary-800/60',
        // Background — dark variant flips to elevated navy panel
        gradient
          ? 'bg-gradient-to-br from-white via-white to-neutral-50/50 dark:from-primary-900 dark:via-primary-900 dark:to-primary-950/60'
          : 'bg-white dark:bg-primary-900',
        // Shadow
        'shadow-sm dark:shadow-none',
        // Transitions
        'transition-all duration-200 ease-out',
        // Hover effects
        hover && 'hover:shadow-md hover:border-neutral-300/80 dark:hover:border-primary-700/80',
        // Interactive (clickable) styles
        interactive && cn(
          'cursor-pointer',
          'hover:shadow-lg hover:-translate-y-0.5',
          'active:shadow-sm active:translate-y-0'
        ),
        onClick && 'cursor-pointer',
        // Padding
        paddingStyles[padding],
        className
      )}
    >
      {children}
    </div>
  );
};

interface CardHeaderProps {
  title: string;
  subtitle?: string;
  action?: React.ReactNode;
  icon?: React.ReactNode;
  className?: string;
}

export const CardHeader: React.FC<CardHeaderProps> = ({
  title,
  subtitle,
  action,
  icon,
  className,
}) => (
  <div className={cn('flex items-start justify-between gap-4 mb-5', className)}>
    <div className="flex items-start gap-3">
      {icon && (
        <div className="w-10 h-10 rounded-xl bg-primary-50 flex items-center justify-center shrink-0 dark:bg-primary-800/40">
          {icon}
        </div>
      )}
      <div>
        <h3 className="text-lg font-semibold text-primary-900 tracking-tight dark:text-neutral-50">{title}</h3>
        {subtitle && (
          <p className="text-sm text-neutral-500 mt-0.5 dark:text-neutral-400">{subtitle}</p>
        )}
      </div>
    </div>
    {action && <div className="shrink-0">{action}</div>}
  </div>
);

// ==================== Input Component ====================

interface InputProps extends React.InputHTMLAttributes<HTMLInputElement> {
  label?: string;
  error?: string;
  hint?: string;
  success?: boolean;
  leftIcon?: React.ReactNode;
  rightIcon?: React.ReactNode;
  inputSize?: 'sm' | 'md' | 'lg';
}

export const Input = React.forwardRef<HTMLInputElement, InputProps>(
  ({
    className,
    label,
    error,
    hint,
    success,
    leftIcon,
    rightIcon,
    inputSize = 'md',
    disabled,
    ...props
  }, ref) => {
    const sizeStyles: Record<string, string> = {
      sm: 'h-9 px-3 text-sm',
      md: 'h-11 px-4 text-base',
      lg: 'h-13 px-5 text-base',
    };

    const iconSizes: Record<string, string> = {
      sm: 'w-4 h-4',
      md: 'w-5 h-5',
      lg: 'w-5 h-5',
    };

    return (
      <div className="w-full">
        {label && (
          <label className="block text-sm font-medium text-primary-900 mb-2 dark:text-neutral-50">
            {label}
          </label>
        )}
        <div className="relative">
          {leftIcon && (
            <div className={cn(
              'absolute left-3.5 top-1/2 -translate-y-1/2 text-neutral-400 pointer-events-none',
              iconSizes[inputSize]
            )}>
              {leftIcon}
            </div>
          )}
          <input
            ref={ref}
            disabled={disabled}
            className={cn(
              // Base styles
              'w-full rounded-xl border bg-white dark:bg-primary-900',
              'text-primary-900 placeholder:text-neutral-400 dark:text-neutral-50',
              // Transitions
              'transition-all duration-200',
              // Default border
              'border-neutral-300 dark:border-primary-700',
              // Hover state
              'hover:border-neutral-400',
              // Focus state
              'focus:outline-none focus:border-primary-500 focus:ring-2 focus:ring-primary-500/20',
              // Error state
              error && 'border-error-500 focus:border-error-500 focus:ring-error-500/20',
              // Success state
              success && 'border-success-500 focus:border-success-500 focus:ring-success-500/20',
              // Disabled state
              disabled && 'bg-neutral-100 text-neutral-500 cursor-not-allowed hover:border-neutral-300 dark:bg-primary-800 dark:text-neutral-400',
              // Size
              sizeStyles[inputSize],
              // Icon padding
              leftIcon && (inputSize === 'sm' ? 'pl-9' : inputSize === 'lg' ? 'pl-12' : 'pl-11'),
              rightIcon && (inputSize === 'sm' ? 'pr-9' : inputSize === 'lg' ? 'pr-12' : 'pr-11'),
              className
            )}
            {...props}
          />
          {rightIcon && (
            <div className={cn(
              'absolute right-3.5 top-1/2 -translate-y-1/2 text-neutral-400 pointer-events-none',
              iconSizes[inputSize]
            )}>
              {rightIcon}
            </div>
          )}
          {/* Success checkmark */}
          {success && !rightIcon && (
            <div className="absolute right-3.5 top-1/2 -translate-y-1/2 text-success-500">
              <Check className={iconSizes[inputSize]} />
            </div>
          )}
          {/* Error icon */}
          {error && !rightIcon && !success && (
            <div className="absolute right-3.5 top-1/2 -translate-y-1/2 text-error-500">
              <AlertCircle className={iconSizes[inputSize]} />
            </div>
          )}
        </div>
        {error && (
          <p className="mt-2 text-sm text-error-600 flex items-center gap-1.5 dark:text-error-300">
            <AlertCircle className="w-3.5 h-3.5 shrink-0" />
            {error}
          </p>
        )}
        {hint && !error && (
          <p className="mt-2 text-sm text-neutral-500 dark:text-neutral-400">{hint}</p>
        )}
      </div>
    );
  }
);

Input.displayName = 'Input';

// ==================== TextArea Component ====================

interface TextAreaProps
  extends React.TextareaHTMLAttributes<HTMLTextAreaElement> {
  label?: string;
  error?: string;
  hint?: string;
  success?: boolean;
  textareaSize?: 'sm' | 'md' | 'lg';
}

export const TextArea = React.forwardRef<HTMLTextAreaElement, TextAreaProps>(
  ({
    className,
    label,
    error,
    hint,
    success,
    textareaSize = 'md',
    disabled,
    rows = 3,
    ...props
  }, ref) => {
    const sizeStyles: Record<string, string> = {
      sm: 'px-3 py-2 text-sm',
      md: 'px-4 py-2.5 text-base',
      lg: 'px-5 py-3 text-base',
    };

    return (
      <div className="w-full">
        {label && (
          <label className="block text-sm font-medium text-primary-900 mb-2 dark:text-neutral-50">
            {label}
          </label>
        )}
        <textarea
          ref={ref}
          rows={rows}
          disabled={disabled}
          className={cn(
            // Base (mirrors Input)
            'w-full rounded-xl border bg-white dark:bg-primary-900',
            'text-primary-900 placeholder:text-neutral-400 dark:text-neutral-50',
            'transition-all duration-200',
            'border-neutral-300 dark:border-primary-700',
            'hover:border-neutral-400',
            'focus:outline-none focus:border-primary-500 focus:ring-2 focus:ring-primary-500/20',
            error && 'border-error-500 focus:border-error-500 focus:ring-error-500/20',
            success && 'border-success-500 focus:border-success-500 focus:ring-success-500/20',
            disabled && 'bg-neutral-100 text-neutral-500 cursor-not-allowed hover:border-neutral-300 dark:bg-primary-800 dark:text-neutral-400',
            sizeStyles[textareaSize],
            className,
          )}
          {...props}
        />
        {error && (
          <p className="mt-2 text-sm text-error-600 flex items-center gap-1.5 dark:text-error-300">
            <AlertCircle className="w-3.5 h-3.5 shrink-0" />
            {error}
          </p>
        )}
        {hint && !error && (
          <p className="mt-2 text-sm text-neutral-500 dark:text-neutral-400">{hint}</p>
        )}
      </div>
    );
  },
);

TextArea.displayName = 'TextArea';

// ==================== Select Component ====================

interface SelectOption {
  value: string | number;
  label: string;
  disabled?: boolean;
}

interface SelectProps extends Omit<React.SelectHTMLAttributes<HTMLSelectElement>, 'size'> {
  label?: string;
  error?: string;
  hint?: string;
  options?: SelectOption[];
  placeholder?: string;
  children?: React.ReactNode;
  selectSize?: 'sm' | 'md' | 'lg';
}

export const Select = React.forwardRef<HTMLSelectElement, SelectProps>(
  ({
    className,
    label,
    error,
    hint,
    options,
    placeholder,
    children,
    selectSize = 'md',
    disabled,
    ...props
  }, ref) => {
    const sizeStyles: Record<string, string> = {
      sm: 'h-9 px-3 pr-9 text-sm',
      md: 'h-11 px-4 pr-10 text-base',
      lg: 'h-13 px-5 pr-12 text-base',
    };

    return (
      <div className="w-full">
        {label && (
          <label className="block text-sm font-medium text-primary-900 dark:text-neutral-50 mb-2">
            {label}
          </label>
        )}
        <div className="relative">
          <select
            ref={ref}
            disabled={disabled}
            className={cn(
              // Base styles
              'w-full rounded-xl border bg-white appearance-none cursor-pointer dark:bg-primary-900',
              'text-primary-900 dark:text-neutral-50',
              // Dark mode surface — flips to navy panel with light text and softer border.
              // Note: native <option> elements still render with the OS theme; that's a browser limitation.
              'dark:bg-primary-900 dark:text-neutral-50 dark:border-primary-700',
              // Transitions
              'transition-all duration-200',
              // Default border
              'border-neutral-300 dark:border-primary-700',
              // Hover state
              'hover:border-neutral-400 dark:hover:border-primary-600',
              // Focus state
              'focus:outline-none focus:border-primary-500 focus:ring-2 focus:ring-primary-500/20',
              'dark:focus:border-accent-400 dark:focus:ring-accent-400/20',
              // Error state
              error && 'border-error-500 focus:border-error-500 focus:ring-error-500/20',
              // Disabled state
              disabled && 'bg-neutral-100 text-neutral-500 cursor-not-allowed hover:border-neutral-300 dark:bg-primary-800 dark:text-neutral-500 dark:text-neutral-400',
              // Size
              sizeStyles[selectSize],
              className
            )}
            {...props}
          >
            {placeholder && (
              <option value="" disabled>
                {placeholder}
              </option>
            )}
            {options && options.length > 0
              ? options.map((option) => (
                  <option
                    key={option.value}
                    value={option.value}
                    disabled={option.disabled}
                  >
                    {option.label}
                  </option>
                ))
              : children}
          </select>
          {/* Custom dropdown arrow */}
          <div className={cn(
            'absolute right-3 top-1/2 -translate-y-1/2 pointer-events-none',
            disabled ? 'text-neutral-400 dark:text-neutral-600' : 'text-neutral-500 dark:text-neutral-400'
          )}>
            <ChevronDown className={selectSize === 'sm' ? 'w-4 h-4' : 'w-5 h-5'} />
          </div>
        </div>
        {error && (
          <p className="mt-2 text-sm text-error-600 dark:text-error-300 flex items-center gap-1.5">
            <AlertCircle className="w-3.5 h-3.5 shrink-0" />
            {error}
          </p>
        )}
        {hint && !error && (
          <p className="mt-2 text-sm text-neutral-500 dark:text-neutral-400">{hint}</p>
        )}
      </div>
    );
  }
);

Select.displayName = 'Select';

// ==================== Skeleton Component ====================

interface SkeletonProps {
  className?: string;
  variant?: 'text' | 'heading' | 'circular' | 'rectangular' | 'card';
  width?: string | number;
  height?: string | number;
  lines?: number;
}

export const Skeleton: React.FC<SkeletonProps> = ({
  className,
  variant = 'text',
  width,
  height,
  lines = 1,
}) => {
  const variants: Record<string, string> = {
    text: 'h-4 rounded-lg',
    heading: 'h-7 rounded-lg',
    circular: 'rounded-full',
    rectangular: 'rounded-xl',
    card: 'rounded-2xl',
  };

  if (lines > 1 && variant === 'text') {
    return (
      <div className="space-y-2.5">
        {Array.from({ length: lines }).map((_, i) => (
          <div
            key={i}
            className={cn(
              'skeleton',
              variants[variant],
              i === lines - 1 && 'w-3/4',
              className
            )}
            style={{ width: i === lines - 1 ? '75%' : width, height }}
          />
        ))}
      </div>
    );
  }

  return (
    <div
      className={cn(
        'skeleton',
        variants[variant],
        className
      )}
      style={{ width, height }}
    />
  );
};

// ==================== Empty State Component ====================

interface EmptyStateProps {
  icon?: React.ReactNode;
  title: string;
  description?: string;
  action?: React.ReactNode;
  compact?: boolean;
}

export const EmptyState: React.FC<EmptyStateProps> = ({
  icon,
  title,
  description,
  action,
  compact = false,
}) => (
  <div className={cn(
    'flex flex-col items-center justify-center text-center',
    compact ? 'py-8 px-4' : 'py-16 px-6'
  )}>
    {icon && (
      <div className={cn(
        'rounded-2xl bg-neutral-100 flex items-center justify-center text-neutral-400 mb-5 dark:bg-primary-800',
        compact ? 'w-14 h-14' : 'w-20 h-20'
      )}>
        {icon}
      </div>
    )}
    <h3 className={cn(
      'font-semibold text-primary-900 dark:text-neutral-50',
      compact ? 'text-base mb-1' : 'text-lg mb-2'
    )}>
      {title}
    </h3>
    {description && (
      <p className={cn(
        'text-neutral-500 max-w-sm dark:text-neutral-400',
        compact ? 'text-sm mb-4' : 'text-base mb-6'
      )}>
        {description}
      </p>
    )}
    {action}
  </div>
);

// ==================== Divider Component ====================

interface DividerProps {
  className?: string;
  label?: string;
  orientation?: 'horizontal' | 'vertical';
}

export const Divider: React.FC<DividerProps> = ({
  className,
  label,
  orientation = 'horizontal',
}) => {
  if (orientation === 'vertical') {
    return <div className={cn('w-px h-full bg-neutral-200', className)} />;
  }

  if (label) {
    return (
      <div className={cn('flex items-center gap-4', className)}>
        <div className="flex-1 h-px bg-neutral-200" />
        <span className="label">
          {label}
        </span>
        <div className="flex-1 h-px bg-neutral-200" />
      </div>
    );
  }

  return <div className={cn('h-px w-full bg-neutral-200', className)} />;
};

// ==================== Loading Spinner ====================

interface SpinnerProps {
  size?: 'xs' | 'sm' | 'md' | 'lg' | 'xl';
  className?: string;
  color?: 'primary' | 'white' | 'neutral';
}

export const Spinner: React.FC<SpinnerProps> = ({
  size = 'md',
  className,
  color = 'primary',
}) => {
  const sizes: Record<string, string> = {
    xs: 'w-3.5 h-3.5',
    sm: 'w-4 h-4',
    md: 'w-6 h-6',
    lg: 'w-8 h-8',
    xl: 'w-12 h-12',
  };

  const colors: Record<string, string> = {
    primary: 'text-primary-600 dark:text-primary-200',
    white: 'text-white',
    neutral: 'text-neutral-500 dark:text-neutral-400',
  };

  return (
    <Loader2 className={cn(
      'animate-spin',
      sizes[size],
      colors[color],
      className
    )} />
  );
};

// ==================== Avatar Component ====================

interface AvatarProps {
  src?: string;
  alt?: string;
  name?: string;
  size?: 'xs' | 'sm' | 'md' | 'lg' | 'xl';
  className?: string;
}

export const Avatar: React.FC<AvatarProps> = ({
  src,
  alt,
  name,
  size = 'md',
  className,
}) => {
  const sizes: Record<string, string> = {
    xs: 'w-6 h-6 text-[10px]',
    sm: 'w-8 h-8 text-xs',
    md: 'w-10 h-10 text-sm',
    lg: 'w-12 h-12 text-base',
    xl: 'w-16 h-16 text-lg',
  };

  const getInitials = (name?: string) => {
    if (!name) return '?';
    return name
      .split(' ')
      .map(n => n[0])
      .join('')
      .toUpperCase()
      .slice(0, 2);
  };

  if (src) {
    return (
      <img
        src={src}
        alt={alt || name}
        className={cn(
          'rounded-full object-cover',
          sizes[size],
          className
        )}
      />
    );
  }

  return (
    <div
      className={cn(
        'rounded-full bg-primary-600 text-white font-semibold',
        'flex items-center justify-center',
        sizes[size],
        className
      )}
    >
      {getInitials(name)}
    </div>
  );
};

// ==================== Progress Bar ====================

interface ProgressProps {
  value: number;
  max?: number;
  size?: 'sm' | 'md' | 'lg';
  variant?: 'primary' | 'success' | 'warning' | 'error';
  showLabel?: boolean;
  className?: string;
}

export const Progress: React.FC<ProgressProps> = ({
  value,
  max = 100,
  size = 'md',
  variant = 'primary',
  showLabel = false,
  className,
}) => {
  const percentage = Math.min(Math.max((value / max) * 100, 0), 100);

  const sizes: Record<string, string> = {
    sm: 'h-1.5',
    md: 'h-2.5',
    lg: 'h-4',
  };

  const variants: Record<string, string> = {
    primary: 'bg-primary-600',
    success: 'bg-success-500',
    warning: 'bg-warning-500',
    error: 'bg-error-500',
  };

  return (
    <div className={cn('w-full', className)}>
      {showLabel && (
        <div className="flex justify-between mb-1.5">
          <span className="text-sm font-medium text-primary-900 dark:text-neutral-50">Progress</span>
          <span className="text-sm text-neutral-500 dark:text-neutral-400">{Math.round(percentage)}%</span>
        </div>
      )}
      <div className={cn(
        'w-full bg-neutral-200 rounded-full overflow-hidden',
        sizes[size]
      )}>
        <div
          className={cn(
            'h-full rounded-full transition-all duration-500 ease-out',
            variants[variant]
          )}
          style={{ width: `${percentage}%` }}
        />
      </div>
    </div>
  );
};
