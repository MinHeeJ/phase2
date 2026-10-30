import { api, readData } from "../../shared/api";
export type CashFlowMode = "PLAN" | "ACTUAL" | "BOTH";
export interface CashFlowMonth {
  month: string;
  closed: boolean;
  planned_opening: number | null;
  planned_inflow: number;
  planned_outflow: number;
  planned_closing: number | null;
  actual_opening: number | null;
  actual_inflow: number | null;
  actual_outflow: number | null;
  actual_closing: number | null;
  forecast_closing: number | null;
  warning: string | null;
  categories?: {
    code: string;
    planned_inflow: number;
    planned_outflow: number;
    actual_inflow: number | null;
    actual_outflow: number | null;
  }[];
}
export interface CashFlowFile {
  file_name: string;
  content_type: string;
  content_base64: string;
}
export interface CashFlow {
  year: number;
  mode: CashFlowMode;
  months: CashFlowMonth[];
  initial_month: string;
  first_risk_month: string | null;
  export_file: CashFlowFile | null;
}
export interface CashOpening {
  month: string;
  amount: number | null;
}
export interface CashInflow {
  inflw_id?: number;
  month: string;
  amount: number;
  inflw_se_cd: "MISC" | "LOAN" | "ETC";
  inflw_item_nm: string;
  planned_date: string | null;
  actual_date: string | null;
  remark: string | null;
}
export const getFinanceCashflow = (year: number, mode: CashFlowMode) =>
  readData<CashFlow>(`/api/finance/cashflow?year=${year}&mode=${mode}`);
export const getCashOpening = (month: string) =>
  readData<CashOpening>(
    `/api/finance/cashflow/opening?month=${encodeURIComponent(month)}`,
  );
export const saveCashOpening = async (input: CashOpening) =>
  (
    await api<CashOpening>(
      `/api/finance/cashflow/opening?month=${encodeURIComponent(input.month)}`,
      { method: "PUT", body: JSON.stringify(input) },
    )
  ).data!;
export const getFinanceOtherInflows = () =>
  readData<{ items: CashInflow[]; total: number }>(
    "/api/finance/cashflow/other-inflows",
  );
const inflowBody = (input: CashInflow) => ({
  month: input.month,
  amount: input.amount,
  inflw_se_cd: input.inflw_se_cd,
  inflw_item_nm: input.inflw_item_nm,
  planned_date: input.planned_date,
  actual_date: input.actual_date,
  remark: input.remark,
});
export const postFinanceOtherInflows = async (input: CashInflow) =>
  (
    await api<CashInflow>("/api/finance/cashflow/other-inflows", {
      method: "POST",
      body: JSON.stringify(inflowBody(input)),
    })
  ).data!;
export const putFinanceOtherInflows = async (input: CashInflow) =>
  (
    await api<CashInflow>("/api/finance/cashflow/other-inflows", {
      method: "PUT",
      body: JSON.stringify({ ...inflowBody(input), inflw_id: input.inflw_id }),
    })
  ).data!;
export function downloadCashflow(file: CashFlowFile) {
  const decoded = atob(file.content_base64),
    bytes = Uint8Array.from(decoded, (c) => c.charCodeAt(0));
  const url = URL.createObjectURL(
    new Blob([bytes], { type: file.content_type }),
  );
  const link = document.createElement("a");
  link.href = url;
  link.download = file.file_name;
  link.click();
  URL.revokeObjectURL(url);
}
