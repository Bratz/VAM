// ============================================================================
// Connector icon lookup — the non-component half of ConnectorIcons.tsx.
//
// Imports FROM ConnectorIcons.tsx (never the reverse) so that file exports
// components only and Fast Refresh keeps working. Named *IconMap rather than
// `connectorIcons.ts` deliberately: a case-only twin of ConnectorIcons.tsx
// would be ambiguous to resolve on a case-insensitive filesystem.
// ============================================================================
import React from 'react';
import {
  IconProps,
  SapIcon, OracleIcon, MicrosoftIcon, NetsuiteIcon, WorkdayIcon,
  KyribaIcon, FisIcon, IonIcon,
  Psd2Icon, OpenBankingUkIcon, StetIcon, PolishApiIcon, PlaidIcon, TinkIcon,
  TruelayerIcon, YapilyIcon,
  SwiftIcon, SwiftGpiIcon, SepaIcon, FasterPaymentsIcon, Iso20022Icon,
  BankIcon, HostToHostIcon, EbicsIcon, Bai2Icon, Mt940Icon,
  SftpIcon, RestApiIcon, WebhookIcon, GraphqlIcon,
} from './ConnectorIcons';

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
