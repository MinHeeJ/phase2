import { api, readData, ApiFailure } from "../../shared/api";

export interface PartnerRow {
  ptnr_id: string;
  ptnr_no: string;
  ptnr_nm: string;
  ptnr_se_cd: string;
  biz_reg_no: string;
  ceo_nm: string | null;
  indty_cd: string | null;
  chrg_nm: string | null;
  chrg_tel_no: string | null;
  txinv_email_addr: string | null;
  use_yn: string;
  kindName?: string;
  industryName?: string | null;
  paymentAccount: string | null;
  projectCount: number | null;
  assignmentPeopleCount: number;
}
export interface PartnerDetail
  extends Omit<
    PartnerRow,
    "paymentAccount" | "projectCount" | "assignmentPeopleCount"
  > {
  bank_cd?: string | null;
  acnt_no?: string | null;
  dpstr_nm?: string | null;
  rmrk_desc?: string | null;
}
export interface PartnerAssignment {
  projectName: string;
  developerName: string;
  affiliation: string;
  jobCode: string;
  jobName: string;
  assignmentRate: number;
  endDate: string;
}
export interface PartnerChange {
  changedAt: string;
  column: string | null;
  itemName: string;
  before: string | null;
  after: string | null;
  changeType: string;
  actorName: string;
}
export interface PartnerBlocked {
  id: string;
  name: string;
  projectCount: number;
  peopleCount: number;
  reason: string;
}
export interface PartnerDisableResult {
  disabledIds: string[];
  blocked: PartnerBlocked[];
}
export interface PartnerFilters {
  kind?: string;
  useYn?: string;
  q?: string;
}
export interface PartnerPage<T> {
  items: T[];
  total: number;
  page: number;
  size: number;
}
export type { SalesPart as SalesPartRow } from "../salesparts/client";
export function partnerQuery(filters: PartnerFilters): URLSearchParams {
  const query = new URLSearchParams();
  Object.entries(filters).forEach(([key, value]) => {
    if (value !== undefined) query.set(key, value);
  });
  return query;
}
export const listPartnerAssignments = (id: string) =>
  readData<PartnerAssignment[]>(
    `/api/partners/${encodeURIComponent(id)}/assignments`,
  );
export const listPartnerHistory = (id: string) =>
  readData<PartnerChange[]>(`/api/partners/${encodeURIComponent(id)}/history`);
export const listPartnerOptions = (kind: "CUST" | "VEND") =>
  readData<{ id: string; no: string; name: string }[]>(
    `/api/partners/options?kind=${kind}`,
  );
export const savePartnerDisable = async (ids: string[]) =>
  (
    await api<PartnerDisableResult>("/api/partners/disable", {
      method: "POST",
      body: JSON.stringify({ ids }),
    })
  ).data!;
export const savePartnerEnable = async (id: string) =>
  (
    await api<PartnerDetail>(`/api/partners/${encodeURIComponent(id)}/enable`, {
      method: "POST",
    })
  ).data!;
export async function downloadPartnerFile(
  resource: "partners" | "sales-parts",
  filters: PartnerFilters,
  columns: readonly string[],
  ids?: string[],
) {
  const query = partnerQuery(filters);
  columns.forEach((key) => query.append("columns", key));
  ids?.forEach((id) => query.append("ids", id));
  let response: Response;
  try {
    response =
      resource === "partners"
        ? await fetch(`/api/partners/export?${query}`)
        : await fetch(`/api/sales-parts/export?${query}`);
  } catch {
    throw new ApiFailure("서버에 연결할 수 없습니다.", null, null);
  }
  if (!response.ok) {
    const body = await response.json().catch(() => ({}));
    throw new ApiFailure(
      body.message ?? "파일을 내려받지 못했습니다.",
      response.status,
      body.requestId ?? response.headers.get("X-Request-Id"),
      body.field ?? null,
      body.code ?? null,
    );
  }
  if (
    !response.headers
      .get("Content-Type")
      ?.includes(
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
      )
  )
    throw new ApiFailure(
      "엑셀 파일 응답이 아닙니다.",
      response.status,
      response.headers.get("X-Request-Id"),
    );
  const encoded = response.headers
    .get("Content-Disposition")
    ?.match(/filename\*=UTF-8''([^;]+)/i)?.[1];
  if (!encoded)
    throw new ApiFailure("파일명을 확인할 수 없습니다.", response.status, null);
  const name = decodeURIComponent(encoded);
  if (/[\\/\r\n]/.test(name) || !name.endsWith(".xlsx"))
    throw new ApiFailure("파일명을 확인할 수 없습니다.", response.status, null);
  const blob = await response.blob(),
    url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = name;
  link.dataset.testid = "partner-file-download";
  document.body.append(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}
