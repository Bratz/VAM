import React from 'react';
import { ChevronRight, Building2, Wallet, Globe, MapPin, Layers } from 'lucide-react';
import { cn } from '../../utils';

// ============================================================================
// TYPES
// ============================================================================

export interface BreadcrumbSegment {
  id: string;
  code: string;
  name: string;
  level: number;
  type: 'MASTER' | 'CONSOLIDATION' | 'VIRTUAL_ACCOUNT';
}

export interface HierarchyBreadcrumbProps {
  path: string | BreadcrumbSegment[];
  maxLevels?: number;
  showIcons?: boolean;
  onClick?: (segment: BreadcrumbSegment) => void;
  className?: string;
  size?: 'sm' | 'md';
}

// ============================================================================
// HIERARCHY BREADCRUMB COMPONENT
// ============================================================================

export const HierarchyBreadcrumb: React.FC<HierarchyBreadcrumbProps> = ({
  path,
  maxLevels = 3,
  showIcons = true,
  onClick,
  className,
  size = 'sm',
}) => {
  // Parse path string into segments if needed
  const segments: BreadcrumbSegment[] = typeof path === 'string'
    ? parsePath(path)
    : path;

  if (!segments || segments.length === 0) {
    return <span className="text-neutral-400 text-sm dark:text-neutral-500">—</span>;
  }

  // Truncate to maxLevels
  const displaySegments = segments.length > maxLevels
    ? [
        ...segments.slice(0, 1),
        { id: 'ellipsis', code: '...', name: '...', level: -1, type: 'CONSOLIDATION' as const },
        ...segments.slice(-maxLevels + 1),
      ]
    : segments;

  const getIcon = (type: string, level: number) => {
    if (type === 'MASTER') return Globe;
    if (type === 'VIRTUAL_ACCOUNT') return Wallet;
    if (level <= 2) return MapPin;
    if (level <= 4) return Building2;
    return Layers;
  };

  const textSize = size === 'sm' ? 'text-xs' : 'text-sm';
  const iconSize = size === 'sm' ? 'w-3 h-3' : 'w-4 h-4';

  return (
    <div className={cn('flex items-center gap-1 flex-wrap', className)}>
      {displaySegments.map((segment, index) => {
        if (segment.id === 'ellipsis') {
          return (
            <React.Fragment key="ellipsis">
              <span className={cn('text-neutral-400 dark:text-neutral-500', textSize)}>...</span>
              <ChevronRight className={cn(iconSize, 'text-neutral-300 flex-shrink-0 dark:text-neutral-600')} />
            </React.Fragment>
          );
        }

        const Icon = getIcon(segment.type, segment.level);
        const isLast = index === displaySegments.length - 1;
        const isClickable = onClick && segment.id !== 'ellipsis';

        return (
          <React.Fragment key={segment.id}>
            <button
              type="button"
              onClick={() => isClickable && onClick(segment)}
              disabled={!isClickable}
              className={cn(
                'flex items-center gap-1 rounded px-1 py-0.5 transition-colors',
                isClickable && 'hover:bg-neutral-100 cursor-pointer dark:hover:bg-primary-800',
                !isClickable && 'cursor-default'
              )}
            >
              {showIcons && (
                <Icon className={cn(
                  iconSize,
                  'flex-shrink-0',
                  segment.type === 'MASTER' ? 'text-primary-600 dark:text-primary-200' :
                  segment.type === 'VIRTUAL_ACCOUNT' ? 'text-success-600 dark:text-success-300' :
                  'text-neutral-500 dark:text-neutral-400'
                )} />
              )}
              <span className={cn(
                textSize,
                'font-medium truncate max-w-[100px]',
                isLast ? 'text-primary-900 dark:text-neutral-50' : 'text-neutral-600 dark:text-neutral-300'
              )}>
                {segment.name || segment.code}
              </span>
            </button>
            
            {!isLast && (
              <ChevronRight className={cn(iconSize, 'text-neutral-300 flex-shrink-0 dark:text-neutral-600')} />
            )}
          </React.Fragment>
        );
      })}
    </div>
  );
};

// ============================================================================
// HELPER FUNCTIONS
// ============================================================================

function parsePath(pathString: string): BreadcrumbSegment[] {
  if (!pathString) return [];
  
  // Path format: /PROGRAM/L1/L2/L3/L4/L5/L6/L7
  const parts = pathString.split('/').filter(Boolean);
  
  return parts.map((part, index) => ({
    id: `${index}-${part}`,
    code: part,
    name: part.replace(/_/g, ' '),
    level: index,
    type: index === 0 ? 'MASTER' as const :
          index === parts.length - 1 ? 'VIRTUAL_ACCOUNT' as const :
          'CONSOLIDATION' as const,
  }));
}

// ============================================================================
// COMPACT HIERARCHY PATH (Single line display)
// ============================================================================

export interface CompactHierarchyPathProps {
  path: string;
  className?: string;
}

export const CompactHierarchyPath: React.FC<CompactHierarchyPathProps> = ({
  path,
  className,
}) => {
  if (!path) return <span className="text-neutral-400 dark:text-neutral-500">—</span>;
  
  // Show only last 2 segments for compact view
  const parts = path.split('/').filter(Boolean);
  const display = parts.length > 2
    ? `.../${parts.slice(-2).join('/')}`
    : parts.join('/');

  return (
    <span className={cn('text-xs text-neutral-500 font-mono dark:text-neutral-400', className)}>
      {display}
    </span>
  );
};

export default HierarchyBreadcrumb;