import {
  AlertTriangle, Building2, Calendar, Check, CheckCircle, ChevronDown, ChevronRight, Copy,
  CreditCard, Download, ExternalLink, Filter, Info, Landmark, Loader2, MoreHorizontal, Pencil,
  Plus, RefreshCw, Search, Settings, Trash2, Upload, User, Users, Wallet, X, XCircle,
} from 'lucide-react';
import type { LucideIcon } from 'lucide-react';

/** Icon size scale — full literal class names so Tailwind can see them. */
export const ICON_SIZE = {
  xs: 'w-3 h-3',   // 12px
  sm: 'w-4 h-4',   // 16px — default inline / buttons
  md: 'w-5 h-5',   // 20px — nav / headers
  lg: 'w-6 h-6',   // 24px — cards
  xl: 'w-8 h-8',   // 32px — hero
  hero: 'w-12 h-12', // 48px — empty-state illustrations only
} as const;

export const ICON_PX = { xs: 12, sm: 16, md: 20, lg: 24, xl: 32, hero: 48 } as const;

export interface IconEntry { concept: string; name: string; icon: LucideIcon; note: string }

/** Canonical icon per concept. lucide-react only; stroke stays 2; colour via currentColor. */
export const ICONS: IconEntry[] = [
  { concept: 'Success', name: 'CheckCircle', icon: CheckCircle, note: 'Completed / approved' },
  { concept: 'Warning', name: 'AlertTriangle', icon: AlertTriangle, note: 'Needs attention' },
  { concept: 'Error', name: 'XCircle', icon: XCircle, note: 'Failed / rejected' },
  { concept: 'Info', name: 'Info', icon: Info, note: 'Neutral information' },
  { concept: 'Add', name: 'Plus', icon: Plus, note: 'Create / add row' },
  { concept: 'Close / dismiss', name: 'X', icon: X, note: 'Modals, chips, toasts' },
  { concept: 'Tick', name: 'Check', icon: Check, note: 'Selected item, checkbox' },
  { concept: 'Refresh', name: 'RefreshCw', icon: RefreshCw, note: 'Reload data' },
  { concept: 'Download', name: 'Download', icon: Download, note: 'Export / save file' },
  { concept: 'Upload', name: 'Upload', icon: Upload, note: 'Import / attach file' },
  { concept: 'Search', name: 'Search', icon: Search, note: 'Search inputs' },
  { concept: 'Edit', name: 'Pencil', icon: Pencil, note: 'Edit in place' },
  { concept: 'Delete', name: 'Trash2', icon: Trash2, note: 'Destructive remove' },
  { concept: 'Settings', name: 'Settings', icon: Settings, note: 'Configuration' },
  { concept: 'Loading', name: 'Loader2', icon: Loader2, note: 'Always with animate-spin' },
  { concept: 'Bank', name: 'Landmark', icon: Landmark, note: 'Banks / institutions' },
  { concept: 'Entity / company', name: 'Building2', icon: Building2, note: 'Legal entities' },
  { concept: 'User', name: 'User', icon: User, note: 'Single person' },
  { concept: 'Users', name: 'Users', icon: Users, note: 'Groups / teams' },
  { concept: 'Wallet', name: 'Wallet', icon: Wallet, note: 'Wallet programs' },
  { concept: 'Card', name: 'CreditCard', icon: CreditCard, note: 'Cards / payment method' },
  { concept: 'Navigate forward', name: 'ChevronRight', icon: ChevronRight, note: 'Breadcrumbs, row links' },
  { concept: 'Expand', name: 'ChevronDown', icon: ChevronDown, note: 'Disclosure / select' },
  { concept: 'External link', name: 'ExternalLink', icon: ExternalLink, note: 'Opens elsewhere' },
  { concept: 'Copy', name: 'Copy', icon: Copy, note: 'Copy to clipboard' },
  { concept: 'Filter', name: 'Filter', icon: Filter, note: 'Filter controls' },
  { concept: 'Calendar', name: 'Calendar', icon: Calendar, note: 'Dates' },
  { concept: 'More', name: 'MoreHorizontal', icon: MoreHorizontal, note: 'Overflow menu' },
];

export type StatusTone = 'success' | 'warning' | 'error' | 'info';

export const STATUS_ICONS: Record<StatusTone, LucideIcon> = {
  success: CheckCircle,
  warning: AlertTriangle,
  error: XCircle,
  info: Info,
};
