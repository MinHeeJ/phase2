import { api, readData } from "../../shared/api";
import type { UploadResult } from "../apiops/client";
import type { PersonnelIssue, PersonnelPage } from "../uploadsops/client";

export interface AttendancePreview {
  row: number;
  severity: "NORMAL" | "WARNING" | "ERROR";
  attendance: {
    asgn_id: number;
    dev_id: number;
    work_day_qty: number | null;
    rglr_tm_qty: number | null;
    ovtm_tm_qty: number;
    mth_work_day_qty: number;
    calculated_mm: number | null;
    assignment_mm: number | null;
    price_available: boolean;
    warnings: string[];
  } | null;
}
export interface AttendanceApplied {
  upload_id: number;
  form_type: "B.3";
  applied_count: number;
  message?: string;
}
export async function validateDispatchAttendanceUpload(
  file: File,
  month: string,
  projectId: number,
): Promise<UploadResult> {
  const body = new FormData();
  body.append("file", file);
  body.append("month", month);
  body.append("projectId", String(projectId));
  return (
    await api<UploadResult>("/api/uploads/dispatch-attendance/validate", {
      method: "POST",
      body,
    })
  ).data;
}
export async function applyDispatchAttendanceUpload(
  uploadId: number,
): Promise<AttendanceApplied> {
  return (
    await api<AttendanceApplied>("/api/uploads/dispatch-attendance/apply", {
      method: "POST",
      body: JSON.stringify({ upload_id: uploadId, confirm: true }),
    })
  ).data!;
}
export async function revalidateDispatchAttendanceUpload(
  uploadId: number,
): Promise<UploadResult> {
  return (
    await api<UploadResult>(`/api/uploads/${uploadId}/revalidate`, {
      method: "POST",
    })
  ).data!;
}
export const listDispatchAttendanceUploadRows = (
  uploadId: number,
  severity: string,
  page: number,
  signal?: AbortSignal,
) =>
  readData<PersonnelPage<AttendancePreview>>(
    `/api/uploads/${uploadId}/rows?severity=${encodeURIComponent(severity)}&page=${page}&size=50&sort=row,asc`,
    signal,
  );
export const listDispatchAttendanceUploadIssues = (
  uploadId: number,
  severity: string,
  page: number,
  signal?: AbortSignal,
) =>
  readData<PersonnelPage<PersonnelIssue>>(
    `/api/uploads/${uploadId}/issues?severity=${encodeURIComponent(severity)}&page=${page}&size=50&sort=row,asc`,
    signal,
  );
