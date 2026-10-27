import { api, readData } from "../../shared/api";

export interface AssignmentWarning {
  code: string;
  message: string;
  work_ym?: string | null;
}
export interface AssignmentMonth {
  work_ym: string;
  auto_mm_qty: number;
  mm_qty: number;
  mnl_yn: string;
  person_month_mm_qty: number;
  closed_yn: string;
}
export interface AssignmentInput {
  prj_id: number;
  dev_id: number;
  job_cd: string;
  bgn_dt: string;
  end_dt: string;
  asgn_rt: number;
  asgn_se_cd: string;
  bill_untprc_amt: number | null;
  untprc_base_cd: string | null;
  cntrct_dev_id: number | null;
  ext_psblty_cd: string | null;
  rmrk_desc: string | null;
  rsn_cd?: string;
  rsn_desc?: string | null;
}
export interface AssignmentItem extends AssignmentInput {
  asgn_id: number;
  asgn_no: string;
  prj_nm: string;
  dev_nm: string;
  mng_no: string;
  plan_end_dt: string;
  cntrct_dev_nm: string | null;
  asgn_stts_cd: "ACTIVE" | "ENDED" | "REPLACED" | "CANCELLED";
  rplc_asgn_id: number | null;
  current_month_mm_qty: number;
  person_month_mm_qty: number;
  year_mm_qty: number;
  warnings: AssignmentWarning[];
  updated_at: string;
}
export interface AssignmentDetail extends AssignmentItem {
  extend_count: number;
  rates: { aply_bgn_dt: string; bill_untprc_amt: number | null }[];
  months: AssignmentMonth[];
}
export interface AssignmentFilters {
  year: number;
  projectId?: number;
  job: string;
  warning: string;
  q: string;
  page: number;
  size: 20 | 50 | 100;
  sort: string;
}
export interface AssignmentPage {
  items: AssignmentItem[];
  total: number;
  page: number;
  size: number;
}
export interface AssignmentSave {
  assignment: AssignmentDetail;
  change: { asgn_chg_id: number; asgn_chg_ty_cd: string };
}
export const assignmentDefaults = (year: number): AssignmentFilters => ({
  year,
  job: "",
  warning: "",
  q: "",
  page: 0,
  size: 50,
  sort: "prj_nm,asc;bgn_dt,asc",
});
export const listAssignments = (filters: AssignmentFilters) =>
  readData<AssignmentPage>(
    `/api/assignments?${new URLSearchParams(
      Object.entries(filters)
        .filter(([, v]) => v !== "" && v != null)
        .map(([k, v]) => [k, String(v)]),
    )}`,
  );
export const getAssignment = (id: number) =>
  readData<AssignmentDetail>(`/api/assignments/${encodeURIComponent(id)}`);
export const createAssignment = async (input: AssignmentInput) =>
  (
    await api<AssignmentSave>("/api/assignments", {
      method: "POST",
      body: JSON.stringify(input),
    })
  ).data!;
export const checkAssignments = async (input: AssignmentInput) =>
  (
    await api<{ warnings: AssignmentWarning[]; months: AssignmentMonth[] }>(
      "/api/assignments/check",
      { method: "POST", body: JSON.stringify(input) },
    )
  ).data!;
