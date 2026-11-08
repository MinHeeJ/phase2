import { api, readData, ApiFailure } from "../../shared/api";
import type { PageResult } from "../../shared/types";

export interface AdminUser {
  userId: number;
  loginId: string;
  name: string;
  email: string | null;
  phone: string | null;
  partId: number | null;
  developerId: number | null;
  roleIds: number[];
  partIds: number[];
  projectIds: number[];
  status: string;
  resignationDate: string | null;
  lastLoginAt: string | null;
}
export interface AdminRole {
  roleId: number;
  name: string;
  description: string | null;
  useYn: string;
  dataScope: string;
  admin: boolean;
  builtin: boolean;
  sensitiveInquiry: boolean;
  partStandardChange: boolean;
}
export interface AdminMenu {
  menuId: number;
  parentId: number | null;
  name: string;
  screenId: string | null;
  icon: string | null;
  sortOrder: number;
  useYn: string;
  mobileVisible: boolean;
}
export const actionKeys = [
  "inq",
  "reg",
  "mdfcn",
  "del",
  "upld",
  "dwnld",
  "cnfrm",
] as const;
export type ActionKey = (typeof actionKeys)[number];
export type MenuPermission = Pick<
  AdminMenu,
  "menuId" | "name" | "screenId" | "parentId"
> &
  Record<ActionKey, "Y" | "N">;
export interface AccessHistory {
  accessHistoryId: number;
  userId: number | null;
  name: string | null;
  type: string;
  result: string | null;
  screenId: string | null;
  target: string | null;
  ipAddress: string | null;
  createdAt: string;
}
export function query(values: Record<string, string | number | undefined>) {
  const result = new URLSearchParams();
  Object.entries(values).forEach(([key, value]) => {
    if (value !== undefined && value !== "") result.set(key, String(value));
  });
  return result.toString();
}
export const adminUsers = (filters: Record<string, string | number> = {}) =>
  readData<PageResult<AdminUser>>(`/api/admin/users?${query(filters)}`);
export const adminUser = (id: number) =>
  readData<AdminUser>(`/api/admin/users/${id}`);
export const adminRoles = () => readData<AdminRole[]>("/api/admin/roles");
export const adminRole = (id: number) =>
  readData<AdminRole>(`/api/admin/roles/${id}`);
export const rolePermissions = (id: number) =>
  readData<MenuPermission[]>(`/api/admin/roles/${id}/permissions`);
export const adminMenus = () => readData<AdminMenu[]>("/api/admin/menus");
export const accessHistory = (filters: Record<string, string | number>) =>
  readData<PageResult<AccessHistory>>(
    `/api/admin/access-history?${query(filters)}`,
  );
export async function command<T>(
  path: string,
  method: string,
  body?: unknown,
): Promise<T> {
  const csrf = await readData<{ csrfToken: string }>("/api/auth/csrf");
  const result = (
    await api<T>(path, {
      method,
      credentials: "same-origin",
      headers: { "X-CSRF-TOKEN": csrf.csrfToken },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    })
  ).data as T;
  if (path.startsWith("/api/admin/")) {
    window.dispatchEvent(new Event("management-changed"));
    if ("BroadcastChannel" in window) {
      const channel = new BroadcastChannel("system-authorization");
      channel.postMessage("changed");
      channel.close();
    }
  }
  return result;
}
export async function downloadAdmin(path: string) {
  if (!path.startsWith("/api/") || path.startsWith("//"))
    throw new Error("상대 API 경로가 필요합니다.");
  const response = await fetch(path, { credentials: "same-origin" });
  if (!response.ok) {
    if (response.status === 401)
      window.dispatchEvent(new Event("session-expired"));
    if (response.status === 403)
      window.dispatchEvent(new Event("permissions-changed"));
    const body = await response.json().catch(() => ({}));
    throw new ApiFailure(
      body.message ?? "파일을 내려받지 못했습니다.",
      response.status,
      body.requestId ?? null,
      body.field ?? null,
      body.code ?? null,
    );
  }
  const url = URL.createObjectURL(await response.blob());
  try {
    const link = document.createElement("a");
    link.href = url;
    link.download = path.split("?")[0].endsWith("/access-history/export")
      ? "access-history.xlsx"
      : "users.xlsx";
    link.click();
  } finally {
    URL.revokeObjectURL(url);
  }
}
