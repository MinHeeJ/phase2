import { api, ApiFailure } from "../../shared/api";

export interface UploadIssue {
  severity: "ERROR" | "WARN";
  rowNo: number;
  column: string;
  rawValue: string | null;
  message: string;
  fix?: { type: "MAPPING"; group: "PARTNER_KIND" | "INDUSTRY" | "BANK" } | null;
}
export interface UploadValidation {
  fileName: string;
  totalRows: number;
  okRows: number;
  errorRows: number;
  warningRows: number;
  items: UploadIssue[];
  upldId?: string;
  token?: string;
}
export interface UploadApplied {
  upldId: string;
  appliedRows: number;
}
const enc = encodeURIComponent;
export const uploadPaths = {
  template: (form: string) => `/api/uploads/templates/${enc(form)}`,
  validate: (form: string) => `/api/uploads/${enc(form)}/validate`,
  recheck: (id: string) => `/api/uploads/${enc(id)}/recheck`,
  apply: (token: string) => `/api/uploads/validated/${enc(token)}/apply`,
  errors: (id: string) => `/api/uploads/${enc(id)}/error-file`,
};
export function uploadFileError(file: File): string | null {
  if (!/\.xlsx$/i.test(file.name)) return "xlsx 파일만 올려 주세요.";
  if (file.size > 10 * 1024 * 1024) return "파일은 10MB 이하로 올려 주세요.";
  if (!file.size) return "데이터가 없습니다.";
  return null;
}
async function fileResponse(path: string, init: RequestInit = {}) {
  if (!path.startsWith("/api/uploads/"))
    throw new Error("상대 업로드 API 경로가 필요합니다.");
  let response: Response;
  try {
    response = await fetch(path, init);
  } catch {
    throw new ApiFailure("서버에 연결할 수 없습니다.", null, null);
  }
  if (!response.ok) {
    let error: {
      message?: string;
      field?: string;
      code?: string;
      requestId?: string;
    } = {};
    try {
      error = await response.json();
    } catch {
      /* Preserve a safe transport error. */
    }
    throw new ApiFailure(
      error.message ?? "파일 요청을 처리할 수 없습니다.",
      response.status,
      error.requestId ?? response.headers.get("X-Request-Id"),
      error.field ?? null,
      error.code ?? null,
    );
  }
  return response;
}
export async function createUploadValidation(
  form: string,
  file: File,
): Promise<UploadValidation> {
  const error = uploadFileError(file);
  if (error) throw new ApiFailure(error, null, null, "file");
  const body = new FormData();
  body.append("file", file);
  const response = await fileResponse(uploadPaths.validate(form), {
    method: "POST",
    body,
  });
  const result = await response.json();
  if (result.success !== true || !result.data)
    throw new ApiFailure(
      "검사 응답을 확인할 수 없습니다.",
      response.status,
      response.headers.get("X-Request-Id"),
    );
  return result.data;
}
export const saveUploadRecheck = async (id: string) =>
  (await api<UploadValidation>(uploadPaths.recheck(id), { method: "POST" }))
    .data as UploadValidation;
export const saveValidatedUploadApply = async (token: string) =>
  (await api<UploadApplied>(uploadPaths.apply(token), { method: "POST" }))
    .data as UploadApplied;
export async function downloadUploadFile(path: string) {
  const response = await fileResponse(path);
  if (
    !response.headers
      .get("Content-Type")
      ?.includes(
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
      )
  )
    throw new ApiFailure(
      "엑셀 파일 응답이 아닙니다.",
      response.status,
      response.headers.get("X-Request-Id"),
    );
  const disposition = response.headers.get("Content-Disposition") ?? "";
  const encoded = /filename\*=UTF-8''([^;]+)/i.exec(disposition)?.[1];
  const plain = /filename="([^"]+)"/i.exec(disposition)?.[1];
  let filename = "업로드.xlsx";
  try {
    filename = encoded ? decodeURIComponent(encoded) : (plain ?? filename);
  } catch {
    /* Use the safe fallback. */
  }
  filename = filename.replace(/[\\/\x00-\x1f]/g, "_");
  const url = URL.createObjectURL(await response.blob());
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = filename;
  document.body.append(anchor);
  anchor.click();
  anchor.remove();
  URL.revokeObjectURL(url);
}
export const getUploadTemplate = (form: string) =>
  downloadUploadFile(uploadPaths.template(form));
export const getUploadErrorFile = (id: string) =>
  downloadUploadFile(uploadPaths.errors(id));
