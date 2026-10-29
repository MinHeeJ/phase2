import { readData } from "../../shared/api";

export interface RenegotiationFilters {
  windowMonths: number;
  marginBelow: number;
  raiseRate: number;
  empType: string;
  projectId: string;
  page: number;
  size: number;
  sort: string;
}
export const renegotiationDefaults: RenegotiationFilters = {
  windowMonths: 6,
  marginBelow: 15,
  raiseRate: 5,
  empType: "",
  projectId: "",
  page: 0,
  size: 20,
  sort: "end_dt,asc",
};
export interface RenegotiationRow {
  asgn_id: number;
  dev_id: number;
  dev_nm: string;
  emp_se_cd: string;
  prj_id: number;
  prj_nm: string;
  cntrct_ty_cd: string;
  end_dt: string;
  days_remaining: number;
  mm_qty: number;
  bill_untprc_amt: number | null;
  allocated_cost_amt: number | null;
  margin_rt: number | null;
  target_untprc_amt: number | null;
  required_raise_rt: number | null;
  annual_increase_amt: number | null;
  target_annual_increase_amt: number | null;
  warnings: string[];
}
export interface RenegotiationData {
  month: string;
  closed: boolean;
  items: RenegotiationRow[];
  total: number;
  page: number;
  size: number;
  summary: {
    people_count: number;
    weighted_margin_rt: number | null;
    annual_increase_amt: number;
    target_annual_increase_amt: number;
  };
  warnings: string[];
}
export const listCostRenegotiations = (filters: RenegotiationFilters) =>
  readData<RenegotiationData>(
    `/api/costs/renegotiations?${new URLSearchParams(
      Object.entries(filters)
        .filter(([, value]) => value !== "")
        .map(([key, value]) => [key, String(value)]),
    )}`,
  );
export interface AllocationData {
  month: string;
  closed: boolean;
  items: {
    dev_id: number | null;
    prj_id: number | null;
    asgn_id: number | null;
    aloc_ty_cd: string;
    mm_qty: number | null;
    lbr_cst_amt: number;
    drct_expns_amt: number;
    sls_amt: number | null;
    bill_untprc_amt: number | null;
  }[];
  expenses: {
    expns_id: number;
    prj_id: number;
    aloc_ty_cd: string;
    expns_amt: number;
  }[];
  warnings: {
    dev_id: number | null;
    asgn_id: number | null;
    code: string;
    message: string;
  }[];
  unallocated: {
    dev_id: number;
    lbr_cst_amt: number;
    drct_expns_amt: number;
  }[];
}
export const getCostAllocation = (month: string, projectId?: string) =>
  readData<AllocationData>(
    `/api/costs/allocation?${new URLSearchParams({ month, ...(projectId ? { projectId } : {}) })}`,
  );
