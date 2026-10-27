import { readData } from "../../shared/api";

export type BoardLight = "위험" | "주의" | "정상";
export interface BoardPhase {
  phs_id: number;
  prj_id: number;
  phs_seq: number;
  phs_nm: string;
  wght_rt: number;
  plan_bgn_dt: string | null;
  plan_end_dt: string | null;
  cmpl_dt: string | null;
  status: "완료" | "지연" | "진행 중" | "예정";
}
export interface BoardRow {
  prj_id: number;
  prj_no: string;
  prj_nm: string;
  custName: string | null;
  cntrct_ty_cd: string;
  pmName: string;
  currentPhase: BoardPhase | null;
  plan_prgs_rt: number;
  actl_prgs_rt: number;
  schedule_diff_rt: number;
  cost_use_rt: number | null;
  cost_progress_diff_rt: number | null;
  cumulative_cost_amt: number | null;
  budget_amt: number | null;
  light: BoardLight;
  reasons: string[];
  memo_elapsed_days: number | null;
  last_memo_dt: string | null;
  open_issue_count: number;
  overdue_issue_count: number;
}
export interface BoardSummary {
  project_count: number;
  normal_count: number;
  warn_count: number;
  risk_count: number;
  open_issue_count: number;
  high_open_issue_count: number;
  overdue_issue_count: number;
  stale_project_count: number;
}
export interface BoardData {
  summary: BoardSummary;
  items: BoardRow[];
  total: number;
  page: number;
  size: number;
  stale_projects: {
    prj_id: number;
    prj_nm: string;
    memo_elapsed_days: number;
  }[];
}
export interface ProgressParameter {
  oprt_std_id: string;
  aply_bgn_ym: string;
  oprt_std_nm: string;
  num_val: number | null;
  txt_val: string | null;
  unit_nm: string | null;
  use_plc_desc: string | null;
}
export interface BoardFilters {
  light: string;
  type: string;
  part: string;
  page: number;
  size: 20 | 50 | 100;
  sort: string;
}
export const boardDefaults = (): BoardFilters => ({
  light: "",
  type: "",
  part: "",
  page: 0,
  size: 50,
  sort: "",
});
export function getProjectBoard(filters: BoardFilters) {
  const query = new URLSearchParams(
    Object.entries(filters)
      .filter(([, value]) => value !== "")
      .map(([key, value]) => [key, String(value)]),
  );
  return readData<BoardData>(`/api/project-board?${query}`);
}
export const getProgressParameters = () =>
  readData<ProgressParameter[]>("/api/params");
export const projectProgressPath = (id: number) =>
  `/UI-01?open=${encodeURIComponent(id)}&tab=progress`;
