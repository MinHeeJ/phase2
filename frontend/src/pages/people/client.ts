import {
  listCostPeople,
  getCostPersonBreakdown,
  downloadCostPeople,
  type CostPeopleFilters,
  type CostMetadata,
  type CostAllocationRow,
} from "../costprofit/client";

export interface PeopleProject {
  asgn_id: number;
  dev_id: number;
  prj_id: number;
  prj_nm: string;
  cntrct_ty_cd: string;
  bgn_dt: string;
  end_dt: string;
}
export interface PeopleRow {
  dev_id: number;
  mng_no: string;
  dev_nm: string;
  affiliation_name: string;
  vendor_id: number | null;
  bsn_part_id: number | null;
  pstn_cd: string | null;
  emp_se_cd: string;
  job_cd: string | null;
  grd_cd: string | null;
  on_leave: boolean;
  projects: PeopleProject[];
  cntrct_ty_cd: string;
  mm_qty: number;
  bill_untprc_amt: number | null;
  slry_amt: number | null;
  alwnc_amt: number | null;
  sins_cmpny_amt: number | null;
  sins_emp_amt: number | null;
  whtax_amt: number | null;
  rtrpay_acml_amt: number | null;
  real_pay_amt: number | null;
  indv_lbr_cst_amt: number | null;
  team_expns_amt: number | null;
  project_expns_amt: number | null;
  total_cost_amt: number | null;
  personal_revenue_amt: number | null;
  profit_amt: number | null;
  margin_rt: number | null;
  overlap: boolean;
  warnings: string[];
}
export interface PeopleSummary {
  people_count: number;
  average_people_count: number;
  priced_people_count: number;
  loss_people_count: number;
  monthly_active_count: number | null;
  monthly_paid_count: number | null;
  utilization_rt: number | null;
  paid_rt: number | null;
  average_price_amt: number | null;
  average_cost_amt: number | null;
  average_profit_amt: number | null;
  contributions: {
    job_cd: string | null;
    grd_cd: string | null;
    people_count: number;
    profit_amt: number | null;
  }[];
}
export interface PeoplePage extends CostMetadata {
  items: PeopleRow[];
  total: number;
  page: number;
  size: number;
  summary: PeopleSummary;
}
export interface PeopleBreakdown extends CostMetadata {
  person: PeopleRow;
  items: CostAllocationRow[];
  monthly_rows: {
    month: string;
    status: string;
    items: CostAllocationRow[];
    warnings: string[];
  }[];
}
export const peopleDefaults = (year: number): CostPeopleFilters => ({
  year,
  view: "TOTAL",
  page: 0,
  size: 50,
  sort: "dev_nm,asc",
});
export async function readPeople(
  filters: CostPeopleFilters,
): Promise<PeoplePage> {
  const result = await listCostPeople<PeopleRow>(filters);
  return { ...result, summary: result.summary as unknown as PeopleSummary };
}
export const readPersonBreakdown = (id: number, filters: CostPeopleFilters) =>
  getCostPersonBreakdown<PeopleBreakdown>(id, filters.year, filters.view);
export async function savePeopleWorkbook(filters: CostPeopleFilters) {
  const { blob, filename } = await downloadCostPeople(filters);
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  link.click();
  URL.revokeObjectURL(url);
}
