import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type ReactNode,
} from "react";
import { useLocation, useNavigate } from "react-router-dom";
import {
  Bell,
  Search,
  UserRound,
  X,
  PanelsTopLeft,
  ChevronDown,
  PanelLeftClose,
  PanelLeftOpen,
} from "lucide-react";
import { ResponsiveLayout } from "../../app/ResponsiveLayout";
import { Button, ConfirmDialog, Modal, Skeleton } from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { currentUser } from "../../shared/types";
import { menuScreens } from "./api";
import { MenuNavigation } from "./MenuNavigation";
import {
  PlaceholderScreen,
  SharedMobileGuard,
  useSharedMobile,
} from "./PlaceholderScreen";
import { isScreenId, locateScreen } from "./registry";
import { useSharedPreferences } from "./preferences";
import { useMenus } from "./useMenus";
import styles from "./SharedScreens.module.css";

export interface BusinessScreenBindings {
  onDirtyChange: (dirty: boolean) => void;
  name?: string;
  groupName?: string;
}
interface ScreenTab {
  id: string;
  viewed: number;
  dirty: boolean;
}
export function AppShell({
  renderBusiness,
}: {
  renderBusiness: (
    screenId: string,
    bindings: BusinessScreenBindings,
  ) => ReactNode;
}) {
  const { pathname, search: locationSearch } = useLocation();
  const navigate = useNavigate();
  const id =
    pathname === "/UI-32"
      ? "UI-02"
      : pathname === "/UI-33"
        ? "UI-01"
        : pathname.slice(1);
  const { menus, loading, error, retry } = useMenus();
  const preferences = useSharedPreferences();
  const mobile = useSharedMobile();
  const clock = useRef(0);
  const lastRoute = useRef(id);
  const [tabs, setTabs] = useState<ScreenTab[]>(() => [
    { id: "UI-16", viewed: 0, dirty: false },
    ...(id !== "UI-16" && isScreenId(id)
      ? [{ id, viewed: 1, dirty: false }]
      : []),
  ]);
  const [confirm, setConfirm] = useState<{
    kind: "overflow" | "dirty";
    candidate: string;
    next?: string;
  } | null>(null);
  const [search, setSearch] = useState(false);
  const [query, setQuery] = useState("");
  const [notifications, setNotifications] = useState(false);
  const [settings, setSettings] = useState(false);

  const developerRoute = useRef(pathname === "/UI-32" ? "/UI-32" : "/UI-02");
  if (id === "UI-02") developerRoute.current = pathname;
  const projectRoute = useRef(pathname === "/UI-33" ? "/UI-33" : "/UI-01");
  if (id === "UI-01") projectRoute.current = pathname + locationSearch;
  const navigationMenus = menus
    .map((group) => ({
      ...group,
      children: group.children.filter(
        (child) => child.screenId !== "UI-32" && child.screenId !== "UI-33",
      ),
    }))
    .filter(
      (group) => group.screenId !== "UI-32" && group.screenId !== "UI-33",
    );
  const navigateTo = useCallback(
    (next: string) => {
      navigate(
        next === "UI-02"
          ? developerRoute.current
          : next === "UI-01"
            ? projectRoute.current
            : `/${next}`,
      );
    },
    [navigate],
  );
  const open = (next: string) => {
    if (!isScreenId(next)) return;
    if (!tabs.some((tab) => tab.id === next) && tabs.length >= 8) {
      const candidate = tabs
        .filter((tab) => tab.id !== "UI-16")
        .reduce((old, tab) => (tab.viewed < old.viewed ? tab : old));
      setConfirm({ kind: "overflow", candidate: candidate.id, next });
      return;
    }
    navigateTo(next);
    setSearch(false);
  };
  useEffect(() => {
    if (pathname === "/") {
      navigate("/UI-16", { replace: true });
      return;
    }
    if (!isScreenId(id)) return;
    if (!tabs.some((tab) => tab.id === id) && tabs.length >= 8) {
      const candidate = tabs
        .filter((tab) => tab.id !== "UI-16")
        .reduce((old, tab) => (tab.viewed < old.viewed ? tab : old));
      setConfirm({ kind: "overflow", candidate: candidate.id, next: id });
      navigate(`/${lastRoute.current}`, { replace: true });
      return;
    }
    lastRoute.current = id;
    clock.current += 1;
    setTabs((old) =>
      old.some((tab) => tab.id === id)
        ? old.map((tab) =>
            tab.id === id ? { ...tab, viewed: clock.current } : tab,
          )
        : old.length < 8
          ? [...old, { id, viewed: clock.current, dirty: false }]
          : old,
    );
    useSharedPreferences.getState().visit(id);
  }, [id, pathname, navigate]);
  useEffect(() => {
    const handler = (event: KeyboardEvent) => {
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "k") {
        event.preventDefault();
        setSearch(true);
      }
    };
    window.addEventListener("keydown", handler);
    return () => window.removeEventListener("keydown", handler);
  }, []);
  const dirty = tabs.some((tab) => tab.dirty);
  useEffect(() => {
    if (!dirty) return;
    const handler = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      event.returnValue = "";
    };
    window.addEventListener("beforeunload", handler);
    return () => window.removeEventListener("beforeunload", handler);
  }, [dirty]);
  const remove = (candidate: string, next?: string) => {
    setTabs((old) => old.filter((tab) => tab.id !== candidate));
    if (next) navigateTo(next);
    else if (candidate === id) navigateTo("UI-16");
    setConfirm(null);
    setSearch(false);
  };
  const markDirty = useCallback((screenId: string, value: boolean) => {
    setTabs((old) =>
      old.some((tab) => tab.id === screenId && tab.dirty !== value)
        ? old.map((tab) =>
            tab.id === screenId ? { ...tab, dirty: value } : tab,
          )
        : old,
    );
  }, []);
  const allScreens = menuScreens(navigationMenus).filter(
    (screen) => !mobile || screen.mobile,
  );
  const label = (screenId: string) =>
    locateScreen(menus, screenId)?.screen.name ?? screenId;
  const header = (
    <>
      <Button
        className={styles.brand}
        variant="text"
        onClick={() => open("UI-16")}
      >
        <span className={styles.brandMark}>
          <PanelsTopLeft size={20} aria-hidden="true" />
        </span>
        <span>프로젝트·원가 관리</span>
      </Button>
      <div className={styles.headerActions}>
        <Button
          variant="text"
          aria-label="화면 검색"
          onClick={() => setSearch(true)}
        >
          <Search size={20} />
          <kbd className={styles.shortcutKey}>Ctrl K</kbd>
        </Button>
        <Button
          aria-label="알림"
          variant="text"
          aria-expanded={notifications}
          onClick={() => {
            setNotifications(!notifications);
            setSettings(false);
          }}
        >
          <Bell size={20} />
        </Button>
        <Button
          aria-label="사용자 설정"
          variant="text"
          aria-expanded={settings}
          onClick={() => {
            setSettings(!settings);
            setNotifications(false);
          }}
        >
          <UserRound size={20} />
          <span>{currentUser.name}</span>
          <ChevronDown size={14} aria-hidden="true" />
        </Button>
      </div>
      {notifications && (
        <div role="status" className={styles.popover}>
          새 알림이 없습니다
        </div>
      )}
      {settings && (
        <div className={styles.popover} aria-label="사용자 설정 항목">
          <label>
            목록 건수{" "}
            <select
              value={preferences.pageSize}
              onChange={(event) =>
                preferences.configure({
                  pageSize: Number(event.target.value) as 20 | 50 | 100,
                })
              }
            >
              {[20, 50, 100].map((size) => (
                <option value={size} key={size}>
                  {size}건
                </option>
              ))}
            </select>
          </label>
          <label>
            <input
              type="checkbox"
              checked={preferences.dense}
              onChange={(event) =>
                preferences.configure({ dense: event.target.checked })
              }
            />
            좁게 보기
          </label>
        </div>
      )}
    </>
  );
  return (
    <>
      <ResponsiveLayout
        collapsed={preferences.collapsed}
        onCollapsedChange={(collapsed) => preferences.configure({ collapsed })}
        header={header}
        menu={
          <>
            <Button
              className={styles.collapseButton}
              aria-label={preferences.collapsed ? "메뉴 펼치기" : "메뉴 접기"}
              onClick={() =>
                preferences.configure({ collapsed: !preferences.collapsed })
              }
            >
              {preferences.collapsed ? (
                <PanelLeftOpen size={18} />
              ) : (
                <PanelLeftClose size={18} />
              )}
              <span>{preferences.collapsed ? "메뉴 펼치기" : "메뉴 접기"}</span>
            </Button>
            <MenuNavigation
              menus={navigationMenus}
              active={id}
              onNavigate={open}
            />
          </>
        }
        tabs={
          <nav className={styles.screenTabs} aria-label="열린 화면">
            {tabs.map((tab) => (
              <div
                key={tab.id}
                className={tab.id === id ? styles.activeTab : ""}
              >
                <button
                  type="button"
                  aria-current={tab.id === id ? "page" : undefined}
                  onClick={() => open(tab.id)}
                >
                  {tab.dirty ? "● " : ""}
                  {label(tab.id)}
                </button>
                {tab.id !== "UI-16" && (
                  <button
                    type="button"
                    aria-label={`${label(tab.id)} 닫기`}
                    onClick={() =>
                      tab.dirty
                        ? setConfirm({ kind: "dirty", candidate: tab.id })
                        : remove(tab.id)
                    }
                  >
                    <X size={16} />
                  </button>
                )}
              </div>
            ))}
          </nav>
        }
      >
        <ErrorBanner error={error} onRetry={retry} pending={loading} />
        {loading && menus.length === 0 ? (
          <Skeleton label="메뉴 불러오는 중" />
        ) : (
          <>
            {tabs.map((tab) => {
              const location = locateScreen(menus, tab.id);
              return (
                <div hidden={tab.id !== id} key={tab.id}>
                  {location ? (
                    tab.id === "UI-27" ||
                    tab.id === "UI-15" ||
                    tab.id === "UI-01" ||
                    tab.id === "UI-02" ? (
                      <SharedMobileGuard
                        mobileAllowed={location.screen.mobile}
                        onDashboard={() => open("UI-16")}
                      >
                        {renderBusiness(tab.id, {
                          onDirtyChange: (value) => markDirty(tab.id, value),
                          name: location.screen.name,
                          groupName: location.group?.name,
                        })}
                      </SharedMobileGuard>
                    ) : (
                      <PlaceholderScreen
                        screen={location.screen}
                        group={location.group}
                        onDashboard={() => open("UI-16")}
                      />
                    )
                  ) : tab.id === "UI-22" ? (
                    <PlaceholderScreen
                      screen={{ name: tab.id, screenId: tab.id, mobile: true }}
                      onDashboard={() => open("UI-16")}
                    />
                  ) : (
                    !error && (
                      <div className={styles.placeholder}>
                        <p>
                          {menus.length === 0
                            ? "표시할 메뉴가 없습니다. 메뉴 설정을 확인해 주세요."
                            : "화면을 찾을 수 없습니다."}
                        </p>
                        <Button onClick={retry}>다시 시도</Button>
                      </div>
                    )
                  )}
                </div>
              );
            })}
            {!isScreenId(id) && <p>화면을 찾을 수 없습니다.</p>}
          </>
        )}
      </ResponsiveLayout>
      {search && (
        <Modal title="화면 검색" onCancel={() => setSearch(false)}>
          <input
            className={styles.searchInput}
            autoFocus
            aria-label="화면 이름 또는 ID"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
          />
          <ul className={styles.searchResults}>
            {allScreens
              .filter((screen) =>
                `${screen.name} ${screen.screenId}`
                  .toLocaleLowerCase()
                  .includes(query.trim().toLocaleLowerCase()),
              )
              .map((screen) => (
                <li key={screen.menuId}>
                  <Button onClick={() => open(screen.screenId!)}>
                    {screen.name} {screen.screenId}
                  </Button>
                </li>
              ))}
          </ul>
          {allScreens.filter((screen) =>
            `${screen.name} ${screen.screenId}`
              .toLocaleLowerCase()
              .includes(query.trim().toLocaleLowerCase()),
          ).length === 0 && <p>검색 결과가 없습니다.</p>}
          <Button onClick={() => setSearch(false)}>닫기</Button>
        </Modal>
      )}
      {confirm && (
        <ConfirmDialog
          title={
            confirm.kind === "overflow"
              ? "열린 화면 닫기"
              : "저장하지 않은 내용"
          }
          cancelLabel={confirm.kind === "dirty" ? "계속 작성" : "취소"}
          confirmLabel={confirm.kind === "dirty" ? "나가기" : "닫고 열기"}
          onCancel={() => setConfirm(null)}
          onConfirm={() => {
            const candidate = tabs.find((tab) => tab.id === confirm.candidate);
            if (confirm.kind === "overflow" && candidate?.dirty)
              setConfirm({ ...confirm, kind: "dirty" });
            else remove(confirm.candidate, confirm.next);
          }}
        >
          {confirm.kind === "dirty"
            ? "저장하지 않은 내용이 있습니다. 나가면 사라집니다."
            : `${label(confirm.candidate)} 화면을 닫고 새 화면을 여시겠습니까?`}
        </ConfirmDialog>
      )}
    </>
  );
}
