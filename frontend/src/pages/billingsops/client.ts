import { api, readData, ApiFailure } from "../../shared/api";

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
  asgn_id: number | null;
  dev_id: number | null;
  cntrct_dev_id: number | null;
  display_name: string | null;
  work_ym: string | null;
  mm_qty: number | null;
  source_mm_qty: number | null;
  untprc_amt: number | null;
  work_day_qty: number | null;
  work_tm_qty: number | null;
  ovtm_tm_qty: number | null;
  ovtm_amt: number;
  bill_amt: number;
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
  warnings: string[];
  change_history: {
    description: string;
    changed_at: string;
    user_id: string | null;
  }[];
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
}
export interface BillingPage<T> {
  items: T[];
  total: number;
  page: number;
  size: number;
}
export interface BillingGapPage extends BillingPage<BillingGap> {
  month: string;
  total_difference_amt: number;
}
export interface BillingOverduePage extends BillingPage<BillingOverdue> {
  stage_counts: { stage: number; count: number }[];
  customer_totals: {
    customer_id: number;
    customer_name: string;
    receivable_amt: number;
  }[];
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
export const billingQuery = (filters: BillingFilters) => {
  const query = new URLSearchParams();
  Object.entries(filters).forEach(([key, value]) => {
    if (value !== undefined && value !== "") query.set(key, String(value));
  });
  return query.toString();
};
export const getBillingSummary = (year: number) =>
  readData<BillingSummary>(`/api/billings/summary?year=${year}`);
export const listBillingGaps = (filters: BillingFilters) =>
  readData<BillingGapPage>(`/api/billings/gaps?${billingQuery(filters)}`);
export const listBillingOverdue = (filters: BillingFilters) =>
  readData<BillingOverduePage>(
    `/api/billings/overdue?${billingQuery(filters)}`,
  );
export const getBillingDetail = (id: number) =>
  readData<BillingDetail>(`/api/billings/${id}`);
export const previewBilling = (filters: BillingFilters) =>
  readData<BillingPreview>(`/api/billings/preview?${billingQuery(filters)}`);
export async function writeBilling(
  path: string,
  body?: unknown,
  method = "POST",
) {
  return (
    await api<BillingDetail>(path, {
      method,
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    })
  ).data!;
}
export const confirmBilling = (id: number) =>
  writeBilling(`/api/billings/${id}/confirm`);
export const cancelBilling = (id: number, reason: string) =>
  writeBilling(`/api/billings/${id}/cancel`, { reason });
export const createBillingAmendment = (id: number, body: unknown) =>
  writeBilling(`/api/billings/${id}/amendments`, body);
export async function downloadBilling(tab: string, filters: BillingFilters) {
  const { page, size, ...all } = filters;
  const path =
    tab === "list" ? "/api/billings/export" : `/api/billings/${tab}/export`;
  const response = await fetch(`${path}?${billingQuery(all)}`);
  if (!response.ok)
    throw new ApiFailure("다운로드에 실패했습니다.", response.status, null);
  const blob = await response.blob();
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = "청구.xlsx";
  link.click();
  URL.revokeObjectURL(url);
}
