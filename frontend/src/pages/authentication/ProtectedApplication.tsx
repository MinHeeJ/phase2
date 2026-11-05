import { useEffect, useState, type ReactNode } from "react";
import { Navigate, useLocation } from "react-router-dom";
import { api, ApiFailure, readData } from "../../shared/api";
import { setIdentity, type CurrentUser } from "../../shared/types";
import type { MenuNode } from "../shared/api";
import { isScreenId } from "../shared/registry";
import { Button, Skeleton } from "../../shared/Controls";

export interface PermissionRow {
  menuId: number;
  screenId: string;
  inq: boolean;
  reg: boolean;
  mdfcn: boolean;
  del: boolean;
  upld: boolean;
  dwnld: boolean;
  cnfrm: boolean;
}
export function flatten(menus: MenuNode[]): MenuNode[] {
  return menus.flatMap((m) => (m.screenId ? [m] : flatten(m.children)));
}
export async function loginDestination() {
  const menus = await readData<MenuNode[]>("/api/navigation");
  const screens = flatten(menus);
  return (
    screens.find((m) => m.screenId === "UI-16")?.screenId ??
    screens[0]?.screenId ??
    null
  );
}
export function ProtectedApplication({ children }: { children: ReactNode }) {
  const location = useLocation();
  const [state, setState] = useState<
    "loading" | "ready" | "anonymous" | "password" | "error"
  >("loading");
  const [menus, setMenus] = useState<MenuNode[]>([]);
  const [revision, setRevision] = useState(0);
  const [version, setVersion] = useState("");
  useEffect(() => {
    let active = true;
    const controller = new AbortController();
    const refresh = async () => {
      try {
        const identity = await readData<CurrentUser>(
          "/api/auth/me",
          controller.signal,
        );
        const permission = await readData<{ permissions: PermissionRow[] }>(
          "/api/me/permissions",
          controller.signal,
        );
        const navigation = await readData<MenuNode[]>(
          "/api/navigation",
          controller.signal,
        );
        if (!active) return;
        const grants: Record<string, Record<string, boolean>> = {};
        for (const row of permission.permissions)
          grants[row.screenId] = {
            inq: row.inq,
            reg: row.reg,
            mdfcn: row.mdfcn,
            del: row.del,
            upld: row.upld,
            dwnld: row.dwnld,
            cnfrm: row.cnfrm,
          };
        setIdentity(identity, grants);
        setMenus(navigation);
        setVersion(JSON.stringify([identity, permission, navigation]));
        setState("ready");
      } catch (error) {
        if (!active) return;
        setIdentity(null);
        setMenus([]);
        setState(
          error instanceof ApiFailure &&
            error.code === "PASSWORD_CHANGE_REQUIRED"
            ? "password"
            : error instanceof ApiFailure && error.status === 401
              ? "anonymous"
              : "error",
        );
      }
    };
    void refresh();
    const ended = () => {
      setIdentity(null);
      setMenus([]);
      setState("anonymous");
    };
    const changed = () => {
      setIdentity(null);
      setState("loading");
      setRevision((n) => n + 1);
    };
    const timer = window.setInterval(refresh, 30000);
    window.addEventListener("sicost:session-ended", ended);
    window.addEventListener("sicost:permissions-changed", changed);
    window.addEventListener("focus", changed);
    return () => {
      active = false;
      controller.abort();
      window.clearInterval(timer);
      window.removeEventListener("sicost:session-ended", ended);
      window.removeEventListener("sicost:permissions-changed", changed);
      window.removeEventListener("focus", changed);
    };
  }, [revision]);
  useEffect(() => {
    if (state !== "ready") return;
    let last = 0;
    const activity = (event: Event) => {
      if (!event.isTrusted || Date.now() - last < 30000) return;
      last = Date.now();
      const screenId = location.pathname.slice(1);
      if (!/^UI-\d{2}$/.test(screenId)) return;
      void api("/api/auth/activity", {
        method: "POST",
        body: JSON.stringify({
          activityType: event.type === "keydown" ? "KEY" : "CLICK",
          screenId,
        }),
      }).catch(() => undefined);
    };
    window.addEventListener("keydown", activity);
    window.addEventListener("click", activity);
    return () => {
      window.removeEventListener("keydown", activity);
      window.removeEventListener("click", activity);
    };
  }, [state, location.pathname]);
  if (state === "loading") return <Skeleton label="세션 확인 중" />;
  if (state === "anonymous") return <Navigate to="/UI-22" replace />;
  if (state === "password") return <Navigate to="/UI-22#password" replace />;
  if (state === "error")
    return (
      <section data-testid="session-error">
        <p role="alert">세션을 확인할 수 없습니다.</p>
        <Button
          data-testid="session-retry"
          onClick={() => setRevision((n) => n + 1)}
        >
          다시 시도
        </Button>
      </section>
    );
  const screens = flatten(menus);
  const first = screens.find((m) => m.screenId === "UI-16") ?? screens[0];
  if (!first)
    return (
      <section data-testid="no-permitted-menu">
        <p>접근 가능한 메뉴가 없습니다.</p>
        <Button
          data-testid="no-menu-logout"
          onClick={() => {
            void api("/api/auth/logout", { method: "POST" }).then(() => {
              setIdentity(null);
              setState("anonymous");
            });
          }}
        >
          로그아웃
        </Button>
      </section>
    );
  if (location.pathname === "/")
    return <Navigate to={`/${first.screenId}`} replace />;
  const id =
    location.pathname === "/UI-32"
      ? "UI-02"
      : location.pathname === "/UI-33" &&
          location.state?.workspace !== "candidates"
        ? "UI-01"
        : location.pathname.slice(1);
  if (
    isScreenId(id) &&
    id !== "UI-34" &&
    !screens.some((m) => m.screenId === id)
  )
    return (
      <section data-testid="screen-permission-denied">
        <p role="alert">이 화면에 접근할 권한이 없습니다.</p>
        <a data-testid="permission-first-menu" href={`/${first.screenId}`}>
          허용 화면으로
        </a>
      </section>
    );
  return (
    <div key={version} data-testid="authenticated-application">
      {children}
    </div>
  );
}
