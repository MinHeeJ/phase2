import { api, ApiFailure } from "../../shared/api";
import type { ApiResponse, ApiError } from "../../shared/types";

export interface BusinessPlanPreview {
  row: number;
  severity: string;
  plan: {
    month: string;
    partId: number;
    revenue: number;
    profit: number;
    utilization: number;
  } | null;
}
export interface BusinessPlanUploadResult {
  upload_id: string;
  error_count: number;
  items: { field: string; message: string; row: number; severity: string }[];
  row_count: number;
  can_apply: boolean;
  applied_count: number;
  rows: BusinessPlanPreview[];
  overwrite_notice: string;
}
export async function validateBusinessPlanUpload(
  file: File,
): Promise<BusinessPlanUploadResult> {
  const body = new FormData();
  body.append("file", file);
  let response: Response;
  try {
    response = await fetch("/api/uploads/business-plan/validate", {
      method: "POST",
      body,
    });
  } catch {
    throw new ApiFailure("서버에 연결할 수 없습니다.", null, null);
  }
  let result: ApiResponse<BusinessPlanUploadResult> | ApiError;
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
  return (result as ApiResponse<BusinessPlanUploadResult>).data!;
}
export const applyBusinessPlanUpload = async (
  upload_id: string,
  confirm: boolean,
) =>
  (
    await api<BusinessPlanUploadResult>("/api/uploads/business-plan/apply", {
      method: "POST",
      body: JSON.stringify({ upload_id, confirm }),
    })
  ).data!;
