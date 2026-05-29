import React, { useState } from 'react';
import {
  UserCheck,
  Plus,
  Search,
  Filter,
  Shield,
  AlertTriangle,
  CheckCircle,
  Clock,
  XCircle,
  Phone,
  Mail,
  CreditCard,
  Calendar,
  MoreHorizontal,
  Eye,
  Edit,
  RefreshCw,
  Download,
  Upload,
  FileText,
} from 'lucide-react';
import { Card, CardHeader, Button, Badge, Input, EmptyState } from '../ui';
import { Modal, Tabs, Avatar, Alert, ProgressBar } from '../ui/enhanced';
import { formatDate, cn } from '../../utils';

// Types
interface KyccRecord {
  id: string;
  kyccReference: string;
  fullName: string;
  mobileNumber: string;
  emailAddress: string;
  kycIdType: 'EMIRATES_ID' | 'PASSPORT' | 'NATIONAL_ID';
  kycIdNumber: string; // Masked
  kycIdExpiry: string;
  kycIdCountry: string;
  verificationStatus: 'PENDING' | 'VERIFIED' | 'REJECTED' | 'EXPIRED';
  verificationMethod?: string;
  verifiedAt?: string;
  riskScore: number;
  riskCategory: 'LOW' | 'MEDIUM' | 'HIGH';
  consentGiven: boolean;
  validFrom?: string;
  validUntil?: string;
  linkedBeneficiaries: number;
  createdAt: string;
}

// Mock data
const mockKyccRecords: KyccRecord[] = [
  {
    id: '1',
    kyccReference: 'KYCC-2024-001234',
    fullName: 'Mohammed Al Rashid',
    mobileNumber: '+971501234567',
    emailAddress: 'mohammed.rashid@email.com',
    kycIdType: 'EMIRATES_ID',
    kycIdNumber: '****-****-****-1234',
    kycIdExpiry: '2027-06-15',
    kycIdCountry: 'AE',
    verificationStatus: 'VERIFIED',
    verificationMethod: 'OTP',
    verifiedAt: '2024-01-20T10:30:00Z',
    riskScore: 12,
    riskCategory: 'LOW',
    consentGiven: true,
    validFrom: '2024-01-20',
    validUntil: '2025-01-20',
    linkedBeneficiaries: 3,
    createdAt: '2024-01-15T09:00:00Z',
  },
  {
    id: '2',
    kyccReference: 'KYCC-2024-001235',
    fullName: 'Sarah Ahmed Khan',
    mobileNumber: '+971509876543',
    emailAddress: 'sarah.khan@company.ae',
    kycIdType: 'PASSPORT',
    kycIdNumber: '****5678',
    kycIdExpiry: '2026-03-20',
    kycIdCountry: 'PK',
    verificationStatus: 'PENDING',
    riskScore: 18,
    riskCategory: 'MEDIUM',
    consentGiven: false,
    linkedBeneficiaries: 1,
    createdAt: '2024-02-10T14:00:00Z',
  },
  {
    id: '3',
    kyccReference: 'KYCC-2024-001236',
    fullName: 'Omar Hassan Ali',
    mobileNumber: '+971505551234',
    emailAddress: 'omar.ali@gmail.com',
    kycIdType: 'EMIRATES_ID',
    kycIdNumber: '****-****-****-5678',
    kycIdExpiry: '2024-04-01', // Expiring soon
    kycIdCountry: 'AE',
    verificationStatus: 'VERIFIED',
    verificationMethod: 'MANUAL',
    verifiedAt: '2024-01-25T11:00:00Z',
    riskScore: 8,
    riskCategory: 'LOW',
    consentGiven: true,
    validFrom: '2024-01-25',
    validUntil: '2024-04-01', // Limited by ID expiry
    linkedBeneficiaries: 2,
    createdAt: '2024-01-22T08:30:00Z',
  },
  {
    id: '4',
    kyccReference: 'KYCC-2024-001237',
    fullName: 'Fatima Al Maktoum',
    mobileNumber: '+971507778899',
    emailAddress: 'fatima.m@business.ae',
    kycIdType: 'NATIONAL_ID',
    kycIdNumber: '****9012',
    kycIdExpiry: '2028-12-31',
    kycIdCountry: 'SA',
    verificationStatus: 'REJECTED',
    riskScore: 35,
    riskCategory: 'HIGH',
    consentGiven: true,
    linkedBeneficiaries: 0,
    createdAt: '2024-02-05T16:00:00Z',
  },
  {
    id: '5',
    kyccReference: 'KYCC-2024-001238',
    fullName: 'Ahmad Abdullah',
    mobileNumber: '+971502223344',
    emailAddress: 'ahmad.a@corp.com',
    kycIdType: 'EMIRATES_ID',
    kycIdNumber: '****-****-****-3456',
    kycIdExpiry: '2023-12-31', // Expired
    kycIdCountry: 'AE',
    verificationStatus: 'EXPIRED',
    verificationMethod: 'OTP',
    verifiedAt: '2023-06-15T09:00:00Z',
    riskScore: 10,
    riskCategory: 'LOW',
    consentGiven: true,
    validFrom: '2023-06-15',
    validUntil: '2023-12-31',
    linkedBeneficiaries: 1,
    createdAt: '2023-06-10T10:00:00Z',
  },
];

// Status configurations
const statusConfig = {
  PENDING: { label: 'Pending', color: 'warning', icon: <Clock className="w-4 h-4" /> },
  VERIFIED: { label: 'Verified', color: 'success', icon: <CheckCircle className="w-4 h-4" /> },
  REJECTED: { label: 'Rejected', color: 'error', icon: <XCircle className="w-4 h-4" /> },
  EXPIRED: { label: 'Expired', color: 'neutral', icon: <AlertTriangle className="w-4 h-4" /> },
};

const riskConfig = {
  LOW: { label: 'Low Risk', color: 'success' },
  MEDIUM: { label: 'Medium Risk', color: 'warning' },
  HIGH: { label: 'High Risk', color: 'error' },
};

const idTypeLabels = {
  EMIRATES_ID: 'Emirates ID',
  PASSPORT: 'Passport',
  NATIONAL_ID: 'National ID',
};

// Risk Score Indicator
const RiskIndicator: React.FC<{ score: number; category: KyccRecord['riskCategory'] }> = ({ score, category }) => {
  const config = riskConfig[category];
  return (
    <div className="flex items-center gap-2">
      <div className="w-16">
        <ProgressBar
          value={score}
          max={100}
          size="sm"
          variant={config.color as any}
        />
      </div>
      <span className={cn(
        'text-caption font-medium',
        category === 'LOW' && 'text-success-600',
        category === 'MEDIUM' && 'text-warning-600',
        category === 'HIGH' && 'text-error-600'
      )}>
        {score}
      </span>
    </div>
  );
};

// KYCC Row Component
const KyccRow: React.FC<{
  record: KyccRecord;
  onView: () => void;
  onVerify: () => void;
}> = ({ record, onView, onVerify }) => {
  const [showActions, setShowActions] = useState(false);
  const status = statusConfig[record.verificationStatus];
  const isExpiringSoon = record.kycIdExpiry && 
    new Date(record.kycIdExpiry) < new Date(Date.now() + 90 * 24 * 60 * 60 * 1000);

  return (
    <tr className="hover:bg-neutral-50 transition-colors">
      <td className="px-6 py-4">
        <div className="flex items-center gap-3">
          <Avatar 
            name={record.fullName} 
            size="md" 
            status={record.verificationStatus === 'VERIFIED' ? 'online' : 'offline'} 
          />
          <div>
            <p className="text-body-md font-medium text-primary-900">{record.fullName}</p>
            <p className="text-body-sm text-neutral-500">{record.kyccReference}</p>
          </div>
        </div>
      </td>
      <td className="px-6 py-4">
        <div className="space-y-1">
          <div className="flex items-center gap-2 text-body-sm">
            <Phone className="w-3.5 h-3.5 text-neutral-400" />
            <span className="text-primary-900">{record.mobileNumber}</span>
          </div>
          <div className="flex items-center gap-2 text-body-sm">
            <Mail className="w-3.5 h-3.5 text-neutral-400" />
            <span className="text-neutral-600">{record.emailAddress}</span>
          </div>
        </div>
      </td>
      <td className="px-6 py-4">
        <div className="space-y-1">
          <div className="flex items-center gap-2">
            <CreditCard className="w-4 h-4 text-neutral-400" />
            <span className="text-body-sm font-medium text-primary-900">
              {idTypeLabels[record.kycIdType]}
            </span>
          </div>
          <p className="text-body-sm text-neutral-500 font-mono">{record.kycIdNumber}</p>
          <div className="flex items-center gap-1">
            <Calendar className="w-3 h-3 text-neutral-400" />
            <span className={cn(
              'text-caption',
              isExpiringSoon ? 'text-warning-600 font-medium' : 'text-neutral-500'
            )}>
              Exp: {formatDate(record.kycIdExpiry)}
              {isExpiringSoon && ' ⚠️'}
            </span>
          </div>
        </div>
      </td>
      <td className="px-6 py-4">
        <Badge variant={status.color as any} className="flex items-center gap-1.5 w-fit">
          {status.icon}
          {status.label}
        </Badge>
        {record.verifiedAt && (
          <p className="text-caption text-neutral-500 mt-1">
            {record.verificationMethod} • {formatDate(record.verifiedAt)}
          </p>
        )}
      </td>
      <td className="px-6 py-4">
        <RiskIndicator score={record.riskScore} category={record.riskCategory} />
        <Badge variant={riskConfig[record.riskCategory].color as any} size="sm" className="mt-1">
          {riskConfig[record.riskCategory].label}
        </Badge>
      </td>
      <td className="px-6 py-4">
        <div className="flex items-center gap-1">
          <Shield className="w-4 h-4 text-neutral-400" />
          <span className="text-body-sm text-primary-900">{record.linkedBeneficiaries}</span>
        </div>
        <p className="text-caption text-neutral-500">beneficiaries</p>
      </td>
      <td className="px-6 py-4">
        <div className="relative">
          <button
            onClick={() => setShowActions(!showActions)}
            className="p-2 hover:bg-neutral-100 rounded-lg"
          >
            <MoreHorizontal className="w-5 h-5 text-neutral-500" />
          </button>
          {showActions && (
            <>
              <div className="fixed inset-0 z-10" onClick={() => setShowActions(false)} />
              <div className="absolute right-0 top-full mt-1 w-48 bg-white rounded-lg shadow-medium border py-1 z-20">
                <button
                  onClick={() => { onView(); setShowActions(false); }}
                  className="w-full flex items-center gap-2 px-4 py-2 text-body-sm hover:bg-neutral-50"
                >
                  <Eye className="w-4 h-4" /> View Details
                </button>
                {record.verificationStatus === 'PENDING' && (
                  <button
                    onClick={() => { onVerify(); setShowActions(false); }}
                    className="w-full flex items-center gap-2 px-4 py-2 text-body-sm text-success-600 hover:bg-success-50"
                  >
                    <CheckCircle className="w-4 h-4" /> Verify
                  </button>
                )}
                <button
                  onClick={() => setShowActions(false)}
                  className="w-full flex items-center gap-2 px-4 py-2 text-body-sm hover:bg-neutral-50"
                >
                  <Edit className="w-4 h-4" /> Edit Record
                </button>
                {record.verificationStatus === 'EXPIRED' && (
                  <button
                    onClick={() => setShowActions(false)}
                    className="w-full flex items-center gap-2 px-4 py-2 text-body-sm text-info-600 hover:bg-info-50"
                  >
                    <RefreshCw className="w-4 h-4" /> Re-verify
                  </button>
                )}
              </div>
            </>
          )}
        </div>
      </td>
    </tr>
  );
};

// Main KYCC Page
const KyccPage: React.FC = () => {
  const [searchQuery, setSearchQuery] = useState('');
  const [activeTab, setActiveTab] = useState('all');
  const [showCreateModal, setShowCreateModal] = useState(false);
  const [showVerifyModal, setShowVerifyModal] = useState(false);
  const [selectedRecord, setSelectedRecord] = useState<KyccRecord | null>(null);

  const tabs = [
    { id: 'all', label: 'All Records', badge: mockKyccRecords.length },
    { id: 'pending', label: 'Pending', badge: mockKyccRecords.filter(r => r.verificationStatus === 'PENDING').length },
    { id: 'verified', label: 'Verified', badge: mockKyccRecords.filter(r => r.verificationStatus === 'VERIFIED').length },
    { id: 'expired', label: 'Expiring/Expired', badge: mockKyccRecords.filter(r => 
      r.verificationStatus === 'EXPIRED' || 
      (r.kycIdExpiry && new Date(r.kycIdExpiry) < new Date(Date.now() + 90 * 24 * 60 * 60 * 1000))
    ).length },
  ];

  const filteredRecords = mockKyccRecords.filter(record => {
    const matchesSearch = record.fullName.toLowerCase().includes(searchQuery.toLowerCase()) ||
                         record.kyccReference.toLowerCase().includes(searchQuery.toLowerCase()) ||
                         record.mobileNumber.includes(searchQuery);
    const matchesTab = activeTab === 'all' ||
                      (activeTab === 'pending' && record.verificationStatus === 'PENDING') ||
                      (activeTab === 'verified' && record.verificationStatus === 'VERIFIED') ||
                      (activeTab === 'expired' && (
                        record.verificationStatus === 'EXPIRED' ||
                        (record.kycIdExpiry && new Date(record.kycIdExpiry) < new Date(Date.now() + 90 * 24 * 60 * 60 * 1000))
                      ));
    return matchesSearch && matchesTab;
  });

  const stats = {
    total: mockKyccRecords.length,
    verified: mockKyccRecords.filter(r => r.verificationStatus === 'VERIFIED').length,
    pending: mockKyccRecords.filter(r => r.verificationStatus === 'PENDING').length,
    highRisk: mockKyccRecords.filter(r => r.riskCategory === 'HIGH').length,
  };

  const handleVerify = (record: KyccRecord) => {
    setSelectedRecord(record);
    setShowVerifyModal(true);
  };

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
        <div>
          <h1 className="text-display-md text-primary-900">KYCC Management</h1>
          <p className="text-body-md text-neutral-500 mt-1">
            Know Your Customer's Clients - Verification & Compliance
          </p>
        </div>
        <Button leftIcon={<Plus className="w-4 h-4" />} onClick={() => setShowCreateModal(true)}>
          New KYCC Record
        </Button>
      </div>

      {/* Stats */}
      <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
        <Card padding="sm">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-body-sm text-neutral-500">Total Records</p>
              <p className="text-heading-xl text-primary-900">{stats.total}</p>
            </div>
            <div className="p-3 rounded-xl bg-primary-100">
              <FileText className="w-6 h-6 text-primary-700" />
            </div>
          </div>
        </Card>
        <Card padding="sm">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-body-sm text-neutral-500">Verified</p>
              <p className="text-heading-xl text-success-600">{stats.verified}</p>
            </div>
            <div className="p-3 rounded-xl bg-success-50">
              <CheckCircle className="w-6 h-6 text-success-600" />
            </div>
          </div>
        </Card>
        <Card padding="sm">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-body-sm text-neutral-500">Pending Review</p>
              <p className="text-heading-xl text-warning-600">{stats.pending}</p>
            </div>
            <div className="p-3 rounded-xl bg-warning-50">
              <Clock className="w-6 h-6 text-warning-600" />
            </div>
          </div>
        </Card>
        <Card padding="sm">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-body-sm text-neutral-500">High Risk</p>
              <p className="text-heading-xl text-error-600">{stats.highRisk}</p>
            </div>
            <div className="p-3 rounded-xl bg-error-50">
              <AlertTriangle className="w-6 h-6 text-error-600" />
            </div>
          </div>
        </Card>
      </div>

      {/* Alerts */}
      {stats.pending > 0 && (
        <Alert variant="warning" title="Pending Verifications">
          You have {stats.pending} KYCC record(s) awaiting verification. 
          Beneficiaries cannot transact until their KYCC is verified.
        </Alert>
      )}

      {/* Table */}
      <Card padding="none">
        <div className="p-4 border-b border-neutral-200">
          <Tabs tabs={tabs} activeTab={activeTab} onChange={setActiveTab} />
        </div>

        <div className="p-4 border-b border-neutral-200">
          <div className="flex flex-col sm:flex-row gap-4">
            <div className="flex-1">
              <Input
                placeholder="Search by name, reference, or mobile..."
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                leftIcon={<Search className="w-4 h-4" />}
              />
            </div>
            <div className="flex gap-2">
              <Button variant="outline" leftIcon={<Filter className="w-4 h-4" />}>
                Filters
              </Button>
              <Button variant="outline" leftIcon={<Upload className="w-4 h-4" />}>
                Bulk Import
              </Button>
              <Button variant="outline" leftIcon={<Download className="w-4 h-4" />}>
                Export
              </Button>
            </div>
          </div>
        </div>

        {filteredRecords.length > 0 ? (
          <div className="overflow-x-auto">
            <table className="w-full">
              <thead className="bg-neutral-50 border-b border-neutral-200">
                <tr>
                  <th className="px-6 py-3 text-left text-body-sm font-semibold text-neutral-600">Customer</th>
                  <th className="px-6 py-3 text-left text-body-sm font-semibold text-neutral-600">Contact</th>
                  <th className="px-6 py-3 text-left text-body-sm font-semibold text-neutral-600">ID Document</th>
                  <th className="px-6 py-3 text-left text-body-sm font-semibold text-neutral-600">Status</th>
                  <th className="px-6 py-3 text-left text-body-sm font-semibold text-neutral-600">Risk</th>
                  <th className="px-6 py-3 text-left text-body-sm font-semibold text-neutral-600">Linked</th>
                  <th className="px-6 py-3 text-left text-body-sm font-semibold text-neutral-600">Actions</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-neutral-100">
                {filteredRecords.map(record => (
                  <KyccRow
                    key={record.id}
                    record={record}
                    onView={() => setSelectedRecord(record)}
                    onVerify={() => handleVerify(record)}
                  />
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <div className="p-8">
            <EmptyState
              icon={<UserCheck className="w-8 h-8" />}
              title="No KYCC records found"
              description="Create a new KYCC record to verify customer identities"
              action={<Button onClick={() => setShowCreateModal(true)}>Create Record</Button>}
            />
          </div>
        )}
      </Card>

      {/* Create KYCC Modal */}
      <Modal
        isOpen={showCreateModal}
        onClose={() => setShowCreateModal(false)}
        title="Create KYCC Record"
        subtitle="Collect minimal KYC information for customer verification"
        size="md"
        footer={
          <>
            <Button variant="outline" onClick={() => setShowCreateModal(false)}>Cancel</Button>
            <Button>Create Record</Button>
          </>
        }
      >
        <div className="space-y-4">
          <Input label="Full Name *" placeholder="Enter full legal name" />
          <div className="grid grid-cols-2 gap-4">
            <Input label="Mobile Number *" placeholder="+971 5XX XXX XXXX" />
            <Input label="Email Address" placeholder="email@example.com" />
          </div>
          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="block text-body-sm font-medium text-primary-900 mb-1.5">
                ID Document Type *
              </label>
              <select className="w-full px-4 py-2.5 border border-neutral-300 rounded-lg text-body-md">
                <option value="">Select type...</option>
                <option value="EMIRATES_ID">Emirates ID</option>
                <option value="PASSPORT">Passport</option>
                <option value="NATIONAL_ID">National ID</option>
              </select>
            </div>
            <Input label="ID Number *" placeholder="Enter ID number" />
          </div>
          <div className="grid grid-cols-2 gap-4">
            <Input label="ID Expiry Date *" type="date" />
            <div>
              <label className="block text-body-sm font-medium text-primary-900 mb-1.5">
                Issuing Country *
              </label>
              <select className="w-full px-4 py-2.5 border border-neutral-300 rounded-lg text-body-md">
                <option value="">Select country...</option>
                <option value="AE">United Arab Emirates</option>
                <option value="SA">Saudi Arabia</option>
                <option value="PK">Pakistan</option>
                <option value="IN">India</option>
              </select>
            </div>
          </div>
          
          <Alert variant="info">
            <strong>Minimal KYCC:</strong> Only name, mobile, email, and one ID document required.
            Verification can be done via OTP or manual review.
          </Alert>
        </div>
      </Modal>

      {/* Verify Modal */}
      <Modal
        isOpen={showVerifyModal}
        onClose={() => setShowVerifyModal(false)}
        title="Verify KYCC Record"
        subtitle={selectedRecord ? `Verifying: ${selectedRecord.fullName}` : ''}
        size="md"
        footer={
          <>
            <Button variant="outline" onClick={() => setShowVerifyModal(false)}>Cancel</Button>
            <Button variant="danger" onClick={() => setShowVerifyModal(false)}>Reject</Button>
            <Button onClick={() => setShowVerifyModal(false)}>Verify</Button>
          </>
        }
      >
        {selectedRecord && (
          <div className="space-y-4">
            <div className="grid grid-cols-2 gap-4 p-4 bg-neutral-50 rounded-xl">
              <div>
                <p className="text-caption text-neutral-500">Full Name</p>
                <p className="text-body-md font-medium text-primary-900">{selectedRecord.fullName}</p>
              </div>
              <div>
                <p className="text-caption text-neutral-500">Mobile</p>
                <p className="text-body-md text-primary-900">{selectedRecord.mobileNumber}</p>
              </div>
              <div>
                <p className="text-caption text-neutral-500">ID Type</p>
                <p className="text-body-md text-primary-900">{idTypeLabels[selectedRecord.kycIdType]}</p>
              </div>
              <div>
                <p className="text-caption text-neutral-500">ID Number</p>
                <p className="text-body-md font-mono text-primary-900">{selectedRecord.kycIdNumber}</p>
              </div>
            </div>

            <div>
              <label className="block text-body-sm font-medium text-primary-900 mb-1.5">
                Verification Method
              </label>
              <select className="w-full px-4 py-2.5 border border-neutral-300 rounded-lg text-body-md">
                <option value="OTP">OTP Verification</option>
                <option value="MANUAL">Manual Review</option>
                <option value="DOCUMENT_CHECK">Document Check</option>
              </select>
            </div>

            <div className="flex items-start gap-3 p-4 bg-warning-50 rounded-xl">
              <AlertTriangle className="w-5 h-5 text-warning-600 flex-shrink-0 mt-0.5" />
              <div>
                <p className="text-body-sm font-medium text-warning-800">Risk Assessment</p>
                <p className="text-body-sm text-warning-700 mt-1">
                  Risk Score: <strong>{selectedRecord.riskScore}</strong> ({selectedRecord.riskCategory})
                </p>
                <p className="text-caption text-warning-600 mt-1">
                  {selectedRecord.riskCategory === 'HIGH' 
                    ? 'Additional scrutiny recommended before verification.'
                    : 'Standard verification process applicable.'}
                </p>
              </div>
            </div>

            <div className="flex items-center gap-2">
              <input type="checkbox" id="consent" className="rounded border-neutral-300" />
              <label htmlFor="consent" className="text-body-sm text-neutral-700">
                Confirm customer has provided consent for data processing
              </label>
            </div>
          </div>
        )}
      </Modal>
    </div>
  );
};

export default KyccPage;
