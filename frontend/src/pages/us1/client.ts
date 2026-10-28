import { api, ApiFailure } from "../../shared/api";

export interface WithdrawalFilters {
  year: number;
  period?: "SOON" | "LATER" | "DONE";
  ext?: "H" | "M" | "L" | "NONE";
  job?: string;
  q?: string;
  dispatchWithin60?: boolean;
  page: number;
  size: 50;
  sort: string;
}
export interface WithdrawalRow {
  asgn_id: number;
  asgn_no: string;
  dev_id: number;
  dev_nm: string;
  mng_no: string;
  prj_id: number;
  prj_nm: string;
  job_cd: string;
  job_nm: string;
  bgn_dt: string;
  end_dt: string;
  plan_end_dt: string | null;
  ext_psblty_cd: string | null;
  ext_psblty_nm: string;
  asgn_stts_cd: string;
  prj_stts_cd: string;
  use_yn: string;
  cntrct_ty_cd: string;
  updated_at: string;
  extend_count: number;
  early_end: boolean;
  replacement_name: string | null;
  remaining_days: number;
  dispatch_first_dt: string | null;
  dispatch_expiry_dt: string | null;
  dispatch_display: string;
  dispatch_tone: "neutral" | "warning" | "danger";
  dispatch_alert_dates: readonly string[];
  next_asgn_id: number | null;
  next_prj_nm: string | null;
  next_bgn_dt: string | null;
  next_display: string;
  chance_locked: boolean;
  chance_lock_reason: string | null;
}
export interface WithdrawalPage {
  rows: WithdrawalRow[];
  total: number;
  page: number;
  size: 50;
  summary: { soonPeople: number; noNextPeople: number };
}
export const withdrawalDefaults = (year: number): WithdrawalFilters => ({
  year,
  page: 0,
  size: 50,
  sort: "end_dt,asc;dev_nm,asc",
});
export function withdrawalQuery(filters: WithdrawalFilters, exporting = false) {
  const query = new URLSearchParams();
  for (const [key, value] of Object.entries(filters)) {
    if (exporting && (key === "page" || key === "size")) continue;
    if (value !== undefined && value !== "") query.set(key, String(value));
  }
  return query;
}
export async function listWithdrawals(filters: WithdrawalFilters) {
  const response = await api<WithdrawalPage>(
    `/api/withdrawals?${withdrawalQuery(filters)}`,
  );
  return { ...response.data!, today: String(response.meta.today) };
}
export async function exportWithdrawals(filters: WithdrawalFilters) {
  let response: Response;
  try {
    response = await fetch(
      `/api/withdrawals/export?${withdrawalQuery(filters, true)}`,
    );
  } catch {
    throw new ApiFailure("서버에 연결할 수 없습니다.", null, null);
  }
  if (!response.ok) {
    const error = await response.json();
    throw new ApiFailure(
      error.message,
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
  ) {
    throw new ApiFailure("엑셀 파일 응답이 아닙니다.", response.status, null);
  }
  const encoded = response.headers
    .get("Content-Disposition")
    ?.match(/filename\*=UTF-8''([^;]+)/i)?.[1];
  let name = "";
  try {
    name = encoded ? decodeURIComponent(encoded) : "";
  } catch {
    /* Invalid filename is rejected below. */
  }
  if (!/^[^/\\\r\n]{1,200}\.xlsx$/.test(name))
    throw new ApiFailure("파일명을 확인하세요.", response.status, null);
  const blob = await response.blob();
  const prefix = new Uint8Array(await blob.slice(0, 2).arrayBuffer());
  if (prefix[0] !== 80 || prefix[1] !== 75)
    throw new ApiFailure(
      "엑셀 파일을 확인할 수 없습니다.",
      response.status,
      null,
    );
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
