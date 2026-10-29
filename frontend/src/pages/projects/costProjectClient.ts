import type { CostMetadata } from "../costprofit/client";
export {
  listCostProjects,
  getCostProject,
  getCostProjectCauses,
  simulateCostProject,
  downloadCostProjects,
} from "../costprofit/client";
export interface CostProjectRow {
  prj_id: number;
  prj_no: string;
  prj_nm: string;
  cntrct_ty_cd: string;
  prj_stts_cd: string;
  bsn_part_id: number | null;
  part_name: string | null;
  init_cntrct_amt: number | null;
  cur_cntrct_amt: number | null;
  trgt_cst_amt: number | null;
  trgt_mrgn_rt: number | null;
  plan_mm_qty: number | null;
  actual_mm_qty: number;
  confirmed_supply_amt: number | null;
  lbr_cst_amt: number | null;
  drct_expns_amt: number;
  total_cost_amt: number | null;
  profit_amt: number | null;
  margin_rt: number | null;
  expected_margin_rt: number | null;
  monthly_margin_rt: number | null;
  budget_use_rt: number | null;
  target_diff_rt: number | null;
  per_mm_price_amt: number | null;
  per_mm_cost_amt: number | null;
  per_mm_profit_amt: number | null;
  free_mm_qty: number;
  free_cost_amt: number | null;
  rnd_cost_amt: number | null;
  budget_warning: string | null;
  low_margin: boolean;
  main_cause: string | null;
  warnings: string[];
}
export interface CostProjectMonth {
  month: string;
  status: string;
  confirmed_supply_amt: number | null;
  lbr_cst_amt: number | null;
  drct_expns_amt: number;
  total_cost_amt: number | null;
  profit_amt: number | null;
  margin_rt: number | null;
  mm_qty: number;
  free_mm_qty: number;
  free_cost_amt: number | null;
  budget_remaining_amt: number | null;
  reference_revenue_amt: number | null;
}
export interface CostProjectPerson {
  month: string;
  dev_id: number;
  dev_nm: string;
  asgn_id: number | null;
  mm_qty: number;
  lbr_cst_amt: number | null;
  drct_expns_amt: number;
  personal_revenue_amt: number | null;
  free_sprt_yn: string;
}
export interface CostProjectCause {
  code: string;
  label: string;
  amount: number | null;
  basis: string;
  owner: string;
  action: string;
  navigation: string | null;
  available: boolean;
}
export interface CostProjectCauses extends CostMetadata {
  prj_id: number;
  year: number;
  items: CostProjectCause[];
  main_cause: string | null;
  progress_excess_amt: number | null;
  unexplained_amt: number | null;
}
export interface CostProjectDetail extends CostMetadata {
  project: CostProjectRow;
  monthly_rows: CostProjectMonth[];
  people_rows: CostProjectPerson[];
  reference_revenue_amt: number | null;
  causes: CostProjectCauses;
}
export const projectNumber = (
  value: number | null | undefined,
  scale = 0,
  divisor = 1,
) =>
  value == null
    ? "–"
    : (value / divisor).toLocaleString("ko-KR", {
        minimumFractionDigits: scale,
        maximumFractionDigits: scale,
      });
export const causeLabel = (code: string | null) =>
  code === "EXCESS_MM"
    ? "투입 초과"
    : code === "COST_PRICE"
      ? "단가 대비 원가"
      : code === "FREE_SUPPORT"
        ? "무상지원"
        : "–";
