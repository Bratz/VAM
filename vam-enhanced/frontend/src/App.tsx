import React, { useState } from 'react';
import { Toaster } from 'react-hot-toast';
import Layout from './components/layout/Layout';
import DashboardPage from './pages/DashboardPage';
import AccountsPage from './pages/AccountsPage';
import EscrowPage from './pages/EscrowPage';
import WalletPage from './pages/WalletPage';
import KyccPage from './pages/KyccPage';
import SyncAdminPage from './pages/SyncAdminPage';

// Page type
type PageType = 'dashboard' | 'accounts' | 'escrow' | 'wallet' | 'kycc' | 'transactions' | 'beneficiaries' | 'sync-admin' | 'settings';

const App: React.FC = () => {
  const [currentPage, setCurrentPage] = useState<PageType>('dashboard');

  const renderPage = () => {
    switch (currentPage) {
      case 'accounts':
        return <AccountsPage />;
      case 'escrow':
        return <EscrowPage />;
      case 'wallet':
        return <WalletPage />;
      case 'kycc':
        return <KyccPage />;
      case 'sync-admin':
        return <SyncAdminPage />;
      case 'dashboard':
      default:
        return <DashboardPage />;
    }
  };

  return (
    <>
      <Toaster
        position="top-right"
        toastOptions={{
          duration: 4000,
          style: {
            background: '#fff',
            color: '#102a43',
            boxShadow: '0 4px 12px rgba(0,0,0,0.15)',
            borderRadius: '12px',
            padding: '16px 20px',
            fontSize: '14px',
          },
          success: {
            iconTheme: {
              primary: '#10b981',
              secondary: '#fff',
            },
          },
          error: {
            iconTheme: {
              primary: '#ef4444',
              secondary: '#fff',
            },
          },
        }}
      />
      <Layout currentPage={currentPage} onNavigate={setCurrentPage}>
        {renderPage()}
      </Layout>
    </>
  );
};

export default App;
