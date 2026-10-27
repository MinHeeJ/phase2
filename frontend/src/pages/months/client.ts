import { api, readData } from "../../shared/api";

export interface AssignmentMonth {
  work_ym: string;
  auto_mm_qty: number;
  mm_qty: number;
  mnl_yn: "Y" | "N";
  person_month_mm_qty: number;
  closed_yn: "Y" | "N";
}
export interface AssignmentMonthWarning {
  code: string;
  message: string;
  work_ym?: string | null;
}
export interface AssignmentMonthsResult {
  asgn_id: number;
  updated_at: string;
  items: AssignmentMonth[];
  warnings: AssignmentMonthWarning[];
}
export interface AssignmentMonthsRequest {
  updated_at: string;
  months: { work_ym: string; mm_qty: number }[];
}
const path = (id: number) =>
  `/api/assignments/${encodeURIComponent(id)}/months`;
export const getAssignmentMonths = (id: number) =>
  readData<AssignmentMonthsResult>(path(id));
export const saveAssignmentMonths = async (
  id: number,
  input: AssignmentMonthsRequest,
) =>
  (
    await api<AssignmentMonthsResult>(path(id), {
      method: "PUT",
      body: JSON.stringify(input),
    })
  ).data!;

export interface ProjectAssignmentMonthsResult {
  prj_id: number;
  year: number;
  items: {
    asgn_id: number;
    dev_id: number;
    dev_nm: string;
    months: { work_ym: string; mm_qty: number }[];
    total_mm_qty: number;
  }[];
  month_totals: { work_ym: string; mm_qty: number }[];
}
export const getProjectAssignmentMonths = (id: number, year: number) =>
  readData<ProjectAssignmentMonthsResult>(
    `/api/projects/${encodeURIComponent(id)}/assignment-months?year=${encodeURIComponent(year)}`,
  );
