import { ApiFailure, readData } from "../../shared/api";
import type { ForecastFilters, ForecastPage } from "../opportunitiesops/client";

export type WorkforceForecastPage = ForecastPage["workforce"];
export type WorkforceForecastFilters = Pick<
  ForecastFilters,
  "from" | "months" | "job" | "grade" | "year" | "sort"
>;

export async function getWorkforceForecast(
  filters: WorkforceForecastFilters = {},
): Promise<WorkforceForecastPage> {
  const query = new URLSearchParams();
  Object.entries(filters).forEach(([key, value]) => {
    if (value !== undefined && value !== "") query.set(key, String(value));
  });
  query.set("size", "100");
  let result: WorkforceForecastPage | undefined;
  const items: WorkforceForecastPage["items"] = [];
  for (let page = 0; ; page++) {
    query.set("page", String(page));
    const next = await readData<WorkforceForecastPage>(
      `/api/workforce/forecast?${query}`,
    );
    result ??= next;
    items.push(...next.items);
    if (items.length >= next.total)
      return { ...result, items, total: next.total };
    if (!next.items.length)
      throw new ApiFailure("전체 예측 결과를 확인할 수 없습니다.", null, null);
  }
}
