import { api, readData } from "../../shared/api";

export interface BillingReceiptInput {
  rcpt_dt: string;
  rcpt_amt: number | null;
  rmrk_desc: string | null;
}
export interface BillingReceipt extends BillingReceiptInput {
  receipt_id: number;
  rcpt_amt: number;
}
export interface ReceiptBillingRow {
  bill_id: number;
  bill_no: string;
  project_name: string;
  contract_type: "TK" | "MO" | "DP";
  atrb_ym: string | null;
  bill_dt: string | null;
  sply_amt: number;
  vat_amt: number;
  tot_amt: number;
  bill_stts_cd: "PLAN" | "DRAFT" | "CONFIRM" | "CANCEL";
  rcpt_plan_dt: string | null;
  received_amt: number;
  adjusted_total_amt: number;
  receivable_amt: number;
}
export interface ReceiptBillingDetail {
  billing: ReceiptBillingRow;
  details: unknown[];
  receipts: BillingReceipt[];
  amendments: ReceiptBillingRow[];
  original_bill_id: number | null;
  change_history: {
    description: string;
    changed_at: string;
    user_id: string | null;
  }[];
  warnings: string[];
}
const path = (billId: number) =>
  `/api/billings/${encodeURIComponent(billId)}/receipts`;
export const listBillingReceipts = (billId: number) =>
  readData<ReceiptBillingDetail>(path(billId));
const payload = (input: BillingReceiptInput) => ({
  rcpt_dt: input.rcpt_dt,
  rcpt_amt: input.rcpt_amt,
  rmrk_desc: input.rmrk_desc || null,
});
export const createBillingReceipt = async (
  billId: number,
  input: BillingReceiptInput,
) =>
  (
    await api<ReceiptBillingDetail>(path(billId), {
      method: "POST",
      body: JSON.stringify(payload(input)),
    })
  ).data!;
export const updateBillingReceipt = async (
  billId: number,
  receiptId: number,
  input: BillingReceiptInput,
) =>
  (
    await api<ReceiptBillingDetail>(
      `${path(billId)}/${encodeURIComponent(receiptId)}`,
      { method: "PUT", body: JSON.stringify(payload(input)) },
    )
  ).data!;
export const deleteBillingReceipt = async (billId: number, receiptId: number) =>
  (
    await api<ReceiptBillingDetail>(
      `${path(billId)}/${encodeURIComponent(receiptId)}`,
      { method: "DELETE" },
    )
  ).data!;
