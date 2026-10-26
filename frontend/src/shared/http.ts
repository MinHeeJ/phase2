export interface ApiResponse<T = never> {
  success: true;
  meta: Record<string, unknown>;
  data?: T;
}
export interface QueryResponse<T> {
  success: true;
  meta: Record<string, unknown>;
  data: T;
}
export interface ApiErrorBody {
  success: false;
  meta: Record<string, unknown>;
  code: string;
  message: string;
  field?: string | null;
  requestId?: string;
}
export class ApiFailure extends Error {
  constructor(
    public readonly body: ApiErrorBody,
    public readonly status: number,
  ) {
    super(body.message);
  }
  get field() {
    return this.body.field;
  }
  get requestId() {
    return this.body.requestId;
  }
}
export class NetworkFailure extends Error {
  constructor() {
    super("서버에 연결할 수 없습니다.");
  }
}
export async function request<T>(
  path: string,
  init: RequestInit = {},
): Promise<ApiResponse<T>> {
  if (!path.startsWith("/api/") || path.startsWith("//"))
    throw new Error("상대 API 경로가 필요합니다.");
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
    throw new NetworkFailure();
  }
  let body: unknown;
  try {
    body = await response.json();
  } catch {
    throw new NetworkFailure();
  }
  if (!response.ok || (body as { success?: boolean })?.success === false) {
    const error = body as Partial<ApiErrorBody>;
    throw new ApiFailure(
      {
        success: false,
        meta: {},
        code: error.code ?? "INTERNAL_ERROR",
        message: error.message ?? "처리 중 오류가 발생했습니다.",
        field: error.field,
        requestId:
          error.requestId || response.headers.get("X-Request-Id") || undefined,
      },
      response.status,
    );
  }
  return body as ApiResponse<T>;
}
// Query operations have exactly two approved envelopes; health/writes do not.
export async function query<T>(path: string): Promise<T> {
  const outer = await request<QueryResponse<T>>(path);
  if (outer.data?.success !== true || !("data" in outer.data))
    throw new NetworkFailure();
  return outer.data.data;
}
export async function mutate(
  path: string,
  method: "POST" | "PUT" | "PATCH",
  data: unknown,
): Promise<void> {
  await request(path, { method, body: JSON.stringify(data) });
}
