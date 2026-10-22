import { useEffect, useState } from "react";
export interface Envelope<T> {
  success: boolean;
  data: T;
  meta: Record<string, unknown>;
}
export interface Page<T> {
  items: T[];
  total: number;
  page: number;
  size: number;
}
export interface MenuNode {
  menuId: number;
  name: string;
  screenId: string | null;
  icon: string | null;
  mobile: boolean;
  children: MenuNode[];
}
export interface Group {
  cd_grp_id: string;
  cd_grp_nm: string;
  cd_grp_se_cd: "SYSTEM" | "BIZ";
  sort_seq: number;
  codeCount: number;
  attr1_nm: string | null;
  attr2_nm: string | null;
  attr3_nm: string | null;
  use_yn: "Y" | "N";
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
export interface CodeOption {
  code: string;
  name: string;
  attr1: string | null;
  attr2: string | null;
  attr3: string | null;
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
export interface Param {
  oprt_std_id: string;
  oprt_std_nm: string;
  aply_bgn_ym: string;
  num_val: number | null;
  txt_val: string | null;
  unit_nm: string | null;
  use_plc_desc: string | null;
}
export interface Change {
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
export class ApiError extends Error {
  constructor(
    message: string,
    public field?: string,
    public requestId?: string,
    public code?: string,
  ) {
    super(message);
  }
}
export async function api<T>(
  path: string,
  method = "GET",
  body?: unknown,
  signal?: AbortSignal,
): Promise<T> {
  let response: Response;
  try {
    response = await fetch(path, {
      method,
      headers:
        body === undefined ? undefined : { "Content-Type": "application/json" },
      body: body === undefined ? undefined : JSON.stringify(body),
      signal,
    });
  } catch (e) {
    if (e instanceof DOMException && e.name === "AbortError") throw e;
    throw new ApiError("서버에 연결할 수 없습니다. 다시 시도해 주세요.");
  }
  const payload = await response.json().catch(() => null);
  if (!response.ok || payload?.success !== true)
    throw new ApiError(
      payload?.message ?? "요청을 처리할 수 없습니다.",
      payload?.field,
      payload?.requestId ?? response.headers.get("X-Request-Id") ?? undefined,
      payload?.code,
    );
  return (payload as Envelope<T>).data;
}
export const groupPath = (id: string) =>
  `/api/admin/code-groups/${encodeURIComponent(id)}/codes`;
export const paramPath = (key: string) =>
  `/api/admin/params/${encodeURIComponent(key)}/versions`;
export async function allPages<T>(
  path: string,
  signal?: AbortSignal,
): Promise<T[]> {
  const result: T[] = [];
  let page = 1;
  for (;;) {
    const data = await api<Page<T>>(
      `${path}${path.includes("?") ? "&" : "?"}page=${page}&size=100`,
      "GET",
      undefined,
      signal,
    );
    result.push(...data.items);
    if (result.length >= data.total) return result;
    if (!data.items.length)
      throw new ApiError("전체 목록을 불러오지 못했습니다.");
    page++;
  }
}
export function useCodes(group: string) {
  const [options, setOptions] = useState<CodeOption[]>([]);
  const [error, setError] = useState<unknown>();
  const [loading, setLoading] = useState(false);
  const [revision, setRevision] = useState(0);
  useEffect(() => {
    setOptions([]);
    setError(undefined);
    setLoading(!!group);
    if (!group) return;
    const c = new AbortController();
    api<CodeOption[]>(
      `/api/codes/${encodeURIComponent(group)}`,
      "GET",
      undefined,
      c.signal,
    )
      .then((data) => {
        if (!c.signal.aborted) setOptions(data);
      })
      .catch((e) => {
        if (!c.signal.aborted) setError(e);
      })
      .finally(() => {
        if (!c.signal.aborted) setLoading(false);
      });
    return () => c.abort();
  }, [group, revision]);
  return { options, error, loading, retry: () => setRevision((v) => v + 1) };
}
