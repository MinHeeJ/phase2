import { api, ApiFailure, readData } from "../../shared/api";

export async function opportunitySessionWrite<T>(
  path: string,
  init: RequestInit,
) {
  const csrf = await readData<{ headerName: string; csrfToken: string }>(
    "/api/auth/csrf",
  );
  if (
    csrf.headerName !== "X-CSRF-TOKEN" ||
    typeof csrf.csrfToken !== "string" ||
    !csrf.csrfToken ||
    csrf.csrfToken.length > 1024 ||
    /[\r\n]/.test(csrf.csrfToken)
  )
    throw new ApiFailure("보안 정보를 다시 확인하세요.", 403, null);
  return api<T>(path, {
    ...init,
    credentials: "same-origin",
    headers: { ...init.headers, [csrf.headerName]: csrf.csrfToken },
  });
}
