import { api, readData, ApiFailure } from "../../shared/api";
import type { PageResult } from "../../shared/types";
import type { OperatingParam } from "../shared/api";
export type { CodeOption, OperatingParam } from "../shared/api";

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
export interface CodeRow {
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
export type ExcelMapPatch = Partial<{
  map_role_cd: ExcelMap["map_role_cd"];
  rmrk_desc: string;
  use_yn: ExcelMap["use_yn"];
}>;
export interface ExcelMapCreate {
  excel_lbl_nm: string;
  trns_cd_grp_id: string;
  trns_cd: string;
  map_role_cd: "MAIN" | "SUB";
  rmrk_desc: string;
}
export interface CodeGroupCreate {
  cd_grp_id: string;
  cd_grp_nm: string;
  attr1_nm: string;
  attr2_nm: string;
  attr3_nm: string;
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
const enc = encodeURIComponent;
export const adminPaths = {
  groups: "/api/admin/code-groups",
  maps: "/api/admin/excel-maps",
  params: "/api/admin/params",
  history: "/api/admin/code-history",
  codes: (group: string) => `/api/admin/code-groups/${enc(group)}/codes`,
  versions: (key: string) => `/api/admin/params/${enc(key)}/versions`,
};
export async function allPages<T>(path: string): Promise<T[]> {
  const items: T[] = [];
  let page = 1;
  for (;;) {
    const result = await readData<PageResult<T>>(
      `${path}${path.includes("?") ? "&" : "?"}page=${page}&size=100`,
    );
    if (
      result.page !== page ||
      (result.items.length === 0 && items.length < result.total)
    )
      throw new ApiFailure("전체 목록을 확인할 수 없습니다.", null, null);
    items.push(...result.items);
    if (items.length >= result.total) return items;
    page++;
  }
}
export const listCodeGroups = () => allPages<CodeGroup>(adminPaths.groups);
export const listManagedCodes = (group: string) =>
  allPages<CodeRow>(adminPaths.codes(group));
export const listParamVersions = (key: string) =>
  allPages<OperatingParam>(adminPaths.versions(key));
export const saveGroupCodes = (group: string, rows: CodeRow[]) =>
  api<void>(adminPaths.codes(group), {
    method: "PUT",
    body: JSON.stringify(rows),
  });
export const createCodeGroup = (input: CodeGroupCreate) =>
  api<void>(adminPaths.groups, { method: "POST", body: JSON.stringify(input) });
export const createExcelMap = (input: ExcelMapCreate) =>
  api<void>(adminPaths.maps, { method: "POST", body: JSON.stringify(input) });
export const updateExcelMap = (id: string, input: ExcelMapPatch) =>
  api<void>(`${adminPaths.maps}/${enc(id)}`, {
    method: "PATCH",
    body: JSON.stringify(input),
  });
export const saveParamVersion = (
  key: string,
  month: string,
  input: { num_val: string } | { txt_val: string },
) => {
  if ("num_val" in input && !/^\d+(?:\.\d{1,4})?$/.test(input.num_val))
    throw new Error("올바른 값을 입력하세요.");
  const body =
    "num_val" in input
      ? `{"num_val":${input.num_val.replace(/^0+(?=\d)/, "")}}`
      : JSON.stringify(input);
  return api<void>(`${adminPaths.versions(key)}/${enc(month)}`, {
    method: "PUT",
    body,
  });
};
export const failure = (e: unknown) =>
  e instanceof ApiFailure
    ? e
    : new ApiFailure("요청을 처리할 수 없습니다.", null, null);
