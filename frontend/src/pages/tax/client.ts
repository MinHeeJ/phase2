import { api, readData } from "../../shared/api";

export interface TaxMonth {
  month: string;
  closed: boolean;
  amount: number | null;
  warning: string | null;
}
export interface TaxHistory {
  before_amount: number | null;
  after_amount: number;
  reason: string;
  changed_at: string;
}
export interface TaxPlan {
  year: number;
  annual_amount: number;
  reason: string;
  months: TaxMonth[];
  history: TaxHistory[];
  editable: boolean;
}
export interface TaxPayment {
  tax_pay_id?: number;
  tax_pay_se_cd: "FINAL" | "INTERIM";
  planned_date: string;
  planned_amount: number | null;
  actual_date: string | null;
  actual_amount: number | null;
  updated_at?: string;
}
export interface TaxSuggestion {
  tax_pay_se_cd: TaxPayment["tax_pay_se_cd"];
  suggested_date: string;
  suggested_amount: number | null;
}
export interface TaxPayments {
  year: number;
  payments: TaxPayment[];
  suggestions: TaxSuggestion[];
  locked_types: string[];
}
const path = (resource: string, year: number) =>
  `/api/finance/tax/${resource}?year=${encodeURIComponent(year)}`;
export const getTaxPlan = (year: number) =>
  readData<TaxPlan>(path("plan", year));
export const getTaxPayments = (year: number) =>
  readData<TaxPayments>(path("payments", year));
export const saveTaxPlan = async (
  year: number,
  annual_amount: number,
  reason: string,
) =>
  (
    await api<TaxPlan>(path("plan", year), {
      method: "PUT",
      body: JSON.stringify({ annual_amount, reason }),
    })
  ).data!;
export const saveTaxPayments = async (year: number, payments: TaxPayment[]) =>
  (
    await api<TaxPayments>(path("payments", year), {
      method: "PUT",
      body: JSON.stringify({
        payments: payments.map((row) => ({
          tax_pay_se_cd: row.tax_pay_se_cd,
          planned_date: row.planned_date,
          planned_amount: row.planned_amount,
          actual_date: row.actual_date,
          actual_amount: row.actual_amount,
          ...(row.updated_at ? { updated_at: row.updated_at } : {}),
        })),
      }),
    })
  ).data!;
