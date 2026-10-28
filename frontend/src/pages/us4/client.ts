import { readData } from "../../shared/api";

export interface ComparisonFilters {
  year: number;
  projectId?: number;
  job?: string;
  gap?: string;
  page: number;
  size: number;
}
export interface ComparisonRow {
  projectId: number;
  projectName: string;
  job: string;
  jobName: string;
  planMm: number;
  actualMm: number;
  difference: number;
  gap: string;
}
export interface ComparisonPage {
  rows: ComparisonRow[];
  total: number;
  page: number;
  size: number;
  totals: { planMm: number; actualMm: number };
  tolerance: number;
}
const query = (filters: ComparisonFilters) =>
  new URLSearchParams(
    Object.entries(filters)
      .filter(([, value]) => value !== undefined && value !== "")
      .map(([key, value]) => [key, String(value)]),
  ).toString();
export const listPlanComparison = (filters: ComparisonFilters) =>
  readData<ComparisonPage>(`/api/plan-vs-actual?${query(filters)}`);
export async function downloadPlanComparison(filters: ComparisonFilters) {
  const response = await fetch(`/api/plan-vs-actual/export?${query(filters)}`);
  if (!response.ok) throw new Error("다운로드에 실패했습니다.");
  const url = URL.createObjectURL(await response.blob());
  const link = document.createElement("a");
  link.href = url;
  const header = response.headers.get("Content-Disposition") ?? "";
  const filename = header.match(/filename\*=UTF-8''([^;]+)/i)?.[1];
  link.download = filename
    ? decodeURIComponent(filename)
    : `계획대비투입_${filters.year}.xlsx`;
  link.click();
  URL.revokeObjectURL(url);
}
