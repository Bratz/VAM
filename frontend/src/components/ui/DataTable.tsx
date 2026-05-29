import React, { useState, useMemo } from 'react';
import { cn } from '../../utils';
import {
  ChevronDown,
  ChevronUp,
  ChevronLeft,
  ChevronRight,
  ChevronsLeft,
  ChevronsRight,
  Search,
  Filter,
  Download,
  MoreHorizontal,
  ArrowUpDown,
  Check,
} from 'lucide-react';
import { Button, Badge, Skeleton, EmptyState } from './index';

// ============================================================================
// WORLD-CLASS DATA TABLE - Responsive Desktop/Mobile Design
// ============================================================================

export interface Column<T> {
  key: keyof T | string;
  header: string;
  sortable?: boolean;
  width?: string;
  align?: 'left' | 'center' | 'right';
  // For mobile card view - which columns to show prominently
  mobileLabel?: boolean;
  mobileValue?: boolean;
  mobileHidden?: boolean;
  // Custom render function
  render?: (value: unknown, row: T, index: number) => React.ReactNode;
}

export interface DataTableProps<T> {
  data: T[];
  columns: Column<T>[];
  keyExtractor: (row: T) => string | number;
  // Pagination
  pagination?: boolean;
  pageSize?: number;
  currentPage?: number;
  totalCount?: number;
  onPageChange?: (page: number) => void;
  // Sorting
  sortKey?: string;
  sortDirection?: 'asc' | 'desc';
  onSort?: (key: string, direction: 'asc' | 'desc') => void;
  // Selection
  selectable?: boolean;
  selectedKeys?: Set<string | number>;
  onSelectionChange?: (keys: Set<string | number>) => void;
  // Search
  searchable?: boolean;
  searchPlaceholder?: string;
  onSearch?: (query: string) => void;
  // Loading
  loading?: boolean;
  // Empty state
  emptyTitle?: string;
  emptyDescription?: string;
  emptyIcon?: React.ReactNode;
  emptyAction?: React.ReactNode;
  // Row click
  onRowClick?: (row: T) => void;
  // Styling
  className?: string;
  stickyHeader?: boolean;
  compact?: boolean;
  striped?: boolean;
  // Mobile
  mobileCardRenderer?: (row: T, index: number) => React.ReactNode;
  // Actions
  actions?: React.ReactNode;
  bulkActions?: React.ReactNode;
}

export function DataTable<T>({
  data,
  columns,
  keyExtractor,
  pagination = false,
  pageSize = 10,
  currentPage = 1,
  totalCount,
  onPageChange,
  sortKey,
  sortDirection = 'asc',
  onSort,
  selectable = false,
  selectedKeys = new Set(),
  onSelectionChange,
  searchable = false,
  searchPlaceholder = 'Search...',
  onSearch,
  loading = false,
  emptyTitle = 'No data found',
  emptyDescription = 'There are no items to display.',
  emptyIcon,
  emptyAction,
  onRowClick,
  className,
  stickyHeader = false,
  compact = false,
  striped = false,
  mobileCardRenderer,
  actions,
  bulkActions,
}: DataTableProps<T>) {
  const [localSearch, setLocalSearch] = useState('');
  const [showFilters, setShowFilters] = useState(false);

  const handleSort = (key: string) => {
    if (!onSort) return;
    const newDirection = sortKey === key && sortDirection === 'asc' ? 'desc' : 'asc';
    onSort(key, newDirection);
  };

  const handleSelectAll = () => {
    if (!onSelectionChange) return;
    const allKeys = new Set(data.map(row => keyExtractor(row)));
    if (selectedKeys.size === data.length) {
      onSelectionChange(new Set());
    } else {
      onSelectionChange(allKeys);
    }
  };

  const handleSelectRow = (key: string | number) => {
    if (!onSelectionChange) return;
    const newKeys = new Set(selectedKeys);
    if (newKeys.has(key)) {
      newKeys.delete(key);
    } else {
      newKeys.add(key);
    }
    onSelectionChange(newKeys);
  };

  const handleSearch = (e: React.ChangeEvent<HTMLInputElement>) => {
    const value = e.target.value;
    setLocalSearch(value);
    onSearch?.(value);
  };

  // Calculate pagination
  const total = totalCount ?? data.length;
  const totalPages = Math.ceil(total / pageSize);
  const startIndex = (currentPage - 1) * pageSize + 1;
  const endIndex = Math.min(currentPage * pageSize, total);

  const hasSelection = selectedKeys.size > 0;
  const allSelected = selectedKeys.size === data.length && data.length > 0;

  // Desktop Table View
  const DesktopTable = () => (
    <div className="hidden lg:block overflow-hidden">
      <div className={cn(
        'overflow-x-auto',
        stickyHeader && 'max-h-[600px]'
      )}>
        <table className="w-full">
          <thead className={cn(
            stickyHeader && 'sticky top-0 z-10'
          )}>
            <tr className="bg-neutral-50/80 backdrop-blur-sm border-b border-neutral-200 dark:border-primary-800">
              {selectable && (
                <th className="w-12 px-4 py-3">
                  <button
                    onClick={handleSelectAll}
                    className={cn(
                      'w-5 h-5 rounded border-2 flex items-center justify-center transition-all duration-200',
                      allSelected
                        ? 'bg-primary-600 border-primary-600'
                        : 'border-neutral-300 hover:border-primary-400 dark:border-primary-700'
                    )}
                  >
                    {allSelected && <Check className="w-3 h-3 text-white" />}
                  </button>
                </th>
              )}
              {columns.map((col) => (
                <th
                  key={String(col.key)}
                  className={cn(
                    'px-4 text-xs font-semibold text-neutral-500 uppercase tracking-wider dark:text-neutral-400',
                    compact ? 'py-2.5' : 'py-3.5',
                    col.align === 'center' && 'text-center',
                    col.align === 'right' && 'text-right',
                    col.sortable && 'cursor-pointer select-none hover:text-neutral-700 transition-colors'
                  )}
                  style={{ width: col.width }}
                  onClick={() => col.sortable && handleSort(String(col.key))}
                >
                  <div className={cn(
                    'flex items-center gap-1.5',
                    col.align === 'center' && 'justify-center',
                    col.align === 'right' && 'justify-end'
                  )}>
                    <span>{col.header}</span>
                    {col.sortable && (
                      <span className="text-neutral-400">
                        {sortKey === col.key ? (
                          sortDirection === 'asc' ? (
                            <ChevronUp className="w-3.5 h-3.5" />
                          ) : (
                            <ChevronDown className="w-3.5 h-3.5" />
                          )
                        ) : (
                          <ArrowUpDown className="w-3.5 h-3.5 opacity-50" />
                        )}
                      </span>
                    )}
                  </div>
                </th>
              ))}
            </tr>
          </thead>
          <tbody className="divide-y divide-neutral-100">
            {loading ? (
              Array.from({ length: pageSize }).map((_, i) => (
                <tr key={i}>
                  {selectable && (
                    <td className="px-4 py-4">
                      <Skeleton width={20} height={20} variant="rectangular" />
                    </td>
                  )}
                  {columns.map((col) => (
                    <td key={String(col.key)} className="px-4 py-4">
                      <Skeleton width="80%" />
                    </td>
                  ))}
                </tr>
              ))
            ) : data.length === 0 ? (
              <tr>
                <td colSpan={columns.length + (selectable ? 1 : 0)} className="py-16">
                  <EmptyState
                    icon={emptyIcon}
                    title={emptyTitle}
                    description={emptyDescription}
                    action={emptyAction}
                    compact
                  />
                </td>
              </tr>
            ) : (
              data.map((row, index) => {
                const key = keyExtractor(row);
                const isSelected = selectedKeys.has(key);

                return (
                  <tr
                    key={key}
                    onClick={() => onRowClick?.(row)}
                    className={cn(
                      'transition-colors duration-150',
                      onRowClick && 'cursor-pointer',
                      striped && index % 2 === 1 && 'bg-neutral-50/50',
                      isSelected && 'bg-primary-50 dark:bg-primary-800/40',
                      !isSelected && 'hover:bg-neutral-50'
                    )}
                  >
                    {selectable && (
                      <td className="px-4 py-4" onClick={(e) => e.stopPropagation()}>
                        <button
                          onClick={() => handleSelectRow(key)}
                          className={cn(
                            'w-5 h-5 rounded border-2 flex items-center justify-center transition-all duration-200',
                            isSelected
                              ? 'bg-primary-600 border-primary-600'
                              : 'border-neutral-300 hover:border-primary-400 dark:border-primary-700'
                          )}
                        >
                          {isSelected && <Check className="w-3 h-3 text-white" />}
                        </button>
                      </td>
                    )}
                    {columns.map((col) => {
                      const value = col.key.toString().includes('.')
                        ? col.key.toString().split('.').reduce((obj: unknown, key: string) => (obj as Record<string, unknown>)?.[key], row)
                        : (row as Record<string, unknown>)[col.key as string];

                      return (
                        <td
                          key={String(col.key)}
                          className={cn(
                            'px-4 text-sm text-primary-900 dark:text-neutral-50',
                            compact ? 'py-2.5' : 'py-4',
                            col.align === 'center' && 'text-center',
                            col.align === 'right' && 'text-right'
                          )}
                        >
                          {col.render ? col.render(value, row, index) : String(value ?? '-')}
                        </td>
                      );
                    })}
                  </tr>
                );
              })
            )}
          </tbody>
        </table>
      </div>
    </div>
  );

  // Mobile Card View
  const MobileCards = () => {
    const labelColumns = columns.filter(c => c.mobileLabel);
    const valueColumns = columns.filter(c => c.mobileValue);
    const detailColumns = columns.filter(c => !c.mobileLabel && !c.mobileValue && !c.mobileHidden);

    return (
      <div className="lg:hidden space-y-3">
        {loading ? (
          Array.from({ length: 3 }).map((_, i) => (
            <div key={i} className="bg-white rounded-2xl border border-neutral-200 p-4 dark:bg-primary-900 dark:border-primary-800">
              <div className="flex justify-between items-start mb-3">
                <Skeleton width="50%" height={20} />
                <Skeleton width="20%" height={20} />
              </div>
              <div className="space-y-2">
                <Skeleton width="70%" />
                <Skeleton width="60%" />
              </div>
            </div>
          ))
        ) : data.length === 0 ? (
          <EmptyState
            icon={emptyIcon}
            title={emptyTitle}
            description={emptyDescription}
            action={emptyAction}
          />
        ) : (
          data.map((row, index) => {
            const key = keyExtractor(row);
            const isSelected = selectedKeys.has(key);

            if (mobileCardRenderer) {
              return (
                <div key={key} onClick={() => onRowClick?.(row)}>
                  {mobileCardRenderer(row, index)}
                </div>
              );
            }

            return (
              <div
                key={key}
                onClick={() => onRowClick?.(row)}
                className={cn(
                  'bg-white rounded-2xl border border-neutral-200 p-4 dark:bg-primary-900 dark:border-primary-800',
                  'transition-all duration-200',
                  'active:scale-[0.99]',
                  onRowClick && 'cursor-pointer',
                  isSelected && 'border-primary-300 bg-primary-50/50 ring-1 ring-primary-200',
                  !isSelected && 'hover:border-neutral-300 hover:shadow-md'
                )}
              >
                {/* Header row with label and value columns */}
                <div className="flex items-start justify-between gap-3 mb-3">
                  <div className="flex-1 min-w-0">
                    {labelColumns.map((col) => {
                      const value = (row as Record<string, unknown>)[col.key as string];
                      return (
                        <div key={String(col.key)}>
                          {col.render ? col.render(value, row, index) : (
                            <span className="font-semibold text-primary-900 text-base dark:text-neutral-50">
                              {String(value ?? '-')}
                            </span>
                          )}
                        </div>
                      );
                    })}
                  </div>
                  <div className="flex items-center gap-2 shrink-0">
                    {valueColumns.map((col) => {
                      const value = (row as Record<string, unknown>)[col.key as string];
                      return (
                        <div key={String(col.key)}>
                          {col.render ? col.render(value, row, index) : (
                            <span className="font-semibold text-primary-900 dark:text-neutral-50">
                              {String(value ?? '-')}
                            </span>
                          )}
                        </div>
                      );
                    })}
                    {selectable && (
                      <button
                        onClick={(e) => {
                          e.stopPropagation();
                          handleSelectRow(key);
                        }}
                        className={cn(
                          'w-6 h-6 rounded-lg border-2 flex items-center justify-center transition-all duration-200',
                          isSelected
                            ? 'bg-primary-600 border-primary-600'
                            : 'border-neutral-300 dark:border-primary-700'
                        )}
                      >
                        {isSelected && <Check className="w-3.5 h-3.5 text-white" />}
                      </button>
                    )}
                  </div>
                </div>

                {/* Detail rows */}
                <div className="space-y-2">
                  {detailColumns.slice(0, 4).map((col) => {
                    const value = (row as Record<string, unknown>)[col.key as string];
                    return (
                      <div key={String(col.key)} className="flex justify-between items-center text-sm">
                        <span className="text-neutral-500 dark:text-neutral-400">{col.header}</span>
                        <span className="text-primary-900 font-medium dark:text-neutral-50">
                          {col.render ? col.render(value, row, index) : String(value ?? '-')}
                        </span>
                      </div>
                    );
                  })}
                </div>
              </div>
            );
          })
        )}
      </div>
    );
  };

  // Pagination Component
  const Pagination = () => {
    if (!pagination || totalPages <= 1) return null;

    const pages: (number | 'ellipsis')[] = [];
    const maxVisiblePages = 5;

    if (totalPages <= maxVisiblePages) {
      for (let i = 1; i <= totalPages; i++) pages.push(i);
    } else {
      pages.push(1);
      if (currentPage > 3) pages.push('ellipsis');
      for (let i = Math.max(2, currentPage - 1); i <= Math.min(totalPages - 1, currentPage + 1); i++) {
        if (!pages.includes(i)) pages.push(i);
      }
      if (currentPage < totalPages - 2) pages.push('ellipsis');
      if (!pages.includes(totalPages)) pages.push(totalPages);
    }

    return (
      <div className="flex flex-col sm:flex-row items-center justify-between gap-4 mt-6">
        <p className="text-sm text-neutral-500 dark:text-neutral-400">
          Showing <span className="font-medium text-primary-900 dark:text-neutral-50">{startIndex}</span> to{' '}
          <span className="font-medium text-primary-900 dark:text-neutral-50">{endIndex}</span> of{' '}
          <span className="font-medium text-primary-900 dark:text-neutral-50">{total}</span> results
        </p>

        <div className="flex items-center gap-1">
          <Button
            variant="ghost"
            size="sm"
            disabled={currentPage === 1}
            onClick={() => onPageChange?.(1)}
            className="hidden sm:flex"
          >
            <ChevronsLeft className="w-4 h-4" />
          </Button>
          <Button
            variant="ghost"
            size="sm"
            disabled={currentPage === 1}
            onClick={() => onPageChange?.(currentPage - 1)}
          >
            <ChevronLeft className="w-4 h-4" />
          </Button>

          <div className="flex items-center gap-1 px-2">
            {pages.map((page, idx) =>
              page === 'ellipsis' ? (
                <span key={`ellipsis-${idx}`} className="px-2 text-neutral-400">
                  ...
                </span>
              ) : (
                <button
                  key={page}
                  onClick={() => onPageChange?.(page)}
                  className={cn(
                    'w-9 h-9 rounded-xl text-sm font-medium transition-all duration-200',
                    currentPage === page
                      ? 'bg-primary-600 text-white shadow-md'
                      : 'text-neutral-600 hover:bg-neutral-100 dark:text-neutral-300'
                  )}
                >
                  {page}
                </button>
              )
            )}
          </div>

          <Button
            variant="ghost"
            size="sm"
            disabled={currentPage === totalPages}
            onClick={() => onPageChange?.(currentPage + 1)}
          >
            <ChevronRight className="w-4 h-4" />
          </Button>
          <Button
            variant="ghost"
            size="sm"
            disabled={currentPage === totalPages}
            onClick={() => onPageChange?.(totalPages)}
            className="hidden sm:flex"
          >
            <ChevronsRight className="w-4 h-4" />
          </Button>
        </div>
      </div>
    );
  };

  return (
    <div className={cn('w-full', className)}>
      {/* Toolbar */}
      {(searchable || actions || hasSelection) && (
        <div className="flex flex-col sm:flex-row items-stretch sm:items-center justify-between gap-3 mb-4">
          <div className="flex items-center gap-3">
            {searchable && (
              <div className="relative flex-1 sm:flex-none sm:w-72">
                <Search className="absolute left-3.5 top-1/2 -translate-y-1/2 w-4 h-4 text-neutral-400" />
                <input
                  type="text"
                  value={localSearch}
                  onChange={handleSearch}
                  placeholder={searchPlaceholder}
                  className={cn(
                    'w-full h-10 pl-10 pr-4 rounded-xl border border-neutral-200 dark:border-primary-800',
                    'bg-white text-sm placeholder:text-neutral-400 dark:bg-primary-900',
                    'focus:outline-none focus:border-primary-300 focus:ring-2 focus:ring-primary-500/10',
                    'transition-all duration-200'
                  )}
                />
              </div>
            )}
            {hasSelection && bulkActions && (
              <div className="flex items-center gap-2 animate-fade-in">
                <Badge variant="primary" size="sm">
                  {selectedKeys.size} selected
                </Badge>
                {bulkActions}
              </div>
            )}
          </div>

          <div className="flex items-center gap-2">
            {actions}
          </div>
        </div>
      )}

      {/* Table / Cards */}
      <div className={cn(
        'bg-white rounded-2xl border border-neutral-200 overflow-hidden dark:bg-primary-900 dark:border-primary-800',
        'shadow-sm'
      )}>
        <DesktopTable />
        <MobileCards />
      </div>

      {/* Pagination */}
      <Pagination />
    </div>
  );
}

export default DataTable;
