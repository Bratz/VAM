import axios, { AxiosInstance, AxiosError, InternalAxiosRequestConfig } from 'axios';
import {
  ApiResponse,
  PagedResponse,
  VirtualAccount,
  VirtualAccountSummary,
  VirtualAccountBalance,
  CreateVirtualAccountRequest,
  CloseVirtualAccountRequest,
  HierarchyNode,
  Transaction,
  TransactionSummary,
  InitiateTransactionRequest,
  Beneficiary,
  CorporateCustomer,
  CorporateScheme,
  DashboardStats,
  BalanceTrend,
} from '../types';

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080/api/v1';

// Create axios instance
const apiClient: AxiosInstance = axios.create({
  baseURL: API_BASE_URL,
  timeout: 30000,
  headers: {
    'Content-Type': 'application/json',
  },
});

// Request interceptor for auth token
apiClient.interceptors.request.use(
  (config: InternalAxiosRequestConfig) => {
    const token = localStorage.getItem('accessToken');
    if (token) {
      config.headers.Authorization = `Bearer ${token}`;
    }
    return config;
  },
  (error) => Promise.reject(error)
);

// Response interceptor for error handling
apiClient.interceptors.response.use(
  (response) => response,
  (error: AxiosError) => {
    if (error.response?.status === 401) {
      localStorage.removeItem('accessToken');
      window.location.href = '/login';
    }
    return Promise.reject(error);
  }
);

// ==================== Virtual Account API ====================

export const virtualAccountApi = {
  create: async (data: CreateVirtualAccountRequest): Promise<ApiResponse<VirtualAccount>> => {
    const response = await apiClient.post('/virtual-accounts', data);
    return response.data;
  },

  getById: async (id: string): Promise<ApiResponse<VirtualAccount>> => {
    const response = await apiClient.get(`/virtual-accounts/${id}`);
    return response.data;
  },

  getByIban: async (iban: string): Promise<ApiResponse<VirtualAccount>> => {
    const response = await apiClient.get(`/virtual-accounts/iban/${iban}`);
    return response.data;
  },

  listByScheme: async (
    schemeCode: string,
    page = 0,
    size = 20
  ): Promise<PagedResponse<VirtualAccountSummary>> => {
    const response = await apiClient.get(`/virtual-accounts/scheme/${schemeCode}`, {
      params: { page, size },
    });
    return response.data;
  },

  listByCustomer: async (
    customerId: string,
    page = 0,
    size = 20
  ): Promise<PagedResponse<VirtualAccountSummary>> => {
    const response = await apiClient.get(`/virtual-accounts/customer/${customerId}`, {
      params: { page, size },
    });
    return response.data;
  },

  search: async (
    customerId: string,
    query: string,
    page = 0,
    size = 20
  ): Promise<PagedResponse<VirtualAccountSummary>> => {
    const response = await apiClient.get('/virtual-accounts/search', {
      params: { customerId, q: query, page, size },
    });
    return response.data;
  },

  getBalance: async (iban: string): Promise<ApiResponse<VirtualAccountBalance>> => {
    const response = await apiClient.get(`/virtual-accounts/${iban}/balance`);
    return response.data;
  },

  close: async (data: CloseVirtualAccountRequest): Promise<ApiResponse<VirtualAccount>> => {
    const response = await apiClient.post('/virtual-accounts/close', data);
    return response.data;
  },

  getHierarchy: async (schemeId: string): Promise<ApiResponse<HierarchyNode[]>> => {
    const response = await apiClient.get(`/virtual-accounts/hierarchy/${schemeId}`);
    return response.data;
  },
};

// ==================== Transaction API ====================

export const transactionApi = {
  initiate: async (data: InitiateTransactionRequest): Promise<ApiResponse<Transaction>> => {
    const response = await apiClient.post('/transactions', data);
    return response.data;
  },

  getById: async (id: string): Promise<ApiResponse<Transaction>> => {
    const response = await apiClient.get(`/transactions/${id}`);
    return response.data;
  },

  getByReference: async (reference: string): Promise<ApiResponse<Transaction>> => {
    const response = await apiClient.get(`/transactions/reference/${reference}`);
    return response.data;
  },

  listByAccount: async (
    accountId: string,
    page = 0,
    size = 20
  ): Promise<PagedResponse<TransactionSummary>> => {
    const response = await apiClient.get(`/transactions/account/${accountId}`, {
      params: { page, size },
    });
    return response.data;
  },

  listByCustomer: async (
    customerId: string,
    page = 0,
    size = 20
  ): Promise<PagedResponse<TransactionSummary>> => {
    const response = await apiClient.get(`/transactions/customer/${customerId}`, {
      params: { page, size },
    });
    return response.data;
  },

  search: async (
    params: {
      startDate?: string;
      endDate?: string;
      status?: string;
      minAmount?: number;
      maxAmount?: number;
    },
    page = 0,
    size = 20
  ): Promise<PagedResponse<TransactionSummary>> => {
    const response = await apiClient.get('/transactions/search', {
      params: { ...params, page, size },
    });
    return response.data;
  },
};

// ==================== Beneficiary API ====================

export const beneficiaryApi = {
  create: async (data: Partial<Beneficiary>): Promise<ApiResponse<Beneficiary>> => {
    const response = await apiClient.post('/beneficiaries', data);
    return response.data;
  },

  update: async (id: string, data: Partial<Beneficiary>): Promise<ApiResponse<Beneficiary>> => {
    const response = await apiClient.put(`/beneficiaries/${id}`, data);
    return response.data;
  },

  delete: async (id: string): Promise<ApiResponse<void>> => {
    const response = await apiClient.delete(`/beneficiaries/${id}`);
    return response.data;
  },

  getById: async (id: string): Promise<ApiResponse<Beneficiary>> => {
    const response = await apiClient.get(`/beneficiaries/${id}`);
    return response.data;
  },

  listByCustomer: async (
    customerId: string,
    page = 0,
    size = 20
  ): Promise<PagedResponse<Beneficiary>> => {
    const response = await apiClient.get(`/beneficiaries/customer/${customerId}`, {
      params: { page, size },
    });
    return response.data;
  },

  search: async (
    customerId: string,
    query: string,
    page = 0,
    size = 20
  ): Promise<PagedResponse<Beneficiary>> => {
    const response = await apiClient.get('/beneficiaries/search', {
      params: { customerId, q: query, page, size },
    });
    return response.data;
  },
};

// ==================== Corporate API ====================

export const corporateApi = {
  getCustomer: async (id: string): Promise<ApiResponse<CorporateCustomer>> => {
    const response = await apiClient.get(`/corporates/${id}`);
    return response.data;
  },

  listSchemes: async (customerId: string): Promise<ApiResponse<CorporateScheme[]>> => {
    const response = await apiClient.get(`/corporates/${customerId}/schemes`);
    return response.data;
  },

  getScheme: async (schemeCode: string): Promise<ApiResponse<CorporateScheme>> => {
    const response = await apiClient.get(`/schemes/${schemeCode}`);
    return response.data;
  },
};

// ==================== Dashboard API ====================

export const dashboardApi = {
  getStats: async (customerId: string): Promise<ApiResponse<DashboardStats>> => {
    const response = await apiClient.get(`/dashboard/stats/${customerId}`);
    return response.data;
  },

  getBalanceTrend: async (
    customerId: string,
    days = 30
  ): Promise<ApiResponse<BalanceTrend[]>> => {
    const response = await apiClient.get(`/dashboard/balance-trend/${customerId}`, {
      params: { days },
    });
    return response.data;
  },

  getRecentTransactions: async (
    customerId: string,
    limit = 10
  ): Promise<ApiResponse<TransactionSummary[]>> => {
    const response = await apiClient.get(`/dashboard/recent-transactions/${customerId}`, {
      params: { limit },
    });
    return response.data;
  },
};

// ==================== Auth API ====================

export const authApi = {
  login: async (username: string, password: string): Promise<ApiResponse<{ token: string }>> => {
    const response = await apiClient.post('/auth/login', { username, password });
    return response.data;
  },

  logout: async (): Promise<void> => {
    await apiClient.post('/auth/logout');
    localStorage.removeItem('accessToken');
  },

  refreshToken: async (): Promise<ApiResponse<{ token: string }>> => {
    const response = await apiClient.post('/auth/refresh');
    return response.data;
  },

  getCurrentUser: async (): Promise<ApiResponse<any>> => {
    const response = await apiClient.get('/auth/me');
    return response.data;
  },
};

export default apiClient;
