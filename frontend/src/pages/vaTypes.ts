// ============================================================================
// VIRTUAL ACCOUNT TYPES
// Comprehensive type definitions for the VA feature
// ============================================================================

// ============================================================================
// ENUMS
// ============================================================================

export type ProgramType = 
  | 'COLLECTION'
  | 'WALLET'
  | 'IHB'
  | 'CORPORATE_CARD'
  | 'ESCROW'
  | 'LOYALTY'
  | 'PAYABLES'
  | 'MOBILE_MONEY'
  | 'GIFT_CARD'
  | 'VIBAN';

export type VaStatus = 
  | 'ACTIVE'
  | 'INACTIVE'
  | 'SUSPENDED'
  | 'CLOSED'
  | 'BLOCKED'
  | 'PENDING_ACTIVATION'
  | 'EXPIRED';

export type WalletType = 
  | 'CONSUMER'
  | 'EMPLOYEE'
  | 'MERCHANT'
  | 'AGENT'
  | 'CORPORATE'
  | 'GIFT';

export type ValueType = 
  | 'FIAT'
  | 'POINTS'
  | 'MILES'
  | 'TOKENS'
  | 'CRYPTO';

export type LoyaltyTier = 
  | 'BASIC'
  | 'BLUE'
  | 'SILVER'
  | 'GOLD'
  | 'PLATINUM';

export type CardProgramType = 
  | 'TRAVEL'
  | 'PROCUREMENT'
  | 'FLEET'
  | 'VIRTUAL'
  | 'EXPENSE'
  | 'PETTY_CASH';

export type CollectionChannel = 
  | 'BANK_TRANSFER'
  | 'CARD'
  | 'MOBILE'
  | 'CASH'
  | 'CHEQUE'
  | 'DIRECT_DEBIT'
  | 'WALLET';

export type ExpiryAction = 
  | 'ZERO_BALANCE'
  | 'FORFEIT'
  | 'TRANSFER'
  | 'EXTEND'
  | 'NOTIFY';

// ============================================================================
// PROGRAM
// ============================================================================

export interface Program {
  id: string;
  programCode: string;
  programName: string;
  programType: ProgramType;
  currencyCode: string;
  status: string;
  corporateId: string;
  physicalAccountId: string;
  
  // Limits
  defaultPerTransactionLimit?: number;
  defaultDailyLimit?: number;
  defaultWeeklyLimit?: number;
  defaultMonthlyLimit?: number;
  defaultYearlyLimit?: number;
  defaultMaxBalance?: number;
  defaultDailyTopupLimit?: number;
  defaultMonthlyTopupLimit?: number;
  
  // Wallet
  defaultWalletType?: string;
  
  // KYC
  kycRequired?: boolean;
  minKycLevel?: number;
  
  // Hierarchy
  hierarchyEnabled?: boolean;
  rootHierarchyNodeId?: string;
  
  // Expiry
  walletExpiryDays?: number;
  
  // Counts
  currentVaCount?: number;
  maxVirtualAccounts?: number;
  
  // Metadata
  vaPrefix?: string;
  createdAt?: string;
  updatedAt?: string;
}

// ============================================================================
// PROGRAM TYPE CONFIG
// ============================================================================

export interface ProgramTypeConfig {
  programType: string;
  displayName: string;
  description: string;
  
  // Feature flags
  supportsWallet: boolean;
  supportsLimits: boolean;
  supportsKyc: boolean;
  supportsHierarchy: boolean;
  supportsMcc: boolean;
  supportsLoyalty: boolean;
  supportsCardProgram: boolean;
  supportsExpiry: boolean;
  
  // Field classification
  requiredFields: string[];
  optionalFields: string[];
  hiddenFields: string[];
  
  // Defaults
  defaultValues: Record<string, any>;
  
  // Validation
  validationRules?: Record<string, any>;
  
  // UI
  tabs: string[];
  uiConfig?: Record<string, any>;
}

// ============================================================================
// VIRTUAL ACCOUNT - CREATE REQUEST
// ============================================================================

export interface CreateVaRequest {
  // Core (required)
  vaName: string;
  programId: string;
  corporateId: string;
  physicalAccountId: string;
  currencyCode: string;
  
  // Core (optional)
  viban?: string;
  vaPrefix?: string;
  externalReference?: string;
  metadata?: string;
  inheritProgramDefaults?: boolean;
  
  // Hierarchy
  hierarchyNodeId?: string;
  collectionChannel?: CollectionChannel | string;
  
  // Wallet
  walletType?: WalletType | string;
  kycLevel?: number;
  expiresAt?: string;
  holderPartyId?: string;
  
  // Spending Limits
  perTransactionLimit?: number;
  dailyLimit?: number;
  weeklyLimit?: number;
  monthlyLimit?: number;
  annualLimit?: number;
  maxBalance?: number;
  
  // Topup Limits
  dailyTopupLimit?: number;
  monthlyTopupLimit?: number;
  
  // Value Type / Loyalty
  valueType?: ValueType | string;
  pointsToCurrencyRate?: number;
  loyaltyTier?: LoyaltyTier | string;
  loyaltyProgramId?: string;
  
  // Card Program
  cardProgramType?: CardProgramType | string;
  linkedCardId?: string;
  budgetOwnerId?: string;
  costCenter?: string;
  department?: string;
  
  // MCC Restrictions (JSON strings)
  mccWhitelist?: string;
  mccBlacklist?: string;
  merchantWhitelist?: string;
  countryWhitelist?: string;
  
  // Expiry
  balanceExpiryDate?: string;
  expiryAction?: ExpiryAction | string;

   // Dynamic Hierarchy Dimensions
  // Map of dimension key -> value (e.g., { region: 'NORTH', city: 'DUBAI' })
  hierarchyDimensions?: Record<string, string>;
}

// ============================================================================
// NEW: HIERARCHY LEVEL CONFIG
// ============================================================================

export interface HierarchyLevelConfig {
  id?: string;
  programId?: string;
  levelNumber: number;
  levelName: string;
  dimensionType: string;
  isRequired?: boolean;
  allowedValues?: string[];
  description?: string;
  createdAt?: string;
  updatedAt?: string;
}

// ============================================================================
// NEW: PROGRAM-SPECIFIC VA REQUEST TYPES
// ============================================================================

export interface CollectionVaRequest {
  currency: string;
  channel: string;      // L2: INVOICE, ECOMMERCE, POS, DIRECT
  platform: string;     // L3: AMAZON, NOON, SHOPIFY, etc.
  segment: string;      // L4: SME, ENTERPRISE, RETAIL
  customerId: string;   // L5: Customer identifier
  customerName: string;
  accountType: string;  // L6: RECEIVABLES, REFUNDS, etc.
  vaName?: string;
  externalReference?: string;
}

export interface IhbVaRequest {
  currency: string;
  region: string;       // L2: NORTH, SOUTH, EMEA, APAC
  state: string;        // L3: State/Province/Emirate
  city: string;         // L4: City
  entityCode: string;   // L5: Legal entity code
  entityName: string;
  accountType: string;  // L6: PAYABLES, RECEIVABLES, TAXES, PAYROLL
  vaName?: string;
  externalReference?: string;
}

export interface WalletVaRequest {
  currency: string;
  userType: string;     // L2: CONSUMER, MERCHANT, AGENT
  region: string;       // L3: Region
  kycTier: string;      // L4: TIER_1, TIER_2, TIER_3
  groupCode?: string;   // L5: Optional grouping
  walletType: string;   // L6: MAIN, SAVINGS, GIFT
  customerId: string;
  customerName: string;
  holderPartyId?: string;
  vaName?: string;
  externalReference?: string;
}

export interface EscrowVaRequest {
  currency: string;
  escrowType: string;    // L2: REAL_ESTATE, TRADE, M_AND_A, PROJECT
  transactionId: string; // L3: Transaction reference
  partyRole: string;     // L4: BUYER, SELLER, AGENT, BROKER
  partyName: string;
  vaName?: string;
  externalReference?: string;
}

export interface VibanVaRequest {
  currency: string;
  poolId: string;        // L2: VIBAN pool identifier
  customerType: string;  // L3: CORPORATE, SME, RETAIL, FINTECH
  customerId: string;    // L4: Customer identifier
  customerName: string;
  viban?: string;        // Pre-assigned VIBAN (optional)
  vaName?: string;
  externalReference?: string;
}

export interface PayablesVaRequest {
  currency: string;
  paymentType: string;    // L2: SUPPLIER, PAYROLL, TAX, UTILITY
  entityCode: string;     // L3: Legal entity
  entityName: string;
  costCenter: string;     // L4: Cost center
  beneficiaryType: string;// L5: DOMESTIC, INTERNATIONAL, INTERNAL
  vaName?: string;
  externalReference?: string;
}

export interface CorporateCardVaRequest {
  currency: string;
  department: string;     // L2: Department
  costCenter: string;     // L3: Cost center
  employeeId: string;     // L4: Employee ID
  employeeName: string;
  cardType: string;       // L5: TRAVEL, PROCUREMENT, EXPENSE, FLEET
  vaName?: string;
  externalReference?: string;
}

export interface MobileMoneyVaRequest {
  currency: string;
  agentType: string;      // L2: MASTER_AGENT, SUPER_AGENT, AGENT, MERCHANT
  region: string;         // L3: Region
  agentId: string;        // L4: Agent ID
  agentName: string;
  vaName?: string;
  externalReference?: string;
}

// ============================================================================
// NEW: GENERIC HIERARCHY VA REQUEST
// ============================================================================

export interface CreateWithHierarchyRequest {
  programId: string;
  currency: string;
  hierarchyDimensions: Record<string, string>;
  vaName?: string;
  externalReference?: string;
  walletType?: string;
  kycLevel?: number;
}

// ============================================================================
// VIRTUAL ACCOUNT - UPDATE REQUEST
// ============================================================================

export interface UpdateVaRequest {
  vaName?: string;
  externalReference?: string;
  metadata?: string;
  
  // Hierarchy
  hierarchyNodeId?: string;
  collectionChannel?: string;
  
  // Wallet
  walletType?: string;
  expiresAt?: string;
  holderPartyId?: string;
  
  // Card Program
  cardProgramType?: string;
  linkedCardId?: string;
  budgetOwnerId?: string;
  costCenter?: string;
  department?: string;
  
  // Loyalty
  loyaltyTier?: string;
  loyaltyProgramId?: string;
  
  // Expiry
  balanceExpiryDate?: string;
  expiryAction?: string;
}

// ============================================================================
// VIRTUAL ACCOUNT - LIMITS UPDATE REQUEST
// ============================================================================

export interface LimitsUpdateRequest {
  perTransactionLimit?: number;
  dailyLimit?: number;
  weeklyLimit?: number;
  monthlyLimit?: number;
  annualLimit?: number;
  maxBalance?: number;
  dailyTopupLimit?: number;
  monthlyTopupLimit?: number;
  resetUsage?: boolean;
}

// ============================================================================
// VIRTUAL ACCOUNT - KYC UPDATE REQUEST
// ============================================================================

export interface KycUpdateRequest {
  kycLevel: number;
  kycExpiryDate?: string;
}

// ============================================================================
// VIRTUAL ACCOUNT - MCC RESTRICTIONS REQUEST
// ============================================================================

export interface MccRestrictionsRequest {
  mccWhitelist?: string[];
  mccBlacklist?: string[];
  merchantWhitelist?: string[];
  countryWhitelist?: string[];
  replaceAll?: boolean;
}

// ============================================================================
// VIRTUAL ACCOUNT - RESPONSE
// ============================================================================

export interface VaResponse {
  // Core
  id: string;
  vaNumber: string;
  viban?: string;
  vaName: string;
  programId: string;
  programName?: string;
  programType?: string;
  corporateId: string;
  corporateName?: string;
  physicalAccountId: string;
  physicalAccountNumber?: string;
  currencyCode: string;
  currentBalance: number;
  availableBalance: number;
  heldBalance?: number;
  status: VaStatus | string;
  statusLabel?: string;
  statusVariant?: string;
  externalReference?: string;
  kycVerified?: boolean;
  metadata?: string;
  createdAt: string;
  updatedAt?: string;
  
  // Hierarchy
  hierarchyNodeId?: string;
  hierarchyNodeName?: string;
  hierarchyPath?: string;
  hierarchyLevel?: number;
  collectionChannel?: string;
  
  // Wallet
  walletType?: string;
  walletTypeLabel?: string;
  kycLevel?: number;
  kycLevelLabel?: string;
  kycExpiryDate?: string;
  kycVerifiedAt?: string;
  expiresAt?: string;
  isExpired?: boolean;
  daysUntilExpiry?: number;
  suspensionReason?: string;
  blockReason?: string;
  holderPartyId?: string;
  
  // Limits
  limits?: LimitsInfo;
  limitsUsage?: LimitsUsage;
  
  // Value Type / Loyalty
  valueType?: string;
  valueTypeLabel?: string;
  pointsToCurrencyRate?: number;
  pointsBalance?: number;
  pendingPoints?: number;
  lifetimePoints?: number;
  loyaltyTier?: string;
  loyaltyTierLabel?: string;
  loyaltyProgramId?: string;
  
  // Card Program
  cardProgramType?: string;
  cardProgramTypeLabel?: string;
  linkedCardId?: string;
  budgetOwnerId?: string;
  costCenter?: string;
  department?: string;
  
  // MCC Restrictions
  mccRestrictions?: MccRestrictions;
  hasMccRestrictions?: boolean;
  
  // Expiry
  balanceExpiryDate?: string;
  expiryAction?: string;
  expiryActionLabel?: string;
  balanceExpired?: boolean;
  daysUntilBalanceExpiry?: number;
  
  // Stats
  transactionCount?: number;
  topupCount?: number;
  withdrawalCount?: number;
  lastTransactionAt?: string;
  lastTopupAt?: string;
  lastWithdrawalAt?: string;
  lastActivityDate?: string;
  activatedAt?: string;
}

// ============================================================================
// VIRTUAL ACCOUNT - SUMMARY (Lightweight)
// ============================================================================

export interface VaSummary {
  id: string;
  vaNumber: string;
  viban?: string;
  vaName: string;
  currencyCode: string;
  currentBalance: number;
  availableBalance: number;
  status: string;
  statusVariant?: string;
  walletType?: string;
  kycVerified?: boolean;
  kycLevel?: number;
  hierarchyPath?: string;
  createdAt: string;
}

// ============================================================================
// LIMITS INFO & USAGE
// ============================================================================

export interface LimitsInfo {
  perTransactionLimit?: number;
  dailyLimit?: number;
  weeklyLimit?: number;
  monthlyLimit?: number;
  annualLimit?: number;
  maxBalance?: number;
  dailyTopupLimit?: number;
  monthlyTopupLimit?: number;
  hasSpendingLimits?: boolean;
  hasTopupLimits?: boolean;
}

export interface LimitsUsage {
  dailyUsed?: number;
  weeklyUsed?: number;
  monthlyUsed?: number;
  annualUsed?: number;
  dailyTopupUsed?: number;
  monthlyTopupUsed?: number;
  lastResetDate?: string;
  
  // Remaining
  dailyRemaining?: number;
  weeklyRemaining?: number;
  monthlyRemaining?: number;
  annualRemaining?: number;
  dailyTopupRemaining?: number;
  monthlyTopupRemaining?: number;
  
  // Percentages
  dailyUsagePercent?: number;
  weeklyUsagePercent?: number;
  monthlyUsagePercent?: number;
  annualUsagePercent?: number;
  dailyTopupUsagePercent?: number;
  monthlyTopupUsagePercent?: number;
  
  // Warnings
  dailyLimitWarning?: boolean;
  weeklyLimitWarning?: boolean;
  monthlyLimitWarning?: boolean;
  dailyLimitExceeded?: boolean;
  weeklyLimitExceeded?: boolean;
  monthlyLimitExceeded?: boolean;
}

// ============================================================================
// MCC RESTRICTIONS
// ============================================================================

export interface MccRestrictions {
  mccWhitelist?: string[];
  mccBlacklist?: string[];
  merchantWhitelist?: string[];
  countryWhitelist?: string[];
  mccWhitelistCount?: number;
  mccBlacklistCount?: number;
  merchantWhitelistCount?: number;
  countryWhitelistCount?: number;
}

// ============================================================================
// BALANCE RESPONSE
// ============================================================================

export interface BalanceResponse {
  id: string;
  vaNumber: string;
  currencyCode: string;
  currentBalance: number;
  availableBalance: number;
  heldBalance?: number;
  pointsBalance?: number;
  pendingPoints?: number;
  lastActivityDate?: string;
}

// ============================================================================
// STATISTICS
// ============================================================================

export interface VaStats {
  totalAccounts: number;
  activeAccounts: number;
  inactiveAccounts?: number;
  suspendedAccounts?: number;
  blockedAccounts?: number;
  expiredAccounts?: number;
  pendingActivationAccounts?: number;
  totalBalance: number;
  availableBalance?: number;
  heldBalance?: number;
  countByWalletType?: Record<string, number>;
  countByStatus?: Record<string, number>;
  countByKycLevel?: Record<string, number>;
  kycVerifiedCount?: number;
  kycPendingCount?: number;
  kycExpiringCount?: number;
  activeLastDay?: number;
  activeLastWeek?: number;
  activeLastMonth?: number;
}

// ============================================================================
// BULK OPERATIONS
// ============================================================================

export interface BulkStatusUpdateRequest {
  accountIds: string[];
  status: string;
  reason?: string;
}

export interface BulkLimitsUpdateRequest {
  accountIds: string[];
  perTransactionLimit?: number;
  dailyLimit?: number;
  weeklyLimit?: number;
  monthlyLimit?: number;
  annualLimit?: number;
  maxBalance?: number;
}

export interface BulkOperationResponse {
  totalRequested: number;
  successCount: number;
  failureCount: number;
  errors?: BulkOperationError[];
}

export interface BulkOperationError {
  accountId: string;
  error: string;
  errorCode?: string;
}

// ============================================================================
// HIERARCHY NODE
// ============================================================================

export interface HierarchyNode {
  id: string;
  nodeCode: string;
  nodeName: string;
  nodeType: 'MASTER' | 'CONSOLIDATION' | 'VIRTUAL_ACCOUNT';
  levelNumber: number;
  parentId?: string;
  currencyCode: string;
  aggregatedBalance?: number;
  virtualAccountId?: string;
  primaryViban?: string;
  vibanCount?: number;
  children?: HierarchyNode[];
  materializedPath: string;
  status?: string;
}

// ============================================================================
// API RESPONSE WRAPPER
// ============================================================================

export interface ApiResponse<T> {
  success: boolean;
  data: T;
  message?: string;
  timestamp?: string;
}

export interface PagedResponse<T> {
  success: boolean;
  data: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages?: number;
  message?: string;
}

// ============================================================================
// FORM STATE
// ============================================================================

export interface VaFormState {
  data: CreateVaRequest;
  errors: Record<string, string>;
  touched: Record<string, boolean>;
  isValid: boolean;
  isDirty: boolean;
}

// ============================================================================
// FILTER & SEARCH
// ============================================================================

export interface VaSearchParams {
  query?: string;
  status?: VaStatus;
  walletType?: WalletType;
  currencyCode?: string;
  corporateId?: string;
  programId?: string;
  hierarchyNodeId?: string;
  kycLevel?: number;
  page?: number;
  size?: number;
  sort?: string;
  hierarchyLevel?: number;
}

export interface VaExportParams {
  status?: VaStatus;
  corporateId?: string;
  programId?: string;
  walletType?: string;
  format?: 'csv' | 'xlsx' | 'json';
}