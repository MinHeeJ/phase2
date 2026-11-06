import { api, readData } from "../../shared/api";
import { opportunitySessionWrite } from "../opportunitiesops/opportunitySession";

export type Row = Record<string, unknown> & { id: number; name?: string };
export interface Page<T> {
  items: T[];
  total: number;
  page: number;
  size: number;
}
export interface SecurityUser extends Row {
  loginId: string;
  name: string;
  email: string;
  phone: string | null;
  basePartId: number | null;
  developerId: number | null;
  roleIds: number[];
  partIds: number[];
  projectIds: number[];
  status: string;
  resignationDate: string | null;
  lastLoginAt: string | null;
}
export interface SecurityRole extends Row {
  name: string;
  description: string;
  useYn: string;
  dataScope: string;
  sensitiveInquiry: boolean;
  partStandardChange: boolean;
  admin: boolean;
  builtIn: boolean;
}
export interface SecurityMenu extends Row {
  name: string;
  screenId: string | null;
  parentId: number | null;
  sortOrder: number;
  icon: string | null;
  useYn: string;
  mobileVisible: boolean;
}
export const actions = [
  "inq",
  "reg",
  "mdfcn",
  "del",
  "upld",
  "dwnld",
  "cnfrm",
] as const;
export type Action = (typeof actions)[number];
export type Permission = Record<Action, boolean> & {
  menuId: number;
  name: string;
  screenId: string | null;
  supportedActions: Action[];
};
export const get = <T>(path: string) => readData<T>(path);
export const write = async <T = unknown>(
  path: string,
  method: string,
  body?: unknown,
): Promise<T> =>
  (
    await opportunitySessionWrite<T>(path, {
      method,
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    })
  ).data as T;
export async function all<T>(path: string): Promise<T[]> {
  const rows: T[] = [];
  let page = 0;
  for (;;) {
    const result = await get<Page<T>>(
      `${path}${path.includes("?") ? "&" : "?"}page=${page}&size=100`,
    );
    rows.push(...result.items);
    if (rows.length >= result.total || !result.items.length) return rows;
    page++;
  }
}
export async function download(path: string, filename: string) {
  const response = await fetch(path, { credentials: "same-origin" });
  if (!response.ok) {
    await api(path);
    return;
  }
  const url = URL.createObjectURL(await response.blob());
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  link.click();
  URL.revokeObjectURL(url);
}
