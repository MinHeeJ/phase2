import { api, readData, ApiFailure } from "../../shared/api";

export type BillingRow = {
  bill_id: number | null;
  bill_stg_id: number | null;
  virtual_plan: boolean;
  bill_no: string | null;
  prj_id: number;
  project_name: string;
  customer_name: string | null;
  customer_id: number | null;
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
};
export type DetailLine = {
  asgn_id: number | null;
  display_name: string | null;
  work_ym: string | null;
  mm_qty: number | null;
  source_mm_qty: number | null;
  untprc_amt: number | null;
  work_day_qty: number | null;
  work_tm_qty: number | null;
  ovtm_amt: number;
  bill_amt: number;
};
export type Receipt = {
  receipt_id: number;
  rcpt_dt: string;
  rcpt_amt: number;
  rmrk_desc: string | null;
};
export type BillingDetail = {
  billing: BillingRow;
  details: DetailLine[];
  receipts: Receipt[];
  amendments: BillingRow[];
  original_bill_id: number | null;
  change_history: { description: string; changed_at: string }[];
  warnings: string[];
};
export type Preview = {
  project_id: number;
  bill_stg_id: number | null;
  month: string | null;
  sply_amt: number;
  suggested_vat_amt: number;
  details: DetailLine[];
  warnings: string[];
  missing_price_assignments: number[];
};
export type Gap = {
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
};
export type Overdue = {
  billing: BillingRow;
  overdue_days: number | null;
  stage: number | null;
  due_status: string;
  recipient_roles: string[];
  next_notification_date: string | null;
  sent_history: { sent_at: string }[];
  sales_part: string | null;
};
export type Page<T> = { items: T[]; total: number; page: number; size: number };
export type GapResult = Page<Gap> & {
  month: string;
  total_difference_amt: number;
};
export type OverdueResult = Page<Overdue> & {
  customer_totals: { customer_name: string; receivable_amt: number }[];
  stage_counts: { stage: number; count: number }[];
};
export type Summary = {
  year: number;
  annual_confirmed_supply_amt: number;
  current_month_confirmed_supply_amt: number;
  confirmed_receivable_amt: number;
  overdue_amt: number;
  planned_supply_amt: number;
  draft_supply_amt: number;
};
export type Filters = Record<string, string | number>;
export const query = (filters: Filters) =>
  new URLSearchParams(
    Object.entries(filters)
      .filter(([, v]) => v !== "")
      .map(([k, v]) => [k, String(v)]),
  ).toString();
export const listBillings = (f: Filters) =>
  readData<Page<BillingRow>>(`/api/billings?${query(f)}`);
export const listBillingGaps = (f: Filters) =>
  readData<GapResult>(`/api/billings/gaps?${query(f)}`);
export const listBillingOverdue = (f: Filters) =>
  readData<OverdueResult>(`/api/billings/overdue?${query(f)}`);
export const getBillingSummary = (year: number) =>
  readData<Summary>(`/api/billings/summary?year=${year}`);
export const getBilling = (id: number) =>
  readData<BillingDetail>(`/api/billings/${id}`);
export const previewBilling = (f: Filters) =>
  readData<Preview>(`/api/billings/preview?${query(f)}`);
export async function writeBilling(
  path: string,
  method: string,
  body?: unknown,
) {
  return (
    await api<BillingDetail>(path, {
      method,
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    })
  ).data!;
}
export const confirmBilling = (id: number) =>
  writeBilling(`/api/billings/${id}/confirm`, "POST");
export const cancelBilling = (id: number, reason: string) =>
  writeBilling(`/api/billings/${id}/cancel`, "POST", { reason });
export const createBillingAmendment = (id: number, body: unknown) =>
  writeBilling(`/api/billings/${id}/amendments`, "POST", body);
export async function downloadBilling(path: string, filters: Filters = {}) {
  const { page: _page, size: _size, ...full } = filters;
  const response = await fetch(`${path}?${query(full)}`);
  if (!response.ok)
    throw new ApiFailure(
      "내려받기에 실패했습니다.",
      response.status,
      response.headers.get("X-Request-Id"),
    );
  const blob = await response.blob();
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = "청구.xlsx";
  a.click();
  URL.revokeObjectURL(url);
}
export const billingFailure = (error: unknown) =>
  error instanceof ApiFailure
    ? error
    : new ApiFailure("처리하지 못했습니다.", null, null);
