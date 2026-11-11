import React, { useState, useEffect } from 'react';
import { ArrowLeft, Save, Send, Search, Building2, Calculator, ArrowLeftRight, Paperclip, Loader2, X, Check, Info, CreditCard, Landmark, Clock, RefreshCw, AlertTriangle, CheckCircle, Settings, Receipt, FileText } from 'lucide-react';
import toast from 'react-hot-toast';
import { Card, Button, Badge, StatusIconBadge, Toggle, Input, Select, TextArea } from '../components/ui';
import { CurrencyPicker } from '../components/ui/CurrencyPicker';
import { Page } from '../components/layout/Page';
import { PageHeader } from '../components/layout/PageHeader';
import { payablesApiPhase2, legalEntityApi, partiesApi, corporatesApi, virtualAccountsApi, type CreatePayableRequest } from '../services/api';
import { useNavigation } from '../hooks/useNavigation';
import { useMarket } from '../context/useMarket';
import { formatCurrency } from '../utils';

// ============================================================================
// TYPES
// ============================================================================

/** A vendor bank account as the party detail endpoint returns it. */
interface BankAccount {
  id: string;
  label: string;
  holderName: string;
  bankName: string;
  bankCode: string;
  iban: string;
  accountNumber: string;
  currency: string;
  isPrimary: boolean;
  isVerified: boolean;
  status: string;
}

interface VendorHit {
  id: string;
  name: string;
  partyCode: string;
  partyType: string;
}

interface Vendor extends VendorHit {
  taxId: string;
  email: string;
  bankAccounts: BankAccount[];
  poboEligible: boolean;         // party.poboEligible -- the backend refuses POBO for anyone else
  poboDefaultPayerId?: string;   // the vendor's configured default POBO payer
  groupEntity: boolean;          // vendor is another entity of the group: intercompany, not POBO
}

interface Entity {
  id: string;
  code: string;
  name: string;
  /** Can pay for others: the treasury centre, or a holding company. */
  isPayer: boolean;
}

interface SourceAccount {
  id: string;
  vaNumber: string;
  name: string;
  availableBalance: number;
  currencyCode: string;
}

interface FormData {
  owningEntityId: string;
  vendorId: string;
  vendorName: string;
  partyBankAccountId: string;
  sourceVirtualAccountId: string;
  invoiceNumber: string;
  invoiceDate: string;
  paymentTerms: string;   // days, '' = none
  dueDate: string;
  currencyCode: string;
  amount: string;         // VAT-inclusive: what the vendor is owed
  description: string;
  applyVat: boolean;
  vatRate: string;
  poboEnabled: boolean;
  payingEntityId: string;
  poGrnRef: string;       // -> externalReference
  contractRef: string;    // -> vendorReference
  internalNotes: string;  // -> notes
  paymentChannel: string;
  scheduledDate: string;
  paymentPriority: string;
}

type TabId = 'tax' | 'pobo' | 'references';

// ============================================================================
// CONSTANTS & HELPERS
// ============================================================================

const TERM_DAYS = [0, 15, 30, 45, 60, 90];
const termLabel = (d: number) => (d === 0 ? 'Due on receipt' : `Net ${d} days`);

// PaymentChannel enum values from backend: DIRECT, BATCH, SCHEDULED, IMMEDIATE
const PAYMENT_CHANNELS = [
  { value: 'DIRECT', label: 'Direct Payment', description: 'Single payment', icon: Landmark },
  { value: 'SCHEDULED', label: 'Scheduled Payment', description: 'Pay on a chosen date', icon: Clock },
  { value: 'BATCH', label: 'Batch Payment', description: 'Include in a payment batch', icon: ArrowLeftRight },
  { value: 'IMMEDIATE', label: 'Immediate/Express', description: 'Priority same-day processing', icon: RefreshCw },
];

// Payable.PaymentPriority values the backend accepts
const PRIORITIES = [
  { value: 'LOW', label: 'Low' },
  { value: 'NORMAL', label: 'Normal' },
  { value: 'HIGH', label: 'High' },
  { value: 'URGENT', label: 'Urgent' },
];

const TABS: { id: TabId; label: string; icon: React.ElementType }[] = [
  { id: 'tax', label: 'VAT', icon: Calculator },
  { id: 'pobo', label: 'POBO', icon: ArrowLeftRight },
  { id: 'references', label: 'References & Notes', icon: Paperclip },
];

// Where a payment can be debited from: operational leaves only. Shadow (REAL) accounts mirror a bank
// account, settlement-marked accounts belong to the platform, and IHB mirror accounts (IC receivable /
// payable, IHB settlement) are intercompany positions -- none of them is a place to pay a vendor from.
const OPERATIONAL_CATEGORIES = ['TRANSACTION', 'COLLECTION', 'DISBURSEMENT'];
const isPayFromAccount = (va: any) =>
  va.status === 'ACTIVE'
  && OPERATIONAL_CATEGORIES.includes(va.accountCategory)
  && !va.settlementMark
  && va.accountType !== 'REAL'
  && (!va.mirrorAccountType || va.mirrorAccountType === 'NONE');

// Dates are local calendar days: toISOString() would shift them to UTC (a day early east of Greenwich).
const pad = (n: number) => String(n).padStart(2, '0');
const toLocalIso = (d: Date) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
const parseLocal = (iso: string) => { const [y, m, d] = iso.split('-').map(Number); return new Date(y, m - 1, d); };
const addDays = (iso: string, days: number) => { const d = parseLocal(iso); d.setDate(d.getDate() + days); return toLocalIso(d); };
const dueFrom = (invoiceDate: string, terms: string) => (invoiceDate && terms !== '' ? addDays(invoiceDate, Number(terms)) : '');
const formatDate = (iso: string) => (iso ? parseLocal(iso).toLocaleDateString('en-GB', { day: '2-digit', month: 'short', year: 'numeric' }) : '');
const round2 = (n: number) => Math.round(n * 100) / 100;

const toBankAccount = (ba: any): BankAccount => ({
  id: ba.id,
  label: ba.label || '',
  holderName: ba.holderName || '',
  bankName: ba.bankName || '',
  bankCode: ba.bankCode || '',
  iban: ba.iban || '',
  accountNumber: ba.accountNumber || '',
  currency: ba.currency || '',
  isPrimary: !!ba.isPrimary,
  isVerified: !!ba.isVerified,
  status: ba.status || '',
});

const toVendorHit = (p: any): VendorHit => ({
  id: p.id,
  name: p.displayName || p.legalName || '',
  partyCode: p.partyCode || '',
  partyType: p.partyType || '',
});

/** Bank accounts and POBO / intercompany set-up live on the party detail: fetched for the chosen vendor only. */
const loadVendor = async (partyId: string): Promise<Vendor> => {
  const detail: any = await partiesApi.getDetail(partyId);
  const party = detail?.party || {};
  return {
    ...toVendorHit({ ...party, id: party.id || partyId }),
    taxId: party.taxId || '',
    email: party.contactEmail || '',
    bankAccounts: (detail?.bankAccounts || []).map(toBankAccount),
    poboEligible: detail?.poboConfig?.poboEligible === true,
    poboDefaultPayerId: detail?.poboConfig?.defaultPayerEntityId || undefined,
    groupEntity: !!(detail?.intercompanyConfig?.isIntercompany && detail?.intercompanyConfig?.linkedLegalEntityId),
  };
};

const StepTitle: React.FC<{ n: number; title: string }> = ({ n, title }) => (
  <div className="flex items-center gap-3 mb-4">
    <div className="w-8 h-8 rounded-full bg-primary-100 dark:bg-primary-700 flex items-center justify-center"><span className="text-body-sm font-bold text-primary-600 dark:text-primary-200">{n}</span></div>
    <h2 className="section-title">{title}</h2>
  </div>
);

const optionCard = (selected: boolean) =>
  `w-full flex items-center gap-4 p-4 rounded-lg border-2 transition-all text-left ${selected
    ? 'border-primary-500 bg-primary-50 dark:bg-primary-800/40'
    : 'border-edge bg-surface-card hover:border-neutral-300 dark:hover:border-primary-700'}`;

// ============================================================================
// VENDOR SEARCH -- queries the server as the user types
// ============================================================================

const VendorSearch: React.FC<{ corporateId: string; onSelect: (v: VendorHit) => void }> = ({ corporateId, onSelect }) => {
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<VendorHit[]>([]);
  const [loading, setLoading] = useState(false);
  const [open, setOpen] = useState(false);

  useEffect(() => {
    if (!open || !corporateId) return;
    let live = true;
    const t = setTimeout(() => {
      setLoading(true);
      partiesApi.getAll({ corporateId, role: 'VENDOR', query: query.trim() || undefined, pageSize: 25 })
        .then((r: any) => { if (live) setResults((r?.parties || r?.data?.parties || []).map(toVendorHit)); })
        .catch(() => { if (live) setResults([]); })
        .finally(() => { if (live) setLoading(false); });
    }, 250);
    return () => { live = false; clearTimeout(t); };
  }, [query, open, corporateId]);

  return (
    <div className="relative">
      <Input
        value={query}
        onChange={(e) => { setQuery(e.target.value); setOpen(true); }}
        onFocus={() => setOpen(true)}
        placeholder={corporateId ? 'Search vendors by name or code...' : 'Choose the corporate first'}
        disabled={!corporateId}
        leftIcon={<Search className="w-5 h-5" />}
        rightIcon={loading ? <Loader2 className="w-5 h-5 animate-spin" /> : undefined}
        inputSize="lg"
      />
      {open && corporateId && (
        <>
          <div className="fixed inset-0 z-10" onClick={() => setOpen(false)} />
          <div className="absolute z-20 mt-2 w-full bg-surface-card border border-edge rounded-lg shadow-xl max-h-80 overflow-y-auto">
            {results.length === 0 ? (
              <div className="p-4 text-center text-neutral-500 dark:text-neutral-400"><Building2 className="w-8 h-8 mx-auto mb-2 text-neutral-300 dark:text-neutral-400" /><p>{loading ? 'Searching...' : 'No vendors found'}</p></div>
            ) : results.map((vendor) => (
              <button
                key={vendor.id}
                onClick={() => { onSelect(vendor); setOpen(false); setQuery(''); }}
                className="w-full flex items-center gap-4 p-4 hover:bg-neutral-50 dark:hover:bg-primary-800/50 text-left border-b border-edge-subtle last:border-b-0 transition-colors"
              >
                <StatusIconBadge tone="neutral" icon={Building2} className="flex-shrink-0" />
                <div className="flex-1 min-w-0">
                  <p className="font-medium text-neutral-900 dark:text-neutral-50 truncate">{vendor.name}</p>
                  <p className="body-sm">{[vendor.partyCode, vendor.partyType].filter(Boolean).join(' • ')}</p>
                </div>
              </button>
            ))}
          </div>
        </>
      )}
    </div>
  );
};

// ============================================================================
// MAIN COMPONENT
// ============================================================================

interface CreatePayablePageProps {
  payableId?: string;
}

const CreatePayablePage: React.FC<CreatePayablePageProps> = ({ payableId }) => {
  const isEditMode = !!payableId;
  const navigation = useNavigation();
  const { profile } = useMarket();

  const corporateIdFromParams: string = navigation.params?.corporateId || '';
  // No made-up corporate: it comes from the Payables list, from the payable being edited, or the user picks it.
  // Pre-filled from the Payables list; still changeable here when creating.
  const [chosenCorporateId, setChosenCorporateId] = useState(corporateIdFromParams);
  const [editCorporateId, setEditCorporateId] = useState('');
  const corporateId = isEditMode ? editCorporateId : chosenCorporateId;
  const corporatePickable = !isEditMode;

  const today = toLocalIso(new Date());
  const [formData, setFormData] = useState<FormData>({
    owningEntityId: navigation.params?.legalEntityId || '',
    vendorId: '', vendorName: '', partyBankAccountId: '', sourceVirtualAccountId: '',
    invoiceNumber: '', invoiceDate: today, paymentTerms: '30', dueDate: dueFrom(today, '30'),
    currencyCode: profile.defaultCurrency, amount: '', description: '',
    applyVat: false, vatRate: '',
    poboEnabled: false, payingEntityId: '',
    poGrnRef: '', contractRef: '', internalNotes: '',
    paymentChannel: '', scheduledDate: '', paymentPriority: 'NORMAL',
  });

  const [selectedVendor, setSelectedVendor] = useState<Vendor | null>(null);
  const [vendorLoading, setVendorLoading] = useState(false);
  const [activeTab, setActiveTab] = useState<TabId | null>(null);
  const [saving, setSaving] = useState(false);
  const [loadingPayable, setLoadingPayable] = useState(isEditMode);
  const [payableNumber, setPayableNumber] = useState('');
  const [currentStatus, setCurrentStatus] = useState('');
  const [corporateOptions, setCorporateOptions] = useState<{ id: string; name: string }[]>([]);
  const [corporateName, setCorporateName] = useState('');
  const [entities, setEntities] = useState<Entity[]>([]);
  const [accounts, setAccounts] = useState<SourceAccount[]>([]);

  const updateField = <K extends keyof FormData>(field: K, value: FormData[K]) => setFormData(prev => ({ ...prev, [field]: value }));

  // ---- Reference data -------------------------------------------------------

  useEffect(() => {
    if (!corporatePickable) return;
    corporatesApi.getAll(0, 100)
      .then((r) => setCorporateOptions((r.data || []).map((c) => ({ id: c.id, name: c.legalName || c.tradeName || c.corporateId }))))
      .catch(() => setCorporateOptions([]));
  }, [corporatePickable]);

  useEffect(() => {
    if (!corporateId || corporatePickable) return;
    let live = true;
    corporatesApi.getById(corporateId)
      .then((r) => { if (live) setCorporateName(r.data?.legalName || r.data?.tradeName || r.data?.corporateId || ''); })
      .catch(() => { if (live) setCorporateName(''); });
    return () => { live = false; };
  }, [corporateId, corporatePickable]);

  useEffect(() => {
    if (!corporateId) { setEntities([]); setAccounts([]); return; }
    let live = true;
    legalEntityApi.getByCorporate(corporateId)
      .then((r) => {
        if (!live) return;
        setEntities((Array.isArray(r.data) ? r.data : []).map((e: any) => ({
          id: e.id,
          code: e.entityCode || '',
          name: e.entityName || '',
          // Same rule as Create Invoice's collect-on-behalf picker.
          isPayer: !!(e.isTreasuryCenter || e.treasuryCenter || e.entityType === 'TREASURY_CENTER' || e.entityType === 'HOLDING'),
        })));
      })
      .catch(() => { if (live) setEntities([]); });
    virtualAccountsApi.getAll(0, 200, corporateId)
      .then((r) => {
        if (!live) return;
        setAccounts((Array.isArray(r.data) ? r.data : []).filter(isPayFromAccount).map((va: any) => ({
          id: va.id,
          vaNumber: va.vaNumber || '',
          name: va.vaName || va.vaNumber || '',
          availableBalance: Number(va.availableBalance ?? 0),
          currencyCode: va.currencyCode,
        })));
      })
      .catch(() => { if (live) setAccounts([]); });
    return () => { live = false; };
  }, [corporateId]);

  // ---- Edit mode: load the payable as stored ---------------------------------

  useEffect(() => {
    if (!payableId) return;
    let cancelled = false;
    (async () => {
      setLoadingPayable(true);
      try {
        const p: any = await payablesApiPhase2.getById(payableId);
        if (cancelled) return;
        const gross = Number(p.grossAmount || 0);
        const tax = Number(p.taxAmount || 0);
        setPayableNumber(p.payableNumber || '');
        setCurrentStatus(p.status || '');
        setEditCorporateId(p.corporateId || '');
        setFormData({
          owningEntityId: p.owningEntityId || '',
          vendorId: p.partyId || p.vendorId || '',
          vendorName: p.vendorName || '',
          partyBankAccountId: p.partyBankAccountId || '',
          sourceVirtualAccountId: p.virtualAccountId || '',
          invoiceNumber: p.invoiceNumber || '',
          invoiceDate: p.invoiceDate || '',
          paymentTerms: p.paymentTermsDays != null ? String(p.paymentTermsDays) : '',
          dueDate: p.dueDate || '',
          currencyCode: p.currencyCode || profile.defaultCurrency,
          amount: gross ? String(gross) : '',
          description: p.description || '',
          applyVat: tax > 0,
          // gross is VAT-inclusive: tax = gross * r / (100 + r)  =>  r = 100 * tax / (gross - tax)
          vatRate: tax > 0 && gross > tax ? String(round2((100 * tax) / (gross - tax))) : '',
          poboEnabled: !!p.isPobo,
          payingEntityId: p.paymentViaEntityId || '',
          poGrnRef: p.externalReference || '',
          contractRef: p.vendorReference || '',
          internalNotes: p.notes || '',
          paymentChannel: p.paymentChannel || '',
          scheduledDate: p.scheduledDate || '',
          paymentPriority: p.paymentPriority || 'NORMAL',
        });
        const partyId = p.partyId || p.vendorId;
        if (partyId) {
          try {
            const v = await loadVendor(partyId);
            if (!cancelled) setSelectedVendor(v);
          } catch (error) {
            console.error('Failed to load vendor for payable edit:', error);
          }
        }
      } catch (error) {
        console.error('Failed to load payable for editing:', error);
        toast.error('Failed to load payable for editing.');
      } finally {
        if (!cancelled) setLoadingPayable(false);
      }
    })();
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- loads once per payable; profile.defaultCurrency is only a fallback for a payable without a currency.
  }, [payableId]);

  // ---- Derived ----------------------------------------------------------------

  const amount = parseFloat(formData.amount) || 0;
  const vatRate = parseFloat(formData.vatRate) || 0;
  // The typed amount is VAT-inclusive: the VAT is the share already inside it, not added on top.
  const taxAmount = formData.applyVat && vatRate > 0 ? round2((amount * vatRate) / (100 + vatRate)) : 0;

  const owningEntity = entities.find(e => e.id === formData.owningEntityId) || null;
  const payers = entities.filter(e => e.isPayer && e.id !== formData.owningEntityId);
  const payer = payers.find(e => e.id === formData.payingEntityId) || null;

  const sourceAccounts = accounts.filter(a => a.currencyCode === formData.currencyCode);
  const selectedSource = sourceAccounts.find(a => a.id === formData.sourceVirtualAccountId) || null;
  const selectedBank = selectedVendor?.bankAccounts.find(b => b.id === formData.partyBankAccountId) || null;

  // POBO: only rules backed by real data -- the vendor's POBO set-up, intercompany, and the group's entities.
  const poboBlockers: string[] = [];
  if (!selectedVendor) poboBlockers.push('Select a vendor first');
  else {
    if (selectedVendor.groupEntity) poboBlockers.push('The vendor is a group entity: this is an intercompany payable, not POBO');
    if (!selectedVendor.poboEligible) poboBlockers.push('The vendor is not set up to receive POBO payments');
  }
  if (!owningEntity) poboBlockers.push('Choose the owning entity (the entity the payment is made on behalf of)');
  else if (payers.length === 0) poboBlockers.push('No treasury centre or holding entity other than the owning entity can pay');
  const poboActive = formData.poboEnabled && poboBlockers.length === 0;

  const missing: string[] = [];
  if (!corporateId) missing.push('Choose the corporate');
  if (!formData.vendorId) missing.push('Select a vendor');
  if (!formData.invoiceNumber.trim()) missing.push('Enter the invoice number');
  if (amount <= 0) missing.push('Enter an amount above zero');
  if (!formData.dueDate) missing.push('Set the due date');
  if (formData.applyVat && vatRate <= 0) missing.push('Enter the VAT rate, or switch VAT off');
  if (!selectedSource) missing.push(`Choose a ${formData.currencyCode} source account (Pay From)`);
  if (!selectedBank) missing.push('Choose the vendor bank account (Pay To)');
  if (!formData.paymentChannel) missing.push('Choose a payment channel');
  if (formData.paymentChannel === 'SCHEDULED' && !formData.scheduledDate) missing.push('Set the scheduled payment date');
  if (poboActive && !payer) missing.push('Choose the paying entity (POBO tab)');
  const canSubmit = missing.length === 0;

  const termOptions = TERM_DAYS.map(String);
  if (formData.paymentTerms !== '' && !termOptions.includes(formData.paymentTerms)) termOptions.push(formData.paymentTerms);

  // ---- Handlers -----------------------------------------------------------------

  const resetForCorporate = (id: string) => {
    setChosenCorporateId(id);
    setSelectedVendor(null);
    setFormData(prev => ({ ...prev, owningEntityId: '', vendorId: '', vendorName: '', partyBankAccountId: '', sourceVirtualAccountId: '', poboEnabled: false, payingEntityId: '' }));
  };

  const handleVendorSelect = async (hit: VendorHit) => {
    setFormData(prev => ({ ...prev, vendorId: hit.id, vendorName: hit.name, partyBankAccountId: '', poboEnabled: false, payingEntityId: '' }));
    setSelectedVendor({ ...hit, taxId: '', email: '', bankAccounts: [], poboEligible: false, groupEntity: false });
    setVendorLoading(true);
    try {
      const v = await loadVendor(hit.id);
      setSelectedVendor(v);
      const primary = v.bankAccounts.find(b => b.isPrimary);
      if (primary) updateField('partyBankAccountId', primary.id);
    } catch (error) {
      console.error('Failed to load vendor details:', error);
      toast.error('Could not load the vendor\'s bank accounts');
    } finally {
      setVendorLoading(false);
    }
  };

  const clearVendor = () => {
    setSelectedVendor(null);
    setFormData(prev => ({ ...prev, vendorId: '', vendorName: '', partyBankAccountId: '', poboEnabled: false, payingEntityId: '' }));
  };

  const setInvoiceDate = (v: string) => setFormData(prev => ({ ...prev, invoiceDate: v, dueDate: dueFrom(v, prev.paymentTerms) || prev.dueDate }));
  const setTerms = (v: string) => setFormData(prev => ({ ...prev, paymentTerms: v, dueDate: dueFrom(prev.invoiceDate, v) || prev.dueDate }));

  const setPobo = (on: boolean) => setFormData(prev => ({
    ...prev,
    poboEnabled: on,
    // The vendor's configured default payer, when it is one of this group's payers.
    payingEntityId: on && !prev.payingEntityId ? (payers.find(p => p.id === selectedVendor?.poboDefaultPayerId)?.id ?? '') : prev.payingEntityId,
  }));

  const handleSubmit = async (isDraft: boolean) => {
    if (!corporateId) { toast.error('Choose the corporate first'); return; }
    if (!isDraft && !canSubmit) return;
    setSaving(true);
    try {
      const payload = {
        status: (isDraft ? 'DRAFT' : 'PENDING_APPROVAL') as 'DRAFT' | 'PENDING_APPROVAL',
        owningEntityId: owningEntity?.id,
        owningEntityCode: owningEntity?.code,
        owningEntityName: owningEntity?.name,
        partyId: formData.vendorId || undefined,
        vendorName: formData.vendorName || undefined,
        partyBankAccountId: selectedBank?.id,
        virtualAccountId: selectedSource?.id,
        invoiceNumber: formData.invoiceNumber.trim(),
        invoiceDate: formData.invoiceDate || undefined,
        dueDate: formData.dueDate || undefined,
        paymentTermsDays: formData.paymentTerms === '' ? undefined : Number(formData.paymentTerms),
        currencyCode: formData.currencyCode,
        grossAmount: amount,
        taxAmount,
        paymentChannel: formData.paymentChannel || undefined,
        scheduledDate: formData.paymentChannel === 'SCHEDULED' && formData.scheduledDate ? formData.scheduledDate : undefined,
        paymentPriority: formData.paymentPriority,
        externalReference: formData.poGrnRef.trim(),
        vendorReference: formData.contractRef.trim(),
        description: formData.description.trim(),
        notes: formData.internalNotes.trim(),
        isPobo: poboActive,
        paymentViaEntityId: poboActive ? payer?.id : undefined,
        paymentViaEntityCode: poboActive ? payer?.code : undefined,
      };
      if (isEditMode && payableId) {
        await payablesApiPhase2.update(payableId, payload);
      } else {
        const request: CreatePayableRequest = { ...payload, corporateId };
        await payablesApiPhase2.create(request);
      }
      toast.success(isDraft ? 'Payable saved as draft' : 'Payable submitted for approval');
      navigation.navigate('payables');
    } catch (err: any) {
      console.error('Submit failed:', err);
      toast.error('Failed to save payable: ' + (err?.response?.data?.message || err?.response?.data?.error || err?.message || 'Unknown error'));
    } finally {
      setSaving(false);
    }
  };

  const goBack = () => navigation.navigate('payables');

  if (loadingPayable) {
    return (
      <div className="flex items-center justify-center py-24">
        <Loader2 className="w-8 h-8 text-primary-500 animate-spin" />
      </div>
    );
  }

  const daysUntilDue = formData.dueDate ? Math.round((parseLocal(formData.dueDate).getTime() - parseLocal(today).getTime()) / 86400000) : null;
  const actionButtons = (size?: 'lg') => (
    <>
      <Button variant="secondary" size={size} onClick={() => handleSubmit(true)} disabled={saving || !corporateId} leftIcon={<Save className="w-4 h-4" />}>Save Draft</Button>
      <Button variant="primary" size={size} onClick={() => handleSubmit(false)} disabled={saving || !canSubmit} loading={saving} leftIcon={<Send className="w-4 h-4" />}>Submit for Approval</Button>
    </>
  );

  return (
    <Page maxWidth="narrow" className="py-6">
      {/* Header */}
      <div className="bg-surface-card border border-edge rounded-lg shadow-sm">
        <div className="px-4 py-3 flex flex-wrap items-center justify-between gap-3">
          <div className="flex items-center gap-4">
            <button onClick={goBack} className="p-2 hover:bg-neutral-100 dark:hover:bg-primary-800 rounded-lg transition-colors" title="Back to payables"><ArrowLeft className="w-5 h-5 text-neutral-600 dark:text-neutral-300" /></button>
            <PageHeader
              title={isEditMode ? 'Edit Payable' : 'Create Payable'}
              description={isEditMode ? `Editing ${payableNumber || payableId}` : 'Record a vendor invoice for payment'}
            />
          </div>
          <div className="flex flex-wrap items-center gap-3">{actionButtons()}</div>
        </div>
      </div>

      {/* Corporate / owning entity */}
      <div className="bg-gradient-to-r from-primary-50 to-white border border-primary-200 rounded-lg p-4 dark:border-primary-700 dark:from-primary-800/40 dark:to-primary-900">
        <div className="grid gap-4 grid-cols-1 xl:grid-cols-2 items-end">
          <div className="flex items-center gap-4 min-w-0">
            <div className="p-2 bg-primary-100 dark:bg-primary-700 rounded-lg flex-shrink-0">
              <Building2 className="w-5 h-5 text-primary-600 dark:text-primary-200" />
            </div>
            <div className="flex-1 min-w-0">
              <p className="text-caption font-medium text-primary-600 dark:text-primary-200 uppercase tracking-wide">{isEditMode ? 'Payable of' : 'Creating payable for'}</p>
              {corporatePickable ? (
                <Select
                  aria-label="Corporate"
                  value={chosenCorporateId}
                  onChange={(e) => resetForCorporate(e.target.value)}
                  placeholder="Choose the corporate..."
                  options={corporateOptions.map(c => ({ value: c.id, label: c.name }))}
                  selectSize="sm"
                />
              ) : (
                <p className="font-semibold text-primary-900 dark:text-neutral-50 truncate">{corporateName || (corporateId ? 'Loading...' : 'No corporate')}</p>
              )}
            </div>
          </div>
          <Select
            label="Owning entity"
            value={formData.owningEntityId}
            onChange={(e) => setFormData(prev => ({ ...prev, owningEntityId: e.target.value, payingEntityId: prev.payingEntityId === e.target.value ? '' : prev.payingEntityId }))}
            options={[{ value: '', label: entities.length ? 'Not set' : 'No legal entities' }, ...entities.map(e => ({ value: e.id, label: `${e.code} - ${e.name}` }))]}
            disabled={!corporateId || entities.length === 0}
            selectSize="sm"
          />
        </div>
      </div>

      <div className="flex flex-col xl:flex-row gap-6">
        <div className="flex-1 min-w-0 space-y-6">
          {/* Step 1: Vendor */}
          <Card padding="lg">
            <StepTitle n={1} title="Select Vendor" />
            {selectedVendor ? (
              <div className="border-2 border-primary-200 bg-gradient-to-r from-primary-50 to-white rounded-lg p-5 dark:border-primary-700 dark:from-primary-800/40 dark:to-primary-900">
                <div className="flex items-start justify-between gap-3">
                  <div className="flex items-start gap-4 min-w-0">
                    <StatusIconBadge tone="primary" icon={Building2} size="lg" />
                    <div className="min-w-0">
                      <h3 className="font-semibold text-body-lg text-neutral-900 dark:text-neutral-50">{selectedVendor.name}</h3>
                      {selectedVendor.partyCode && <p className="body-sm mt-0.5">{selectedVendor.partyCode}</p>}
                      {selectedVendor.taxId && <p className="body-sm">TRN: {selectedVendor.taxId}</p>}
                      <div className="flex flex-wrap items-center gap-2 mt-2">
                        {selectedVendor.partyType && <Badge variant="neutral">{selectedVendor.partyType}</Badge>}
                        {selectedVendor.groupEntity && <Badge variant="info">Group entity</Badge>}
                        {selectedVendor.email && <span className="caption">{selectedVendor.email}</span>}
                      </div>
                    </div>
                  </div>
                  <button onClick={clearVendor} className="p-2 rounded-lg transition-colors hover:bg-primary-100 dark:hover:bg-primary-700" title="Change vendor">
                    <X className="w-5 h-5 text-neutral-500 dark:text-neutral-400" />
                  </button>
                </div>
              </div>
            ) : (
              <VendorSearch corporateId={corporateId} onSelect={handleVendorSelect} />
            )}
          </Card>

          {/* Step 2: Invoice */}
          <Card padding="lg">
            <StepTitle n={2} title="Invoice Details" />
            <div className="space-y-4">
              <Input label="Invoice / Bill Number *" value={formData.invoiceNumber} onChange={(e) => updateField('invoiceNumber', e.target.value)} placeholder="As printed on the vendor's invoice" leftIcon={<FileText className="w-5 h-5" />} />
              <div className="grid gap-4 grid-cols-2 xl:grid-cols-4">
                <Input label="Amount (incl. VAT) *" type="number" min="0" step="0.01" value={formData.amount} onChange={(e) => updateField('amount', e.target.value)} placeholder="0.00" />
                <div className="w-full">
                  <label className="block body-strong mb-2">Currency</label>
                  <CurrencyPicker
                    value={formData.currencyCode}
                    onChange={(v) => updateField('currencyCode', v)}
                    extra={[...(selectedVendor?.bankAccounts.map(b => b.currency) || []), ...accounts.map(a => a.currencyCode)].filter(Boolean)}
                    className="h-11"
                  />
                </div>
                <Input label="Invoice Date" type="date" value={formData.invoiceDate} onChange={(e) => setInvoiceDate(e.target.value)} />
                <Select
                  label="Payment Terms"
                  value={formData.paymentTerms}
                  onChange={(e) => setTerms(e.target.value)}
                  options={[{ value: '', label: 'No terms' }, ...termOptions.map(d => ({ value: d, label: termLabel(Number(d)) }))]}
                />
              </div>
              <div className="grid gap-4 grid-cols-1 xl:grid-cols-2">
                <Input label="Due Date *" type="date" value={formData.dueDate} onChange={(e) => updateField('dueDate', e.target.value)} hint="Set from the invoice date and terms; you can change it." />
                <Input label="Description / Memo" value={formData.description} onChange={(e) => updateField('description', e.target.value)} placeholder="Brief description of this payable..." />
              </div>
            </div>
          </Card>

          {/* Step 3: Payment */}
          <Card padding="lg">
            <StepTitle n={3} title="Payment Method" />

            <div className="mb-6">
              <label className="field-label block mb-2">Source Account (Pay From) <span className="text-error-500 dark:text-error-300">*</span></label>
              {!corporateId ? (
                <p className="body-sm">Choose the corporate first.</p>
              ) : sourceAccounts.length === 0 ? (
                <div className="bg-warning-50 border border-warning-200 rounded-lg p-4 flex items-center gap-3 dark:bg-warning-500/10 dark:border-warning-500/30">
                  <AlertTriangle className="w-5 h-5 text-warning-600 dark:text-warning-300 flex-shrink-0" />
                  <p className="text-body-sm text-warning-800 dark:text-warning-300">No active {formData.currencyCode} operational account to pay from</p>
                </div>
              ) : (
                <div className="space-y-2">
                  {sourceAccounts.map((va) => {
                    const isSelected = formData.sourceVirtualAccountId === va.id;
                    const covers = va.availableBalance >= amount;
                    return (
                      <button key={va.id} onClick={() => updateField('sourceVirtualAccountId', va.id)} className={optionCard(isSelected)}>
                        <StatusIconBadge tone={isSelected ? 'primary' : 'neutral'} icon={CreditCard} />
                        <div className="flex-1 min-w-0">
                          <div className="flex flex-wrap items-center gap-2">
                            <p className="font-medium text-neutral-900 dark:text-neutral-50">{va.name}</p>
                            {!covers && <Badge variant="warning" size="sm">Balance below amount</Badge>}
                          </div>
                          <p className="text-body-sm text-neutral-500 dark:text-neutral-400 font-mono truncate">{va.vaNumber}</p>
                        </div>
                        <div className="text-right">
                          <p className={`font-semibold ${covers ? 'text-success-600 dark:text-success-300' : 'text-warning-600 dark:text-warning-300'}`}>{formatCurrency(va.availableBalance, va.currencyCode)}</p>
                          <p className="caption">Available</p>
                        </div>
                        {isSelected && <Check className="w-5 h-5 text-primary-600 dark:text-primary-200" />}
                      </button>
                    );
                  })}
                </div>
              )}
            </div>

            <div className="pt-4 border-t border-edge mb-6">
              <label className="field-label block mb-2">Vendor Bank Account (Pay To) <span className="text-error-500 dark:text-error-300">*</span></label>
              {!selectedVendor ? (
                <p className="body-sm">Select a vendor first.</p>
              ) : vendorLoading ? (
                <p className="body-sm flex items-center gap-2"><Loader2 className="w-4 h-4 animate-spin" />Loading bank accounts...</p>
              ) : selectedVendor.bankAccounts.length === 0 ? (
                <div className="bg-warning-50 border border-warning-200 rounded-lg p-4 flex items-center gap-3 dark:bg-warning-500/10 dark:border-warning-500/30">
                  <AlertTriangle className="w-5 h-5 text-warning-600 dark:text-warning-300 flex-shrink-0" />
                  <p className="text-body-sm text-warning-800 dark:text-warning-300">This vendor has no bank account on file</p>
                </div>
              ) : (
                <div className="space-y-2">
                  {selectedVendor.bankAccounts.map((account) => {
                    const isSelected = formData.partyBankAccountId === account.id;
                    return (
                      <button key={account.id} onClick={() => updateField('partyBankAccountId', account.id)} className={optionCard(isSelected)}>
                        <StatusIconBadge tone={isSelected ? 'primary' : 'neutral'} icon={Landmark} />
                        <div className="flex-1 min-w-0">
                          <div className="flex flex-wrap items-center gap-2">
                            <p className="font-medium text-neutral-900 dark:text-neutral-50">{account.bankName || account.label || 'Bank account'}</p>
                            {account.isPrimary && <Badge variant="primary" size="sm">Primary</Badge>}
                            {account.isVerified && <Badge variant="success" size="sm">Verified</Badge>}
                            {account.status && account.status !== 'ACTIVE' && <Badge variant="warning" size="sm">{account.status}</Badge>}
                            {account.currency && <Badge variant="neutral" size="sm">{account.currency}</Badge>}
                          </div>
                          {account.holderName && <p className="body-sm">{account.holderName}</p>}
                          <p className="text-body-sm text-neutral-500 dark:text-neutral-400 font-mono truncate">{account.iban || account.accountNumber}</p>
                          {account.bankCode && <p className="caption">Bank code: {account.bankCode}</p>}
                        </div>
                        {isSelected && <Check className="w-5 h-5 text-primary-600 dark:text-primary-200" />}
                      </button>
                    );
                  })}
                </div>
              )}
              {selectedBank && selectedBank.currency && selectedBank.currency !== formData.currencyCode && (
                <p className="mt-2 text-body-sm text-warning-700 dark:text-warning-300 flex items-center gap-2">
                  <AlertTriangle className="w-4 h-4 flex-shrink-0" />This account is in {selectedBank.currency}; the payable is in {formData.currencyCode}.
                </p>
              )}
            </div>

            <div className="pt-4 border-t border-edge space-y-4">
              <div>
                <label className="field-label block mb-2">Payment Channel <span className="text-error-500 dark:text-error-300">*</span></label>
                <div className="grid gap-3 grid-cols-1 xl:grid-cols-2">
                  {PAYMENT_CHANNELS.map((channel) => {
                    const Icon = channel.icon;
                    const isSelected = formData.paymentChannel === channel.value;
                    return (
                      <button key={channel.value} onClick={() => updateField('paymentChannel', channel.value)} className={optionCard(isSelected)}>
                        <Icon className={`w-5 h-5 flex-shrink-0 ${isSelected ? 'text-primary-600 dark:text-primary-200' : 'text-neutral-400'}`} />
                        <div>
                          <p className="font-medium text-body-sm text-neutral-700 dark:text-neutral-200">{channel.label}</p>
                          <p className="caption">{channel.description}</p>
                        </div>
                      </button>
                    );
                  })}
                </div>
              </div>
              <div className="grid gap-4 grid-cols-1 xl:grid-cols-2">
                {formData.paymentChannel === 'SCHEDULED' && (
                  <Input label="Scheduled Payment Date *" type="date" min={today} value={formData.scheduledDate} onChange={(e) => updateField('scheduledDate', e.target.value)} />
                )}
                <Select label="Payment Priority" value={formData.paymentPriority} onChange={(e) => updateField('paymentPriority', e.target.value)} options={PRIORITIES} />
              </div>
            </div>
          </Card>

          {/* Optional configuration */}
          <Card padding="none">
            <div className="border-b border-edge px-4 pt-4">
              <div className="flex items-center gap-2 mb-4">
                <Settings className="w-5 h-5 text-neutral-500 dark:text-neutral-400" />
                <h2 className="font-semibold text-neutral-900 dark:text-neutral-50">Optional Configuration</h2>
              </div>
              <div className="flex gap-1 -mb-px overflow-x-auto">
                {TABS.map((tab) => {
                  const Icon = tab.icon;
                  const isActive = activeTab === tab.id;
                  const hasContent = tab.id === 'tax' ? formData.applyVat : tab.id === 'pobo' ? poboActive : !!(formData.poGrnRef || formData.contractRef || formData.internalNotes);
                  return (
                    <button key={tab.id} onClick={() => setActiveTab(isActive ? null : tab.id)}
                      className={`flex items-center gap-2 px-4 py-3 text-body-sm font-medium border-b-2 transition-colors whitespace-nowrap ${isActive
                        ? 'border-primary-500 text-primary-600 bg-primary-50 dark:text-primary-200 dark:bg-primary-800/40'
                        : 'border-transparent text-neutral-500 hover:text-neutral-700 hover:bg-neutral-50 dark:text-neutral-400 dark:hover:text-neutral-200 dark:hover:bg-primary-800/50'}`}>
                      <Icon className="w-4 h-4" />
                      {tab.label}
                      {hasContent && !isActive && <span className="w-2 h-2 rounded-full bg-primary-500 dark:bg-primary-300" />}
                    </button>
                  );
                })}
              </div>
            </div>
            {!activeTab && <div className="p-6 text-center text-neutral-500 dark:text-neutral-400 text-body-sm">VAT, pay on behalf of (POBO), references and internal notes</div>}

            {activeTab === 'tax' && (
              <div className="p-6 space-y-4">
                <Toggle layout="split" label="Invoice includes VAT" description="The amount above already includes it; the VAT share is recorded with the payable" checked={formData.applyVat} onChange={(v) => updateField('applyVat', v)} />
                {formData.applyVat && (
                  <div className="grid gap-4 grid-cols-1 xl:grid-cols-2">
                    <Input label="VAT rate (%) *" type="number" min="0" step="0.01" value={formData.vatRate} onChange={(e) => updateField('vatRate', e.target.value)} />
                    <div>
                      <p className="body-strong mb-2">VAT included</p>
                      <p className="h-11 flex items-center font-medium text-neutral-900 dark:text-neutral-50">{formatCurrency(taxAmount, formData.currencyCode)}</p>
                    </div>
                  </div>
                )}
              </div>
            )}

            {activeTab === 'pobo' && (
              <div className="p-6 space-y-4">
                {poboBlockers.length > 0 ? (
                  <div className="bg-warning-50 border border-warning-200 rounded-lg p-4 dark:bg-warning-500/10 dark:border-warning-500/30">
                    <div className="flex items-start gap-3">
                      <Info className="w-5 h-5 text-warning-600 mt-0.5 flex-shrink-0 dark:text-warning-300" />
                      <div>
                        <p className="text-body-sm font-medium text-warning-800 dark:text-warning-300">Pay on behalf of (POBO) is not available:</p>
                        <ul className="mt-2 space-y-1">
                          {poboBlockers.map((reason) => <li key={reason} className="text-body-sm text-warning-700 dark:text-warning-300">• {reason}</li>)}
                        </ul>
                      </div>
                    </div>
                  </div>
                ) : (
                  <>
                    <Toggle layout="split" label="Pay on behalf of (POBO)" description={`A treasury entity pays this vendor on behalf of ${owningEntity?.name}`} checked={formData.poboEnabled} onChange={setPobo} />
                    {formData.poboEnabled && (
                      <div className="grid gap-4 grid-cols-1 xl:grid-cols-2">
                        <Select
                          label="Paying entity (treasury) *"
                          value={formData.payingEntityId}
                          onChange={(e) => updateField('payingEntityId', e.target.value)}
                          placeholder="Choose the paying entity..."
                          options={payers.map(e => ({ value: e.id, label: `${e.code} - ${e.name}` }))}
                        />
                        <div>
                          <p className="body-strong mb-2">On behalf of</p>
                          <p className="h-11 flex items-center font-medium text-neutral-900 dark:text-neutral-50">{owningEntity?.code} - {owningEntity?.name}</p>
                        </div>
                      </div>
                    )}
                    <p className="caption">After approval, the treasury request is raised from the Payables list (Request POBO).</p>
                  </>
                )}
              </div>
            )}

            {activeTab === 'references' && (
              <div className="p-6 space-y-4">
                <div className="grid gap-4 grid-cols-1 xl:grid-cols-2">
                  <Input label="PO / GRN reference" value={formData.poGrnRef} onChange={(e) => updateField('poGrnRef', e.target.value)} maxLength={100} />
                  <Input label="Contract reference" value={formData.contractRef} onChange={(e) => updateField('contractRef', e.target.value)} maxLength={100} />
                </div>
                <TextArea label="Internal notes" value={formData.internalNotes} onChange={(e) => updateField('internalNotes', e.target.value)} placeholder="Not sent to the vendor" rows={4} />
              </div>
            )}
          </Card>
        </div>

        {/* Summary */}
        <div className="w-full xl:w-80 flex-shrink-0">
          <div className="bg-surface-card rounded-lg border border-edge shadow-sm xl:sticky xl:top-24">
            <div className="p-4 border-b border-edge-subtle">
              <h3 className="section-title flex items-center gap-2"><Receipt className="w-5 h-5 text-primary-600 dark:text-primary-200" />Payment Summary</h3>
            </div>
            <div className="p-4 space-y-4">
              {formData.vendorName && (
                <div className="pb-4 border-b border-edge-subtle">
                  <p className="label">Vendor</p>
                  <p className="font-semibold text-neutral-900 dark:text-neutral-50 mt-1">{formData.vendorName}</p>
                  {formData.invoiceNumber && <p className="text-body-sm text-neutral-500 dark:text-neutral-400 font-mono mt-1">{formData.invoiceNumber}</p>}
                </div>
              )}
              <div className="space-y-2">
                <div className="flex justify-between pt-1"><span className="font-semibold text-neutral-900 dark:text-neutral-50">Total Payable</span><span className="font-bold text-heading-sm text-primary-600 dark:text-primary-200">{formatCurrency(amount, formData.currencyCode)}</span></div>
                {taxAmount > 0 && <div className="flex justify-between text-body-sm"><span className="text-neutral-500 dark:text-neutral-400">incl. VAT ({vatRate}%)</span><span className="text-neutral-900 dark:text-neutral-50">{formatCurrency(taxAmount, formData.currencyCode)}</span></div>}
              </div>
              {formData.dueDate && (
                <div className="bg-surface-page rounded-lg p-3">
                  <div className="flex justify-between items-center"><span className="body-sm">Due Date</span><span className="font-medium text-neutral-900 dark:text-neutral-50">{formatDate(formData.dueDate)}</span></div>
                  {daysUntilDue !== null && <p className={`inline-flex items-center gap-1 text-caption mt-1 ${daysUntilDue < 0 ? 'text-error-600 dark:text-error-300' : daysUntilDue < 7 ? 'text-warning-600 dark:text-warning-300' : 'text-success-600 dark:text-success-300'}`}>{daysUntilDue < 0 ? <><AlertTriangle className="w-3 h-3" /> {Math.abs(daysUntilDue)} days overdue</> : daysUntilDue === 0 ? <><Clock className="w-3 h-3" /> Due today</> : <><Check className="w-3 h-3" /> {daysUntilDue} days remaining</>}</p>}
                </div>
              )}
              {poboActive && (
                <div className="bg-accent-50 border border-accent-200 rounded-lg p-3 dark:bg-accent-500/10 dark:border-accent-500/30">
                  <div className="flex items-center gap-2 text-accent-700 dark:text-accent-300"><ArrowLeftRight className="w-4 h-4" /><span className="font-medium text-body-sm">Pay on behalf of</span></div>
                  <p className="text-caption text-accent-600 mt-1 dark:text-accent-300">{payer?.name || 'Paying entity not chosen'} → {owningEntity?.name}</p>
                </div>
              )}
              <div className="pt-3 border-t border-edge-subtle space-y-1 text-body-sm">
                {isEditMode && currentStatus && <p className="body-sm">Now: <span className="font-medium text-neutral-900 dark:text-neutral-50">{currentStatus.replace(/_/g, ' ')}</span></p>}
                <p className="body-sm"><span className="font-medium text-neutral-900 dark:text-neutral-50">Save Draft</span> stores it as Draft (may be incomplete).</p>
                <p className="body-sm"><span className="font-medium text-neutral-900 dark:text-neutral-50">Submit for Approval</span> stores it as Pending Approval; it is approved from the Payables list.</p>
              </div>
              {canSubmit ? (
                <p className="flex items-center gap-2 text-body-sm text-success-700 dark:text-success-300"><CheckCircle className="w-4 h-4" />Ready to submit</p>
              ) : (
                <div>
                  <p className="text-caption font-medium text-neutral-700 dark:text-neutral-200 mb-1">To submit for approval:</p>
                  <ul className="space-y-0.5">{missing.map(m => <li key={m} className="text-caption text-error-600 dark:text-error-300">• {m}</li>)}</ul>
                </div>
              )}
            </div>
          </div>
        </div>
      </div>

      {/* Bottom action bar */}
      <div className="mt-2 border-t border-edge py-4 flex flex-wrap justify-between items-center gap-3">
        <Button variant="ghost" onClick={goBack}>Cancel</Button>
        <div className="flex flex-wrap items-center gap-3">{actionButtons('lg')}</div>
      </div>
    </Page>
  );
};

export default CreatePayablePage;
