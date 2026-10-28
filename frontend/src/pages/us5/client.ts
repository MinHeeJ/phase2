import { readData } from "../../shared/api";

export interface ShortageFilter {
  year: number;
  projectId?: number;
  job?: string;
  page?: number;
}
export interface ShortageRow {
  projectId: number;
  projectName: string;
  job: string;
  jobName: string;
  month: string;
  planMm: number;
  actualMm: number;
  shortageMm: number;
}
export interface ShortagePage {
  rows: ShortageRow[];
  total: number;
  page: number;
  size: number;
}
function query(filter: ShortageFilter, page = true) {
  const params = new URLSearchParams({ year: String(filter.year) });
  if (filter.projectId) params.set("projectId", String(filter.projectId));
  if (filter.job) params.set("job", filter.job);
  if (page) {
    params.set("page", String(filter.page ?? 1));
    params.set("size", "50");
  }
  return params.toString();
}
export const listShortages = (filter: ShortageFilter) =>
  readData<ShortagePage>(`/api/plan-vs-actual/shortages?${query(filter)}`);
export async function downloadShortages(filter: ShortageFilter) {
  const response = await fetch(
    `/api/plan-vs-actual/shortages/export?${query(filter, false)}`,
  );
  if (!response.ok) throw new Error("다운로드에 실패했습니다.");
  const url = URL.createObjectURL(await response.blob());
  const anchor = document.createElement("a");
  anchor.href = url;
  const header = response.headers.get("Content-Disposition") ?? "";
  const filename = header.match(/filename\*=UTF-8''([^;]+)/i)?.[1];
  anchor.download = filename
    ? decodeURIComponent(filename)
    : `미충원직무_${filter.year}.xlsx`;
  anchor.click();
  URL.revokeObjectURL(url);
}
