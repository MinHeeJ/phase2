import { readData } from "../../shared/api";
export { downloadBusinessPlan as downloadProfit } from "../businessplan/client";
export type ProfitMetric =
  | "revenue"
  | "cost"
  | "gross_profit"
  | "operating_profit"
  | "net_profit"
  | "project_labor_cost"
  | "idle_cost"
  | "direct_expenses"
  | "sga_input"
  | "indirect_labor_cost"
  | "rnd_cost"
  | "sga"
  | "previous_adjustment"
  | "non_operating_income"
  | "non_operating_expenses"
  | "corporate_tax"
  | "allocation_revenue_reference"
  | "operating_margin"
  | "net_margin";
export type ProfitValues = Partial<Record<ProfitMetric, number | null>>;
export interface ProfitFile {
  file_name: string;
  content_type: string;
  content_base64: string;
}
export interface ProfitMonthValues extends ProfitValues {
  month: string;
  closed: boolean;
  source: string;
  warning: string;
  warnings: string[];
}
export interface ProfitMonth extends ProfitMonthValues {
  cumulative: ProfitValues;
  previous: ProfitValues;
  operating_export_file: ProfitFile;
  net_export_file: ProfitFile;
}
export interface ProfitAnnual {
  year: number;
  months: ProfitMonth[];
  previous_months: ProfitMonthValues[];
}
export interface ProfitComposition {
  code: string;
  name: string;
  revenue: number | null;
  cost: number | null;
  gross_profit: number | null;
  share: number | null;
  margin: number | null;
}
export type ProfitPeriod = "MONTH" | "QUARTER" | "YTD";
export interface ProfitStructure extends ProfitValues {
  previous: ProfitValues;
  difference: ProfitValues;
  growth_rate: ProfitValues;
  months: ProfitMonthValues[];
  composition_available: boolean;
  cost_breakdown: ProfitComposition[];
  revenue_by_contract: ProfitComposition[];
  revenue_by_customer: ProfitComposition[];
  revenue_by_part: ProfitComposition[];
  warning: string;
  warnings: string[];
  contribution_difference: number | null;
  contribution_note: string;
  export_file: ProfitFile;
}
export const getMonthlyFinanceProfit = (year: number, signal?: AbortSignal) =>
  readData<ProfitAnnual>(
    `/api/finance/profit/monthly?${new URLSearchParams({ year: String(year) })}`,
    signal,
  );
export const getFinanceProfitStructure = (
  year: number,
  month: number,
  period: ProfitPeriod,
  signal?: AbortSignal,
) =>
  readData<ProfitStructure>(
    `/api/finance/profit/structure?${new URLSearchParams({ year: String(year), month: String(month), period })}`,
    signal,
  );
export const profitRows: readonly {
  key: ProfitMetric;
  label: string;
  cost?: boolean;
}[] = [
  { key: "revenue", label: "매출" },
  { key: "project_labor_cost", label: "배분 원가", cost: true },
  { key: "idle_cost", label: "대기 원가", cost: true },
  { key: "direct_expenses", label: "직접 경비", cost: true },
  { key: "cost", label: "매출원가", cost: true },
  { key: "gross_profit", label: "매출총이익" },
  { key: "sga_input", label: "판관비 입력", cost: true },
  { key: "indirect_labor_cost", label: "간접 인건비", cost: true },
  { key: "rnd_cost", label: "연구개발비", cost: true },
  { key: "sga", label: "판관비", cost: true },
  { key: "previous_adjustment", label: "전월 조정" },
  { key: "operating_profit", label: "영업이익" },
  { key: "non_operating_income", label: "영업외수익" },
  { key: "non_operating_expenses", label: "영업외비용", cost: true },
  { key: "corporate_tax", label: "법인세(월할)", cost: true },
  { key: "net_profit", label: "당기순이익" },
  { key: "net_margin", label: "순이익률" },
];
export const profitFormat = (
  value: number | null | undefined,
  unit = 1,
  rate = false,
) =>
  value == null
    ? "–"
    : rate
      ? `${value.toFixed(1)}%`
      : (value / unit).toLocaleString("ko-KR", { maximumFractionDigits: 1 });
