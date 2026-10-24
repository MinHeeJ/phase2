import { api, readData, ApiFailure } from "../../shared/api";

export interface DeveloperRow {
  dev_id: number;
  mng_no: string;
  dev_nm: string;
  use_yn: "Y" | "N";
  rsgn_dt: string | null;
  affiliation_name: string;
  emp_se_cd: string;
  emp_type_name?: string;
  grade_name?: string;
  main_job_name?: string;
  status: string;
  current_project_name: string | null;
  current_assignment_count: number;
  current_month_mm: number;
  over_one_mm: boolean;
  latest_assignment_end_dt: string | null;
  dflt_untprc_amt: number | null;
}
export interface DeveloperDetail extends DeveloperRow {
  tel_no?: string;
  email_addr?: string;
  rmrk_desc?: string;
  sub_job: string[];
  skill: string[];
  latest_salary: { month: string; slry_amt: number } | null;
}
export interface DeveloperPage<T> {
  items: T[];
  total: number;
  page: number;
  size: 20 | 50 | 100;
}
export interface DeveloperFilter {
  empType?: string;
  job?: string;
  status?: string;
  q?: string;
  use_yn?: string;
}
export interface DeveloperOption {
  id: number;
  mngNo: string;
  name: string;
  mainJob: string;
  empType: string;
}
export interface DeveloperAssignment {
  asgn_id: number;
  prj_id: number;
  prj_nm: string;
  job_cd: string;
  job_name: string;
  bgn_dt: string;
  end_dt: string;
  asgn_rt: number;
  asgn_stts_cd: string;
}
export interface DeveloperCost {
  cst_ym: string;
  emp_se_cd: string;
  slry_amt: number;
  alwnc_amt: number;
  sins_cmpny_amt: number;
  rtrpay_acml_amt: number;
  indv_lbr_cst_amt: number;
  real_pay_amt: number;
}
export interface DeveloperChange {
  changed_at: string;
  table_name: string;
  record_id: string;
  change_type: string;
  item: string | null;
  before: string | null;
  after: string | null;
  actor: string | null;
}
export interface DeveloperBlock {
  id: number;
  name: string;
  current_assignment_count: number;
  code: string;
  reason: string;
}
export interface DeveloperDisableResult {
  disabled_ids: number[];
  blocked: DeveloperBlock[];
  disabled_count: number;
  blocked_count: number;
}
export function developerQuery(filter: DeveloperFilter) {
  const query = new URLSearchParams();
  for (const [key, value] of Object.entries(filter))
    if (value) query.set(key, value);
  return query;
}
export const listDeveloperOptions = (q = "") =>
  readData<DeveloperOption[]>(
    `/api/developers/options?${new URLSearchParams({ q })}`,
  );
export const listDeveloperAssignments = (id: number, page = 0, size = 50) =>
  readData<DeveloperPage<DeveloperAssignment>>(
    `/api/developers/${id}/assignments?page=${page}&size=${size}`,
  );
export const listDeveloperHistory = (id: number, page = 0, size = 50) =>
  readData<DeveloperPage<DeveloperChange>>(
    `/api/developers/${id}/history?page=${page}&size=${size}`,
  );
export const listDeveloperMonthlyCosts = (id: number, page = 0, size = 50) =>
  readData<DeveloperPage<DeveloperCost>>(
    `/api/developers/${id}/monthly-costs?page=${page}&size=${size}`,
  );
export const disableDevelopers = async (ids: number[]) =>
  (
    await api<DeveloperDisableResult>("/api/developers/disable", {
      method: "POST",
      body: JSON.stringify({ ids }),
    })
  ).data!;
export const enableDeveloper = async (id: number) =>
  (
    await api<DeveloperDetail>(`/api/developers/${id}/enable`, {
      method: "POST",
    })
  ).data!;
export async function downloadDevelopers(filter: DeveloperFilter) {
  const response = await fetch(
    `/api/developers/export?${developerQuery(filter)}`,
  );
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
  const encoded = response.headers
    .get("Content-Disposition")
    ?.match(/filename\*=UTF-8''([^;]+)/i)?.[1];
  if (!encoded)
    throw new ApiFailure("파일명이 없습니다.", response.status, null);
  const name = decodeURIComponent(encoded);
  if (!/^개발자풀_[0-9]{8}\.xlsx$/.test(name))
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
