import { api, ApiFailure } from "../../shared/api";
import { downloadPartnerFile } from "../partnersops/client";
export type UploadGroup = "PARTNER_KIND" | "INDUSTRY" | "BANK";
export interface UploadIssue {
  severity: "ERROR" | "WARN";
  rowNo: number;
  column: string;
  rawValue: string | null;
  message: string;
  fix?: { type: "MAPPING"; group: UploadGroup } | null;
}
interface UploadSummary {
  fileName: string;
  totalRows: number;
  okRows: number;
  errorRows: number;
  warningRows: number;
  items: UploadIssue[];
}
export type UploadResult = UploadSummary &
  ({ upldId: string; token?: never } | { token: string; upldId?: never });
export const MAX_UPLOAD_BYTES = 10 * 1024 * 1024;
export function checkUploadFile(file: File) {
  if (!/\.xlsx$/i.test(file.name)) return "xlsx 파일만 올릴 수 있습니다.";
  if (file.size > MAX_UPLOAD_BYTES) return "파일 크기는 10MB 이하여야 합니다.";
  return null;
}
export async function createUploadValidation(
  form: string,
  file: File,
): Promise<UploadResult> {
  const body = new FormData();
  body.append("file", file);
  let response: Response;
  try {
    response = await fetch(
      `/api/uploads/${encodeURIComponent(form)}/validate`,
      { method: "POST", body },
    );
  } catch {
    throw new ApiFailure(
      "서버에 연결할 수 없습니다. 검사 결과를 확인한 뒤 다시 올려 주세요.",
      null,
      null,
      null,
      null,
      true,
    );
  }
  let envelope;
  try {
    envelope = await response.json();
  } catch {
    throw new ApiFailure(
      "서버 응답을 확인할 수 없습니다.",
      response.status,
      response.headers.get("X-Request-Id"),
      null,
      null,
      true,
    );
  }
  if (!response.ok || envelope.success !== true)
    throw new ApiFailure(
      envelope.message ?? "파일을 검사할 수 없습니다.",
      response.status,
      envelope.requestId ?? response.headers.get("X-Request-Id"),
      envelope.field,
      envelope.code,
    );
  return envelope.data as UploadResult;
}
export const saveUploadRecheck = async (id: string) =>
  (
    await api<UploadResult>(`/api/uploads/${encodeURIComponent(id)}/recheck`, {
      method: "POST",
    })
  ).data as UploadResult;
export const saveValidatedUploadApply = async (token: string) =>
  (
    await api<{ upldId: string; appliedRows: number }>(
      `/api/uploads/validated/${encodeURIComponent(token)}/apply`,
      { method: "POST" },
    )
  ).data!;
export const getUploadTemplate = (form: string) =>
  downloadPartnerFile(`/api/uploads/templates/${encodeURIComponent(form)}`);
export const getUploadErrorFile = (id: string) =>
  downloadPartnerFile(`/api/uploads/${encodeURIComponent(id)}/error-file`);
