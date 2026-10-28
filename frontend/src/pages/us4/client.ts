import { api, ApiFailure } from "../../shared/api";
import type { MmGap } from "../../shared/MmGapJudge";

export interface PlanComparisonFilters {
  year: number;
  projectId: string;
  job: string;
  gap: "" | MmGap;
  sort: string;
  page: number;
  size: 50;
}
export interface PlanComparisonRow {
  prj_id: number;
  prj_no: string;
  prj_nm: string;
  job_cd: string;
  job_nm: string;
  planMm: number;
  actualMm: number;
  gapMm: number;
  gap: MmGap;
}
export interface PlanComparisonPage {
  rows: PlanComparisonRow[];
  total: number;
  page: number;
  size: number;
  totals: { planMm: number; actualMm: number };
}
export interface PlanComparisonResult {
  data: PlanComparisonPage;
  today: string;
  projects: { prj_id: number; prj_no: string; prj_nm: string }[];
}
export const planComparisonDefaults = (
  year: number,
): PlanComparisonFilters => ({
  year,
  projectId: "",
  job: "",
  gap: "",
  sort: "gapMm,asc;prj_nm,asc;job_cd,asc",
  page: 0,
  size: 50,
});
export function planComparisonQuery(
  filters: PlanComparisonFilters,
  download = false,
) {
  const query = new URLSearchParams();
  for (const key of [
    "year",
    "projectId",
    "job",
    "gap",
    "sort",
    "page",
    "size",
  ] as const) {
    if (download && (key === "page" || key === "size")) continue;
    if (filters[key] !== "") query.set(key, String(filters[key]));
  }
  return query;
}
export async function listPlanComparison(
  filters: PlanComparisonFilters,
): Promise<PlanComparisonResult> {
  const response = await api<PlanComparisonPage>(
    `/api/plan-vs-actual?${planComparisonQuery(filters)}`,
  );
  return {
    data: response.data,
    today: response.meta.today as string,
    projects: response.meta.projects as PlanComparisonResult["projects"],
  };
}
export const projectStaffPlansPath = (id: number) =>
  `/UI-01?open=${encodeURIComponent(id)}&tab=staff-plans`;
export async function downloadPlanComparison(filters: PlanComparisonFilters) {
  const path = `/api/plan-vs-actual/export?${planComparisonQuery(filters, true)}`;
  let response: Response;
  try {
    response = await fetch(path);
  } catch {
    throw new ApiFailure("서버에 연결할 수 없습니다.", null, null);
  }
  if (!response.ok) {
    const failure = await response.json().catch(() => ({}));
    throw new ApiFailure(
      failure.message ?? "다운로드에 실패했습니다.",
      response.status,
      response.headers.get("X-Request-Id"),
      failure.field ?? null,
      failure.code ?? null,
    );
  }
  if (
    !response.headers
      .get("Content-Type")
      ?.includes(
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
      )
  )
    throw new ApiFailure(
      "엑셀 응답을 확인할 수 없습니다.",
      response.status,
      response.headers.get("X-Request-Id"),
    );
  const disposition = response.headers.get("Content-Disposition") ?? "";
  const name = /filename\*=UTF-8''([^;]+)/i.exec(disposition)?.[1];
  if (!name)
    throw new ApiFailure("파일명을 확인할 수 없습니다.", response.status, null);
  const url = URL.createObjectURL(await response.blob());
  const link = document.createElement("a");
  link.href = url;
  link.download = decodeURIComponent(name);
  document.body.append(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}
