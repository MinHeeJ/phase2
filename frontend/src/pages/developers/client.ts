import { api, readData } from "../../shared/api";
import type { PageResult } from "../../shared/types";

export interface DeveloperInput {
  mng_no: string | null;
  dev_nm: string;
  emp_se_cd: string;
  main_job_cd: string;
  vndr_ptnr_id: number | null;
  pstn_cd: string | null;
  duty_se_cd: string;
  grd_cd: string | null;
  sub_job: string[];
  skill: string[];
  bsn_part_id: number | null;
  hire_dt: string | null;
  tel_no: string | null;
  email_addr: string | null;
  rmrk_desc: string | null;
  dflt_untprc_amt: number | null;
}
export interface DeveloperListItem {
  dev_id: number;
  mng_no: string;
  dev_nm: string;
  affiliation_name: string;
  emp_se_cd: string;
  emp_type_name: string;
  grd_cd: string | null;
  grade_name: string | null;
  main_job_cd: string;
  main_job_name: string;
  status: string;
  current_project_name: string | null;
  current_assignment_count: number;
  current_month_mm: number;
  over_one_mm: boolean;
  latest_assignment_end_dt: string | null;
  dflt_untprc_amt: number | null;
  use_yn: "Y" | "N";
  rsgn_dt: string | null;
}
export type DeveloperDetail = DeveloperListItem &
  DeveloperInput & {
    latest_salary: { month: string; slry_amt: number } | null;
    updated_at: string | null;
    code_names?: Record<string, string>;
    sales_part_name?: string | null;
  };
export interface DeveloperFilters {
  empType: string;
  job: string;
  status: string;
  q: string;
  use_yn: "Y" | "N";
  page: number;
  size: 20 | 50 | 100;
  sort: string;
}
export const defaultDeveloperFilters: DeveloperFilters = {
  empType: "",
  job: "",
  status: "",
  q: "",
  use_yn: "Y",
  page: 0,
  size: 50,
  sort: "dev_nm,asc",
};
export const emptyDeveloper: DeveloperInput = {
  mng_no: null,
  dev_nm: "",
  emp_se_cd: "",
  main_job_cd: "",
  vndr_ptnr_id: null,
  pstn_cd: null,
  duty_se_cd: "DIRECT",
  grd_cd: null,
  sub_job: [],
  skill: [],
  bsn_part_id: null,
  hire_dt: null,
  tel_no: null,
  email_addr: null,
  rmrk_desc: null,
  dflt_untprc_amt: null,
};
export function developerPayload(r: DeveloperInput): DeveloperInput {
  return {
    mng_no: r.mng_no || null,
    dev_nm: r.dev_nm.trim(),
    emp_se_cd: r.emp_se_cd,
    main_job_cd: r.main_job_cd,
    vndr_ptnr_id: r.emp_se_cd === "OS" ? r.vndr_ptnr_id : null,
    pstn_cd: r.pstn_cd || null,
    duty_se_cd: r.duty_se_cd || "DIRECT",
    grd_cd: r.grd_cd || null,
    sub_job: [...new Set(r.sub_job)].filter((c) => c !== r.main_job_cd).sort(),
    skill: [...new Set(r.skill)].sort(),
    bsn_part_id: r.bsn_part_id,
    hire_dt: r.hire_dt || null,
    tel_no: r.tel_no?.trim() || null,
    email_addr: r.email_addr?.trim() || null,
    rmrk_desc: r.rmrk_desc?.trim() || null,
    dflt_untprc_amt: r.dflt_untprc_amt,
  };
}
export function developerQuery(filters: DeveloperFilters) {
  const params = new URLSearchParams();
  Object.entries(filters).forEach(([key, value]) => {
    if (value !== "") params.set(key, String(value));
  });
  return params.toString();
}
export const listDevelopers = (filters: DeveloperFilters) =>
  readData<PageResult<DeveloperListItem>>(
    `/api/developers?${developerQuery(filters)}`,
  );
export const getDeveloper = (id: number) =>
  readData<DeveloperDetail>(`/api/developers/${id}`);
export const createDeveloper = async (input: DeveloperInput) =>
  (
    await api<DeveloperDetail>("/api/developers", {
      method: "POST",
      body: JSON.stringify(developerPayload(input)),
    })
  ).data!;
export const updateDeveloper = async (id: number, input: DeveloperInput) => {
  const { mng_no: _identity, ...body } = developerPayload(input);
  return (
    await api<{ developer: DeveloperDetail; changed: boolean }>(
      `/api/developers/${id}`,
      { method: "PUT", body: JSON.stringify(body) },
    )
  ).data!;
};
export const enableDeveloper = async (id: number) =>
  (
    await api<DeveloperDetail>(`/api/developers/${id}/enable`, {
      method: "POST",
    })
  ).data!;
export const developerStatuses = [
  "투입중",
  "철수예정",
  "대기",
  "휴직",
  "퇴사",
] as const;
