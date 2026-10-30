import { api, ApiFailure } from "../../shared/api";
import type { ApiResponse, ApiError } from "../../shared/types";
import type { FinanceEntryInput } from "../entries/client";

export interface FinanceEntriesPreview {
  row: number;
  severity: string;
  entry: FinanceEntryInput | null;
}
export interface FinanceEntriesUploadResult {
  upload_id: string;
  error_count: number;
  warning_count: number;
  items: {
    field: string;
    message: string;
    row: number;
    severity: string;
    raw_value?: string | null;
  }[];
  row_count: number;
  can_apply: boolean;
  applied_count: number;
  overwrite_count: number;
  rows: FinanceEntriesPreview[];
  overwrite_notice: string;
}
export async function validateFinanceEntriesUpload(
  file: File,
): Promise<FinanceEntriesUploadResult> {
  const body = new FormData();
  body.append("file", file);
  let response: Response;
  try {
    response = await fetch("/api/uploads/finance-entries/validate", {
      method: "POST",
      body,
    });
  } catch {
    throw new ApiFailure("서버에 연결할 수 없습니다.", null, null);
  }
  let result: ApiResponse<FinanceEntriesUploadResult> | ApiError;
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
  return (result as ApiResponse<FinanceEntriesUploadResult>).data!;
}
export const applyFinanceEntriesUpload = async (
  upload_id: string,
  confirm: boolean,
) =>
  (
    await api<FinanceEntriesUploadResult>(
      "/api/uploads/finance-entries/apply",
      {
        method: "POST",
        body: JSON.stringify({ upload_id, confirm }),
      },
    )
  ).data!;
