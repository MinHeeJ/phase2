import { readData } from "../../shared/api";

export interface CostProjectSource {
  prj_id: number;
  prj_no: string;
  prj_nm: string;
  cntrct_ty_cd: string;
  prj_stts_cd: string;
  part_name: string | null;
  pm_name: string | null;
  init_cntrct_amt: number | null;
  cur_cntrct_amt: number | null;
  trgt_cst_amt: number | null;
  actl_prgs_rt: number | null;
}
export interface CostProjectRow {
  project: CostProjectSource;
  plan_mm: number;
  actual_mm: number;
  revenue: number | null;
  cost: number;
  profit: number | null;
  margin: number | null;
  forecast_cost: number | null;
  estimated_margin: number | null;
  target_margin: number | null;
  target_difference: number | null;
  budget_rate: number | null;
  budget_alert: string;
  low_margin: boolean;
  revenue_per_mm: number | null;
  cost_per_mm: number | null;
  profit_per_mm: number | null;
  free_mm: number;
  free_cost: number;
  rnd_cost: number;
  primary_cause: string | null;
  warnings: string[];
}
export interface CostProjectMonth {
  month: string;
  revenue: number | null;
  labor: number;
  expenses: number;
  cost: number;
  mm: number;
  free_mm: number;
  free_cost: number;
  margin: number | null;
  budget_remaining: number | null;
  reference_revenue: number | null;
  closed: boolean;
  warnings: string[];
}
export interface CostProjectPerson {
  month: string;
  dev_id: number;
  dev_nm: string | null;
  asgn_id: number | null;
  mm_qty: number;
  lbr_cst_amt: number;
  drct_expns_amt: number;
  reference_revenue: number | null;
  free_sprt: boolean;
  pre_cntrct: boolean;
}
export interface CostProjectCause {
  code: string;
  label: string;
  amount: number;
  basis: string;
  owner: string;
  recommendation: string;
  route: string | null;
  unavailable: string | null;
}
export interface CostProjectCauses {
  prj_id: number;
  year: number;
  items: CostProjectCause[];
  primary_cause: string | null;
  progress_excess: number | null;
  residual: number | null;
  warnings: string[];
}
export interface CostProjectDetail {
  summary: CostProjectRow;
  through: string;
  months: CostProjectMonth[];
  people: CostProjectPerson[];
  causes: CostProjectCauses;
  period_note: string;
}
export interface CostProjectFilters {
  year: number;
  type: string;
  status: string;
  partId: string;
  q: string;
  page: number;
  size: number;
  sort: string;
}
export interface CostProjectPage {
  items: CostProjectRow[];
  total: number;
  page: number;
  size: number;
  through: string;
  summary: {
    revenue: number;
    profit: number;
    low_margin_count: number;
    budget_risk_count: number;
    rnd_cost: number;
  };
}
export const costProjectDefaults = (year: number): CostProjectFilters => ({
  year,
  type: "",
  status: "",
  partId: "",
  q: "",
  page: 0,
  size: 50,
  sort: "prj_nm,asc",
});
export function listCostProjects(filters: CostProjectFilters) {
  const query = new URLSearchParams();
  for (const [key, value] of Object.entries(filters))
    if (value !== "") query.set(key, String(value));
  return readData<CostProjectPage>(`/api/costs/projects?${query}`);
}
export const getCostProject = (id: number, year: number) =>
  readData<CostProjectDetail>(
    `/api/costs/projects/${encodeURIComponent(id)}?year=${year}`,
  );
export const getCostProjectCauses = (id: number, year: number) =>
  readData<CostProjectCauses>(
    `/api/costs/projects/${encodeURIComponent(id)}/causes?year=${year}`,
  );
