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
}
export interface BillingLine {
  asgn_id: number;
  dev_id: number;
  cntrct_dev_id: number;
  display_name: string;
  work_ym: string | null;
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
export interface BillingReceipt {
  receipt_id: number;
  rcpt_dt: string;
  rcpt_amt: number;
  rmrk_desc: string | null;
}
export interface BillingDetail {
  billing: BillingRow;
  details: BillingLine[];
  receipts: BillingReceipt[];
  amendments: BillingRow[];
  original_bill_id: number | null;
  change_history: {
    description: string;
    changed_at: string;
    user_id: string | null;
  }[];
  warnings: string[];
}
export interface BillingPage {
  items: BillingRow[];
  total: number;
  page: number;
  size: number;
}
export interface BillingPreview {
  project_id: number;
  bill_stg_id: number | null;
  month: string | null;
  sply_amt: number;
  suggested_vat_amt: number;
  details: BillingLine[];
  warnings: string[];
  missing_price_assignments: number[];
}
export interface BillingGap {
  project_id: number;
  project_name: string;
  month: string;
  basis_supply_amt: number;
  confirmed_supply_amt: number;
  difference_amt: number;
  tolerance: number;
  judgment: string;
  explanation: string;
  attendance_missing: boolean;
  suspected_people: { name: string; management_number: string }[];
  billing_ids: number[];
}
export interface BillingGapResult {
  month: string;
  items: BillingGap[];
  total: number;
  page: number;
  size: number;
  total_difference_amt: number;
}
export interface BillingOverdue {
  billing: BillingRow;
  overdue_days: number | null;
  stage: number | null;
  due_status: string;
  recipient_roles: string[];
  next_notification_date: string | null;
  sent_history: { sent_at: string }[];
  sales_part: string | null;
}
export interface BillingOverdueResult {
  items: BillingOverdue[];
  total: number;
  page: number;
  size: number;
  customer_totals: {
    customer_id: number | null;
    customer_name: string | null;
    receivable_amt: number;
  }[];
  stage_counts: { stage: number; count: number }[];
}
export interface BillingSummary {
  year: number;
  annual_confirmed_supply_amt: number;
  current_month_confirmed_supply_amt: number;
  confirmed_receivable_amt: number;
  overdue_amt: number;
  planned_supply_amt: number;
  draft_supply_amt: number;
}
export type BillingFilters = Record<string, string | number | undefined>;
export function billingQuery(filters: BillingFilters) {
  const q = new URLSearchParams();
  Object.entries(filters).forEach(([key, value]) => {
    if (value !== undefined && value !== "") q.set(key, String(value));
  });
  return q.toString();
}
const path = (id: number) => `/api/billings/${encodeURIComponent(id)}`;
export const listBillingGaps = (filters: BillingFilters) =>
  readData<BillingGapResult>(`/api/billings/gaps?${billingQuery(filters)}`);
export const listBillingOverdue = (filters: BillingFilters) =>
  readData<BillingOverdueResult>(
    `/api/billings/overdue?${billingQuery(filters)}`,
  );
export const getBillingSummary = (year: number) =>
  readData<BillingSummary>(`/api/billings/summary?${billingQuery({ year })}`);
export const listBillings = (filters: BillingFilters) =>
  readData<BillingPage>(`/api/billings?${billingQuery(filters)}`);
export const getBilling = (id: number) => readData<BillingDetail>(path(id));
export const previewBilling = (filters: BillingFilters) =>
  readData<BillingPreview>(`/api/billings/preview?${billingQuery(filters)}`);
export async function billingWrite(
  id: number,
  action: "confirm" | "cancel" | "amendments",
  body?: unknown,
) {
  return (
    await api<BillingDetail>(`${path(id)}/${action}`, {
      method: "POST",
      body: body === undefined ? undefined : JSON.stringify(body),
    })
  ).data!;
}
export async function saveBilling(id: number | null, body: unknown) {
  return (
    await api<BillingDetail>(id === null ? "/api/billings" : path(id), {
      method: id === null ? "POST" : "PUT",
      body: JSON.stringify(body),
    })
  ).data!;
}
export async function saveBillingReceipt(
  id: number,
  receiptId: number | null,
  body: unknown,
  remove = false,
) {
  return api(
    `${path(id)}/receipts${receiptId === null ? "" : `/${encodeURIComponent(receiptId)}`}`,
    {
      method: remove ? "DELETE" : receiptId === null ? "POST" : "PUT",
      body: remove ? undefined : JSON.stringify(body),
    },
  );
}
