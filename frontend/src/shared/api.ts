import type { ApiError, ApiResponse } from "./types";

export class ApiFailure extends Error {
  constructor(
    message: string,
    public readonly status: number | null,
    public readonly requestId: string | null,
    public readonly field: string | null = null,
    public readonly code: string | null = null,
    public readonly uncertainWrite = false,
  ) {
    super(message);
    this.name = "ApiFailure";
  }
}
export async function api<T = void>(
  path: string,
  init: RequestInit = {},
): Promise<ApiResponse<T>> {
  if (!path.startsWith("/api/") || path.startsWith("//"))
    throw new Error("상대 API 경로가 필요합니다.");
  const write = !["GET", "HEAD"].includes((init.method ?? "GET").toUpperCase());
  let response: Response;
  try {
    response = await fetch(path, {
      ...init,
      headers: {
        ...(init.body ? { "Content-Type": "application/json" } : {}),
        ...init.headers,
      },
    });
  } catch {
    throw new ApiFailure(
      "서버에 연결할 수 없습니다.",
      null,
      null,
      null,
      null,
      write,
    );
  }
  const headerId = response.headers.get("X-Request-Id");
  let body: ApiResponse<T> | ApiError;
  try {
    body = await response.json();
  } catch {
    throw new ApiFailure(
      "서버 응답을 확인할 수 없습니다.",
      response.status,
      headerId,
      null,
      null,
      write,
    );
  }
  if (!response.ok || body.success !== true) {
    const error = body as ApiError;
    throw new ApiFailure(
      typeof error.message === "string"
        ? error.message
        : "서버 오류가 발생했습니다.",
      response.status,
      typeof error.requestId === "string" ? error.requestId : headerId,
      error.field ?? null,
      error.code ?? null,
      write && response.status >= 500,
    );
  }
  return body as ApiResponse<T>;
}
export async function readData<T>(
  path: string,
  signal?: AbortSignal,
): Promise<T> {
  return (await api<T>(path, { signal })).data as T;
}
export function retryRecovery(
  error: ApiFailure,
  read: () => Promise<unknown>,
): Promise<unknown> {
  return read();
}
