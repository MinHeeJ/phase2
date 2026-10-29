import {
  listCostRenegotiations,
  type CostRenegotiationFilters,
} from "../costprofit/client";

export interface RenegotiationRow {
  asgn_id: number;
  dev_id: number;
  dev_nm: string;
  emp_se_cd: string;
  prj_id: number;
  prj_nm: string;
  end_dt: string;
  days_until_end: number;
  mm_qty: number | null;
  bill_untprc_amt: number | null;
  lbr_cst_amt: number | null;
  drct_expns_amt: number | null;
  monthly_cost_amt: number | null;
  margin_rt: number | null;
  target_price_amt: number | null;
  required_raise_rt: number | null;
  annual_profit_increase_amt: number | null;
  target_annual_profit_increase_amt: number | null;
}
export const renegotiationDefaults: CostRenegotiationFilters = {
  windowMonths: 6,
  marginBelow: 15,
  raiseRate: 5,
  page: 0,
  size: 50,
  sort: "end_dt,asc",
};
export const readRenegotiations = (filters: CostRenegotiationFilters) =>
  listCostRenegotiations<RenegotiationRow>(filters);
