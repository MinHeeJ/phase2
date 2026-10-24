import { api, ApiFailure } from "../../shared/api";
import type { ApiResponse, ApiError } from "../../shared/types";

export interface UploadIssue {
  severity: "ERROR" | "WARN";
  rowNo: number;
  column: string;
  rawValue: string | null;
  message: string;
  fix?: { type: "MAPPING"; group: string };
}
export interface UploadValidationResult {
  reviewId?: number;
  month?: string | null;
  overwriteNotice?: string | null;
  canApply?: boolean;
  fileName: string;
  upldId?: string;
  token?: string;
  totalRows: number;
  okRows: number;
  errorRows: number;
  warningRows: number;
  items: UploadIssue[];
}
export interface UploadApplyResult {
  upldId: string;
  appliedRows: number;
}
const enc = encodeURIComponent;
export const uploadTemplatePath = (form: string) =>
  `/api/uploads/templates/${enc(form)}`;
export const uploadErrorPath = (id: string) =>
  `/api/uploads/${enc(id)}/error-file`;
export async function createUploadValidation(
  form: string,
  file: File,
  month?: string,
): Promise<UploadValidationResult> {
  const body = new FormData();
  body.append("file", file);
  const personnel = form === "B.8" || form === "B.1";
  if (personnel) body.append("form_type", form);
  if (month) body.append("month", month);
  let response: Response;
  try {
    response = await fetch(
      personnel
        ? "/api/uploads/validate"
        : `/api/uploads/${enc(form)}/validate`,
      {
        method: "POST",
        body,
      },
    );
  } catch {
    throw new ApiFailure("서버에 연결할 수 없습니다.", null, null);
  }
  let result: ApiResponse<UploadValidationResult> | ApiError;
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
  if (personnel)
    return reviewResult(
      (result as unknown as ApiResponse<PersonnelValidation>).data!,
      file.name,
    );
  return (result as ApiResponse<UploadValidationResult>).data!;
}
export interface PersonnelValidation {
  upload_id: number;
  form_type: "B.8" | "B.1";
  month: string | null;
  row_count: number;
  error_count: number;
  warning_count: number;
  can_apply: boolean;
  overwrite_notice: string | null;
}
export interface PersonnelIssue {
  row: number;
  column: string;
  severity: "ERROR" | "WARNING";
  message: string;
  raw_value: string | null;
  normalized_value: string | null;
  mapping_allowed: boolean;
}
export interface PersonnelPreview {
  row: number;
  severity: string;
  developer?: Record<string, unknown>;
  salary?: Record<string, unknown>;
}
export interface PersonnelPage<T> {
  items: T[];
  total: number;
  page: number;
  size: number;
}
const mappingGroups: Record<string, string> = {
  직원구분: "EMP_TYPE",
  구분: "EMP_TYPE",
  직위: "POSITION",
  "업무 구분": "WORK_TYPE",
  등급: "GRADE",
  "주 직무": "JOB",
  "보조 직무": "JOB",
  스킬: "SKILL",
  상태: "WORK_STATUS",
};
async function reviewResult(
  data: PersonnelValidation,
  fileName: string,
): Promise<UploadValidationResult> {
  const items: PersonnelIssue[] = [];
  let page = 0,
    total = 0;
  do {
    const response = (
      await api<PersonnelPage<PersonnelIssue>>(
        `/api/uploads/${data.upload_id}/issues?page=${page}&size=100&sort=row,asc`,
      )
    ).data!;
    items.push(...response.items);
    total = response.total;
    page++;
  } while (items.length < total);
  return {
    fileName,
    reviewId: data.upload_id,
    upldId: String(data.upload_id),
    month: data.month,
    overwriteNotice: data.overwrite_notice,
    canApply: data.can_apply,
    totalRows: data.row_count,
    okRows: data.row_count - data.error_count,
    errorRows: data.error_count,
    warningRows: data.warning_count,
    items: items.map((i) => ({
      rowNo: i.row,
      severity: i.severity === "WARNING" ? "WARN" : "ERROR",
      column: i.column,
      rawValue: i.raw_value,
      message: i.message,
      ...(i.mapping_allowed && mappingGroups[i.column]
        ? { fix: { type: "MAPPING" as const, group: mappingGroups[i.column] } }
        : {}),
    })),
  };
}
export const revalidatePersonnelUpload = async (id: number, name: string) =>
  reviewResult(
    (
      await api<PersonnelValidation>(`/api/uploads/${id}/revalidate`, {
        method: "POST",
      })
    ).data!,
    name,
  );
export const applyPersonnelUpload = async (
  id: number,
): Promise<UploadApplyResult> => {
  const data = (
    await api<{ upload_id: number; applied_count: number }>(
      `/api/uploads/${id}/apply`,
      { method: "POST" },
    )
  ).data!;
  return { upldId: String(data.upload_id), appliedRows: data.applied_count };
};
export const listPersonnelUploadRows = async (
  id: number,
  page: number,
  severity: string,
) =>
  (
    await api<PersonnelPage<PersonnelPreview>>(
      `/api/uploads/${id}/rows?${new URLSearchParams({ page: String(page), size: "50", sort: "row,asc", severity })}`,
    )
  ).data!;
export const saveUploadRecheck = async (id: string) =>
  (
    await api<UploadValidationResult>(`/api/uploads/${enc(id)}/recheck`, {
      method: "POST",
    })
  ).data!;
export const saveValidatedUploadApply = async (token: string) =>
  (
    await api<UploadApplyResult>(`/api/uploads/validated/${enc(token)}/apply`, {
      method: "POST",
    })
  ).data!;
export async function downloadUploadFile(path: string, fallback: string) {
  let response: Response;
  try {
    response = await fetch(path);
  } catch {
    throw new ApiFailure("파일을 내려받을 수 없습니다.", null, null);
  }
  if (!response.ok) {
    let error: Partial<ApiError> = {};
    try {
      error = await response.json();
    } catch {
      /* Binary error without JSON. */
    }
    throw new ApiFailure(
      error.message ?? "파일을 내려받을 수 없습니다.",
      response.status,
      error.requestId ?? null,
    );
  }
  const blob = await response.blob();
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = fallback;
  document.body.append(anchor);
  anchor.click();
  anchor.remove();
  URL.revokeObjectURL(url);
}
export const uploadFailure = (e: unknown) =>
  e instanceof ApiFailure
    ? e
    : new ApiFailure("요청을 처리할 수 없습니다.", null, null);
