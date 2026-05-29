import React, { useState, useEffect } from 'react';
import {
  Search,
  Globe,
  Shield,
  Building2,
  ChevronRight,
  Loader2,
  Check,
  AlertCircle,
} from 'lucide-react';
import { Modal } from '../components/ui/enhanced';
import { cn } from '../utils';

// ============================================================================
// TYPES
// ============================================================================

interface Aspsp {
  aspspId: string;
  name: string;
  bic?: string;
  country: string;
  logoUrl?: string;
  supportedFeatures: string[];
  supportsAis: boolean;
  supportsPis: boolean;
  supportsCof: boolean;
}

interface OpenBankingSetupProps {
  isOpen: boolean;
  onClose: () => void;
  connectorCode: string;
  connectorName: string;
  onInitiateAuth: (aspspId: string, permissions: string[]) => Promise<{ authorizationUrl: string }>;
  onFetchAspsps: (country?: string, search?: string) => Promise<Aspsp[]>;
}

// ============================================================================
// COUNTRY CONFIG
// ============================================================================

const countries = [
  { code: 'GB', name: 'United Kingdom', flag: '🇬🇧' },
  { code: 'DE', name: 'Germany', flag: '🇩🇪' },
  { code: 'FR', name: 'France', flag: '🇫🇷' },
  { code: 'NL', name: 'Netherlands', flag: '🇳🇱' },
  { code: 'ES', name: 'Spain', flag: '🇪🇸' },
  { code: 'IT', name: 'Italy', flag: '🇮🇹' },
  { code: 'BE', name: 'Belgium', flag: '🇧🇪' },
  { code: 'AT', name: 'Austria', flag: '🇦🇹' },
  { code: 'CH', name: 'Switzerland', flag: '🇨🇭' },
  { code: 'PL', name: 'Poland', flag: '🇵🇱' },
  { code: 'SE', name: 'Sweden', flag: '🇸🇪' },
  { code: 'NO', name: 'Norway', flag: '🇳🇴' },
  { code: 'DK', name: 'Denmark', flag: '🇩🇰' },
  { code: 'FI', name: 'Finland', flag: '🇫🇮' },
  { code: 'IE', name: 'Ireland', flag: '🇮🇪' },
  { code: 'PT', name: 'Portugal', flag: '🇵🇹' },
];

const permissionOptions = [
  { id: 'AIS', label: 'Account Information', desc: 'View balances and transactions' },
  { id: 'PIS', label: 'Payment Initiation', desc: 'Initiate payments from accounts' },
  { id: 'COF', label: 'Confirmation of Funds', desc: 'Check if funds are available' },
];

// ============================================================================
// COMPONENT
// ============================================================================

export const OpenBankingSetupModal: React.FC<OpenBankingSetupProps> = ({
  isOpen,
  onClose,
  connectorCode,
  connectorName,
  onInitiateAuth,
  onFetchAspsps,
}) => {
  const [step, setStep] = useState(1);
  const [selectedCountry, setSelectedCountry] = useState<string>('');
  const [searchTerm, setSearchTerm] = useState('');
  const [aspsps, setAspsps] = useState<Aspsp[]>([]);
  const [selectedAspsp, setSelectedAspsp] = useState<Aspsp | null>(null);
  const [selectedPermissions, setSelectedPermissions] = useState<string[]>(['AIS']);
  const [loading, setLoading] = useState(false);
  const [authorizing, setAuthorizing] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Fetch ASPSPs when country changes
  useEffect(() => {
    if (selectedCountry) {
      setLoading(true);
      setError(null);
      onFetchAspsps(selectedCountry, searchTerm)
        .then(data => {
          setAspsps(data);
          setLoading(false);
        })
        .catch(err => {
          setError('Failed to load banks');
          setLoading(false);
        });
    }
  }, [selectedCountry, onFetchAspsps]);

  // Filter ASPSPs by search
  const filteredAspsps = aspsps.filter(a =>
    a.name.toLowerCase().includes(searchTerm.toLowerCase()) ||
    (a.bic && a.bic.toLowerCase().includes(searchTerm.toLowerCase()))
  );

  const handleInitiateAuth = async () => {
    if (!selectedAspsp) return;
    
    setAuthorizing(true);
    setError(null);
    
    try {
      const response = await onInitiateAuth(selectedAspsp.aspspId, selectedPermissions);
      // Redirect to bank authorization URL
      window.location.href = response.authorizationUrl;
    } catch (err) {
      setError('Failed to initiate authorization');
      setAuthorizing(false);
    }
  };

  const togglePermission = (permId: string) => {
    if (permId === 'AIS') return; // AIS is always required
    setSelectedPermissions(prev =>
      prev.includes(permId)
        ? prev.filter(p => p !== permId)
        : [...prev, permId]
    );
  };

  const resetAndClose = () => {
    setStep(1);
    setSelectedCountry('');
    setSearchTerm('');
    setAspsps([]);
    setSelectedAspsp(null);
    setSelectedPermissions(['AIS']);
    setError(null);
    onClose();
  };

  return (
    <Modal isOpen={isOpen} onClose={resetAndClose} title="" size="lg">
      <div className="min-h-[500px] flex flex-col">
        {/* Header */}
        <div className="pb-6 border-b border-neutral-200 dark:border-primary-800 mb-6">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 bg-neutral-900 flex items-center justify-center">
              <Globe className="w-5 h-5 text-white" />
            </div>
            <div>
              <h2 className="text-lg font-medium text-neutral-900 dark:text-neutral-50">Connect via {connectorName}</h2>
              <p className="text-sm text-neutral-500 dark:text-neutral-400">Securely link your bank account</p>
            </div>
          </div>
        </div>

        {/* Step Indicator */}
        <div className="flex items-center gap-1 mb-8">
          {['Select Country', 'Choose Bank', 'Permissions', 'Authorize'].map((label, idx) => (
            <React.Fragment key={idx}>
              <div className={cn(
                "flex items-center gap-2 px-3 py-1.5 text-xs font-medium transition-colors",
                step === idx + 1 && "bg-neutral-900 text-white",
                step > idx + 1 && "bg-neutral-100 dark:bg-primary-800 text-neutral-900 dark:text-neutral-50",
                step < idx + 1 && "text-neutral-400 dark:text-neutral-500"
              )}>
                {step > idx + 1 ? <Check className="w-3 h-3" /> : <span>{idx + 1}</span>}
                <span className="hidden sm:inline">{label}</span>
              </div>
              {idx < 3 && <div className={cn("flex-1 h-px", step > idx + 1 ? "bg-neutral-900" : "bg-neutral-200 dark:bg-primary-800")} />}
            </React.Fragment>
          ))}
        </div>

        {/* Error Display */}
        {error && (
          <div className="mb-4 p-3 bg-error-50 dark:bg-error-500/10 border border-error-200 dark:border-error-500/30 flex items-center gap-2">
            <AlertCircle className="w-4 h-4 text-error-600 dark:text-error-300" />
            <span className="text-sm text-error-700 dark:text-error-300">{error}</span>
          </div>
        )}

        {/* Step Content */}
        <div className="flex-1">
          {/* Step 1: Country Selection */}
          {step === 1 && (
            <div className="space-y-4">
              <p className="text-sm text-neutral-600 dark:text-neutral-300">Select the country where your bank is located:</p>
              <div className="grid grid-cols-2 sm:grid-cols-4 gap-2">
                {countries.map(country => (
                  <button
                    key={country.code}
                    onClick={() => {
                      setSelectedCountry(country.code);
                      setStep(2);
                    }}
                    className={cn(
                      "p-3 border text-left transition-all hover:border-neutral-400 dark:hover:border-primary-700",
                      selectedCountry === country.code
                        ? "border-neutral-900 bg-neutral-900 text-white"
                        : "border-neutral-200 dark:border-primary-800"
                    )}
                  >
                    <span className="text-lg">{country.flag}</span>
                    <p className="text-xs mt-1 font-medium">{country.name}</p>
                  </button>
                ))}
              </div>
            </div>
          )}

          {/* Step 2: Bank Selection */}
          {step === 2 && (
            <div className="space-y-4">
              <div className="relative">
                <Search className="w-4 h-4 text-neutral-400 dark:text-neutral-500 absolute left-3 top-1/2 -translate-y-1/2" />
                <input
                  type="text"
                  placeholder="Search for your bank..."
                  value={searchTerm}
                  onChange={e => setSearchTerm(e.target.value)}
                  className="w-full pl-10 pr-4 py-2.5 border border-neutral-300 dark:border-primary-700 text-sm focus:border-neutral-900 focus:outline-none"
                />
              </div>

              {loading ? (
                <div className="py-12 text-center">
                  <Loader2 className="w-6 h-6 text-neutral-400 dark:text-neutral-500 animate-spin mx-auto mb-2" />
                  <p className="text-sm text-neutral-500 dark:text-neutral-400">Loading banks...</p>
                </div>
              ) : (
                <div className="max-h-64 overflow-y-auto border border-neutral-200 dark:border-primary-800">
                  {filteredAspsps.length === 0 ? (
                    <div className="p-6 text-center text-sm text-neutral-500 dark:text-neutral-400">
                      No banks found
                    </div>
                  ) : (
                    filteredAspsps.map(aspsp => (
                      <button
                        key={aspsp.aspspId}
                        onClick={() => {
                          setSelectedAspsp(aspsp);
                          setStep(3);
                        }}
                        className={cn(
                          "w-full p-4 flex items-center justify-between border-b border-neutral-100 dark:border-primary-800/60 last:border-0 hover:bg-neutral-50 dark:hover:bg-primary-800/50 transition-colors",
                          selectedAspsp?.aspspId === aspsp.aspspId && "bg-neutral-50 dark:bg-primary-950"
                        )}
                      >
                        <div className="flex items-center gap-3">
                          <div className="w-10 h-10 bg-neutral-100 dark:bg-primary-800 flex items-center justify-center">
                            {aspsp.logoUrl ? (
                              <img src={aspsp.logoUrl} alt={aspsp.name} className="w-6 h-6 object-contain" />
                            ) : (
                              <Building2 className="w-5 h-5 text-neutral-500 dark:text-neutral-400" />
                            )}
                          </div>
                          <div className="text-left">
                            <p className="text-sm font-medium text-neutral-900 dark:text-neutral-50">{aspsp.name}</p>
                            {aspsp.bic && <p className="text-xs text-neutral-500 dark:text-neutral-400">{aspsp.bic}</p>}
                          </div>
                        </div>
                        <div className="flex items-center gap-2">
                          {aspsp.supportsAis && <span className="px-1.5 py-0.5 text-[10px] bg-neutral-100 dark:bg-primary-800 text-neutral-600 dark:text-neutral-300">AIS</span>}
                          {aspsp.supportsPis && <span className="px-1.5 py-0.5 text-[10px] bg-neutral-100 dark:bg-primary-800 text-neutral-600 dark:text-neutral-300">PIS</span>}
                          <ChevronRight className="w-4 h-4 text-neutral-400 dark:text-neutral-500" />
                        </div>
                      </button>
                    ))
                  )}
                </div>
              )}
            </div>
          )}

          {/* Step 3: Permissions */}
          {step === 3 && selectedAspsp && (
            <div className="space-y-6">
              <div className="p-4 bg-neutral-50 dark:bg-primary-950 border border-neutral-200 dark:border-primary-800">
                <div className="flex items-center gap-3">
                  <div className="w-10 h-10 bg-white dark:bg-primary-900 border border-neutral-200 dark:border-primary-800 flex items-center justify-center">
                    <Building2 className="w-5 h-5 text-neutral-600 dark:text-neutral-300" />
                  </div>
                  <div>
                    <p className="text-sm font-medium text-neutral-900 dark:text-neutral-50">{selectedAspsp.name}</p>
                    <p className="text-xs text-neutral-500 dark:text-neutral-400">{selectedAspsp.bic || selectedAspsp.country}</p>
                  </div>
                </div>
              </div>

              <div>
                <p className="text-xs font-medium text-neutral-600 dark:text-neutral-300 uppercase tracking-wide mb-3">Select Permissions</p>
                <div className="space-y-2">
                  {permissionOptions.map(perm => {
                    const isSupported =
                      (perm.id === 'AIS' && selectedAspsp.supportsAis) ||
                      (perm.id === 'PIS' && selectedAspsp.supportsPis) ||
                      (perm.id === 'COF' && selectedAspsp.supportsCof);
                    const isSelected = selectedPermissions.includes(perm.id);
                    const isRequired = perm.id === 'AIS';

                    return (
                      <button
                        key={perm.id}
                        onClick={() => isSupported && togglePermission(perm.id)}
                        disabled={!isSupported || isRequired}
                        className={cn(
                          "w-full p-4 border text-left transition-all flex items-center justify-between",
                          isSelected && isSupported && "border-neutral-900 bg-neutral-900 text-white",
                          !isSelected && isSupported && "border-neutral-200 dark:border-primary-800 hover:border-neutral-400 dark:hover:border-primary-700",
                          !isSupported && "border-neutral-100 dark:border-primary-800/60 bg-neutral-50 dark:bg-primary-950 opacity-50 cursor-not-allowed"
                        )}
                      >
                        <div>
                          <p className={cn("text-sm font-medium", isSelected && isSupported ? "text-white" : "text-neutral-900 dark:text-neutral-50")}>
                            {perm.label}
                            {isRequired && <span className="ml-1 text-xs opacity-60">(Required)</span>}
                          </p>
                          <p className={cn("text-xs mt-0.5", isSelected && isSupported ? "text-neutral-300 dark:text-neutral-600" : "text-neutral-500 dark:text-neutral-400")}>
                            {perm.desc}
                          </p>
                        </div>
                        {isSelected && isSupported && <Check className="w-5 h-5" />}
                        {!isSupported && <span className="text-xs text-neutral-400 dark:text-neutral-500">Not supported</span>}
                      </button>
                    );
                  })}
                </div>
              </div>

              <div className="p-4 bg-info-50 dark:bg-info-500/10 border border-info-200 dark:border-info-500/30">
                <div className="flex items-start gap-2">
                  <Shield className="w-4 h-4 text-info-600 dark:text-info-300 mt-0.5" />
                  <div>
                    <p className="text-xs font-medium text-info-800 dark:text-info-300">Secure Authorization</p>
                    <p className="text-xs text-info-700 dark:text-info-300 mt-0.5">
                      You will be redirected to {selectedAspsp.name} to securely authorize access.
                      We never see your login credentials.
                    </p>
                  </div>
                </div>
              </div>
            </div>
          )}

          {/* Step 4: Authorization */}
          {step === 4 && selectedAspsp && (
            <div className="text-center py-8">
              {authorizing ? (
                <div>
                  <Loader2 className="w-12 h-12 text-neutral-400 dark:text-neutral-500 animate-spin mx-auto mb-4" />
                  <p className="text-sm font-medium text-neutral-900 dark:text-neutral-50">Preparing Authorization</p>
                  <p className="text-xs text-neutral-500 dark:text-neutral-400 mt-1">Redirecting to {selectedAspsp.name}...</p>
                </div>
              ) : (
                <div>
                  <div className="w-16 h-16 bg-neutral-100 dark:bg-primary-800 flex items-center justify-center mx-auto mb-4">
                    <Shield className="w-8 h-8 text-neutral-600 dark:text-neutral-300" />
                  </div>
                  <p className="text-sm font-medium text-neutral-900 dark:text-neutral-50">Ready to Authorize</p>
                  <p className="text-xs text-neutral-500 dark:text-neutral-400 mt-1 max-w-sm mx-auto">
                    Click below to connect to {selectedAspsp.name}. You'll be redirected to your bank to complete authorization.
                  </p>
                  <div className="mt-6 p-4 bg-neutral-50 dark:bg-primary-950 border border-neutral-200 dark:border-primary-800 text-left max-w-sm mx-auto">
                    <p className="text-[10px] uppercase tracking-wide text-neutral-500 dark:text-neutral-400 mb-2">Selected Permissions</p>
                    <div className="flex flex-wrap gap-1">
                      {selectedPermissions.map(p => (
                        <span key={p} className="px-2 py-0.5 text-xs bg-neutral-900 text-white">{p}</span>
                      ))}
                    </div>
                  </div>
                </div>
              )}
            </div>
          )}
        </div>

        {/* Footer */}
        <div className="flex justify-between pt-6 mt-6 border-t border-neutral-200 dark:border-primary-800">
          <button
            onClick={step === 1 ? resetAndClose : () => setStep(step - 1)}
            className="px-4 py-2 text-sm text-neutral-600 dark:text-neutral-300 hover:text-neutral-900 dark:hover:text-neutral-50 transition-colors"
            disabled={authorizing}
          >
            {step === 1 ? 'Cancel' : 'Back'}
          </button>
          {step === 3 && (
            <button
              onClick={() => setStep(4)}
              className="px-6 py-2 bg-neutral-900 text-white text-sm font-medium hover:bg-neutral-800 transition-colors"
            >
              Continue
            </button>
          )}
          {step === 4 && (
            <button
              onClick={handleInitiateAuth}
              disabled={authorizing}
              className="px-6 py-2 bg-neutral-900 text-white text-sm font-medium hover:bg-neutral-800 transition-colors disabled:opacity-50"
            >
              {authorizing ? 'Redirecting...' : 'Authorize with Bank'}
            </button>
          )}
        </div>
      </div>
    </Modal>
  );
};

export default OpenBankingSetupModal;
