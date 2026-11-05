import { api, ApiFailure, readData } from "../../shared/api";

export async function opportunityRequest<T>(path: string, init: RequestInit) {
  const csrf = await readData<{ headerName: string; csrfToken: string }>(
    "/api/auth/csrf",
  );
  if (csrf.headerName !== "X-CSRF-TOKEN" || !csrf.csrfToken) {
    throw new ApiFailure("보안 정보를 확인할 수 없습니다.", 403, null);
  }
  return (
    await api<T>(path, {
      ...init,
      credentials: "same-origin",
      headers: { ...init.headers, "X-CSRF-TOKEN": csrf.csrfToken },
    })
  ).data!;
}
