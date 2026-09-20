import React, { useCallback, useEffect, useState } from 'react';
import { Store, Search, Loader2, RefreshCw, Users, TrendingUp, XCircle, Info } from 'lucide-react';
import { Card, Button, Badge, Input, StatusIconBadge, DataTable } from '../components/ui';
import { Alert } from '../components/ui/enhanced';
import { ecommerceApi } from '../services/api';
import type { CollectionAccount, EcommerceMerchant } from '../services/api';
import { formatCurrency, formatDate } from '../utils';
import { Page } from '../components/layout/Page';

/**
 * Collection accounts — the accounts money is actually collected into, with the
 * volume each has taken. Acquiring merchants are listed separately: va_movements
 * carries the merchant columns but no feed populates them and there is no
 * merchant registry to onboard into, so this page reports that rather than
 * offering a form that writes nowhere.
 */
const MerchantOnboardingPage: React.FC = () => {
  const [accounts, setAccounts] = useState<CollectionAccount[]>([]);
  const [merchants, setMerchants] = useState<EcommerceMerchant[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [searchQuery, setSearchQuery] = useState('');

  const fetchData = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [accountsRes, merchantsRes] = await Promise.all([
        ecommerceApi.getCollectionAccounts(),
        ecommerceApi.getMerchants(),
      ]);
      setAccounts(accountsRes.data ?? []);
      setMerchants(merchantsRes.data ?? []);
    } catch (err: any) {
      setError(err?.response?.data?.message || err?.message || 'Could not load collection accounts');
      setAccounts([]);
      setMerchants([]);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void fetchData(); }, [fetchData]);

  const q = searchQuery.toLowerCase();
  const filtered = accounts.filter(a =>
    !q
    || a.vaName?.toLowerCase().includes(q)
    || a.vaNumber?.toLowerCase().includes(q)
    || a.programName?.toLowerCase().includes(q)
  );

  const collecting = accounts.filter(a => a.collectionCount > 0);
  const totalCollected = accounts.reduce((sum, a) => sum + (a.collectedVolume || 0), 0);

  if (loading) {
    return (
      <div className="flex items-center justify-center py-12">
        <Loader2 className="w-6 h-6 animate-spin text-primary-600 dark:text-primary-200" />
      </div>
    );
  }

  return (
    <Page>
      <div className="flex items-center justify-end gap-2 animate-fade-in" style={{ animationDelay: '0.05s' }}>
        <Button variant="outline" leftIcon={<RefreshCw className="w-4 h-4" />} onClick={() => void fetchData()}>
          Refresh
        </Button>
      </div>

      {merchants.length === 0 && (
        <Alert variant="info" title="No acquiring merchants registered">
          This platform collects through virtual accounts rather than a merchant register. Card
          movements carry merchant fields, but nothing populates them yet and there is no merchant
          record to onboard into, so merchant registration is unavailable.
        </Alert>
      )}

      <div className="grid grid-cols-1 md:grid-cols-3 gap-4 animate-fade-in" style={{ animationDelay: '0.1s' }}>
        <Card hover>
          <div className="p-4">
            <StatusIconBadge tone="primary" icon={Store} />
            <p className="stat-value-sm mt-3">{accounts.length}</p>
            <p className="label">Collection Accounts</p>
          </div>
        </Card>
        <Card hover>
          <div className="p-4">
            <StatusIconBadge tone="success" icon={TrendingUp} />
            <p className="stat-value-success mt-3">{collecting.length}</p>
            <p className="label">With Collections</p>
          </div>
        </Card>
        <Card hover>
          <div className="p-4">
            <StatusIconBadge tone="info" icon={Users} />
            <p className="stat-value-sm mt-3">{formatCurrency(totalCollected)}</p>
            <p className="label">Collected to Date</p>
          </div>
        </Card>
      </div>

      <Card className="animate-fade-in" style={{ animationDelay: '0.15s' }}>
        <div className="p-4 relative">
          <Search className="w-4 h-4 absolute left-7 top-1/2 -translate-y-1/2 text-neutral-400" />
          <Input
            className="pl-9"
            placeholder="Search by account name, number or program..."
            value={searchQuery}
            onChange={e => setSearchQuery(e.target.value)}
          />
        </div>
      </Card>

      {error && (
        <Card className="bg-error-50 border-error-200 animate-fade-in dark:bg-error-500/10 dark:border-error-500/30" style={{ animationDelay: '0.15s' }}>
          <div className="flex items-center gap-4 p-4">
            <StatusIconBadge tone="error" icon={XCircle} />
            <span className="text-error-800 dark:text-error-300">{error}</span>
          </div>
        </Card>
      )}

      <Card className="animate-fade-in" style={{ animationDelay: '0.2s' }}>
        <DataTable
          data={filtered}
          keyExtractor={(a) => a.vaId}
          emptyIcon={<Store className="w-12 h-12 text-neutral-300 dark:text-neutral-400" />}
          emptyTitle={accounts.length === 0 ? 'No collection accounts' : 'No accounts match this search'}
          columns={[
            {
              key: 'vaName',
              header: 'Collection Account',
              render: (_, a) => (
                <div className="flex items-center gap-3">
                  <StatusIconBadge tone={a.collectionCount > 0 ? 'primary' : 'neutral'} icon={Store} />
                  <div>
                    <p className="font-medium text-neutral-900 dark:text-neutral-50">{a.vaName}</p>
                    <p className="caption">{a.vaNumber}</p>
                  </div>
                </div>
              ),
            },
            { key: 'programName', header: 'Program', render: (_, a) => <Badge variant="neutral">{a.programName}</Badge> },
            {
              key: 'collectionCount',
              header: 'Collections',
              align: 'right',
              render: (_, a) => <span className="body-sm">{a.collectionCount.toLocaleString()}</span>,
            },
            {
              key: 'collectedVolume',
              header: 'Collected',
              align: 'right',
              render: (_, a) => (
                <span className="font-medium tracking-tight">{formatCurrency(a.collectedVolume, a.currencyCode)}</span>
              ),
            },
            {
              key: 'currentBalance',
              header: 'Balance',
              align: 'right',
              render: (_, a) => (
                <span className="body-sm">{formatCurrency(a.currentBalance, a.currencyCode)}</span>
              ),
            },
            {
              key: 'lastCollectionAt',
              header: 'Last Collection',
              render: (_, a) => (
                <span className="body-sm">{a.lastCollectionAt ? formatDate(a.lastCollectionAt) : '—'}</span>
              ),
            },
          ]}
        />
      </Card>

      {merchants.length > 0 && (
        <Card className="animate-fade-in" style={{ animationDelay: '0.25s' }}>
          <div className="p-4 border-b border-edge flex items-center gap-2">
            <Info className="w-4 h-4 text-neutral-400" />
            <h3 className="body-strong">Acquiring merchants</h3>
          </div>
          <DataTable
            data={merchants}
            keyExtractor={(m) => m.merchantId}
            columns={[
              { key: 'merchantName', header: 'Merchant', render: (_, m) => <span className="body-strong">{m.merchantName || m.merchantId}</span> },
              { key: 'category', header: 'Category', render: (_, m) => <Badge variant="neutral">{m.category || '—'}</Badge> },
              { key: 'transactionCount', header: 'Transactions', align: 'right', render: (_, m) => <span className="body-sm">{m.transactionCount.toLocaleString()}</span> },
              { key: 'volume', header: 'Volume', align: 'right', render: (_, m) => <span className="font-medium tracking-tight">{formatCurrency(m.volume)}</span> },
              { key: 'terminalCount', header: 'Terminals', align: 'right', render: (_, m) => <span className="body-sm">{m.terminalCount}</span> },
            ]}
          />
        </Card>
      )}
    </Page>
  );
};

export default MerchantOnboardingPage;
