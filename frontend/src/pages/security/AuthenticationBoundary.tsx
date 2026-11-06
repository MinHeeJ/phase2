import { useEffect, useRef, useState, type ReactNode } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { ApiFailure } from "../../shared/api";
import { clearSecurity, setSecurity } from "../../shared/types";
import { get, write } from "./client";

interface Permissions {
  permissions: ({ screenId: string } & Record<string, boolean | string>)[];
}
interface Me {
  name: string;
  roles: string[];
  passwordAgeWarning: boolean;
}
export function AuthenticationBoundary({ children }: { children: ReactNode }) {
  const location = useLocation(),
    navigate = useNavigate();
  const [ready, setReady] = useState(false),
    [error, setError] = useState(""),
    [warning, setWarning] = useState(false),
    [version, setVersion] = useState(0);
  const permissionSignature = useRef("");
  useEffect(() => {
    let alive = true;
    setReady(false);
    setError("");
    Promise.all([
      get<Me>("/api/auth/me"),
      get<Permissions>("/api/me/permissions"),
      get<{ firstAllowedScreenId: string | null }>("/api/navigation"),
    ])
      .then(([me, permissions, navigation]) => {
        if (!alive) return;
        setSecurity(me.name, me.roles.map(Number), permissions.permissions);
        permissionSignature.current = JSON.stringify(permissions.permissions);
        const screen = location.pathname.slice(1);
        if (location.pathname === "/") {
          if (navigation.firstAllowedScreenId)
            navigate(`/${navigation.firstAllowedScreenId}`, { replace: true });
          else setError("접근 가능한 메뉴가 없습니다.");
          return;
        }
        const alias =
          screen === "UI-32"
            ? "UI-02"
            : screen === "UI-33" && location.state?.workspace !== "candidates"
              ? "UI-01"
              : screen;
        if (
          screen !== "UI-34" &&
          !permissions.permissions.some(
            (row) => row.screenId === alias && row.read === true,
          )
        ) {
          setError("접근 권한이 없습니다.");
          return;
        }
        setWarning(me.passwordAgeWarning);
        setReady(true);
      })
      .catch((failure: unknown) => {
        if (!alive) return;
        clearSecurity();
        if (failure instanceof ApiFailure && failure.status === 401)
          navigate("/UI-22", { replace: true });
        else
          setError(
            failure instanceof Error
              ? failure.message
              : "권한 조회에 실패했습니다.",
          );
      });
    return () => {
      alive = false;
    };
  }, [location.pathname, location.state?.workspace, navigate, version]);
  useEffect(() => {
    const expired = () => {
      clearSecurity();
      setReady(false);
      navigate("/UI-22", { replace: true });
    };
    const denied = () => {
      setReady(false);
      setVersion((value) => value + 1);
    };
    window.addEventListener("sicost-session-expired", expired);
    window.addEventListener("sicost:permissions-changed", denied);
    return () => {
      window.removeEventListener("sicost-session-expired", expired);
      window.removeEventListener("sicost:permissions-changed", denied);
    };
  }, [navigate]);
  useEffect(() => {
    const timer = window.setInterval(() => {
      void get<Permissions>("/api/me/permissions")
        .then((result) => {
          if (
            JSON.stringify(result.permissions) !== permissionSignature.current
          )
            setVersion((value) => value + 1);
        })
        .catch((failure) => {
          if (failure instanceof ApiFailure && failure.status === 401) {
            clearSecurity();
            setReady(false);
            navigate("/UI-22", { replace: true });
          }
        });
    }, 30000);
    return () => window.clearInterval(timer);
  }, [navigate]);
  useEffect(() => {
    let lastActivity = 0;
    const activity = (event: Event) => {
      if (!event.isTrusted || !ready || Date.now() - lastActivity < 30000)
        return;
      lastActivity = Date.now();
      void write("/api/auth/activity", "POST", {
        activityType: event.type === "keydown" ? "KEY" : "CLICK",
        screenId: location.pathname.slice(1),
      }).catch(() => {});
    };
    window.addEventListener("keydown", activity);
    window.addEventListener("click", activity);
    return () => {
      window.removeEventListener("keydown", activity);
      window.removeEventListener("click", activity);
    };
  }, [ready, location.pathname]);
  if (error)
    return (
      <main data-testid="permission-denied-screen">
        <p role="alert">{error}</p>
        <button
          data-testid="permission-retry-button"
          onClick={() => setVersion((value) => value + 1)}
        >
          다시 확인
        </button>
        <button
          data-testid="permission-logout-button"
          onClick={() =>
            void write("/api/auth/logout", "POST").finally(() => {
              clearSecurity();
              navigate("/UI-22");
            })
          }
        >
          로그아웃
        </button>
      </main>
    );
  if (!ready)
    return (
      <main data-testid="authentication-loading-screen" role="status">
        로그인·권한 확인 중…
      </main>
    );
  return (
    <>
      {warning && (
        <p role="status">
          비밀번호 변경 후 90일이 지났습니다. 비밀번호를 변경해 주세요.
        </p>
      )}
      {children}
    </>
  );
}
