// ============================================================================
// SHARED ENTITY CONSTANTS — Per-profile demo bundles
//
// One bundle per supported MarketProfile (UAE / KSA / UK / EU / US / SG).
// The active bundle is resolved at access time from the MarketContext.
//
// Usage:
//   - React components: `const bundle = useDemoBundle()` → bundle.entities, etc.
//   - Non-React code: `const bundle = getDemoBundle()` (falls back to UAE before
//     the MarketProvider has loaded).
//
// Adding a new market: extend `DEMO_BUNDLES` with the same shape as UAE_BUNDLE.
// Currently shipped: UAE (full), KSA, UK. EU/US/SG fall back to UAE — extend
// as needed when those demos are scheduled.
// ============================================================================

import { useMarket } from '../context/MarketContext';
import { getActiveMarket } from '../utils';

// ----------------------------------------------------------------------------
// Types
// ----------------------------------------------------------------------------

export interface CorporateCustomer {
  id: string;
  name: string;
  code: string;
  country: string;
  industry: string;
  incorporationDate: string;
}

export interface CorporateEntity {
  id: string;
  code: string;
  name: string;
  shortName: string;
  type: 'PARENT' | 'SUBSIDIARY' | 'BRANCH' | 'DIVISION';
  country: string;
  currency: string;
  timezone: string;
  status: 'ACTIVE' | 'INACTIVE' | 'SUSPENDED';
}

export interface VirtualAccountStandard {
  id: string;
  accountNumber: string;
  iban: string;
  name: string;
  entityCode: string;
  currency: string;
  purpose: 'OPERATING' | 'COLLECTIONS' | 'PAYABLES' | 'TREASURY' | 'ESCROW' | 'PAYROLL';
}

export interface PhysicalBankAccount {
  id: string;
  accountNumber: string;
  iban: string;
  name: string;
  entityCode: string;
  bankName: string;
  bankCode: string;
  currency: string;
  poolingEnabled: boolean;
  sweepEnabled: boolean;
  sweepRole?: 'HEADER' | 'PARTICIPANT';
}

export interface Party {
  id: string;
  code: string;
  name: string;
  type: 'INDIVIDUAL' | 'COMPANY' | 'GOVERNMENT' | 'FINANCIAL_INSTITUTION';
  roles: ('CUSTOMER' | 'VENDOR' | 'EMPLOYEE' | 'GOVERNMENT' | 'FINANCIAL')[];
  country: string;
  taxId?: string;
  kycStatus: 'PENDING' | 'VERIFIED' | 'EXPIRED' | 'EXEMPTED';
  riskRating: 'LOW' | 'MEDIUM' | 'HIGH';
  status: 'ACTIVE' | 'SUSPENDED' | 'BLOCKED' | 'INACTIVE';
}

export interface IhbParticipant {
  id: string;
  entityId: string;
  entityCode: string;
  entityName: string;
  entityType: 'PARENT' | 'SUBSIDIARY' | 'BRANCH' | 'DIVISION';
  virtualAccountId: string;
  virtualAccountNumber: string;
  currency: string;
  currentBalance: number;
  availableBalance: number;
  position: 'SURPLUS' | 'DEFICIT' | 'NEUTRAL';
  creditLimit: number;
  interestRateLend: number;
  interestRateBorrow: number;
  accruedInterest: number;
  status: 'ACTIVE' | 'INACTIVE' | 'SUSPENDED';
}

export interface DemoBundle {
  profileCode: string;
  corporateCustomer: CorporateCustomer;
  corporateEntities: CorporateEntity[];
  virtualAccounts: VirtualAccountStandard[];
  physicalAccounts: PhysicalBankAccount[];
  parties: Party[];
  ihbParticipants: IhbParticipant[];
}

// ----------------------------------------------------------------------------
// UAE bundle (full)
// ----------------------------------------------------------------------------

const UAE_BUNDLE: DemoBundle = {
  profileCode: 'UAE',
  corporateCustomer: {
    id: 'cust-001',
    name: 'Emirates Trading Group',
    code: 'ETG',
    country: 'AE',
    industry: 'Diversified Trading',
    incorporationDate: '2010-01-15',
  },
  corporateEntities: [
    { id: 'ent-hq',   code: 'HQ',   name: 'Group Treasury',         shortName: 'Treasury',  type: 'PARENT',     country: 'AE', currency: 'AED', timezone: 'Asia/Dubai',       status: 'ACTIVE' },
    { id: 'ent-dxb',  code: 'DXB',  name: 'Dubai Operations',       shortName: 'Dubai Ops', type: 'SUBSIDIARY', country: 'AE', currency: 'AED', timezone: 'Asia/Dubai',       status: 'ACTIVE' },
    { id: 'ent-auh',  code: 'AUH',  name: 'Abu Dhabi Operations',   shortName: 'Abu Dhabi', type: 'SUBSIDIARY', country: 'AE', currency: 'AED', timezone: 'Asia/Dubai',       status: 'ACTIVE' },
    { id: 'ent-shj',  code: 'SHJ',  name: 'Sharjah Branch',         shortName: 'Sharjah',   type: 'BRANCH',     country: 'AE', currency: 'AED', timezone: 'Asia/Dubai',       status: 'ACTIVE' },
    { id: 'ent-rak',  code: 'RAK',  name: 'Ras Al Khaimah Unit',    shortName: 'RAK',       type: 'BRANCH',     country: 'AE', currency: 'AED', timezone: 'Asia/Dubai',       status: 'ACTIVE' },
    { id: 'ent-eu',   code: 'EU',   name: 'European Subsidiary',    shortName: 'Europe',    type: 'SUBSIDIARY', country: 'NL', currency: 'EUR', timezone: 'Europe/Amsterdam', status: 'ACTIVE' },
    { id: 'ent-uk',   code: 'UK',   name: 'UK Operations',          shortName: 'UK',        type: 'BRANCH',     country: 'GB', currency: 'GBP', timezone: 'Europe/London',    status: 'ACTIVE' },
    { id: 'ent-sg',   code: 'SG',   name: 'Singapore Operations',   shortName: 'Singapore', type: 'SUBSIDIARY', country: 'SG', currency: 'SGD', timezone: 'Asia/Singapore',   status: 'ACTIVE' },
  ],
  virtualAccounts: [
    { id: 'va-hq-001', accountNumber: 'VA-HQ-001', iban: 'AE070331234567890000001', name: 'Treasury Master',   entityCode: 'HQ',  currency: 'AED', purpose: 'TREASURY' },
    { id: 'va-hq-002', accountNumber: 'VA-HQ-002', iban: 'AE070331234567890000002', name: 'Collections Pool',  entityCode: 'HQ',  currency: 'AED', purpose: 'COLLECTIONS' },
    { id: 'va-hq-003', accountNumber: 'VA-HQ-003', iban: 'AE070331234567890000003', name: 'Payables Pool',     entityCode: 'HQ',  currency: 'AED', purpose: 'PAYABLES' },
    { id: 'va-dxb-001', accountNumber: 'VA-DXB-001', iban: 'AE070331234567890000101', name: 'Dubai Operating',     entityCode: 'DXB', currency: 'AED', purpose: 'OPERATING' },
    { id: 'va-auh-001', accountNumber: 'VA-AUH-001', iban: 'AE070331234567890000201', name: 'Abu Dhabi Operating', entityCode: 'AUH', currency: 'AED', purpose: 'OPERATING' },
    { id: 'va-shj-001', accountNumber: 'VA-SHJ-001', iban: 'AE070331234567890000301', name: 'Sharjah Operating',   entityCode: 'SHJ', currency: 'AED', purpose: 'OPERATING' },
  ],
  physicalAccounts: [
    { id: 'pa-enbd-001', accountNumber: '0331234567890', iban: 'AE070331234567890123456', name: 'Main Operating',  entityCode: 'HQ',  bankName: 'Emirates NBD', bankCode: 'EABORAEAD', currency: 'AED', poolingEnabled: true, sweepEnabled: true, sweepRole: 'HEADER' },
    { id: 'pa-enbd-002', accountNumber: '0331234567891', iban: 'AE070331234567890123457', name: 'Dubai Branch',    entityCode: 'DXB', bankName: 'Emirates NBD', bankCode: 'EABORAEAD', currency: 'AED', poolingEnabled: true, sweepEnabled: true, sweepRole: 'PARTICIPANT' },
    { id: 'pa-enbd-003', accountNumber: '0331234567892', iban: 'AE070331234567890123458', name: 'Abu Dhabi Branch', entityCode: 'AUH', bankName: 'Emirates NBD', bankCode: 'EABORAEAD', currency: 'AED', poolingEnabled: true, sweepEnabled: true, sweepRole: 'PARTICIPANT' },
    { id: 'pa-hsbc-001', accountNumber: '0441234567890', iban: 'AE070441234567890123456', name: 'Payroll Account', entityCode: 'HQ',  bankName: 'HSBC',         bankCode: 'BBABORAEAD', currency: 'AED', poolingEnabled: false, sweepEnabled: true, sweepRole: 'PARTICIPANT' },
  ],
  parties: [
    { id: 'party-001', code: 'P-001', name: 'Emirates Steel Industries', type: 'COMPANY',  roles: ['CUSTOMER', 'VENDOR'], country: 'AE', taxId: 'TRN100001234', kycStatus: 'VERIFIED', riskRating: 'LOW',    status: 'ACTIVE' },
    { id: 'party-002', code: 'P-002', name: 'Al Futtaim Group',          type: 'COMPANY',  roles: ['CUSTOMER'],           country: 'AE', taxId: 'TRN100005678', kycStatus: 'VERIFIED', riskRating: 'LOW',    status: 'ACTIVE' },
    { id: 'party-010', code: 'P-010', name: 'Dubai Logistics LLC',       type: 'COMPANY',  roles: ['VENDOR'],             country: 'AE', taxId: 'TRN200001234', kycStatus: 'VERIFIED', riskRating: 'LOW',    status: 'ACTIVE' },
    { id: 'party-011', code: 'P-011', name: 'Gulf IT Services',          type: 'COMPANY',  roles: ['VENDOR'],             country: 'AE', taxId: 'TRN200005678', kycStatus: 'VERIFIED', riskRating: 'MEDIUM', status: 'ACTIVE' },
    { id: 'party-040', code: 'F-001', name: 'Emirates NBD',              type: 'FINANCIAL_INSTITUTION', roles: ['FINANCIAL'], country: 'AE',                       kycStatus: 'VERIFIED', riskRating: 'LOW', status: 'ACTIVE' },
  ],
  ihbParticipants: [
    { id: 'ihb-hq',  entityId: 'ent-hq',  entityCode: 'HQ',  entityName: 'Group Treasury',       entityType: 'PARENT',     virtualAccountId: 'va-hq-001',  virtualAccountNumber: 'VA-HQ-001',  currency: 'AED', currentBalance: 25_000_000, availableBalance: 25_000_000, position: 'SURPLUS', creditLimit: 0,        interestRateLend: 3.0, interestRateBorrow: 4.0, accruedInterest: 0,        status: 'ACTIVE' },
    { id: 'ihb-dxb', entityId: 'ent-dxb', entityCode: 'DXB', entityName: 'Dubai Operations',     entityType: 'SUBSIDIARY', virtualAccountId: 'va-dxb-001', virtualAccountNumber: 'VA-DXB-001', currency: 'AED', currentBalance:  8_500_000, availableBalance:  8_500_000, position: 'SURPLUS', creditLimit: 5_000_000, interestRateLend: 2.5, interestRateBorrow: 4.0, accruedInterest:  17_708, status: 'ACTIVE' },
    { id: 'ihb-auh', entityId: 'ent-auh', entityCode: 'AUH', entityName: 'Abu Dhabi Operations', entityType: 'SUBSIDIARY', virtualAccountId: 'va-auh-001', virtualAccountNumber: 'VA-AUH-001', currency: 'AED', currentBalance: -3_500_000, availableBalance:  1_500_000, position: 'DEFICIT', creditLimit: 5_000_000, interestRateLend: 2.5, interestRateBorrow: 4.5, accruedInterest: -13_125, status: 'ACTIVE' },
    { id: 'ihb-shj', entityId: 'ent-shj', entityCode: 'SHJ', entityName: 'Sharjah Branch',       entityType: 'BRANCH',     virtualAccountId: 'va-shj-001', virtualAccountNumber: 'VA-SHJ-001', currency: 'AED', currentBalance:  1_200_000, availableBalance:  1_200_000, position: 'NEUTRAL', creditLimit: 2_000_000, interestRateLend: 2.5, interestRateBorrow: 4.0, accruedInterest:   2_500, status: 'ACTIVE' },
  ],
};

// ----------------------------------------------------------------------------
// KSA bundle
// ----------------------------------------------------------------------------

const KSA_BUNDLE: DemoBundle = {
  profileCode: 'KSA',
  corporateCustomer: {
    id: 'cust-001',
    name: 'Saudi Industrial Investment Group',
    code: 'SIIG',
    country: 'SA',
    industry: 'Petrochemicals & Industrial',
    incorporationDate: '2010-01-15',
  },
  corporateEntities: [
    { id: 'ent-hq',   code: 'HQ',   name: 'Group Treasury',         shortName: 'Treasury',   type: 'PARENT',     country: 'SA', currency: 'SAR', timezone: 'Asia/Riyadh', status: 'ACTIVE' },
    { id: 'ent-ryd',  code: 'RUH',  name: 'Riyadh Operations',      shortName: 'Riyadh Ops', type: 'SUBSIDIARY', country: 'SA', currency: 'SAR', timezone: 'Asia/Riyadh', status: 'ACTIVE' },
    { id: 'ent-jed',  code: 'JED',  name: 'Jeddah Operations',      shortName: 'Jeddah',     type: 'SUBSIDIARY', country: 'SA', currency: 'SAR', timezone: 'Asia/Riyadh', status: 'ACTIVE' },
    { id: 'ent-dmm',  code: 'DMM',  name: 'Dammam Branch',          shortName: 'Dammam',     type: 'BRANCH',     country: 'SA', currency: 'SAR', timezone: 'Asia/Riyadh', status: 'ACTIVE' },
    { id: 'ent-uk',   code: 'UK',   name: 'UK Trading Subsidiary',  shortName: 'UK',         type: 'SUBSIDIARY', country: 'GB', currency: 'GBP', timezone: 'Europe/London', status: 'ACTIVE' },
  ],
  virtualAccounts: [
    { id: 'va-hq-001',  accountNumber: 'VA-HQ-001',  iban: 'SA0380000000608010111111', name: 'Treasury Master',  entityCode: 'HQ',  currency: 'SAR', purpose: 'TREASURY' },
    { id: 'va-hq-002',  accountNumber: 'VA-HQ-002',  iban: 'SA0380000000608010111112', name: 'Collections Pool', entityCode: 'HQ',  currency: 'SAR', purpose: 'COLLECTIONS' },
    { id: 'va-ruh-001', accountNumber: 'VA-RUH-001', iban: 'SA0380000000608010222201', name: 'Riyadh Operating', entityCode: 'RUH', currency: 'SAR', purpose: 'OPERATING' },
    { id: 'va-jed-001', accountNumber: 'VA-JED-001', iban: 'SA0380000000608010333301', name: 'Jeddah Operating', entityCode: 'JED', currency: 'SAR', purpose: 'OPERATING' },
  ],
  physicalAccounts: [
    { id: 'pa-rajhi-001', accountNumber: '608010167519', iban: 'SA0380000000608010167519', name: 'Main Operating',   entityCode: 'HQ',  bankName: 'Al Rajhi Bank', bankCode: 'RJHISARI', currency: 'SAR', poolingEnabled: true,  sweepEnabled: true, sweepRole: 'HEADER' },
    { id: 'pa-rajhi-002', accountNumber: '608010167520', iban: 'SA0380000000608010167520', name: 'Riyadh Branch',    entityCode: 'RUH', bankName: 'Al Rajhi Bank', bankCode: 'RJHISARI', currency: 'SAR', poolingEnabled: true,  sweepEnabled: true, sweepRole: 'PARTICIPANT' },
    { id: 'pa-snb-001',   accountNumber: '450123456789', iban: 'SA8050000000450123456789', name: 'Payroll Account',  entityCode: 'HQ',  bankName: 'Saudi National Bank', bankCode: 'NCBKSAJE', currency: 'SAR', poolingEnabled: false, sweepEnabled: false },
  ],
  parties: [
    { id: 'party-001', code: 'P-001', name: 'SABIC',                     type: 'COMPANY', roles: ['CUSTOMER', 'VENDOR'], country: 'SA', taxId: 'VAT300011112233003', kycStatus: 'VERIFIED', riskRating: 'LOW',    status: 'ACTIVE' },
    { id: 'party-002', code: 'P-002', name: 'Saudi Aramco',              type: 'COMPANY', roles: ['CUSTOMER'],           country: 'SA', taxId: 'VAT300012345600003', kycStatus: 'VERIFIED', riskRating: 'LOW',    status: 'ACTIVE' },
    { id: 'party-010', code: 'P-010', name: 'Riyadh Logistics Co.',      type: 'COMPANY', roles: ['VENDOR'],             country: 'SA', taxId: 'VAT300034567800003', kycStatus: 'VERIFIED', riskRating: 'LOW',    status: 'ACTIVE' },
    { id: 'party-011', code: 'P-011', name: 'Najd Information Systems',  type: 'COMPANY', roles: ['VENDOR'],             country: 'SA', taxId: 'VAT300056789000003', kycStatus: 'VERIFIED', riskRating: 'MEDIUM', status: 'ACTIVE' },
    { id: 'party-040', code: 'F-001', name: 'Al Rajhi Bank',             type: 'FINANCIAL_INSTITUTION', roles: ['FINANCIAL'], country: 'SA',                          kycStatus: 'VERIFIED', riskRating: 'LOW', status: 'ACTIVE' },
  ],
  ihbParticipants: [
    { id: 'ihb-hq',  entityId: 'ent-hq',  entityCode: 'HQ',  entityName: 'Group Treasury',     entityType: 'PARENT',     virtualAccountId: 'va-hq-001',  virtualAccountNumber: 'VA-HQ-001',  currency: 'SAR', currentBalance: 25_000_000, availableBalance: 25_000_000, position: 'SURPLUS', creditLimit: 0,         interestRateLend: 3.0, interestRateBorrow: 4.0, accruedInterest: 0,         status: 'ACTIVE' },
    { id: 'ihb-ruh', entityId: 'ent-ryd', entityCode: 'RUH', entityName: 'Riyadh Operations',  entityType: 'SUBSIDIARY', virtualAccountId: 'va-ruh-001', virtualAccountNumber: 'VA-RUH-001', currency: 'SAR', currentBalance:  8_500_000, availableBalance:  8_500_000, position: 'SURPLUS', creditLimit:  5_000_000, interestRateLend: 2.5, interestRateBorrow: 4.0, accruedInterest:   17_708, status: 'ACTIVE' },
    { id: 'ihb-jed', entityId: 'ent-jed', entityCode: 'JED', entityName: 'Jeddah Operations',  entityType: 'SUBSIDIARY', virtualAccountId: 'va-jed-001', virtualAccountNumber: 'VA-JED-001', currency: 'SAR', currentBalance: -3_500_000, availableBalance:  1_500_000, position: 'DEFICIT', creditLimit:  5_000_000, interestRateLend: 2.5, interestRateBorrow: 4.5, accruedInterest:  -13_125, status: 'ACTIVE' },
  ],
};

// ----------------------------------------------------------------------------
// UK bundle
// ----------------------------------------------------------------------------

const UK_BUNDLE: DemoBundle = {
  profileCode: 'UK',
  corporateCustomer: {
    id: 'cust-001',
    name: 'Thames Trading Holdings Plc',
    code: 'TTH',
    country: 'GB',
    industry: 'Diversified Trading',
    incorporationDate: '2010-01-15',
  },
  corporateEntities: [
    { id: 'ent-hq',   code: 'HQ',   name: 'Group Treasury',          shortName: 'Treasury',    type: 'PARENT',     country: 'GB', currency: 'GBP', timezone: 'Europe/London',    status: 'ACTIVE' },
    { id: 'ent-lon',  code: 'LON',  name: 'London Operations',       shortName: 'London Ops',  type: 'SUBSIDIARY', country: 'GB', currency: 'GBP', timezone: 'Europe/London',    status: 'ACTIVE' },
    { id: 'ent-man',  code: 'MAN',  name: 'Manchester Operations',   shortName: 'Manchester',  type: 'SUBSIDIARY', country: 'GB', currency: 'GBP', timezone: 'Europe/London',    status: 'ACTIVE' },
    { id: 'ent-edi',  code: 'EDI',  name: 'Edinburgh Branch',        shortName: 'Edinburgh',   type: 'BRANCH',     country: 'GB', currency: 'GBP', timezone: 'Europe/London',    status: 'ACTIVE' },
    { id: 'ent-de',   code: 'DE',   name: 'Germany Subsidiary',      shortName: 'Germany',     type: 'SUBSIDIARY', country: 'DE', currency: 'EUR', timezone: 'Europe/Berlin',    status: 'ACTIVE' },
    { id: 'ent-us',   code: 'US',   name: 'US Trading Subsidiary',   shortName: 'US',          type: 'SUBSIDIARY', country: 'US', currency: 'USD', timezone: 'America/New_York', status: 'ACTIVE' },
  ],
  virtualAccounts: [
    { id: 'va-hq-001',  accountNumber: 'VA-HQ-001',  iban: 'GB29HBUK40051512345678', name: 'Treasury Master',      entityCode: 'HQ',  currency: 'GBP', purpose: 'TREASURY' },
    { id: 'va-hq-002',  accountNumber: 'VA-HQ-002',  iban: 'GB29HBUK40051512345679', name: 'Collections Pool',     entityCode: 'HQ',  currency: 'GBP', purpose: 'COLLECTIONS' },
    { id: 'va-lon-001', accountNumber: 'VA-LON-001', iban: 'GB29HBUK40051522345601', name: 'London Operating',     entityCode: 'LON', currency: 'GBP', purpose: 'OPERATING' },
    { id: 'va-man-001', accountNumber: 'VA-MAN-001', iban: 'GB29HBUK40051533345601', name: 'Manchester Operating', entityCode: 'MAN', currency: 'GBP', purpose: 'OPERATING' },
  ],
  physicalAccounts: [
    { id: 'pa-hsbc-001', accountNumber: '12345678', iban: 'GB29HBUK40051512345678', name: 'Main Operating',  entityCode: 'HQ',  bankName: 'HSBC UK',    bankCode: 'HBUKGB4B', currency: 'GBP', poolingEnabled: true,  sweepEnabled: true, sweepRole: 'HEADER' },
    { id: 'pa-hsbc-002', accountNumber: '12345679', iban: 'GB29HBUK40051512345679', name: 'London Branch',   entityCode: 'LON', bankName: 'HSBC UK',    bankCode: 'HBUKGB4B', currency: 'GBP', poolingEnabled: true,  sweepEnabled: true, sweepRole: 'PARTICIPANT' },
    { id: 'pa-natw-001', accountNumber: '23456789', iban: 'GB29NWBK60161331926819', name: 'Payroll Account', entityCode: 'HQ',  bankName: 'NatWest',    bankCode: 'NWBKGB2L', currency: 'GBP', poolingEnabled: false, sweepEnabled: false },
  ],
  parties: [
    { id: 'party-001', code: 'P-001', name: 'British Steel Holdings',      type: 'COMPANY', roles: ['CUSTOMER', 'VENDOR'], country: 'GB', taxId: 'GB123456789', kycStatus: 'VERIFIED', riskRating: 'LOW',    status: 'ACTIVE' },
    { id: 'party-002', code: 'P-002', name: 'Tesco Group',                 type: 'COMPANY', roles: ['CUSTOMER'],           country: 'GB', taxId: 'GB234567890', kycStatus: 'VERIFIED', riskRating: 'LOW',    status: 'ACTIVE' },
    { id: 'party-010', code: 'P-010', name: 'London Logistics Ltd',        type: 'COMPANY', roles: ['VENDOR'],             country: 'GB', taxId: 'GB345678901', kycStatus: 'VERIFIED', riskRating: 'LOW',    status: 'ACTIVE' },
    { id: 'party-011', code: 'P-011', name: 'Britannia IT Services Ltd',   type: 'COMPANY', roles: ['VENDOR'],             country: 'GB', taxId: 'GB456789012', kycStatus: 'VERIFIED', riskRating: 'MEDIUM', status: 'ACTIVE' },
    { id: 'party-040', code: 'F-001', name: 'HSBC UK',                     type: 'FINANCIAL_INSTITUTION', roles: ['FINANCIAL'], country: 'GB',                  kycStatus: 'VERIFIED', riskRating: 'LOW', status: 'ACTIVE' },
  ],
  ihbParticipants: [
    { id: 'ihb-hq',  entityId: 'ent-hq',  entityCode: 'HQ',  entityName: 'Group Treasury',         entityType: 'PARENT',     virtualAccountId: 'va-hq-001',  virtualAccountNumber: 'VA-HQ-001',  currency: 'GBP', currentBalance: 25_000_000, availableBalance: 25_000_000, position: 'SURPLUS', creditLimit: 0,         interestRateLend: 4.5, interestRateBorrow: 5.5, accruedInterest: 0,        status: 'ACTIVE' },
    { id: 'ihb-lon', entityId: 'ent-lon', entityCode: 'LON', entityName: 'London Operations',      entityType: 'SUBSIDIARY', virtualAccountId: 'va-lon-001', virtualAccountNumber: 'VA-LON-001', currency: 'GBP', currentBalance:  8_500_000, availableBalance:  8_500_000, position: 'SURPLUS', creditLimit:  5_000_000, interestRateLend: 4.0, interestRateBorrow: 5.5, accruedInterest:  17_708, status: 'ACTIVE' },
    { id: 'ihb-man', entityId: 'ent-man', entityCode: 'MAN', entityName: 'Manchester Operations',  entityType: 'SUBSIDIARY', virtualAccountId: 'va-man-001', virtualAccountNumber: 'VA-MAN-001', currency: 'GBP', currentBalance: -3_500_000, availableBalance:  1_500_000, position: 'DEFICIT', creditLimit:  5_000_000, interestRateLend: 4.0, interestRateBorrow: 6.0, accruedInterest: -13_125, status: 'ACTIVE' },
  ],
};

// ----------------------------------------------------------------------------
// Bundle registry
// ----------------------------------------------------------------------------

export const DEMO_BUNDLES: Record<string, DemoBundle> = {
  UAE: UAE_BUNDLE,
  KSA: KSA_BUNDLE,
  UK:  UK_BUNDLE,
  // EU/US/SG fall back to UAE for now — add full bundles when those demos ship.
};

/** Resolve the active demo bundle. Falls back to UAE before MarketProvider boot. */
export function getDemoBundle(profileCode?: string): DemoBundle {
  if (profileCode && DEMO_BUNDLES[profileCode]) return DEMO_BUNDLES[profileCode];
  // Best-effort: pick the bundle whose currency matches getActiveMarket().
  const active = getActiveMarket();
  const found = Object.values(DEMO_BUNDLES).find(b => b.corporateCustomer.country === activeCountryGuess(active.currency));
  return found ?? UAE_BUNDLE;
}

function activeCountryGuess(ccy: string): string {
  return ccy === 'SAR' ? 'SA' : ccy === 'GBP' ? 'GB' : ccy === 'EUR' ? 'DE' : ccy === 'USD' ? 'US' : ccy === 'SGD' ? 'SG' : 'AE';
}

/** React hook: returns the demo bundle for the active market profile. */
export function useDemoBundle(): DemoBundle {
  const { profile } = useMarket();
  return DEMO_BUNDLES[profile.code] ?? UAE_BUNDLE;
}

// ----------------------------------------------------------------------------
// Legacy top-level exports — UAE bundle for backward compatibility with any
// non-React code that imports these directly. New code should use the hook.
// ----------------------------------------------------------------------------

export const CORPORATE_CUSTOMER: CorporateCustomer = UAE_BUNDLE.corporateCustomer;
export const CORPORATE_ENTITIES: CorporateEntity[] = UAE_BUNDLE.corporateEntities;
export const VIRTUAL_ACCOUNTS:   VirtualAccountStandard[] = UAE_BUNDLE.virtualAccounts;
export const PHYSICAL_ACCOUNTS:  PhysicalBankAccount[] = UAE_BUNDLE.physicalAccounts;
export const PARTIES:            Party[] = UAE_BUNDLE.parties;
export const IHB_PARTICIPANTS:   IhbParticipant[] = UAE_BUNDLE.ihbParticipants;

// ----------------------------------------------------------------------------
// Helper functions (operate on the legacy UAE arrays for backward compatibility).
// New code should pass an explicit bundle via useDemoBundle().
// ----------------------------------------------------------------------------

export const getEntityByCode = (code: string): CorporateEntity | undefined =>
  CORPORATE_ENTITIES.find(e => e.code === code);

export const getEntityById = (id: string): CorporateEntity | undefined =>
  CORPORATE_ENTITIES.find(e => e.id === id);

export const getPartyByCode = (code: string): Party | undefined =>
  PARTIES.find(p => p.code === code);

export const getPartiesByRole = (role: Party['roles'][number]): Party[] =>
  PARTIES.filter(p => p.roles.includes(role));

export const getVirtualAccountByNumber = (accountNumber: string): VirtualAccountStandard | undefined =>
  VIRTUAL_ACCOUNTS.find(va => va.accountNumber === accountNumber);

export const getVirtualAccountsByEntity = (entityCode: string): VirtualAccountStandard[] =>
  VIRTUAL_ACCOUNTS.filter(va => va.entityCode === entityCode);

export const getIhbParticipantByCode = (code: string): IhbParticipant | undefined =>
  IHB_PARTICIPANTS.find(p => p.entityCode === code);

export const getPhysicalAccountsByEntity = (entityCode: string): PhysicalBankAccount[] =>
  PHYSICAL_ACCOUNTS.filter(pa => pa.entityCode === entityCode);

export const formatEntityDisplay = (entity: CorporateEntity): string =>
  `${entity.name} (${entity.code})`;

export const formatPartyDisplay = (party: Party): string =>
  `${party.name} (${party.code})`;

export const getEntityTypeLabel = (type: CorporateEntity['type']): string => {
  const labels: Record<CorporateEntity['type'], string> = {
    PARENT: 'Parent Company',
    SUBSIDIARY: 'Subsidiary',
    BRANCH: 'Branch',
    DIVISION: 'Division',
  };
  return labels[type];
};

export const getPartyTypeLabel = (type: Party['type']): string => {
  const labels: Record<Party['type'], string> = {
    INDIVIDUAL: 'Individual',
    COMPANY: 'Company',
    GOVERNMENT: 'Government',
    FINANCIAL_INSTITUTION: 'Financial Institution',
  };
  return labels[type];
};

export const getPositionColor = (position: IhbParticipant['position']): string => {
  const colors: Record<IhbParticipant['position'], string> = {
    SURPLUS: 'success',
    DEFICIT: 'error',
    NEUTRAL: 'neutral',
  };
  return colors[position];
};
