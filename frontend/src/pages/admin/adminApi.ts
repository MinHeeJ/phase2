import { query, mutate, request } from "../../shared/http";
import type { SharedEffectiveParam } from "../shared/sharedApi";

export interface AdminPage<T> {
  items: T[];
  total: number;
  page: number;
  size: 20 | 50 | 100;
}
export interface CodeGroup {
  cd_grp_id: string;
  cd_grp_nm: string;
  cd_grp_se_cd: "SYSTEM" | "BIZ";
  attr1_nm: string | null;
  attr2_nm: string | null;
  attr3_nm: string | null;
  sort_seq: number;
  use_yn: "Y" | "N";
  codeCount: number;
}
export interface ManagedCode {
  dtl_cd: string;
  cd_nm: string;
  sort_seq: number;
  use_yn: "Y" | "N";
  attr1_val: string | null;
  attr2_val: string | null;
  attr3_val: string | null;
  aply_bgn_dt: string | null;
}
export interface ExcelMap {
  map_id: string;
  excel_lbl_nm: string;
  trns_cd_grp_id: string;
  trns_cd: string;
  cd_grp_nm: string;
  cd_nm: string;
  map_role_cd: "MAIN" | "SUB";
  rmrk_desc: string | null;
  use_yn: "Y" | "N";
}
export interface CodeChange {
  chg_at: string;
  cd_grp_id: string;
  cd_grp_nm: string;
  dtl_cd: string | null;
  cd_chg_ty_cd: "ADD" | "UPDATE" | "DISABLE" | "ENABLE";
  item_nm: string;
  bfr_val: string | null;
  aft_val: string | null;
  chg_user_id: number | null;
}
export type OperatingParam = SharedEffectiveParam;
export type CodeGroupCreate = Pick<CodeGroup, "cd_grp_id" | "cd_grp_nm"> &
  Partial<Record<"attr1_nm" | "attr2_nm" | "attr3_nm", string>>;
export type ExcelMapCreate = Pick<
  ExcelMap,
  "excel_lbl_nm" | "trns_cd_grp_id" | "trns_cd" | "map_role_cd"
> & { rmrk_desc?: string };
export type ExcelMapPatch = Partial<
  Pick<ExcelMap, "map_role_cd" | "use_yn">
> & { rmrk_desc?: string };
const pathKey = encodeURIComponent;
const paging = (page: number, size: number) =>
  new URLSearchParams({ page: String(page), size: String(size) });
export const listCodeGroups = (page = 0, size = 50) =>
  query<AdminPage<CodeGroup>>(`/api/admin/code-groups?${paging(page, size)}`);
export const listManagedCodes = (group: string, page = 0, size = 50) =>
  query<AdminPage<ManagedCode>>(
    `/api/admin/code-groups/${pathKey(group)}/codes?${paging(page, size)}`,
  );
export const listExcelMaps = (page = 0, size = 50) =>
  query<AdminPage<ExcelMap>>(`/api/admin/excel-maps?${paging(page, size)}`);
export const listCodeHistory = (group?: string, page = 0, size = 50) => {
  const params = paging(page, size);
  if (group) params.set("group", group);
  return query<AdminPage<CodeChange>>(`/api/admin/code-history?${params}`);
};
export const listCurrentParams = (page = 0, size = 50) =>
  query<AdminPage<OperatingParam>>(`/api/admin/params?${paging(page, size)}`);
export const listParamVersions = (key: string, page = 0, size = 50) =>
  query<AdminPage<OperatingParam>>(
    `/api/admin/params/${pathKey(key)}/versions?${paging(page, size)}`,
  );
export const createCodeGroup = (body: CodeGroupCreate) =>
  mutate("/api/admin/code-groups", "POST", body);
export const saveGroupCodes = (group: string, body: ManagedCode[]) =>
  mutate(`/api/admin/code-groups/${pathKey(group)}/codes`, "PUT", body);
export const createExcelMap = (body: ExcelMapCreate) =>
  mutate("/api/admin/excel-maps", "POST", body);
export const updateExcelMap = (id: string, body: ExcelMapPatch) =>
  mutate(`/api/admin/excel-maps/${pathKey(id)}`, "PATCH", body);
export async function saveParamVersion(
  key: string,
  month: string,
  body: { num_val: number | string } | { txt_val: string },
): Promise<void> {
  const path = `/api/admin/params/${pathKey(key)}/versions/${pathKey(month)}`;
  if ("txt_val" in body) {
    await mutate(path, "PUT", body);
    return;
  }
  const value = String(body.num_val);
  if (!/^\d+(?:\.\d+)?$/.test(value))
    throw new Error("올바른 값을 입력하세요.");
  const [integer, fraction] = value.split(".");
  const literal = `${BigInt(integer).toString()}${fraction === undefined ? "" : `.${fraction}`}`;
  await request(path, { method: "PUT", body: `{"num_val":${literal}}` });
}
export async function collectAdminPages<T>(
  load: (page: number) => Promise<AdminPage<T>>,
): Promise<T[]> {
  const rows: T[] = [];
  for (let page = 0; ; page++) {
    const result = await load(page);
    rows.push(...result.items);
    if (rows.length >= result.total) return rows;
    if (!result.items.length)
      throw new Error("전체 목록을 불러오지 못했습니다. 다시 조회하세요.");
  }
}
