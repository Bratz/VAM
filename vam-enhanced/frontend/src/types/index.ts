// API Response Types
export interface ApiResponse<T> {
  success: boolean;
  message?: string;
  data?: T;
  error?: ErrorInfo;
  timestamp: string;
  requestId?: string;
}

export interface PagedResponse<T> {
  success: boolean;
  data: T[];
  pagination: PageInfo;
  timestamp: string;
}

export interface PageInfo {
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  hasNext: boolean;
  hasPrevious: boolean;
}

export interface ErrorInfo {
  code: string;
  message: string;
  field?: string;
  fieldErrors?: FieldError[];
  traceId?: string;
}

export interface FieldError {
  field: string;
  message: string;
  rejectedValue?: unknown;
}

// Virtual Account Types
export interface VirtualAccount {
  id: string;
  virtualAccountNumber: number;
  virtualIban: string;
  virtualBban?: string;
  accountReference1?: string;
  accountReference2?: string;
  corporateSchemeCode: string;
  schemeName: string;
  customerReference: string;
  customerName: string;
  accountName: string;
  localAccountName?: string;
  accountSequence?: string;
  accountUsage: number;
  accountUsageDescription: string;
  currencyCode: string;
  parentAccountId?: string;
  parentAccountIban?: string;
  hierarchyName?: string;
  hierarchyLevel: number;
  childAccountCount: number;
  currentBalance: number;
  availableBalance: number;
  fundHold: number;
  overdraftLimit: number;
  balanceLastUpdated?: string;
  status: number;
  statusDescription: string;
  openedOn: string;
  closedOn?: string;
  validTillDate?: string;
  accountManager?: string;
  faClassification?: string;
  remarks?: string;
  provenance: number;
  initiatingChannel?: string;
  whitelistCount: number;
  transactionCount?: number;
  createdAt: string;
  updatedAt?: string;
  createdBy?: string;
}

export interface VirtualAccountSummary {
  id: string;
  virtualAccountNumber: number;
  virtualIban: string;
  accountName: string;
  corporateSchemeCode: string;
  currencyCode: string;
  currentBalance: number;
  availableBalance: number;
  status: number;
  statusDescription: string;
  openedOn: string;
}

export interface VirtualAccountBalance {
  virtualIban: string;
  accountName: string;
  currencyCode: string;
  currentBalance: number;
  availableBalance: number;
  fundHold: number;
  overdraftLimit: number;
  netChargeAmount?: number;
  netSettlementAmount?: number;
  settlementAccount?: string;
  balanceLastUpdated?: string;
}

export interface CreateVirtualAccountRequest {
  corporateSchemeCode: string;
  customerReference: string;
  accountName: string;
  localAccountName?: string;
  accountUsage?: number;
  currencyCode?: string;
  parentAccountId?: string;
  parentAccountCategory?: number;
  hierarchyName?: string;
  accountManager?: string;
  faClassification?: string;
  remarks?: string;
  validTillDate?: string;
  levelDefinitions?: LevelDefinition[];
}

export interface LevelDefinition {
  levelId: number;
  levelDescription: string;
  levelValue: string;
  levelFormat: number;
  minimumLength: number;
  maximumLength: number;
}

export interface CloseVirtualAccountRequest {
  virtualAccountId: string;
  closureReason?: number;
  settlementAccountReference?: string;
  settlementMode?: number;
}

export interface HierarchyNode {
  id: string;
  virtualIban: string;
  accountName: string;
  hierarchyLevel: number;
  currentBalance: number;
  status: number;
  children: HierarchyNode[];
}

// Transaction Types
export interface Transaction {
  id: string;
  transactionReference: string;
  paymentReference?: string;
  e2eReference?: string;
  uetr?: string;
  debtorAccount: string;
  debtorName?: string;
  beneficiaryAccount: string;
  beneficiaryName: string;
  beneficiaryBankName?: string;
  beneficiaryBankBic?: string;
  instructedAmount: number;
  instructedCurrency: string;
  transferAmount?: number;
  transferCurrency?: string;
  exchangeRate?: number;
  totalCharges?: number;
  status: string;
  statusDescription?: string;
  statusReason?: string;
  transactionDate: string;
  valueDate?: string;
  requestedExecutionDate?: string;
  clearingType?: number;
  clearingTypeDescription?: string;
  purposeCode?: string;
  remittanceInfo?: string;
  createdAt: string;
  createdBy?: string;
  updatedAt?: string;
}

export interface TransactionSummary {
  id: string;
  transactionReference: string;
  debtorAccount: string;
  beneficiaryName: string;
  beneficiaryAccount: string;
  instructedAmount: number;
  instructedCurrency: string;
  status: string;
  transactionDate: string;
  valueDate?: string;
}

export interface InitiateTransactionRequest {
  debtorAccount: string;
  debtorCifId?: string;
  debtorName?: string;
  debtorAddress?: string;
  beneficiaryAccount: string;
  beneficiaryName: string;
  beneficiaryAddress?: string;
  beneficiaryBankName?: string;
  beneficiaryBankBic?: string;
  beneficiaryBankCountry?: string;
  instructedAmount: number;
  instructedCurrency: string;
  transferAmount?: number;
  transferCurrency?: string;
  exchangeRate?: number;
  forexContractReference?: string;
  clearingType?: number;
  purposeCode?: string;
  categoryPurpose?: string;
  requestedExecutionDate?: string;
  valueDate?: string;
  chargeOption?: number;
  chargeAccount?: string;
  waiveCharges?: boolean;
  remittanceInfo?: string;
  remittanceInfoLine1?: string;
  remittanceInfoLine2?: string;
  remittanceInfoLine3?: string;
  remittanceInfoLine4?: string;
  e2eReference?: string;
  transactionReference?: string;
  relatedTransactionReference?: string;
  bookingNarrative?: string;
  debitRemarks?: string;
  regulatoryReportingLine1?: string;
  regulatoryReportingLine2?: string;
  intermediaryName?: string;
  intermediaryIdentifier?: string;
  intermediaryAccount?: string;
  intermediaryCountry?: string;
  senderToReceiverInfo?: string;
  validateOnly?: boolean;
}

// Beneficiary Types
export interface Beneficiary {
  id: string;
  beneficiaryReference: string;
  customerId: string;
  beneficiaryName: string;
  beneficiaryNickname?: string;
  beneficiaryAccount: string;
  beneficiaryAccountType?: number;
  beneficiaryBankName?: string;
  beneficiaryBankBic?: string;
  beneficiaryBankIdentifierType?: number;
  localClearingCode?: string;
  beneficiaryAddressLine1?: string;
  beneficiaryAddressLine2?: string;
  beneficiaryAddressLine3?: string;
  beneficiaryCountry?: string;
  bankAddressLine1?: string;
  bankAddressLine2?: string;
  bankAddressLine3?: string;
  bankCountry?: string;
  schemeType: number;
  currencyCode: string;
  defaultPurposeCode?: string;
  status: string;
  startDate: string;
  endDate?: string;
  bancsTransactionReference?: number;
  createdAt: string;
  updatedAt?: string;
}

// Corporate Types
export interface CorporateCustomer {
  id: string;
  customerReference: string;
  cifId?: string;
  customerName: string;
  tradeLicenseNumber?: string;
  legalEntityType?: string;
  registrationCountry: string;
  industrySector?: string;
  contactEmail?: string;
  contactPhone?: string;
  addressLine1?: string;
  addressLine2?: string;
  city?: string;
  postalCode?: string;
  kycStatus: string;
  kycVerifiedAt?: string;
  status: string;
  createdAt: string;
  updatedAt?: string;
}

export interface CorporateScheme {
  id: string;
  corporateSchemeCode: string;
  customerId: string;
  schemeName: string;
  schemeDescription?: string;
  operationalUnit: string;
  accountUsage: number;
  currencyCode: string;
  settlementAccountIban?: string;
  settlementAccountBban?: string;
  maxVirtualAccounts: number;
  dailyTransactionLimit?: number;
  monthlyTransactionLimit?: number;
  autoSweepEnabled: boolean;
  sweepThreshold?: number;
  sweepTargetBalance?: number;
  hierarchyEnabled: boolean;
  whitelistRequired: boolean;
  status: string;
  effectiveFrom: string;
  effectiveTo?: string;
  createdAt: string;
  updatedAt?: string;
}

// Whitelist Types
export interface WhitelistedAccount {
  id: string;
  virtualAccountId: string;
  whitelistedAccountReference: string;
  whitelistedAccountCurrency: string;
  withinOutFlag: number;
  direction: number;
  bankIdentifierCode?: string;
  localClearingCode?: string;
  validFrom: string;
  validTill: string;
  status: number;
  deletionFlag: number;
  createdAt: string;
  updatedAt?: string;
}

// Dashboard Types
export interface DashboardStats {
  totalAccounts: number;
  activeAccounts: number;
  totalBalance: number;
  availableBalance: number;
  todayTransactions: number;
  todayVolume: number;
  pendingTransactions: number;
}

export interface BalanceTrend {
  date: string;
  balance: number;
  available: number;
}

// User Types
export interface User {
  id: string;
  username: string;
  email: string;
  firstName?: string;
  lastName?: string;
  customerId?: string;
  customerName?: string;
  roles: string[];
  permissions: string[];
  status: string;
  lastLoginAt?: string;
}

// Enums
export enum AccountStatus {
  ACTIVE = 1,
  ON_HOLD = 2,
  ACTIVE_PENDING_AUTH = 3,
  RELEASED = 4,
  PENDING_CANCEL_AUTH = 5,
  PENDING_CANCEL = 6,
  CANCELLED = 7,
  REJECTED = 8,
  M_ON_HOLD = 9,
}

export enum TransactionStatusEnum {
  PENDING = 'PENDING',
  VALIDATED = 'VALIDATED',
  INITIATED = 'INITIATED',
  PROCESSING = 'PROCESSING',
  COMPLETED = 'COMPLETED',
  FAILED = 'FAILED',
  CANCELLED = 'CANCELLED',
  REJECTED = 'REJECTED',
  RETURNED = 'RETURNED',
}

export enum ClearingType {
  RTGS = 1,
  ACH = 2,
  INTERNAL = 3,
}

export enum ChargeOption {
  OUR = 1,
  BEN = 2,
  SHA = 3,
}

export enum WhitelistDirection {
  INCOMING = 1,
  OUTGOING = 2,
  BOTH = 3,
}
