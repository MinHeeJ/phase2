import { readData } from "../../shared/api";

export interface MenuNode {
  menuId: number;
  name: string;
  screenId: string | null;
  icon: string | null;
  mobile: boolean;
  children: MenuNode[];
}
export interface CodeOption {
  code: string;
  name: string;
  attr1: string | null;
  attr2: string | null;
  attr3: string | null;
}
export interface MonthClose {
  month: string;
  status: "CLOSED" | "OPEN";
}
export interface OperatingParam {
  oprt_std_id: string;
  aply_bgn_ym: string;
  oprt_std_nm: string;
  num_val: number | null;
  txt_val: string | null;
  unit_nm: string | null;
  use_plc_desc: string | null;
}
export const getMenus = (signal?: AbortSignal) =>
  readData<MenuNode[]>("/api/menus", signal);
export const getCodeOptions = (group: string, signal?: AbortSignal) =>
  readData<CodeOption[]>(`/api/codes/${encodeURIComponent(group)}`, signal);
export const getMonthClose = (from: string, to: string, signal?: AbortSignal) =>
  readData<MonthClose[]>(
    `/api/month-close?${new URLSearchParams({ from, to })}`,
    signal,
  );
export const getEffectiveParam = (
  key: string,
  month: string,
  signal?: AbortSignal,
) =>
  readData<OperatingParam>(
    `/api/params/${encodeURIComponent(key)}?${new URLSearchParams({ month })}`,
    signal,
  );
export function monthStatus(
  rows: readonly MonthClose[],
  month: string,
): MonthClose["status"] {
  return rows.find((row) => row.month === month)?.status ?? "OPEN";
}
export function menuScreens(menus: readonly MenuNode[]): MenuNode[] {
  return menus.flatMap((node) =>
    node.screenId
      ? [node]
      : node.children.filter((child) => child.screenId !== null),
  );
}
