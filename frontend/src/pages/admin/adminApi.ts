import { api } from "../../services/api";
import type { PageData } from "../../services/types";
export type { OperatingParam } from "../shared/api";
import type { OperatingParam } from "../shared/api";
export type CodeGroup = {
  cd_grp_id: string;
  cd_grp_nm: string;
  cd_grp_se_cd: "SYSTEM" | "BIZ";
  attr1_nm: string | null;
  attr2_nm: string | null;
  attr3_nm: string | null;
  sort_seq: number;
  use_yn: "Y" | "N";
  codeCount: number;
};
export type CodeRow = {
  dtl_cd: string;
  cd_nm: string;
  sort_seq: number;
  use_yn: "Y" | "N";
  attr1_val: string | null;
  attr2_val: string | null;
  attr3_val: string | null;
  aply_bgn_dt: string | null;
};
export type ExcelMap = {
  map_id: string;
  excel_lbl_nm: string;
  trns_cd_grp_id: string;
  trns_cd: string;
  cd_grp_nm: string;
  cd_nm: string;
  map_role_cd: "MAIN" | "SUB";
  rmrk_desc: string | null;
  use_yn: "Y" | "N";
};

export type CodeChange = {
  chg_at: string;
  cd_grp_id: string;
  cd_grp_nm: string;
  dtl_cd: string | null;
  cd_chg_ty_cd: "ADD" | "UPDATE" | "DISABLE" | "ENABLE";
  item_nm: string;
  bfr_val: string | null;
  aft_val: string | null;
  chg_user_id: number | null;
};
export type CodeGroupCreate = Pick<
  CodeGroup,
  "cd_grp_id" | "cd_grp_nm" | "attr1_nm" | "attr2_nm" | "attr3_nm"
>;
export type ExcelMapCreate = Pick<
  ExcelMap,
  "excel_lbl_nm" | "trns_cd_grp_id" | "trns_cd" | "map_role_cd"
> & { rmrk_desc?: string };
export type ExcelMapPatch = Partial<
  Pick<ExcelMap, "map_role_cd" | "use_yn">
> & { rmrk_desc?: string };
export type PageSize = 20 | 50 | 100;
const segment = encodeURIComponent;
export const managedCodesPath = (group: string) =>
  `/api/admin/code-groups/${segment(group)}/codes`;
export const paramVersionsPath = (key: string) =>
  `/api/admin/params/${segment(key)}/versions`;
export async function collectAdminPages<T>(path: string): Promise<T[]> {
  const items: T[] = [];
  let page = 1;
  for (;;) {
    const data = await api<PageData<T>>(
      `${path}${path.includes("?") ? "&" : "?"}page=${page}&size=100`,
    );
    if (
      data.page !== page ||
      data.size !== 100 ||
      !Array.isArray(data.items) ||
      !Number.isSafeInteger(data.total) ||
      data.total < 0 ||
      (data.items.length === 0 && items.length < data.total)
    )
      throw new Error("전체 목록을 확인할 수 없습니다. 다시 조회하세요.");
    items.push(...data.items);
    if (items.length >= data.total) return items;
    page++;
  }
}
export const adminApi = {
  listCodeGroups: () => collectAdminPages<CodeGroup>("/api/admin/code-groups"),
  listManagedCodes: (group: string) =>
    collectAdminPages<CodeRow>(managedCodesPath(group)),
  refreshCodeOptions: (group: string) => api(`/api/codes/${segment(group)}`),
  saveGroupCodes: (group: string, rows: CodeRow[]) =>
    api(managedCodesPath(group), { method: "PUT", body: JSON.stringify(rows) }),
  createCodeGroup: (body: CodeGroupCreate) =>
    api("/api/admin/code-groups", {
      method: "POST",
      body: JSON.stringify(
        Object.fromEntries(
          Object.entries(body).filter(([, value]) => value !== null),
        ),
      ),
    }),
  listExcelMaps: (page: number, size: PageSize) =>
    api<PageData<ExcelMap>>(`/api/admin/excel-maps?page=${page}&size=${size}`),
  createExcelMap: (body: ExcelMapCreate) =>
    api("/api/admin/excel-maps", {
      method: "POST",
      body: JSON.stringify(body),
    }),
  updateExcelMap: (mapId: string, body: ExcelMapPatch) =>
    api(`/api/admin/excel-maps/${segment(mapId)}`, {
      method: "PATCH",
      body: JSON.stringify(body),
    }),
  listCurrentParams: (page: number, size: PageSize) =>
    api<PageData<OperatingParam>>(
      `/api/admin/params?page=${page}&size=${size}`,
    ),
  listParamVersions: (key: string) =>
    collectAdminPages<OperatingParam>(paramVersionsPath(key)),
  saveParamVersion: (
    key: string,
    month: string,
    body: { num_val: number | string } | { txt_val: string },
  ) => {
    let payload: string;
    if ("num_val" in body) {
      const value = String(body.num_val);
      if (!/^(?:0|[1-9]\d{0,13})(?:\.\d{1,4})?$/.test(value))
        return Promise.reject(new Error("올바른 값을 입력하세요."));
      payload = `{"num_val":${value}}`;
    } else payload = JSON.stringify(body);
    return api(`${paramVersionsPath(key)}/${segment(month)}`, {
      method: "PUT",
      body: payload,
    });
  },
  listCodeHistory: (group: string, page: number, size: PageSize) =>
    api<PageData<CodeChange>>(
      `/api/admin/code-history?page=${page}&size=${size}${group ? `&group=${segment(group)}` : ""}`,
    ),
};
