import { api, readData, ApiFailure } from "../../shared/api";

export interface PaymentInput {
  pay_ym: string;
  pay_odr_no: number | null;
  pay_se_cd: string;
  rcvr_se_cd: string;
  dev_id: number | null;
  ptnr_id: number | null;
  recipient: string;
  sply_amt: number;
  vat_amt: number;
  ddct_amt: number;
  pay_plan_dt: string;
  bank: string | null;
  account: string | null;
  dpstr_nm: string | null;
  remark: string | null;
  updated_at?: string | null;
}
export interface Payment extends PaymentInput {
  pay_id: number;
  pay_amt: number;
  pay_dt: string | null;
  pay_stts_cd: "PLAN" | "PAID" | "UNPAID" | "CANCEL";
  pay_src_cd: string;
  src_rec_id: number | null;
  cry_pay_id: number | null;
  executed_amt: number;
  updated_at: string;
}
export interface PaymentList {
  items: Payment[];
  total: number;
  closed: boolean;
  warnings: string[];
  categories: { code: string; name: string }[];
  banks: { code: string; name: string }[];
  summary: {
    pay_odr_no: number | null;
    count: number;
    planned_amount: number;
    paid_amount: number;
    remaining_amount: number;
  }[];
}
const path = "/api/finance/payments";
export const listFinancePayments = (month: string) =>
  readData<PaymentList>(`${path}?${new URLSearchParams({ month })}`);
export const createFinancePayment = (input: PaymentInput) =>
  api<Payment>(path, { method: "POST", body: JSON.stringify(input) }).then(
    (r) => r.data!,
  );
export const updateFinancePayment = (id: number, input: PaymentInput) =>
  api<Payment>(`${path}/${id}`, {
    method: "PUT",
    body: JSON.stringify(input),
  }).then((r) => r.data!);
export const generateFinancePayments = (month: string) =>
  api<PaymentList>(`${path}/generate?${new URLSearchParams({ month })}`, {
    method: "POST",
  }).then((r) => r.data!);
export const completeFinancePayment = (
  id: number,
  input: { pay_dt: string; paid_amount: number; updated_at: string },
) =>
  api<Payment>(`${path}/${id}/complete`, {
    method: "POST",
    body: JSON.stringify(input),
  }).then((r) => r.data!);
export const reopenFinancePayment = (id: number, reason: string) =>
  api<Payment>(`${path}/${id}/reopen`, {
    method: "POST",
    body: JSON.stringify({ reason }),
  }).then((r) => r.data!);
export const carryFinancePayment = (id: number, month: string) =>
  api<Payment>(`${path}/${id}/carry`, {
    method: "POST",
    body: JSON.stringify({ month }),
  }).then((r) => r.data!);
export async function exportFinancePayments(month: string, ids: number[]) {
  const q = new URLSearchParams({ month });
  ids.forEach((id) => q.append("ids", String(id)));
  const response = await fetch(`${path}/export?${q}`);
  if (!response.ok) {
    const e = await response.json();
    throw new ApiFailure(
      e.message,
      response.status,
      e.requestId,
      e.field,
      e.code,
    );
  }
  if (
    !response.headers
      .get("Content-Type")
      ?.includes(
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
      )
  )
    throw new ApiFailure("엑셀 파일 응답이 아닙니다.", response.status, null);
  const blob = await response.blob();
  const prefix = new Uint8Array(await blob.slice(0, 2).arrayBuffer());
  if (prefix[0] !== 80 || prefix[1] !== 75)
    throw new ApiFailure(
      "엑셀 파일을 확인할 수 없습니다.",
      response.status,
      null,
    );
  const url = URL.createObjectURL(blob);
  try {
    const a = document.createElement("a");
    a.href = url;
    a.download = `지급-검토-${month}.xlsx`;
    a.click();
  } finally {
    URL.revokeObjectURL(url);
  }
}
// The payment-orders feature owns these approved operations.
export type { PaymentOrder } from "../paymentorders/client";
export {
  getPaymentOrders as listFinancePaymentOrders,
  savePaymentOrders as updateFinancePaymentOrders,
} from "../paymentorders/client";
