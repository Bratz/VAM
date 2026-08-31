import React from 'react';

/**
 * Connector Icons - Swiss Minimalism Style
 * 
 * Geometric, monochromatic icons for integration connectors.
 * All icons use a consistent 24x24 viewBox with 1.5px stroke.
 */

interface IconProps {
  className?: string;
  size?: number;
}

const defaultProps: IconProps = {
  className: '',
  size: 24,
};

// ============================================================================
// ERP ICONS
// ============================================================================

export const SapIcon: React.FC<IconProps> = ({ className = '', size = 24 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" className={className}>
    <rect x="3" y="3" width="18" height="18" rx="2" stroke="currentColor" strokeWidth="1.5"/>
    <path d="M7 12h2l1-3 1 6 1-3h2" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round"/>
    <line x1="7" y1="16" x2="17" y2="16" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round"/>
  </svg>
);

export const OracleIcon: React.FC<IconProps> = ({ className = '', size = 24 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" className={className}>
    <circle cx="12" cy="12" r="9" stroke="currentColor" strokeWidth="1.5"/>
    <circle cx="12" cy="12" r="5" stroke="currentColor" strokeWidth="1.5"/>
    <circle cx="12" cy="12" r="1.5" fill="currentColor"/>
  </svg>
);

export const MicrosoftIcon: React.FC<IconProps> = ({ className = '', size = 24 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" className={className}>
    <rect x="3" y="3" width="8" height="8" stroke="currentColor" strokeWidth="1.5"/>
    <rect x="13" y="3" width="8" height="8" stroke="currentColor" strokeWidth="1.5"/>
    <rect x="3" y="13" width="8" height="8" stroke="currentColor" strokeWidth="1.5"/>
    <rect x="13" y="13" width="8" height="8" stroke="currentColor" strokeWidth="1.5"/>
  </svg>
);

export const NetsuiteIcon: React.FC<IconProps> = ({ className = '', size = 24 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" className={className}>
    <path d="M12 3L21 8v8l-9 5-9-5V8l9-5z" stroke="currentColor" strokeWidth="1.5" strokeLinejoin="round"/>
    <path d="M12 12v9M12 12L3 8M12 12l9-4" stroke="currentColor" strokeWidth="1.5"/>
  </svg>
);

export const WorkdayIcon: React.FC<IconProps> = ({ className = '', size = 24 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" className={className}>
    <circle cx="12" cy="8" r="4" stroke="currentColor" strokeWidth="1.5"/>
    <path d="M4 20c0-4.4 3.6-8 8-8s8 3.6 8 8" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round"/>
  </svg>
);

// ============================================================================
// TREASURY ICONS
// ============================================================================

export const KyribaIcon: React.FC<IconProps> = ({ className = '', size = 24 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" className={className}>
    <rect x="3" y="6" width="18" height="12" rx="2" stroke="currentColor" strokeWidth="1.5"/>
    <path d="M3 10h18" stroke="currentColor" strokeWidth="1.5"/>
    <path d="M7 14h4M15 14h2" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round"/>
  </svg>
);

export const FisIcon: React.FC<IconProps> = ({ className = '', size = 24 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" className={className}>
    <path d="M4 4h16v16H4z" stroke="currentColor" strokeWidth="1.5"/>
    <path d="M4 12h16M12 4v16" stroke="currentColor" strokeWidth="1.5"/>
    <circle cx="8" cy="8" r="1.5" fill="currentColor"/>
    <circle cx="16" cy="16" r="1.5" fill="currentColor"/>
  </svg>
);

export const IonIcon: React.FC<IconProps> = ({ className = '', size = 24 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" className={className}>
    <circle cx="12" cy="12" r="3" stroke="currentColor" strokeWidth="1.5"/>
    <circle cx="12" cy="12" r="8" stroke="currentColor" strokeWidth="1.5" strokeDasharray="4 2"/>
    <path d="M12 3v2M12 19v2M3 12h2M19 12h2" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round"/>
  </svg>
);

// ============================================================================
// OPEN BANKING ICONS
// ============================================================================

export const Psd2Icon: React.FC<IconProps> = ({ className = '', size = 24 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" className={className}>
    <circle cx="12" cy="12" r="9" stroke="currentColor" strokeWidth="1.5"/>
    <path d="M8 12h8M12 8v8" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round"/>
    <circle cx="12" cy="12" r="3" stroke="currentColor" strokeWidth="1.5"/>
  </svg>
);

export const OpenBankingUkIcon: React.FC<IconProps> = ({ className = '', size = 24 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" className={className}>
    <rect x="4" y="4" width="16" height="16" rx="2" stroke="currentColor" strokeWidth="1.5"/>
    <path d="M8 12h8" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round"/>
    <path d="M12 8v8" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round"/>
    <circle cx="8" cy="8" r="1" fill="currentColor"/>
    <circle cx="16" cy="8" r="1" fill="currentColor"/>
    <circle cx="8" cy="16" r="1" fill="currentColor"/>
    <circle cx="16" cy="16" r="1" fill="currentColor"/>
  </svg>
);

export const StetIcon: React.FC<IconProps> = ({ className = '', size = 24 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" className={className}>
    <path d="M12 3l9 6v6l-9 6-9-6V9l9-6z" stroke="currentColor" strokeWidth="1.5" strokeLinejoin="round"/>
    <path d="M12 9v6M9 12h6" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round"/>
  </svg>
);

export const PolishApiIcon: React.FC<IconProps> = ({ className = '', size = 24 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" className={className}>
    <rect x="4" y="4" width="16" height="8" stroke="currentColor" strokeWidth="1.5"/>
    <rect x="4" y="12" width="16" height="8" fill="currentColor" fillOpacity="0.2" stroke="currentColor" strokeWidth="1.5"/>
  </svg>
);

export const PlaidIcon: React.FC<IconProps> = ({ className = '', size = 24 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" className={className}>
    <rect x="4" y="4" width="6" height="6" stroke="currentColor" strokeWidth="1.5"/>
    <rect x="14" y="4" width="6" height="6" stroke="currentColor" strokeWidth="1.5"/>
    <rect x="4" y="14" width="6" height="6" stroke="currentColor" strokeWidth="1.5"/>
    <rect x="14" y="14" width="6" height="6" fill="currentColor"/>
  </svg>
);

export const TinkIcon: React.FC<IconProps> = ({ className = '', size = 24 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" className={className}>
    <circle cx="12" cy="12" r="9" stroke="currentColor" strokeWidth="1.5"/>
    <path d="M12 7v5l3 3" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round"/>
  </svg>
);

export const TruelayerIcon: React.FC<IconProps> = ({ className = '', size = 24 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" className={className}>
    <path d="M4 8h16M4 12h16M4 16h16" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round"/>
    <circle cx="8" cy="8" r="1.5" fill="currentColor"/>
    <circle cx="12" cy="12" r="1.5" fill="currentColor"/>
    <circle cx="16" cy="16" r="1.5" fill="currentColor"/>
  </svg>
);

export const YapilyIcon: React.FC<IconProps> = ({ className = '', size = 24 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" className={className}>
    <path d="M12 3v7M12 21v-7M3 12h7M21 12h-7" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round"/>
    <circle cx="12" cy="12" r="3" stroke="currentColor" strokeWidth="1.5"/>
  </svg>
);

// ============================================================================
// PAYMENT ICONS
// ============================================================================

export const SwiftIcon: React.FC<IconProps> = ({ className = '', size = 24 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" className={className}>
    <path d="M4 12h16" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round"/>
    <path d="M8 8l-4 4 4 4" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round"/>
    <path d="M16 8l4 4-4 4" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round"/>
  </svg>
);

export const SwiftGpiIcon: React.FC<IconProps> = ({ className = '', size = 24 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" className={className}>
    <circle cx="12" cy="12" r="9" stroke="currentColor" strokeWidth="1.5"/>
    <path d="M8 12h8M12 8l4 4-4 4" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round"/>
  </svg>
);

export const SepaIcon: React.FC<IconProps> = ({ className = '', size = 24 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" className={className}>
    <ellipse cx="12" cy="12" rx="9" ry="6" stroke="currentColor" strokeWidth="1.5"/>
    <path d="M12 6v12" stroke="currentColor" strokeWidth="1.5"/>
    <path d="M6 9h12M6 15h12" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round"/>
  </svg>
);

export const FasterPaymentsIcon: React.FC<IconProps> = ({ className = '', size = 24 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" className={className}>
    <path d="M13 3l-10 9h7l-1 9 10-9h-7l1-9z" stroke="currentColor" strokeWidth="1.5" strokeLinejoin="round"/>
  </svg>
);

export const Iso20022Icon: React.FC<IconProps> = ({ className = '', size = 24 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" className={className}>
    <rect x="3" y="5" width="18" height="14" rx="2" stroke="currentColor" strokeWidth="1.5"/>
    <path d="M7 9h10M7 12h6M7 15h8" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round"/>
  </svg>
);

// ============================================================================
// BANKING ICONS
// ============================================================================

export const HostToHostIcon: React.FC<IconProps> = ({ className = '', size = 24 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" className={className}>
    <rect x="3" y="4" width="7" height="6" rx="1" stroke="currentColor" strokeWidth="1.5"/>
    <rect x="14" y="4" width="7" height="6" rx="1" stroke="currentColor" strokeWidth="1.5"/>
    <rect x="3" y="14" width="7" height="6" rx="1" stroke="currentColor" strokeWidth="1.5"/>
    <rect x="14" y="14" width="7" height="6" rx="1" stroke="currentColor" strokeWidth="1.5"/>
    <path d="M10 7h4M10 17h4M6.5 10v4M17.5 10v4" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round"/>
  </svg>
);

export const EbicsIcon: React.FC<IconProps> = ({ className = '', size = 24 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" className={className}>
    <path d="M12 3v18M3 12h18" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round"/>
    <rect x="6" y="6" width="12" height="12" rx="1" stroke="currentColor" strokeWidth="1.5"/>
  </svg>
);

export const Bai2Icon: React.FC<IconProps> = ({ className = '', size = 24 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" className={className}>
    <path d="M4 4h16v16H4z" stroke="currentColor" strokeWidth="1.5"/>
    <path d="M4 9h16M9 4v16" stroke="currentColor" strokeWidth="1.5"/>
    <circle cx="6.5" cy="6.5" r="1" fill="currentColor"/>
  </svg>
);

export const Mt940Icon: React.FC<IconProps> = ({ className = '', size = 24 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" className={className}>
    <path d="M5 4h14a1 1 0 011 1v14a1 1 0 01-1 1H5a1 1 0 01-1-1V5a1 1 0 011-1z" stroke="currentColor" strokeWidth="1.5"/>
    <path d="M8 8h8M8 11h8M8 14h5" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round"/>
  </svg>
);

export const BankIcon: React.FC<IconProps> = ({ className = '', size = 24 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" className={className}>
    <path d="M3 21h18M4 21V10M20 21V10M12 3l9 7H3l9-7z" stroke="currentColor" strokeWidth="1.5" strokeLinejoin="round"/>
    <path d="M8 14v4M12 14v4M16 14v4" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round"/>
  </svg>
);

// ============================================================================
// GENERIC ICONS
// ============================================================================

export const SftpIcon: React.FC<IconProps> = ({ className = '', size = 24 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" className={className}>
    <path d="M14 3v4a1 1 0 001 1h4" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round"/>
    <path d="M5 8V5a2 2 0 012-2h7l5 5v11a2 2 0 01-2 2H7a2 2 0 01-2-2v-3" stroke="currentColor" strokeWidth="1.5"/>
    <path d="M3 15h7M8 12l3 3-3 3" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round"/>
  </svg>
);

export const RestApiIcon: React.FC<IconProps> = ({ className = '', size = 24 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" className={className}>
    <path d="M7 8l-4 4 4 4M17 8l4 4-4 4" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round"/>
    <path d="M14 4l-4 16" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round"/>
  </svg>
);

export const WebhookIcon: React.FC<IconProps> = ({ className = '', size = 24 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" className={className}>
    <circle cx="6" cy="12" r="3" stroke="currentColor" strokeWidth="1.5"/>
    <circle cx="18" cy="6" r="3" stroke="currentColor" strokeWidth="1.5"/>
    <circle cx="18" cy="18" r="3" stroke="currentColor" strokeWidth="1.5"/>
    <path d="M9 12h6M15 9l3-3M15 15l3 3" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round"/>
  </svg>
);

export const GraphqlIcon: React.FC<IconProps> = ({ className = '', size = 24 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" className={className}>
    <path d="M12 3l8 4.5v9L12 21l-8-4.5v-9L12 3z" stroke="currentColor" strokeWidth="1.5" strokeLinejoin="round"/>
    <circle cx="12" cy="3" r="1.5" fill="currentColor"/>
    <circle cx="20" cy="7.5" r="1.5" fill="currentColor"/>
    <circle cx="20" cy="16.5" r="1.5" fill="currentColor"/>
    <circle cx="12" cy="21" r="1.5" fill="currentColor"/>
    <circle cx="4" cy="16.5" r="1.5" fill="currentColor"/>
    <circle cx="4" cy="7.5" r="1.5" fill="currentColor"/>
  </svg>
);

// ============================================================================
// STATUS & UI ICONS
// ============================================================================

export const ConnectedIcon: React.FC<IconProps> = ({ className = '', size = 24 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" className={className}>
    <circle cx="12" cy="12" r="9" stroke="currentColor" strokeWidth="1.5"/>
    <path d="M8 12l3 3 5-6" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round"/>
  </svg>
);

export const DisconnectedIcon: React.FC<IconProps> = ({ className = '', size = 24 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" className={className}>
    <circle cx="12" cy="12" r="9" stroke="currentColor" strokeWidth="1.5"/>
    <path d="M9 9l6 6M15 9l-6 6" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round"/>
  </svg>
);

export const SyncingIcon: React.FC<IconProps> = ({ className = '', size = 24 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" className={className}>
    <path d="M4 12a8 8 0 018-8" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round"/>
    <path d="M20 12a8 8 0 01-8 8" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round"/>
    <path d="M12 4l-2-2M12 4l2-2M12 20l-2 2M12 20l2 2" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round"/>
  </svg>
);

export const ErrorIcon: React.FC<IconProps> = ({ className = '', size = 24 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" className={className}>
    <circle cx="12" cy="12" r="9" stroke="currentColor" strokeWidth="1.5"/>
    <path d="M12 8v5M12 16v.01" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round"/>
  </svg>
);

// ============================================================================
// ICON MAP - For dynamic icon selection
// ============================================================================

export const ConnectorIconMap: Record<string, React.FC<IconProps>> = {
  // ERP
  'sap': SapIcon,
  'SAP_S4HANA': SapIcon,
  'oracle': OracleIcon,
  'ORACLE_FUSION': OracleIcon,
  'microsoft': MicrosoftIcon,
  'MS_DYNAMICS_365': MicrosoftIcon,
  'netsuite': NetsuiteIcon,
  'NETSUITE': NetsuiteIcon,
  'workday': WorkdayIcon,
  'WORKDAY': WorkdayIcon,
  
  // Treasury
  'kyriba': KyribaIcon,
  'KYRIBA': KyribaIcon,
  'fis': FisIcon,
  'FIS_QUANTUM': FisIcon,
  'ion': IonIcon,
  'ION_TREASURY': IonIcon,
  
  // Open Banking
  'eu': Psd2Icon,
  'psd2': Psd2Icon,
  'PSD2_BERLIN_GROUP': Psd2Icon,
  'uk': OpenBankingUkIcon,
  'OPEN_BANKING_UK': OpenBankingUkIcon,
  'france': StetIcon,
  'PSD2_STET': StetIcon,
  'poland': PolishApiIcon,
  'POLISH_API': PolishApiIcon,
  'plaid': PlaidIcon,
  'PLAID': PlaidIcon,
  'tink': TinkIcon,
  'TINK': TinkIcon,
  'truelayer': TruelayerIcon,
  'TRUELAYER': TruelayerIcon,
  'yapily': YapilyIcon,
  'YAPILY': YapilyIcon,
  
  // Payments
  'swift': SwiftIcon,
  'SWIFT_ALLIANCE_LITE2': SwiftIcon,
  'SWIFT_GPI': SwiftGpiIcon,
  'sepa': SepaIcon,
  'SEPA': SepaIcon,
  'fps': FasterPaymentsIcon,
  'FASTER_PAYMENTS_UK': FasterPaymentsIcon,
  'iso': Iso20022Icon,
  'ISO20022': Iso20022Icon,
  
  // Banking
  'bank': BankIcon,
  'HOST_TO_HOST': HostToHostIcon,
  'ebics': EbicsIcon,
  'EBICS': EbicsIcon,
  'bai': Bai2Icon,
  'BAI2': Bai2Icon,
  'MT940_MT942': Mt940Icon,
  
  // Generic
  'sftp': SftpIcon,
  'SFTP': SftpIcon,
  'api': RestApiIcon,
  'REST_API': RestApiIcon,
  'webhook': WebhookIcon,
  'WEBHOOKS': WebhookIcon,
  'graphql': GraphqlIcon,
  'GRAPHQL': GraphqlIcon,
};

/**
 * Get connector icon component by icon type or connector code
 */
export const getConnectorIcon = (iconType: string | undefined | null): React.FC<IconProps> => {
  if (!iconType) return BankIcon;
  return ConnectorIconMap[iconType] || BankIcon;
};

export default ConnectorIconMap;
