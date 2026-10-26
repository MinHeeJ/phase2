import { readData } from "../../shared/api";
import type { MemoProgress, MemoProject } from "../memos/client";
import type { BoardPhase } from "../apiops/boardClient";

export type ProgressProject = MemoProject;
export type ProgressPhase = BoardPhase;
export interface ProjectProgress extends MemoProgress {
  prj_id: number;
  pmName: string;
  currentPhase: ProgressPhase | null;
  schedule_diff_rt: number;
  cost_use_rt: number | null;
  cost_progress_diff_rt: number | null;
  cumulative_cost_amt: number | null;
  budget_amt: number | null;
  light: "정상" | "주의" | "위험" | null;
  reasons: string[];
  memo_elapsed_days: number | null;
  last_memo_dt: string | null;
  phases: ProgressPhase[];
  missing_salary_months: string[];
}
export interface ProjectIssueRow {
  prj_id: number;
  ttl_nm: string;
  svrt_cd: "HIGH" | "MID" | "LOW";
  chrg_nm: string | null;
  ddln_dt: string | null;
  issue_stts_cd: "OPEN" | "DOING" | "CLOSED";
  actn_desc: string | null;
  issue_id: number;
  reg_dt: string;
  end_dt: string | null;
}
export interface ProjectIssuesData {
  items: ProjectIssueRow[];
  open_count: number;
}
export const getProjectProgress = (id: number) =>
  readData<ProjectProgress>(`/api/projects/${encodeURIComponent(id)}/progress`);
export const listProjectIssues = (id: number) =>
  readData<ProjectIssuesData>(`/api/projects/${encodeURIComponent(id)}/issues`);
