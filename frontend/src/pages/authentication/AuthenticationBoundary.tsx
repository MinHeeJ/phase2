import { useEffect, useState, type ReactNode } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { api, readData } from "../../shared/api";
import { currentUser, setPermissions } from "../../shared/types";

export function AuthenticationBoundary({ children }: { children: ReactNode }) {
  const navigate = useNavigate(),
    location = useLocation();
  const [ready, setReady] = useState(false),
    [error, setError] = useState("");
  useEffect(() => {
    let cancelled = false;
    const controller = new AbortController();
    const load = async () => {
      try {
        const [identity, access] = await Promise.all([
          readData<{ userId: number; name: string; roleIds: number[] }>(
            "/api/auth/me",
            controller.signal,
          ),
          readData<{ permissions: Record<string, boolean> }>(
            "/api/me/permissions",
            controller.signal,
          ),
        ]);
        if (cancelled) return;
        Object.assign(currentUser, identity);
        setPermissions(access.permissions);
        setReady(true);
        setError("");
      } catch (e) {
        if (cancelled) return;
        setReady(false);
        setPermissions({});
        const status = (e as { status?: number }).status;
        if (status === 401) navigate("/UI-22", { replace: true });
        else if (status === 403) navigate("/UI-22#password", { replace: true });
        else setError("인증 정보를 불러오지 못했습니다.");
      }
    };
    void load();
    const lost = () => {
      setReady(false);
      setPermissions({});
      Object.assign(currentUser, { userId: null, name: "", roleIds: [] });
      navigate("/UI-22", { replace: true });
    };
    const changed = () => {
      setReady(false);
      void load();
    };
    window.addEventListener("session-expired", lost);
    window.addEventListener("permissions-changed", changed);
    let lastActivity = 0;
    const activity = (event: Event) => {
      if (!event.isTrusted || Date.now() - lastActivity < 30000) return;
      lastActivity = Date.now();
      void readData<{ csrfToken: string }>("/api/auth/csrf")
        .then((csrf) =>
          api("/api/auth/activity", {
            method: "POST",
            headers: { "X-CSRF-TOKEN": csrf.csrfToken },
          }),
        )
        .catch(() => {});
    };
    window.addEventListener("pointerdown", activity);
    window.addEventListener("keydown", activity);
    return () => {
      cancelled = true;
      controller.abort();
      window.removeEventListener("session-expired", lost);
      window.removeEventListener("permissions-changed", changed);
      window.removeEventListener("pointerdown", activity);
      window.removeEventListener("keydown", activity);
    };
  }, [navigate]);
  if (error)
    return (
      <section data-testid="authentication-error">
        <p role="alert">{error}</p>
        <button
          data-testid="authentication-retry"
          onClick={() => navigate(location.pathname, { replace: true })}
        >
          다시 시도
        </button>
      </section>
    );
  return ready ? (
    <>{children}</>
  ) : (
    <section data-testid="authentication-loading" role="status">
      로그인 확인 중…
    </section>
  );
}
