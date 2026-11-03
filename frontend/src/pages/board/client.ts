import { readData, ApiFailure } from "../../shared/api";
import type { AssignmentItem } from "../assignments/client";

export interface BoardFilters {
  year: number;
  job?: string;
  grade?: string;
  skill?: string;
  emp_se_cd?: string;
  free_only?: boolean;
  sort: string;
  page: number;
  size: 20 | 50 | 100;
}
export interface BoardTemporary {
  id: number;
  need_id: number;
  dev_id: number;
  bgn_ym: string;
  end_ym: string;
  mth_mm_qty: number;
  oppt_nm?: string;
  warnings: string[];
}
export interface BoardCell {
  month: string;
  confirmed_mm: number;
  temporary_mm: number;
  assignment_count: number;
  status: string;
  withdrawal_date: string | null;
  fill_ratio: number;
  assignments: AssignmentItem[];
  temp_assignments: BoardTemporary[];
  warnings: string[];
}
export interface BoardRow {
  dev_id: number;
  dev_nm: string;
  mng_no: string;
  job_cd: string;
  grd_cd: string | null;
  months: BoardCell[];
}
export interface BoardPage {
  items: BoardRow[];
  page: number;
  size: number;
  total: number;
  monthly_totals: {
    month: string;
    free_people: number;
    remaining_mm: number;
  }[];
}
export interface WorkforceMonth {
  month: string;
  job_cd: string;
  grd_cd: string | null;
  supply_mm: number;
  demand_mm: number;
  remaining_mm: number;
  shortage_mm: number;
  available_mm: number;
  confirmed_mm: number;
  extension_mm: number;
  temporary_mm: number;
  utilization_rate: number | null;
  idle_cost: number;
  estimated: boolean;
  baseline_operating_profit: number | null;
  average_labor_cost: number;
  warnings: string[];
}
export interface BoardOpportunityNeed {
  job_cd: string;
  grd_cd?: string | null;
  psn_cnt: number;
  bgn_ym: string;
  mth_cnt: number;
  mth_mm_qty: number;
}
export interface BoardOpportunity {
  oppt_id: number;
  oppt_nm: string;
  oppt_stts_cd: string;
  win_prblt_rt: number;
  expc_amt: number;
  bgn_ym: string;
  mth_cnt: number;
  needs: BoardOpportunityNeed[];
}
export const boardDefaults = (year: number): BoardFilters => ({
  year,
  sort: "dev_id,asc",
  page: 0,
  size: 50,
});
export function boardQuery(filters: BoardFilters, exporting = false) {
  const query = new URLSearchParams();
  Object.entries(filters).forEach(([key, value]) => {
    if (exporting && ["sort", "page", "size"].includes(key)) return;
    if (value !== undefined && value !== "") query.set(key, String(value));
  });
  return query;
}
export const getWorkforceBoard = (filters: BoardFilters) =>
  readData<BoardPage>(`/api/workforce/board?${boardQuery(filters)}`);
export async function exportWorkforceBoard(filters: BoardFilters) {
  const artifact = await readData<{
    file_name: string;
    media_type: string;
    content_base64: string;
  }>(`/api/workforce/board/export?${boardQuery(filters, true)}`);
  if (
    !/^[^/\\\r\n]{1,200}\.xlsx$/.test(artifact.file_name) ||
    artifact.media_type !==
      "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
  )
    throw new ApiFailure("엑셀 파일 응답을 확인하세요.", null, null);
  const bytes = Uint8Array.from(atob(artifact.content_base64), (c) =>
    c.charCodeAt(0),
  );
  if (bytes[0] !== 80 || bytes[1] !== 75)
    throw new ApiFailure("엑셀 파일 응답을 확인하세요.", null, null);
  const url = URL.createObjectURL(
    new Blob([bytes], { type: artifact.media_type }),
  );
  try {
    const link = document.createElement("a");
    link.href = url;
    link.download = artifact.file_name;
    link.click();
  } finally {
    URL.revokeObjectURL(url);
  }
}
async function allPages<T>(path: string, query: URLSearchParams): Promise<T[]> {
  const items: T[] = [];
  for (let page = 0; ; page++) {
    query.set("page", String(page));
    query.set("size", "100");
    const result = await readData<{ items: T[]; total: number }>(
      `${path}?${query}`,
    );
    items.push(...result.items);
    if (items.length >= result.total) return items;
    if (!result.items.length)
      throw new ApiFailure("전체 조회 결과를 확인할 수 없습니다.", null, null);
  }
}
export function getBoardForecast(filters: BoardFilters) {
  const query = new URLSearchParams({
    year: String(filters.year),
    months: "6",
  });
  if (filters.job) query.set("job", filters.job);
  if (filters.grade) query.set("grade", filters.grade);
  return allPages<WorkforceMonth>("/api/workforce/forecast", query);
}
export async function getBoardOpportunities() {
  const rows = await allPages<{ oppt_id: number }>(
    "/api/opportunities",
    new URLSearchParams({ oppt_stts_cd: "OPEN" }),
  );
  const details = await Promise.all(
    rows.map((r) =>
      readData<BoardOpportunity>(
        `/api/opportunities/${encodeURIComponent(r.oppt_id)}`,
      ),
    ),
  );
  return details.filter((r) => r.oppt_stts_cd === "OPEN");
}
export async function getBoardScenarioRows(filters: BoardFilters) {
  const query = boardQuery({ ...filters, free_only: false });
  return allPages<BoardRow>("/api/workforce/board", query);
}
