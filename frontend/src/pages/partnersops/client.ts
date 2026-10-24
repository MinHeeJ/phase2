import { api, readData, ApiFailure } from "../../shared/api";

export interface PartnerRow {
  ptnr_id: string;
  ptnr_no: string;
  ptnr_nm: string;
  ptnr_se_cd: string;
  biz_reg_no: string;
  use_yn: string;
  kindName?: string;
  industryName?: string;
  ceo_nm?: string;
  chrg_nm?: string;
  chrg_tel_no?: string;
  txinv_email_addr?: string;
  paymentAccount: string | null;
  projectCount: number | null;
  assignmentPeopleCount: number;
}
export interface PartnerDetail
  extends Omit<
    PartnerRow,
    "paymentAccount" | "projectCount" | "assignmentPeopleCount"
  > {
  bank_cd?: string;
  acnt_no?: string;
  dpstr_nm?: string;
  indty_cd?: string;
  rmrk_desc?: string;
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
export interface PartnerBlock {
  id: string;
  name: string;
  projectCount: number;
  peopleCount: number;
  reason: string;
}
export interface PartnerDisableResult {
  disabledIds: string[];
  blocked: PartnerBlock[];
}
export interface PartnerFilter {
  kind?: string;
  useYn?: string;
  q?: string;
}
export interface PartnerPage {
  items: PartnerRow[];
  total: number;
  page: number;
  size: number;
}
export const partnerColumns = [
  ["ptnr_no", "거래처 코드"],
  ["kindName", "구분"],
  ["ptnr_nm", "상호"],
  ["biz_reg_no", "사업자번호"],
  ["ceo_nm", "대표자"],
  ["industryName", "업종"],
  ["chrg_nm", "담당자"],
  ["chrg_tel_no", "연락처"],
  ["txinv_email_addr", "세금계산서 이메일"],
  ["paymentAccount", "지급 계좌"],
  ["projectCount", "진행 프로젝트"],
  ["assignmentPeopleCount", "투입 인력"],
  ["use_yn", "사용 여부"],
] as const;
export function partnerQuery(filter: PartnerFilter) {
  const query = new URLSearchParams();
  for (const [key, value] of Object.entries(filter))
    if (value !== undefined && (value !== "" || key === "useYn"))
      query.set(key, value);
  return query;
}
export const listPartnerOptions = (kind: "CUST" | "VEND") =>
  readData<{ id: string; no: string; name: string }[]>(
    `/api/partners/options?kind=${kind}`,
  );
export const listPartnerAssignments = (id: string) =>
  readData<PartnerAssignment[]>(
    `/api/partners/${encodeURIComponent(id)}/assignments`,
  );
export const listPartnerHistory = (id: string) =>
  readData<PartnerChange[]>(`/api/partners/${encodeURIComponent(id)}/history`);
export const getPartnerDetail = (id: string) =>
  readData<PartnerDetail>(`/api/partners/${encodeURIComponent(id)}`);
export const disablePartners = async (ids: string[]) =>
  (
    await api<PartnerDisableResult>("/api/partners/disable", {
      method: "POST",
      body: JSON.stringify({ ids }),
    })
  ).data!;
export const enablePartner = async (id: string) =>
  (
    await api<PartnerDetail>(`/api/partners/${encodeURIComponent(id)}/enable`, {
      method: "POST",
    })
  ).data!;
export async function downloadList(
  resource: "partners" | "sales-parts",
  filter: PartnerFilter,
  columns: readonly string[],
  ids?: readonly string[],
) {
  const query = partnerQuery(filter);
  columns.forEach((key) => query.append("columns", key));
  ids?.forEach((id) => query.append("ids", id));
  const response =
    resource === "partners"
      ? await fetch(`/api/partners/export?${query}`)
      : await fetch(`/api/sales-parts/export?${query}`);
  if (!response.ok) {
    const error = await response.json();
    throw new ApiFailure(
      error.message ?? "다운로드하지 못했습니다.",
      response.status,
      error.requestId,
      error.field,
      error.code,
    );
  }
  if (
    !response.headers
      .get("Content-Type")
      ?.includes(
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
      )
  )
    throw new ApiFailure("엑셀 파일 응답이 아닙니다.", response.status, null);
  const blob = await response.blob();
  const prefix = new Uint8Array(await blob.slice(0, 2).arrayBuffer());
  if (prefix[0] !== 80 || prefix[1] !== 75)
    throw new ApiFailure(
      "엑셀 파일을 확인할 수 없습니다.",
      response.status,
      null,
    );
  const encoded = response.headers
    .get("Content-Disposition")
    ?.match(/filename\*=UTF-8''([^;]+)/i)?.[1];
  if (!encoded)
    throw new ApiFailure("파일명이 없습니다.", response.status, null);
  const name = decodeURIComponent(encoded);
  if (!/^[^/\\\r\n]{1,200}\.xlsx$/.test(name))
    throw new ApiFailure("파일명을 확인하세요.", response.status, null);
  const url = URL.createObjectURL(blob);
  try {
    const link = document.createElement("a");
    link.href = url;
    link.download = name;
    link.click();
  } finally {
    URL.revokeObjectURL(url);
  }
}
