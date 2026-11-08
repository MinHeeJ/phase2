import { api, readData } from "../../shared/api";

export interface NotificationItem {
  notificationId: number;
  title: string;
  message: string | null;
  createdAt: string;
  read: boolean;
  targetScreenId: string | null;
  target: Record<string, unknown> | null;
}
export interface NotificationPage {
  items: NotificationItem[];
  total: number;
  page: number;
  size: number;
}
export const NOTIFICATIONS_CHANGED = "notifications-changed";
export async function opportunityRequest<T = void>(
  path: string,
  init: RequestInit = {},
) {
  const write = !["GET", "HEAD"].includes((init.method ?? "GET").toUpperCase());
  const headers = new Headers(init.headers);
  if (write) {
    const csrf = await readData<{ headerName: string; csrfToken: string }>(
      "/api/auth/csrf",
    );
    headers.set("X-CSRF-TOKEN", csrf.csrfToken);
  }
  return api<T>(path, {
    ...init,
    credentials: "same-origin",
    headers: Object.fromEntries(headers.entries()),
  });
}
export function listNotifications(
  page: number,
  unreadOnly: boolean,
  signal?: AbortSignal,
) {
  return readData<NotificationPage>(
    `/api/notifications?page=${page}&size=20&unreadOnly=${unreadOnly}`,
    signal,
  );
}
export function notificationCount(signal?: AbortSignal) {
  return readData<{ count: number }>("/api/notifications/unread-count", signal);
}
export async function readNotification(id: number) {
  const result = await opportunityRequest(`/api/notifications/${id}/read`, {
    method: "PATCH",
  });
  window.dispatchEvent(new Event(NOTIFICATIONS_CHANGED));
  return result;
}
export async function readAllNotifications() {
  const result = await opportunityRequest("/api/notifications/read-all", {
    method: "POST",
  });
  window.dispatchEvent(new Event(NOTIFICATIONS_CHANGED));
  return result;
}
