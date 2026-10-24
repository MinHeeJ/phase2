import { ApiFailure, readData } from "../../shared/api";

export interface UploadResult {
  upload_id: number;
  form_type: "B.8" | "B.1";
  month: string | null;
  row_count: number;
  error_count: number;
  warning_count: number;
  can_apply: boolean;
  existing_salary_count: number;
  overwrite_notice: string | null;
  issues_url: string;
  normalized_rows_available: boolean;
}

export function getUploadResult(
  uploadId: number,
  signal?: AbortSignal,
): Promise<UploadResult> {
  if (!Number.isSafeInteger(uploadId) || uploadId < 1) {
    return Promise.reject(
      new ApiFailure(
        "검사 결과 번호를 확인하세요.",
        400,
        null,
        "uploadId",
        "INVALID_INPUT",
      ),
    );
  }
  return readData<UploadResult>(`/api/uploads/${uploadId}`, signal);
}
