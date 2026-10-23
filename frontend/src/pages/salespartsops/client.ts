import { api, ApiFailure, readData } from "../../shared/api";

export interface SalesPartStandard {
  part_std_hist_id: string;
  aply_bgn_ym: string;
  aply_end_ym: string | null;
  ldr_nm: string | null;
  prft_dstrb_rt: number;
  bsn_fee_amt: number;
  status: "예정" | "적용 중" | "지난 기준";
}
export interface SalesPartDetail {
  bsn_part_id: string;
  bsn_part_nm: string;
  hq_yn: "Y" | "N";
  use_yn: "Y" | "N";
  sort_seq: number;
  referenceMonth: string;
  currentStandard: SalesPartStandard | null;
  scheduledChanges: SalesPartStandard[];
  peopleCount: number;
  projectCount: number;
  standards: SalesPartStandard[];
  lastClosedMonth: string | null;
  firstAllowedMonth: string | null;
}
export interface SalesPartStandardSave {
  aply_bgn_ym: string;
  ldr_nm: string;
  prft_dstrb_rt: number;
  bsn_fee_amt: number;
}
export const salesPartExportColumns = [
  { key: "bsn_part_nm", label: "파트명" },
  { key: "ldr_nm", label: "파트장" },
  { key: "prft_dstrb_rt", label: "이익 배분율" },
  { key: "bsn_fee_amt", label: "영업비(1인·월)" },
  { key: "aply_bgn_ym", label: "적용 시작" },
  { key: "scheduledChanges", label: "예정된 변경" },
  { key: "peopleCount", label: "소속 인력" },
  { key: "projectCount", label: "진행 프로젝트" },
  { key: "use_yn", label: "사용 여부" },
] as const;
export const listSalesPartOptions = () =>
  readData<{ id: string; name: string; hq: boolean }[]>(
    "/api/sales-parts/options",
  );
export const readSalesPartDetail = (id: string) =>
  readData<SalesPartDetail>(`/api/sales-parts/${encodeURIComponent(id)}`);
export async function saveSalesPartStandard(
  id: string,
  input: SalesPartStandardSave,
) {
  return (
    await api<SalesPartDetail>(
      `/api/sales-parts/${encodeURIComponent(id)}/standards`,
      { method: "POST", body: JSON.stringify(input) },
    )
  ).data as SalesPartDetail;
}
export async function updateSalesPartName(id: string, name: string) {
  return (
    await api<SalesPartDetail>(
      `/api/sales-parts/${encodeURIComponent(id)}/name`,
      { method: "PUT", body: JSON.stringify({ bsn_part_nm: name }) },
    )
  ).data as SalesPartDetail;
}
export async function saveSalesPartDisable(id: string) {
  return (
    await api<SalesPartDetail>(
      `/api/sales-parts/${encodeURIComponent(id)}/disable`,
      { method: "POST" },
    )
  ).data as SalesPartDetail;
}
export async function saveSalesPartEnable(id: string) {
  return (
    await api<SalesPartDetail>(
      `/api/sales-parts/${encodeURIComponent(id)}/enable`,
      { method: "POST" },
    )
  ).data as SalesPartDetail;
}
export async function getSalesPartExport(
  columns: readonly string[],
  ids?: readonly string[],
) {
  const query = new URLSearchParams();
  columns.forEach((key) => query.append("columns", key));
  ids?.forEach((id) => query.append("ids", id));
  let response: Response;
  try {
    response = await fetch(`/api/sales-parts/export?${query}`);
  } catch {
    throw new ApiFailure("서버에 연결할 수 없습니다.", null, null);
  }
  if (!response.ok) {
    let error: { message?: string; field?: string; code?: string } = {};
    try {
      error = await response.json();
    } catch {
      /* Keep the transport error. */
    }
    throw new ApiFailure(
      error.message ?? "파일을 내려받지 못했습니다.",
      response.status,
      response.headers.get("X-Request-Id"),
      error.field ?? null,
      error.code ?? null,
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
  let filename = "";
  try {
    filename = decodeURIComponent(encoded ?? "");
  } catch {
    /* Reject malformed names. */
  }
  if (!/^영업파트_\d{8}\.xlsx$/.test(filename))
    throw new ApiFailure("파일명을 확인할 수 없습니다.", response.status, null);
  return { blob: await response.blob(), filename };
}
