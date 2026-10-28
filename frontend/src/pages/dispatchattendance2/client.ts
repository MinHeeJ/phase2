import { api, ApiFailure, readData } from "../../shared/api";
import type { ApiError, ApiResponse } from "../../shared/types";
import type { UploadResult } from "../apiops/client";
import type { PersonnelIssue, PersonnelPage } from "../uploadsops/client";

export type AttendanceUploadResult = UploadResult;
export interface AttendanceUploadApplication {
  upload_id: number;
  form_type: "B.3";
  applied_count: number;
  message?: string;
}
export interface AttendanceUploadPreview {
  row: number;
  severity: "NORMAL" | "WARNING" | "ERROR";
  attendance: {
    mng_no: string;
    dev_nm: string;
    asgn_id: number | null;
    work_day_qty: number | null;
    rglr_tm_qty: number | null;
    ovtm_tm_qty: number | null;
    mth_work_day_qty: number | null;
    calculated_mm: number | null;
    assignment_mm: number | null;
    price_available: boolean;
  };
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
  let result: ApiResponse<UploadResult> | ApiError;
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
  return (result as ApiResponse<UploadResult>).data!;
}
export async function applyDispatchAttendanceUpload(uploadId: number) {
  return (
    await api<AttendanceUploadApplication>(
      "/api/uploads/dispatch-attendance/apply",
      {
        method: "POST",
        body: JSON.stringify({ upload_id: uploadId, confirm: true }),
      },
    )
  ).data!;
}
export async function revalidateDispatchAttendanceUpload(uploadId: number) {
  return (
    await api<UploadResult>(`/api/uploads/${uploadId}/revalidate`, {
      method: "POST",
    })
  ).data!;
}
export const listAttendanceUploadRows = (
  uploadId: number,
  severity: string,
  page: number,
) =>
  readData<PersonnelPage<AttendanceUploadPreview>>(
    `/api/uploads/${uploadId}/rows?severity=${encodeURIComponent(severity)}&page=${page}&size=50&sort=row,asc`,
  );
export const listAttendanceUploadIssues = (
  uploadId: number,
  severity: string,
  page: number,
) =>
  readData<PersonnelPage<PersonnelIssue>>(
    `/api/uploads/${uploadId}/issues?severity=${encodeURIComponent(severity)}&page=${page}&size=50&sort=row,asc`,
  );
