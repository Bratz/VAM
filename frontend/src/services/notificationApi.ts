// The header bell. Backend: NotificationController at /api/v1/notifications.

import { apiClient, ApiResponse } from './api';

export interface AppNotification {
  id: string;
  type: string;
  /** INFO, WARNING, ERROR, SUCCESS or URGENT. */
  category: string;
  title: string;
  message: string;
  referenceNumber?: string;
  /** The app page to open, e.g. "fx-deals". */
  actionUrl?: string;
  actionLabel?: string;
  createdAt: string;
  read: boolean;
}

export interface Inbox {
  unread: number;
  items: AppNotification[];
}

export const notificationApi = {
  /** A corporate's latest notifications; every corporate's when none is given. */
  inbox: (corporateId?: string | null) =>
    apiClient.get<ApiResponse<Inbox>>('/notifications', { params: corporateId ? { corporateId } : {} }).then(r => r.data.data),

  read: (id: string) => apiClient.post(`/notifications/${id}/read`),

  readAll: (corporateId?: string | null) =>
    apiClient.post('/notifications/read-all', null, { params: corporateId ? { corporateId } : {} }),
};
