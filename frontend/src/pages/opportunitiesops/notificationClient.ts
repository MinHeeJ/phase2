import { readData } from "../../shared/api";
import { opportunitySessionWrite } from "./opportunitySession";

export interface SecurityNotification {
  id: number | string;
  type: string;
  title: string;
  content: string | null;
  createdAt: string;
  read: boolean;
  resolved: boolean;
  targetScreenId: string | null;
  targetId: string | number | null;
  basisMonth: string | null;
}
export interface SecurityNotificationPage {
  items: SecurityNotification[];
  total: number;
  page: number;
  size: number;
}
export interface NotificationFilters {
  unreadOnly?: boolean;
  type?: string;
  resolved?: boolean;
  search?: string;
  page: number;
  size: number;
}
export interface SecurityNotificationRead {
  id: number | string;
  read: boolean;
  unreadCount: number;
  targetScreenId: string | null;
  targetId: string | number | null;
}
export const listNotifications = (filters: NotificationFilters) => {
  const query = new URLSearchParams();
  Object.entries(filters).forEach(([key, value]) => {
    if (value !== undefined && value !== "") query.set(key, String(value));
  });
  return readData<SecurityNotificationPage>(`/api/notifications?${query}`);
};
export const getNotificationUnreadCount = () =>
  readData<{ unreadCount: number }>("/api/notifications/unread-count");
export const getNotification = (id: SecurityNotification["id"]) =>
  readData<SecurityNotification>(
    `/api/notifications/${encodeURIComponent(id)}`,
  );
export const updateNotificationRead = async (id: SecurityNotification["id"]) =>
  (
    await opportunitySessionWrite<SecurityNotificationRead>(
      `/api/notifications/${encodeURIComponent(id)}/read`,
      { method: "PATCH" },
    )
  ).data;
export const saveNotificationsReadAll = async () =>
  (
    await opportunitySessionWrite<{
      updatedCount: number;
      unreadCount: number;
    }>("/api/notifications/read-all", { method: "POST" })
  ).data;
