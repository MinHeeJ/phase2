import { ApiFailure } from "../../shared/api";

export const costWorkbookType =
  "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

const filenames = {
  people: "개인원가수익.xlsx",
  projects: "프로젝트원가분석.xlsx",
  renegotiations: "단가재협상.xlsx",
} as const;

export async function downloadCostWorkbook(
  resource: keyof typeof filenames,
  query: URLSearchParams,
) {
  let response: Response;
  try {
    const path =
      resource === "people"
        ? `/api/costs/people?${query}`
        : resource === "projects"
          ? `/api/costs/projects?${query}`
          : `/api/costs/renegotiations?${query}`;
    response = await fetch(path, {
      method: "GET",
      credentials: "same-origin",
      cache: "no-store",
      headers: { Accept: costWorkbookType },
    });
  } catch {
    throw new ApiFailure("서버에 연결할 수 없습니다.", null, null);
  }
  const requestId = response.headers.get("X-Request-Id");
  if (!response.ok) {
    let body: Record<string, unknown> = {};
    try {
      const parsed: unknown = await response.json();
      if (parsed && typeof parsed === "object" && !Array.isArray(parsed)) {
        body = parsed as Record<string, unknown>;
      }
    } catch {
      // A proxy may return HTML instead of the application's JSON error.
    }
    throw new ApiFailure(
      typeof body.message === "string"
        ? body.message
        : "엑셀 다운로드에 실패했습니다.",
      response.status,
      typeof body.requestId === "string" ? body.requestId : requestId,
      typeof body.field === "string" ? body.field : null,
      typeof body.code === "string" ? body.code : null,
    );
  }
  if (
    response.headers.get("Content-Type")?.split(";")[0].trim() !==
    costWorkbookType
  ) {
    throw new ApiFailure(
      "엑셀 파일 응답이 아닙니다.",
      response.status,
      requestId,
    );
  }
  const blob = await response.blob();
  const signature = new Uint8Array(await blob.slice(0, 4).arrayBuffer());
  if (
    signature[0] !== 0x50 ||
    signature[1] !== 0x4b ||
    signature[2] !== 3 ||
    signature[3] !== 4
  ) {
    throw new ApiFailure(
      "엑셀 파일이 손상되었습니다.",
      response.status,
      requestId,
    );
  }
  const url = URL.createObjectURL(blob);
  try {
    const link = document.createElement("a");
    link.href = url;
    link.download = filenames[resource];
    link.click();
  } finally {
    URL.revokeObjectURL(url);
  }
}
