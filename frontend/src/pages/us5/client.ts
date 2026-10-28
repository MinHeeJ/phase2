import { readData, ApiFailure } from "../../shared/api";

export interface MonthlyShortageFilters {
  year: number;
  projectId?: number;
  job?: string;
}
export interface MonthlyShortageRow {
  prj_id: number;
  prj_nm: string;
  job_cd: string;
  job_nm: string;
  plan_ym: string;
  planMm: number;
  actualMm: number;
  shortageMm: number;
}
export interface MonthlyShortagePage {
  rows: MonthlyShortageRow[];
  total: number;
  page: number;
  size: number;
}
export function monthlyShortageQuery(filters: MonthlyShortageFilters) {
  const query = new URLSearchParams({ year: String(filters.year) });
  if (filters.projectId !== undefined)
    query.set("projectId", String(filters.projectId));
  if (filters.job) query.set("job", filters.job);
  return query;
}
export function listMonthlyShortages(
  filters: MonthlyShortageFilters,
  page = 0,
) {
  const query = monthlyShortageQuery(filters);
  query.set("page", String(page));
  query.set("size", "50");
  return readData<MonthlyShortagePage>(
    `/api/plan-vs-actual/shortages?${query}`,
  );
}
export async function downloadMonthlyShortages(
  filters: MonthlyShortageFilters,
) {
  let response: Response;
  try {
    response = await fetch(
      `/api/plan-vs-actual/shortages/export?${monthlyShortageQuery(filters)}`,
    );
  } catch {
    throw new ApiFailure("파일을 내려받을 수 없습니다.", null, null);
  }
  if (!response.ok) {
    const error = await response.json().catch(() => ({}));
    throw new ApiFailure(
      error.message ?? "파일을 내려받을 수 없습니다.",
      response.status,
      error.requestId ?? null,
      error.field ?? null,
      error.code ?? null,
    );
  }
  const header = response.headers.get("Content-Disposition") ?? "";
  const encoded = /filename\*=UTF-8''([^;]+)/i.exec(header)?.[1];
  let name = "";
  try {
    name = encoded
      ? decodeURIComponent(encoded)
      : (/filename="([^"]+)"/.exec(header)?.[1] ?? "");
  } catch {
    /* Reject an invalid filename rather than using it. */
  }
  if (!/^미충원직무_\d{1,4}_\d{8}\.xlsx$/.test(name)) {
    throw new ApiFailure(
      "다운로드 파일명을 확인할 수 없습니다.",
      response.status,
      null,
    );
  }
  const url = URL.createObjectURL(await response.blob());
  try {
    const link = document.createElement("a");
    link.href = url;
    link.download = name;
    link.click();
  } finally {
    URL.revokeObjectURL(url);
  }
}
