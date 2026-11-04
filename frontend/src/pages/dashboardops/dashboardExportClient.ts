import { ApiFailure } from "../../shared/api";

export const dashboardWorkbookType =
  "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

export async function downloadDashboard(month: string, signal?: AbortSignal) {
  let response: Response;
  try {
    response = await fetch(
      `/api/dashboard/export?${new URLSearchParams({ month })}`,
      {
        method: "GET",
        headers: { Accept: dashboardWorkbookType },
        signal,
      },
    );
  } catch (error) {
    if (signal?.aborted) throw error;
    throw new ApiFailure("서버에 연결할 수 없습니다.", null, null);
  }
  const requestId = response.headers.get("X-Request-Id");
  if (!response.ok) {
    let body: Record<string, unknown> = {};
    try {
      body = await response.json();
    } catch {
      /* Keep the HTTP failure visible, including non-JSON errors. */
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
    dashboardWorkbookType
  )
    throw new ApiFailure(
      "엑셀 파일 응답이 아닙니다.",
      response.status,
      requestId,
    );
  const blob = await response.blob();
  const signature = new Uint8Array(await blob.slice(0, 4).arrayBuffer());
  if (
    signature[0] !== 0x50 ||
    signature[1] !== 0x4b ||
    signature[2] !== 3 ||
    signature[3] !== 4
  )
    throw new ApiFailure(
      "엑셀 파일을 확인할 수 없습니다.",
      response.status,
      requestId,
    );
  if (signal?.aborted) throw new DOMException("다운로드 취소", "AbortError");
  const url = URL.createObjectURL(blob);
  try {
    const link = document.createElement("a");
    link.href = url;
    link.download = /^[0-9]{4}-(0[1-9]|1[0-2])$/.test(month)
      ? `dashboard_${month}.xlsx`
      : "dashboard.xlsx";
    link.click();
  } finally {
    URL.revokeObjectURL(url);
  }
}
