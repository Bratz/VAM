// ============================================================================
// KYC COMPLIANCE PAGE - PREMIUM DESIGN SYSTEM
// ============================================================================

import React, { useState, useEffect } from 'react';
import {
  Shield, CheckCircle, XCircle, Clock, AlertTriangle, Loader2,
  AlertCircle, RefreshCw, Eye, ChevronRight,
  FileText, TrendingUp, Ban,
} from 'lucide-react';
import { Card, Button, Badge, Skeleton, StatusIconBadge, DataTable } from '../components/ui';
import { Modal } from '../components/ui/enhanced';
import { kycApi } from '../services/api';
import { cn } from '../utils';
import { Page } from '../components/layout/Page';

// ============================================================================
// STAT CARD COMPONENT
// ============================================================================

interface StatCardProps {
  title: string;
  value: string | number;
  icon: React.ReactNode;
  iconBg: string;
  loading?: boolean;
  delay?: number;
}

const StatCard: React.FC<StatCardProps> = ({ title, value, icon, iconBg, loading, delay = 0 }) => {
  if (loading) {
    return (
      <Card hover className="animate-fade-in" style={{ animationDelay: `${delay}s` }}>
        <div className="flex items-center gap-3">
          <Skeleton className="w-12 h-12 rounded-xl" />
          <div className="flex-1">
            <Skeleton className="h-3 w-20 mb-2" />
            <Skeleton className="h-7 w-16" />
          </div>
        </div>
      </Card>
    );
  }

  return (
    <Card hover className="animate-fade-in" style={{ animationDelay: `${delay}s` }}>
      <div className="flex items-center gap-3">
        <div className={cn("w-12 h-12 rounded-xl flex items-center justify-center shrink-0", iconBg)}>
          {icon}
        </div>
        <div>
          <p className="label">{title}</p>
          <p className="stat-value-sm">{value}</p>
        </div>
      </div>
    </Card>
  );
};

// ============================================================================
// KYC ROW COMPONENT (Desktop)
// ============================================================================

interface KycRowProps {
  kyc: any;
  onView: (id: string) => void;
  onApprove: (id: string) => void;
  onReject: (kyc: any) => void;
  processing: boolean;
}

const getKycRiskBadge = (level: string) => {
  const variants: Record<string, 'success' | 'warning' | 'error'> = {
    LOW: 'success', MEDIUM: 'warning', HIGH: 'error'
  };
  return <Badge variant={variants[level] || 'neutral'} size="sm">{level}</Badge>;
};

// ponytail: actions always-visible instead of hover-reveal — DataTable's
// row doesn't expose a per-row className hook for the group-hover trick
// the old hand-rolled table used.
const KycActionsCell: React.FC<Omit<KycRowProps, 'kyc'> & { kyc: any }> = ({ kyc, onView, onApprove, onReject, processing }) => (
  <div className="flex items-center justify-end gap-1" onClick={(e) => e.stopPropagation()}>
    <Button size="sm" variant="ghost" onClick={() => onView(kyc.id)}><Eye className="w-4 h-4" /></Button>
    <Button
      size="sm"
      variant="ghost"
      onClick={() => onApprove(kyc.id)}
      disabled={processing}
      className="text-success-600 hover:bg-success-50 dark:text-success-300 dark:hover:bg-success-500/10"
    >
      <CheckCircle className="w-4 h-4" />
    </Button>
    <Button
      size="sm"
      variant="ghost"
      onClick={() => onReject(kyc)}
      className="text-error-600 hover:bg-error-50 dark:text-error-300 dark:hover:bg-error-500/10"
    >
      <XCircle className="w-4 h-4" />
    </Button>
  </div>
);

// ============================================================================
// KYC MOBILE CARD
// ============================================================================

interface KycMobileCardProps {
  kyc: any;
  onView: (id: string) => void;
  index: number;
}

const KycMobileCard: React.FC<KycMobileCardProps> = ({ kyc, onView, index }) => {
  const getRiskBadge = (level: string) => {
    const variants: Record<string, 'success' | 'warning' | 'error'> = {
      LOW: 'success', MEDIUM: 'warning', HIGH: 'error'
    };
    return <Badge variant={variants[level] || 'neutral'} size="sm">{level}</Badge>;
  };

  return (
    <Card
      interactive
      hover
      onClick={() => onView(kyc.id)}
      className="animate-fade-in"
      style={{ animationDelay: `${index * 0.03}s` }}
    >
      <div className="flex items-start gap-3">
        <StatusIconBadge tone="primary" icon={Shield} size="lg" className="shrink-0 dark:bg-primary-700" />
        <div className="flex-1 min-w-0">
          <div className="flex items-start justify-between gap-2">
            <div className="min-w-0">
              {/* Phase 9 Task E: the heading no longer renders entirely in
                  monospace. The mono is now scoped to the identifier itself
                  via .code, so the H3 is sans-serif (heading) and the ref
                  inside reads as data. */}
              <h3 className="font-semibold text-primary-900 text-sm truncate dark:text-neutral-50">
                <span className="code">{kyc.applicationRef}</span>
              </h3>
              <p className="text-xs text-neutral-500 mt-0.5 dark:text-neutral-400">{kyc.entityName}</p>
            </div>
            {getRiskBadge(kyc.riskLevel)}
          </div>
          <div className="mt-3 pt-3 border-t border-neutral-100 flex items-center justify-between dark:border-primary-800/60">
            <div>
              <p className="text-xs text-neutral-500 dark:text-neutral-400">Documents</p>
              <p className="text-sm font-medium text-primary-900 dark:text-neutral-50">
                {kyc.documentsVerified}/{kyc.documentsSubmitted}
              </p>
            </div>
            <div className="text-right">
              <p className="text-xs text-neutral-500 dark:text-neutral-400">Submitted</p>
              <p className="field-label">
                {new Date(kyc.submittedAt).toLocaleDateString()}
              </p>
            </div>
          </div>
        </div>
        <ChevronRight className="w-5 h-5 text-neutral-400 shrink-0 mt-4 dark:text-neutral-500" />
      </div>
    </Card>
  );
};

// ============================================================================
// REJECT FORM
// ============================================================================

interface RejectFormProps {
  onSubmit: (reason: string) => void;
  loading: boolean;
  onCancel: () => void;
}

const RejectForm: React.FC<RejectFormProps> = ({ onSubmit, loading, onCancel }) => {
  const [reason, setReason] = useState('');

  return (
    <div className="space-y-4">
      <div>
        <label className="form-label">Rejection Reason *</label>
        <textarea
          className="w-full px-4 py-3 border border-neutral-300 rounded-xl text-sm resize-none h-24 focus:outline-none focus:ring-2 focus:ring-primary-500 focus:border-primary-500 dark:border-primary-700"
          value={reason}
          onChange={(e) => setReason(e.target.value)}
          placeholder="Enter reason for rejection..."
        />
      </div>
      <div className="flex justify-end gap-3 pt-4 border-t border-neutral-200 dark:border-primary-800">
        <Button variant="outline" onClick={onCancel}>Cancel</Button>
        <Button
          variant="danger"
          onClick={() => onSubmit(reason)}
          disabled={loading || !reason}
          leftIcon={loading ? <Loader2 className="w-4 h-4 animate-spin" /> : <XCircle className="w-4 h-4" />}
        >
          Reject Application
        </Button>
      </div>
    </div>
  );
};

// ============================================================================
// MAIN KYC PAGE
// ============================================================================

const KyccPage: React.FC = () => {
  const [pendingKyc, setPendingKyc] = useState<any[]>([]);
  const [stats, setStats] = useState<any>(null);
  const [expiring, setExpiring] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [showDetailModal, setShowDetailModal] = useState(false);
  const [showRejectModal, setShowRejectModal] = useState(false);
  const [selectedKyc, setSelectedKyc] = useState<any>(null);
  const [processing, setProcessing] = useState(false);

  // Pagination
  const [currentPage, setCurrentPage] = useState(0);
  const pageSize = 10;

  const fetchData = async () => {
    try {
      setLoading(true);
      setError(null);
      const [pendingRes, statsRes, expiringRes] = await Promise.all([
        kycApi.getPending(),
        kycApi.getStats(),
        kycApi.getExpiring(30),
      ]);
      if (pendingRes.success) setPendingKyc(pendingRes.data);
      if (statsRes.success) setStats(statsRes.data);
      if (expiringRes.success) setExpiring(expiringRes.data);
    } catch (err: any) {
      setError(err.message || 'Failed to load KYC data');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchData();
  }, []);

  const viewDetails = async (id: string) => {
    try {
      const res = await kycApi.getById(id);
      if (res.success) {
        setSelectedKyc(res.data);
        setShowDetailModal(true);
      }
    } catch (err) {
      console.error(err);
    }
  };

  const handleApprove = async (id: string) => {
    setProcessing(true);
    try {
      await kycApi.approve(id, 'Current User', 'Approved after review');
      await fetchData();
      setShowDetailModal(false);
    } finally {
      setProcessing(false);
    }
  };

  const handleReject = async (reason: string) => {
    if (!selectedKyc) return;
    setProcessing(true);
    try {
      await kycApi.reject(selectedKyc.id, 'Current User', reason, '');
      await fetchData();
      setShowRejectModal(false);
      setShowDetailModal(false);
    } finally {
      setProcessing(false);
    }
  };

  const getRiskBadge = (level: string) => {
    const variants: Record<string, 'success' | 'warning' | 'error'> = {
      LOW: 'success', MEDIUM: 'warning', HIGH: 'error'
    };
    return <Badge variant={variants[level] || 'neutral'}>{level}</Badge>;
  };

  const paginatedKyc = pendingKyc.slice(currentPage * pageSize, (currentPage + 1) * pageSize);

  // Loading State
  if (loading) {
    return (
      <Page>
        <div className="flex items-center justify-end">
          <Skeleton className="h-10 w-24" />
        </div>
        <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-5 gap-4">
          {[0, 1, 2, 3, 4].map((i) => (
            <StatCard
              key={i}
              title=""
              value=""
              icon={null}
              iconBg=""
              loading
              delay={i * 0.05}
            />
          ))}
        </div>
        <Card>
          <div className="p-6 space-y-4">
            {[0, 1, 2, 3, 4].map((i) => (
              <div key={i} className="flex items-center gap-4">
                <Skeleton className="w-10 h-10 rounded-xl" />
                <div className="flex-1 space-y-2">
                  <Skeleton className="h-4 w-1/3" />
                  <Skeleton className="h-3 w-1/4" />
                </div>
                <Skeleton className="h-6 w-16 rounded-full" />
              </div>
            ))}
          </div>
        </Card>
      </Page>
    );
  }

  return (
    <Page>
      {/* Quick Actions */}
      <div className="flex items-center justify-end">
        <Button
          variant="outline"
          leftIcon={<RefreshCw className={cn("w-4 h-4", loading && "animate-spin")} />}
          onClick={fetchData}
        >
          <span className="hidden sm:inline">Refresh</span>
        </Button>
      </div>

      {/* Stats */}
      <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-5 gap-4">
        <StatCard
          title="Total Applications"
          value={stats?.totalApplications || 0}
          icon={<FileText className="w-6 h-6 text-primary-600 dark:text-primary-200" />}
          iconBg="bg-primary-100 dark:bg-primary-700"
          delay={0.05}
        />
        <StatCard
          title="Pending Review"
          value={stats?.pendingReview || 0}
          icon={<Clock className="w-6 h-6 text-warning-600 dark:text-warning-300" />}
          iconBg="bg-warning-100 dark:bg-warning-500/20"
          delay={0.1}
        />
        <StatCard
          title="Approved"
          value={stats?.approved || 0}
          icon={<CheckCircle className="w-6 h-6 text-success-600 dark:text-success-300" />}
          iconBg="bg-success-100 dark:bg-success-500/20"
          delay={0.15}
        />
        <StatCard
          title="Rejected"
          value={stats?.rejected || 0}
          icon={<XCircle className="w-6 h-6 text-error-600 dark:text-error-300" />}
          iconBg="bg-error-100 dark:bg-error-500/20"
          delay={0.2}
        />
        <StatCard
          title="Approval Rate"
          value={`${stats?.approvalRate || 0}%`}
          icon={<TrendingUp className="w-6 h-6 text-info-600 dark:text-info-300" />}
          iconBg="bg-info-100 dark:bg-info-500/20"
          delay={0.25}
        />
      </div>

      {/* Error Banner */}
      {error && (
        <Card className="bg-error-50 border-error-200 animate-fade-in dark:bg-error-500/10 dark:border-error-500/30">
          <div className="flex items-center gap-3 p-4">
            <AlertCircle className="w-5 h-5 text-error-600 dark:text-error-300" />
            <span className="text-error-800 font-medium dark:text-error-300">{error}</span>
            <button
              onClick={() => setError(null)}
              className="ml-auto p-1 hover:bg-error-100 rounded-lg transition-colors dark:hover:bg-error-500/20"
            >
              <Ban className="w-4 h-4 text-error-600 dark:text-error-300" />
            </button>
          </div>
        </Card>
      )}

      {/* Expiring Alert */}
      {expiring.length > 0 && (
        <Card className="bg-warning-50 border-warning-200 animate-fade-in dark:bg-warning-500/10 dark:border-warning-500/30" style={{ animationDelay: '0.3s' }}>
          <div className="flex items-center gap-3">
            <StatusIconBadge tone="warning" icon={AlertTriangle} className="shrink-0 dark:bg-warning-500/20" />
            <div>
              <p className="font-semibold text-warning-800 dark:text-warning-300">KYC Expiring Soon</p>
              <p className="text-sm text-warning-700 dark:text-warning-300">{expiring.length} entities have KYC expiring within 30 days</p>
            </div>
          </div>
        </Card>
      )}

      {/* Pending KYC Table */}
      <Card className="animate-fade-in" style={{ animationDelay: '0.35s' }}>
        <div className="px-6 py-4 border-b border-neutral-200 dark:border-primary-800">
          <h3 className="font-semibold text-primary-900 dark:text-neutral-50">Pending Review ({pendingKyc.length})</h3>
        </div>

        <DataTable
          data={paginatedKyc}
          keyExtractor={(kyc) => kyc.id}
          onRowClick={(kyc) => viewDetails(kyc.id)}
          pagination
          pageSize={pageSize}
          currentPage={currentPage + 1}
          totalCount={pendingKyc.length}
          onPageChange={(p) => setCurrentPage(p - 1)}
          mobileCardRenderer={(kyc, idx) => (
            <KycMobileCard kyc={kyc} onView={viewDetails} index={idx} />
          )}
          emptyIcon={<Shield className="w-12 h-12" />}
          emptyTitle="No pending applications"
          emptyDescription="All KYC applications have been processed."
          columns={[
            {
              key: 'applicationRef',
              header: 'Application',
              render: (_, kyc) => (
                <div className="flex items-center gap-3">
                  <StatusIconBadge tone="primary" icon={Shield} className="shrink-0 dark:bg-primary-700" />
                  <div className="min-w-0">
                    <p className="text-sm font-semibold text-primary-900 font-mono truncate dark:text-neutral-50">{kyc.applicationRef}</p>
                    <p className="text-xs text-neutral-500 mt-0.5 dark:text-neutral-400">{new Date(kyc.submittedAt).toLocaleDateString()}</p>
                  </div>
                </div>
              ),
            },
            { key: 'entityName', header: 'Entity', render: (_, kyc) => <p className="text-sm font-medium text-primary-900 dark:text-neutral-50">{kyc.entityName}</p> },
            { key: 'entityType', header: 'Type', render: (_, kyc) => <Badge variant="neutral" size="sm">{kyc.entityType}</Badge> },
            { key: 'riskLevel', header: 'Risk Level', render: (_, kyc) => getKycRiskBadge(kyc.riskLevel) },
            {
              key: 'documentsVerified',
              header: 'Documents',
              render: (_, kyc) => <span className="text-sm font-medium text-primary-900 dark:text-neutral-50">{kyc.documentsVerified}/{kyc.documentsSubmitted}</span>,
            },
            {
              key: 'actions',
              header: 'Actions',
              align: 'right',
              width: '8rem',
              render: (_, kyc) => (
                <KycActionsCell
                  kyc={kyc}
                  onView={viewDetails}
                  onApprove={handleApprove}
                  onReject={(k) => { setSelectedKyc(k); setShowRejectModal(true); }}
                  processing={processing}
                />
              ),
            },
          ]}
        />
      </Card>

      {/* Detail Modal */}
      <Modal
        isOpen={showDetailModal}
        onClose={() => setShowDetailModal(false)}
        title="KYC Application Details"
        size="lg"
      >
        {selectedKyc && (
          <div className="space-y-6">
            {/* Header */}
            <div className="flex items-center gap-4">
              <div className="w-16 h-16 rounded-2xl bg-primary-100 flex items-center justify-center dark:bg-primary-700">
                <Shield className="w-8 h-8 text-primary-600 dark:text-primary-200" />
              </div>
              <div>
                {/* Phase 9 Task E: heading is sans-serif; the application
                    reference reads as data via .code. */}
                <h3 className="section-title">
                  <span className="code">{selectedKyc.applicationRef}</span>
                </h3>
                <div className="flex items-center gap-2 mt-1">
                  <Badge variant="warning">{selectedKyc.status?.replace('_', ' ')}</Badge>
                  {getRiskBadge(selectedKyc.riskLevel)}
                </div>
              </div>
            </div>

            {/* Details Grid */}
            <div className="grid grid-cols-2 gap-4 pt-4 border-t border-neutral-200 dark:border-primary-800">
              <div>
                <p className="text-xs text-neutral-500 uppercase tracking-wide dark:text-neutral-400">Entity Name</p>
                <p className="text-sm font-medium text-primary-900 mt-1 dark:text-neutral-50">{selectedKyc.entityName}</p>
              </div>
              <div>
                <p className="text-xs text-neutral-500 uppercase tracking-wide dark:text-neutral-400">Entity Type</p>
                <Badge variant="neutral" className="mt-1">{selectedKyc.entityType}</Badge>
              </div>
              <div>
                <p className="text-xs text-neutral-500 uppercase tracking-wide dark:text-neutral-400">Risk Score</p>
                <p className="text-sm font-medium text-primary-900 mt-1 dark:text-neutral-50">{selectedKyc.riskScore}/100</p>
              </div>
              <div>
                <p className="text-xs text-neutral-500 uppercase tracking-wide dark:text-neutral-400">Submitted</p>
                <p className="text-sm font-medium text-primary-900 mt-1 dark:text-neutral-50">
                  {new Date(selectedKyc.submittedAt).toLocaleDateString()}
                </p>
              </div>
            </div>

            {/* Documents */}
            {selectedKyc.documents && (
              <div className="pt-4 border-t border-neutral-200 dark:border-primary-800">
                <h4 className="text-sm font-semibold text-primary-900 uppercase tracking-wide mb-3 dark:text-neutral-50">Documents</h4>
                <div className="space-y-2">
                  {selectedKyc.documents.map((doc: any, idx: number) => (
                    <div key={idx} className="flex items-center justify-between p-3 bg-neutral-50 rounded-xl dark:bg-primary-950">
                      <div className="flex items-center gap-2">
                        <FileText className="w-4 h-4 text-neutral-500 dark:text-neutral-400" />
                        <span className="text-sm text-primary-900 dark:text-neutral-50">{doc.type} - {doc.fileName}</span>
                      </div>
                      <Badge variant={doc.status === 'VERIFIED' ? 'success' : 'warning'} size="sm">
                        {doc.status}
                      </Badge>
                    </div>
                  ))}
                </div>
              </div>
            )}

            {/* Screening Results */}
            {selectedKyc.screeningResults && (
              <div className="pt-4 border-t border-neutral-200 dark:border-primary-800">
                <h4 className="text-sm font-semibold text-primary-900 uppercase tracking-wide mb-3 dark:text-neutral-50">Screening Results</h4>
                <div className="grid grid-cols-3 gap-3">
                  <div className="p-3 bg-neutral-50 rounded-xl text-center dark:bg-primary-950">
                    <p className="text-xs text-neutral-500 mb-1 dark:text-neutral-400">Sanctions</p>
                    <Badge variant={selectedKyc.screeningResults.sanctionsHit ? 'error' : 'success'}>
                      {selectedKyc.screeningResults.sanctionsHit ? 'HIT' : 'CLEAR'}
                    </Badge>
                  </div>
                  <div className="p-3 bg-neutral-50 rounded-xl text-center dark:bg-primary-950">
                    <p className="text-xs text-neutral-500 mb-1 dark:text-neutral-400">PEP</p>
                    <Badge variant={selectedKyc.screeningResults.pepHit ? 'error' : 'success'}>
                      {selectedKyc.screeningResults.pepHit ? 'HIT' : 'CLEAR'}
                    </Badge>
                  </div>
                  <div className="p-3 bg-neutral-50 rounded-xl text-center dark:bg-primary-950">
                    <p className="text-xs text-neutral-500 mb-1 dark:text-neutral-400">Adverse Media</p>
                    <Badge variant={selectedKyc.screeningResults.adverseMedia ? 'error' : 'success'}>
                      {selectedKyc.screeningResults.adverseMedia ? 'HIT' : 'CLEAR'}
                    </Badge>
                  </div>
                </div>
              </div>
            )}

            {/* Actions */}
            <div className="flex justify-end gap-3 pt-4 border-t border-neutral-200 dark:border-primary-800">
              <Button variant="outline" onClick={() => setShowDetailModal(false)}>
                Close
              </Button>
              <Button
                variant="outline"
                onClick={() => setShowRejectModal(true)}
                leftIcon={<XCircle className="w-4 h-4" />}
                className="text-error-600 border-error-200 hover:bg-error-50 dark:text-error-300 dark:border-error-500/30 dark:hover:bg-error-500/10"
              >
                Reject
              </Button>
              <Button
                onClick={() => handleApprove(selectedKyc.id)}
                disabled={processing}
                leftIcon={processing ? <Loader2 className="w-4 h-4 animate-spin" /> : <CheckCircle className="w-4 h-4" />}
              >
                Approve
              </Button>
            </div>
          </div>
        )}
      </Modal>

      {/* Reject Modal */}
      <Modal
        isOpen={showRejectModal}
        onClose={() => setShowRejectModal(false)}
        title="Reject KYC Application"
      >
        <RejectForm
          onSubmit={handleReject}
          loading={processing}
          onCancel={() => setShowRejectModal(false)}
        />
      </Modal>
    </Page>
  );
};

export default KyccPage;
