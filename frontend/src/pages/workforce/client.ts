import { readData } from "../../shared/api";
import { query } from "../candidates/client";

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
export interface Shortage {
  job_cd: string;
  grd_cd: string | null;
  maximum_shortage_mm: number;
  maximum_shortage_people: number;
  first_shortage_month: string;
  recommended_hiring_month: string;
  review_immediately: boolean;
  candidate_count: number;
}
export interface ForecastPage {
  items: WorkforceMonth[];
  page: number;
  size: 20 | 50 | 100;
  total: number;
  shortages: Shortage[];
  months: string[];
  target_utilization_rate: number;
  message: string | null;
}
export interface ForecastFilters {
  year?: number;
  from?: string;
  months?: number;
  job: string;
  grade: string;
  page: number;
  size: 20 | 50 | 100;
  sort: string;
}
export const getForecast = (filters: ForecastFilters) =>
  readData<ForecastPage>(`/api/workforce/forecast?${query(filters)}`);
export const getShortages = (filters: Partial<ForecastFilters> = {}) =>
  readData<{ items: Shortage[]; page: number; size: number; total: number }>(
    `/api/candidates/shortages?${query(filters)}`,
  );
