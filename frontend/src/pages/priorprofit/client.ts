import { api, ApiFailure } from "../../shared/api";
import type { ApiResponse, ApiError } from "../../shared/types";

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
  let response: Response;
  try {
    response = await fetch("/api/uploads/prior-profit/validate", {
      method: "POST",
      body,
    });
  } catch {
    throw new ApiFailure("서버에 연결할 수 없습니다.", null, null);
  }
  let result: ApiResponse<PriorProfitUploadResult> | ApiError;
  try {
    result = await response.json();
  } catch {
    throw new ApiFailure(
      "서버 응답을 확인할 수 없습니다.",
      response.status,
      response.headers.get("X-Request-Id"),
    );
  }
  if (!response.ok || result.success !== true) {
    const error = result as ApiError;
    throw new ApiFailure(
      error.message ?? "서버 오류가 발생했습니다.",
      response.status,
      error.requestId,
      error.field,
      error.code,
    );
  }
  return (result as ApiResponse<PriorProfitUploadResult>).data!;
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
