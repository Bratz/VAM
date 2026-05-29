import React, { useState, useEffect } from 'react';
import {
  Building2,
  Wallet,
  TrendingUp,
  ArrowUpRight,
  ArrowDownRight,
  CreditCard,
  Send,
  Loader2,
  Layers,
  Globe,
} from 'lucide-react';
// Fixed imports for flat project structure
import { Card, CardHeader, Badge } from '../ui'; // UI components
import { formatCurrency, cn } from '../../utils';
import {
  Treemap,
  ResponsiveContainer,
  Tooltip,
  Cell,
} from 'recharts';

// ============================================================================
// TYPES
// ============================================================================

interface LevelBalance {
  level: number;
  levelName: string;
  totalBalance: number;
  nodeCount: number;
  currencyCode: string;
}

interface TopEntity {
  id: string;
  name: string;
  code: string;
  path: string;
  balance: number;
  change: number;
  currencyCode: string;
}

interface VibanStats {
  todayAmount: number;
  todayCount: number;
  activeVibans: number;
  pendingMatching: number;
  averageAmount: number;
  currencyCode: string;
}

interface PoboStats {
  todayPoboAmount: number;
  todayPoboCount: number;
  pendingRecharge: number;
  monthlyPobo: number;
  activeAuthorizations: number;
  currencyCode: string;
}

// Helper function for compact currency
const formatCompactCurrency = (amount: number, currency: string = 'AED'): string => {
  if (amount >= 1000000) {
    return `${(amount / 1000000).toFixed(1)}M ${currency}`;
  }
  if (amount >= 1000) {
    return `${(amount / 1000).toFixed(1)}K ${currency}`;
  }
  return formatCurrency(amount, currency);
};

// ============================================================================
// BALANCE BY LEVEL WIDGET (TreeMap)
// ============================================================================

interface BalanceByLevelWidgetProps {
  programId?: string;
  className?: string;
}

export const BalanceByLevelWidget: React.FC<BalanceByLevelWidgetProps> = ({
  programId,
  className,
}) => {
  const [data, setData] = useState<LevelBalance[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const fetchData = async () => {
      try {
        setLoading(true);
        // Mock data - replace with API call
        setData([
          { level: 1, levelName: 'Currency', totalBalance: 125000000, nodeCount: 1, currencyCode: 'AED' },
          { level: 2, levelName: 'Region', totalBalance: 125000000, nodeCount: 4, currencyCode: 'AED' },
          { level: 3, levelName: 'Country', totalBalance: 125000000, nodeCount: 8, currencyCode: 'AED' },
          { level: 4, levelName: 'Entity', totalBalance: 125000000, nodeCount: 15, currencyCode: 'AED' },
          { level: 5, levelName: 'Department', totalBalance: 125000000, nodeCount: 45, currencyCode: 'AED' },
          { level: 6, levelName: 'Account Type', totalBalance: 125000000, nodeCount: 120, currencyCode: 'AED' },
          { level: 7, levelName: 'Virtual Account', totalBalance: 125000000, nodeCount: 350, currencyCode: 'AED' },
        ]);
      } catch (err) {
        console.error('Failed to load balance by level:', err);
      } finally {
        setLoading(false);
      }
    };
    fetchData();
  }, [programId]);

  // Color palette for treemap
  const COLORS = [
    '#102a43', // primary-900
    '#243b53', // primary-800
    '#334e68', // primary-700
    '#486581', // primary-600
    '#627d98', // primary-500
    '#829ab1', // primary-400
    '#9fb3c8', // primary-300
  ];

  // Transform data for treemap
  const treemapData = data.map((item, index) => ({
    name: `L${item.level}: ${item.levelName}`,
    size: item.nodeCount,
    balance: item.totalBalance,
    color: COLORS[index % COLORS.length],
  }));

  if (loading) {
    return (
      <Card padding="md" className={className}>
        <div className="flex items-center justify-between mb-4">
          <div className="h-5 bg-neutral-200 rounded w-32 animate-pulse dark:bg-primary-800" />
        </div>
        <div className="h-32 bg-neutral-100 dark:bg-primary-800 rounded animate-pulse" />
      </Card>
    );
  }

  return (
    <Card padding="md" className={className}>
      <div className="flex items-center justify-between mb-3">
        <div className="flex items-center gap-2">
          <Layers className="w-4 h-4 text-primary-600 dark:text-primary-200" />
          <h3 className="text-sm font-semibold text-primary-900 dark:text-neutral-50">Balance by Level</h3>
        </div>
      </div>
      
      <div className="h-32">
        <ResponsiveContainer width="100%" height="100%">
          <Treemap
            data={treemapData}
            dataKey="size"
            aspectRatio={4 / 3}
            stroke="#fff"
            strokeWidth={2}
          >
            {treemapData.map((entry, index) => (
              <Cell key={`cell-${index}`} fill={entry.color} />
            ))}
            <Tooltip
              content={({ payload }) => {
                if (payload && payload.length > 0) {
                  const item = payload[0].payload;
                  return (
                    <div className="bg-white dark:bg-primary-900 p-2 rounded shadow-lg border border-neutral-200 dark:border-primary-800 text-xs">
                      <p className="font-medium text-primary-900 dark:text-neutral-50">{item.name}</p>
                      <p className="text-neutral-600 dark:text-neutral-300">{item.size} nodes</p>
                      <p className="text-primary-600 dark:text-primary-200 font-medium">
                        {formatCompactCurrency(item.balance, 'AED')}
                      </p>
                    </div>
                  );
                }
                return null;
              }}
            />
          </Treemap>
        </ResponsiveContainer>
      </div>

      <div className="mt-3 flex items-center justify-between text-xs">
        <span className="text-neutral-500 dark:text-neutral-400 dark:text-neutral-500">7 levels • 543 nodes</span>
        <span className="text-primary-600 dark:text-primary-200 font-medium">
          {formatCompactCurrency(125000000, 'AED')}
        </span>
      </div>
    </Card>
  );
};

// ============================================================================
// TOP ENTITIES WIDGET
// ============================================================================

interface TopEntitiesWidgetProps {
  programId?: string;
  limit?: number;
  className?: string;
}

export const TopEntitiesWidget: React.FC<TopEntitiesWidgetProps> = ({
  programId,
  limit = 5,
  className,
}) => {
  const [data, setData] = useState<TopEntity[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const fetchData = async () => {
      try {
        setLoading(true);
        // Mock data
        setData([
          { id: '1', name: 'UAE Operations', code: 'UAE-OPS', path: 'GCC > UAE', balance: 45000000, change: 5.2, currencyCode: 'AED' },
          { id: '2', name: 'Saudi Branch', code: 'KSA-BR1', path: 'GCC > KSA', balance: 32000000, change: -2.1, currencyCode: 'AED' },
          { id: '3', name: 'Qatar Office', code: 'QAT-HQ', path: 'GCC > Qatar', balance: 28000000, change: 8.5, currencyCode: 'AED' },
          { id: '4', name: 'Egypt Division', code: 'EGY-DIV', path: 'MENA > Egypt', balance: 15000000, change: 1.3, currencyCode: 'AED' },
          { id: '5', name: 'Kuwait Branch', code: 'KWT-BR', path: 'GCC > Kuwait', balance: 5000000, change: -0.5, currencyCode: 'AED' },
        ]);
      } catch (err) {
        console.error('Failed to load top entities:', err);
      } finally {
        setLoading(false);
      }
    };
    fetchData();
  }, [programId, limit]);

  if (loading) {
    return (
      <Card padding="md" className={className}>
        <div className="h-5 bg-neutral-200 rounded w-32 mb-4 animate-pulse dark:bg-primary-800" />
        <div className="space-y-3">
          {[1, 2, 3, 4, 5].map((i) => (
            <div key={i} className="h-10 bg-neutral-100 dark:bg-primary-800 rounded animate-pulse" />
          ))}
        </div>
      </Card>
    );
  }

  return (
    <Card padding="md" className={className}>
      <div className="flex items-center justify-between mb-3">
        <div className="flex items-center gap-2">
          <Building2 className="w-4 h-4 text-primary-600 dark:text-primary-200" />
          <h3 className="text-sm font-semibold text-primary-900 dark:text-neutral-50">Top Entities</h3>
        </div>
      </div>

      <div className="space-y-2">
        {data.slice(0, limit).map((entity, index) => (
          <div
            key={entity.id}
            className="flex items-center gap-2 p-2 rounded-lg hover:bg-neutral-50 dark:hover:bg-primary-800/50 dark:bg-primary-950 cursor-pointer transition-colors"
          >
            <div className={cn(
              'w-6 h-6 rounded-full flex items-center justify-center text-xs font-bold',
              index === 0 ? 'bg-amber-100 dark:bg-amber-500/20 text-amber-700 dark:text-amber-300' :
              index === 1 ? 'bg-neutral-200 text-neutral-600 dark:text-neutral-300 dark:bg-primary-800' :
              index === 2 ? 'bg-warning-100 dark:bg-warning-500/20 text-warning-700 dark:text-warning-300' :
              'bg-neutral-100 dark:bg-primary-800 text-neutral-500 dark:text-neutral-400 dark:text-neutral-500'
            )}>
              {index + 1}
            </div>
            <div className="flex-1 min-w-0">
              <p className="text-xs font-medium text-primary-900 dark:text-neutral-50 truncate">{entity.name}</p>
              <p className="text-xs text-neutral-400 dark:text-neutral-500 truncate">{entity.path}</p>
            </div>
            <div className="text-right">
              <p className="text-xs font-semibold text-primary-900 dark:text-neutral-50">
                {formatCompactCurrency(entity.balance, entity.currencyCode)}
              </p>
              <div className="flex items-center justify-end">
                {entity.change >= 0 ? (
                  <ArrowUpRight className="w-3 h-3 text-success-500" />
                ) : (
                  <ArrowDownRight className="w-3 h-3 text-error-500" />
                )}
                <span className={cn(
                  'text-xs',
                  entity.change >= 0 ? 'text-success-600 dark:text-success-300' : 'text-error-600 dark:text-error-300'
                )}>
                  {Math.abs(entity.change)}%
                </span>
              </div>
            </div>
          </div>
        ))}
      </div>
    </Card>
  );
};

// ============================================================================
// VIBAN COLLECTIONS WIDGET
// ============================================================================

interface VibanCollectionsWidgetProps {
  programId?: string;
  className?: string;
}

export const VibanCollectionsWidget: React.FC<VibanCollectionsWidgetProps> = ({
  programId,
  className,
}) => {
  const [stats, setStats] = useState<VibanStats | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const fetchData = async () => {
      try {
        setLoading(true);
        // Mock data
        setStats({
          todayAmount: 2450000,
          todayCount: 156,
          activeVibans: 1240,
          pendingMatching: 12,
          averageAmount: 15705,
          currencyCode: 'AED',
        });
      } catch (err) {
        console.error('Failed to load VIBAN stats:', err);
      } finally {
        setLoading(false);
      }
    };
    fetchData();
  }, [programId]);

  if (loading || !stats) {
    return (
      <Card padding="md" className={className}>
        <div className="h-5 bg-neutral-200 rounded w-32 mb-4 animate-pulse dark:bg-primary-800" />
        <div className="space-y-3">
          <div className="h-16 bg-neutral-100 dark:bg-primary-800 rounded animate-pulse" />
          <div className="grid grid-cols-2 gap-2">
            <div className="h-12 bg-neutral-100 dark:bg-primary-800 rounded animate-pulse" />
            <div className="h-12 bg-neutral-100 dark:bg-primary-800 rounded animate-pulse" />
          </div>
        </div>
      </Card>
    );
  }

  return (
    <Card padding="md" className={className}>
      <div className="flex items-center justify-between mb-3">
        <div className="flex items-center gap-2">
          <CreditCard className="w-4 h-4 text-success-600 dark:text-success-300" />
          <h3 className="text-sm font-semibold text-primary-900 dark:text-neutral-50">VIBAN Collections</h3>
        </div>
        <Badge variant="success" size="sm">Today</Badge>
      </div>

      <div className="bg-success-50 rounded-lg p-3 mb-3 dark:bg-success-500/10">
        <p className="text-xs text-success-600 mb-1 dark:text-success-300">Collections Today</p>
        <p className="text-xl font-bold text-success-700 dark:text-success-300">
          {formatCurrency(stats.todayAmount, stats.currencyCode)}
        </p>
        <p className="text-xs text-success-600 dark:text-success-300">{stats.todayCount} transactions</p>
      </div>

      <div className="grid grid-cols-2 gap-2">
        <div className="bg-neutral-50 dark:bg-primary-950 rounded-lg p-2">
          <p className="text-xs text-neutral-500 dark:text-neutral-400 dark:text-neutral-500">Active VIBANs</p>
          <p className="text-sm font-semibold text-primary-900 dark:text-neutral-50">{stats.activeVibans.toLocaleString()}</p>
        </div>
        <div className="bg-neutral-50 dark:bg-primary-950 rounded-lg p-2">
          <p className="text-xs text-neutral-500 dark:text-neutral-400 dark:text-neutral-500">Pending Match</p>
          <p className={cn(
            'text-sm font-semibold',
            stats.pendingMatching > 0 ? 'text-warning-600 dark:text-warning-300' : 'text-success-600 dark:text-success-300'
          )}>
            {stats.pendingMatching}
          </p>
        </div>
      </div>
    </Card>
  );
};

// ============================================================================
// POBO ACTIVITY WIDGET
// ============================================================================

interface PoboActivityWidgetProps {
  programId?: string;
  className?: string;
}

export const PoboActivityWidget: React.FC<PoboActivityWidgetProps> = ({
  programId,
  className,
}) => {
  const [stats, setStats] = useState<PoboStats | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const fetchData = async () => {
      try {
        setLoading(true);
        // Mock data
        setStats({
          todayPoboAmount: 1850000,
          todayPoboCount: 23,
          pendingRecharge: 5,
          monthlyPobo: 45000000,
          activeAuthorizations: 18,
          currencyCode: 'AED',
        });
      } catch (err) {
        console.error('Failed to load POBO stats:', err);
      } finally {
        setLoading(false);
      }
    };
    fetchData();
  }, [programId]);

  if (loading || !stats) {
    return (
      <Card padding="md" className={className}>
        <div className="h-5 bg-neutral-200 rounded w-32 mb-4 animate-pulse dark:bg-primary-800" />
        <div className="space-y-3">
          <div className="h-16 bg-neutral-100 dark:bg-primary-800 rounded animate-pulse" />
          <div className="grid grid-cols-2 gap-2">
            <div className="h-12 bg-neutral-100 dark:bg-primary-800 rounded animate-pulse" />
            <div className="h-12 bg-neutral-100 dark:bg-primary-800 rounded animate-pulse" />
          </div>
        </div>
      </Card>
    );
  }

  return (
    <Card padding="md" className={className}>
      <div className="flex items-center justify-between mb-3">
        <div className="flex items-center gap-2">
          <Send className="w-4 h-4 text-info-600 dark:text-info-300" />
          <h3 className="text-sm font-semibold text-primary-900 dark:text-neutral-50">POBO Activity</h3>
        </div>
        <Badge variant="info" size="sm">Today</Badge>
      </div>

      <div className="bg-info-50 rounded-lg p-3 mb-3 dark:bg-info-500/10">
        <p className="text-xs text-info-600 mb-1 dark:text-info-300">POBO Payments Today</p>
        <p className="text-xl font-bold text-info-700 dark:text-info-300">
          {formatCurrency(stats.todayPoboAmount, stats.currencyCode)}
        </p>
        <p className="text-xs text-info-600 dark:text-info-300">{stats.todayPoboCount} payments</p>
      </div>

      <div className="grid grid-cols-2 gap-2">
        <div className="bg-neutral-50 dark:bg-primary-950 rounded-lg p-2">
          <p className="text-xs text-neutral-500 dark:text-neutral-400 dark:text-neutral-500">Pending Recharge</p>
          <p className={cn(
            'text-sm font-semibold',
            stats.pendingRecharge > 0 ? 'text-warning-600 dark:text-warning-300' : 'text-success-600 dark:text-success-300'
          )}>
            {stats.pendingRecharge}
          </p>
        </div>
        <div className="bg-neutral-50 dark:bg-primary-950 rounded-lg p-2">
          <p className="text-xs text-neutral-500 dark:text-neutral-400 dark:text-neutral-500">Authorizations</p>
          <p className="text-sm font-semibold text-primary-900 dark:text-neutral-50">{stats.activeAuthorizations}</p>
        </div>
      </div>
    </Card>
  );
};

export default {
  BalanceByLevelWidget,
  TopEntitiesWidget,
  VibanCollectionsWidget,
  PoboActivityWidget,
};