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
export interface BillingFilters {
  year: number;
  status: string;
  type: string;
  customerId: string;
  q: string;
  page: number;
  size: 20 | 50 | 100;
  sort: string;
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
  extends Omit<BillingUpdate, "recalculate" | "rmrk_desc"> {
  project_id: number;
  stage_id?: number;
  month?: string;
  additional?: boolean;
  bill_odr_no?: number;
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
  customerId: "",
  q: "",
  page: 0,
  size: 50,
  sort: "bill_dt,desc",
});
export function billingQuery(values: object) {
  return new URLSearchParams(
    Object.entries(values)
      .filter(([, value]) => value !== "" && value != null)
      .map(([key, value]) => [key, String(value)]),
  );
}
export const listBillings = (filters: BillingFilters, signal?: AbortSignal) =>
  readData<BillingPage>(`/api/billings?${billingQuery(filters)}`, signal);
export const getBilling = (id: number) =>
  readData<BillingDetail>(`/api/billings/${id}`);
export const createBilling = async (input: BillingCreate) =>
  (
    await api<BillingDetail>("/api/billings", {
      method: "POST",
      body: JSON.stringify(input),
    })
  ).data!;
export const updateBilling = async (id: number, input: BillingUpdate) =>
  (
    await api<BillingDetail>(`/api/billings/${id}`, {
      method: "PUT",
      body: JSON.stringify(input),
    })
  ).data!;
export const previewBilling = (identity: {
  projectId: number;
  stageId?: number;
  month?: string;
}) =>
  readData<BillingPreview>(`/api/billings/preview?${billingQuery(identity)}`);
export const getBillingSummary = (year: number, signal?: AbortSignal) =>
  readData<BillingSummary>(`/api/billings/summary?year=${year}`, signal);
export async function downloadBillings(filters: BillingFilters) {
  const { page: _page, size: _size, ...query } = filters;
  const artifact = await readData<{
    file_name: string;
    media_type: string;
    content_base64: string;
  }>(`/api/billings/export?${billingQuery(query)}`);
  const bytes = Uint8Array.from(atob(artifact.content_base64), (c) =>
    c.charCodeAt(0),
  );
  const url = URL.createObjectURL(
    new Blob([bytes], { type: artifact.media_type }),
  );
  try {
    const link = document.createElement("a");
    link.href = url;
    link.download = artifact.file_name;
    link.click();
  } finally {
    URL.revokeObjectURL(url);
  }
}
