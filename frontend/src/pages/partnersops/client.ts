import { api, readData, ApiFailure } from "../../shared/api";
export type PartnerSelection = {
  ptnr_id: string;
  ptnr_nm: string;
  use_yn: "Y" | "N";
};
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
export type PartnerFilters = {
  kind?: string;
  useYn?: string;
  q?: string;
  sort?: string;
};
export const partnerColumns = [
  { key: "kindName", label: "구분" },
  { key: "ptnr_nm", label: "상호" },
  { key: "biz_reg_no", label: "사업자번호" },
  { key: "ceo_nm", label: "대표자" },
  { key: "industryName", label: "업종" },
  { key: "chrg_nm", label: "담당자" },
  { key: "chrg_tel_no", label: "연락처" },
  { key: "txinv_email_addr", label: "세금계산서 이메일" },
  { key: "paymentAccount", label: "지급 계좌", sensitive: true },
  { key: "projectCount", label: "진행 프로젝트" },
  { key: "assignmentPeopleCount", label: "투입 인력" },
  { key: "use_yn", label: "사용 여부" },
] as const;
const enc = encodeURIComponent;
export const listPartnerOptions = (kind: "CUST" | "VEND") =>
  readData<{ id: string; no: string; name: string }[]>(
    `/api/partners/options?kind=${kind}`,
  );
export const listPartnerAssignments = (id: string) =>
  readData<PartnerAssignment[]>(`/api/partners/${enc(id)}/assignments`);
export const listPartnerHistory = (id: string) =>
  readData<PartnerChange[]>(`/api/partners/${enc(id)}/history`);
export const savePartnerDisable = async (ids: string[]) =>
  (
    await api<PartnerDisableResult>("/api/partners/disable", {
      method: "POST",
      body: JSON.stringify({ ids }),
    })
  ).data;
export const savePartnerEnable = async (id: string) =>
  (
    await api<PartnerSelection>(`/api/partners/${enc(id)}/enable`, {
      method: "POST",
    })
  ).data;
export const partnerFailure = (e: unknown) =>
  e instanceof ApiFailure
    ? e
    : new ApiFailure("요청을 처리할 수 없습니다.", null, null);
export async function downloadPartnerFile(path: string) {
  if (!path.startsWith("/api/") || path.includes("\r") || path.includes("\n"))
    throw new Error("상대 API 경로가 필요합니다.");
  let response: Response;
  try {
    response = await fetch(path);
  } catch {
    throw new ApiFailure("서버에 연결할 수 없습니다.", null, null);
  }
  if (
    !response.ok ||
    !response.headers
      .get("Content-Type")
      ?.includes(
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
      )
  ) {
    let error: {
      message?: string;
      field?: string;
      code?: string;
      requestId?: string;
    } = {};
    try {
      error = await response.json();
    } catch {
      /* binary failure has no JSON envelope */
    }
    throw new ApiFailure(
      error.message ?? "파일을 내려받을 수 없습니다.",
      response.status,
      error.requestId ?? response.headers.get("X-Request-Id"),
      error.field,
      error.code,
    );
  }
  const disposition = response.headers.get("Content-Disposition") ?? "";
  const encoded = /filename\*=UTF-8''([^;]+)/i.exec(disposition)?.[1];
  if (!encoded)
    throw new ApiFailure("파일명을 확인할 수 없습니다.", response.status, null);
  const filename = decodeURIComponent(encoded);
  if (!filename.endsWith(".xlsx") || /[\\/\r\n]/.test(filename))
    throw new ApiFailure("파일명을 확인할 수 없습니다.", response.status, null);
  const blob = await response.blob();
  if (!blob.size)
    throw new ApiFailure("파일이 비어 있습니다.", response.status, null);
  const url = URL.createObjectURL(blob),
    anchor = document.createElement("a");
  try {
    anchor.href = url;
    anchor.download = filename;
    anchor.click();
  } finally {
    URL.revokeObjectURL(url);
  }
}
export function partnerExportPath(
  resource: "partners" | "sales-parts",
  filters: PartnerFilters,
  columns: readonly string[],
  ids?: readonly string[],
) {
  const query = new URLSearchParams();
  Object.entries(filters).forEach(([key, value]) => {
    if (value !== undefined && key !== "page" && key !== "size")
      query.set(key, value);
  });
  columns.forEach((c) => query.append("columns", c));
  ids?.forEach((id) => query.append("ids", id));
  return `/api/${resource}/export?${query}`;
}
