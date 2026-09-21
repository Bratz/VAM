import React, { useState, useEffect, useCallback, useRef } from 'react';
import { Search, Download, RefreshCw, Plus, Eye, MoreHorizontal, CheckCircle, Copy, Archive, Loader2, Layers, PauseCircle, PlayCircle, TrendingUp, Pencil, Hash, Gauge, Receipt } from 'lucide-react';
import { Card, Badge, Button, Input, Select, StatusIconBadge, DataTable } from '../components/ui';
import { HeroMetricCard } from '../components/ui/HeroMetricCard';
import { Modal } from '../components/ui/enhanced';
import toast from 'react-hot-toast';
import { useMarket } from '../context/MarketContext';
import { useReportingRates } from '../components/multiBank/useReportingRates';
import { TileAmount } from '../components/TileAmount';
import { formatCurrency, cn } from '../utils';
import { usePageHeaderActions } from '../context/PageHeaderContext';
import { Page } from '../components/layout/Page';
import { PageHeader } from '../components/layout/PageHeader';
import { ScopeSelector } from '../components/layout/ScopeSelector';

import { CORPORATES_API, extractArray, Corporate, ProgramStatus, Program, ProgramStats, programApi, LevelConfigPayload, hierarchyLevelApi, statusConfig } from './programs/shared';
import { ProgramDetailModal } from './programs/ProgramDetailModal';
import { ProgramFormModal, ProgramConfigStep } from './programs/ProgramFormModal';

// MAIN PAGE
// ============================================================================

const ProgramsPage: React.FC = () => {
  const [searchQuery, setSearchQuery] = useState('');
  const [statusFilter, setStatusFilter] = useState<ProgramStatus | 'ALL'>('ALL');
  const [selectedProgram, setSelectedProgram] = useState<Program | null>(null);
  const [editProgram, setEditProgram] = useState<Program | null>(null);
  // Set with editProgram to open the form on one step (VIBAN / limits / fees).
  const [configStep, setConfigStep] = useState<ProgramConfigStep | undefined>(undefined);
  const [showCreateModal, setShowCreateModal] = useState(false);
  const [programs, setPrograms] = useState<Program[]>([]);
  const [stats, setStats] = useState<ProgramStats | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setError] = useState<string | null>(null);
  // Action menu state
  const [actionMenuId, setActionMenuId] = useState<string | null>(null);
  // The table scrolls horizontally, which clips an absolutely-placed menu, so the
  // menu is fixed to the viewport at the button's position (flipped up near the bottom).
  const [menuStyle, setMenuStyle] = useState<React.CSSProperties>({});
  const menuTriggerRef = useRef<HTMLElement | null>(null);
  // Closing the menu hands focus back to its button, before any dialog the item opens takes it
  // (the dialog then returns focus there too, not to a menu item that no longer exists).
  const closeMenu = () => {
    setActionMenuId(null);
    menuTriggerRef.current?.focus();
  };
  // The table renders each row twice (desktop and a hidden mobile layout), so the menu is found
  // by what's on screen, not by a ref (which would land on the hidden copy).
  const menuItems = (menu?: Element | null) => Array.from(
    (menu ?? Array.from(document.querySelectorAll('[role="menu"]')).find(m => m.getBoundingClientRect().width > 0))
      ?.querySelectorAll<HTMLElement>('[role="menuitem"]') ?? []);
  const onMenuKeyDown = (e: React.KeyboardEvent) => {
    const items = menuItems(e.currentTarget);
    const at = items.indexOf(document.activeElement as HTMLElement);
    const go = (i: number) => { e.preventDefault(); items[(i + items.length) % items.length]?.focus(); };
    if (e.key === 'ArrowDown') go(at + 1);
    else if (e.key === 'ArrowUp') go(at - 1);
    else if (e.key === 'Home') go(0);
    else if (e.key === 'End') go(items.length - 1);
    else if (e.key === 'Tab') { e.preventDefault(); closeMenu(); }
  };
  useEffect(() => {
    if (actionMenuId) menuItems()[0]?.focus();
  }, [actionMenuId]);
  const openMenu = (id: string, e: React.MouseEvent<HTMLElement>) => {
    if (actionMenuId === id) { closeMenu(); return; }
    menuTriggerRef.current = e.currentTarget;
    const b = e.currentTarget.getBoundingClientRect();
    const up = window.innerHeight - b.bottom < 260;
    setMenuStyle({ right: window.innerWidth - b.right, ...(up ? { bottom: window.innerHeight - b.top + 4 } : { top: b.bottom + 4 }) });
    setActionMenuId(id);
  };
  useEffect(() => {
    if (!actionMenuId) return;
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') closeMenu(); };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [actionMenuId]);
  const [cloneSource, setCloneSource] = useState<Program | null>(null);
  const [cloneCode, setCloneCode] = useState('');
  const [cloneName, setCloneName] = useState('');
  const [cloning, setCloning] = useState(false);
  const startClone = (p: Program) => {
    setCloneSource(p);
    setCloneCode(p.programCode + '-COPY');
    setCloneName(p.programName + ' (Copy)');
  };
  const confirmClone = async () => {
    if (!cloneSource) return;
    setCloning(true);
    const res = await programApi.clone(cloneSource.id, cloneCode.trim(), cloneName.trim());
    setCloning(false);
    if (res.success) {
      setPrograms(prev => [res.data, ...prev]);
      toast.success(`Cloned as ${res.data.programName} (pending approval)`);
      setCloneSource(null);
    } else {
      toast.error('Failed to clone program: ' + (res.message || 'Unknown error'));
    }
  };
  const [deleteTarget, setDeleteTarget] = useState<Program | null>(null);
  const [deleting, setDeleting] = useState(false);
  const confirmClose = async () => {
    if (!deleteTarget) return;
    setDeleting(true);
    const res = await programApi.close(deleteTarget.id);
    setDeleting(false);
    if (res.success) {
      setPrograms(prev => prev.map(p => p.id === deleteTarget.id ? res.data : p));
      toast.success(`Closed ${deleteTarget.programName}`);
      setDeleteTarget(null);
    } else {
      toast.error('Could not close the program: ' + (res.message || 'Unknown error'));
    }
  };

  // Corporate context state
  const [corporates, setCorporates] = useState<Corporate[]>([]);
  const [selectedCorporateId, setSelectedCorporateId] = useState<string | undefined>();
  const [corporatesLoading, setCorporatesLoading] = useState(true);

  // Load corporates on mount
  useEffect(() => {
    setCorporatesLoading(true);
    fetch(CORPORATES_API)
      .then(res => res.json())
      .then(result => {
        const corps = extractArray<Corporate>(result as any);
        setCorporates(corps);
      })
      .catch(err => {
        console.error('Failed to load corporates:', err);
        // Continue without corporates - page should still work
        setCorporates([]);
      })
      .finally(() => setCorporatesLoading(false));
  }, []);

// Full-page spinner only before the first load; later loads keep the page (and the search box) mounted.
  const hasLoaded = useRef(false);

  const loadData = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const params: Record<string, string> = {};
// VIBAN is a feature any program type can enable, so that tile filters on the flag client-side.
      if (statusFilter !== 'ALL') params.status = statusFilter;
      if (selectedCorporateId) params.corporateId = selectedCorporateId;
      
      // The API pages (20 by default). The table filters and totals client-side, so it needs
      // every page; one page only ever showed the first 20 programs.
      const PAGE_SIZE = 100;
      const res = await programApi.getAll({ ...params, page: '0', pageSize: String(PAGE_SIZE) });

      if (res.success && res.data) {
        // Safe extraction - handles multiple response formats
        const responseData = res.data as any;
        let programList: Program[] = responseData.programs || extractArray<Program>(res as any) || [];
        const statsData = responseData.stats || null;
        const total: number = responseData.totalCount ?? programList.length;
        for (let page = 1; programList.length < total; page++) {
          const more = await programApi.getAll({ ...params, page: String(page), pageSize: String(PAGE_SIZE) });
          const items: Program[] = (more.data as any)?.programs ?? [];
          if (!more.success) throw new Error(more.message || 'Could not load every page of programs');
          if (items.length === 0) break;
          programList = programList.concat(items);
        }

        setPrograms(Array.isArray(programList) ? programList : []);
        setStats(statsData);
      } else {
        // A failed load is an error, not an empty system: the page used to say "No programs
        // found -- Programs will appear here once created" with zero totals.
        setPrograms([]);
        setStats(null);
        setError(res.message || 'The programs could not be loaded');
      }
    } catch (err) {
      console.error('Failed to load programs:', err);
      setPrograms([]);
      setStats(null);
      setError(err instanceof Error ? err.message : 'The programs could not be loaded');
    } finally {
      hasLoaded.current = true;
      setLoading(false);
    }
  }, [statusFilter, selectedCorporateId]);

  useEffect(() => { loadData(); }, [loadData]);

  const handleSaveProgram = async (data: Partial<Program> & { hierarchyLevelConfigs?: LevelConfigPayload[] }) => {
    // Extract level configs before saving (they're saved separately)
    const { hierarchyLevelConfigs, ...programData } = data;

    let savedProgram: Program | null = null;

    if (editProgram) {
      const res = await programApi.update(editProgram.id, programData);
      if (res.success) {
        savedProgram = res.data;
        setPrograms(prev => prev.map(p => p.id === editProgram.id ? res.data : p));
        setEditProgram(null);
      } else {
        console.error('Update failed:', res);
        toast.error('Failed to update program: ' + (res.message || 'Unknown error'));
        throw new Error(res.message || 'Update failed');
      }
    } else {
      const res = await programApi.create(programData);
      if (res.success) {
        savedProgram = res.data;
        setPrograms(prev => [res.data, ...prev]);
        setShowCreateModal(false);
      } else {
        console.error('Create failed:', res);
        toast.error('Failed to create program: ' + (res.message || 'Unknown error'));
        throw new Error(res.message || 'Create failed');
      }
    }

    // Save hierarchy level configs if the user configured them in the wizard
    if (savedProgram && hierarchyLevelConfigs && hierarchyLevelConfigs.length > 0) {
      try {
        const levelRes = await hierarchyLevelApi.saveLevelConfigs(savedProgram.id, hierarchyLevelConfigs);
        if (levelRes.success) {
          console.log('Hierarchy level configs saved successfully');
        } else {
          console.warn('Failed to save hierarchy level configs:', levelRes.message);
          // Don't throw - program was created successfully, level config is secondary
        }
      } catch (err) {
        console.error('Error saving hierarchy level configs:', err);
        // Don't throw - program was created successfully
      }
    }
    return savedProgram;
  };

  const handleExport = () => {
    const cell = (v: unknown) => `"${String(v ?? '').replace(/"/g, '""')}"`;
    const csv = [
      ['Code', 'Name', 'Corporate', 'Currency', 'Virtual accounts', 'Active virtual accounts', 'Balance', 'Status'],
      ...sortedPrograms.map(p => [p.programCode, p.programName, p.corporateName, p.currencyCode, p.virtualAccountCount ?? 0,
        p.activeVirtualAccountCount ?? 0, p.totalBalance ?? 0, statusConfig[p.status]?.label ?? p.status]),
    ].map(row => row.map(cell).join(',')).join('\n');
    const link = document.createElement('a');
    link.href = URL.createObjectURL(new Blob([csv], { type: 'text/csv' }));
    link.download = `programs-${new Date().toISOString().split('T')[0]}.csv`;
    link.click();
    URL.revokeObjectURL(link.href);
  };

  const handleStatusChange = async (programId: string, status: string) => {
    const res = await programApi.updateStatus(programId, status);
    if (res.success) {
      setPrograms(prev => prev.map(p => p.id === programId ? res.data : p));
      if (selectedProgram?.id === programId) setSelectedProgram(res.data);
      toast.success(`${res.data.programName} is now ${statusConfig[res.data.status]?.label.toLowerCase() ?? res.data.status}`);
    } else {
      toast.error('Could not change the status: ' + (res.message || 'Unknown error'));
    }
  };

  // Suspending stops a live program, so it asks first; the other changes don't.
  const [suspendTarget, setSuspendTarget] = useState<Program | null>(null);
  const requestStatusChange = (programId: string, status: string) => {
    if (status === 'SUSPENDED') {
      setSuspendTarget(programs.find(p => p.id === programId) ?? selectedProgram);
      return;
    }
    handleStatusChange(programId, status);
  };

  // Calculate display stats with safe defaults
  const defaultStats: ProgramStats = {
    totalPrograms: 0, activePrograms: 0, inactivePrograms: 0, pendingPrograms: 0,
    totalVirtualAccounts: 0, totalBalance: 0,
  };

  const displayStats: ProgramStats = stats || {
    ...defaultStats,
    totalPrograms: programs.length,
    activePrograms: programs.filter(p => p.status === 'ACTIVE').length,
    inactivePrograms: programs.filter(p => p.status === 'INACTIVE').length,
    pendingPrograms: programs.filter(p => p.status === 'PENDING_APPROVAL').length,
    totalVirtualAccounts: programs.reduce((s, p) => s + (p.virtualAccountCount || 0), 0),
    totalBalance: programs.reduce((s, p) => s + (p.totalBalance || 0), 0),
  };

  // Programs hold balances in different currencies, so the headline converts each program's
  // balance to the market's reporting currency instead of adding raw amounts together.
  const { profile } = useMarket();
  const reportingCurrency = profile.defaultCurrency;
  const currencyCodes = React.useMemo(
    () => [...new Set(programs.map(p => p.currencyCode).filter(Boolean))].sort(),
    [programs],
  );
  const { rates, excluded: excludedCurrencies, loading: ratesLoading } = useReportingRates(currencyCodes, reportingCurrency);
  const consolidatedBalance = programs.reduce(
    (sum, p) => sum + (p.totalBalance || 0) * (rates.get(p.currencyCode) ?? (p.currencyCode === reportingCurrency ? 1 : 0)),
    0,
  );

  // Memoised: the header toolbar depends on it, and a new array every render loops the header update.
  const filteredPrograms = React.useMemo(() => programs.filter(p => {
    if (!p) return false;
    // All pages are loaded, so search is local: name, code or corporate.
    const q = searchQuery.trim().toLowerCase();
    const matchesSearch = !q || [p.programName, p.programCode, p.corporateName].some(v => v?.toLowerCase().includes(q));
    return matchesSearch && (statusFilter === 'ALL' || p.status === statusFilter);
  }), [programs, searchQuery, statusFilter]);

  // The table only reports header clicks; the page owns the order.
  const [sort, setSort] = useState<{ key: string; direction: 'asc' | 'desc' } | null>(null);
  const sortedPrograms = React.useMemo(() => {
    if (!sort) return filteredPrograms;
    const factor = sort.direction === 'asc' ? 1 : -1;
    return [...filteredPrograms].sort((a, b) => {
      const x = (a as any)[sort.key], y = (b as any)[sort.key];
      if (typeof x === 'number' || typeof y === 'number') return ((x ?? 0) - (y ?? 0)) * factor;
      return String(x ?? '').localeCompare(String(y ?? '')) * factor;
    });
  }, [filteredPrograms, sort]);

  // Toolbar actions in the Aperture Layout header — same pattern as the
  // rest of Aperture's conformed pages (removes the floating in-page row).
  usePageHeaderActions(
    () => (
      <>
        <Button variant="outline" leftIcon={<Download className="w-4 h-4" />} onClick={handleExport} disabled={sortedPrograms.length === 0}>
          <span className="hidden sm:inline">Export</span>
        </Button>
        <Button variant="outline" leftIcon={<RefreshCw className={cn('w-4 h-4', loading && 'animate-spin')} />} onClick={loadData} disabled={loading}>
          Refresh
        </Button>
        <Button leftIcon={<Plus className="w-4 h-4" />} onClick={() => setShowCreateModal(true)}>
          New Program
        </Button>
      </>
    ),
    [loadData, loading, sortedPrograms]
  );

  // Show loading only on initial load, not on filter changes
  if (!hasLoaded.current) {
    return (
      <div className="flex items-center justify-center h-96">
        <Loader2 className="w-8 h-8 animate-spin text-primary-600 dark:text-primary-200" />
      </div>
    );
  }

  return (
    <Page>
      {/* In-body identity (Phase 10) — aligns with the rest of Aperture's
          conformed pages. Export / New Program CTAs live in the Aperture
          Layout header via usePageHeaderActions above. */}
      <PageHeader
        title="Programs"
        description="Each program belongs to one corporate, runs in one currency on its bank accounts, and has its own account hierarchy, VIBANs and wallet settings."
      />

      {/* Corporate Context Selector — uses the shared ScopeSelector
          primitive in `corporate-only` mode. Replaces the inline
          `CorporateSelector` component (still defined further up the file
          for any non-page-scope callers). */}
      <ScopeSelector
        mode="corporate-only"
        corporates={corporates}
        selectedCorporateId={selectedCorporateId || ''}
        onCorporateChange={(id) => setSelectedCorporateId(id || undefined)}
        loading={corporatesLoading}
      />

      {/* Headline figure — Total Balance across all programs. Matches the
          hero+strip hierarchy used elsewhere; these four metrics previously
          competed as an equal-weight strip with no visual hierarchy. */}
      {loadError ? (
        <Card padding="md" className="border-error-200 dark:border-error-500/30">
          <div className="flex items-center justify-between gap-4">
            <div>
              <p className="body-strong">Programs could not be loaded</p>
              <p className="body-sm mt-1">{loadError}</p>
            </div>
            <Button variant="outline" leftIcon={<RefreshCw className="w-4 h-4" />} onClick={loadData} loading={loading}>Retry</Button>
          </div>
        </Card>
      ) : (
      <HeroMetricCard
        primary={{
          label: 'Total Balance',
          value: ratesLoading ? '…' : <TileAmount value={consolidatedBalance} currency={reportingCurrency} />,
          sub: `${displayStats.totalPrograms} programs · ${displayStats.activePrograms} active · ${displayStats.totalVirtualAccounts} virtual accounts`
            + (currencyCodes.length > 1 ? ` · converted to ${reportingCurrency} at live rates` : '')
            + (excludedCurrencies.length ? ` · excludes ${excludedCurrencies.join(', ')} (no rate)` : ''),
        }}
        icon={<TrendingUp className="w-6 h-6 text-accent-600 dark:text-accent-300" />}
      />
      )}

      {/* Search and Filters */}
      <Card padding="md">
        <div className="flex flex-col sm:flex-row gap-4">
          <div className="flex-1">
            <Input inputSize="sm" leftIcon={<Search className="w-4 h-4" />} aria-label="Search programs" placeholder="Search programs..." value={searchQuery} onChange={e => setSearchQuery(e.target.value)} />
          </div>
          <div className="w-48 shrink-0">
            <Select selectSize="sm" aria-label="Status" value={statusFilter} onChange={e => setStatusFilter(e.target.value as ProgramStatus | 'ALL')}>
              <option value="ALL">All Statuses</option><option value="ACTIVE">Active</option><option value="INACTIVE">Inactive</option><option value="SUSPENDED">Suspended</option><option value="PENDING_APPROVAL">Pending</option><option value="CLOSED">Closed</option>
            </Select>
          </div>
        </div>
      </Card>

      {/* Programs Table */}
      {!loadError && (
      <Card>
        {filteredPrograms.length > 0 && (
        <DataTable
          hairline
          data={sortedPrograms}
          sortKey={sort?.key}
          sortDirection={sort?.direction}
          onSort={(key, direction) => setSort({ key, direction })}
          keyExtractor={(program) => program.id}
          columns={[
            { key: 'programName', header: 'Program', minWidth: 240, sortable: true, mobileLabel: true, render: (_v, program) => {
              return (
                <div className="flex items-center gap-3">
                  <StatusIconBadge tone="neutral" icon={Layers} subtle />
                  <div>
                    <div className="flex items-center gap-2">
                      <p className="font-medium text-primary-900 dark:text-neutral-50">{program.programName}</p>
</div>
                    <p className="text-caption text-neutral-500 font-mono dark:text-neutral-400">{program.programCode}</p>
                  </div>
                </div>
              );
            } },
            // Which corporate owns each program: needed in the all-corporates view, redundant once one is picked.
            ...(selectedCorporateId ? [] : [{ key: 'corporateName', header: 'Corporate', minWidth: 150, dropOrder: 1, sortable: true, render: (_v: unknown, program: Program) => <p className="text-body-sm text-primary-900 dark:text-neutral-50">{program.corporateName || '-'}</p> }]),
            { key: 'virtualAccountCount', header: 'VAs', align: 'center', minWidth: 90, dropOrder: 2, sortable: true, render: (_v, program) => (
              <><p className="text-body-sm font-medium">{program.virtualAccountCount || 0}</p><p className="caption">{program.activeVirtualAccountCount || 0} active</p></>
            ) },
            { key: 'totalBalance', header: 'Balance', align: 'right', minWidth: 140, mobileValue: true, render: (_v, program) => (
              <p className="font-medium text-primary-900 dark:text-neutral-50">{formatCurrency(program.totalBalance || 0, program.currencyCode || 'AED')}</p>
            ) },
            { key: 'status', header: 'Status', minWidth: 120, sortable: true, render: (_v, program) => {
              const stConfig = statusConfig[program.status];
              return <Badge variant={stConfig?.variant}>{stConfig?.label || program.status}</Badge>;
            } },
            { key: 'actions', header: 'Actions', align: 'right', minWidth: 130, render: (_v, program) => (
              <div className="flex justify-end gap-1">
                <Button size="sm" variant="ghost" onClick={() => setSelectedProgram(program)} title="View Details" aria-label={`View ${program.programName}`}><Eye className="w-4 h-4" /></Button>
                <Button size="sm" variant="ghost" disabled={program.status === 'CLOSED'} onClick={() => setEditProgram(program)} title={program.status === 'CLOSED' ? 'Closed programs cannot be edited' : 'Edit Program'} aria-label={`Edit ${program.programName}`}><Pencil className="w-4 h-4" /></Button>
                <Button size="sm" variant="ghost" disabled={program.status === 'CLOSED'} onClick={() => { setEditProgram(program); setConfigStep('VIBAN Pool'); }} title="VIBAN settings" aria-label="VIBAN settings"><Hash className="w-4 h-4" /></Button>
                {/* Action Menu Dropdown */}
                <div className="relative">
                  <Button
                    size="sm"
                    variant="ghost"
                    onClick={e => openMenu(program.id, e)}
                    title="More Actions"
                    aria-label={`More actions for ${program.programName}`}
                    aria-haspopup="menu"
                    aria-expanded={actionMenuId === program.id}
                  >
                    <MoreHorizontal className="w-4 h-4" />
                  </Button>
                  {actionMenuId === program.id && (
                    <>
                      {/* Backdrop to close menu when clicking outside */}
                      <div className="fixed inset-0 z-10" onClick={closeMenu} onWheel={() => setActionMenuId(null)} />
                      {/* Dropdown Menu */}
                      <div role="menu" aria-label={`Actions for ${program.programName}`} onKeyDown={onMenuKeyDown} style={menuStyle} className="fixed w-48 bg-surface-card border border-edge rounded-lg shadow-lg z-20 py-1 text-left">
                        {/* Configure: settings that are optional at create time */}
                        {program.status !== 'CLOSED' && (<>
                        <button
                          role="menuitem"
                          tabIndex={-1}
                          className="w-full px-3 py-2 text-left text-body-sm hover:bg-neutral-50 flex items-center gap-2 dark:hover:bg-primary-800/40"
                          onClick={() => { setEditProgram(program); setConfigStep('Wallet Limits'); closeMenu(); }}
                        >
                          <Gauge className="w-4 h-4" />Wallet limits
                        </button>
                        <button
                          role="menuitem"
                          tabIndex={-1}
                          className="w-full px-3 py-2 text-left text-body-sm hover:bg-neutral-50 flex items-center gap-2 dark:hover:bg-primary-800/40"
                          onClick={() => { setEditProgram(program); setConfigStep('Wallet Fees'); closeMenu(); }}
                        >
                          <Receipt className="w-4 h-4" />Wallet fees
                        </button>
                        <div className="border-t border-edge my-1" />
                        </>)}
                        {/* Status Actions */}
                        {program.status === 'ACTIVE' && (
                          <button
                          role="menuitem"
                          tabIndex={-1}
                            className="w-full px-3 py-2 text-left text-body-sm hover:bg-neutral-50 flex items-center gap-2 text-warning-600 dark:hover:bg-primary-800/50 dark:text-warning-300"
                            onClick={() => { requestStatusChange(program.id, 'SUSPENDED'); closeMenu(); }}
                          >
                            <PauseCircle className="w-4 h-4" />
                            Suspend Program
                          </button>
                        )}
                        {(program.status === 'SUSPENDED' || program.status === 'INACTIVE') && (
                          <button
                          role="menuitem"
                          tabIndex={-1}
                            className="w-full px-3 py-2 text-left text-body-sm hover:bg-neutral-50 flex items-center gap-2 text-success-600 dark:hover:bg-primary-800/50 dark:text-success-300"
                            onClick={() => { handleStatusChange(program.id, 'ACTIVE'); closeMenu(); }}
                          >
                            <PlayCircle className="w-4 h-4" />
                            Activate Program
                          </button>
                        )}
                        {program.status === 'PENDING_APPROVAL' && (
                          <button
                          role="menuitem"
                          tabIndex={-1}
                            className="w-full px-3 py-2 text-left text-body-sm hover:bg-neutral-50 flex items-center gap-2 text-success-600 dark:hover:bg-primary-800/50 dark:text-success-300"
                            onClick={() => { handleStatusChange(program.id, 'ACTIVE'); closeMenu(); }}
                          >
                            <CheckCircle className="w-4 h-4" />
                            Approve Program
                          </button>
                        )}
                        {/* Clone Action */}
                        <button
                          role="menuitem"
                          tabIndex={-1}
                          className="w-full px-3 py-2 text-left text-body-sm hover:bg-neutral-50 flex items-center gap-2 text-neutral-700 dark:hover:bg-primary-800/50 dark:text-neutral-200"
                          onClick={() => { startClone(program); closeMenu(); }}
                        >
                          <Copy className="w-4 h-4" />
                          Clone Program
                        </button>
                        {/* Divider */}
                        <div className="border-t border-edge-subtle my-1" />
                        {/* Close Action */}
                        {program.status !== 'CLOSED' && (
                        <button
                          role="menuitem"
                          tabIndex={-1}
                          className="w-full px-3 py-2 text-left text-body-sm hover:bg-error-50 flex items-center gap-2 text-error-600 dark:hover:bg-error-500/10 dark:text-error-300"
                          onClick={() => {
                            setDeleteTarget(program);
                            closeMenu();
                          }}
                        >
                          <Archive className="w-4 h-4" />
                          Close Program
                        </button>
                        )}
                      </div>
                    </>
                  )}
                </div>
              </div>
            ) },
          ]}
        />
        )}
        
        {/* Empty State - Graceful handling when no data */}
        {filteredPrograms.length === 0 && !loading && (
          <div className="text-center py-12">
            <Layers className="w-12 h-12 mx-auto mb-3 text-neutral-300 dark:text-neutral-400" />
            <p className="text-body-lg font-medium text-primary-900 dark:text-neutral-50">No programs found</p>
            <p className="body-sm mt-1">
              {searchQuery || statusFilter !== 'ALL' 
                ? 'Try adjusting your search or filters'
                : selectedCorporateId 
                  ? 'Create a new program to get started'
                  : 'Programs will appear here once created'}
            </p>
            <Button className="mt-4" size="sm" onClick={() => setShowCreateModal(true)} leftIcon={<Plus className="w-4 h-4" />}>
              Create Program
            </Button>
          </div>
        )}
        
        {/* Loading overlay for table refresh */}
        {loading && programs.length > 0 && (
          <div className="absolute inset-0 bg-white/50 flex items-center justify-center dark:bg-primary-900/50">
            <Loader2 className="w-6 h-6 animate-spin text-primary-600 dark:text-primary-200" />
          </div>
        )}
      </Card>
      )}

      {/* Modals */}
      <Modal
        isOpen={!!deleteTarget}
        onClose={() => setDeleteTarget(null)}
        size="sm"
        title="Close program?"
        footer={
          <>
            <Button variant="outline" onClick={() => setDeleteTarget(null)} disabled={deleting}>Cancel</Button>
            <Button variant="danger" onClick={confirmClose} loading={deleting} leftIcon={<Archive className="w-4 h-4" />}>Close program</Button>
          </>
        }
      >
        <p className="body-sm">
          <span className="body-strong">{deleteTarget?.programName}</span> will be closed and its bank accounts released for other programs. A closed program can't be reopened. Its customer accounts must be closed or moved first.
        </p>
      </Modal>
      <Modal
        isOpen={!!cloneSource}
        onClose={() => setCloneSource(null)}
        size="sm"
        title="Clone program"
        footer={
          <>
            <Button variant="outline" onClick={() => setCloneSource(null)} disabled={cloning}>Cancel</Button>
            <Button type="submit" form="clone-program-form" loading={cloning} disabled={!cloneCode.trim() || !cloneName.trim()} leftIcon={<Copy className="w-4 h-4" />}>Clone</Button>
          </>
        }
      >
        {/* A form, so Enter in either field clones (the button sits in the footer, linked by id). */}
        <form
          id="clone-program-form"
          className="space-y-3"
          onSubmit={e => {
            e.preventDefault();
            if (!cloning && cloneCode.trim() && cloneName.trim()) confirmClone();
          }}
        >
          <p className="body-sm">
            Copies the settings of <span className="body-strong">{cloneSource?.programName}</span>. Accounts and balances are not copied; the new program starts pending approval.
          </p>
          <Input label="Program code" hint="Capital letters, digits, - and _" value={cloneCode} onChange={e => setCloneCode(e.target.value.toUpperCase().replace(/[^A-Z0-9_-]/g, ''))} />
          <Input label="Program name" value={cloneName} onChange={e => setCloneName(e.target.value)} />
        </form>
      </Modal>
      <ProgramDetailModal program={selectedProgram} onClose={() => setSelectedProgram(null)} onEdit={p => { setSelectedProgram(null); setEditProgram(p); }} onClone={p => { setSelectedProgram(null); startClone(p); }} onConfigure={(p, step) => { setSelectedProgram(null); setEditProgram(p); setConfigStep(step); }} onStatusChange={requestStatusChange} />
      <Modal
        isOpen={!!suspendTarget}
        onClose={() => setSuspendTarget(null)}
        size="sm"
        title="Suspend program?"
        footer={
          <>
            <Button variant="outline" onClick={() => setSuspendTarget(null)}>Cancel</Button>
            <Button variant="danger" leftIcon={<PauseCircle className="w-4 h-4" />}
              onClick={() => { if (suspendTarget) handleStatusChange(suspendTarget.id, 'SUSPENDED'); setSuspendTarget(null); }}>
              Suspend
            </Button>
          </>
        }
      >
        <p className="body-sm">
          <span className="body-strong">{suspendTarget?.programName}</span> will stop taking new activity until it is activated again.
        </p>
      </Modal>
      <ProgramFormModal
        isOpen={showCreateModal || !!editProgram}
        program={editProgram}
        onlyStep={configStep}
        onClose={() => { setConfigStep(undefined); setShowCreateModal(false); setEditProgram(null); }}
        onSave={handleSaveProgram}
        defaultCorporateId={selectedCorporateId}
      />
    </Page>
  );
};

export default ProgramsPage;