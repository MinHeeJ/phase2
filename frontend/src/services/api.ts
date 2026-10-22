import type { ApiError, ApiResponse } from "./types";

export class ApiRequestError extends Error {
  constructor(
    message: string,
    public readonly requestId: string | null,
    public readonly field: string | null = null,
    public readonly code: string | null = null,
    public readonly status: number | null = null,
  ) {
    super(message);
    this.name = "ApiRequestError";
  }
}
function safeRequestId(value: unknown): string | null {
  return typeof value === "string" && /^[A-Za-z0-9_-]{1,64}$/.test(value)
    ? value
    : null;
}
export async function api<T>(
  path: string,
  options: RequestInit = {},
): Promise<T> {
  if (!path.startsWith("/api/") || path.includes("://") || path.includes("#"))
    throw new Error("상대 API 경로가 필요합니다.");
  let response: Response;
  try {
    response = await fetch(path, {
      ...options,
      headers: {
        ...(options.body ? { "Content-Type": "application/json" } : {}),
        ...options.headers,
      },
    });
  } catch {
    throw new ApiRequestError(
      "서버에 연결할 수 없습니다. 입력한 내용은 유지됩니다.",
      null,
    );
  }
  const headerId = safeRequestId(response.headers.get("X-Request-Id"));
  let payload: ApiResponse<T> | ApiError;
  try {
    payload = await response.json();
  } catch {
    throw new ApiRequestError(
      "서버 응답을 확인할 수 없습니다.",
      headerId,
      null,
      null,
      response.status,
    );
  }
  if (!response.ok || payload.success !== true) {
    const error = payload as ApiError;
    throw new ApiRequestError(
      typeof error.message === "string"
        ? error.message
        : "요청을 처리하지 못했습니다.",
      safeRequestId(error.requestId) ?? headerId,
      typeof error.field === "string" ? error.field : null,
      typeof error.code === "string" ? error.code : null,
      response.status,
    );
  }
  return (payload as ApiResponse<T>).data as T;
}
