// ============================================================================
// HIERARCHY TEMPLATES CONFIGURATION
// Path: src/config/templateHierarchy.ts
// Required by: ProgramsPage.tsx
// ============================================================================

import {
  CreditCard,
  Building2,
  Wallet,
  Shield,
  Hash,
  Gift,
  TrendingUp,
  Banknote,
  CreditCard as CardIcon,
} from 'lucide-react';
import type { LucideIcon } from 'lucide-react';

// ============================================================================
// TYPES
// ============================================================================

export interface HierarchyLevelConfig {
  levelNumber: number;
  levelName: string;
  dimensionType: string;
  description?: string;
  allowedValues?: string[];
  isRequired?: boolean;
  icon?: string;
}

export interface TemplateConfig {
  id: string;
  name: string;
  description: string;
  icon: LucideIcon;
  /** StatusIconBadge tone for this template's icon tile. */
  tone: 'success' | 'warning' | 'error' | 'info' | 'primary' | 'neutral' | 'accent' | 'cat-1' | 'cat-2' | 'cat-3' | 'cat-4' | 'cat-5' | 'cat-6' | 'cat-7' | 'cat-8';
  /** Feature flags a program must have switched on for this template to apply. Empty = always applicable. */
  forFeatures: string[];
  recommended?: boolean;
  levels: HierarchyLevelConfig[];
}

// ============================================================================
// HIERARCHY TEMPLATES
// ============================================================================

export const HIERARCHY_TEMPLATES: TemplateConfig[] = [
  // ─────────────────────────────────────────────────────────────────────────
  // COLLECTION PROGRAM TEMPLATE
  // ─────────────────────────────────────────────────────────────────────────
  {
    id: 'COLLECTION_PROGRAM',
    name: 'Collection Program',
    description: 'For receivables collection with channel-based hierarchy',
    icon: CreditCard,
    tone: 'info',
    forFeatures: [],
    recommended: true,
    levels: [
      {
        levelNumber: 1,
        levelName: 'Currency',
        dimensionType: 'CURRENCY',
        description: 'Top-level currency grouping',
        isRequired: true,
      },
      {
        levelNumber: 2,
        levelName: 'Channel',
        dimensionType: 'CHANNEL',
        description: 'Collection channel type',
        allowedValues: ['INVOICE', 'ECOMMERCE', 'POS', 'DIRECT', 'STANDING_ORDER'],
      },
      {
        levelNumber: 3,
        levelName: 'Platform',
        dimensionType: 'PLATFORM',
        description: 'Payment platform or gateway',
      },
      {
        levelNumber: 4,
        levelName: 'Segment',
        dimensionType: 'SEGMENT',
        description: 'Customer segment (Corporate, SME, Retail)',
        allowedValues: ['CORPORATE', 'SME', 'RETAIL', 'GOVERNMENT'],
      },
      {
        levelNumber: 5,
        levelName: 'Customer',
        dimensionType: 'CUSTOMER',
        description: 'Individual customer grouping',
      },
      {
        levelNumber: 6,
        levelName: 'Account Type',
        dimensionType: 'ACCOUNT_TYPE',
        description: 'Type of receivable account',
        allowedValues: ['RECEIVABLES', 'DEPOSITS', 'PREPAID'],
      },
      {
        levelNumber: 7,
        levelName: 'Virtual Account',
        dimensionType: 'VIRTUAL_ACCOUNT',
        description: 'Leaf-level virtual account',
        isRequired: true,
      },
    ],
  },

  // ─────────────────────────────────────────────────────────────────────────
  // IN-HOUSE BANK (IHB) PROGRAM TEMPLATE
  // ─────────────────────────────────────────────────────────────────────────
  {
    id: 'IHB_PROGRAM',
    name: 'In-House Bank',
    description: 'For intercompany cash management and treasury',
    icon: Building2,
    tone: 'primary',
    forFeatures: ['ihbEnabled'],
    recommended: true,
    levels: [
      {
        levelNumber: 1,
        levelName: 'Currency',
        dimensionType: 'CURRENCY',
        description: 'Currency pool',
        isRequired: true,
      },
      {
        levelNumber: 2,
        levelName: 'Region',
        dimensionType: 'REGION',
        description: 'Geographic region',
        allowedValues: ['NORTH', 'SOUTH', 'EAST', 'WEST', 'MENA', 'APAC', 'EMEA', 'AMERICAS'],
      },
      {
        levelNumber: 3,
        levelName: 'State/Province',
        dimensionType: 'STATE',
        description: 'State or province level',
      },
      {
        levelNumber: 4,
        levelName: 'City',
        dimensionType: 'CITY',
        description: 'City-level grouping',
      },
      {
        levelNumber: 5,
        levelName: 'Legal Entity',
        dimensionType: 'ENTITY',
        description: 'Subsidiary or legal entity',
      },
      {
        levelNumber: 6,
        levelName: 'Account Type',
        dimensionType: 'ACCOUNT_TYPE',
        description: 'Functional account type',
        allowedValues: ['PAYABLES', 'RECEIVABLES', 'TAXES', 'PAYROLL', 'CAPEX', 'INTERCOMPANY'],
      },
      {
        levelNumber: 7,
        levelName: 'Virtual Account',
        dimensionType: 'VIRTUAL_ACCOUNT',
        description: 'Leaf-level virtual account',
        isRequired: true,
      },
    ],
  },

  // ─────────────────────────────────────────────────────────────────────────
  // WALLET PROGRAM TEMPLATE
  // ─────────────────────────────────────────────────────────────────────────
  {
    id: 'WALLET_PROGRAM',
    name: 'Digital Wallet',
    description: 'For consumer and merchant wallet programs',
    icon: Wallet,
    tone: 'warning',
    forFeatures: ['walletEnabled'],
    recommended: true,
    levels: [
      {
        levelNumber: 1,
        levelName: 'Currency',
        dimensionType: 'CURRENCY',
        description: 'Wallet currency',
        isRequired: true,
      },
      {
        levelNumber: 2,
        levelName: 'User Type',
        dimensionType: 'SEGMENT',
        description: 'Type of wallet user',
        allowedValues: ['CONSUMER', 'MERCHANT', 'AGENT', 'CORPORATE'],
      },
      {
        levelNumber: 3,
        levelName: 'Region',
        dimensionType: 'REGION',
        description: 'Geographic region',
      },
      {
        levelNumber: 4,
        levelName: 'KYC Tier',
        dimensionType: 'KYC_TIER',
        description: 'KYC verification level',
        allowedValues: ['TIER_1', 'TIER_2', 'TIER_3', 'FULL_KYC'],
      },
      {
        levelNumber: 5,
        levelName: 'Group',
        dimensionType: 'ENTITY',
        description: 'User grouping or organization',
      },
      {
        levelNumber: 6,
        levelName: 'Wallet Type',
        dimensionType: 'ACCOUNT_TYPE',
        description: 'Type of wallet',
        allowedValues: ['PRIMARY', 'SAVINGS', 'TRANSIT', 'ESCROW'],
      },
      {
        levelNumber: 7,
        levelName: 'Wallet',
        dimensionType: 'VIRTUAL_ACCOUNT',
        description: 'Individual wallet account',
        isRequired: true,
      },
    ],
  },

  // ─────────────────────────────────────────────────────────────────────────
  // ESCROW PROGRAM TEMPLATE
  // ─────────────────────────────────────────────────────────────────────────
  {
    id: 'ESCROW_PROGRAM',
    name: 'Digital Escrow',
    description: 'For escrow and trust account management',
    icon: Shield,
    tone: 'success',
    forFeatures: ['escrowEnabled'],
    recommended: true,
    levels: [
      {
        levelNumber: 1,
        levelName: 'Currency',
        dimensionType: 'CURRENCY',
        description: 'Escrow currency',
        isRequired: true,
      },
      {
        levelNumber: 2,
        levelName: 'Deal Type',
        dimensionType: 'DEAL_TYPE',
        description: 'Type of escrow deal',
        allowedValues: ['REAL_ESTATE', 'M_AND_A', 'TRADE', 'INSURANCE', 'LEGAL'],
      },
      {
        levelNumber: 3,
        levelName: 'Parties',
        dimensionType: 'PARTIES',
        description: 'Transaction parties grouping',
      },
      {
        levelNumber: 4,
        levelName: 'Status',
        dimensionType: 'STATUS',
        description: 'Escrow status',
        allowedValues: ['FUNDED', 'PENDING', 'RELEASED', 'DISPUTED'],
      },
      {
        levelNumber: 5,
        levelName: 'Escrow Account',
        dimensionType: 'VIRTUAL_ACCOUNT',
        description: 'Individual escrow account',
        isRequired: true,
      },
    ],
  },

  // ─────────────────────────────────────────────────────────────────────────
  // VIBAN PROGRAM TEMPLATE
  // ─────────────────────────────────────────────────────────────────────────
  {
    id: 'VIBAN_PROGRAM',
    name: 'Virtual IBAN',
    description: 'For VIBAN issuance and management',
    icon: Hash,
    tone: 'accent',
    forFeatures: ['vibanEnabled'],
    recommended: true,
    levels: [
      {
        levelNumber: 1,
        levelName: 'Currency',
        dimensionType: 'CURRENCY',
        description: 'VIBAN currency',
        isRequired: true,
      },
      {
        levelNumber: 2,
        levelName: 'Client Type',
        dimensionType: 'SEGMENT',
        description: 'Type of VIBAN client',
        allowedValues: ['FINTECH', 'CORPORATE', 'PSP', 'EXCHANGE'],
      },
      {
        levelNumber: 3,
        levelName: 'Region',
        dimensionType: 'REGION',
        description: 'Geographic region',
      },
      {
        levelNumber: 4,
        levelName: 'Product',
        dimensionType: 'PRODUCT',
        description: 'VIBAN product type',
        allowedValues: ['DEDICATED', 'POOLED', 'TEMPORARY'],
      },
      {
        levelNumber: 5,
        levelName: 'VIBAN',
        dimensionType: 'VIRTUAL_ACCOUNT',
        description: 'Individual VIBAN account',
        isRequired: true,
      },
    ],
  },

  // ─────────────────────────────────────────────────────────────────────────
  // PAYABLES PROGRAM TEMPLATE
  // ─────────────────────────────────────────────────────────────────────────
  {
    id: 'PAYABLES_PROGRAM',
    name: 'Payables Management',
    description: 'For accounts payable and vendor payments',
    icon: Banknote,
    tone: 'warning',
    forFeatures: [],
    recommended: true,
    levels: [
      {
        levelNumber: 1,
        levelName: 'Currency',
        dimensionType: 'CURRENCY',
        description: 'Payment currency',
        isRequired: true,
      },
      {
        levelNumber: 2,
        levelName: 'Payment Type',
        dimensionType: 'PAYMENT_TYPE',
        description: 'Type of payable',
        allowedValues: ['VENDOR', 'SALARY', 'TAX', 'UTILITY', 'INTERCOMPANY'],
      },
      {
        levelNumber: 3,
        levelName: 'Region',
        dimensionType: 'REGION',
        description: 'Geographic region',
      },
      {
        levelNumber: 4,
        levelName: 'Department',
        dimensionType: 'DEPARTMENT',
        description: 'Cost center or department',
      },
      {
        levelNumber: 5,
        levelName: 'Vendor Category',
        dimensionType: 'CATEGORY',
        description: 'Vendor classification',
      },
      {
        levelNumber: 6,
        levelName: 'Payables Account',
        dimensionType: 'VIRTUAL_ACCOUNT',
        description: 'Individual payables account',
        isRequired: true,
      },
    ],
  },

  // ─────────────────────────────────────────────────────────────────────────
  // LOYALTY PROGRAM TEMPLATE
  // ─────────────────────────────────────────────────────────────────────────
  {
    id: 'LOYALTY_PROGRAM',
    name: 'Loyalty & Rewards',
    description: 'For loyalty points and rewards programs',
    icon: TrendingUp,
    tone: 'cat-4',
    forFeatures: ['loyaltyEnabled'],
    recommended: false,
    levels: [
      {
        levelNumber: 1,
        levelName: 'Program',
        dimensionType: 'CURRENCY',
        description: 'Loyalty program identifier',
        isRequired: true,
      },
      {
        levelNumber: 2,
        levelName: 'Partner Type',
        dimensionType: 'CHANNEL',
        description: 'Earn partner category',
        allowedValues: ['AIRLINE', 'HOTEL', 'RETAIL', 'DINING', 'ENTERTAINMENT'],
      },
      {
        levelNumber: 3,
        levelName: 'Tier',
        dimensionType: 'TIER',
        description: 'Member tier level',
        allowedValues: ['PLATINUM', 'GOLD', 'SILVER', 'BLUE', 'MEMBER'],
      },
      {
        levelNumber: 4,
        levelName: 'Earn Category',
        dimensionType: 'SEGMENT',
        description: 'Points earning category',
      },
      {
        levelNumber: 5,
        levelName: 'Partner',
        dimensionType: 'PARTNER',
        description: 'Individual partner',
      },
      {
        levelNumber: 6,
        levelName: 'Points Status',
        dimensionType: 'POINTS_STATUS',
        description: 'Points lifecycle status',
        allowedValues: ['EARNED', 'AVAILABLE', 'PENDING', 'EXPIRED', 'REDEEMED'],
      },
      {
        levelNumber: 7,
        levelName: 'Member Wallet',
        dimensionType: 'VIRTUAL_ACCOUNT',
        description: 'Member points wallet',
        isRequired: true,
      },
    ],
  },

  // ─────────────────────────────────────────────────────────────────────────
  // GIFT CARD PROGRAM TEMPLATE
  // ─────────────────────────────────────────────────────────────────────────
  {
    id: 'GIFT_CARD_PROGRAM',
    name: 'Gift Card Program',
    description: 'For gift card issuance and management',
    icon: Gift,
    tone: 'cat-2',
    forFeatures: ['giftCardEnabled'],
    recommended: false,
    levels: [
      {
        levelNumber: 1,
        levelName: 'Currency',
        dimensionType: 'CURRENCY',
        description: 'Gift card currency',
        isRequired: true,
      },
      {
        levelNumber: 2,
        levelName: 'Card Type',
        dimensionType: 'CARD_TYPE',
        description: 'Gift card type',
        allowedValues: ['OPEN_LOOP', 'CLOSED_LOOP', 'SEMI_CLOSED'],
      },
      {
        levelNumber: 3,
        levelName: 'Merchant',
        dimensionType: 'MERCHANT',
        description: 'Issuing or redeeming merchant',
      },
      {
        levelNumber: 4,
        levelName: 'Channel',
        dimensionType: 'CHANNEL',
        description: 'Sales channel',
        allowedValues: ['RETAIL', 'CORPORATE', 'DIGITAL', 'PROMOTIONAL'],
      },
      {
        levelNumber: 5,
        levelName: 'Batch',
        dimensionType: 'BATCH',
        description: 'Card issuance batch',
      },
      {
        levelNumber: 6,
        levelName: 'Denomination',
        dimensionType: 'DENOMINATION',
        description: 'Card face value',
      },
      {
        levelNumber: 7,
        levelName: 'Gift Card',
        dimensionType: 'VIRTUAL_ACCOUNT',
        description: 'Individual gift card account',
        isRequired: true,
      },
    ],
  },

  // ─────────────────────────────────────────────────────────────────────────
  // CORPORATE CARD PROGRAM TEMPLATE
  // ─────────────────────────────────────────────────────────────────────────
  {
    id: 'CORPORATE_CARD_PROGRAM',
    name: 'Corporate Card',
    description: 'For corporate card expense management',
    icon: CardIcon,
    tone: 'neutral',
    forFeatures: ['corporateCardEnabled'],
    recommended: false,
    levels: [
      {
        levelNumber: 1,
        levelName: 'Currency',
        dimensionType: 'CURRENCY',
        description: 'Card program currency',
        isRequired: true,
      },
      {
        levelNumber: 2,
        levelName: 'Card Program',
        dimensionType: 'CARD_TYPE',
        description: 'Corporate card program type',
        allowedValues: ['TRAVEL', 'PROCUREMENT', 'FLEET', 'VIRTUAL', 'PETTY_CASH'],
      },
      {
        levelNumber: 3,
        levelName: 'Region',
        dimensionType: 'REGION',
        description: 'Geographic region',
      },
      {
        levelNumber: 4,
        levelName: 'Department',
        dimensionType: 'DEPARTMENT',
        description: 'Cost center or department',
      },
      {
        levelNumber: 5,
        levelName: 'Budget Owner',
        dimensionType: 'BUDGET_OWNER',
        description: 'Budget holder or manager',
      },
      {
        levelNumber: 6,
        levelName: 'Card Format',
        dimensionType: 'CARD_FORMAT',
        description: 'Physical or virtual',
        allowedValues: ['PHYSICAL', 'VIRTUAL', 'TOKENIZED'],
      },
      {
        levelNumber: 7,
        levelName: 'Card Account',
        dimensionType: 'VIRTUAL_ACCOUNT',
        description: 'Individual card account',
        isRequired: true,
      },
    ],
  },
];

// ============================================================================
// HELPER FUNCTIONS
// ============================================================================

type Flags = Record<string, boolean | undefined>;

const matches = (tpl: TemplateConfig, flags: Flags) =>
  tpl.forFeatures.every((f) => flags[f] === true);

/**
 * Templates applicable to a program, given the features it has switched on.
 *
 * This used to match on programType. The type is gone, and it was the weaker
 * key anyway: a program typed WALLET with walletEnabled false got wallet
 * templates it could not use, and one with walletEnabled true but another type
 * got none. Templates with no required feature apply to every program.
 */
export function getTemplatesForFeatures(flags: Flags): TemplateConfig[] {
  return HIERARCHY_TEMPLATES.filter((t) => matches(t, flags));
}

/**
 * Best template for a program: the most specific applicable one, preferring any
 * marked recommended. Most specific = requires the most features.
 */
export function getRecommendedTemplate(flags: Flags): TemplateConfig | null {
  const applicable = getTemplatesForFeatures(flags)
    .sort((a, b) => b.forFeatures.length - a.forFeatures.length);
  if (applicable.length === 0) return null;
  return applicable.find((t) => t.recommended && t.forFeatures.length > 0)
    ?? applicable.find((t) => t.recommended)
    ?? applicable[0];
}

/**
 * Get a template by its ID.
 */
export function getTemplateById(templateId: string): TemplateConfig | null {
  return HIERARCHY_TEMPLATES.find((t) => t.id === templateId) || null;
}

/**
 * Templates that do NOT apply to the program's current features.
 * Used for the "Other Templates" section.
 */
export function getOtherTemplates(flags: Flags): TemplateConfig[] {
  return HIERARCHY_TEMPLATES.filter((t) => !matches(t, flags));
}

/**
 * Convert template levels to backend LevelConfigRequest format.
 */
export function toLevelConfigRequests(template: TemplateConfig): Array<{
  levelNumber: number;
  levelName: string;
  dimensionType: string;
  description?: string;
  allowedValues?: string[];
  isRequired?: boolean;
}> {
  return template.levels.map((level) => ({
    levelNumber: level.levelNumber,
    levelName: level.levelName,
    dimensionType: level.dimensionType,
    description: level.description,
    allowedValues: level.allowedValues,
    isRequired: level.isRequired,
  }));
}

// ============================================================================
// EXPORTS
// ============================================================================

export default HIERARCHY_TEMPLATES;