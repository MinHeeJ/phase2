import { readData } from "../../shared/api";
import { opportunityRequest } from "./opportunityRequest";

export interface NotificationItem {
  id: number;
  type: string;
  title: string;
  message: string | null;
  createdAt: string;
  read: boolean;
  readAt: string | null;
  screenId: string | null;
  linkParams: Record<string, unknown> | null;
  resolved: boolean;
}
export interface NotificationPage {
  items: NotificationItem[];
  total: number;
  page: number;
  size: number;
}
export const listNotifications = (
  page = 0,
  unreadOnly = false,
  signal?: AbortSignal,
) =>
  readData<NotificationPage>(
    `/api/notifications?page=${page}&size=20&unreadOnly=${unreadOnly}`,
    signal,
  );
export const getNotification = (id: number) =>
  readData<NotificationItem>(`/api/notifications/${id}`);
export const getNotificationUnreadCount = (signal?: AbortSignal) =>
  readData<{ count: number }>("/api/notifications/unread-count", signal);
export const markNotificationRead = (id: number) =>
  opportunityRequest(`/api/notifications/${id}/read`, { method: "PATCH" });
export const markAllNotificationsRead = () =>
  opportunityRequest("/api/notifications/read-all", { method: "POST" });
