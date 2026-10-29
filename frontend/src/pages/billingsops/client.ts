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
  contract_type: string;
  atrb_ym: string | null;
  bill_dt: string | null;
  sply_amt: number;
  vat_amt: number;
  tot_amt: number;
  bill_stts_cd: string;
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
  details: BillingLine[];
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
export interface BillingPreview {
  project_id: number;
  bill_stg_id: number | null;
  month: string | null;
  sply_amt: number;
  suggested_vat_amt: number;
  rcpt_plan_dt: string | null;
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
export interface BillingPage<T> {
  items: T[];
  total: number;
  page: number;
  size: number;
}
export interface GapPage extends BillingPage<BillingGap> {
  month: string;
  total_difference_amt: number;
}
export interface OverduePage extends BillingPage<BillingOverdue> {
  customer_totals: {
    customer_id: number;
    customer_name: string;
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
  cash_receipts_vat_included_amt: number;
  planned_supply_amt: number;
  draft_supply_amt: number;
}
export interface EditableBilling {
  bill_dt: string | null;
  sply_amt: number;
  vat_amt: number;
  rcpt_plan_dt: string | null;
  txinv_no: string | null;
  txinv_dt: string | null;
}
export type BillingFilters = Record<string, string | number>;
export function query(values: BillingFilters) {
  const q = new URLSearchParams();
  Object.entries(values).forEach(([key, value]) => {
    if (value !== "") q.set(key, String(value));
  });
  return q.toString();
}
export const listBillingGaps = (filters: BillingFilters) =>
  readData<GapPage>(`/api/billings/gaps?${query(filters)}`);
export const listBillingOverdue = (filters: BillingFilters) =>
  readData<OverduePage>(`/api/billings/overdue?${query(filters)}`);
export const getBillingSummary = (year: number) =>
  readData<BillingSummary>(`/api/billings/summary?year=${year}`);
export const getBillingDetail = (id: number) =>
  readData<BillingDetail>(`/api/billings/${id}`);
export const previewBilling = (
  projectId: number,
  month: string,
  stageId?: number,
) =>
  readData<BillingPreview>(
    `/api/billings/preview?${query({ projectId, ...(stageId ? { stageId } : { month }) })}`,
  );
export const confirmBilling = async (id: number) =>
  (await api<BillingDetail>(`/api/billings/${id}/confirm`, { method: "POST" }))
    .data!;
export const cancelBilling = async (id: number, reason: string) =>
  (
    await api<BillingDetail>(`/api/billings/${id}/cancel`, {
      method: "POST",
      body: JSON.stringify({ reason }),
    })
  ).data!;
export const createBillingAmendment = async (
  id: number,
  input: EditableBilling & { reason: string },
) =>
  (
    await api<BillingDetail>(`/api/billings/${id}/amendments`, {
      method: "POST",
      body: JSON.stringify(input),
    })
  ).data!;
export const saveBilling = async (
  id: number | null,
  input: EditableBilling | (EditableBilling & { project_id: number }),
) =>
  (
    await api<BillingDetail>(
      id == null ? "/api/billings" : `/api/billings/${id}`,
      { method: id == null ? "POST" : "PUT", body: JSON.stringify(input) },
    )
  ).data!;
