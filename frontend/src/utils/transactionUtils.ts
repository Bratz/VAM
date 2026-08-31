// ============================================================================
// TRANSACTION UTILITY FUNCTIONS
// ============================================================================
// Centralized logic for determining transaction direction (debit/credit)
// based on MovementType. This must match the backend Transaction.java
// isCredit() and isDebit() methods.
// ============================================================================

/**
 * Movement types that represent CREDIT transactions (increase balance).
 * Must match Transaction.java isCredit() method.
 */
export const CREDIT_MOVEMENT_TYPES = [
  // Standard VA movements
  'CREDIT',
  'TRANSFER_IN',
  'REVERSAL',
  // Treasury movements
  'SWEEP_IN',
  'POOL_CREDIT',
  // Wallet movements
  'TOPUP',
  'WALLET_TRANSFER_IN',
  'REFUND',
  'CASHBACK',
  'INTEREST',
  // ROBO/POBO movements
  'ROBO_CREDIT',
  // Intercompany movements
  'IC_PAYABLE',      // Treasury owes subsidiary (ROBO collection)
  'EXCEPTION_CREDIT',
  // Loyalty movements
  'POINTS_EARN',
  'POINTS_TRANSFER',  // Can be credit when receiving
  // Card movements
  'CARD_REFUND',
  // Gift card movements
  'GIFT_CARD_LOAD',
  // Settlement/Exception VA types (CREDITS to Settlement VA)
  'FEE_CREDIT',
  'CHARGE_CREDIT',
  'TAX_CREDIT',
  'SETTLEMENT_CREDIT',
  'EXCEPTION_PARK',
  'INTEREST_ALLOCATE',
  // Hierarchy movements
  'HIERARCHY_TRANSFER',  // Can be credit when receiving
] as const;

/**
 * Movement types that represent DEBIT transactions (decrease balance).
 * Must match Transaction.java isDebit() method.
 */
export const DEBIT_MOVEMENT_TYPES = [
  // Standard VA movements
  'DEBIT',
  'TRANSFER_OUT',
  // Treasury movements
  'SWEEP_OUT',
  'POOL_DEBIT',
  'NETTING',
  // Wallet movements
  'WITHDRAWAL',
  'WALLET_TRANSFER_OUT',
  'PAYMENT',
  'PURCHASE',
  'FEE',  // Fee DEBIT on source VA
  'ADJUSTMENT',  // Balance adjustment (typically debit)
  // ROBO/POBO movements
  'POBO_DEBIT',
  // Intercompany movements
  'IC_RECEIVABLE',   // Treasury owed by subsidiary (POBO payment)
  'EXCEPTION_DEBIT',
  // Loyalty movements
  'POINTS_BURN',
  'POINTS_EXPIRE',  // Points expiration is a debit
  // Card movements
  'CARD_PURCHASE',
  'CARD_AUTHORIZATION',  // Authorization hold
  'CARD_SETTLEMENT',  // Settlement of card purchase
  // Gift card movements
  'GIFT_CARD_REDEEM',
  // Exception VA types
  'EXCEPTION_RELEASE',
] as const;

export type CreditMovementType = typeof CREDIT_MOVEMENT_TYPES[number];
export type DebitMovementType = typeof DEBIT_MOVEMENT_TYPES[number];
export type MovementType = CreditMovementType | DebitMovementType;

/**
 * Check if a movement type represents a CREDIT (increases balance).
 * Handles edge cases: null, undefined, and case-insensitive matching.
 * @param movementType The movement type string
 * @returns true if credit, false otherwise
 */
export const isCredit = (movementType: string | null | undefined): boolean => {
  if (!movementType) return false;
  // Normalize to uppercase for case-insensitive matching
  const normalized = String(movementType).toUpperCase().trim();
  return CREDIT_MOVEMENT_TYPES.includes(normalized as CreditMovementType);
};

/**
 * Check if a movement type represents a DEBIT (decreases balance).
 * Handles edge cases: null, undefined, and case-insensitive matching.
 * @param movementType The movement type string
 * @returns true if debit, false otherwise
 */
export const isDebit = (movementType: string | null | undefined): boolean => {
  if (!movementType) return false;
  // Normalize to uppercase for case-insensitive matching
  const normalized = String(movementType).toUpperCase().trim();
  return DEBIT_MOVEMENT_TYPES.includes(normalized as DebitMovementType);
};

/**
 * Get the sign for display purposes.
 * @param movementType The movement type string
 * @returns '+' for credits, '-' for debits, '' for unknown
 */
export const getAmountSign = (movementType: string | null | undefined): '+' | '-' | '' => {
  if (isCredit(movementType)) return '+';
  if (isDebit(movementType)) return '-';
  return '';
};

/**
 * Get the signed amount for display.
 * @param amount The absolute amount
 * @param movementType The movement type string
 * @returns Positive amount for credits, negative for debits
 */
export const getSignedAmount = (amount: number, movementType: string | null | undefined): number => {
  if (isDebit(movementType)) return -Math.abs(amount);
  return Math.abs(amount);
};

/**
 * Get CSS color class for amount display.
 * @param movementType The movement type string
 * @returns Tailwind color class
 */
export const getAmountColorClass = (movementType: string | null | undefined): string => {
  if (isCredit(movementType)) return 'text-success-600';
  if (isDebit(movementType)) return 'text-error-600';
  return 'text-neutral-900';
};

/**
 * Get background color class for icons/badges.
 * @param movementType The movement type string
 * @returns Tailwind background color class
 */
export const getMovementBgClass = (movementType: string | null | undefined): string => {
  if (isCredit(movementType)) return 'bg-success-50';
  if (isDebit(movementType)) return 'bg-error-50';
  return 'bg-neutral-50';
};

/**
 * Get icon color class.
 * @param movementType The movement type string
 * @returns Tailwind icon color class
 */
export const getMovementIconClass = (movementType: string | null | undefined): string => {
  if (isCredit(movementType)) return 'text-success-600';
  if (isDebit(movementType)) return 'text-error-600';
  return 'text-neutral-600';
};

/**
 * Format amount with sign for display.
 * @param amount The absolute amount
 * @param movementType The movement type string
 * @param currencyCode The currency code
 * @param formatFn Optional currency format function
 * @returns Formatted string like "+1,234.56 AED" or "-1,234.56 AED"
 */
export const formatSignedAmount = (
  amount: number,
  movementType: string | null | undefined,
  currencyCode: string = 'AED',
  formatFn?: (amount: number, currency: string) => string
): string => {
  const sign = getAmountSign(movementType);
  const formattedAmount = formatFn
    ? formatFn(Math.abs(amount), currencyCode)
    : `${Math.abs(amount).toLocaleString()} ${currencyCode}`;
  return `${sign}${formattedAmount}`;
};