/**
 * Aperture navigation configuration.
 *
 * Phase 7 Design System Unification (2026-05-13): extracted from
 * `components/layout/Layout.tsx`, which previously held the navigation
 * structure inline alongside ~900 lines of rendering JSX. Splitting them
 * makes the information architecture auditable — a designer or PM can
 * read this file end-to-end and understand the entire menu without
 * scrolling through DOM markup.
 *
 * The shape is intentionally narrow:
 *   - {@link navSections}    — the desktop sidebar's section + item structure
 *   - {@link mobileNavItems} — the 5-slot mobile bottom-nav belt
 *   - {@link pageTitles}     — page id → page H1 title
 *   - {@link sectionForPage} — helper for the header breadcrumb pill
 *   - {@link sectionTitleForPage} — helper for sidebar auto-expand
 *
 * Layout.tsx consumes these and is otherwise free of IA decisions.
 *
 * File extension is .tsx (not .ts) because icons are JSX elements; this
 * is a deliberate trade-off for ergonomic authoring vs. a fully decoupled
 * string-name approach.
 */

import React from 'react';
import {
  LayoutDashboard,
  Building2,
  ArrowLeftRight,
  Users,
  FileText,
  Settings,
  Wallet,
  Layers,
  Link2,
  FolderKanban,
  CreditCard,
  Globe,
  Scale,
  AlertTriangle,
  GitBranch,
  Building,
  GitMerge,
  Receipt,
  Handshake,
  Combine,
  Home,
  MoreHorizontal,
  Send,
  QrCode,
  Landmark,
  CircleDot,
  Shield,
  Percent,
  TrendingUp,
  ShoppingCart,
  Store,
  Eye,
  DollarSign,
  Sparkles,    // Insights section / Treasury Copilot
  FileCode2,   // ISO 20022 Payments
  Droplets,    // Multi-Bank Liquidity (water metaphor differentiates from Globe used for Currency Mirrors)
  Briefcase,   // Legal Entities (differentiates from Bank Accounts which uses Building)
  FlaskConical, // Simulator (sandbox — the lab-flask metaphor; reserved for the Simulator only)
} from 'lucide-react';

// ----------------------------------------------------------------------------
// Types
// ----------------------------------------------------------------------------

export interface NavItem {
  icon: React.ReactNode;
  label: string;
  /**
   * Page id for {@code onNavigate(href)}. Special value {@code '__copilot__'}
   * is intercepted by the Layout and opens the Copilot drawer instead of
   * navigating.
   */
  href: string;
  badge?: number;
  isNew?: boolean;
  /** Renders muted + non-clickable + a "Soon" pill — for roadmap features not yet built. */
  isComingSoon?: boolean;
  badgeColor?: 'default' | 'warning' | 'error' | 'success';
  /**
   * Gate this item behind a feature flag (`featureFlags` util). The Layout
   * filters out items whose flag is off, so the entry is fully invisible
   * until the flag flips — used for progressive rollouts (e.g. the
   * Simulator's `simulator.v1`). Unset = always visible.
   */
  featureFlag?: string;
}

export interface NavSection {
  title?: string;
  items: NavItem[];
}

// ----------------------------------------------------------------------------
// Desktop sidebar — section + item structure
// ----------------------------------------------------------------------------

/**
 * Sidebar navigation structure for Aperture.
 *
 * Organised around jobs a treasurer does (visibility → cash management →
 * strategic) rather than around features that were built — the menu shape
 * was restructured during the brand rename. Order is meaningful: the first
 * (untitled) section is the "always-open" overview block; titled sections
 * collapse by default and pop open when the active page lives inside them.
 */
export const navSections: NavSection[] = [
  // Overview — opens here every morning. Visibility-first surfaces.
  {
    items: [
      { icon: <LayoutDashboard className="w-5 h-5" />, label: 'Dashboard', href: 'dashboard' },
      { icon: <Droplets className="w-5 h-5" />, label: 'Multi-Bank Liquidity', href: 'multi-bank-liquidity' },
      { icon: <FileText className="w-5 h-5" />, label: 'Statements', href: 'statements' },
    ]
  },
  // Accounts & Structure — master data + VA architecture (was split across
  // Account Management + Treasury + Physical Accounts).
  {
    title: 'Accounts & Structure',
    items: [
      { icon: <Building2 className="w-5 h-5" />, label: 'Virtual Accounts', href: 'accounts' },
      { icon: <Building className="w-5 h-5" />, label: 'Bank Accounts', href: 'physical-accounts' },
      { icon: <QrCode className="w-5 h-5" />, label: 'VIBAN Management', href: 'viban' },
      { icon: <Link2 className="w-5 h-5" />, label: 'Account Linking', href: 'account-attachments' },
      { icon: <FolderKanban className="w-5 h-5" />, label: 'Programs', href: 'programs' },
      { icon: <GitBranch className="w-5 h-5" />, label: 'Balance Hierarchy', href: 'hierarchy' },
      { icon: <Layers className="w-5 h-5" />, label: 'Entity Balance Tree', href: 'entity-balance-tree' },
      { icon: <Eye className="w-5 h-5" />, label: 'Shadow Accounts', href: 'shadow-accounts' },
      { icon: <Globe className="w-5 h-5" />, label: 'Currency Mirrors', href: 'currency-mirrors' },
      { icon: <Combine className="w-5 h-5" />, label: 'Reorganization', href: 'hierarchy-operations' },
    ]
  },
  // Parties & Entities — who you do business with.
  {
    title: 'Parties & Entities',
    items: [
      { icon: <Briefcase className="w-5 h-5" />, label: 'Legal Entities', href: 'legal-entities' },
      { icon: <Users className="w-5 h-5" />, label: 'Parties & Counterparties', href: 'parties' },
    ]
  },
  // Payments & Collections — everything that moves money. POBO/COBO consolidated here
  // (used to appear under both Transactions and Intercompany).
  {
    title: 'Payments & Collections',
    items: [
      { icon: <ArrowLeftRight className="w-5 h-5" />, label: 'All Transactions', href: 'transactions' },
      { icon: <Send className="w-5 h-5" />, label: 'Transfers', href: 'transfers' },
      { icon: <ArrowLeftRight className="w-5 h-5" />, label: 'Receivables (AR)', href: 'receivables', badge: 2 },
      { icon: <ArrowLeftRight className="w-5 h-5" />, label: 'Payables (AP)', href: 'payables', badge: 1 },
      { icon: <CreditCard className="w-5 h-5" />, label: 'POBO Payments', href: 'intercompany-pobo' },
      { icon: <Wallet className="w-5 h-5" />, label: 'COBO Collections', href: 'intercompany-cobo' },
      { icon: <FileCode2 className="w-5 h-5" />, label: 'ISO 20022 Payments', href: 'iso20022' },
      { icon: <Scale className="w-5 h-5" />, label: 'Settlement VAs', href: 'settlement-vas' },
      { icon: <AlertTriangle className="w-5 h-5" />, label: 'Exceptions', href: 'exceptions', badge: 12, badgeColor: 'warning' },
    ]
  },
  // Liquidity Management — the strategic core. IHB + Netting + IC Dashboard all
  // moved here from "Intercompany" (they're liquidity products, not just IC plumbing).
  {
    title: 'Liquidity Management',
    items: [
      { icon: <ArrowLeftRight className="w-5 h-5" />, label: 'Cash Concentration', href: 'sweeping' },
      { icon: <FlaskConical className="w-5 h-5" />, label: 'Simulator', href: 'simulator', isNew: true },
      { icon: <CircleDot className="w-5 h-5" />, label: 'Notional Pooling', href: 'notional-pooling' },
      { icon: <Landmark className="w-5 h-5" />, label: 'In-House Bank', href: 'ihb' },
      { icon: <GitMerge className="w-5 h-5" />, label: 'Netting Cycles', href: 'netting-enhanced', badge: 2 },
      { icon: <Handshake className="w-5 h-5" />, label: 'Intercompany Dashboard', href: 'intercompany' },
      { icon: <DollarSign className="w-5 h-5" />, label: 'FX Rates', href: 'fx-rates' },
    ]
  },
  // Credit & Interest — was split (Credit Limits lived under Treasury). Consolidated.
  {
    title: 'Credit & Interest',
    items: [
      { icon: <CreditCard className="w-5 h-5" />, label: 'Credit Limits', href: 'credit-limits' },
      { icon: <Percent className="w-5 h-5" />, label: 'Interest Configuration', href: 'interest-config' },
      { icon: <TrendingUp className="w-5 h-5" />, label: 'Interest Accruals', href: 'interest-accruals' },
    ]
  },
  // Insights — home for AI features. Copilot is also a floating FAB on every
  // page; listing it here aids discoverability for new users who haven't
  // noticed the FAB yet. Post-review (2026-05-13): the previously-listed
  // Forecasting / Sweep Optimizer / Smart Reconciliation roadmap items were
  // removed — having 3-out-of-4 items marked `isComingSoon` made the
  // section feel like a teaser rather than a menu. When any of those ships
  // it gets added back here. The roadmap itself lives in
  // tasks/ai-features-roadmap.md.
  {
    title: 'Insights',
    items: [
      { icon: <Sparkles className="w-5 h-5" />, label: 'Treasury Copilot', href: '__copilot__' },
    ]
  },
  // Specialty Programs — narrower products that don't belong with the daily-flow tools.
  {
    title: 'Specialty Programs',
    items: [
      { icon: <Shield className="w-5 h-5" />, label: 'Escrow', href: 'escrow' },
      { icon: <Wallet className="w-5 h-5" />, label: 'Wallets', href: 'wallet' },
      { icon: <ShoppingCart className="w-5 h-5" />, label: 'E-Commerce Collections', href: 'ecommerce-collections' },
      { icon: <Store className="w-5 h-5" />, label: 'Seller Collections', href: 'seller-collections' },
    ]
  },
  {
    title: 'Administration',
    items: [
      { icon: <Receipt className="w-5 h-5" />, label: 'Tax & Charges', href: 'tax-charges' },
      { icon: <Link2 className="w-5 h-5" />, label: 'Integrations', href: 'integrations' },
      { icon: <Settings className="w-5 h-5" />, label: 'Settings', href: 'settings' },
    ]
  },
];

// ----------------------------------------------------------------------------
// Mobile bottom-nav — the 5-slot thumb belt
// ----------------------------------------------------------------------------

/**
 * Mobile bottom-nav items. Lifts Liquidity into the 5-slot belt — for a
 * corporate treasurer, daily liquidity visibility outranks a generic
 * Reports tab. Statements moves to the "More" overflow (still accessible
 * from the desktop sidebar Overview).
 */
export const mobileNavItems: NavItem[] = [
  { icon: <Home className="w-5 h-5" />, label: 'Home', href: 'dashboard' },
  { icon: <Building2 className="w-5 h-5" />, label: 'Accounts', href: 'accounts' },
  { icon: <Droplets className="w-5 h-5" />, label: 'Liquidity', href: 'multi-bank-liquidity' },
  { icon: <Send className="w-5 h-5" />, label: 'Transfers', href: 'transfers' },
  { icon: <MoreHorizontal className="w-5 h-5" />, label: 'More', href: 'more' },
];

// ----------------------------------------------------------------------------
// Page titles — keyed by page id, value shown in the header H1
// ----------------------------------------------------------------------------

/**
 * Page id → page H1 title map. Section grouping is the {@link navSections}
 * declaration above; this map is just "what to put in the H1 when on a
 * given page". Any page not in this map falls back to the page id verbatim.
 */
export const pageTitles: Record<string, string> = {
  // Overview
  'dashboard': 'Dashboard',
  'multi-bank-liquidity': 'Multi-Bank Liquidity',
  'statements': 'Statements',
  // Accounts & Structure
  'accounts': 'Virtual Accounts',
  'physical-accounts': 'Bank Accounts',
  'viban': 'VIBAN Management',
  'account-attachments': 'Account Linking',
  'programs': 'Programs',
  'hierarchy': 'Balance Hierarchy',
  'entity-balance-tree': 'Entity Balance Tree',
  'shadow-accounts': 'Shadow Accounts',
  'currency-mirrors': 'Currency Mirrors',
  'hierarchy-operations': 'Corporate Reorganization',
  // Parties & Entities
  'legal-entities': 'Legal Entities',
  'parties': 'Parties & Counterparties',
  // Payments & Collections
  'transactions': 'All Transactions',
  'transfers': 'Fund Transfers',
  'receivables': 'Accounts Receivable',
  'payables': 'Accounts Payable',
  'intercompany-pobo': 'POBO Payments',
  'intercompany-cobo': 'COBO Collections',
  'iso20022': 'ISO 20022 Payments',
  'settlement-vas': 'Settlement VAs',
  'exceptions': 'Exception Transactions',
  // Liquidity Management
  'sweeping': 'Cash Concentration',
  'simulator': 'Simulator',
  'notional-pooling': 'Notional Pooling',
  'ihb': 'In-House Bank',
  'netting': 'Netting Cycles',
  'netting-enhanced': 'Netting Cycles',
  'intercompany': 'Intercompany Dashboard',
  'intercompany-settlement': 'Intercompany Settlement',
  'fx-rates': 'FX Rate Management',
  // Credit & Interest
  'credit-limits': 'Credit Limits',
  'interest-config': 'Interest Configuration',
  'interest-accruals': 'Interest Accruals',
  // Insights — only Treasury Copilot is shipped; roadmap features
  // (Forecasting, Sweep Optimizer, Smart Reconciliation) removed from
  // the menu per the post-review until they land. Page titles re-add
  // when their corresponding nav items reappear.
  // Specialty Programs
  'escrow': 'Escrow',
  'wallet': 'Wallets',
  'ecommerce-collections': 'E-Commerce Collections',
  'seller-collections': 'Seller Collections',
  // Administration
  'tax-charges': 'Tax & Charges Setup',
  'integrations': 'Integrations',
  'settings': 'Settings',
};

// ----------------------------------------------------------------------------
// Helpers — derived data
// ----------------------------------------------------------------------------

/** All titled section names — used as the initial collapsed set for the sidebar. */
export const ALL_SECTION_TITLES: string[] = navSections
  .map(s => s.title)
  .filter((t): t is string => !!t);

/**
 * Walks the nav structure to find the section title containing a given page.
 * Used by the header breadcrumb so users see the section + page name and
 * learn the mental model. Returns {@code null} for top-level / unmapped pages.
 */
export function sectionForPage(page: string): string | null {
  for (const section of navSections) {
    if (section.items.some(item => item.href === page)) {
      return section.title || null;
    }
  }
  return null;
}

/** Returns the section title (if any) that contains the given page id. */
export function sectionTitleForPage(page: string): string | undefined {
  return navSections.find(s => s.items.some(i => i.href === page))?.title;
}
