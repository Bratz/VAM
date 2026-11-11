import React, { useState } from 'react';
import {
  Plus,
  Settings,
  RefreshCw,
  ExternalLink,
  Upload,
  Download,
  Play,
  Pause,
  Trash2,
  FileText,
  Key,
  ArrowRight,
  ArrowLeftRight,
  Check,
  X,
  Loader2,
  Shield,
  Search,
  AlertTriangle,
  Link2,
  Building2,
  Globe,
  CreditCard,
  Database,
  Zap,
} from 'lucide-react';
import { StatusIconBadge, Checkbox, DataTable } from '../components/ui';
import { Page } from '../components/layout/Page';
import { PageHeader } from '../components/layout/PageHeader';
import { Modal } from '../components/ui/enhanced';
import { formatRelativeTime, cn } from '../utils';
import { getConnectorIcon } from '../components/connectorIconMap';
import toast from 'react-hot-toast';
import {
  useIntegrations,
  type IntegrationConnector,
  type IntegrationConnection,
  type ConnectorCategory,
  type ConnectionStatus,
  type Environment,
  type SyncFrequency,
  type DataFlow,
  type FieldMapping,
  type SyncLog,
  type TestConnectionRequest,
  type TestConnectionResponse,
} from '../hooks/useIntegrations';

// Types and data come from the integration API (useIntegrations); nothing here is invented.
type Connector = IntegrationConnector;
type Connection = IntegrationConnection;
const errMsg = (e: any): string => e?.response?.data?.message || e?.message || 'Request failed';

// ============================================================================
// CATEGORY CONFIG
// ============================================================================

const categoryConfig: Record<ConnectorCategory, { label: string; icon: React.ElementType; color: string }> = {
  ERP: { label: 'ERP', icon: Building2, color: 'text-neutral-700 dark:text-neutral-200' },
  TREASURY: { label: 'Treasury', icon: Database, color: 'text-neutral-700 dark:text-neutral-200' },
  BANKING: { label: 'Banking', icon: Building2, color: 'text-neutral-700 dark:text-neutral-200' },
  OPEN_BANKING: { label: 'Open Banking', icon: Globe, color: 'text-neutral-700 dark:text-neutral-200' },
  PAYMENTS: { label: 'Payments', icon: CreditCard, color: 'text-neutral-700 dark:text-neutral-200' },
  GENERIC: { label: 'Generic', icon: Zap, color: 'text-neutral-700 dark:text-neutral-200' },
};

// ============================================================================
// CONNECTOR CARD - Swiss Minimalism
// ============================================================================

const ConnectorCard: React.FC<{
  connector: Connector;
  onSetup: () => void;
}> = ({ connector, onSetup }) => {
  const IconComponent = getConnectorIcon(connector.iconType || connector.connectorCode);
  
  return (
    <div className="group border border-edge bg-surface-card hover:border-neutral-400 transition-all duration-200">
      <div className="p-6">
        {/* Header */}
        <div className="flex items-start justify-between mb-4">
          <div className="flex items-center gap-4">
            <div className="w-12 h-12 border border-edge flex items-center justify-center bg-surface-page">
              <IconComponent size={24} className="text-neutral-700 dark:text-neutral-200" />
            </div>
            <div>
              <div className="flex items-center gap-2">
                <h3 className="body-strong tracking-tight">{connector.connectorName}</h3>
                {connector.isBeta && (
                  <span className="px-1.5 py-0.5 label bg-surface-muted">Beta</span>
                )}
              </div>
              <p className="caption mt-0.5">{categoryConfig[connector.category].label} · {connector.region || 'Global'}</p>
            </div>
          </div>
          {connector.isConnected && (
            <div className="w-2 h-2 rounded-full bg-neutral-900" title="Connected" />
          )}
        </div>

        {/* Description */}
        <p className="text-caption text-neutral-600 leading-relaxed mb-4 line-clamp-2 dark:text-neutral-300">{connector.description}</p>

        {/* Features */}
        <div className="flex flex-wrap gap-1 mb-4">
          {connector.supportedFeatures.slice(0, 3).map((feature, idx) => (
            <span key={idx} className="px-2 py-0.5 label-cased bg-surface-page border border-edge-subtle">
              {feature}
            </span>
          ))}
          {connector.supportedFeatures.length > 3 && (
            <span className="px-2 py-0.5 label-cased">
              +{connector.supportedFeatures.length - 3}
            </span>
          )}
        </div>

        {/* Compliance Standards */}
        {connector.complianceStandards && connector.complianceStandards.length > 0 && (
          <div className="flex gap-1 mb-4">
            {connector.complianceStandards.slice(0, 3).map((std, idx) => (
              <span key={idx} className="caption">{std}</span>
            ))}
          </div>
        )}
      </div>

      {/* Footer */}
      <div className="px-6 py-3 border-t border-edge-subtle flex items-center justify-between bg-neutral-50/50 dark:bg-primary-950/50">
        {connector.documentationUrl && (
          <button className="caption hover:text-neutral-700 flex items-center gap-1 transition-colors dark:hover:text-neutral-200">
            Documentation <ExternalLink className="w-3 h-3" />
          </button>
        )}
        <button
          onClick={onSetup}
          disabled={connector.status === 'COMING_SOON'}
          className={cn(
            "px-4 py-1.5 text-caption font-medium transition-all",
            connector.status === 'COMING_SOON'
              ? "text-neutral-400 cursor-not-allowed dark:text-neutral-400"
              : "text-neutral-900 hover:bg-neutral-900 hover:text-white border border-neutral-900 dark:text-neutral-50"
          )}
        >
          {connector.isConnected ? 'Manage' : 'Connect'}
        </button>
      </div>
    </div>
  );
};

// ============================================================================
// CONNECTION CARD - Swiss Minimalism
// ============================================================================

const ConnectionCard: React.FC<{
  connection: Connection;
  onView: () => void;
  onSync: () => void;
  onDisconnect: () => void;
}> = ({ connection, onView, onSync, onDisconnect }) => {
  const IconComponent = getConnectorIcon(connection.iconType || connection.connectorCode);

  const statusStyles: Record<ConnectionStatus, { bg: string; text: string; label: string }> = {
    CONNECTED: { bg: 'bg-neutral-900', text: 'text-white', label: 'Active' },
    DISCONNECTED: { bg: 'bg-neutral-200 dark:bg-primary-800', text: 'text-neutral-600 dark:text-neutral-300', label: 'Inactive' },
    ERROR: { bg: 'bg-error-600', text: 'text-white', label: 'Error' },
    SYNCING: { bg: 'bg-neutral-600', text: 'text-white', label: 'Syncing' },
  };

  const status = statusStyles[connection.status];

  return (
    <div className="border border-edge bg-surface-card hover:border-neutral-300 transition-colors dark:hover:border-primary-700">
      {/* Header */}
      <div className="p-5 border-b border-edge-subtle">
        <div className="flex items-start justify-between">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 border border-edge flex items-center justify-center bg-surface-page">
              <IconComponent size={20} className="text-neutral-700 dark:text-neutral-200" />
            </div>
            <div>
              <h3 className="body-strong">{connection.connectionName}</h3>
              <p className="caption">{connection.connectorName}</p>
            </div>
          </div>
          <div className="flex items-center gap-2">
            <span className={cn(
              "px-2 py-0.5 text-caption font-medium uppercase tracking-wide",
              connection.environment === 'PRODUCTION' ? 'bg-neutral-900 text-white' : 'bg-surface-muted text-neutral-600 dark:text-neutral-300'
            )}>
              {connection.environment === 'PRODUCTION' ? 'Prod' : 'Sandbox'}
            </span>
            <span className={cn("px-2 py-0.5 text-caption font-medium uppercase tracking-wide", status.bg, status.text)}>
              {status.label}
            </span>
          </div>
        </div>
      </div>

      {/* Error Message */}
      {connection.errorMessage && (
        <div className="px-5 py-3 bg-error-50 border-b border-error-100 dark:bg-error-500/10 dark:border-error-500/30">
          <p className="text-caption text-error-700 dark:text-error-300">{connection.errorMessage}</p>
        </div>
      )}

      {/* Stats Grid */}
      <div className="grid grid-cols-3 divide-x divide-edge-subtle border-b border-edge-subtle">
        <div className="p-4 text-center">
          <p className="text-body-lg font-light text-neutral-900 dark:text-neutral-50">{connection.dataFlowCount}</p>
          <p className="caption">Flows</p>
        </div>
        <div className="p-4 text-center">
          <p className="text-caption font-medium text-neutral-900 capitalize dark:text-neutral-50">{connection.syncFrequency.toLowerCase().replace('_', ' ')}</p>
          <p className="caption">Frequency</p>
        </div>
        <div className="p-4 text-center">
          <p className="text-caption font-medium text-neutral-900 dark:text-neutral-50">{connection.lastSyncAt ? formatRelativeTime(connection.lastSyncAt) : '—'}</p>
          <p className="caption">Last Sync</p>
        </div>
      </div>

      {/* Actions */}
      <div className="p-3 flex items-center justify-between bg-neutral-50/50 dark:bg-primary-950/50">
        <button
          onClick={onDisconnect}
          className="p-2 text-neutral-400 hover:text-error-600 transition-colors dark:text-neutral-400"
          title="Disconnect"
        >
          <Trash2 className="w-4 h-4" />
        </button>
        <div className="flex items-center gap-2">
          <button
            onClick={onSync}
            disabled={connection.status === 'SYNCING'}
            className="p-2 text-neutral-500 hover:text-neutral-900 transition-colors disabled:opacity-50 dark:text-neutral-400 dark:hover:text-neutral-50"
            title="Sync Now"
          >
            <RefreshCw className={cn("w-4 h-4", connection.status === 'SYNCING' && 'animate-spin')} />
          </button>
          <button
            onClick={onView}
            className="px-3 py-1.5 text-caption font-medium border border-edge-strong hover:border-neutral-900 hover:bg-neutral-900 hover:text-white transition-all"
          >
            Manage
          </button>
        </div>
      </div>
    </div>
  );
};

// ============================================================================
// SETUP WIZARD MODAL - Swiss Minimalism
// ============================================================================

const SetupWizardModal: React.FC<{
  connector: Connector | null;
  onClose: () => void;
  onComplete: (config: Record<string, unknown>) => void;
  onTest: (req: TestConnectionRequest) => Promise<TestConnectionResponse>;
}> = ({ connector, onClose, onComplete, onTest }) => {
  const [step, setStep] = useState(1);
  const [config, setConfig] = useState({
    name: '',
    environment: 'SANDBOX',
    clientId: '',
    clientSecret: '',
    tenantId: '',
    apiKey: '',
    host: '',
    port: '22',
    username: '',
    privateKey: '',
    syncFrequency: 'DAILY',
  });
  const [testing, setTesting] = useState(false);
  const [testResult, setTestResult] = useState<'success' | 'error' | null>(null);
  const [testMessage, setTestMessage] = useState('');

  if (!connector) return null;

  const IconComponent = getConnectorIcon(connector.iconType || connector.connectorCode);

  // The connection's credentials: everything entered except the name / environment / frequency.
  const credentialsOf = (c: Record<string, unknown>) => {
    const { name: _name, environment: _env, syncFrequency: _freq, ...creds } = c;
    return Object.fromEntries(Object.entries(creds).filter(([, v]) => v !== ''));
  };

  const testConnection = async () => {
    setTesting(true);
    setTestResult(null);
    try {
      const r = await onTest({ connectorId: connector.id, credentials: credentialsOf(config), environment: config.environment as Environment });
      setTestResult(r.success ? 'success' : 'error');
      setTestMessage(r.message);
    } catch (e) {
      setTestResult('error');
      setTestMessage(errMsg(e));
    } finally {
      setTesting(false);
    }
  };

  const handleComplete = () => {
    onComplete({ ...config, credentials: credentialsOf(config) });
    onClose();
    setStep(1);
    setTestResult(null);
  };

  const renderAuthFields = () => {
    const authType = connector.authType;
    
    if (authType === 'OAUTH' || authType === 'OAUTH2_AUTHCODE') {
      return (
        <div className="space-y-4">
          <div>
            <label className="block label mb-2">Client ID</label>
            <input
              type="text"
              className="w-full px-3 py-2.5 border border-edge-strong text-body-sm focus:border-neutral-900 focus:outline-none transition-colors"
              placeholder="Enter OAuth Client ID"
              value={config.clientId}
              onChange={e => setConfig({ ...config, clientId: e.target.value })}
            />
          </div>
          <div>
            <label className="block label mb-2">Client Secret</label>
            <input
              type="password"
              className="w-full px-3 py-2.5 border border-edge-strong text-body-sm focus:border-neutral-900 focus:outline-none transition-colors"
              placeholder="Enter OAuth Client Secret"
              value={config.clientSecret}
              onChange={e => setConfig({ ...config, clientSecret: e.target.value })}
            />
          </div>
          {(connector.connectorCode === 'MS_DYNAMICS_365') && (
            <div>
              <label className="block label mb-2">Tenant ID</label>
              <input
                type="text"
                className="w-full px-3 py-2.5 border border-edge-strong text-body-sm focus:border-neutral-900 focus:outline-none transition-colors"
                placeholder="Azure AD Tenant ID"
                value={config.tenantId}
                onChange={e => setConfig({ ...config, tenantId: e.target.value })}
              />
            </div>
          )}
        </div>
      );
    }

    if (authType === 'API_KEY') {
      return (
        <div className="space-y-4">
          <div>
            <label className="block label mb-2">API Key</label>
            <input
              type="password"
              className="w-full px-3 py-2.5 border border-edge-strong text-body-sm focus:border-neutral-900 focus:outline-none transition-colors"
              placeholder="Enter API Key"
              value={config.apiKey}
              onChange={e => setConfig({ ...config, apiKey: e.target.value })}
            />
          </div>
          <div>
            <label className="block label mb-2">Endpoint URL</label>
            <input
              type="text"
              className="w-full px-3 py-2.5 border border-edge-strong text-body-sm focus:border-neutral-900 focus:outline-none transition-colors"
              placeholder="https://api.example.com"
              value={config.host}
              onChange={e => setConfig({ ...config, host: e.target.value })}
            />
          </div>
        </div>
      );
    }

    if (authType === 'CERTIFICATE') {
      return (
        <div className="space-y-4">
          <div>
            <label className="block label mb-2">Host</label>
            <input
              type="text"
              className="w-full px-3 py-2.5 border border-edge-strong text-body-sm focus:border-neutral-900 focus:outline-none transition-colors"
              placeholder="sftp.example.com"
              value={config.host}
              onChange={e => setConfig({ ...config, host: e.target.value })}
            />
          </div>
          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="block label mb-2">Port</label>
              <input
                type="text"
                className="w-full px-3 py-2.5 border border-edge-strong text-body-sm focus:border-neutral-900 focus:outline-none transition-colors"
                placeholder="22"
                value={config.port}
                onChange={e => setConfig({ ...config, port: e.target.value })}
              />
            </div>
            <div>
              <label className="block label mb-2">Username</label>
              <input
                type="text"
                className="w-full px-3 py-2.5 border border-edge-strong text-body-sm focus:border-neutral-900 focus:outline-none transition-colors"
                placeholder="sftp_user"
                value={config.username}
                onChange={e => setConfig({ ...config, username: e.target.value })}
              />
            </div>
          </div>
          <div>
            <label className="block label mb-2">Private Key</label>
            <textarea
              className="w-full h-24 px-3 py-2.5 border border-edge-strong text-body-sm font-mono focus:border-neutral-900 focus:outline-none transition-colors resize-none"
              placeholder="-----BEGIN RSA PRIVATE KEY-----"
              value={config.privateKey}
              onChange={e => setConfig({ ...config, privateKey: e.target.value })}
            />
          </div>
        </div>
      );
    }

    // Open Banking auth types
    if (['PSD2_CONSENT', 'OPEN_BANKING_UK', 'BERLIN_GROUP', 'STET', 'POLISH_API'].includes(authType)) {
      return (
        <div className="space-y-4">
          <div className="p-4 bg-surface-page border border-edge">
            <div className="flex items-start gap-3">
              <Shield className="w-5 h-5 text-neutral-600 mt-0.5 dark:text-neutral-300" />
              <div>
                <p className="body-strong">Bank Authorization Required</p>
                <p className="caption mt-1">
                  You will be redirected to your bank to authorize access. This uses {connector.authType.replace('_', ' ')} authentication.
                </p>
              </div>
            </div>
          </div>
          <div>
            <label className="block label mb-2">TPP Certificate</label>
            <div className="border border-dashed border-edge-strong p-6 text-center">
              <Upload className="w-6 h-6 text-neutral-400 mx-auto mb-2 dark:text-neutral-400" />
              <p className="caption">Drop certificate file or click to upload</p>
            </div>
          </div>
        </div>
      );
    }

    // Default: Basic auth
    return (
      <div className="space-y-4">
        <div>
          <label className="block label mb-2">Username</label>
          <input
            type="text"
            className="w-full px-3 py-2.5 border border-edge-strong text-body-sm focus:border-neutral-900 focus:outline-none transition-colors"
            placeholder="Enter username"
            value={config.username}
            onChange={e => setConfig({ ...config, username: e.target.value })}
          />
        </div>
        <div>
          <label className="block label mb-2">Password</label>
          <input
            type="password"
            className="w-full px-3 py-2.5 border border-edge-strong text-body-sm focus:border-neutral-900 focus:outline-none transition-colors"
            placeholder="Enter password"
            value={config.clientSecret}
            onChange={e => setConfig({ ...config, clientSecret: e.target.value })}
          />
        </div>
      </div>
    );
  };

  return (
    <Modal isOpen={!!connector} onClose={onClose} title="" size="lg">
      <div className="min-h-[500px] flex flex-col">
        {/* Header */}
        <div className="pb-6 border-b border-edge mb-6">
          <div className="flex items-center gap-4">
            <div className="w-12 h-12 border border-edge flex items-center justify-center">
              <IconComponent size={24} className="text-neutral-700 dark:text-neutral-200" />
            </div>
            <div>
              <h2 className="text-body-lg font-medium text-neutral-900 dark:text-neutral-50">Connect {connector.connectorName}</h2>
              <p className="body-sm">{categoryConfig[connector.category].label} Integration</p>
            </div>
          </div>
        </div>

        {/* Step Indicator */}
        <div className="flex items-center gap-1 mb-8">
          {['Details', 'Credentials', 'Settings', 'Verify'].map((label, idx) => (
            <React.Fragment key={idx}>
              <div className={cn(
                "flex items-center gap-2 px-3 py-1.5 text-caption font-medium transition-colors",
                step === idx + 1 && "bg-neutral-900 text-white",
                step > idx + 1 && "bg-surface-muted text-neutral-900 dark:text-neutral-50",
                step < idx + 1 && "text-neutral-400"
              )}>
                {step > idx + 1 ? <Check className="w-3 h-3" /> : <span>{idx + 1}</span>}
                <span className="inline">{label}</span>
              </div>
              {idx < 3 && <div className={cn("flex-1 h-px", step > idx + 1 ? "bg-neutral-900" : "bg-neutral-200 dark:bg-primary-800")} />}
            </React.Fragment>
          ))}
        </div>

        {/* Step Content */}
        <div className="flex-1">
          {step === 1 && (
            <div className="space-y-6">
              <div>
                <label className="block label mb-2">Connection Name</label>
                <input
                  type="text"
                  className="w-full px-3 py-2.5 border border-edge-strong text-body-sm focus:border-neutral-900 focus:outline-none transition-colors"
                  placeholder={`My ${connector.shortName} Connection`}
                  value={config.name}
                  onChange={e => setConfig({ ...config, name: e.target.value })}
                />
              </div>
              <div>
                <label className="block label mb-3">Environment</label>
                <div className="grid grid-cols-2 gap-3">
                  {['SANDBOX', 'PRODUCTION'].map(env => (
                    <button
                      key={env}
                      onClick={() => setConfig({ ...config, environment: env })}
                      className={cn(
                        "p-4 border text-left transition-all",
                        config.environment === env
                          ? "border-neutral-900 bg-neutral-900 text-white"
                          : "border-edge hover:border-neutral-400"
                      )}
                    >
                      <p className="text-body-sm font-medium">{env === 'SANDBOX' ? 'Sandbox' : 'Production'}</p>
                      <p className={cn("text-caption mt-1", config.environment === env ? "text-neutral-300 dark:text-neutral-400" : "text-neutral-500 dark:text-neutral-400")}>
                        {env === 'SANDBOX' ? 'For testing and development' : 'Live production data'}
                      </p>
                    </button>
                  ))}
                </div>
              </div>
            </div>
          )}

          {step === 2 && (
            <div className="space-y-6">
              <div className="p-4 bg-surface-page border border-edge mb-6">
                <div className="flex items-center gap-2 caption">
                  <Shield className="w-4 h-4" />
                  <span>Credentials are encrypted using AES-256 and stored securely</span>
                </div>
              </div>
              {renderAuthFields()}
            </div>
          )}

          {step === 3 && (
            <div className="space-y-6">
              <div>
                <label className="block label mb-3">Sync Frequency</label>
                <div className="grid grid-cols-2 gap-3">
                  {[
                    { value: 'REAL_TIME', label: 'Real-Time', desc: 'Instant sync' },
                    { value: 'HOURLY', label: 'Hourly', desc: 'Every hour' },
                    { value: 'DAILY', label: 'Daily', desc: 'Once per day' },
                    { value: 'MANUAL', label: 'Manual', desc: 'On-demand' },
                  ].map(freq => (
                    <button
                      key={freq.value}
                      onClick={() => setConfig({ ...config, syncFrequency: freq.value })}
                      className={cn(
                        "p-3 border text-left transition-all",
                        config.syncFrequency === freq.value
                          ? "border-neutral-900 bg-neutral-900 text-white"
                          : "border-edge hover:border-neutral-400"
                      )}
                    >
                      <p className="text-body-sm font-medium">{freq.label}</p>
                      <p className={cn("text-caption", config.syncFrequency === freq.value ? "text-neutral-300 dark:text-neutral-400" : "text-neutral-500 dark:text-neutral-400")}>{freq.desc}</p>
                    </button>
                  ))}
                </div>
              </div>
              <div className="p-4 bg-surface-page border border-edge">
                <p className="text-caption font-medium text-neutral-700 uppercase tracking-wide mb-3 dark:text-neutral-200">Default Data Flows</p>
                <div className="space-y-2">
                  {connector.supportedFeatures.slice(0, 4).map((feature, idx) => (
                    <div key={idx} className="flex items-center justify-between py-1.5">
                      <span className="body-sm">{feature}</span>
                      <span className="caption">Enabled</span>
                    </div>
                  ))}
                </div>
              </div>
            </div>
          )}

          {step === 4 && (
            <div className="space-y-6">
              <div className="p-4 bg-surface-page border border-edge">
                <div className="grid grid-cols-2 gap-4 text-body-sm">
                  <div>
                    <p className="label">Name</p>
                    <p className="font-medium text-neutral-900 mt-1 dark:text-neutral-50">{config.name || `My ${connector.shortName}`}</p>
                  </div>
                  <div>
                    <p className="label">Environment</p>
                    <p className="font-medium text-neutral-900 mt-1 dark:text-neutral-50">{config.environment}</p>
                  </div>
                  <div>
                    <p className="label">Auth Type</p>
                    <p className="font-medium text-neutral-900 mt-1 dark:text-neutral-50">{connector.authType.replace('_', ' ')}</p>
                  </div>
                  <div>
                    <p className="label">Sync</p>
                    <p className="font-medium text-neutral-900 mt-1 dark:text-neutral-50">{config.syncFrequency.replace('_', ' ')}</p>
                  </div>
                </div>
              </div>

              <div className="text-center py-8">
                {!testing && !testResult && (
                  <button
                    onClick={testConnection}
                    className="px-6 py-2.5 bg-neutral-900 text-white text-body-sm font-medium hover:bg-neutral-800 transition-colors"
                  >
                    Test Connection
                  </button>
                )}
                {testing && (
                  <div>
                    <Loader2 className="w-8 h-8 text-neutral-400 animate-spin mx-auto mb-3 dark:text-neutral-400" />
                    <p className="body-sm">Testing connection...</p>
                  </div>
                )}
                {testResult === 'success' && (
                  <div>
                    <StatusIconBadge tone="neutral" solid icon={Check} size="lg" className="mx-auto mb-3" />
                    <p className="body-strong">Connection Successful</p>
                    <p className="caption mt-1">{testMessage}</p>
                  </div>
                )}
                {testResult === 'error' && (
                  <div>
                    <StatusIconBadge tone="error" icon={X} size="lg" solid className="mx-auto mb-3" />
                    <p className="body-strong">Connection Failed</p>
                    <p className="caption mt-1">{testMessage}</p>
                    <button onClick={testConnection} className="mt-4 caption hover:text-neutral-900 underline dark:hover:text-neutral-50">
                      Retry
                    </button>
                  </div>
                )}
              </div>
            </div>
          )}
        </div>

        {/* Footer */}
        <div className="flex justify-between pt-6 mt-6 border-t border-edge">
          <button
            onClick={step === 1 ? onClose : () => setStep(step - 1)}
            className="px-4 py-2 body-sm hover:text-neutral-900 transition-colors dark:hover:text-neutral-50"
          >
            {step === 1 ? 'Cancel' : 'Back'}
          </button>
          {step < 4 ? (
            <button
              onClick={() => setStep(step + 1)}
              className="px-6 py-2 bg-neutral-900 text-white text-body-sm font-medium hover:bg-neutral-800 transition-colors"
            >
              Continue
            </button>
          ) : (
            <button
              onClick={handleComplete}
              disabled={testing}
              className="px-6 py-2 bg-neutral-900 text-white text-body-sm font-medium hover:bg-neutral-800 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
            >
              Complete Setup
            </button>
          )}
        </div>
      </div>
    </Modal>
  );
};

// ============================================================================
// CONNECTION DETAIL MODAL
// ============================================================================

const ConnectionDetailModal: React.FC<{
  connection: Connection | null;
  onClose: () => void;
  onOpenMapping: (flow: DataFlow) => void;
  logs: SyncLog[];
  onSync: () => void;
  onToggleFlow: (flowId: string) => void;
}> = ({ connection, onClose, onOpenMapping, logs, onSync, onToggleFlow }) => {
  const [activeTab, setActiveTab] = useState<'overview' | 'flows' | 'logs'>('overview');

  if (!connection) return null;

  const IconComponent = getConnectorIcon(connection.iconType || connection.connectorCode);
  const connectionLogs = logs.filter(log => log.connectionId === connection.id);

  return (
    <Modal isOpen={!!connection} onClose={onClose} title="" size="xl">
      {/* Header */}
      <div className="flex items-center justify-between pb-6 border-b border-edge mb-6">
        <div className="flex items-center gap-4">
          <div className="w-12 h-12 border border-edge flex items-center justify-center">
            <IconComponent size={24} className="text-neutral-700 dark:text-neutral-200" />
          </div>
          <div>
            <h2 className="text-body-lg font-medium text-neutral-900 dark:text-neutral-50">{connection.connectionName}</h2>
            <div className="flex items-center gap-2 mt-1">
              <span className={cn(
                "px-2 py-0.5 text-caption font-medium uppercase tracking-wide",
                connection.environment === 'PRODUCTION' ? 'bg-neutral-900 text-white' : 'bg-surface-muted text-neutral-600 dark:text-neutral-300'
              )}>
                {connection.environment}
              </span>
              <span className={cn(
                "px-2 py-0.5 text-caption font-medium uppercase tracking-wide",
                connection.status === 'CONNECTED' ? 'bg-neutral-900 text-white' : 'bg-error-600 text-white'
              )}>
                {connection.status}
              </span>
            </div>
          </div>
        </div>
        <div className="text-right">
          <p className="caption">Last Sync</p>
          <p className="body-strong">{connection.lastSyncAt ? formatRelativeTime(connection.lastSyncAt) : '—'}</p>
        </div>
      </div>

      {/* Tabs */}
      <div className="flex gap-6 mb-6 border-b border-edge">
        {[
          { id: 'overview', label: 'Overview' },
          { id: 'flows', label: 'Data Flows', count: connection.dataFlows?.length || 0 },
          { id: 'logs', label: 'Sync Logs', count: connectionLogs.length },
        ].map(tab => (
          <button
            key={tab.id}
            onClick={() => setActiveTab(tab.id as typeof activeTab)}
            className={cn(
              "pb-3 text-body-sm font-medium transition-colors border-b-2 -mb-px",
              activeTab === tab.id
                ? "border-neutral-900 text-neutral-900 dark:text-neutral-50"
                : "border-transparent text-neutral-500 hover:text-neutral-700 dark:text-neutral-400 dark:hover:text-neutral-200"
            )}
          >
            {tab.label}
            {tab.count !== undefined && (
              <span className="ml-2 px-1.5 py-0.5 caption bg-surface-muted">{tab.count}</span>
            )}
          </button>
        ))}
      </div>

      {/* Tab Content */}
      <div className="min-h-[300px]">
        {activeTab === 'overview' && (
          <div className="space-y-6">
            <div className="grid grid-cols-4 gap-4">
              {[
                { label: 'Frequency', value: connection.syncFrequency.replace('_', ' ') },
                { label: 'Next Sync', value: connection.nextSyncAt ? formatRelativeTime(connection.nextSyncAt) : 'Manual' },
                { label: 'Created', value: formatRelativeTime(connection.createdAt) },
                { label: 'Created By', value: connection.createdBy },
              ].map((item, idx) => (
                <div key={idx} className="p-4 bg-surface-page border border-edge-subtle">
                  <p className="caption">{item.label}</p>
                  <p className="body-strong mt-1 capitalize">{item.value}</p>
                </div>
              ))}
            </div>

            {connection.errorMessage && (
              <div className="p-4 bg-error-50 border border-error-200 dark:bg-error-500/10 dark:border-error-500/30">
                <div className="flex items-start gap-3">
                  <AlertTriangle className="w-5 h-5 text-error-600 mt-0.5 dark:text-error-300" />
                  <div>
                    <p className="text-body-sm font-medium text-error-800 dark:text-error-300">Connection Error</p>
                    <p className="text-body-sm text-error-700 mt-1 dark:text-error-300">{connection.errorMessage}</p>
                    <button className="mt-3 px-3 py-1.5 text-caption font-medium border border-error-300 text-error-700 hover:bg-error-100 transition-colors dark:text-error-300 dark:hover:bg-error-500/20">
                      Re-authenticate
                    </button>
                  </div>
                </div>
              </div>
            )}

            {connection.consentInfo && (
              <div className="p-4 bg-surface-page border border-edge">
                <p className="text-caption font-medium text-neutral-700 uppercase tracking-wide mb-3 dark:text-neutral-200">Open Banking Consent</p>
                <div className="grid grid-cols-2 gap-4 text-body-sm">
                  <div>
                    <p className="caption">Bank</p>
                    <p className="font-medium text-neutral-900 dark:text-neutral-50">{connection.consentInfo.aspspName}</p>
                  </div>
                  <div>
                    <p className="caption">Status</p>
                    <p className="font-medium text-neutral-900 dark:text-neutral-50">{connection.consentInfo.consentStatus}</p>
                  </div>
                  <div>
                    <p className="caption">Expires</p>
                    <p className="font-medium text-neutral-900 dark:text-neutral-50">{formatRelativeTime(connection.consentInfo.consentExpiresAt)}</p>
                  </div>
                  <div>
                    <p className="caption">Permissions</p>
                    <p className="font-medium text-neutral-900 dark:text-neutral-50">{connection.consentInfo.permissions.join(', ')}</p>
                  </div>
                </div>
              </div>
            )}

            <div className="flex gap-2">
              <button onClick={onSync} className="px-4 py-2 text-body-sm font-medium border border-edge-strong hover:border-neutral-900 transition-colors flex items-center gap-2">
                <RefreshCw className="w-4 h-4" /> Sync Now
              </button>
              <button className="px-4 py-2 text-body-sm font-medium border border-edge-strong hover:border-neutral-900 transition-colors flex items-center gap-2">
                <Settings className="w-4 h-4" /> Settings
              </button>
              <button className="px-4 py-2 text-body-sm font-medium border border-edge-strong hover:border-neutral-900 transition-colors flex items-center gap-2">
                <Key className="w-4 h-4" /> Credentials
              </button>
            </div>
          </div>
        )}

        {activeTab === 'flows' && (
          <div className="space-y-3">
            {connection.dataFlows?.map(flow => (
              <div key={flow.id} className="border border-edge hover:border-neutral-300 transition-colors dark:hover:border-primary-700">
                <div className="p-4 flex items-center justify-between">
                  <div className="flex items-center gap-4">
                    <div className={cn(
                      "w-8 h-8 flex items-center justify-center",
                      flow.direction === 'INBOUND' ? 'bg-surface-muted' : flow.direction === 'OUTBOUND' ? 'bg-neutral-900 text-white' : 'bg-neutral-200 dark:bg-primary-800'
                    )}>
                      {flow.direction === 'INBOUND' ? <Download className="w-4 h-4" /> :
                       flow.direction === 'OUTBOUND' ? <Upload className="w-4 h-4" /> :
                       <ArrowLeftRight className="w-4 h-4" />}
                    </div>
                    <div>
                      <p className="body-strong">{flow.flowName}</p>
                      <p className="caption">{flow.sourceEntity} → {flow.targetEntity}</p>
                    </div>
                  </div>
                  <div className="flex items-center gap-6">
                    <div className="text-right">
                      <p className="body-strong">{flow.recordsProcessed?.toLocaleString() || 0}</p>
                      <p className="caption">Records</p>
                    </div>
                    <span className={cn(
                      "px-2 py-0.5 text-caption font-medium uppercase tracking-wide",
                      flow.status === 'ACTIVE' ? 'bg-neutral-900 text-white' : flow.status === 'PAUSED' ? 'bg-neutral-200 text-neutral-600 dark:bg-primary-800 dark:text-neutral-300' : 'bg-error-600 text-white'
                    )}>
                      {flow.status}
                    </span>
                    <div className="flex gap-1">
                      <button onClick={() => onOpenMapping(flow)} className="p-2 text-neutral-400 hover:text-neutral-900 transition-colors dark:text-neutral-400 dark:hover:text-neutral-50" title="Field Mapping">
                        <FileText className="w-4 h-4" />
                      </button>
                      <button onClick={() => onToggleFlow(flow.id)} className="p-2 text-neutral-400 hover:text-neutral-900 transition-colors dark:text-neutral-400 dark:hover:text-neutral-50" title={flow.status === 'ACTIVE' ? 'Pause' : 'Resume'}>
                        {flow.status === 'ACTIVE' ? <Pause className="w-4 h-4" /> : <Play className="w-4 h-4" />}
                      </button>
                    </div>
                  </div>
                </div>
              </div>
            ))}
            <button className="w-full p-4 border border-dashed border-edge-strong body-sm hover:border-neutral-900 hover:text-neutral-900 transition-colors flex items-center justify-center gap-2 dark:hover:text-neutral-50">
              <Plus className="w-4 h-4" /> Add Data Flow
            </button>
          </div>
        )}

        {activeTab === 'logs' && (
          <DataTable
            hairline
            data={connectionLogs}
            keyExtractor={(log) => log.id}
            columns={[
              { key: 'flowName', header: 'Flow', minWidth: 180, mobileLabel: true, render: (_v, log) => <span className="text-body-sm text-neutral-900 dark:text-neutral-50">{log.flowName}</span> },
              { key: 'direction', header: 'Direction', minWidth: 130, dropOrder: 2, render: (_v, log) => (
                <span className={cn(
                  "px-2 py-0.5 text-caption font-medium uppercase",
                  log.direction === 'INBOUND' ? 'bg-surface-muted text-neutral-600 dark:text-neutral-300' : 'bg-neutral-900 text-white'
                )}>
                  {log.direction}
                </span>
              ) },
              { key: 'startTime', header: 'Time', minWidth: 130, dropOrder: 1, render: (_v, log) => <span className="body-sm">{formatRelativeTime(log.startTime)}</span> },
              { key: 'recordsProcessed', header: 'Records', align: 'right', minWidth: 130, render: (_v, log) => (
                <>
                  <p className="body-strong">{log.recordsProcessed.toLocaleString()}</p>
                  {log.recordsFailed > 0 && <p className="caption-error">{log.recordsFailed} failed</p>}
                </>
              ) },
              { key: 'status', header: 'Status', align: 'center', minWidth: 120, render: (_v, log) => (
                <span className={cn(
                  "px-2 py-0.5 text-caption font-medium uppercase",
                  log.status === 'SUCCESS' ? 'bg-neutral-900 text-white' :
                  log.status === 'PARTIAL' ? 'bg-warning-100 text-warning-800 dark:bg-warning-500/20 dark:text-warning-300' :
                  log.status === 'RUNNING' ? 'bg-info-100 text-info-800 dark:bg-info-500/20 dark:text-info-300' : 'bg-error-600 text-white'
                )}>
                  {log.status}
                </span>
              ) },
            ]}
          />
        )}
      </div>

      {/* Footer */}
      <div className="flex justify-end pt-6 mt-6 border-t border-edge">
        <button onClick={onClose} className="px-4 py-2 body-sm hover:text-neutral-900 transition-colors dark:hover:text-neutral-50">
          Close
        </button>
      </div>
    </Modal>
  );
};

// ============================================================================
// FIELD MAPPING MODAL
// ============================================================================

const FieldMappingModal: React.FC<{
  flow: DataFlow | null;
  onClose: () => void;
  onSave: (flowId: string, mappings: FieldMapping[]) => Promise<unknown>;
}> = ({ flow, onClose, onSave }) => {
  // The flow's own stored mappings (keyed remount per flow), never a sample set.
  const [mappings, setMappings] = useState<FieldMapping[]>(flow?.fieldMappings ?? []);

  if (!flow) return null;

  const save = async () => {
    try {
      // New rows carry a client-only id; the backend assigns real ones.
      await onSave(flow.id, mappings.map(m => (m.id.startsWith('new-') ? { ...m, id: undefined as unknown as string } : m)));
      onClose();
    } catch (e) {
      toast.error(errMsg(e));
    }
  };

  const addMapping = () => {
    setMappings([...mappings, { id: `new-${Date.now()}`, sourceField: '', targetField: '', isRequired: false }]);
  };

  const removeMapping = (id: string) => {
    setMappings(mappings.filter(m => m.id !== id));
  };

  return (
    <Modal isOpen={!!flow} onClose={onClose} title="" size="xl">
      {/* Header */}
      <div className="pb-6 border-b border-edge mb-6">
        <h2 className="text-body-lg font-medium text-neutral-900 dark:text-neutral-50">Field Mapping</h2>
        <p className="body-sm mt-1">{flow.flowName}</p>
        <div className="flex items-center gap-2 mt-3">
          <span className="px-2 py-1 text-caption bg-surface-muted text-neutral-700 dark:text-neutral-200">{flow.sourceEntity}</span>
          <ArrowRight className="w-4 h-4 text-neutral-400" />
          <span className="px-2 py-1 text-caption bg-neutral-900 text-white">{flow.targetEntity}</span>
        </div>
      </div>

      {/* Mapping Table */}
      <div className="border border-edge mb-4">
        {/* eslint-disable-next-line no-restricted-syntax -- editable field-mapping grid (inputs in cells) */}
        <table className="w-full">
          <thead>
            <tr className="bg-surface-page border-b border-edge">
              <th className="px-4 py-3 text-left label-cased w-1/4">Source</th>
              <th className="px-4 py-3 text-center label-cased w-12"></th>
              <th className="px-4 py-3 text-left label-cased w-1/4">Target</th>
              <th className="px-4 py-3 text-left label-cased">Transform</th>
              <th className="px-4 py-3 text-center label-cased w-20">Required</th>
              <th className="px-4 py-3 text-center w-12"></th>
            </tr>
          </thead>
          <tbody className="divide-y divide-edge-subtle">
            {mappings.map((mapping) => (
              <tr key={mapping.id} className="hover:bg-neutral-50 dark:hover:bg-primary-800/50">
                <td className="px-4 py-2">
                  <input
                    type="text"
                    value={mapping.sourceField}
                    onChange={e => setMappings(mappings.map(m => m.id === mapping.id ? { ...m, sourceField: e.target.value } : m))}
                    className="w-full px-2 py-1.5 border border-edge text-body-sm font-mono focus:border-neutral-900 focus:outline-none"
                    placeholder="Source field"
                  />
                </td>
                <td className="px-4 py-2 text-center">
                  <ArrowRight className="w-4 h-4 text-neutral-300 mx-auto dark:text-neutral-400" />
                </td>
                <td className="px-4 py-2">
                  <input
                    type="text"
                    value={mapping.targetField}
                    onChange={e => setMappings(mappings.map(m => m.id === mapping.id ? { ...m, targetField: e.target.value } : m))}
                    className="w-full px-2 py-1.5 border border-edge text-body-sm font-mono focus:border-neutral-900 focus:outline-none"
                    placeholder="Target field"
                  />
                </td>
                <td className="px-4 py-2">
                  <select
                    value={mapping.transformation || 'direct'}
                    onChange={e => setMappings(mappings.map(m => m.id === mapping.id ? { ...m, transformation: e.target.value } : m))}
                    className="w-full px-2 py-1.5 border border-edge text-body-sm focus:border-neutral-900 focus:outline-none bg-surface-card"
                  >
                    <option value="direct">Direct</option>
                    <option value="trim">Trim</option>
                    <option value="uppercase">Uppercase</option>
                    <option value="lowercase">Lowercase</option>
                    <option value="number:2">Number (2 dec)</option>
                    <option value="date:YYYY-MM-DD">Date</option>
                    <option value="lookup:currency_map">Lookup</option>
                  </select>
                </td>
                <td className="px-4 py-2 text-center">
                  <Checkbox
                    size="sm"
                    className="inline-flex"
                    aria-label={`Required: ${mapping.sourceField}`}
                    checked={mapping.isRequired}
                    onChange={checked => setMappings(mappings.map(m => m.id === mapping.id ? { ...m, isRequired: checked } : m))}
                  />
                </td>
                <td className="px-4 py-2 text-center">
                  <button onClick={() => removeMapping(mapping.id)} className="p-1 text-neutral-400 hover:text-error-600 transition-colors dark:text-neutral-400">
                    <X className="w-4 h-4" />
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <button onClick={addMapping} className="body-sm hover:text-neutral-900 flex items-center gap-1 transition-colors dark:hover:text-neutral-50">
        <Plus className="w-4 h-4" /> Add Mapping
      </button>

      {/* Footer */}
      <div className="flex justify-between pt-6 mt-6 border-t border-edge">
        <button onClick={onClose} className="px-4 py-2 body-sm hover:text-neutral-900 transition-colors dark:hover:text-neutral-50">
          Cancel
        </button>
        <div className="flex gap-2">
          <button onClick={save} className="px-6 py-2 bg-neutral-900 text-white text-body-sm font-medium hover:bg-neutral-800 transition-colors">
            Save
          </button>
        </div>
      </div>
    </Modal>
  );
};

// ============================================================================
// MAIN PAGE - Swiss Minimalism Design
// ============================================================================

const IntegrationsPage: React.FC = () => {
  const [activeTab, setActiveTab] = useState<'connections' | 'connectors' | 'logs'>('connections');
  const [selectedConnector, setSelectedConnector] = useState<Connector | null>(null);
  const [selectedConnection, setSelectedConnection] = useState<Connection | null>(null);
  const [selectedFlow, setSelectedFlow] = useState<DataFlow | null>(null);
  const {
    connectors, connections, stats, syncLogs, error,
    createConnection, deleteConnection, testConnection, triggerSync, toggleFlowStatus, updateFieldMappings,
  } = useIntegrations();
  const [filterCategory, setFilterCategory] = useState<string>('all');
  const [searchTerm, setSearchTerm] = useState('');

  // Every action goes to the backend; what it refuses ("Not available: ...") is shown as is.
  const run = async (action: () => Promise<unknown>, done?: string) => {
    try {
      await action();
      if (done) toast.success(done);
    } catch (e) {
      toast.error(errMsg(e));
    }
  };

  const filteredConnectors = connectors.filter(c => {
    const matchesCategory = filterCategory === 'all' || c.category === filterCategory;
    const matchesSearch = !searchTerm || 
      c.connectorName.toLowerCase().includes(searchTerm.toLowerCase()) ||
      c.description.toLowerCase().includes(searchTerm.toLowerCase());
    return matchesCategory && matchesSearch;
  });

  const handleSetupComplete = (config: Record<string, unknown>) => {
    if (!selectedConnector) return;
    const connector = selectedConnector;
    run(async () => {
      await createConnection({
        connectorId: connector.id,
        connectionName: (config.name as string) || `My ${connector.shortName}`,
        environment: (config.environment as Environment) || 'SANDBOX',
        syncFrequency: (config.syncFrequency as SyncFrequency) || 'DAILY',
        credentials: config.credentials as Record<string, unknown>,
      });
      setActiveTab('connections');
    }, 'Connection saved');
  };

  return (
    <div className="min-h-screen bg-surface-page">
      {/* Phase 10 Design System Unification (2026-05-13): the banner chrome
          (header + tab nav below) keeps its full-width bg-white + border-b
          treatment but inner `max-w-7xl mx-auto` no longer adds `px-6` —
          the <main> shell already supplies horizontal padding. The content
          section below uses <Page maxWidth="default"> for max-width and
          space-y-6 rhythm. */}
      <div className="bg-surface-card border-b border-edge">
        <div className="max-w-7xl mx-auto py-8">
          <PageHeader
            title="Integrations"
            description="Connect ERP, treasury, and banking systems"
            actions={
              <>
                <button className="px-4 py-2 text-body-sm border border-edge-strong hover:border-neutral-900 transition-colors flex items-center gap-2">
                  <FileText className="w-4 h-4" /> API Docs
                </button>
                <button
                  onClick={() => setActiveTab('connectors')}
                  className="px-4 py-2 text-body-sm bg-neutral-900 text-white hover:bg-neutral-800 transition-colors flex items-center gap-2"
                >
                  <Plus className="w-4 h-4" /> Add Connection
                </button>
              </>
            }
          />

          {/* Stats */}
          <div className="grid grid-cols-5 gap-6 mt-8">
            {[
              { label: 'Total', value: stats?.totalConnections ?? '—', sub: 'connections' },
              { label: 'Active', value: stats?.activeConnections ?? '—', sub: 'connected' },
              { label: 'Errors', value: stats?.errorConnections ?? '—', sub: 'need attention', alert: (stats?.errorConnections ?? 0) > 0 },
              { label: 'Data Flows', value: stats?.totalDataFlows ?? '—', sub: 'configured' },
              { label: 'Connectors', value: stats?.availableConnectors ?? '—', sub: 'available' },
            ].map((stat, idx) => (
              <div key={idx} className="text-center">
                <p className={cn("text-heading-lg font-light", stat.alert ? 'text-error-600 dark:text-error-300' : 'text-neutral-900 dark:text-neutral-50')}>{stat.value}</p>
                <p className="caption mt-1">{stat.label}</p>
              </div>
            ))}
          </div>
        </div>
      </div>

      {/* Tab Navigation */}
      <div className="bg-surface-card border-b border-edge">
        <div className="max-w-7xl mx-auto">
          <div className="flex gap-8">
            {[
              { id: 'connections', label: 'Active Connections', count: connections.length },
              { id: 'connectors', label: 'Available Connectors', count: connectors.length },
              { id: 'logs', label: 'Sync History', count: syncLogs.length },
            ].map(tab => (
              <button
                key={tab.id}
                onClick={() => setActiveTab(tab.id as typeof activeTab)}
                className={cn(
                  "py-4 text-body-sm font-medium border-b-2 -mb-px transition-colors",
                  activeTab === tab.id
                    ? "border-neutral-900 text-neutral-900 dark:text-neutral-50"
                    : "border-transparent text-neutral-500 hover:text-neutral-700 dark:text-neutral-400 dark:hover:text-neutral-200"
                )}
              >
                {tab.label}
                <span className="ml-2 caption">{tab.count}</span>
              </button>
            ))}
          </div>
        </div>
      </div>

      {/* Content */}
      <Page maxWidth="default" className="py-8">
        {error && (
          <div className="mb-6 p-4 bg-error-50 border border-error-200 dark:bg-error-500/10 dark:border-error-500/30">
            <p className="text-body-sm text-error-700 dark:text-error-300">Could not load integrations: {error}</p>
          </div>
        )}
        {/* Active Connections */}
        {activeTab === 'connections' && (
          <div className="grid gap-4 grid-cols-2">
            {connections.map(connection => (
              <ConnectionCard
                key={connection.id}
                connection={connection}
                onView={() => setSelectedConnection(connection)}
                onSync={() => run(() => triggerSync(connection.id), 'Sync started')}
                onDisconnect={() => {
                  if (confirm(`Delete the connection "${connection.connectionName}"? This cannot be undone.`)) {
                    run(() => deleteConnection(connection.id), 'Connection deleted');
                  }
                }}
              />
            ))}
            {connections.length === 0 && (
              <div className="col-span-2 text-center py-16 border border-dashed border-edge-strong">
                <Link2 className="w-8 h-8 text-neutral-300 mx-auto mb-3 dark:text-neutral-400" />
                <p className="body-sm">No connections configured</p>
                <button
                  onClick={() => setActiveTab('connectors')}
                  className="mt-4 px-4 py-2 text-body-sm bg-neutral-900 text-white hover:bg-neutral-800 transition-colors"
                >
                  Browse Connectors
                </button>
              </div>
            )}
          </div>
        )}

        {/* Available Connectors */}
        {activeTab === 'connectors' && (
          <>
            {/* Filters */}
            <div className="flex items-center justify-between mb-6">
              <div className="flex gap-2">
                {['all', 'ERP', 'TREASURY', 'OPEN_BANKING', 'PAYMENTS', 'BANKING', 'GENERIC'].map(cat => (
                  <button
                    key={cat}
                    onClick={() => setFilterCategory(cat)}
                    className={cn(
                      "px-3 py-1.5 text-caption font-medium transition-all",
                      filterCategory === cat
                        ? "bg-neutral-900 text-white"
                        : "text-neutral-600 hover:text-neutral-900 dark:text-neutral-300 dark:hover:text-neutral-50"
                    )}
                  >
                    {cat === 'all' ? 'All' : cat === 'OPEN_BANKING' ? 'Open Banking' : cat.charAt(0) + cat.slice(1).toLowerCase()}
                  </button>
                ))}
              </div>
              <div className="relative">
                <Search className="w-4 h-4 text-neutral-400 absolute left-3 top-1/2 -translate-y-1/2 dark:text-neutral-400" />
                <input
                  type="text"
                  placeholder="Search connectors..."
                  value={searchTerm}
                  onChange={e => setSearchTerm(e.target.value)}
                  className="pl-9 pr-4 py-2 w-64 border border-edge-strong text-body-sm focus:border-neutral-900 focus:outline-none transition-colors"
                />
              </div>
            </div>

            <div className="grid gap-4 grid-cols-3">
              {filteredConnectors.map(connector => (
                <ConnectorCard
                  key={connector.id}
                  connector={connector}
                  onSetup={() => setSelectedConnector(connector)}
                />
              ))}
            </div>

            {filteredConnectors.length === 0 && (
              <div className="text-center py-16">
                <p className="body-sm">No connectors match your filters</p>
              </div>
            )}
          </>
        )}

        {/* Sync Logs */}
        {activeTab === 'logs' && (
          <DataTable
            data={syncLogs}
            keyExtractor={(log) => log.id}
            columns={[
              { key: 'connectionName', header: 'Connection', minWidth: 160, mobileLabel: true, render: (_v, log) => <span className="body-strong">{log.connectionName}</span> },
              { key: 'flowName', header: 'Flow', minWidth: 150, dropOrder: 3, render: (_v, log) => <span className="body-sm">{log.flowName}</span> },
              { key: 'direction', header: 'Direction', minWidth: 140, dropOrder: 2, render: (_v, log) => (
                <span className={cn(
                  "inline-flex items-center gap-1 px-2 py-0.5 text-caption font-medium uppercase",
                  log.direction === 'INBOUND' ? 'bg-surface-muted text-neutral-600 dark:text-neutral-300' : 'bg-neutral-900 text-white'
                )}>
                  {log.direction === 'INBOUND' ? <Download className="w-3 h-3" /> : <Upload className="w-3 h-3" />}
                  {log.direction}
                </span>
              ) },
              { key: 'startTime', header: 'Time', minWidth: 130, dropOrder: 1, render: (_v, log) => <span className="body-sm">{formatRelativeTime(log.startTime)}</span> },
              { key: 'recordsProcessed', header: 'Records', align: 'right', minWidth: 130, render: (_v, log) => (
                <>
                  <p className="body-strong">{log.recordsProcessed.toLocaleString()}</p>
                  {log.recordsFailed > 0 && <p className="caption-error">{log.recordsFailed} failed</p>}
                </>
              ) },
              { key: 'status', header: 'Status', align: 'center', minWidth: 120, render: (_v, log) => (
                <span className={cn(
                  "px-2 py-0.5 text-caption font-medium uppercase",
                  log.status === 'SUCCESS' ? 'bg-neutral-900 text-white' :
                  log.status === 'PARTIAL' ? 'bg-warning-100 text-warning-800 dark:bg-warning-500/20 dark:text-warning-300' :
                  log.status === 'RUNNING' ? 'bg-info-100 text-info-800 dark:bg-info-500/20 dark:text-info-300' : 'bg-error-600 text-white'
                )}>
                  {log.status}
                </span>
              ) },
              { key: 'errorMessage', header: 'Details', minWidth: 160, dropOrder: 4, render: (_v, log) => (
                log.errorMessage ? <p className="text-caption text-error-600 truncate max-w-48 dark:text-error-300" title={log.errorMessage}>{log.errorMessage}</p> : null
              ) },
            ]}
          />
        )}
      </Page>

      {/* Modals */}
      <SetupWizardModal
        connector={selectedConnector}
        onClose={() => setSelectedConnector(null)}
        onComplete={handleSetupComplete}
        onTest={testConnection}
      />
      <ConnectionDetailModal
        connection={selectedConnection}
        onClose={() => setSelectedConnection(null)}
        onOpenMapping={(flow) => { setSelectedFlow(flow); }}
        logs={syncLogs}
        onSync={() => selectedConnection && run(() => triggerSync(selectedConnection.id), 'Sync started')}
        onToggleFlow={(flowId) => run(() => toggleFlowStatus(flowId))}
      />
      <FieldMappingModal
        key={selectedFlow?.id ?? 'none'}
        flow={selectedFlow}
        onClose={() => setSelectedFlow(null)}
        onSave={updateFieldMappings}
      />
    </div>
  );
};

export default IntegrationsPage;
