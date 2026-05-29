import React, { useState, useEffect, useRef, Fragment } from 'react';
import { X, Check, ChevronRight, AlertCircle, Info } from 'lucide-react';
import { cn } from '../../utils';

// ==================== Modal Component ====================

interface ModalProps {
  isOpen: boolean;
  onClose: () => void;
  title?: string;
  subtitle?: string;
  size?: 'sm' | 'md' | 'lg' | 'xl' | 'full';
  children: React.ReactNode;
  footer?: React.ReactNode;
  closeOnOverlay?: boolean;
  showCloseButton?: boolean;
}

export const Modal: React.FC<ModalProps> = ({
  isOpen,
  onClose,
  title,
  subtitle,
  size = 'md',
  children,
  footer,
  closeOnOverlay = true,
  showCloseButton = true,
}) => {
  const modalRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const handleEscape = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    
    if (isOpen) {
      document.addEventListener('keydown', handleEscape);
      document.body.style.overflow = 'hidden';
    }
    
    return () => {
      document.removeEventListener('keydown', handleEscape);
      document.body.style.overflow = 'unset';
    };
  }, [isOpen, onClose]);

  if (!isOpen) return null;

  const sizes = {
    sm: 'max-w-md',
    md: 'max-w-lg',
    lg: 'max-w-2xl',
    xl: 'max-w-4xl',
    full: 'max-w-[90vw] h-[90vh]',
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center">
      {/* Backdrop */}
      <div 
        className="absolute inset-0 bg-primary-950/60 backdrop-blur-sm animate-fade-in"
        onClick={closeOnOverlay ? onClose : undefined}
      />
      
      {/* Modal */}
      <div
        ref={modalRef}
        className={cn(
          'relative bg-white rounded-2xl shadow-strong w-full mx-4',
          'animate-scale-in',
          sizes[size],
          size === 'full' && 'flex flex-col'
        )}
      >
        {/* Header */}
        {(title || showCloseButton) && (
          <div className="flex items-start justify-between p-6 border-b border-neutral-200">
            <div>
              {title && <h2 className="text-heading-lg text-primary-900">{title}</h2>}
              {subtitle && <p className="text-body-sm text-neutral-500 mt-1">{subtitle}</p>}
            </div>
            {showCloseButton && (
              <button
                onClick={onClose}
                className="p-2 -mr-2 -mt-2 hover:bg-neutral-100 rounded-lg transition-colors"
              >
                <X className="w-5 h-5 text-neutral-500" />
              </button>
            )}
          </div>
        )}
        
        {/* Content */}
        <div className={cn(
          'p-6',
          size === 'full' && 'flex-1 overflow-y-auto'
        )}>
          {children}
        </div>
        
        {/* Footer */}
        {footer && (
          <div className="flex items-center justify-end gap-3 px-6 py-4 border-t border-neutral-200 bg-neutral-50 rounded-b-2xl">
            {footer}
          </div>
        )}
      </div>
    </div>
  );
};

// ==================== Tabs Component ====================

interface Tab {
  id: string;
  label: string;
  icon?: React.ReactNode;
  badge?: number;
  disabled?: boolean;
}

interface TabsProps {
  tabs: Tab[];
  activeTab: string;
  onChange: (tabId: string) => void;
  variant?: 'default' | 'pills' | 'underline';
  size?: 'sm' | 'md';
  fullWidth?: boolean;
}

export const Tabs: React.FC<TabsProps> = ({
  tabs,
  activeTab,
  onChange,
  variant = 'default',
  size = 'md',
  fullWidth = false,
}) => {
  const variants = {
    default: {
      container: 'bg-neutral-100 p-1 rounded-xl',
      tab: 'rounded-lg',
      active: 'bg-white shadow-soft text-primary-900',
      inactive: 'text-neutral-600 hover:text-primary-900',
    },
    pills: {
      container: 'gap-2',
      tab: 'rounded-full border',
      active: 'bg-primary-900 text-white border-primary-900',
      inactive: 'text-neutral-600 border-neutral-300 hover:border-primary-300',
    },
    underline: {
      container: 'border-b border-neutral-200',
      tab: 'border-b-2 -mb-px',
      active: 'border-primary-900 text-primary-900',
      inactive: 'border-transparent text-neutral-500 hover:text-primary-900 hover:border-neutral-300',
    },
  };

  const sizes = {
    sm: 'px-3 py-1.5 text-body-sm',
    md: 'px-4 py-2 text-body-md',
  };

  const style = variants[variant];

  return (
    <div className={cn('flex', style.container, fullWidth && 'w-full')}>
      {tabs.map((tab) => (
        <button
          key={tab.id}
          onClick={() => !tab.disabled && onChange(tab.id)}
          disabled={tab.disabled}
          className={cn(
            'flex items-center gap-2 font-medium transition-all duration-200',
            sizes[size],
            style.tab,
            fullWidth && 'flex-1 justify-center',
            tab.disabled && 'opacity-50 cursor-not-allowed',
            activeTab === tab.id ? style.active : style.inactive
          )}
        >
          {tab.icon}
          {tab.label}
          {tab.badge !== undefined && (
            <span className={cn(
              'text-caption px-1.5 py-0.5 rounded-full',
              activeTab === tab.id ? 'bg-white/20' : 'bg-neutral-200'
            )}>
              {tab.badge}
            </span>
          )}
        </button>
      ))}
    </div>
  );
};

// ==================== Stepper Component ====================

interface Step {
  id: string;
  title: string;
  description?: string;
  icon?: React.ReactNode;
}

interface StepperProps {
  steps: Step[];
  currentStep: number;
  onStepClick?: (stepIndex: number) => void;
  variant?: 'horizontal' | 'vertical';
  size?: 'sm' | 'md';
}

export const Stepper: React.FC<StepperProps> = ({
  steps,
  currentStep,
  onStepClick,
  variant = 'horizontal',
  size = 'md',
}) => {
  const isHorizontal = variant === 'horizontal';

  return (
    <div className={cn(
      'flex',
      isHorizontal ? 'items-center' : 'flex-col'
    )}>
      {steps.map((step, index) => {
        const isCompleted = index < currentStep;
        const isActive = index === currentStep;
        const isClickable = onStepClick && index <= currentStep;

        return (
          <Fragment key={step.id}>
            <div
              className={cn(
                'flex',
                isHorizontal ? 'flex-col items-center' : 'items-start gap-4',
                isClickable && 'cursor-pointer'
              )}
              onClick={() => isClickable && onStepClick(index)}
            >
              {/* Step Circle */}
              <div className={cn(
                'flex items-center justify-center rounded-full border-2 transition-all',
                size === 'sm' ? 'w-8 h-8' : 'w-10 h-10',
                isCompleted && 'bg-success-500 border-success-500 text-white',
                isActive && 'bg-primary-900 border-primary-900 text-white',
                !isCompleted && !isActive && 'border-neutral-300 text-neutral-400'
              )}>
                {isCompleted ? (
                  <Check className={size === 'sm' ? 'w-4 h-4' : 'w-5 h-5'} />
                ) : step.icon ? (
                  step.icon
                ) : (
                  <span className={size === 'sm' ? 'text-body-sm' : 'text-body-md'}>
                    {index + 1}
                  </span>
                )}
              </div>

              {/* Step Content */}
              <div className={cn(
                isHorizontal ? 'mt-2 text-center' : '',
                size === 'sm' && 'max-w-20'
              )}>
                <p className={cn(
                  'font-medium',
                  size === 'sm' ? 'text-body-sm' : 'text-body-md',
                  isActive ? 'text-primary-900' : 'text-neutral-600'
                )}>
                  {step.title}
                </p>
                {step.description && (
                  <p className="text-caption text-neutral-500 mt-0.5">
                    {step.description}
                  </p>
                )}
              </div>
            </div>

            {/* Connector */}
            {index < steps.length - 1 && (
              <div className={cn(
                'transition-colors',
                isHorizontal 
                  ? 'flex-1 h-0.5 mx-4' 
                  : 'w-0.5 h-8 ml-5 my-2',
                index < currentStep ? 'bg-success-500' : 'bg-neutral-200'
              )} />
            )}
          </Fragment>
        );
      })}
    </div>
  );
};

// ==================== Progress Bar ====================

interface ProgressBarProps {
  value: number;
  max?: number;
  size?: 'sm' | 'md' | 'lg';
  variant?: 'default' | 'success' | 'warning' | 'error';
  showLabel?: boolean;
  label?: string;
  animated?: boolean;
}

export const ProgressBar: React.FC<ProgressBarProps> = ({
  value,
  max = 100,
  size = 'md',
  variant = 'default',
  showLabel = false,
  label,
  animated = false,
}) => {
  const percentage = Math.min(100, Math.max(0, (value / max) * 100));

  const sizes = {
    sm: 'h-1.5',
    md: 'h-2.5',
    lg: 'h-4',
  };

  const variants = {
    default: 'bg-primary-600',
    success: 'bg-success-500',
    warning: 'bg-warning-500',
    error: 'bg-error-500',
  };

  return (
    <div className="w-full">
      {(showLabel || label) && (
        <div className="flex justify-between items-center mb-2">
          <span className="text-body-sm text-neutral-600">{label}</span>
          {showLabel && (
            <span className="text-body-sm font-medium text-primary-900">
              {Math.round(percentage)}%
            </span>
          )}
        </div>
      )}
      <div className={cn('w-full bg-neutral-200 rounded-full overflow-hidden', sizes[size])}>
        <div
          className={cn(
            'h-full rounded-full transition-all duration-500 ease-out',
            variants[variant],
            animated && 'animate-pulse-soft'
          )}
          style={{ width: `${percentage}%` }}
        />
      </div>
    </div>
  );
};

// ==================== Alert Component ====================

interface AlertProps {
  variant?: 'info' | 'success' | 'warning' | 'error';
  title?: string;
  children: React.ReactNode;
  onClose?: () => void;
  className?: string;
}

export const Alert: React.FC<AlertProps> = ({
  variant = 'info',
  title,
  children,
  onClose,
  className,
}) => {
  const variants = {
    info: {
      container: 'bg-info-50 border-info-200',
      icon: <Info className="w-5 h-5 text-info-600" />,
      title: 'text-info-800',
      content: 'text-info-700',
    },
    success: {
      container: 'bg-success-50 border-success-200',
      icon: <Check className="w-5 h-5 text-success-600" />,
      title: 'text-success-800',
      content: 'text-success-700',
    },
    warning: {
      container: 'bg-warning-50 border-warning-200',
      icon: <AlertCircle className="w-5 h-5 text-warning-600" />,
      title: 'text-warning-800',
      content: 'text-warning-700',
    },
    error: {
      container: 'bg-error-50 border-error-200',
      icon: <AlertCircle className="w-5 h-5 text-error-600" />,
      title: 'text-error-800',
      content: 'text-error-700',
    },
  };

  const style = variants[variant];

  return (
    <div className={cn(
      'flex gap-3 p-4 rounded-xl border',
      style.container,
      className
    )}>
      <div className="flex-shrink-0">{style.icon}</div>
      <div className="flex-1">
        {title && <p className={cn('font-medium mb-1', style.title)}>{title}</p>}
        <div className={cn('text-body-sm', style.content)}>{children}</div>
      </div>
      {onClose && (
        <button
          onClick={onClose}
          className="flex-shrink-0 p-1 hover:bg-black/5 rounded"
        >
          <X className="w-4 h-4" />
        </button>
      )}
    </div>
  );
};

// ==================== Avatar Component ====================

interface AvatarProps {
  src?: string;
  name?: string;
  size?: 'xs' | 'sm' | 'md' | 'lg' | 'xl';
  variant?: 'circle' | 'rounded';
  status?: 'online' | 'offline' | 'busy' | 'away';
  className?: string;
}

export const Avatar: React.FC<AvatarProps> = ({
  src,
  name,
  size = 'md',
  variant = 'circle',
  status,
  className,
}) => {
  const getInitials = (name: string) => {
    return name
      .split(' ')
      .map(n => n[0])
      .join('')
      .toUpperCase()
      .slice(0, 2);
  };

  const sizes = {
    xs: 'w-6 h-6 text-caption',
    sm: 'w-8 h-8 text-body-sm',
    md: 'w-10 h-10 text-body-md',
    lg: 'w-12 h-12 text-body-lg',
    xl: 'w-16 h-16 text-heading-md',
  };

  const statusColors = {
    online: 'bg-success-500',
    offline: 'bg-neutral-400',
    busy: 'bg-error-500',
    away: 'bg-warning-500',
  };

  const statusSizes = {
    xs: 'w-1.5 h-1.5',
    sm: 'w-2 h-2',
    md: 'w-2.5 h-2.5',
    lg: 'w-3 h-3',
    xl: 'w-4 h-4',
  };

  return (
    <div className={cn('relative inline-flex', className)}>
      <div className={cn(
        'flex items-center justify-center bg-primary-100 text-primary-700 font-medium',
        variant === 'circle' ? 'rounded-full' : 'rounded-lg',
        sizes[size]
      )}>
        {src ? (
          <img
            src={src}
            alt={name}
            className={cn(
              'w-full h-full object-cover',
              variant === 'circle' ? 'rounded-full' : 'rounded-lg'
            )}
          />
        ) : name ? (
          getInitials(name)
        ) : (
          '?'
        )}
      </div>
      {status && (
        <span className={cn(
          'absolute bottom-0 right-0 border-2 border-white rounded-full',
          statusColors[status],
          statusSizes[size]
        )} />
      )}
    </div>
  );
};

// ==================== Tooltip Component ====================

interface TooltipProps {
  content: string;
  children: React.ReactNode;
  position?: 'top' | 'bottom' | 'left' | 'right';
  delay?: number;
}

export const Tooltip: React.FC<TooltipProps> = ({
  content,
  children,
  position = 'top',
  delay = 200,
}) => {
  const [show, setShow] = useState(false);
  const timeoutRef = useRef<NodeJS.Timeout>();

  const handleMouseEnter = () => {
    timeoutRef.current = setTimeout(() => setShow(true), delay);
  };

  const handleMouseLeave = () => {
    if (timeoutRef.current) clearTimeout(timeoutRef.current);
    setShow(false);
  };

  const positions = {
    top: 'bottom-full left-1/2 -translate-x-1/2 mb-2',
    bottom: 'top-full left-1/2 -translate-x-1/2 mt-2',
    left: 'right-full top-1/2 -translate-y-1/2 mr-2',
    right: 'left-full top-1/2 -translate-y-1/2 ml-2',
  };

  return (
    <div 
      className="relative inline-flex"
      onMouseEnter={handleMouseEnter}
      onMouseLeave={handleMouseLeave}
    >
      {children}
      {show && (
        <div className={cn(
          'absolute z-50 px-2 py-1 text-caption text-white bg-primary-900 rounded-md whitespace-nowrap',
          'animate-fade-in',
          positions[position]
        )}>
          {content}
        </div>
      )}
    </div>
  );
};
