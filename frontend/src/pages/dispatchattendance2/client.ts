import { api, ApiFailure, readData } from "../../shared/api";
import type { ApiError, ApiResponse } from "../../shared/types";
import type { PersonnelIssue, PersonnelPage } from "../uploadsops/client";

export interface AttendanceUploadReview {
  upload_id: number;
  form_type: "B.3";
  month: string;
  row_count: number;
  error_count: number;
  warning_count: number;
  can_apply: boolean;
  overwrite_notice: string | null;
  issues_url: string;
  normalized_rows_available: boolean;
}
export interface AttendanceUploadPreview {
  row: number;
  severity: "NORMAL" | "WARNING" | "ERROR";
  management_number: string;
  name: string;
  attendance: {
    asgn_id: number;
    dev_id: number;
    work_day_qty: number | null;
    rglr_tm_qty: number | null;
    ovtm_tm_qty: number | null;
    mth_work_day_qty: number | null;
  } | null;
  calculated_mm: number | null;
  assignment_mm: number | null;
  price_available: boolean;
}
export interface AttendanceUploadApplied {
  upload_id: number;
  form_type: "B.3";
  applied_count: number;
  message: string;
}
export async function validateDispatchAttendanceUpload(
  file: File,
  month: string,
  projectId: number,
) {
  const body = new FormData();
  body.append("file", file);
  body.append("month", month);
  body.append("projectId", String(projectId));
  let response: Response;
  try {
    response = await fetch("/api/uploads/dispatch-attendance/validate", {
      method: "POST",
      body,
    });
  } catch {
    throw new ApiFailure("서버에 연결할 수 없습니다.", null, null);
  }
  let result: ApiResponse<AttendanceUploadReview> | ApiError;
  try {
    result = await response.json();
  } catch {
    throw new ApiFailure(
      "서버 응답을 확인할 수 없습니다.",
      response.status,
      response.headers.get("X-Request-Id"),
    );
  }
  if (!response.ok || !result.success) {
    const error = result as ApiError;
    throw new ApiFailure(
      error.message,
      response.status,
      error.requestId,
      error.field,
      error.code,
    );
  }
  return (result as ApiResponse<AttendanceUploadReview>).data!;
}
export const applyDispatchAttendanceUpload = async (uploadId: number) =>
  (
    await api<AttendanceUploadApplied>(
      "/api/uploads/dispatch-attendance/apply",
      {
        method: "POST",
        body: JSON.stringify({ upload_id: uploadId, confirm: true }),
      },
    )
  ).data!;
export const listAttendanceUploadRows = (
  id: number,
  page: number,
  severity: string,
) =>
  readData<PersonnelPage<AttendanceUploadPreview>>(
    `/api/uploads/${id}/rows?${new URLSearchParams({
      page: String(page),
      size: "50",
      sort: "row,asc",
      ...(severity ? { severity } : {}),
    })}`,
  );
export const listAttendanceUploadIssues = (id: number, page: number) =>
  readData<PersonnelPage<PersonnelIssue>>(
    `/api/uploads/${id}/issues?${new URLSearchParams({
      page: String(page),
      size: "50",
      sort: "row,asc",
    })}`,
  );
