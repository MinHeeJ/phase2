import { api, readData } from "../../shared/api";

export interface BillingReceipt {
  receipt_id: number;
  rcpt_dt: string;
  rcpt_amt: number;
  rmrk_desc: string | null;
}
export interface ReceiptBilling {
  bill_id: number;
  bill_no: string;
  bill_stts_cd: "PLAN" | "DRAFT" | "CONFIRM" | "CANCEL";
  adjusted_total_amt: number;
  received_amt: number;
  receivable_amt: number;
  sply_amt: number;
  atrb_ym: string | null;
}
export interface BillingReceiptDetail {
  billing: ReceiptBilling;
  receipts: BillingReceipt[];
  details: Record<string, unknown>[];
  amendments: Record<string, unknown>[];
  original_bill_id: number | null;
  change_history: Record<string, unknown>[];
  warnings: string[];
}
export interface BillingReceiptInput {
  rcpt_dt?: string;
  rcpt_amt?: number;
  rmrk_desc?: string | null;
}
const base = (id: number) => `/api/billings/${id}/receipts`;
export const listBillingReceipts = (id: number) =>
  readData<BillingReceiptDetail>(base(id));
export const createBillingReceipt = async (
  id: number,
  input: BillingReceiptInput,
) =>
  (
    await api<BillingReceiptDetail>(base(id), {
      method: "POST",
      body: JSON.stringify(input),
    })
  ).data as BillingReceiptDetail;
export const updateBillingReceipt = async (
  id: number,
  receiptId: number,
  input: BillingReceiptInput,
) =>
  (
    await api<BillingReceiptDetail>(`${base(id)}/${receiptId}`, {
      method: "PUT",
      body: JSON.stringify(input),
    })
  ).data as BillingReceiptDetail;
export const deleteBillingReceipt = async (id: number, receiptId: number) =>
  (
    await api<BillingReceiptDetail>(`${base(id)}/${receiptId}`, {
      method: "DELETE",
    })
  ).data as BillingReceiptDetail;
