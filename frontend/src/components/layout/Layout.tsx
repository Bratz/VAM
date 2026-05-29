import React, { useState, useEffect } from 'react';
import {
  // Chrome (header / sidebar / mobile controls) — icons not handled by the
  // navigation config still live here. Menu items themselves get their icons
  // from config/navigation.tsx.
  Bell,
  Search,
  Menu,
  X,
  ChevronDown,
  LogOut,
  User,
  HelpCircle,
  RefreshCw,
  Plus,
  Settings,         // user profile menu icon (not the nav Settings item)
  Layers,           // mobile header logo medallion
  ArrowLeftRight,   // mobile quick-action: Transfer
  FileText,         // mobile quick-action: Statement
  Moon,
  Sun,
} from 'lucide-react';
import { cn } from '../../utils';
import { Avatar } from '../ui';
import { BRAND } from '../../branding';
import { EntityPicker } from '../permissions/EntityPicker';
import { useUser } from '../../context/UserContext';
import { useTheme } from '../../design-system/ThemeProvider';
import { useCopilot } from '../../ai/copilot/CopilotProvider';
import { useRegisteredPageHeaderActions } from '../../context/PageHeaderContext';
// Phase 7 Design System Unification: IA (navigation structure + page titles +
// mobile bottom-nav) moved out of this file into a dedicated config module
// so it can be audited as a flat data file rather than scattered through
// 1000+ lines of JSX. Layout is now a renderer over this config.
import {
  navSections,
  mobileNavItems,
  pageTitles,
  ALL_SECTION_TITLES,
  sectionForPage,
  sectionTitleForPage,
  type NavItem,
} from '../../config/navigation';
import { featureFlags } from '../../utils/featureFlags';

// ============================================================================
// APERTURE LAYOUT - Premium-Glass renderer over the navigation config
// Desktop sidebar + mobile bottom-nav + header chrome.
// IA lives in config/navigation.tsx; this file is rendering-only.
// ============================================================================

// ============================================================================
// Desktop Sidebar - Premium Glass Design
// ============================================================================

interface SidebarProps {
  isOpen: boolean;
  currentPath: string;
  onNavigate: (page: string) => void;
  onClose: () => void;
}

const Sidebar: React.FC<SidebarProps> = ({ isOpen, currentPath, onNavigate, onClose }) => {
  // Sections start collapsed on open. The useEffect below pops the active
  // section back open so the user sees a highlighted active item in context.
  const [collapsedSections, setCollapsedSections] = useState<Set<string>>(
    () => new Set(ALL_SECTION_TITLES)
  );
  const [searchQuery, setSearchQuery] = useState('');
  const { open: openCopilot } = useCopilot();

  // Whenever the user navigates, expand the section containing the new page.
  // Doesn't re-collapse other sections — once a user opens one, it stays open
  // until they collapse it themselves.
  useEffect(() => {
    const title = sectionTitleForPage(currentPath);
    if (!title) return;
    setCollapsedSections(prev => {
      if (!prev.has(title)) return prev;
      const next = new Set(prev);
      next.delete(title);
      return next;
    });
  }, [currentPath]);

  /**
   * Central click handler — intercepts the special {@code __copilot__} href
   * to open the drawer instead of navigating, and no-ops on coming-soon items.
   */
  const handleItemClick = (item: NavItem) => {
    if (item.isComingSoon) return;
    if (item.href === '__copilot__') {
      openCopilot();
      onClose();
      return;
    }
    onNavigate(item.href);
    onClose();
  };

  const toggleSection = (title: string) => {
    setCollapsedSections(prev => {
      const next = new Set(prev);
      if (next.has(title)) next.delete(title);
      else next.add(title);
      return next;
    });
  };

  const getBadgeStyle = (isActive: boolean, badgeColor?: string) => {
    if (isActive) return 'bg-white/20 text-white';   // works on navy in both modes
    switch (badgeColor) {
      case 'warning': return 'bg-warning-100 text-warning-700 dark:bg-warning-500/15 dark:text-warning-300';
      case 'error':   return 'bg-error-100 text-error-700 dark:bg-error-500/15 dark:text-error-300';
      case 'success': return 'bg-success-100 text-success-700 dark:bg-success-500/15 dark:text-success-300';
      default:        return 'bg-primary-100 text-primary-700 dark:bg-primary-800/60 dark:text-primary-200 dark:bg-primary-700 dark:text-neutral-200';
    }
  };

  // Hide flag-gated items whose flag is off (progressive rollout — e.g. the
  // Simulator's `simulator.v1`). Done before the search filter so a hidden
  // item never appears in search results either.
  const flagVisible = (item: NavItem) =>
    !item.featureFlag || featureFlags.isOn(item.featureFlag);
  const visibleSections = navSections
    .map(section => ({ ...section, items: section.items.filter(flagVisible) }))
    .filter(section => section.items.length > 0);

  // Filter navigation based on search
  const filteredSections = searchQuery
    ? visibleSections.map(section => ({
        ...section,
        items: section.items.filter(item =>
          item.label.toLowerCase().includes(searchQuery.toLowerCase())
        )
      })).filter(section => section.items.length > 0)
    : visibleSections;

  return (
    <>
      {/* Backdrop for mobile */}
      {isOpen && (
        <div
          className="fixed inset-0 bg-primary-950/40 backdrop-blur-sm z-40 lg:hidden animate-fade-in"
          onClick={onClose}
        />
      )}

      <aside className={cn(
        'fixed top-0 left-0 h-full w-72 z-50 flex flex-col',
        // Premium glass effect — flips to elevated navy panel in dark mode
        'bg-white/95 backdrop-blur-xl border-r border-neutral-200/60',
        'dark:bg-primary-900/95 dark:border-primary-800/60',
        // Premium shadow
        'shadow-xl lg:shadow-2xl dark:shadow-none',
        // Animation
        'transform transition-transform duration-300 ease-out',
        'lg:translate-x-0',
        isOpen ? 'translate-x-0' : '-translate-x-full'
      )}>
        {/* Logo Header - Premium */}
        <div className="h-20 flex items-center justify-between px-6 border-b border-neutral-200/60 dark:border-primary-800/60">
          <div className="flex items-center gap-3">
            {/* Logo tile — Phase 8 post-review (2026-05-13). The previous
                three-stop gradient + colored drop-shadow + dark-only ring
                was the same recipe just retired from the active nav state
                (Phase 6); leaving it here made the logo the loudest thing
                on the sidebar. Flattened to a solid primary-900 tile with
                an always-on gold ring — quieter, and the gold ring ties
                the brand monogram to the same accent we use on focus
                rings, page-title underlines, and the active-nav rule. */}
            <div className="w-11 h-11 bg-primary-900 dark:bg-primary-800 rounded-lg flex items-center justify-center ring-1 ring-accent-500/60 dark:ring-accent-400/60">
              <Layers className="w-5 h-5 text-white" />
            </div>
            <div>
              {/* Brand wordmark in Fraunces — ties the sidebar to the serif accent used on page titles.
                  Pulled from src/branding.ts so a rebrand is a single-file change. */}
              <h1
                className="font-display text-primary-900 dark:text-neutral-50 text-xl tracking-tight"
                style={{ fontWeight: 500, letterSpacing: '-0.015em' }}
                title={BRAND.tagline}
              >
                {BRAND.name}
              </h1>
              <p className="text-[11px] text-neutral-500 dark:text-neutral-400 font-medium uppercase tracking-[0.12em]">
                {BRAND.shortSubtitle}
              </p>
            </div>
          </div>
          <button
            onClick={onClose}
            className="lg:hidden p-2 hover:bg-neutral-100 dark:hover:bg-primary-800 rounded-xl transition-colors"
          >
            <X className="w-5 h-5 text-neutral-500 dark:text-neutral-400" />
          </button>
        </div>

        {/* Search */}
        <div className="px-4 py-4">
          <div className="relative">
            <Search className="absolute left-3.5 top-1/2 -translate-y-1/2 w-4 h-4 text-neutral-400 dark:text-neutral-500" />
            <input
              type="text"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              placeholder="Search menu..."
              className={cn(
                'w-full h-10 pl-10 pr-4 rounded-xl border border-neutral-200 dark:border-primary-800',
                'bg-neutral-50/80 text-sm text-primary-900 placeholder:text-neutral-400 dark:text-neutral-50',
                'dark:bg-primary-950/60 dark:border-primary-800 dark:text-neutral-100 dark:placeholder:text-neutral-500',
                'focus:outline-none focus:border-primary-300 focus:bg-white focus:ring-2 focus:ring-primary-500/10',
                'dark:focus:border-accent-400 dark:focus:bg-primary-950 dark:focus:ring-accent-400/20',
                'transition-all duration-200'
              )}
            />
          </div>
        </div>

        {/* Navigation - Scrollable */}
        <nav className="flex-1 overflow-y-auto px-3 pb-4 scrollbar-thin">
          {filteredSections.map((section, sectionIdx) => (
            <div key={sectionIdx} className="mb-2">
              {section.title && (
                <button
                  onClick={() => toggleSection(section.title!)}
                  className={cn(
                    'flex items-center justify-between w-full px-3 py-2.5 mt-2',
                    'text-[11px] font-semibold text-neutral-400 dark:text-neutral-500 uppercase tracking-wider',
                    'hover:text-neutral-600 dark:hover:text-neutral-300 transition-colors rounded-lg hover:bg-neutral-50 dark:hover:bg-primary-800/50'
                  )}
                >
                  <span>{section.title}</span>
                  <ChevronDown className={cn(
                    'w-3.5 h-3.5 transition-transform duration-200',
                    collapsedSections.has(section.title) && '-rotate-90'
                  )} />
                </button>
              )}

              <div className={cn(
                'space-y-1 overflow-hidden transition-all duration-200',
                // When searching, override the collapse so matching items
                // surface regardless of which sections the user has closed.
                section.title && collapsedSections.has(section.title) && !searchQuery && 'h-0 opacity-0'
              )}>
                {section.items.map((item) => {
                  const isActive = currentPath === item.href;
                  const isComingSoon = !!item.isComingSoon;
                  return (
                    <button
                      key={item.href}
                      onClick={() => handleItemClick(item)}
                      disabled={isComingSoon}
                      title={isComingSoon ? 'Coming soon' : undefined}
                      className={cn(
                        // Phase 6 Design System Unification: active state calmed
                        // down from "gradient + colored shadow + dark-only left
                        // rule" to "solid navy fill + 2px gold left rule" in
                        // both modes. The transparent left border on inactive
                        // items prevents layout shift when an item becomes
                        // active. Rounded-lg (12px) replaces rounded-xl per the
                        // canonical radius scale collapse.
                        'w-full flex items-center gap-3 pl-[10px] pr-3 py-2.5 rounded-lg text-sm font-medium',
                        'border-l-2 transition-colors duration-200',
                        isComingSoon
                          ? 'border-transparent text-neutral-400 dark:text-neutral-600 cursor-not-allowed opacity-60'
                          : isActive
                          ? 'border-accent-500 dark:border-accent-400 bg-primary-800 dark:bg-primary-700/80 text-white'
                          : 'border-transparent text-neutral-600 hover:bg-neutral-100 hover:text-primary-900 dark:text-neutral-300 dark:hover:bg-primary-800/60 dark:hover:text-neutral-50'
                      )}
                    >
                      <span className={cn(
                        'transition-colors',
                        isComingSoon
                          ? 'text-neutral-300 dark:text-neutral-700'
                          : isActive
                          ? 'text-white'
                          : 'text-neutral-400 dark:text-neutral-500 group-hover:text-primary-600'
                      )}>
                        {item.icon}
                      </span>
                      <span className="flex-1 text-left truncate">{item.label}</span>
                      {item.isNew && !isComingSoon && (
                        <span className={cn(
                          'text-[9px] font-bold px-1.5 py-0.5 rounded-full uppercase tracking-wide',
                          isActive ? 'bg-white/25 text-white' : 'bg-accent-100 text-accent-700 dark:bg-accent-500/20 dark:text-accent-300'
                        )}>
                          New
                        </span>
                      )}
                      {isComingSoon && (
                        <span className="text-[9px] font-bold px-1.5 py-0.5 rounded-full uppercase tracking-wide bg-neutral-100 text-neutral-500 dark:bg-primary-800/60 dark:text-neutral-400">
                          Soon
                        </span>
                      )}
                      {item.badge && !isComingSoon && (
                        <span className={cn(
                          'text-[10px] font-bold min-w-[20px] h-5 flex items-center justify-center rounded-full',
                          getBadgeStyle(isActive, item.badgeColor)
                        )}>
                          {item.badge}
                        </span>
                      )}
                    </button>
                  );
                })}
              </div>
            </div>
          ))}
        </nav>

        {/* Sidebar footer — slim row instead of the old 120px-tall help card.
            Frees the bottom of the sidebar for menu items on shorter viewports.
            Tooltip on the icon explains the destination; clicking still goes to docs. */}
        <div className="px-4 py-3 border-t border-neutral-200/60 dark:border-primary-800/60 flex items-center justify-between gap-2">
          <button
            type="button"
            title="Documentation & support"
            className={cn(
              'flex items-center gap-2 px-2 py-1.5 rounded-lg text-xs font-medium',
              'text-neutral-500 hover:text-primary-700 hover:bg-neutral-100',
              'dark:text-neutral-400 dark:hover:text-neutral-50 dark:hover:bg-primary-800/60',
              'transition-colors'
            )}
          >
            <HelpCircle className="w-4 h-4" />
            <span>Docs</span>
          </button>
          <span className="text-[10px] text-neutral-400 dark:text-neutral-500 uppercase tracking-wider">
            {BRAND.name} v1.0
          </span>
        </div>
      </aside>
    </>
  );
};

// ============================================================================
// Desktop Header - Premium Glass Design
// ============================================================================

interface HeaderProps {
  onMenuClick: () => void;
  currentPage: string;
}

const Header: React.FC<HeaderProps> = ({ onMenuClick, currentPage }) => {
  const [showNotifications, setShowNotifications] = useState(false);
  const [showUserMenu, setShowUserMenu] = useState(false);
  const [showSearch, setShowSearch] = useState(false);
  const { isTreasury } = useUser();
  const { resolvedMode, toggleMode } = useTheme();
  const pageActions = useRegisteredPageHeaderActions();

  const notifications = [
    { id: 0, title: 'POBO Payment Processed', message: 'AED 150,000 paid by HQ on behalf of Dubai Sub', time: '1 min ago', type: 'success' },
    { id: 1, title: 'Netting Cycle Ready', message: 'Q1 2024 cycle calculated - AED 275K savings', time: '5 min ago', type: 'info' },
    { id: 2, title: 'Credit Limit Warning', message: 'EUR Overdraft Facility at 85% utilization', time: '15 min ago', type: 'warning' },
    { id: 3, title: 'New Exception', message: 'Unmatched payment AED 15,000 routed to Exception VA', time: '30 min ago', type: 'warning' },
  ];

  return (
    <header className={cn(
      'sticky top-0 z-30 backdrop-blur-xl',
      'bg-white/80 border-b border-neutral-200/60',
      'dark:bg-primary-900/80 dark:border-primary-800/60',
      'supports-[backdrop-filter]:bg-white/60 dark:supports-[backdrop-filter]:bg-primary-900/60'
    )}>
      {/* min-h-16 instead of h-16 so the header expands gracefully when the
          stacked breadcrumb + title + page-title-display underline exceed 64px.
          py-2 prevents the gold underline pseudo-element from kissing the
          header's border-b. */}
      <div className="flex items-center justify-between min-h-16 px-4 lg:px-8 py-2">
        {/* Left side */}
        <div className="flex items-center gap-4">
          <button
            onClick={onMenuClick}
            className="lg:hidden p-2.5 hover:bg-neutral-100 rounded-xl transition-colors"
          >
            <Menu className="w-5 h-5 text-neutral-600 dark:text-neutral-300" />
          </button>

          {/* Page Title - Desktop. F2: Fraunces display + gold underline accent.
              Polish: small section breadcrumb above the title helps users learn
              the Aperture mental model (which section the current page lives in). */}
          {/* whitespace-nowrap on both lines so 'ACCOUNTS & STRUCTURE' / 'Balance
              Hierarchy' don't break to two rows on standard-width headers. */}
          <div className="hidden sm:block leading-tight min-w-0">
            {sectionForPage(currentPage) && (
              <p className="text-[10px] leading-none uppercase tracking-[0.16em] text-neutral-500 dark:text-neutral-400 font-semibold mb-1 whitespace-nowrap">
                {sectionForPage(currentPage)}
              </p>
            )}
            <h1 className="page-title-display text-xl leading-tight text-primary-900 dark:text-neutral-50 whitespace-nowrap">
              {pageTitles[currentPage] || BRAND.name}
            </h1>
          </div>

          {/* Search - Desktop */}
          <div className="hidden lg:flex items-center ml-4">
            <div className="relative">
              <Search className="absolute left-3.5 top-1/2 -translate-y-1/2 w-4 h-4 text-neutral-400" />
              <input
                type="text"
                placeholder="Search accounts, transactions..."
                className={cn(
                  'w-80 h-10 pl-10 pr-12 rounded-xl border border-neutral-200 dark:border-primary-800',
                  'bg-neutral-50/80 text-sm placeholder:text-neutral-400',
                  'focus:outline-none focus:border-primary-300 focus:bg-white focus:ring-2 focus:ring-primary-500/10',
                  'transition-all duration-200'
                )}
              />
              {/* ⌘K shortcut chip — Phase 8 Design System Unification:
                  switched to JetBrains Mono so the keyboard glyph reads as
                  "code/keystroke" alongside our numerics-and-code mono rule.
                  Generic sans previously made it look like a label, not a key. */}
              <kbd className="absolute right-3 top-1/2 -translate-y-1/2 label-cased bg-white dark:bg-primary-900 px-1.5 py-0.5 rounded border border-neutral-200 dark:border-primary-800 font-mono font-medium tracking-tight">
                ⌘K
              </kbd>
            </div>
          </div>
        </div>

        {/* Right side */}
        <div className="flex items-center gap-2">
          {/* Page-registered toolbar actions (Reload, Export, etc.) come first
              in the right cluster so they sit closest to the page title. Each
              page registers via {@code usePageHeaderActions}. */}
          {pageActions && (
            <div className="hidden md:flex items-center gap-2 mr-2">
              {pageActions}
            </div>
          )}

          {/* Entity Picker — Desktop.
              Slimmed down per UI audit: corporate dropdown hidden (most pages
              carry their own corporate selector), role pill hidden (role is
              already in the user chip below). Just the entity dropdown. */}
          <div className="hidden lg:block">
            <EntityPicker compact showCorporate={false} showRoleBadge={false} />
          </div>

          {/* Mobile Search Button */}
          <button
            onClick={() => setShowSearch(!showSearch)}
            className="lg:hidden p-2.5 hover:bg-neutral-100 rounded-xl transition-colors"
          >
            <Search className="w-5 h-5 text-neutral-600 dark:text-neutral-300" />
          </button>

          {/* F5: Theme toggle */}
          <button
            onClick={toggleMode}
            aria-label={resolvedMode === 'dark' ? 'Switch to light mode' : 'Switch to dark mode'}
            title={resolvedMode === 'dark' ? 'Switch to light mode' : 'Switch to dark mode'}
            className="relative p-2.5 hover:bg-neutral-100 dark:hover:bg-primary-800 rounded-xl transition-colors"
          >
            {resolvedMode === 'dark'
              ? <Sun className="w-5 h-5 text-accent-400" />
              : <Moon className="w-5 h-5 text-neutral-600 dark:text-neutral-300" />}
          </button>

          {/* Notifications */}
          <div className="relative">
            <button
              onClick={() => setShowNotifications(!showNotifications)}
              className="relative p-2.5 hover:bg-neutral-100 rounded-xl transition-colors"
            >
              <Bell className="w-5 h-5 text-neutral-600 dark:text-neutral-300" />
              <span className="absolute top-2 right-2 w-2 h-2 bg-error-500 rounded-full ring-2 ring-white" />
            </button>

            {showNotifications && (
              <>
                <div className="fixed inset-0 z-10" onClick={() => setShowNotifications(false)} />
                <div className={cn(
                  'absolute right-0 top-full mt-2 w-96 max-w-[calc(100vw-2rem)]',
                  'bg-white rounded-2xl shadow-2xl border border-neutral-200/60 z-20 overflow-hidden dark:bg-primary-900',
                  'animate-scale-in origin-top-right'
                )}>
                  <div className="p-4 border-b border-neutral-100 flex items-center justify-between dark:border-primary-800/60">
                    <h3 className="font-semibold text-primary-900 dark:text-neutral-50">Notifications</h3>
                    <button className="text-sm text-primary-600 hover:text-primary-700 font-medium dark:text-primary-200">
                      Mark all read
                    </button>
                  </div>
                  <div className="max-h-80 overflow-y-auto">
                    {notifications.map((n) => (
                      <div
                        key={n.id}
                        className="p-4 hover:bg-neutral-50 cursor-pointer border-b border-neutral-100 last:border-0 transition-colors dark:border-primary-800/60"
                      >
                        <div className="flex items-start gap-3">
                          <div className={cn(
                            'w-2.5 h-2.5 rounded-full mt-1.5 shrink-0',
                            n.type === 'warning' && 'bg-warning-500',
                            n.type === 'info' && 'bg-info-500',
                            n.type === 'success' && 'bg-success-500',
                            n.type === 'error' && 'bg-error-500'
                          )} />
                          <div className="min-w-0">
                            <p className="text-sm font-medium text-primary-900 dark:text-neutral-50">{n.title}</p>
                            <p className="text-sm text-neutral-500 mt-0.5 line-clamp-2 dark:text-neutral-400">{n.message}</p>
                            <p className="text-xs text-neutral-400 mt-1.5">{n.time}</p>
                          </div>
                        </div>
                      </div>
                    ))}
                  </div>
                  <div className="p-3 border-t border-neutral-100 bg-neutral-50/50 dark:border-primary-800/60">
                    <button className="w-full text-sm text-primary-600 hover:text-primary-700 font-medium py-1.5 dark:text-primary-200">
                      View All Notifications
                    </button>
                  </div>
                </div>
              </>
            )}
          </div>

          {/* Divider */}
          <div className="hidden md:block w-px h-8 bg-neutral-200 mx-2" />

          {/* User Menu */}
          <div className="relative">
            <button
              onClick={() => setShowUserMenu(!showUserMenu)}
              className="flex items-center gap-3 p-2 hover:bg-neutral-100 rounded-xl transition-colors"
            >
              <Avatar name="John Doe" size="sm" />
              <div className="hidden md:block text-left">
                <p className="text-sm font-medium text-primary-900 dark:text-neutral-50">John Doe</p>
                {/* Role only — the entity code is now shown in the EntityPicker
                    above, so the (MNC-HOLDING) suffix here was redundant. */}
                <p className={cn(
                  'text-[11px] font-medium',
                  isTreasury ? 'text-primary-600 dark:text-primary-200' : 'text-neutral-500 dark:text-neutral-400'
                )}>
                  {isTreasury ? 'Treasury' : 'Subsidiary'}
                </p>
              </div>
              <ChevronDown className="w-4 h-4 text-neutral-400 hidden md:block" />
            </button>

            {showUserMenu && (
              <>
                <div className="fixed inset-0 z-10" onClick={() => setShowUserMenu(false)} />
                <div className={cn(
                  'absolute right-0 top-full mt-2 w-64',
                  'bg-white rounded-2xl shadow-2xl border border-neutral-200/60 z-20 overflow-hidden dark:bg-primary-900',
                  'animate-scale-in origin-top-right'
                )}>
                  <div className="px-4 py-4 border-b border-neutral-100 bg-neutral-50/50 dark:border-primary-800/60">
                    <div className="flex items-center gap-3">
                      <Avatar name="John Doe" size="md" />
                      <div>
                        <p className="text-sm font-semibold text-primary-900 dark:text-neutral-50">John Doe</p>
                        <p className="text-xs text-neutral-500 dark:text-neutral-400">john.doe@company.com</p>
                      </div>
                    </div>
                  </div>
                  <div className="py-2">
                    <button className="w-full flex items-center gap-3 px-4 py-2.5 text-sm text-neutral-700 hover:bg-neutral-50 transition-colors dark:text-neutral-200">
                      <User className="w-4 h-4 text-neutral-400" />
                      Profile Settings
                    </button>
                    <button className="w-full flex items-center gap-3 px-4 py-2.5 text-sm text-neutral-700 hover:bg-neutral-50 transition-colors dark:text-neutral-200">
                      <Settings className="w-4 h-4 text-neutral-400" />
                      Preferences
                    </button>
                  </div>
                  <div className="border-t border-neutral-100 py-2 dark:border-primary-800/60">
                    <button className="w-full flex items-center gap-3 px-4 py-2.5 text-sm text-error-600 hover:bg-error-50 transition-colors dark:text-error-300">
                      <LogOut className="w-4 h-4" />
                      Sign Out
                    </button>
                  </div>
                </div>
              </>
            )}
          </div>
        </div>
      </div>

      {/* Mobile Search Overlay */}
      {showSearch && (
        <div className="lg:hidden absolute inset-x-0 top-full bg-white border-b border-neutral-200 p-4 animate-slide-down dark:bg-primary-900 dark:border-primary-800">
          <div className="relative">
            <Search className="absolute left-3.5 top-1/2 -translate-y-1/2 w-4 h-4 text-neutral-400" />
            <input
              type="text"
              placeholder="Search..."
              autoFocus
              className={cn(
                'w-full h-11 pl-10 pr-4 rounded-xl border border-neutral-200 dark:border-primary-800',
                'bg-neutral-50 text-base placeholder:text-neutral-400 dark:bg-primary-950',
                'focus:outline-none focus:border-primary-300 focus:bg-white focus:ring-2 focus:ring-primary-500/10'
              )}
            />
          </div>
        </div>
      )}
    </header>
  );
};

// ============================================================================
// Mobile Bottom Navigation - Premium Touch-Optimized
// ============================================================================

interface BottomNavProps {
  currentPath: string;
  onNavigate: (page: string) => void;
  onMoreClick: () => void;
}

const BottomNav: React.FC<BottomNavProps> = ({ currentPath, onNavigate, onMoreClick }) => {
  return (
    <nav className={cn(
      'fixed inset-x-0 bottom-0 z-40 lg:hidden',
      'bg-white/95 backdrop-blur-xl border-t border-neutral-200/60',
      'shadow-[0_-4px_20px_rgba(16,42,67,0.08)]',
      'safe-bottom'
    )}>
      <div className="flex items-center justify-around h-16 px-2">
        {mobileNavItems.map((item) => {
          const isActive = item.href === 'more' ? false : currentPath === item.href;
          const isMore = item.href === 'more';

          return (
            <button
              key={item.href}
              onClick={() => isMore ? onMoreClick() : onNavigate(item.href)}
              className={cn(
                // Mobile active state — Phase 8 post-review (2026-05-13).
                // Now uses the same two-signal language as the desktop
                // sidebar (Phase 6): solid colour + 2px gold rule. The
                // earlier version layered THREE signals (button-level
                // background pill + icon-level background pill + dot/
                // underline) for one state, which read as noisy and
                // didn't speak the same language as desktop. Here:
                //   1. Text + icon shift to primary-700 / dark:neutral-50.
                //   2. 16×2px gold underline at the bottom edge.
                // No background pills.
                'relative flex flex-col items-center justify-center py-1 px-3 min-w-[64px] rounded-lg',
                'transition-colors duration-200',
                isActive
                  ? 'text-primary-700 dark:text-neutral-50'
                  : 'text-neutral-500 active:bg-neutral-100 dark:text-neutral-400'
              )}
            >
              <div className="p-1.5">
                {item.icon}
              </div>
              <span className={cn(
                'text-[10px] font-medium mt-0.5',
                isActive && 'text-primary-700 dark:text-neutral-50'
              )}>
                {item.label}
              </span>
              {isActive && (
                <span
                  aria-hidden
                  className="absolute -bottom-0.5 left-1/2 -translate-x-1/2 w-4 h-0.5 bg-accent-500 dark:bg-accent-400 rounded-full"
                />
              )}
            </button>
          );
        })}
      </div>
    </nav>
  );
};

// ============================================================================
// Mobile More Menu (Bottom Sheet)
// ============================================================================

interface MoreMenuProps {
  isOpen: boolean;
  onClose: () => void;
  currentPath: string;
  onNavigate: (page: string) => void;
}

const MoreMenu: React.FC<MoreMenuProps> = ({ isOpen, onClose, currentPath, onNavigate }) => {
  if (!isOpen) return null;

  const quickActions = [
    { icon: <Plus className="w-5 h-5" />, label: 'New Account', action: 'create-account' },
    { icon: <ArrowLeftRight className="w-5 h-5" />, label: 'Transfer', action: 'transfer' },
    { icon: <FileText className="w-5 h-5" />, label: 'Statement', action: 'statement' },
    { icon: <RefreshCw className="w-5 h-5" />, label: 'Refresh', action: 'refresh' },
  ];

  return (
    <>
      <div
        className="fixed inset-0 bg-primary-950/40 backdrop-blur-sm z-50 animate-fade-in"
        onClick={onClose}
      />
      <div className={cn(
        'fixed inset-x-0 bottom-0 z-50 bg-white rounded-t-3xl dark:bg-primary-900',
        'shadow-[0_-10px_40px_rgba(16,42,67,0.15)]',
        'animate-slide-in-up safe-bottom',
        'max-h-[85vh] overflow-hidden flex flex-col'
      )}>
        {/* Handle */}
        <div className="flex justify-center pt-3 pb-2">
          <div className="w-10 h-1 bg-neutral-300 rounded-full" />
        </div>

        {/* Header */}
        <div className="px-6 py-3 border-b border-neutral-100 dark:border-primary-800/60">
          <h2 className="section-title">Menu</h2>
        </div>

        {/* Quick Actions */}
        <div className="px-4 py-4 border-b border-neutral-100 dark:border-primary-800/60">
          <p className="text-xs font-semibold text-neutral-400 uppercase tracking-wider px-2 mb-3">Quick Actions</p>
          <div className="grid grid-cols-4 gap-2">
            {quickActions.map((action) => (
              <button
                key={action.action}
                className="flex flex-col items-center gap-1.5 p-3 rounded-xl hover:bg-neutral-50 active:bg-neutral-100 transition-colors"
              >
                <div className="w-12 h-12 bg-primary-50 rounded-xl flex items-center justify-center text-primary-600 dark:bg-primary-800/40 dark:text-primary-200">
                  {action.icon}
                </div>
                <span className="text-xs font-medium text-neutral-700 dark:text-neutral-200">{action.label}</span>
              </button>
            ))}
          </div>
        </div>

        {/* Navigation sections - Scrollable */}
        <div className="flex-1 overflow-y-auto px-4 py-4">
          {navSections.slice(1).map((section, idx) => (
            section.title && (
              <div key={idx} className="mb-4">
                <p className="text-xs font-semibold text-neutral-400 uppercase tracking-wider px-2 mb-2">
                  {section.title}
                </p>
                <div className="space-y-0.5">
                  {section.items.map((item) => {
                    const isActive = currentPath === item.href;
                    return (
                      <button
                        key={item.href}
                        onClick={() => { onNavigate(item.href); onClose(); }}
                        className={cn(
                          'w-full flex items-center gap-3 px-3 py-3 rounded-xl',
                          'transition-colors active:bg-neutral-100',
                          isActive
                            ? 'bg-primary-50 text-primary-700 dark:bg-primary-800/40 dark:text-neutral-200'
                            : 'text-neutral-700 hover:bg-neutral-50 dark:text-neutral-200'
                        )}
                      >
                        <span className={isActive ? 'text-primary-600 dark:text-primary-200' : 'text-neutral-400'}>
                          {item.icon}
                        </span>
                        <span className="flex-1 text-left text-sm font-medium">{item.label}</span>
                        {item.badge && (
                          <span className={cn(
                            'text-xs font-semibold px-2 py-0.5 rounded-full',
                            item.badgeColor === 'warning' ? 'bg-warning-100 text-warning-700 dark:bg-warning-500/20 dark:text-warning-300' :
                            item.badgeColor === 'error' ? 'bg-error-100 text-error-700 dark:bg-error-500/20 dark:text-error-300' :
                            'bg-primary-100 text-primary-700 dark:bg-primary-700 dark:text-neutral-200'
                          )}>
                            {item.badge}
                          </span>
                        )}
                        {item.isNew && (
                          <span className="text-[9px] font-bold px-1.5 py-0.5 rounded-full bg-accent-100 text-accent-700 uppercase dark:bg-accent-500/20 dark:text-accent-300">
                            New
                          </span>
                        )}
                      </button>
                    );
                  })}
                </div>
              </div>
            )
          ))}
        </div>

        {/* Close button */}
        <div className="p-4 border-t border-neutral-100 dark:border-primary-800/60">
          <button
            onClick={onClose}
            className="w-full h-12 bg-neutral-100 text-neutral-700 rounded-xl font-medium hover:bg-neutral-200 transition-colors dark:bg-primary-800 dark:text-neutral-200"
          >
            Close
          </button>
        </div>
      </div>
    </>
  );
};

// ============================================================================
// Main Layout Component
// ============================================================================

interface LayoutProps {
  children: React.ReactNode;
  currentPage: string;
  onNavigate: (page: string, params?: Record<string, string>) => void;
}

export const Layout: React.FC<LayoutProps> = ({ children, currentPage, onNavigate }) => {
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const [moreMenuOpen, setMoreMenuOpen] = useState(false);

  // Close sidebar on route change
  useEffect(() => {
    setSidebarOpen(false);
  }, [currentPage]);

  // Handle escape key
  useEffect(() => {
    const handleEscape = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setSidebarOpen(false);
        setMoreMenuOpen(false);
      }
    };
    window.addEventListener('keydown', handleEscape);
    return () => window.removeEventListener('keydown', handleEscape);
  }, []);

  return (
    // App shell — Phase 8 post-review (2026-05-13). The content-area
    // gradient (`from-neutral-50 via-white to-neutral-50/80`) was redundant
    // with body-level radial atmospherics already painting depth. Dropped
    // in favour of a transparent shell that lets the body's two radial
    // gradients carry through. One less layer in the paint budget.
    <div className="min-h-screen bg-transparent">
      {/* Desktop Sidebar */}
      <Sidebar
        isOpen={sidebarOpen}
        currentPath={currentPage}
        onNavigate={onNavigate}
        onClose={() => setSidebarOpen(false)}
      />

      {/* Main Content Area */}
      <div className={cn(
        'min-h-screen flex flex-col',
        'lg:pl-72', // Sidebar width on desktop
        'pb-20 lg:pb-0' // Bottom nav padding on mobile
      )}>
        {/* Header */}
        <Header
          onMenuClick={() => setSidebarOpen(true)}
          currentPage={currentPage}
        />

        {/* App shell padding + page-enter animation only. Content max-width
            and vertical rhythm are owned by the <Page> primitive (see
            src/components/layout/Page.tsx). A page that does NOT wrap itself
            in <Page> renders edge-to-edge at full viewport width — this is
            a Phase 10 smell and should be migrated when the page is next
            touched. Keeping `animate-page-enter` on <main> means every page
            gets the enter animation even before migrating to <Page>. */}
        <main className="flex-1 p-4 lg:p-8 animate-page-enter">
          {children}
        </main>

        {/* Footer - Desktop only */}
        <footer className="hidden lg:block border-t border-neutral-200/60 bg-white/80 backdrop-blur-sm py-4 px-8">
          <div className="flex items-center justify-between text-xs text-neutral-500 dark:text-neutral-400">
            <span>© {BRAND.copyrightYear} {BRAND.name}. All rights reserved.</span>
            <div className="flex items-center gap-4">
              <span className="flex items-center gap-1.5">
                <span className="w-1.5 h-1.5 bg-success-500 rounded-full animate-pulse" />
                All systems operational
              </span>
              <span>•</span>
              <span>Version 4.3.0</span>
            </div>
          </div>
        </footer>
      </div>

      {/* Mobile Bottom Navigation */}
      <BottomNav
        currentPath={currentPage}
        onNavigate={onNavigate}
        onMoreClick={() => setMoreMenuOpen(true)}
      />

      {/* Mobile More Menu */}
      <MoreMenu
        isOpen={moreMenuOpen}
        onClose={() => setMoreMenuOpen(false)}
        currentPath={currentPage}
        onNavigate={onNavigate}
      />
    </div>
  );
};

export default Layout;
