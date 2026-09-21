import React, { useState, useEffect, createContext, useContext } from 'react';
import { Toaster } from 'react-hot-toast';
import { TrendingUp, Building2, CreditCard, Users, Wallet } from 'lucide-react';
import Layout from './components/layout/Layout';
import { StatTile, StatusIconBadge } from './components/ui';
import { UserProvider } from './context/UserContext';
import { MarketProvider } from './context/MarketContext';
import { ThemeProvider } from './design-system/ThemeProvider';
import { CopilotProvider } from './ai/copilot/CopilotProvider';
import { CopilotLauncher } from './ai/copilot/CopilotLauncher';
import { CopilotDrawer } from './ai/copilot/CopilotDrawer';
import { PageHeaderProvider } from './context/PageHeaderContext';

// ============================================================================
// CORE PAGES
// ============================================================================
import Treasury2030DashboardPage from './pages/Treasury2030DashboardPage';
import AccountsPage from './pages/AccountsPage';
import ProgramsPage from './pages/ProgramsPage';
import PartiesPage from './pages/PartiesPage';
import BeneficiariesPage from './pages/BeneficiariesPage';
import TransactionsPage from './pages/TransactionsPage';
import PhysicalAccountsPage from './pages/PhysicalAccountsPage';
import StatementsPage from './pages/StatementsPage';

// ============================================================================
// FINANCE PAGES - Receivables/Payables
// ============================================================================
import EnhancedReceivablesPage from './pages/EnhancedReceivablesPage';
import EnhancedPayablesPage from './pages/EnhancedPayablesPage';
import CreateReceivablePage from './pages/CreateReceivablePage';
import CreatePayablePage from './pages/CreatePayablePage';
import PayInvoicePage from './pages/PayInvoicePage';
import EscrowPage from './pages/EscrowPage';
import WalletPage from './pages/WalletPage';

// ============================================================================
// BAAS & E-COMMERCE PAGES
// ============================================================================
import BaaSCashOperationsPage from './pages/BaaSCashOperationsPage';
import EcommerceDashboardPage from './pages/EcommerceDashboardPage';
import EcommerceCollectionsPage from './pages/EcommerceCollectionsPage';
import MerchantOnboardingPage from './pages/MerchantOnboardingPage';
import SellerCollectionsPage from './pages/SellerCollectionsPage';

// ============================================================================
// COMPLIANCE PAGES
// ============================================================================
import KyccPage from './pages/KyccPage';
import VibanManagementPage from './pages/VibanManagementPage';

// ============================================================================
// TREASURY PAGES - Core
// ============================================================================
import InHouseBankPage from './pages/InHouseBankPage';
import NotionalPoolingPage from './pages/NotionalPoolingPage';
import CashConcentrationPage from './pages/CashConcentrationPage';
import TreasuryHierarchyPage from './pages/TreasuryHierarchyPage';
import ForecastingPage from './pages/ForecastingPage';

// ============================================================================
// TREASURY PAGES - Phase 7 (Shadow, Currency Mirror, Credit, Funds)
// ============================================================================
import MultiBankLiquidityPage from './pages/MultiBankLiquidityPage';
import SimulatorPage from './pages/SimulatorPage';
import CurrencyMirrorPage from './pages/CurrencyMirrorPage';
import CreditLimitsPage from './pages/CreditLimitsPage';
import FundsAvailabilityPage from './pages/FundsAvailabilityPage';
import BalanceAggregationPage from './pages/BalanceAggregationPage';
import FxRatesPage from './pages/FxRatesPage';
import EntityBalanceTreePage from './pages/EntityBalanceTreePage';

// ============================================================================
// UNIFIED VAM DESIGN PAGES - Legal Entity & Account Attachments
// ============================================================================
import LegalEntitiesPage from './pages/LegalEntitiesPage';
import AccountAttachmentsPage from './pages/AccountAttachmentsPage';

// ============================================================================
// CREDIT MANAGEMENT PAGES
// ============================================================================
import CreditAgreementsPage from './pages/CreditAgreementsPage';
import CreditFacilitiesPage from './pages/CreditFacilitiesPage';
import InterestConfigurationPage from './pages/InterestConfigurationPage';
import InterestAccrualReportsPage from './pages/InterestAccrualReportsPage';

// ============================================================================
// SETTLEMENT & EXCEPTION PAGES (Phase 3)
// ============================================================================
import ExceptionDashboardPage from './pages/ExceptionDashboardPage';

// ============================================================================
// PHASE 7 & 8: NETTING & INTERCOMPANY PAGES
// ============================================================================
import EnhancedNettingCyclesPage from './pages/EnhancedNettingCyclesPage'; // Phase 7 Enhanced Netting
import IntercompanyDashboardPage, { IntercompanyTabType } from './pages/IntercompanyDashboardPage';
// ============================================================================
// ADMIN PAGES
// ============================================================================
import SyncAdminPage from './pages/SyncAdminPage';
import IntegrationsPage from './pages/IntegrationsPage';
import TaxChargesSetupPage from './pages/TaxChargeSetupPage';

import HierarchyOperationsPage from './pages/HierarchyOperationsPage';
import OperationHistoryPage from './pages/OperationHistoryPage';
import TransfersPage from './pages/TransfersPage';
import FileIngestUploadPage from './pages/FileIngestUploadPage';

// ============================================================================
// PAGE TYPES - Complete Unified VAM Design + Phase 7 & 8
// ============================================================================

export type PageType =
  // Core
  | 'dashboard'
  | 'accounts'
  | 'programs'
  | 'parties'
  | 'beneficiaries' 
  | 'transactions' 
  | 'physical-accounts'
  | 'statements'
  
  // Finance - List Views
  | 'receivables'
  | 'payables'
  | 'escrow' 
  | 'wallet' 
  
  // Finance - Create/Edit Views
  | 'receivables-create'
  | 'receivables-edit'
  | 'payables-create'
  | 'payables-edit'

  // Public (unauthenticated, no internal chrome)
  | 'pay-invoice'
  
  // BaaS Platform
  | 'baas-dashboard'
  | 'baas-partners'
  | 'baas-cards'
  | 'baas-cash'
  | 'baas-settlements'
  | 'baas-transactions'
  
  // Compliance
  | 'kycc'
  | 'viban'
  
  // Treasury - Core
  | 'ihb'
  | 'pooling'
  | 'notional-pooling'
  | 'sweeping'
  | 'simulator'
  | 'netting-enhanced'  // Phase 7 Enhanced Netting
  | 'hierarchy'
  | 'forecasting'
  
  // Treasury - Phase 7 (Shadow, Currency Mirror, Credit, Funds)
  // 'shadow-accounts' merged into 'physical-accounts' (Bank Accounts page)
  | 'multi-bank-liquidity'
  | 'currency-mirrors'
  | 'credit-limits'
  | 'funds-check'
  | 'balance-aggregation'
  | 'fx-rates'
  | 'entity-balance-tree'
  
  // Unified VAM Design - Legal Entity & Account Attachments
  | 'legal-entities'
  | 'account-attachments'
  
  // Credit Management
  | 'credit-agreements'
  | 'credit-facilities'
  | 'interest-config'
  | 'interest-accruals'
  
  // Settlement & Exception (Phase 3)
  | 'exceptions'
  
  // Phase 8: Intercompany Management
  | 'intercompany'
  | 'intercompany-pobo'
  | 'intercompany-cobo'
  | 'intercompany-settlement'
  
  // E-commerce
  | 'ecommerce-dashboard'
  | 'ecommerce-collections'
  | 'merchant-onboarding'
  | 'seller-collections'
  | 'hierarchy-operations'
  | 'operation-history'
  
  // Admin
  | 'sync-admin'
  | 'integrations'
  | 'tax-charges'
  | 'settings'

  // Fund Transfers
  | 'transfers'

  // File Ingest Pipeline
  | 'file-ingest';

// ============================================================================
// NAVIGATION CONTEXT
// ============================================================================

export interface NavigationContextType {
  currentPage: PageType;
  navigate: (page: PageType, params?: Record<string, string>) => void;
  goBack: () => void;
  params: Record<string, string>;
  selectedProgramId: string | null;
}

const NavigationContext = createContext<NavigationContextType | null>(null);

export const useNavigation = () => {
  const context = useContext(NavigationContext);
  if (!context) {
    throw new Error('useNavigation must be used within NavigationProvider');
  }
  return context;
};

// ============================================================================
// BAAS PLACEHOLDER PAGES
// ============================================================================

const BaaSDashboardPage: React.FC = () => (
  <div className="space-y-6 animate-page-enter">
    {/* Stats Grid — Phase 12 Task E: hand-rolled tiles replaced by the shared
        <StatTile> (components/ui/StatTile). */}
    <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
      {[
        { label: 'Active Partners', value: '12', sub: '↑ 2 this month', tone: 'primary' },
        { label: 'Total Wallets', value: '17,250', sub: '↑ 1,250 this month', tone: 'success' },
        { label: 'Total Float', value: 'AED 174.5M', sub: '↑ 12% vs last month', tone: 'accent' },
        { label: 'Daily Volume', value: 'AED 2.5M', sub: '4,500 transactions', tone: 'info' },
      ].map((stat, i) => (
        <StatTile
          key={stat.label}
          tone={stat.tone}
          label={stat.label}
          value={stat.value}
          sub={stat.sub}
          icon={<TrendingUp className="w-5 h-5" />}
          delay={`${0.05 + i * 0.05}s`}
        />
      ))}
    </div>
    {/* Partner Performance Card */}
    <div className="bg-white rounded-lg shadow-sm border border-neutral-200 animate-fade-in" style={{ animationDelay: '0.25s' }}>
      <div className="p-5 border-b border-neutral-200 flex items-center gap-3">
        <StatusIconBadge tone="primary" icon={Building2} />
        <h2 className="text-body-lg font-semibold text-primary-900 dark:text-neutral-50">Partner Performance</h2>
      </div>
      <div className="divide-y divide-neutral-100">
        {[
          { name: 'Fintech Partner A', wallets: 12500, volume: 'AED 45M', status: 'Active' },
          { name: 'Fintech Partner B', wallets: 3500, volume: 'AED 125M', status: 'Active' },
          { name: 'Fintech Partner C', wallets: 1250, volume: 'AED 4.5M', status: 'Active' },
        ].map((partner, i) => (
          <div key={i} className="flex items-center justify-between p-4 hover:bg-neutral-50 dark:hover:bg-primary-800/50 transition-colors group">
            <div className="flex items-center gap-4">
              <div className="w-10 h-10 bg-primary-100 dark:bg-primary-700 rounded-lg flex items-center justify-center">
                <span className="text-primary-700 dark:text-neutral-200 font-semibold">{partner.name.charAt(0)}</span>
              </div>
              <div>
                <p className="font-medium text-primary-900 dark:text-neutral-50">{partner.name}</p>
                <p className="body-sm">{partner.wallets.toLocaleString()} wallets</p>
              </div>
            </div>
            <div className="text-right flex items-center gap-4">
              <div>
                <p className="font-semibold text-primary-900 dark:text-neutral-50">{partner.volume}</p>
                <span className="text-caption px-2 py-0.5 bg-success-100 dark:bg-success-500/15 text-success-700 dark:text-success-300 rounded-full">{partner.status}</span>
              </div>
              <button className="text-primary-600 text-body-sm opacity-0 group-hover:opacity-100 transition-opacity">View</button>
            </div>
          </div>
        ))}
      </div>
    </div>
  </div>
);

const BaaSPartnersPage: React.FC = () => (
  <div className="space-y-6 animate-page-enter">
    {/* Quick Actions */}
    <div className="flex items-center justify-end gap-2 animate-fade-in" style={{ animationDelay: '0.05s' }}>
      <button className="px-4 py-2 bg-primary-600 text-white rounded-lg hover:bg-primary-700 transition-colors">+ Onboard Partner</button>
    </div>
    {/* Stats */}
    <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
      {[
        { label: 'Total Partners', value: '12', tone: 'primary' },
        { label: 'Active', value: '10', tone: 'success' },
        { label: 'Pending Approval', value: '2', tone: 'warning' },
        { label: 'Total Programs', value: '28', tone: 'info' },
      ].map((stat, i) => (
        <StatTile
          key={stat.label}
          tone={stat.tone}
          valueTone="neutral"
          label={stat.label}
          value={stat.value}
          icon={<Users className="w-5 h-5" />}
          delay={`${0.1 + i * 0.05}s`}
        />
      ))}
    </div>
    {/* Partner List Card */}
    <div className="bg-white rounded-lg shadow-sm border border-neutral-200 animate-fade-in" style={{ animationDelay: '0.3s' }}>
      <div className="p-5 border-b border-neutral-200 flex items-center gap-3">
        <StatusIconBadge tone="primary" icon={Building2} />
        <h2 className="text-body-lg font-semibold text-primary-900 dark:text-neutral-50">Partner Directory</h2>
      </div>
      <div className="p-8 text-center">
        <StatusIconBadge tone="neutral" icon={Building2} size="xl" className="mx-auto mb-4" />
        <p className="text-neutral-500 dark:text-neutral-400">Partner management interface coming soon...</p>
      </div>
    </div>
  </div>
);

const BaaSCardsPage: React.FC = () => (
  <div className="space-y-6 animate-page-enter">
    {/* Quick Actions */}
    <div className="flex items-center justify-end gap-2 animate-fade-in" style={{ animationDelay: '0.05s' }}>
      <button className="px-4 py-2 bg-primary-600 text-white rounded-lg hover:bg-primary-700 transition-colors">+ Issue Card</button>
    </div>
    {/* Stats */}
    <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
      {[
        { label: 'Total Cards', value: '5,420', tone: 'primary' },
        { label: 'Active', value: '4,850', tone: 'success' },
        { label: 'Suspended', value: '120', tone: 'warning' },
        { label: 'Monthly Spend', value: 'AED 2.5M', tone: 'info' },
      ].map((stat, i) => (
        <StatTile
          key={stat.label}
          tone={stat.tone}
          valueTone="neutral"
          label={stat.label}
          value={stat.value}
          icon={<CreditCard className="w-5 h-5" />}
          delay={`${0.1 + i * 0.05}s`}
        />
      ))}
    </div>
    {/* Cards List */}
    <div className="bg-white rounded-lg shadow-sm border border-neutral-200 animate-fade-in" style={{ animationDelay: '0.3s' }}>
      <div className="p-5 border-b border-neutral-200 flex items-center gap-3">
        <StatusIconBadge tone="accent" icon={CreditCard} />
        <h2 className="text-body-lg font-semibold text-primary-900 dark:text-neutral-50">Virtual Card Programs</h2>
      </div>
      <div className="p-8 text-center">
        <StatusIconBadge tone="neutral" icon={CreditCard} size="xl" className="mx-auto mb-4" />
        <p className="text-neutral-500 dark:text-neutral-400">Virtual cards interface coming soon...</p>
      </div>
    </div>
  </div>
);

const BaaSSettlementsPage: React.FC = () => (
  <div className="space-y-6 animate-page-enter">
    {/* Stats */}
    <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
      {[
        { label: 'Pending Settlements', value: '8', tone: 'warning' },
        { label: 'Settled Today', value: '12', tone: 'success' },
        { label: 'Total Amount', value: 'AED 4.2M', tone: 'primary' },
        { label: 'Partners', value: '10', tone: 'info' },
      ].map((stat, i) => (
        <StatTile
          key={stat.label}
          tone={stat.tone}
          valueTone="neutral"
          label={stat.label}
          value={stat.value}
          icon={<Wallet className="w-5 h-5" />}
          delay={`${0.05 + i * 0.05}s`}
        />
      ))}
    </div>
    {/* Content Card */}
    <div className="bg-white rounded-lg shadow-sm border border-neutral-200 animate-fade-in" style={{ animationDelay: '0.25s' }}>
      <div className="p-5 border-b border-neutral-200 flex items-center gap-3">
        <StatusIconBadge tone="success" icon={Wallet} />
        <h2 className="text-body-lg font-semibold text-primary-900 dark:text-neutral-50">Settlement Cycles</h2>
      </div>
      <div className="p-8 text-center">
        <StatusIconBadge tone="neutral" icon={Wallet} size="xl" className="mx-auto mb-4" />
        <p className="text-neutral-500 dark:text-neutral-400">Settlements interface coming soon...</p>
      </div>
    </div>
  </div>
);

const BaaSTransactionsPage: React.FC = () => (
  <div className="space-y-6 animate-page-enter">
    {/* Stats */}
    <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
      {[
        { label: 'Today\'s Volume', value: '4,520', tone: 'primary' },
        { label: 'Successful', value: '4,480', tone: 'success' },
        { label: 'Failed', value: '40', tone: 'danger' },
        { label: 'Total Amount', value: 'AED 2.5M', tone: 'info' },
      ].map((stat, i) => (
        <StatTile
          key={stat.label}
          tone={stat.tone}
          valueTone="neutral"
          label={stat.label}
          value={stat.value}
          icon={<TrendingUp className="w-5 h-5" />}
          delay={`${0.05 + i * 0.05}s`}
        />
      ))}
    </div>
    {/* Content Card */}
    <div className="bg-white rounded-lg shadow-sm border border-neutral-200 animate-fade-in" style={{ animationDelay: '0.25s' }}>
      <div className="p-5 border-b border-neutral-200 flex items-center gap-3">
        <StatusIconBadge tone="primary" icon={TrendingUp} />
        <h2 className="text-body-lg font-semibold text-primary-900 dark:text-neutral-50">Transaction History</h2>
      </div>
      <div className="p-8 text-center">
        <StatusIconBadge tone="neutral" icon={TrendingUp} size="xl" className="mx-auto mb-4" />
        <p className="text-neutral-500 dark:text-neutral-400">BaaS transactions interface coming soon...</p>
      </div>
    </div>
  </div>
);

// ============================================================================
// MAIN APP COMPONENT
// ============================================================================

// F4: section themes — drives --section-accent / --section-accent-warm.
// See styles/index.css [data-section="..."] rules.
type SectionKey = 'core' | 'finance' | 'compliance' | 'treasury' | 'baas' | 'intercompany' | 'admin' | 'payments';
const sectionFor = (page: PageType): SectionKey => {
  if (page === 'transfers') return 'payments';
  if (page.startsWith('baas-')) return 'baas';
  if (page === 'kycc' || page === 'viban') return 'compliance';
  if (page === 'sync-admin' || page === 'integrations' || page === 'tax-charges' || page === 'settings' || page === 'operation-history') return 'admin';
  if (page.startsWith('intercompany')) return 'intercompany';
  const treasuryPages: PageType[] = ['ihb','pooling','notional-pooling','sweeping','simulator','netting-enhanced','hierarchy',
    'forecasting','multi-bank-liquidity','currency-mirrors','credit-limits','funds-check','balance-aggregation','fx-rates',
    'entity-balance-tree','legal-entities','account-attachments','credit-agreements','credit-facilities','interest-config',
    'interest-accruals','exceptions','hierarchy-operations'];
  if (treasuryPages.includes(page)) return 'treasury';
  if (['receivables','payables','escrow','wallet','receivables-create','receivables-edit','payables-create','payables-edit',
       'ecommerce-dashboard','ecommerce-collections','merchant-onboarding','seller-collections','file-ingest'].includes(page)) return 'finance';
  return 'core';
};

const App: React.FC = () => {
  const [currentPage, setCurrentPage] = useState<PageType>(
    // Dev deep-link (?page=forecasting) — used by docs/manual/capture.mjs to
    // screenshot every page; harmless in prod (unknown values fall through).
    (new URLSearchParams(window.location.search).get('page') as PageType) || 'dashboard'
  );
  const [pageHistory, setPageHistory] = useState<PageType[]>(['dashboard']);
  const [pageParams, setPageParams] = useState<Record<string, string>>(() => {
    // A payment link is opened cold from outside the app (email/SMS), so its token must come
    // from the URL on first load too -- pageParams is otherwise only ever set by in-app navigate().
    const initial: Record<string, string> = {};
    const token = new URLSearchParams(window.location.search).get('token');
    if (token) initial.token = token;
    return initial;
  });
  const [selectedProgramId, setSelectedProgramId] = useState<string | null>(null);

  // F4: sync data-section on <html> whenever the route changes.
  useEffect(() => {
    document.documentElement.setAttribute('data-section', sectionFor(currentPage));
  }, [currentPage]);

  const navigate = (page: PageType, params?: Record<string, string>) => {
    setPageHistory(prev => [...prev, currentPage]);
    setCurrentPage(page);
    if (params) {
      setPageParams(params);
      if (params.programId) setSelectedProgramId(params.programId);
    } else {
      setPageParams({});
    }
  };

  const handleNavigate = (page: PageType | string) => {
    navigate(page as PageType);
  };

  const goBack = () => {
    if (pageHistory.length > 1) {
      const newHistory = [...pageHistory];
      const previousPage = newHistory.pop();
      setPageHistory(newHistory);
      setCurrentPage(previousPage || 'dashboard');
      setPageParams({});
      setSelectedProgramId(null);
    } else {
      if (currentPage.includes('receivables')) setCurrentPage('receivables');
      else if (currentPage.includes('payables')) setCurrentPage('payables');
      else setCurrentPage('dashboard');
    }
  };

  // Only the public, unauthenticated pay-invoice page skips the app shell.
  // receivables-create/edit and payables-create/edit used to be listed here
  // too, which meant the sidebar (and all other navigation) disappeared
  // entirely while filling out an invoice or payable -- both pages already
  // build their own content assuming Layout's chrome via <Page maxWidth=
  // "narrow"> and <PageHeader> (which registers into Layout's header rather
  // than rendering its own), so this was a straightforward regression, not
  // an intentional "focused wizard" design.
  const isFullScreenPage = ['pay-invoice'].includes(currentPage);

  const renderPage = () => {
    switch (currentPage) {
      // ====================================================================
      // BaaS Platform Pages
      // ====================================================================
      case 'baas-dashboard': return <BaaSDashboardPage />;
      case 'baas-partners': return <BaaSPartnersPage />;
      case 'baas-cards': return <BaaSCardsPage />;
      case 'baas-cash': return <BaaSCashOperationsPage />;
      case 'baas-settlements': return <BaaSSettlementsPage />;
      case 'baas-transactions': return <BaaSTransactionsPage />;

      // ====================================================================
      // Core Pages
      // ====================================================================
      case 'accounts': return <AccountsPage onNavigate={handleNavigate} />;
      case 'programs': return <ProgramsPage />;
      case 'parties': return <PartiesPage />;
      case 'beneficiaries': return <BeneficiariesPage />;
      case 'transactions': return <TransactionsPage />;
      case 'physical-accounts': return <PhysicalAccountsPage />;
      case 'statements': return <StatementsPage />;
      
      // ====================================================================
      // Finance - List Views
      // ====================================================================
      case 'receivables': return <EnhancedReceivablesPage />;
      case 'payables': return <EnhancedPayablesPage />;
      case 'escrow': return <EscrowPage />;
      case 'wallet': return <WalletPage />;
      case 'file-ingest': return <FileIngestUploadPage />;
      
      // ====================================================================
      // Finance - Create/Edit Views
      // ====================================================================
      case 'receivables-create': return <CreateReceivablePage />;
      case 'receivables-edit': return <CreateReceivablePage receivableId={pageParams.id} />;
      case 'payables-create': return <CreatePayablePage />;
      case 'payables-edit': return <CreatePayablePage payableId={pageParams.id} />;

      // ====================================================================
      // Public (unauthenticated)
      // ====================================================================
      case 'pay-invoice': return <PayInvoicePage token={pageParams.token} />;
      
      // ====================================================================
      // Compliance
      // ====================================================================
      case 'kycc': return <KyccPage />;
      case 'viban': return <VibanManagementPage />;
      
      // ====================================================================
      // Treasury - Core
      // ====================================================================
      case 'ihb': return <InHouseBankPage />;
      case 'pooling':
      case 'notional-pooling': return <NotionalPoolingPage />;
      case 'sweeping': return <CashConcentrationPage />;
      // Cash-Concentration + Notional-Pool simulator — first-class feature
      // (the simulator.* feature flags were discontinued 2026-05-16).
      case 'simulator':
        return <SimulatorPage />;
      case 'netting-enhanced': return <EnhancedNettingCyclesPage />;  // Phase 7 Enhanced
      case 'hierarchy': return <TreasuryHierarchyPage onNavigate={handleNavigate} />;
      case 'forecasting': return <ForecastingPage />;
      
      // ====================================================================
      // Treasury - Phase 7 (Shadow, Currency Mirror, Credit, Funds)
      // ====================================================================
      case 'multi-bank-liquidity': return <MultiBankLiquidityPage />;
      case 'currency-mirrors': return <CurrencyMirrorPage />;
      case 'credit-limits': return <CreditLimitsPage />;
      case 'funds-check': return <FundsAvailabilityPage />;
      case 'balance-aggregation': return <BalanceAggregationPage />;
      case 'fx-rates': return <FxRatesPage />;
      case 'entity-balance-tree': return <EntityBalanceTreePage />;
      
      // ====================================================================
      // Unified VAM Design - Legal Entity & Account Attachments
      // ====================================================================
      case 'legal-entities': return <LegalEntitiesPage />;
      case 'account-attachments': return <AccountAttachmentsPage />;
      
      // ====================================================================
      // Credit Management
      // ====================================================================
      case 'credit-agreements': return <CreditAgreementsPage />;
      case 'credit-facilities': return <CreditFacilitiesPage />;
      case 'interest-config': return <InterestConfigurationPage />;
      case 'interest-accruals': return <InterestAccrualReportsPage />;
        
      // ====================================================================
      // Settlement & Exception (Phase 3)
      // ====================================================================
      case 'exceptions': return <ExceptionDashboardPage />;
      
      // ====================================================================
      // Phase 8: Intercompany Management
      // ====================================================================
      case 'intercompany': return <IntercompanyDashboardPage defaultTab="overview" />;
      case 'intercompany-pobo': return <IntercompanyDashboardPage defaultTab="pobo" />;
      case 'intercompany-cobo': return <IntercompanyDashboardPage defaultTab="cobo" />;
      case 'intercompany-settlement': return <IntercompanyDashboardPage defaultTab="settlement" />;
      
      // ====================================================================
      // E-commerce
      // ====================================================================
      case 'ecommerce-dashboard': return <EcommerceDashboardPage />;
      case 'ecommerce-collections': return <EcommerceCollectionsPage />;
      case 'merchant-onboarding': return <MerchantOnboardingPage />;
      case 'seller-collections': return <SellerCollectionsPage />;
 
      //Hierarchy Operations
      case 'hierarchy-operations': return <HierarchyOperationsPage />;
      case 'operation-history': return <OperationHistoryPage />;
      
      // ====================================================================
      // Admin
      // ====================================================================
      case 'sync-admin': return <SyncAdminPage />;
      case 'integrations': return <IntegrationsPage />;
      case 'tax-charges': return <TaxChargesSetupPage />;
      case 'settings': return <div className="p-8 text-center text-neutral-500 dark:text-neutral-400">Settings page coming soon...</div>;

      // Fund Transfers
      case 'transfers': return <TransfersPage />;

      case 'dashboard':
      default: return <Treasury2030DashboardPage onNavigate={handleNavigate} />;
    }
  };

  const navigationContextValue: NavigationContextType = {
    currentPage,
    navigate,
    goBack,
    params: pageParams,
    selectedProgramId,
  };

  return (
    <ThemeProvider defaultMode="light">
    <MarketProvider>
    <UserProvider>
    <CopilotProvider>
    <PageHeaderProvider>
      <NavigationContext.Provider value={navigationContextValue}>
        <Toaster
          position="top-right"
          toastOptions={{
            duration: 4000,
            style: {
              background: '#fff',
              color: '#46494c', // ink — palette swap, was navy-900 #102a43
              boxShadow: '0 4px 12px rgba(0,0,0,0.15)',
              borderRadius: '12px',
              padding: '16px 20px',
              fontSize: '14px',
            },
            success: { iconTheme: { primary: '#578b7a', secondary: '#fff' } },
            error: { iconTheme: { primary: '#bb6d67', secondary: '#fff' } },
          }}
        />
        {isFullScreenPage ? (
          <div className="min-h-screen bg-neutral-50 dark:bg-primary-950/50">{renderPage()}</div>
        ) : (
          <Layout currentPage={currentPage} onNavigate={handleNavigate}>{renderPage()}</Layout>
        )}
        {/* Treasury Copilot — anchored once at the root so it overlays
            every page and survives navigation without remounting. */}
        <CopilotLauncher />
        <CopilotDrawer />
      </NavigationContext.Provider>
    </PageHeaderProvider>
    </CopilotProvider>
    </UserProvider>
    </MarketProvider>
    </ThemeProvider>
  );
};

export default App;