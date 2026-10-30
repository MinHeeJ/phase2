import { api, ApiFailure, readData } from "../../shared/api";
import { seoulMonth } from "../shared/PeriodBar";
import type { AllocationRow } from "../costsops/client";

export interface CostProjectFilters {
  year: number;
  type: string;
  status: string;
  partId: string;
  q: string;
  page: number;
  size: 20 | 50 | 100;
  sort: string;
}
export const costProjectDefaults = (): CostProjectFilters => ({
  year:
    Number(sessionStorage.getItem("UI-04.year")) ||
    Number(seoulMonth().slice(0, 4)),
  type: "",
  status: "",
  partId: "",
  q: "",
  page: 0,
  size: 50,
  sort: "prj_id,asc",
});
export interface CostProjectRow {
  prj_id: number;
  prj_no: string;
  prj_nm: string;
  cntrct_ty_cd: string;
  cur_cntrct_amt: number | null;
  init_cntrct_amt: number | null;
  trgt_cst_amt: number | null;
  trgt_mrgn_rt: number | null;
  plan_mm_qty: number;
  actual_mm_qty: number;
  confirmed_supply_amt: number | null;
  lbr_cst_amt: number;
  drct_expns_amt: number;
  cumulative_cost_amt: number;
  profit_amt: number | null;
  margin_rt: number | null;
  budget_use_rt: number | null;
  budget_remaining_amt: number | null;
  estimated_cost_amt: number | null;
  estimated_margin_rt: number | null;
  reference_revenue_amt: number | null;
  free_mm_qty: number;
  free_cost_amt: number;
  price_per_mm_amt: number | null;
  cost_per_mm_amt: number | null;
  profit_per_mm_amt: number | null;
  low_margin: boolean;
  budget_warning: string | null;
  primary_cause: string | null;
}
interface Metadata {
  calculation_month: string;
  reference_month: string;
  closed: boolean;
  unit: string;
  warnings: string[];
}
export interface CostProjectList extends Metadata {
  items: CostProjectRow[];
  total: number;
  page: number;
  size: 20 | 50 | 100;
  summary: {
    confirmed_supply_amt: number;
    profit_amt: number;
    low_margin_count: number;
    budget_over_count: number;
    rnd_cost_amt: number;
  };
}
export interface ProjectCause {
  cause_code: string;
  amount: number;
  basis: string;
  owner_name: string | null;
  recommended_action: string;
  target_screen: string;
  project_id: number;
}
export interface ProjectCauses {
  items: ProjectCause[];
  progress_excess_amt: number | null;
  unexplained_amt: number | null;
  primary_cause: string | null;
}
export interface CostProjectDetail extends Metadata {
  project: CostProjectRow;
  months: {
    month: string;
    closed: boolean;
    confirmed_supply_amt: number | null;
    lbr_cst_amt: number;
    drct_expns_amt: number;
    cumulative_cost_amt: number;
    profit_amt: number | null;
    margin_rt: number | null;
    free_mm_qty: number;
    free_cost_amt: number;
    budget_remaining_amt: number | null;
    reference_revenue_amt: number | null;
  }[];
  people: { dev_nm: string | null; allocation: AllocationRow }[];
  causes: ProjectCauses;
}
export interface SimulationInput {
  headcount_delta: number;
  monthly_cost_amt: number;
  months: number;
  monthly_price_amt?: number;
}
export interface ProjectSimulation {
  simulated: boolean;
  baseline_cost_amt: number;
  additional_cost_amt: number;
  simulated_cost_amt: number;
  additional_revenue_amt: number | null;
  simulated_revenue_amt: number | null;
  simulated_profit_amt: number | null;
  simulated_margin_rt: number | null;
  warnings: string[];
}
export const costProjectQuery = (filters: CostProjectFilters) =>
  new URLSearchParams(
    Object.entries(filters)
      .filter(([, v]) => v !== "")
      .map(([k, v]) => [k, String(v)]),
  );
export const listCostProjects = async (filters: CostProjectFilters) =>
  (
    await api<CostProjectList>(
      `/api/costs/projects?${costProjectQuery(filters)}`,
      { headers: { Accept: "application/json" } },
    )
  ).data!;
export const getCostProject = (id: number, year: number) =>
  readData<CostProjectDetail>(`/api/costs/projects/${id}?year=${year}`);
export const getCostProjectCauses = (id: number, year: number) =>
  readData<ProjectCauses>(`/api/costs/projects/${id}/causes?year=${year}`);
export const simulateCostProject = async (id: number, input: SimulationInput) =>
  (
    await api<ProjectSimulation>(`/api/costs/projects/${id}/simulate`, {
      method: "POST",
      body: JSON.stringify(input),
    })
  ).data!;
export async function downloadCostProjects(filters: CostProjectFilters) {
  const response = await fetch(
    `/api/costs/projects?${costProjectQuery(filters)}`,
    {
      headers: {
        Accept:
          "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
      },
    },
  );
  if (!response.ok) {
    const error = await response.json();
    throw new ApiFailure(
      error.message,
      response.status,
      error.requestId,
      error.field,
      error.code,
    );
  }
  if (!response.headers.get("Content-Type")?.includes("spreadsheetml.sheet"))
    throw new ApiFailure(
      "엑셀 파일을 확인할 수 없습니다.",
      response.status,
      null,
    );
  const url = URL.createObjectURL(await response.blob());
  try {
    const link = document.createElement("a");
    link.href = url;
    link.download = "프로젝트원가분석.xlsx";
    link.click();
  } finally {
    URL.revokeObjectURL(url);
  }
}
