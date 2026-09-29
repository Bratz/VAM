// Display config for the shared entity-credit-limit allocation UI. Kept out of
// EntityAllocationModal.tsx so that file exports components only and Fast
// Refresh keeps working.
import type React from 'react';
import { Building2, Crown, Landmark, Users, Briefcase, ArrowLeftRight, FlaskConical } from 'lucide-react';
import type { EntityType, LimitType } from './EntityAllocationModal';

export const currencyConfig: Record<string, { symbol: string; name: string; color: string; bgColor: string }> = {
  AED: { symbol: 'د.إ', name: 'UAE Dirham', color: 'text-success-700 dark:text-success-300', bgColor: 'bg-success-50 dark:bg-success-500/10' },
  USD: { symbol: '$', name: 'US Dollar', color: 'text-success-700 dark:text-success-300', bgColor: 'bg-success-50 dark:bg-success-500/15' },
  EUR: { symbol: '€', name: 'Euro', color: 'text-info-700 dark:text-info-300', bgColor: 'bg-info-50 dark:bg-info-500/15' },
  GBP: { symbol: '£', name: 'British Pound', color: 'text-accent-700 dark:text-accent-300', bgColor: 'bg-accent-50 dark:bg-accent-500/15' },
  SAR: { symbol: 'ر.س', name: 'Saudi Riyal', color: 'text-primary-700 dark:text-neutral-200', bgColor: 'bg-primary-50 dark:bg-primary-800/40' },
  QAR: { symbol: 'ر.ق', name: 'Qatari Riyal', color: 'text-info-700 dark:text-info-300', bgColor: 'bg-info-50 dark:bg-info-500/10' },
  KWD: { symbol: 'د.ك', name: 'Kuwaiti Dinar', color: 'text-warning-700 dark:text-warning-300', bgColor: 'bg-warning-50 dark:bg-warning-500/10' },
  CHF: { symbol: 'CHF', name: 'Swiss Franc', color: 'text-warning-700 dark:text-warning-300', bgColor: 'bg-warning-50 dark:bg-warning-500/10' },
  JPY: { symbol: '¥', name: 'Japanese Yen', color: 'text-accent-700 dark:text-accent-300', bgColor: 'bg-accent-50 dark:bg-accent-500/15' },
  INR: { symbol: '₹', name: 'Indian Rupee', color: 'text-primary-700 dark:text-neutral-200', bgColor: 'bg-primary-50 dark:bg-primary-800/40' },
};

export const entityTypeConfig: Record<EntityType, { label: string; icon: React.ElementType; color: string; bgColor: string }> = {
  HOLDING: { label: 'Holding', icon: Crown, color: 'text-primary-700 dark:text-neutral-200', bgColor: 'bg-primary-100 dark:bg-primary-700' },
  SUBSIDIARY: { label: 'Subsidiary', icon: Building2, color: 'text-info-700 dark:text-info-300', bgColor: 'bg-info-50 dark:bg-info-500/10' },
  BRANCH: { label: 'Branch', icon: Building2, color: 'text-info-600 dark:text-info-300', bgColor: 'bg-info-50 dark:bg-info-500/10' },
  REPRESENTATIVE: { label: 'Representative', icon: Users, color: 'text-neutral-700 dark:text-neutral-200', bgColor: 'bg-surface-muted' },
  JOINT_VENTURE: { label: 'Joint Venture', icon: ArrowLeftRight, color: 'text-warning-700 dark:text-warning-300', bgColor: 'bg-warning-50 dark:bg-warning-500/10' },
  ASSOCIATE: { label: 'Associate', icon: Briefcase, color: 'text-success-700 dark:text-success-300', bgColor: 'bg-success-50 dark:bg-success-500/10' },
  SPV: { label: 'SPV', icon: FlaskConical, color: 'text-error-700 dark:text-error-300', bgColor: 'bg-error-50 dark:bg-error-500/10' },
  TREASURY_CENTER: { label: 'Treasury', icon: Landmark, color: 'text-accent-700 dark:text-accent-300', bgColor: 'bg-accent-100 dark:bg-accent-500/20' },
};

export const limitTypeOptions: { value: LimitType; label: string; description: string }[] = [
  { value: 'OVERDRAFT', label: 'Overdraft', description: 'Standard overdraft facility' },
  { value: 'INTRADAY', label: 'Intraday', description: 'Daylight borrowing - cleared by EOD' },
  { value: 'AGGREGATE', label: 'Aggregate', description: 'Combined limit across all types' },
  { value: 'TRANSACTION', label: 'Per-Transaction', description: 'Maximum per single transaction' },
  { value: 'DAILY', label: 'Daily Cap', description: 'Maximum daily cumulative usage' },
  { value: 'MONTHLY', label: 'Monthly Cap', description: 'Maximum monthly cumulative usage' },
];
