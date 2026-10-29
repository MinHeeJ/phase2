import { api, readData } from "../../shared/api";

export interface BillingReceiptInput {
  rcpt_dt: string;
  rcpt_amt: number;
  rmrk_desc: string | null;
}
export interface BillingReceipt extends BillingReceiptInput {
  receipt_id: number;
}
export interface ReceiptBillingRow {
  bill_id: number;
  bill_no: string | null;
  bill_stts_cd: "PLAN" | "DRAFT" | "CONFIRM" | "CANCEL";
  sply_amt: number;
  vat_amt: number;
  tot_amt: number;
  adjusted_total_amt: number;
  received_amt: number;
  receivable_amt: number;
  atrb_ym: string | null;
  rcpt_plan_dt: string | null;
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
const path = (billId: number) => `/api/billings/${billId}/receipts`;
export const listBillingReceipts = (billId: number) =>
  readData<ReceiptBillingDetail>(path(billId));
export const createBillingReceipt = async (
  billId: number,
  input: BillingReceiptInput,
) =>
  (
    await api<ReceiptBillingDetail>(path(billId), {
      method: "POST",
      body: JSON.stringify(input),
    })
  ).data!;
export const updateBillingReceipt = async (
  billId: number,
  receiptId: number,
  input: BillingReceiptInput,
) =>
  (
    await api<ReceiptBillingDetail>(`${path(billId)}/${receiptId}`, {
      method: "PUT",
      body: JSON.stringify(input),
    })
  ).data!;
export const deleteBillingReceipt = async (billId: number, receiptId: number) =>
  (
    await api<ReceiptBillingDetail>(`${path(billId)}/${receiptId}`, {
      method: "DELETE",
    })
  ).data!;
