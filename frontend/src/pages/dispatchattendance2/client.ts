import { api, ApiFailure, readData } from "../../shared/api";
import type { PersonnelIssue, PersonnelPage } from "../uploadsops/client";

export interface DispatchAttendanceValidation {
  upload_id: number;
  form_type: "B.3";
  month: string;
  row_count: number;
  error_count: number;
  warning_count: number;
  can_apply: boolean;
  overwrite_notice: string | null;
}
export interface DispatchAttendancePreview {
  row: number;
  severity: "NORMAL" | "WARNING" | "ERROR";
  attendance: {
    asgn_id: number;
    dev_id: number;
    work_day_qty: number | null;
    rglr_tm_qty: number | null;
    ovtm_tm_qty: number;
    mth_work_day_qty: number;
  } | null;
  calculated_mm: number | null;
  assignment_mm: number | null;
  warnings: string[];
}
export interface DispatchAttendanceApplied {
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
  let result;
  try {
    result = await response.json();
  } catch {
    throw new ApiFailure(
      "서버 응답을 확인할 수 없습니다.",
      response.status,
      response.headers.get("X-Request-Id"),
    );
  }
  if (!response.ok || result.success !== true)
    throw new ApiFailure(
      result.message ?? "검사에 실패했습니다.",
      response.status,
      result.requestId,
      result.field,
      result.code,
    );
  return result.data as DispatchAttendanceValidation;
}
export const applyDispatchAttendanceUpload = async (uploadId: number) =>
  (
    await api<DispatchAttendanceApplied>(
      "/api/uploads/dispatch-attendance/apply",
      {
        method: "POST",
        body: JSON.stringify({ upload_id: uploadId, confirm: true }),
      },
    )
  ).data!;
export const revalidateDispatchAttendanceUpload = async (uploadId: number) =>
  (
    await api<DispatchAttendanceValidation>(
      `/api/uploads/${uploadId}/revalidate`,
      { method: "POST" },
    )
  ).data!;
export const getDispatchAttendanceInspection = (uploadId: number) =>
  readData<DispatchAttendanceValidation>(`/api/uploads/${uploadId}`);
export const listDispatchAttendanceIssues = (uploadId: number, page = 0) =>
  readData<PersonnelPage<PersonnelIssue>>(
    `/api/uploads/${uploadId}/issues?${new URLSearchParams({ page: String(page), size: "50", sort: "row,asc" })}`,
  );
export const listDispatchAttendanceRows = (uploadId: number, page = 0) =>
  readData<PersonnelPage<DispatchAttendancePreview>>(
    `/api/uploads/${uploadId}/rows?${new URLSearchParams({ page: String(page), size: "50", sort: "row,asc" })}`,
  );
