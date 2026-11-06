import { readData } from "../../shared/api";
import { opportunitySessionWrite } from "./opportunitySession";
import type { ProjectInput, ProjectDetail } from "../projects/client";
import type { AssignmentSave } from "../assignments/client";

export interface Need {
  need_id: number;
  job_cd: string;
  grd_cd: string | null;
  psn_cnt: number;
  bgn_ym: string;
  mth_cnt: number;
  mth_mm_qty: number;
  expc_untprc_amt: number;
  psn_mth_cst_amt: number;
  min_untprc_amt: number;
  trgt_untprc_amt: number;
  warnings: string[];
}
export interface OpportunityDetail {
  oppt_id: number;
  oppt_no: string;
  oppt_nm: string;
  cust_ptnr_id: number;
  bsn_part_id: number | null;
  bsn_stg_cd: string;
  win_prblt_rt: number;
  expc_amt: number;
  cntrct_ty_cd: string;
  bgn_ym: string;
  mth_cnt: number;
  next_action: string | null;
  next_action_dt: string | null;
  oppt_stts_cd: string;
  win_prj_id: number | null;
  created_at: string;
  updated_at: string | null;
  stale_warning: boolean;
  warnings: string[];
  needs: Need[];
  quote: {
    expc_sls_amt: number;
    expc_cst_amt: number;
    expc_mrgn_rt: number | null;
    cost_status: string;
    approval_state: string;
    approved_quote_amount: number | null;
    warnings: string[];
  };
  approval_history: {
    action: string;
    opinion: string | null;
    user_id: number | null;
    created_at: string;
    expc_mrgn_rt?: number | null;
  }[];
}
export interface OpportunityPage {
  items: OpportunityDetail[];
  total: number;
  page: number;
  size: 20 | 50 | 100;
  kpi: {
    open_count: number;
    expected_amount: number;
    weighted_amount: number;
    needed_people: number;
    stale_count: number;
  };
}
export interface OpportunityFilters {
  q?: string;
  oppt_stts_cd?: string;
  bsn_stg_cd?: string;
  cust_ptnr_id?: number;
  bsn_part_id?: number;
  from?: string;
  to?: string;
  page?: number;
  size?: number;
  sort?: string;
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
export interface RevenueMonth {
  month: string;
  project_revenue: number;
  opportunity_revenue: number;
  weighted_total: number;
  all_won_total: number;
}
export interface ForecastPage {
  items: RevenueMonth[];
  page: number;
  size: 20 | 50 | 100;
  total: number;
  workforce: {
    items: WorkforceMonth[];
    page: number;
    size: number;
    total: number;
    shortages: Shortage[];
    months: string[];
    target_utilization_rate: number;
    message: string | null;
  };
  kpi: {
    weighted_total: number;
    opportunity_total: number;
    all_won_total: number;
    average_demand_mm: number;
    average_supply_mm: number;
    hiring_job_count: number;
  };
}
export interface ForecastFilters {
  from?: string;
  months?: number;
  job?: string;
  grade?: string;
  year?: number;
  sort?: string;
  page?: number;
  size?: ForecastPage["size"];
}
const query = (filters: object) =>
  new URLSearchParams(
    Object.entries(filters)
      .filter(([, v]) => v != null && v !== "")
      .map(([k, v]) => [k, String(v)]),
  );
export const listOpportunities = (filters: OpportunityFilters) =>
  readData<OpportunityPage>(`/api/opportunities?${query(filters)}`);
export const getOpportunity = (id: number) =>
  readData<OpportunityDetail>(`/api/opportunities/${id}`);
export const getOpportunityForecast = (filters: ForecastFilters = {}) =>
  readData<ForecastPage>(`/api/opportunities/forecast?${query(filters)}`);
async function command<T>(id: number, action: string, body?: unknown) {
  return (
    await opportunitySessionWrite<T>(`/api/opportunities/${id}/${action}`, {
      method: "POST",
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    })
  ).data!;
}
export const updateOpportunityStage = (id: number, stage: string) =>
  command<OpportunityDetail>(id, "stage", { bsn_stg_cd: stage });
export const updateOpportunityApproval = (
  id: number,
  opinion: string,
  evidence?: string,
) =>
  command<OpportunityDetail>(id, "approve", {
    opinion,
    ...(evidence ? { zero_cost_evidence: evidence } : {}),
  });
export const updateOpportunityRejection = (id: number, reason: string) =>
  command<OpportunityDetail>(id, "reject", { reason });
export const updateOpportunityLoss = (id: number, reason: string) =>
  command<OpportunityDetail>(id, "lose", { reason });
export const updateOpportunityDisable = (id: number) =>
  command<OpportunityDetail>(id, "disable");
export const createOpportunityWin = (id: number, project: ProjectInput) =>
  command<{
    opportunity: OpportunityDetail;
    project: ProjectDetail;
    converted_assignments: AssignmentSave[];
  }>(id, "win", { project });
export async function exportOpportunities(filters: OpportunityFilters) {
  const { page: _page, size: _size, sort: _sort, ...conditions } = filters;
  const file = await readData<{
    file_name: string;
    media_type: string;
    content_base64: string;
  }>(`/api/opportunities/export?${query(conditions)}`);
  const bytes = Uint8Array.from(atob(file.content_base64), (c) =>
    c.charCodeAt(0),
  );
  const url = URL.createObjectURL(new Blob([bytes], { type: file.media_type }));
  try {
    const link = document.createElement("a");
    link.href = url;
    link.download = file.file_name;
    link.click();
  } finally {
    URL.revokeObjectURL(url);
  }
}
export const candidateNavigation = (row: Shortage) => ({
  pathname: "/UI-33",
  state: {
    workspace: "candidates",
    candidateFilters: {
      job: row.job_cd,
      grade: row.grd_cd ?? "",
      shortage_month: row.first_shortage_month,
    },
  },
});
