import { api, readData } from "../../shared/api";

export interface BillingRow {
  bill_id: number | null;
  bill_stg_id: number | null;
  virtual_plan: boolean;
  bill_no: string | null;
  prj_id: number;
  project_name: string;
  customer_id: number | null;
  customer_name: string | null;
  contract_type: "TK" | "MO" | "DP";
  atrb_ym: string | null;
  bill_dt: string | null;
  sply_amt: number;
  vat_amt: number;
  tot_amt: number;
  bill_stts_cd: "PLAN" | "DRAFT" | "CONFIRM" | "CANCEL";
  txinv_no: string | null;
  txinv_dt: string | null;
  rcpt_plan_dt: string | null;
  received_amt: number;
  adjusted_total_amt: number;
  receivable_amt: number;
  reminder_stage: number | null;
  bill_odr_no?: number;
  amnd_yn?: string;
  rmrk_desc?: string | null;
}
export interface BillingDetailLine {
  asgn_id: number;
  dev_id: number;
  cntrct_dev_id: number;
  display_name: string;
  work_ym: string;
  mm_qty: number | null;
  source_mm_qty: number | null;
  untprc_amt: number | null;
  work_day_qty: number | null;
  work_tm_qty: number | null;
  ovtm_tm_qty: number | null;
  ovtm_amt: number;
  bill_amt: number;
  rmrk_desc: string | null;
}
export interface BillingDetail {
  billing: BillingRow;
  details: BillingDetailLine[];
  receipts: {
    receipt_id: number;
    rcpt_dt: string;
    rcpt_amt: number;
    rmrk_desc: string | null;
  }[];
  amendments: BillingRow[];
  original_bill_id: number | null;
  change_history: {
    description: string;
    changed_at: string;
    user_id: string | null;
  }[];
  warnings: string[];
}
export interface BillingUpdate {
  bill_dt?: string | null;
  sply_amt?: number;
  vat_amt?: number;
  rcpt_plan_dt?: string | null;
  txinv_no?: string | null;
  txinv_dt?: string | null;
  rmrk_desc?: string;
  recalculate?: boolean;
  details?: { asgn_id: number; ovtm_amt: number }[];
}
export interface BillingCreate
  extends Omit<BillingUpdate, "rmrk_desc" | "recalculate"> {
  project_id: number;
  stage_id?: number;
  month?: string;
  additional?: boolean;
  bill_odr_no?: number;
}
export interface BillingFilters {
  year: number;
  status: string;
  type: string;
  customerId?: number;
  q: string;
  page: number;
  size: 20 | 50 | 100;
  sort: string;
}
export interface BillingPage {
  items: BillingRow[];
  total: number;
  page: number;
  size: number;
}
export interface BillingPreview {
  details: BillingDetailLine[];
  sply_amt: number;
  suggested_vat_amt: number;
  warnings: string[];
  missing_price_assignments: number[];
}
export interface BillingSummary {
  annual_confirmed_supply_amt: number;
  current_month_confirmed_supply_amt: number;
  confirmed_receivable_amt: number;
  overdue_amt: number;
  planned_supply_amt: number;
  draft_supply_amt: number;
}
export const billingDefaults = (year: number): BillingFilters => ({
  year,
  status: "",
  type: "",
  q: "",
  page: 0,
  size: 50,
  sort: "bill_dt,desc",
});
export const billingQuery = (input: object) =>
  new URLSearchParams(
    Object.entries(input)
      .filter(([, value]) => value != null && value !== "")
      .map(([key, value]) => [key, String(value)]),
  ).toString();
export const listBillings = (filters: BillingFilters) =>
  readData<BillingPage>(`/api/billings?${billingQuery(filters)}`);
export const getBilling = (id: number) =>
  readData<BillingDetail>(`/api/billings/${encodeURIComponent(id)}`);
export const createBilling = async (input: BillingCreate) =>
  (
    await api<BillingDetail>("/api/billings", {
      method: "POST",
      body: JSON.stringify(input),
    })
  ).data!;
export const updateBilling = async (id: number, input: BillingUpdate) =>
  (
    await api<BillingDetail>(`/api/billings/${encodeURIComponent(id)}`, {
      method: "PUT",
      body: JSON.stringify(input),
    })
  ).data!;
export const previewBilling = (
  projectId: number,
  stageId?: number,
  month?: string,
) =>
  readData<BillingPreview>(
    `/api/billings/preview?${billingQuery({ projectId, stageId, month })}`,
  );
export const getBillingSummary = (year: number) =>
  readData<BillingSummary>(`/api/billings/summary?year=${year}`);
