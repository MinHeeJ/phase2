import { ApiFailure } from "../../shared/api";

export interface ProjectBoardExportFilter {
  light?: string;
  type?: string;
  part?: string | number;
}
const workbookType =
  "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

export async function downloadProjectBoard(filter: ProjectBoardExportFilter) {
  const query = new URLSearchParams();
  for (const key of ["light", "type", "part"] as const) {
    const value = filter[key];
    if (value !== undefined && value !== "") query.set(key, String(value));
  }
  let response: Response;
  try {
    response = await fetch(
      query.size
        ? `/api/project-board/export?${query.toString()}`
        : "/api/project-board/export",
    );
  } catch {
    throw new ApiFailure("서버에 연결할 수 없습니다.", null, null);
  }
  const requestId = response.headers.get("X-Request-Id");
  if (!response.ok) {
    let error: {
      message?: string;
      requestId?: string;
      field?: string;
      code?: string;
    };
    try {
      error = await response.json();
    } catch {
      throw new ApiFailure(
        "다운로드하지 못했습니다.",
        response.status,
        requestId,
      );
    }
    throw new ApiFailure(
      error.message ?? "다운로드하지 못했습니다.",
      response.status,
      error.requestId ?? requestId,
      error.field,
      error.code,
    );
  }
  if (response.headers.get("Content-Type")?.split(";")[0] !== workbookType)
    throw new ApiFailure(
      "엑셀 파일 응답이 아닙니다.",
      response.status,
      requestId,
    );
  const blob = await response.blob();
  const prefix = new Uint8Array(await blob.slice(0, 2).arrayBuffer());
  if (prefix[0] !== 80 || prefix[1] !== 75)
    throw new ApiFailure(
      "엑셀 파일을 확인할 수 없습니다.",
      response.status,
      requestId,
    );
  const encoded = response.headers
    .get("Content-Disposition")
    ?.match(/filename\*=UTF-8''([^;]+)/i)?.[1];
  let name: string;
  try {
    name = decodeURIComponent(encoded ?? "");
  } catch {
    throw new ApiFailure("파일명을 확인하세요.", response.status, requestId);
  }
  if (!/^프로젝트현황판_[0-9]{8}\.xlsx$/.test(name))
    throw new ApiFailure("파일명을 확인하세요.", response.status, requestId);
  const url = URL.createObjectURL(blob);
  try {
    const link = document.createElement("a");
    link.href = url;
    link.download = name;
    link.click();
  } finally {
    URL.revokeObjectURL(url);
  }
}
