/**
 * Intercompany Service - Stub file for COBO and VIBAN integration
 *
 * This is a stub file to fix compilation errors.
 * TODO: Integrate with actual API endpoints from api.ts
 */

import { intercompanyApi, CoboRequest, CoboResult, ApiResponse } from './api';

// Re-export types from api.ts
export type { CoboRequest, CoboResult };

// Stub type for HierarchyEntity (used by EnhancedCoboPicker)
export interface HierarchyEntity {
  id: string;
  entityCode: string;
  entityName: string;
  entityType: 'HOLDING' | 'SUBSIDIARY' | 'BRANCH' | 'TREASURY_CENTER' | 'SPV';
  parentEntityId?: string;
  countryCode?: string;
  functionalCurrency: string;
  ihbEnabled?: boolean;
  isTreasuryCenter?: boolean;
  status: 'ACTIVE' | 'INACTIVE';
}

// Stub type for VIBAN generation result
export interface VibanGenerationResult {
  success: boolean;
  viban?: string;
  vaId?: string;
  vaNumber?: string;
  qrCodeUrl?: string;
  message?: string;
}

// Re-export intercompanyApi from api.ts
export { intercompanyApi };

// Stub for vibanIntegrationApi
export const vibanIntegrationApi = {
  generateViban: async (entityId: string, purpose: string): Promise<ApiResponse<VibanGenerationResult>> => {
    console.warn('[vibanIntegrationApi] Using stub - not implemented');
    return {
      success: false,
      message: 'VIBAN integration not yet implemented',
      data: { success: false, message: 'Not implemented' }
    };
  },

  getVibans: async (entityId: string): Promise<ApiResponse<any[]>> => {
    console.warn('[vibanIntegrationApi] Using stub - not implemented');
    return { success: true, data: [] };
  },
};

// Stub for ihbIntegrationApi
export const ihbIntegrationApi = {
  getEntityPosition: async (entityId: string): Promise<ApiResponse<any>> => {
    console.warn('[ihbIntegrationApi] Using stub - not implemented');
    return {
      success: true,
      data: {
        netPosition: 0,
        depositBalance: 0,
        loanBalance: 0,
        availableCredit: 0,
      }
    };
  },

  submitCoboDeposit: async (request: CoboRequest): Promise<ApiResponse<CoboResult>> => {
    // Delegate to actual intercompanyApi
    return intercompanyApi.cobo.setup(request);
  },
};
