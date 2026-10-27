import { readData } from "../../shared/api";

export interface ProjectMonthCell {
  work_ym: string;
  mm_qty: number;
}
export interface ProjectAssignmentMonthRow {
  asgn_id: number;
  dev_id: number;
  dev_nm: string;
  months: ProjectMonthCell[];
  total_mm_qty: number;
}
export interface ProjectAssignmentMonthsResult {
  prj_id: number;
  year: number;
  items: ProjectAssignmentMonthRow[];
  month_totals: ProjectMonthCell[];
}
export const getProjectAssignmentMonths = (projectId: number, year: number) =>
  readData<ProjectAssignmentMonthsResult>(
    `/api/projects/${encodeURIComponent(projectId)}/assignment-months?${new URLSearchParams({ year: String(year) })}`,
  );
