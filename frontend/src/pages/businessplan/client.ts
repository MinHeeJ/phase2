import { api, readData, ApiFailure } from "../../shared/api";

export interface BusinessPlan {
  year: number;
  month: number;
  partId: number | null;
  target_revenue: number;
  target_operating_profit: number;
  target_utilization_rate: number;
}
export interface BusinessPlans {
  items: BusinessPlan[];
  parts: { id: number; name: string; active: boolean }[];
  closed_months: string[];
  year: number;
}
export interface BusinessMetric {
  target: number | null;
  actual: number | null;
  difference: number | null;
  achievement_rate: number | null;
}
export interface BusinessComparisonMonth {
  month: number;
  target_present: boolean;
  revenue: BusinessMetric;
  operating_profit: BusinessMetric;
  utilization_rate: BusinessMetric;
}
export interface BusinessComparison {
  items: BusinessPlan[];
  actuals: {
    month: number;
    revenue: number | null;
    operating_profit: number | null;
    utilization_rate: number | null;
    warnings: string[];
  }[];
  warning: string | null;
  warnings: string[];
  comparison: {
    months: BusinessComparisonMonth[];
    cumulative: Pick<
      BusinessComparisonMonth,
      "revenue" | "operating_profit" | "utilization_rate"
    >;
    cumulative_months: BusinessComparisonMonth[];
  };
  export_file: {
    file_name: string;
    content_type: string;
    content_base64: string;
  };
}
export const getBusinessPlan = (year: number, signal?: AbortSignal) =>
  readData<BusinessPlans>(
    `/api/finance/business-plan?${new URLSearchParams({ year: String(year) })}`,
    signal,
  );
export const getBusinessPlanComparison = (
  year: number,
  month: number,
  partId: number | null,
  signal?: AbortSignal,
) => {
  const query = new URLSearchParams({
    year: String(year),
    month: String(month),
  });
  if (partId != null) query.set("partId", String(partId));
  return readData<BusinessComparison>(
    `/api/finance/business-plan/comparison?${query}`,
    signal,
  );
};
export const saveBusinessPlan = async (year: number, items: BusinessPlan[]) =>
  (
    await api<BusinessPlans>(
      `/api/finance/business-plan?${new URLSearchParams({ year: String(year) })}`,
      { method: "PUT", body: JSON.stringify({ items }) },
    )
  ).data!;
export function downloadBusinessPlan(file: BusinessComparison["export_file"]) {
  if (
    !file ||
    file.content_type !==
      "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" ||
    !file.file_name.endsWith(".xlsx")
  )
    throw new ApiFailure("엑셀 파일 응답이 아닙니다.", 500, null);
  const binary = atob(file.content_base64);
  const bytes = Uint8Array.from(binary, (c) => c.charCodeAt(0));
  if (bytes[0] !== 80 || bytes[1] !== 75)
    throw new ApiFailure("엑셀 파일을 확인할 수 없습니다.", 500, null);
  const url = URL.createObjectURL(
    new Blob([bytes], { type: file.content_type }),
  );
  try {
    const link = document.createElement("a");
    link.href = url;
    link.download = file.file_name;
    link.click();
  } finally {
    URL.revokeObjectURL(url);
  }
}
