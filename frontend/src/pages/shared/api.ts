import { api } from "../../services/api";
import type { MenuNode } from "../../services/types";

export type { MenuNode } from "../../services/types";
export type CodeOption = {
  code: string;
  name: string;
  attr1: string | null;
  attr2: string | null;
  attr3: string | null;
};
export type MonthClose = { month: string; status: "OPEN" | "CLOSED" };
export type OperatingParam = {
  oprt_std_id: string;
  aply_bgn_ym: string;
  oprt_std_nm: string;
  num_val: number | null;
  txt_val: string | null;
  unit_nm: string | null;
  use_plc_desc: string | null;
};
export const getMenus = () => api<MenuNode[]>("/api/menus");
export const getMonthClose = (from: string, to: string) =>
  api<MonthClose[]>(`/api/month-close?${new URLSearchParams({ from, to })}`);
export const getCodeOptions = (group: string) =>
  api<CodeOption[]>(`/api/codes/${encodeURIComponent(group)}`);
export const getEffectiveParam = (key: string, month: string) =>
  api<OperatingParam>(
    `/api/params/${encodeURIComponent(key)}?${new URLSearchParams({ month })}`,
  );
export const getHealth = () => api<undefined>("/api/health");
