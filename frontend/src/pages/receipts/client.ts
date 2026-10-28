import { api, readData } from "../../shared/api";

export interface BillingReceipt {
  receipt_id: number;
  rcpt_dt: string;
  rcpt_amt: number;
  rmrk_desc: string | null;
}
export interface BillingReceiptInput {
  rcpt_dt: string;
  rcpt_amt: number;
  rmrk_desc: string | null;
}
export interface ReceiptBillingRow {
  bill_id: number;
  bill_no: string;
  bill_stts_cd: "PLAN" | "DRAFT" | "CONFIRM" | "CANCEL";
  sply_amt: number;
  vat_amt: number;
  tot_amt: number;
  adjusted_total_amt: number;
  received_amt: number;
  receivable_amt: number;
  atrb_ym: string | null;
  bill_dt: string | null;
}
export interface BillingReceiptDetail {
  billing: ReceiptBillingRow;
  details: unknown[];
  receipts: BillingReceipt[];
  amendments: unknown[];
  original_bill_id: number | null;
  change_history: {
    description: string;
    changed_at: string;
    user_id: string | null;
  }[];
  warnings: string[];
}
const path = (billId: number) => `/api/billings/${billId}/receipts`;
const payload = (input: BillingReceiptInput) =>
  JSON.stringify({
    rcpt_dt: input.rcpt_dt,
    rcpt_amt: input.rcpt_amt,
    rmrk_desc: input.rmrk_desc,
  });
export const listBillingReceipts = (billId: number) =>
  readData<BillingReceiptDetail>(path(billId));
export const createBillingReceipt = async (
  billId: number,
  input: BillingReceiptInput,
) =>
  (
    await api<BillingReceiptDetail>(path(billId), {
      method: "POST",
      body: payload(input),
    })
  ).data as BillingReceiptDetail;
export const updateBillingReceipt = async (
  billId: number,
  receiptId: number,
  input: BillingReceiptInput,
) =>
  (
    await api<BillingReceiptDetail>(`${path(billId)}/${receiptId}`, {
      method: "PUT",
      body: payload(input),
    })
  ).data as BillingReceiptDetail;
export const deleteBillingReceipt = async (billId: number, receiptId: number) =>
  (
    await api<BillingReceiptDetail>(`${path(billId)}/${receiptId}`, {
      method: "DELETE",
    })
  ).data as BillingReceiptDetail;
