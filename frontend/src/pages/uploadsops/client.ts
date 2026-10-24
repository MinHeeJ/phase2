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
): Promise<UploadValidationResult> {
  const body = new FormData();
  body.append("file", file);
  let response: Response;
  try {
    response = await fetch(`/api/uploads/${enc(form)}/validate`, {
      method: "POST",
      body,
    });
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
  return (result as ApiResponse<UploadValidationResult>).data!;
}
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
