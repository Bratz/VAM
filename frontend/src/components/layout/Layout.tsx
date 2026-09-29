import React, { useState, useEffect } from 'react';
import { Bell, Search, ChevronDown, LogOut, User, HelpCircle, Settings, Layers, Moon, Sun, PanelLeftClose, PanelLeftOpen } from 'lucide-react';
import { cn } from '../../utils';
import { Avatar } from '../ui';
import { BRAND } from '../../branding';
import { EntityPicker } from '../permissions/EntityPicker';
import { useUser } from '../../context/useUser';
import { useTheme } from '../../design-system/useTheme';
import { useCopilot } from '../../ai/copilot/useCopilot';
import { useRegisteredPageHeaderActions, useRegisteredPageHeader } from '../../context/usePageHeader';
import { HeaderHelpPopover } from './HeaderHelpPopover';
// Phase 7 Design System Unification: IA (navigation structure + page titles +
// mobile bottom-nav) moved out of this file into a dedicated config module
// so it can be audited as a flat data file rather than scattered through
// 1000+ lines of JSX. Layout is now a renderer over this config.
import {
  navSections,
  pageTitles,
  ALL_SECTION_TITLES,
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
  currentPath: string;
  onNavigate: (page: string) => void;
  /** Icon rail: narrows the sidebar to 64px, keeping icons and dropping labels. */
  collapsed: boolean;
  onToggleCollapse: () => void;
}

const Sidebar: React.FC<SidebarProps> = ({ currentPath, onNavigate, collapsed, onToggleCollapse }) => {
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
      return;
    }
    onNavigate(item.href);
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
      <aside className={cn(
        'fixed top-0 left-0 h-full w-60 z-50 flex flex-col',
        collapsed && 'w-16',
        // Premium glass effect — flips to elevated navy panel in dark mode
        'bg-white/95 backdrop-blur-xl border-r border-neutral-200/60',
        'dark:bg-primary-900/95 dark:border-primary-800/60',
        // Premium shadow
        'shadow-2xl dark:shadow-none',
        // Animation
        'transition-[width] duration-300 ease-out'
      )}>
        {/* Logo Header - Premium */}
        <div className={cn(
          'h-20 flex items-center justify-between px-6 border-b border-neutral-200/60 dark:border-primary-800/60',
          collapsed && 'px-0 justify-center'
        )}>
          <div className="flex items-center gap-3">
            {/* Logo tile — Phase 8 post-review (2026-05-13). The previous
                three-stop gradient + colored drop-shadow + dark-only ring
                was the same recipe just retired from the active nav state
                (Phase 6); leaving it here made the logo the loudest thing
                on the sidebar. Flattened to a solid primary-900 tile with
                an always-on gold ring — quieter, and the gold ring ties
                the brand monogram to the same accent we use on focus
                rings, page-title underlines, and the active-nav rule. */}
            <div
              className={cn(
                'w-11 h-11 bg-primary-900 dark:bg-primary-800 rounded-lg flex items-center justify-center ring-1 ring-accent-500/60 dark:ring-accent-400/60',
                collapsed && 'cursor-pointer'
              )}
              onClick={collapsed ? onToggleCollapse : undefined}
              title={collapsed ? 'Expand sidebar' : undefined}
            >
              <Layers className="w-5 h-5 text-white" />
            </div>
            <div className={cn(collapsed && 'hidden')}>
              {/* Brand wordmark in Fraunces — ties the sidebar to the serif accent used on page titles.
                  Pulled from src/branding.ts so a rebrand is a single-file change. */}
              <h1
                className="font-display text-primary-900 dark:text-neutral-50 text-heading-sm tracking-tight"
                style={{ fontWeight: 500, letterSpacing: '-0.015em' }}
                title={BRAND.tagline}
              >
                {BRAND.name}
              </h1>
              <p className="label-cased">
                {BRAND.shortSubtitle}
              </p>
            </div>
          </div>
          <button
            onClick={onToggleCollapse}
            className={cn(
              'hidden p-2 hover:bg-neutral-100 dark:hover:bg-primary-800 rounded-lg transition-colors',
              !collapsed && 'inline-flex'
            )}
            title="Collapse sidebar"
            aria-label="Collapse sidebar"
          >
            <PanelLeftClose className="w-4 h-4 text-neutral-500 dark:text-neutral-400" />
          </button>
        </div>

        {/* Search */}
        <div className={cn('px-4 py-4', collapsed && 'hidden')}>
          <div className="relative">
            <Search className="absolute left-3.5 top-1/2 -translate-y-1/2 w-4 h-4 text-neutral-400" />
            <input
              type="text"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              placeholder="Search menu..."
              className={cn(
                'w-full h-10 pl-10 pr-4 rounded-lg border border-edge',
                'bg-neutral-50/80 dark:bg-primary-950/50 text-body-sm text-primary-900 placeholder:text-neutral-400 dark:text-neutral-50',
                'dark:bg-primary-950/60 dark:border-primary-800 dark:text-neutral-100 dark:placeholder:text-neutral-400',
                'focus:outline-none focus:border-primary-300 focus:bg-white focus:ring-2 focus:ring-primary-500/10',
                'dark:focus:border-accent-400 dark:focus:bg-primary-950 dark:focus:ring-accent-400/20',
                'transition-all duration-200'
              )}
            />
          </div>
        </div>

        {/* Navigation - Scrollable */}
        <nav className={cn('flex-1 overflow-y-auto px-3 pb-4 scrollbar-thin', collapsed && 'px-2 pt-3')}>
          {filteredSections.map((section, sectionIdx) => (
            <div key={sectionIdx} className="mb-2">
              {collapsed && sectionIdx > 0 && (
                <div className="h-px mx-2 mb-2 bg-neutral-200/70 block dark:bg-primary-800/70" aria-hidden />
              )}
              {section.title && (
                <button
                  onClick={() => toggleSection(section.title!)}
                  className={cn(
                    'flex items-center justify-between w-full px-3 py-1.5 mt-2',
                    // neutral-400/dark:neutral-500 measured at 2.20:1 (light)
                    // and 1.45:1 (dark) against the sidebar background — both
                    // fail WCAG's 4.5:1 minimum for 12px text by a wide
                    // margin. neutral-500/dark:neutral-300 measure 6.24:1 and
                    // 5.18:1 respectively (computed via the W3C
                    // relative-luminance formula against the actual
                    // rendered backgrounds).
                    'text-caption font-semibold text-neutral-500 dark:text-neutral-300',
                    'hover:text-neutral-600 dark:hover:text-neutral-50 transition-colors rounded-lg hover:bg-neutral-50 dark:hover:bg-primary-800/50',
                    collapsed && 'hidden'
                  )}
                >
                  <span>{section.title}</span>
                  <ChevronDown className={cn(
                    'w-4 h-4 transition-transform duration-200',
                    collapsedSections.has(section.title) && '-rotate-90'
                  )} />
                </button>
              )}

              <div className={cn(
                'space-y-1 overflow-hidden transition-all duration-200',
                // When searching, override the collapse so matching items
                // surface regardless of which sections the user has closed.
                section.title && collapsedSections.has(section.title) && !searchQuery && 'h-0 opacity-0',
                // Icon rail shows every item's icon; section folding only applies to the full sidebar.
                collapsed && 'h-auto opacity-100'
              )}>
                {section.items.map((item) => {
                  const isActive = currentPath === item.href;
                  const isComingSoon = !!item.isComingSoon;
                  return (
                    <button
                      key={item.href}
                      onClick={() => handleItemClick(item)}
                      disabled={isComingSoon}
                      title={isComingSoon ? 'Coming soon' : collapsed ? item.label : undefined}
                      className={cn(
                        'relative',
                        // Phase 6 Design System Unification: active state calmed
                        // down from "gradient + colored shadow + dark-only left
                        // rule" to "solid navy fill + 2px gold left rule" in
                        // both modes. The transparent left border on inactive
                        // items prevents layout shift when an item becomes
                        // active. Rounded-lg (12px) replaces rounded-xl per the
                        // canonical radius scale collapse.
                        'w-full flex items-center gap-3 pl-[10px] pr-3 py-1.5 rounded-lg text-body-sm font-medium',
                        'border-l-2 transition-colors duration-200',
                        isComingSoon
                          ? 'border-transparent text-neutral-400 cursor-not-allowed opacity-60'
                          : isActive
                          ? 'border-accent-500 dark:border-accent-400 bg-primary-800 dark:bg-primary-700/80 text-white'
                          : 'border-transparent text-neutral-600 hover:bg-neutral-100 hover:text-primary-900 dark:text-neutral-300 dark:hover:bg-primary-800/60 dark:hover:text-neutral-50',
                        collapsed && 'justify-center px-0 gap-0'
                      )}
                    >
                      <span className={cn(
                        'transition-colors',
                        isComingSoon
                          ? 'text-neutral-300 dark:text-neutral-400'
                          : isActive
                          ? 'text-white'
                          : 'text-neutral-400 group-hover:text-primary-600'
                      )}>
                        {item.icon}
                      </span>
                      <span className={cn('flex-1 text-left truncate', collapsed && 'hidden')}>{item.label}</span>
                      {item.isNew && !isComingSoon && (
                        <span className={cn(
                          collapsed && 'hidden',
                          'text-caption font-bold px-1.5 py-0 leading-4 rounded-full uppercase tracking-wide',
                          isActive ? 'bg-white/25 text-white' : 'bg-accent-100 text-accent-700 dark:bg-accent-500/20 dark:text-accent-300'
                        )}>
                          New
                        </span>
                      )}
                      {isComingSoon && (
                        <span className={cn(collapsed && 'hidden', 'text-caption font-bold px-1.5 py-0 leading-4 rounded-full uppercase tracking-wide bg-neutral-100 text-neutral-500 dark:bg-primary-800/60 dark:text-neutral-400')}>
                          Soon
                        </span>
                      )}
                      {item.badge && !isComingSoon && (
                        <span className={cn(
                          'text-caption font-bold min-w-[20px] h-5 flex items-center justify-center rounded-full',
                          getBadgeStyle(isActive, item.badgeColor),
                          collapsed && 'hidden'
                        )}>
                          {item.badge}
                        </span>
                      )}
                      {collapsed && item.badge && !isComingSoon && (
                        <span className="absolute top-1 right-2 w-2 h-2 rounded-full bg-accent-500 block" aria-hidden />
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
        <div className={cn(
          'px-4 py-3 border-t border-neutral-200/60 dark:border-primary-800/60 flex items-center justify-between gap-2',
          collapsed && 'flex-col px-2 justify-center'
        )}>
          <button
            type="button"
            title="Documentation & support"
            className={cn(
              'flex items-center gap-2 px-2 py-1.5 rounded-lg text-caption font-medium',
              'text-neutral-500 dark:text-neutral-400 hover:text-primary-700 dark:hover:text-primary-200 hover:bg-neutral-100 dark:hover:bg-primary-800/50',
              'dark:text-neutral-400 dark:hover:text-neutral-50 dark:hover:bg-primary-800/60',
              'transition-colors'
            )}
          >
            <HelpCircle className="w-4 h-4" />
            <span className={cn(collapsed && 'hidden')}>Docs</span>
          </button>
          {collapsed && (
            <button
              type="button"
              onClick={onToggleCollapse}
              title="Expand sidebar"
              aria-label="Expand sidebar"
              className="p-2 rounded-lg text-neutral-500 transition-colors inline-flex hover:bg-neutral-100 dark:text-neutral-400 dark:hover:bg-primary-800/60"
            >
              <PanelLeftOpen className="w-4 h-4" />
            </button>
          )}
          {/* Same neutral-400/dark:neutral-500 contrast failure as the
              section headers above (2.20:1 light, 1.45:1 dark) — same fix. */}
          <span className={cn('text-caption text-neutral-500 dark:text-neutral-300 uppercase tracking-wider', collapsed && 'hidden')}>
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
  currentPage: string;
}

const Header: React.FC<HeaderProps> = ({ currentPage }) => {
  const [showNotifications, setShowNotifications] = useState(false);
  const [showUserMenu, setShowUserMenu] = useState(false);
  const [showSearch, setShowSearch] = useState(false);
  const { isTreasury } = useUser();
  const { resolvedMode, toggleMode } = useTheme();
  const pageActions = useRegisteredPageHeaderActions();
  const { title: registeredTitle, description: registeredDescription } = useRegisteredPageHeader();

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
      {/* Fixed h-14 (56px) — the in-page title block that used to push this
          taller is gone (PageHeader now registers into this header instead
          of rendering its own <h1>), so a single-line title always fits. */}
      <div className="flex items-center h-14 px-8">
        {/* Left side. No justify-between on the row above: that only pushes
            this div and "Right side" apart by their own min-content widths,
            leaving the gap between them as dead space the title can't reach.
            flex-1 here instead makes this div itself claim that gap, so the
            flex-1 title below (which sits inside it) has real room to grow
            into instead of truncating while the header is visibly half-empty. */}
        <div className="flex items-center gap-4 min-w-0 flex-1">
          {/* Page title — Desktop. F2: Fraunces display + gold underline
              accent. Sourced from whatever the current page registered via
              <PageHeader>/usePageHeaderTitle; falls back to the generic
              per-route title for pages not yet migrated. whitespace-nowrap
              so long titles don't wrap the header to two rows. */}
          {/* flex-1 min-w-[96px] not min-w-0 — with several page-registered
              header actions + the search bar + EntityPicker all
              shrink-resistant, the title was the only flexible element left
              and could collapse to a true 0px width (confirmed live at
              1440px with AccountsPage's 3 header actions), and even where it
              didn't fully collapse it truncated long before running out of
              real header space because it never grew past its own
              min-content width (confirmed live: titles truncated at
              1280-1440px with the header visibly half-empty). flex-1 lets it
              claim the parent's slack; the min-w-[96px] floor still keeps
              `truncate` working for long titles without the title ever
              fully disappearing when space is genuinely tight. */}
          <div className="items-center gap-1.5 flex-1 min-w-[96px] flex">
            {/* No flex-1 here (only on the wrapper div) — the wrapper
                growing gives the title room to render at full width with
                the help button sitting right after the visible text; if h1
                itself also grew, the button would get pushed to the far
                right edge of the wrapper's now-large box, stranded away
                from the text it annotates. min-w-0 lets this still shrink
                and truncate on the rare title that's longer than even the
                grown wrapper allows. */}
            <h1 className="page-title-display text-heading-sm leading-tight text-primary-900 dark:text-neutral-50 whitespace-nowrap truncate min-w-0">
              {registeredTitle || pageTitles[currentPage] || BRAND.name}
            </h1>
            {registeredDescription && <HeaderHelpPopover description={registeredDescription} />}
          </div>

          {/* Search - Desktop. min-w-0 + w-full (not a fixed w-80) lets this
              chain shrink instead of overflowing when page-registered header
              actions (e.g. Dashboard's "Refresh all"/"New payment") claim
              more of the row — but shrinking alone still isn't enough: even
              at its 140px floor, the row's total content (title + search +
              actions + entity picker + icons) can outweigh the available
              width below ~1350px, which measured as a real, reproducible
              overlap at 1280px (a common laptop viewport), not just a rare
              edge case. Rather than let it collide again at some other
              width, hide search outright once actions are registered and
              raise its own breakpoint to 2xl — a hidden search is a much
              smaller loss (the sidebar's own "Search menu..." still covers
              it) than an intermittently overlapping header. Pages with no
              header actions keep the original lg breakpoint, unaffected. */}
          <div className={cn('hidden items-center ml-4 min-w-0 flex-shrink', pageActions ? '2xl:flex' : 'flex')}>
            <div className="relative min-w-0 w-full">
              <Search className="absolute left-3.5 top-1/2 -translate-y-1/2 w-4 h-4 text-neutral-400" />
              <input
                type="text"
                placeholder="Search accounts, transactions..."
                className={cn(
                  'w-full min-w-[140px] max-w-[320px] h-10 pl-10 pr-12 rounded-lg border border-edge',
                  'bg-neutral-50/80 dark:bg-primary-950/50 text-body-sm placeholder:text-neutral-400',
                  'focus:outline-none focus:border-primary-300 focus:bg-white focus:ring-2 focus:ring-primary-500/10',
                  'transition-all duration-200'
                )}
              />
              {/* ⌘K shortcut chip — Phase 8 Design System Unification:
                  switched to mono (Geist Mono) so the keyboard glyph reads as
                  "code/keystroke" alongside our numerics-and-code mono rule.
                  Generic sans previously made it look like a label, not a key. */}
              <kbd className="absolute right-3 top-1/2 -translate-y-1/2 label-cased bg-surface-card px-1.5 py-0.5 rounded-md border border-edge font-mono font-medium tracking-tight">
                ⌘K
              </kbd>
            </div>
          </div>
        </div>

        {/* Right side */}
        <div className="flex items-center gap-2 shrink-0">
          {/* Page-registered toolbar actions (Reload, Export, etc.) come first
              in the right cluster so they sit closest to the page title. Each
              page registers via {@code usePageHeaderActions}. shrink-0 +
              whitespace-nowrap so buttons like "Refresh all (3 stale)" stop
              wrapping/clipping in the now-shorter 56px header. */}
          {pageActions && (
            <div className="items-center gap-2 mr-2 shrink-0 whitespace-nowrap flex">
              {pageActions}
            </div>
          )}

          {/* Entity Picker — Desktop.
              Slimmed down per UI audit: corporate dropdown hidden (most pages
              carry their own corporate selector), role pill hidden (role is
              already in the user chip below). Just the entity dropdown. */}
          <div className="block">
            <EntityPicker compact showCorporate={false} showRoleBadge={false} />
          </div>

          {/* Mobile Search Button */}
          <button
            onClick={() => setShowSearch(!showSearch)}
            className="p-2.5 rounded-lg transition-colors hidden hover:bg-neutral-100 dark:hover:bg-primary-800/50"
          >
            <Search className="w-5 h-5 text-neutral-600 dark:text-neutral-300" />
          </button>

          {/* F5: Theme toggle */}
          <button
            onClick={toggleMode}
            aria-label={resolvedMode === 'dark' ? 'Switch to light mode' : 'Switch to dark mode'}
            title={resolvedMode === 'dark' ? 'Switch to light mode' : 'Switch to dark mode'}
            className="relative p-2.5 hover:bg-neutral-100 dark:hover:bg-primary-800 rounded-lg transition-colors"
          >
            {resolvedMode === 'dark'
              ? <Sun className="w-5 h-5 text-accent-400" />
              : <Moon className="w-5 h-5 text-neutral-600 dark:text-neutral-300" />}
          </button>

          {/* Notifications */}
          <div className="relative">
            <button
              onClick={() => setShowNotifications(!showNotifications)}
              className="relative p-2.5 hover:bg-neutral-100 dark:hover:bg-primary-800/50 rounded-lg transition-colors"
            >
              <Bell className="w-5 h-5 text-neutral-600 dark:text-neutral-300" />
              <span className="absolute top-2 right-2 w-2 h-2 bg-error-500 rounded-full ring-2 ring-white" />
            </button>

            {showNotifications && (
              <>
                <div className="fixed inset-0 z-10" onClick={() => setShowNotifications(false)} />
                <div className={cn(
                  'absolute right-0 top-full mt-2 w-96 max-w-[calc(100vw-2rem)]',
                  'bg-surface-card rounded-lg shadow-2xl border border-neutral-200/60 z-20 overflow-hidden',
                  'animate-scale-in origin-top-right'
                )}>
                  <div className="p-4 border-b border-edge-subtle flex items-center justify-between">
                    <h3 className="font-semibold text-primary-900 dark:text-neutral-50">Notifications</h3>
                    <button className="text-body-sm text-primary-600 hover:text-primary-700 dark:hover:text-primary-200 font-medium dark:text-primary-200">
                      Mark all read
                    </button>
                  </div>
                  <div className="max-h-80 overflow-y-auto">
                    {notifications.map((n) => (
                      <div
                        key={n.id}
                        className="p-4 hover:bg-neutral-50 dark:hover:bg-primary-800/50 cursor-pointer border-b border-edge-subtle last:border-0 transition-colors"
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
                            <p className="body-strong">{n.title}</p>
                            <p className="body-sm mt-0.5 line-clamp-2">{n.message}</p>
                            <p className="caption mt-1.5">{n.time}</p>
                          </div>
                        </div>
                      </div>
                    ))}
                  </div>
                  <div className="p-3 border-t border-edge-subtle bg-neutral-50/50 dark:bg-primary-950/50">
                    <button className="w-full text-body-sm text-primary-600 hover:text-primary-700 dark:hover:text-primary-200 font-medium py-1.5 dark:text-primary-200">
                      View All Notifications
                    </button>
                  </div>
                </div>
              </>
            )}
          </div>

          {/* Divider */}
          <div className="w-px h-8 bg-neutral-200 mx-2 block dark:bg-primary-800" />

          {/* User Menu */}
          <div className="relative">
            <button
              onClick={() => setShowUserMenu(!showUserMenu)}
              className="flex items-center gap-3 p-2 hover:bg-neutral-100 dark:hover:bg-primary-800/50 rounded-lg transition-colors"
            >
              <Avatar name="John Doe" size="sm" />
              <div className="text-left block">
                <p className="body-strong">John Doe</p>
                {/* Role only — the entity code is now shown in the EntityPicker
                    above, so the (MNC-HOLDING) suffix here was redundant. */}
                <p className={cn(
                  'text-caption font-medium',
                  // dark:text-neutral-400 measured 4.11:1 against this
                  // header's dark background, just under the 4.5:1 minimum
                  // for 12px text — bumped to neutral-300 (5.18:1) to match
                  // the sidebar's fix for the same underlying issue.
                  isTreasury ? 'text-primary-600 dark:text-primary-200' : 'text-neutral-500 dark:text-neutral-300'
                )}>
                  {isTreasury ? 'Treasury' : 'Subsidiary'}
                </p>
              </div>
              <ChevronDown className="w-4 h-4 text-neutral-400 block" />
            </button>

            {showUserMenu && (
              <>
                <div className="fixed inset-0 z-10" onClick={() => setShowUserMenu(false)} />
                <div className={cn(
                  'absolute right-0 top-full mt-2 w-64',
                  'bg-surface-card rounded-lg shadow-2xl border border-neutral-200/60 z-20 overflow-hidden',
                  'animate-scale-in origin-top-right'
                )}>
                  <div className="px-4 py-4 border-b border-edge-subtle bg-neutral-50/50 dark:bg-primary-950/50">
                    <div className="flex items-center gap-3">
                      <Avatar name="John Doe" size="md" />
                      <div>
                        <p className="body-strong font-semibold">John Doe</p>
                        <p className="caption">john.doe@company.com</p>
                      </div>
                    </div>
                  </div>
                  <div className="py-2">
                    <button className="w-full flex items-center gap-3 px-4 py-2.5 text-body-sm text-neutral-700 hover:bg-neutral-50 dark:hover:bg-primary-800/50 transition-colors dark:text-neutral-200">
                      <User className="w-4 h-4 text-neutral-400" />
                      Profile Settings
                    </button>
                    <button className="w-full flex items-center gap-3 px-4 py-2.5 text-body-sm text-neutral-700 hover:bg-neutral-50 dark:hover:bg-primary-800/50 transition-colors dark:text-neutral-200">
                      <Settings className="w-4 h-4 text-neutral-400" />
                      Preferences
                    </button>
                  </div>
                  <div className="border-t border-edge-subtle py-2">
                    <button className="w-full flex items-center gap-3 px-4 py-2.5 text-body-sm text-error-600 hover:bg-error-50 transition-colors dark:text-error-300">
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

      {/* Page actions on phones: the right cluster has no room for them, and hiding them
          left pages like Programs with no way to create, export or refresh. */}
      {pageActions && (
        <div className="flex-wrap items-center justify-end gap-2 px-4 pb-2 hidden">
          {pageActions}
        </div>
      )}

      {/* Mobile Search Overlay */}
      {showSearch && (
        <div className="absolute inset-x-0 top-full bg-surface-card border-b border-edge p-4 animate-slide-down hidden">
          <div className="relative">
            <Search className="absolute left-3.5 top-1/2 -translate-y-1/2 w-4 h-4 text-neutral-400" />
            <input
              type="text"
              placeholder="Search..."
              autoFocus
              className={cn(
                'w-full h-11 pl-10 pr-4 rounded-lg border border-edge',
                'bg-surface-page text-body placeholder:text-neutral-400',
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
// Main Layout Component
// ============================================================================

interface LayoutProps {
  children: React.ReactNode;
  currentPage: string;
  onNavigate: (page: string, params?: Record<string, string>) => void;
}

export const Layout: React.FC<LayoutProps> = ({ children, currentPage, onNavigate }) => {
  // Icon rail. With no saved choice it starts collapsed on screens under 1440 CSS px
  // (a 1920px display at 150% scaling is 1280), where a 240px sidebar leaves too little room.
  const [sidebarCollapsed, setSidebarCollapsed] = useState(() => {
    try {
      const saved = localStorage.getItem('sidebar:collapsed');
      if (saved === '1' || saved === '0') return saved === '1';
    } catch { /* storage unavailable: fall through to the width default */ }
    return window.innerWidth < 1440;
  });
  const toggleSidebarCollapsed = () => {
    setSidebarCollapsed((prev) => {
      const next = !prev;
      try { localStorage.setItem('sidebar:collapsed', next ? '1' : '0'); } catch { /* ignore */ }
      return next;
    });
  };
  // Lets pages that reserve side columns (e.g. the dashboard's Action queue) know how much room there is.
  useEffect(() => {
    document.documentElement.dataset.sidebar = sidebarCollapsed ? 'collapsed' : 'expanded';
  }, [sidebarCollapsed]);

  return (
    // App shell — Phase 8 post-review (2026-05-13). The content-area
    // gradient (`from-neutral-50 via-white to-neutral-50/80`) was redundant
    // with body-level radial atmospherics already painting depth. Dropped
    // in favour of a transparent shell that lets the body's two radial
    // gradients carry through. One less layer in the paint budget.
    <div className="min-h-screen bg-transparent">
      <Sidebar
        currentPath={currentPage}
        onNavigate={onNavigate}
        collapsed={sidebarCollapsed}
        onToggleCollapse={toggleSidebarCollapsed}
      />

      {/* Main Content Area */}
      <div className={cn(
        'min-h-screen flex flex-col',
        sidebarCollapsed ? 'pl-16' : 'pl-60', // clears the fixed sidebar
        'transition-[padding] duration-300'
      )}>
        <Header currentPage={currentPage} />

        {/* App shell padding + page-enter animation only. Content max-width
            and vertical rhythm are owned by the <Page> primitive (see
            src/components/layout/Page.tsx). A page that does NOT wrap itself
            in <Page> renders edge-to-edge at full viewport width — this is
            a Phase 10 smell and should be migrated when the page is next
            touched. Keeping `animate-page-enter` on <main> means every page
            gets the enter animation even before migrating to <Page>. */}
        <main className="flex-1 p-4 animate-page-enter px-6 py-5">
          {children}
        </main>

        {/* Footer - Desktop only */}
        <footer className="border-t border-neutral-200/60 bg-white/80 backdrop-blur-sm py-4 px-8 block">
          <div className="flex items-center justify-between caption">
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

    </div>
  );
};

export default Layout;
