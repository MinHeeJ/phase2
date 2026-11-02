import { api, readData } from "../../shared/api";

export interface PaymentOrder {
  pay_odr_no: number;
  planned_date: string;
  paid?: boolean;
}
export interface PaymentOrders {
  items: PaymentOrder[];
  month_status: "OPEN" | "CLOSED";
}
const path = (month: string) =>
  `/api/finance/payment-orders?month=${encodeURIComponent(month)}`;
export const getPaymentOrders = (month: string) =>
  readData<PaymentOrders>(path(month));
export const savePaymentOrders = async (month: string, items: PaymentOrder[]) =>
  (
    await api<PaymentOrders>(path(month), {
      method: "PUT",
      body: JSON.stringify({
        items: items.map(({ pay_odr_no, planned_date }) => ({
          pay_odr_no,
          planned_date,
        })),
      }),
    })
  ).data!;
