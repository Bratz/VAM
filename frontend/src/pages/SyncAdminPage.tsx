import React, { useState, useEffect } from 'react';
import { RefreshCw, Play, Clock, CheckCircle, XCircle, Loader2, AlertCircle, List } from 'lucide-react';
import { Card, Button, Badge } from '../components/ui';
import { syncAdminApi } from '../services/api';
import { PageHeader } from '../components/layout/PageHeader';

const SyncAdminPage: React.FC = () => {
  const [jobs, setJobs] = useState<any[]>([]);
  const [queue, setQueue] = useState<any[]>([]);
  const [stats, setStats] = useState<any>(null);
  const [logs, setLogs] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [processing, setProcessing] = useState<string | null>(null);

  const fetchData = async () => {
    try {
      setLoading(true);
      setError(null);
      const [jobsRes, queueRes, statsRes, logsRes] = await Promise.all([
        syncAdminApi.getJobs(),
        syncAdminApi.getQueue(),
        syncAdminApi.getStats(),
        syncAdminApi.getLogs()
      ]);
      if (jobsRes.success) setJobs(jobsRes.data);
      if (queueRes.success) setQueue(queueRes.data);
      if (statsRes.success) setStats(statsRes.data);
      if (logsRes.success) setLogs(logsRes.data);
    } catch (err: any) {
      setError(err.message || 'Failed to load sync data');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { fetchData(); }, []);

  const triggerJob = async (jobType: string) => {
    setProcessing(jobType);
    try {
      await syncAdminApi.triggerJob(jobType);
      await fetchData();
    } finally {
      setProcessing(null);
    }
  };

  const getStatusBadge = (status: string) => {
    const variants: Record<string, 'success' | 'warning' | 'error' | 'neutral'> = {
      RUNNING: 'warning', COMPLETED: 'success', FAILED: 'error', SCHEDULED: 'neutral'
    };
    return <Badge variant={variants[status] || 'neutral'}>{status}</Badge>;
  };

  const getLogLevelClass = (level: string) => {
    const classes: Record<string, string> = {
      INFO: 'text-info-600 dark:text-info-300', WARN: 'text-warning-600 dark:text-warning-300', ERROR: 'text-error-600 dark:text-error-300'
    };
    return classes[level] || 'text-neutral-600 dark:text-neutral-300';
  };

  if (loading) {
    return (
      <div className="flex items-center justify-center py-12">
        <Loader2 className="w-8 h-8 animate-spin text-primary-500" />
        <span className="ml-3">Loading sync status...</span>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      {/* Header */}
      <PageHeader
        title="Sync Administration"
        description="Monitor and manage data synchronization jobs"
        actions={<Button variant="outline" leftIcon={<RefreshCw className="w-4 h-4" />} onClick={fetchData}>Refresh</Button>}
      />

      {/* Stats */}
      {stats && (
        <div className="grid grid-cols-5 gap-4">
          <Card><div className="p-4"><p className="stat-value-sm">{stats.jobsToday}</p><p className="text-sm text-neutral-500 dark:text-neutral-400">Jobs Today</p></div></Card>
          <Card><div className="p-4"><p className="stat-value-success">{stats.successRate}%</p><p className="text-sm text-neutral-500 dark:text-neutral-400">Success Rate</p></div></Card>
          <Card><div className="p-4"><p className="stat-value-sm">{stats.averageDuration}</p><p className="text-sm text-neutral-500 dark:text-neutral-400">Avg Duration</p></div></Card>
          <Card><div className="p-4"><p className="stat-value-warning">{stats.queueDepth}</p><p className="text-sm text-neutral-500 dark:text-neutral-400">Queue Depth</p></div></Card>
          <Card><div className="p-4"><p className="stat-value-error">{stats.failedJobs24h}</p><p className="text-sm text-neutral-500 dark:text-neutral-400">Failed (24h)</p></div></Card>
        </div>
      )}

      {error && (
        <Card className="bg-error-50 dark:bg-error-500/10 border-error-200 dark:border-error-500/30">
          <div className="flex items-center gap-3 p-4">
            <AlertCircle className="w-5 h-5 text-error-600 dark:text-error-300" />
            <span className="text-error-800 dark:text-error-300">{error}</span>
          </div>
        </Card>
      )}

      {/* Quick Actions */}
      <Card>
        <div className="p-4 border-b">
          <h3 className="font-medium">Quick Sync Actions</h3>
        </div>
        <div className="p-4 grid grid-cols-4 gap-4">
          {['BALANCE_SYNC', 'TRANSACTION_SYNC', 'CORPORATE_SYNC', 'ACCOUNT_SYNC'].map(jobType => (
            <Button 
              key={jobType} 
              variant="outline" 
              onClick={() => triggerJob(jobType)}
              disabled={processing === jobType}
              className="justify-start"
            >
              {processing === jobType ? <Loader2 className="w-4 h-4 animate-spin mr-2" /> : <Play className="w-4 h-4 mr-2" />}
              {jobType.replace('_', ' ')}
            </Button>
          ))}
        </div>
      </Card>

      <div className="grid grid-cols-2 gap-6">
        {/* Recent Jobs */}
        <Card>
          <div className="p-4 border-b">
            <h3 className="font-medium">Recent Jobs</h3>
          </div>
          <div className="divide-y max-h-96 overflow-y-auto">
            {jobs.map(job => (
              <div key={job.id} className="p-4 flex items-center justify-between">
                <div>
                  <p className="font-medium text-sm">{job.jobType.replace('_', ' ')}</p>
                  <p className="text-xs text-neutral-500 dark:text-neutral-400">{new Date(job.startedAt).toLocaleString()}</p>
                </div>
                <div className="text-right">
                  {getStatusBadge(job.status)}
                  <p className="text-xs text-neutral-500 dark:text-neutral-400 mt-1">{job.recordsProcessed} records</p>
                </div>
              </div>
            ))}
          </div>
        </Card>

        {/* Queue */}
        <Card>
          <div className="p-4 border-b">
            <h3 className="font-medium">Sync Queue ({queue.length})</h3>
          </div>
          <div className="divide-y max-h-96 overflow-y-auto">
            {queue.map(item => (
              <div key={item.id} className="p-4 flex items-center justify-between">
                <div>
                  <p className="font-medium text-sm">{item.type.replace('_', ' ')}</p>
                  <p className="text-xs text-neutral-500 dark:text-neutral-400">Retry: {item.retryCount}</p>
                </div>
                <Badge variant={item.priority === 'HIGH' ? 'error' : 'neutral'}>{item.priority}</Badge>
              </div>
            ))}
            {queue.length === 0 && (
              <div className="p-8 text-center text-neutral-500 dark:text-neutral-400">Queue is empty</div>
            )}
          </div>
        </Card>
      </div>

      {/* Logs */}
      <Card>
        <div className="p-4 border-b flex items-center justify-between">
          <h3 className="font-medium">Sync Logs</h3>
          <List className="w-4 h-4 text-neutral-400 dark:text-neutral-500" />
        </div>
        <div className="divide-y max-h-80 overflow-y-auto font-mono text-sm">
          {logs.map((log, idx) => (
            <div key={idx} className="p-3 flex gap-4">
              <span className="text-neutral-400 dark:text-neutral-500 whitespace-nowrap">{new Date(log.timestamp).toLocaleTimeString()}</span>
              <span className={`font-medium w-12 ${getLogLevelClass(log.level)}`}>{log.level}</span>
              <span className="text-neutral-600 dark:text-neutral-300">{log.message}</span>
              {log.details && <span className="text-error-600 dark:text-error-300">{log.details}</span>}
            </div>
          ))}
        </div>
      </Card>
    </div>
  );
};

export default SyncAdminPage;
