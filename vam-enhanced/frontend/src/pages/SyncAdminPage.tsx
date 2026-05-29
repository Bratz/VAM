import React, { useState, useEffect } from 'react';
import {
  Server,
  RefreshCw,
  CheckCircle,
  XCircle,
  Clock,
  AlertTriangle,
  Activity,
  Database,
  Zap,
  Play,
  Pause,
  RotateCcw,
  Filter,
  Download,
  Settings,
  Wifi,
  WifiOff,
  Timer,
  ArrowUpRight,
  ArrowDownRight,
} from 'lucide-react';
import { Card, CardHeader, Button, Badge, Input } from '../ui';
import { Modal, Tabs, ProgressBar, Alert } from '../ui/enhanced';
import { formatDate, cn } from '../../utils';

// Types
interface SyncQueueItem {
  id: string;
  operationType: string;
  entityType: string;
  entityId: string;
  status: 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED' | 'CANCELLED';
  retryCount: number;
  maxRetries: number;
  priority: number;
  errorMessage?: string;
  errorCode?: string;
  createdAt: string;
  nextRetryAt?: string;
  processedAt?: string;
  processingDurationMs?: number;
}

interface HealthStatus {
  isAvailable: boolean;
  consecutiveFailures: number;
  lastCheckTime: string;
  lastSuccessTime?: string;
  averageResponseTimeMs: number;
}

interface QueueStatistics {
  pendingCount: number;
  processingCount: number;
  failedCount: number;
  completedToday: number;
  oldestPendingTime?: string;
  averageProcessingTime: number;
}

// Mock data
const mockHealthStatus: HealthStatus = {
  isAvailable: true,
  consecutiveFailures: 0,
  lastCheckTime: new Date().toISOString(),
  lastSuccessTime: new Date(Date.now() - 30000).toISOString(),
  averageResponseTimeMs: 245,
};

const mockQueueStats: QueueStatistics = {
  pendingCount: 12,
  processingCount: 2,
  failedCount: 3,
  completedToday: 156,
  oldestPendingTime: new Date(Date.now() - 600000).toISOString(), // 10 mins ago
  averageProcessingTime: 1250,
};

const mockQueueItems: SyncQueueItem[] = [
  {
    id: '1',
    operationType: 'CREATE_VIRTUAL_ACCOUNT',
    entityType: 'VirtualAccount',
    entityId: 'VA-2024-001234',
    status: 'PENDING',
    retryCount: 0,
    maxRetries: 5,
    priority: 2,
    createdAt: new Date(Date.now() - 120000).toISOString(),
    nextRetryAt: new Date().toISOString(),
  },
  {
    id: '2',
    operationType: 'INITIATE_PAYMENT',
    entityType: 'Transaction',
    entityId: 'TXN-2024-005678',
    status: 'PROCESSING',
    retryCount: 1,
    maxRetries: 5,
    priority: 1,
    createdAt: new Date(Date.now() - 180000).toISOString(),
  },
  {
    id: '3',
    operationType: 'CREATE_BENEFICIARY',
    entityType: 'Beneficiary',
    entityId: 'BENE-2024-009012',
    status: 'FAILED',
    retryCount: 3,
    maxRetries: 5,
    priority: 3,
    errorMessage: 'Connection timeout after 30000ms',
    errorCode: 'TIMEOUT',
    createdAt: new Date(Date.now() - 600000).toISOString(),
    nextRetryAt: new Date(Date.now() + 240000).toISOString(),
  },
  {
    id: '4',
    operationType: 'MODIFY_WHITELIST',
    entityType: 'Whitelist',
    entityId: 'WL-2024-003456',
    status: 'COMPLETED',
    retryCount: 0,
    maxRetries: 5,
    priority: 4,
    createdAt: new Date(Date.now() - 300000).toISOString(),
    processedAt: new Date(Date.now() - 298000).toISOString(),
    processingDurationMs: 1850,
  },
  {
    id: '5',
    operationType: 'CLOSE_VIRTUAL_ACCOUNT',
    entityType: 'VirtualAccount',
    entityId: 'VA-2024-007890',
    status: 'FAILED',
    retryCount: 5,
    maxRetries: 5,
    priority: 2,
    errorMessage: 'Max retries exceeded. Manual intervention required.',
    errorCode: 'MAX_RETRIES',
    createdAt: new Date(Date.now() - 900000).toISOString(),
  },
];

// Status configurations
const statusConfig = {
  PENDING: { label: 'Pending', color: 'warning', icon: <Clock className="w-4 h-4" /> },
  PROCESSING: { label: 'Processing', color: 'info', icon: <RefreshCw className="w-4 h-4 animate-spin" /> },
  COMPLETED: { label: 'Completed', color: 'success', icon: <CheckCircle className="w-4 h-4" /> },
  FAILED: { label: 'Failed', color: 'error', icon: <XCircle className="w-4 h-4" /> },
  CANCELLED: { label: 'Cancelled', color: 'neutral', icon: <XCircle className="w-4 h-4" /> },
};

const operationLabels: Record<string, string> = {
  CREATE_VIRTUAL_ACCOUNT: 'Create Virtual Account',
  CLOSE_VIRTUAL_ACCOUNT: 'Close Virtual Account',
  CREATE_BENEFICIARY: 'Create Beneficiary',
  MODIFY_BENEFICIARY: 'Modify Beneficiary',
  DELETE_BENEFICIARY: 'Delete Beneficiary',
  CREATE_WHITELIST: 'Create Whitelist',
  MODIFY_WHITELIST: 'Modify Whitelist',
  INITIATE_PAYMENT: 'Initiate Payment',
  GET_BALANCE: 'Get Balance',
  GET_STATEMENT: 'Get Statement',
};

const priorityLabels: Record<number, { label: string; color: string }> = {
  1: { label: 'Critical', color: 'error' },
  2: { label: 'High', color: 'warning' },
  3: { label: 'Medium', color: 'info' },
  4: { label: 'Low', color: 'neutral' },
  5: { label: 'Lowest', color: 'neutral' },
};

// Health Status Component
const HealthStatusCard: React.FC<{ status: HealthStatus; onRefresh: () => void }> = ({ status, onRefresh }) => (
  <Card className={cn(
    'border-2',
    status.isAvailable ? 'border-success-200 bg-success-50/30' : 'border-error-200 bg-error-50/30'
  )}>
    <div className="flex items-center justify-between mb-4">
      <div className="flex items-center gap-3">
        {status.isAvailable ? (
          <div className="w-12 h-12 rounded-xl bg-success-100 flex items-center justify-center">
            <Wifi className="w-6 h-6 text-success-600" />
          </div>
        ) : (
          <div className="w-12 h-12 rounded-xl bg-error-100 flex items-center justify-center">
            <WifiOff className="w-6 h-6 text-error-600" />
          </div>
        )}
        <div>
          <h3 className="text-heading-md text-primary-900">BaNCS Connection</h3>
          <p className={cn(
            'text-body-sm font-medium',
            status.isAvailable ? 'text-success-600' : 'text-error-600'
          )}>
            {status.isAvailable ? 'Connected & Healthy' : 'Disconnected'}
          </p>
        </div>
      </div>
      <Button variant="outline" size="sm" leftIcon={<RefreshCw className="w-4 h-4" />} onClick={onRefresh}>
        Check Now
      </Button>
    </div>

    <div className="grid grid-cols-4 gap-4">
      <div className="p-3 bg-white rounded-lg">
        <p className="text-caption text-neutral-500">Status</p>
        <div className="flex items-center gap-2 mt-1">
          <span className={cn(
            'w-2 h-2 rounded-full',
            status.isAvailable ? 'bg-success-500' : 'bg-error-500'
          )} />
          <span className="text-body-sm font-medium text-primary-900">
            {status.isAvailable ? 'Online' : 'Offline'}
          </span>
        </div>
      </div>
      <div className="p-3 bg-white rounded-lg">
        <p className="text-caption text-neutral-500">Consecutive Failures</p>
        <p className={cn(
          'text-heading-md mt-1',
          status.consecutiveFailures === 0 ? 'text-success-600' : 'text-error-600'
        )}>
          {status.consecutiveFailures}
        </p>
      </div>
      <div className="p-3 bg-white rounded-lg">
        <p className="text-caption text-neutral-500">Avg Response Time</p>
        <p className="text-heading-md text-primary-900 mt-1">
          {status.averageResponseTimeMs}ms
        </p>
      </div>
      <div className="p-3 bg-white rounded-lg">
        <p className="text-caption text-neutral-500">Last Check</p>
        <p className="text-body-sm text-primary-900 mt-1">
          {new Date(status.lastCheckTime).toLocaleTimeString()}
        </p>
      </div>
    </div>
  </Card>
);

// Queue Stats Component
const QueueStatsCard: React.FC<{ stats: QueueStatistics }> = ({ stats }) => (
  <div className="grid grid-cols-1 md:grid-cols-5 gap-4">
    <Card padding="sm" className="border-l-4 border-warning-500">
      <div className="flex items-center justify-between">
        <div>
          <p className="text-body-sm text-neutral-500">Pending</p>
          <p className="text-heading-xl text-warning-600">{stats.pendingCount}</p>
        </div>
        <Clock className="w-8 h-8 text-warning-300" />
      </div>
    </Card>
    <Card padding="sm" className="border-l-4 border-info-500">
      <div className="flex items-center justify-between">
        <div>
          <p className="text-body-sm text-neutral-500">Processing</p>
          <p className="text-heading-xl text-info-600">{stats.processingCount}</p>
        </div>
        <RefreshCw className="w-8 h-8 text-info-300 animate-spin" />
      </div>
    </Card>
    <Card padding="sm" className="border-l-4 border-error-500">
      <div className="flex items-center justify-between">
        <div>
          <p className="text-body-sm text-neutral-500">Failed</p>
          <p className="text-heading-xl text-error-600">{stats.failedCount}</p>
        </div>
        <AlertTriangle className="w-8 h-8 text-error-300" />
      </div>
    </Card>
    <Card padding="sm" className="border-l-4 border-success-500">
      <div className="flex items-center justify-between">
        <div>
          <p className="text-body-sm text-neutral-500">Completed Today</p>
          <p className="text-heading-xl text-success-600">{stats.completedToday}</p>
        </div>
        <CheckCircle className="w-8 h-8 text-success-300" />
      </div>
    </Card>
    <Card padding="sm" className="border-l-4 border-primary-500">
      <div className="flex items-center justify-between">
        <div>
          <p className="text-body-sm text-neutral-500">Avg Processing</p>
          <p className="text-heading-xl text-primary-900">{(stats.averageProcessingTime / 1000).toFixed(1)}s</p>
        </div>
        <Timer className="w-8 h-8 text-primary-300" />
      </div>
    </Card>
  </div>
);

// Queue Item Row
const QueueItemRow: React.FC<{
  item: SyncQueueItem;
  onRetry: () => void;
  onCancel: () => void;
}> = ({ item, onRetry, onCancel }) => {
  const status = statusConfig[item.status];
  const priority = priorityLabels[item.priority] || priorityLabels[5];
  const canRetry = item.status === 'FAILED' && item.retryCount < item.maxRetries;

  return (
    <tr className="hover:bg-neutral-50 transition-colors">
      <td className="px-6 py-4">
        <Badge variant={priority.color as any} size="sm">
          P{item.priority}
        </Badge>
      </td>
      <td className="px-6 py-4">
        <p className="text-body-sm font-medium text-primary-900">
          {operationLabels[item.operationType] || item.operationType}
        </p>
        <p className="text-caption text-neutral-500">{item.entityType}</p>
      </td>
      <td className="px-6 py-4">
        <p className="text-body-sm font-mono text-primary-900">{item.entityId}</p>
      </td>
      <td className="px-6 py-4">
        <Badge variant={status.color as any} className="flex items-center gap-1.5 w-fit">
          {status.icon}
          {status.label}
        </Badge>
        {item.status === 'FAILED' && item.errorCode && (
          <p className="text-caption text-error-600 mt-1">{item.errorCode}</p>
        )}
      </td>
      <td className="px-6 py-4">
        <div className="flex items-center gap-1">
          <span className={cn(
            'text-body-sm font-medium',
            item.retryCount >= item.maxRetries ? 'text-error-600' : 'text-primary-900'
          )}>
            {item.retryCount}
          </span>
          <span className="text-caption text-neutral-500">/ {item.maxRetries}</span>
        </div>
        <ProgressBar
          value={item.retryCount}
          max={item.maxRetries}
          size="sm"
          variant={item.retryCount >= item.maxRetries ? 'error' : 'default'}
          className="w-16 mt-1"
        />
      </td>
      <td className="px-6 py-4">
        <p className="text-body-sm text-neutral-600">
          {new Date(item.createdAt).toLocaleString()}
        </p>
        {item.nextRetryAt && item.status === 'PENDING' && (
          <p className="text-caption text-neutral-500">
            Next: {new Date(item.nextRetryAt).toLocaleTimeString()}
          </p>
        )}
      </td>
      <td className="px-6 py-4">
        <div className="flex items-center gap-1">
          {canRetry && (
            <Button variant="ghost" size="sm" onClick={onRetry}>
              <RotateCcw className="w-4 h-4" />
            </Button>
          )}
          {(item.status === 'PENDING' || item.status === 'FAILED') && (
            <Button variant="ghost" size="sm" onClick={onCancel}>
              <XCircle className="w-4 h-4 text-error-500" />
            </Button>
          )}
        </div>
      </td>
    </tr>
  );
};

// Main Sync Admin Page
const SyncAdminPage: React.FC = () => {
  const [healthStatus, setHealthStatus] = useState(mockHealthStatus);
  const [queueStats, setQueueStats] = useState(mockQueueStats);
  const [queueItems, setQueueItems] = useState(mockQueueItems);
  const [activeTab, setActiveTab] = useState('all');
  const [isProcessing, setIsProcessing] = useState(false);

  const tabs = [
    { id: 'all', label: 'All', badge: queueItems.length },
    { id: 'pending', label: 'Pending', badge: queueItems.filter(i => i.status === 'PENDING').length },
    { id: 'processing', label: 'Processing', badge: queueItems.filter(i => i.status === 'PROCESSING').length },
    { id: 'failed', label: 'Failed', badge: queueItems.filter(i => i.status === 'FAILED').length },
  ];

  const filteredItems = queueItems.filter(item => {
    if (activeTab === 'all') return true;
    return item.status === activeTab.toUpperCase();
  });

  const handleProcessNow = () => {
    setIsProcessing(true);
    setTimeout(() => setIsProcessing(false), 2000);
  };

  const handleRetryFailed = () => {
    console.log('Retrying failed items');
  };

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
        <div>
          <h1 className="text-display-md text-primary-900">BaNCS Sync Monitor</h1>
          <p className="text-body-md text-neutral-500 mt-1">
            Monitor and manage BaNCS synchronization queue
          </p>
        </div>
        <div className="flex gap-2">
          <Button 
            variant="outline" 
            leftIcon={<RotateCcw className="w-4 h-4" />}
            onClick={handleRetryFailed}
          >
            Retry Failed
          </Button>
          <Button 
            leftIcon={isProcessing ? <RefreshCw className="w-4 h-4 animate-spin" /> : <Play className="w-4 h-4" />}
            onClick={handleProcessNow}
            disabled={isProcessing}
          >
            {isProcessing ? 'Processing...' : 'Process Now'}
          </Button>
        </div>
      </div>

      {/* Health Status */}
      <HealthStatusCard 
        status={healthStatus} 
        onRefresh={() => console.log('Refresh health')} 
      />

      {/* Unavailable Alert */}
      {!healthStatus.isAvailable && (
        <Alert variant="error" title="BaNCS Unavailable">
          BaNCS connection is currently unavailable. Operations are being queued locally and will be synchronized when the connection is restored.
          Consecutive failures: {healthStatus.consecutiveFailures}
        </Alert>
      )}

      {/* Queue Stats */}
      <QueueStatsCard stats={queueStats} />

      {/* Oldest Pending Alert */}
      {queueStats.oldestPendingTime && (
        <Alert variant="warning" title="Queue Delay">
          Oldest pending item has been waiting for {
            Math.round((Date.now() - new Date(queueStats.oldestPendingTime).getTime()) / 60000)
          } minutes. Consider triggering manual processing.
        </Alert>
      )}

      {/* Queue Table */}
      <Card padding="none">
        <div className="p-4 border-b border-neutral-200 flex items-center justify-between">
          <Tabs tabs={tabs} activeTab={activeTab} onChange={setActiveTab} variant="pills" />
          <div className="flex gap-2">
            <Button variant="outline" size="sm" leftIcon={<Filter className="w-4 h-4" />}>
              Filters
            </Button>
            <Button variant="outline" size="sm" leftIcon={<Download className="w-4 h-4" />}>
              Export
            </Button>
          </div>
        </div>

        <div className="overflow-x-auto">
          <table className="w-full">
            <thead className="bg-neutral-50 border-b border-neutral-200">
              <tr>
                <th className="px-6 py-3 text-left text-body-sm font-semibold text-neutral-600 w-20">Priority</th>
                <th className="px-6 py-3 text-left text-body-sm font-semibold text-neutral-600">Operation</th>
                <th className="px-6 py-3 text-left text-body-sm font-semibold text-neutral-600">Entity ID</th>
                <th className="px-6 py-3 text-left text-body-sm font-semibold text-neutral-600">Status</th>
                <th className="px-6 py-3 text-left text-body-sm font-semibold text-neutral-600 w-24">Retries</th>
                <th className="px-6 py-3 text-left text-body-sm font-semibold text-neutral-600">Created</th>
                <th className="px-6 py-3 text-left text-body-sm font-semibold text-neutral-600 w-24">Actions</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-neutral-100">
              {filteredItems.map(item => (
                <QueueItemRow
                  key={item.id}
                  item={item}
                  onRetry={() => console.log('Retry', item.id)}
                  onCancel={() => console.log('Cancel', item.id)}
                />
              ))}
            </tbody>
          </table>
        </div>

        {filteredItems.length === 0 && (
          <div className="p-8 text-center">
            <Database className="w-12 h-12 text-neutral-300 mx-auto mb-4" />
            <p className="text-body-md text-neutral-500">No items in queue</p>
          </div>
        )}
      </Card>

      {/* Processing Info */}
      <Card>
        <CardHeader title="Sync Configuration" subtitle="Current processing settings" />
        <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
          <div className="p-4 bg-neutral-50 rounded-lg">
            <p className="text-caption text-neutral-500">Batch Size</p>
            <p className="text-heading-md text-primary-900">10 items</p>
          </div>
          <div className="p-4 bg-neutral-50 rounded-lg">
            <p className="text-caption text-neutral-500">Process Interval</p>
            <p className="text-heading-md text-primary-900">60 seconds</p>
          </div>
          <div className="p-4 bg-neutral-50 rounded-lg">
            <p className="text-caption text-neutral-500">Max Retries</p>
            <p className="text-heading-md text-primary-900">5 attempts</p>
          </div>
          <div className="p-4 bg-neutral-50 rounded-lg">
            <p className="text-caption text-neutral-500">Backoff Strategy</p>
            <p className="text-heading-md text-primary-900">Exponential</p>
          </div>
        </div>
      </Card>
    </div>
  );
};

export default SyncAdminPage;
