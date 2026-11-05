import { api } from "../../shared/api";

export interface PriorProfitSnapshot {
  month: string;
  revenue: number;
  allocatedCost: number;
  idleCost: number;
  directExpense: number;
  sgaInput: number;
  indirectLabor: number;
  researchCost: number;
  adjustment: number;
  nonOperatingIncome: number;
  nonOperatingExpense: number;
  corporateTax: number;
  allocatedRevenue: number | null;
}
export interface PriorProfitUploadResult {
  upload_id: string;
  error_count: number;
  items: { field: string; message: string; row: number; severity: string }[];
  row_count: number;
  can_apply: boolean;
  applied_count: number;
  rows: { row: number; severity: string; profit: PriorProfitSnapshot | null }[];
  details: {
    row: number;
    severity: string;
    detail: { month: string; code: string; amount: number } | null;
  }[];
  overwrite_notice: string;
}
export async function validatePriorProfitUpload(
  file: File,
): Promise<PriorProfitUploadResult> {
  const body = new FormData();
  body.append("file", file);
  return (
    await api<PriorProfitUploadResult>("/api/uploads/prior-profit/validate", {
      method: "POST",
      body,
    })
  ).data;
}
export const applyPriorProfitUpload = async (
  upload_id: string,
  confirm: boolean,
) =>
  (
    await api<PriorProfitUploadResult>("/api/uploads/prior-profit/apply", {
      method: "POST",
      body: JSON.stringify({ upload_id, confirm }),
    })
  ).data!;
