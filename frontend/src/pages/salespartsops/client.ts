import { api, readData, ApiFailure } from "../../shared/api";

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
export interface SalesPartOption {
  id: string;
  name: string;
  hq: boolean;
}
const path = (id: string) => `/api/sales-parts/${encodeURIComponent(id)}`;
export const readSalesPart = (id: string) =>
  readData<SalesPartDetail>(path(id));
export const listSalesPartOptions = () =>
  readData<SalesPartOption[]>("/api/sales-parts/options");
export const saveSalesPartStandard = async (
  id: string,
  input: SalesPartStandardSave,
) =>
  (
    await api<SalesPartDetail>(`${path(id)}/standards`, {
      method: "POST",
      body: JSON.stringify(input),
    })
  ).data as SalesPartDetail;
export const updateSalesPartName = async (id: string, name: string) =>
  (
    await api<SalesPartDetail>(`${path(id)}/name`, {
      method: "PUT",
      body: JSON.stringify({ bsn_part_nm: name }),
    })
  ).data as SalesPartDetail;
export const saveSalesPartDisable = async (id: string) =>
  (await api<SalesPartDetail>(`${path(id)}/disable`, { method: "POST" }))
    .data as SalesPartDetail;
export const saveSalesPartEnable = async (id: string) =>
  (await api<SalesPartDetail>(`${path(id)}/enable`, { method: "POST" }))
    .data as SalesPartDetail;

export async function getSalesPartExport(
  columns: readonly string[],
  ids?: readonly string[],
  sort?: string,
) {
  const query = new URLSearchParams();
  columns.forEach((key) => query.append("columns", key));
  ids?.forEach((id) => query.append("ids", id));
  if (sort) query.set("sort", sort);
  let response: Response;
  try {
    response = await fetch(`/api/sales-parts/export?${query}`, {
      headers: {
        Accept:
          "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
      },
    });
  } catch {
    throw new ApiFailure("서버에 연결할 수 없습니다.", null, null);
  }
  const requestId = response.headers.get("X-Request-Id");
  if (!response.ok) {
    const error = await response.json().catch(() => null);
    throw new ApiFailure(
      error?.message ?? "파일을 내려받을 수 없습니다.",
      response.status,
      error?.requestId ?? requestId,
      error?.field ?? null,
      error?.code ?? null,
    );
  }
  if (
    !response.headers
      .get("Content-Type")
      ?.startsWith(
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
      )
  )
    throw new ApiFailure(
      "엑셀 파일 응답을 확인할 수 없습니다.",
      response.status,
      requestId,
    );
  const encoded = response.headers
    .get("Content-Disposition")
    ?.match(/filename\*=UTF-8''([^;]+)/i)?.[1];
  let filename = "";
  try {
    filename = decodeURIComponent(encoded ?? "");
  } catch {
    /* Reject malformed download metadata below. */
  }
  if (!/^영업파트_\d{8}\.xlsx$/.test(filename))
    throw new ApiFailure(
      "파일명을 확인할 수 없습니다.",
      response.status,
      requestId,
    );
  try {
    const blob = await response.blob();
    if (!blob.size) throw new Error("empty");
    return { blob, filename };
  } catch {
    throw new ApiFailure(
      "파일을 내려받을 수 없습니다.",
      response.status,
      requestId,
    );
  }
}
