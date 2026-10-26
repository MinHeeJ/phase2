import {
  createContext,
  useContext,
  useEffect,
  useState,
  type ReactNode,
} from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { createPortal } from "react-dom";
import {
  Bell,
  CalendarDays,
  ChevronDown,
  ChevronsLeft,
  ChevronsRight,
  Clock3,
  ChartNoAxesCombined,
  Database,
  Folder,
  Grid2X2,
  LockKeyhole,
  Receipt,
  Search,
  Settings,
  Sparkles,
  Star,
  User,
  Wallet,
  X,
  type LucideIcon,
} from "lucide-react";
import { ShellLayout } from "../../shared/ShellLayout";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { usePreferences } from "../../shared/preferences";
import { Button, Dialog, EmptyState, Notice, Skeleton } from "../../shared/ui";
import { getMenus, useSharedRead, type SharedMenuNode } from "./sharedApi";
import { SharedPlaceholderScreen } from "./SharedPlaceholderScreen";
import styles from "./shared.module.css";

const screenState = createContext({
  screenId: "",
  titleActionId: "",
  setDirty: (_value: boolean) => undefined as void,
});
export const useSharedScreenState = () => useContext(screenState);
export function ScreenTitleAction({ children }: { children: ReactNode }) {
  const { titleActionId } = useSharedScreenState();
  const [target, setTarget] = useState<HTMLElement | null>(null);
  useEffect(() => {
    setTarget(titleActionId ? document.getElementById(titleActionId) : null);
  }, [titleActionId]);
  return target ? createPortal(children, target) : <>{children}</>;
}
const icons: Record<string, LucideIcon> = {
  grid: Grid2X2,
  db: Database,
  folder: Folder,
  calendar: CalendarDays,
  chart: ChartNoAxesCombined,
  receipt: Receipt,
  wallet: Wallet,
  lock: LockKeyhole,
  sparkle: Sparkles,
  gear: Settings,
};
function SharedMenuIcon({ name }: { name: string | null }) {
  const Icon = icons[name ?? ""] ?? Folder;
  return <Icon size={18} aria-hidden="true" />;
}
function storedScreens(key: string): string[] {
  try {
    const value: unknown = JSON.parse(
      localStorage.getItem(`si.${key}`) ?? "[]",
    );
    return Array.isArray(value)
      ? value.filter((id): id is string => typeof id === "string")
      : [];
  } catch {
    return [];
  }
}
function persistScreens(key: string, value: string[]) {
  try {
    localStorage.setItem(`si.${key}`, JSON.stringify(value));
  } catch {
    /* Keep session state when storage is unavailable. */
  }
}
function useSharedMedia(queryText: string) {
  const [mobile, setMobile] = useState(
    () => window.matchMedia?.(queryText).matches ?? false,
  );
  useEffect(() => {
    const query = window.matchMedia?.(queryText);
    if (!query) return;
    const changed = () => setMobile(query.matches);
    query.addEventListener("change", changed);
    return () => query.removeEventListener("change", changed);
  }, [queryText]);
  return mobile;
}
export function SharedWorkspace({
  renderScreen,
}: {
  renderScreen?: (screenId: string) => ReactNode;
}) {
  const location = useLocation();
  const navigate = useNavigate();
  const mobile = useSharedMedia("(max-width: 767px)");
  const tablet = useSharedMedia("(min-width: 768px) and (max-width: 1279px)");
  const [tabletExpanded, setTabletExpanded] = useState(false);
  const requested =
    location.pathname === "/" ? "UI-16" : location.pathname.slice(1);
  const menus = useSharedRead(getMenus);
  const leaves = menus.data?.flatMap((group) => group.children) ?? [];
  const current = leaves.find((item) => item.screenId === requested);
  const preferences = usePreferences();
  const collapsed =
    !mobile && (tablet ? !tabletExpanded : preferences.collapsed);
  function toggleMenu() {
    if (tablet) setTabletExpanded((value) => !value);
    else preferences.update({ collapsed: !preferences.collapsed });
  }
  const [tabs, setTabs] = useState(["UI-16"]);
  const [recentUse, setRecentUse] = useState(["UI-16"]);
  const [dirty, setDirty] = useState<Record<string, boolean>>({});
  const [favorite, setFavorite] = useState(() =>
    storedScreens("favorites").slice(0, 10),
  );
  const [recent, setRecent] = useState(() =>
    storedScreens("recent").slice(0, 5),
  );
  const [expanded, setExpanded] = useState<number | null>(null);
  const [hovered, setHovered] = useState<number | null>(null);
  const [search, setSearch] = useState<string | null>(null);
  const [bell, setBell] = useState(false);
  const [settings, setSettings] = useState(false);
  const [confirmation, setConfirmation] = useState<{
    close: string;
    next?: string;
  } | null>(null);
  const valid = requested === "UI-22" || !!current;
  const leafById = (id: string) => leaves.find((leaf) => leaf.screenId === id);

  useEffect(() => {
    if (location.pathname === "/") navigate("/UI-16", { replace: true });
  }, [location.pathname, navigate]);
  useEffect(() => {
    if (!valid) return;
    if (!tabs.includes(requested)) {
      if (tabs.length >= 8) {
        const oldest = recentUse.find((id) => id !== "UI-16")!;
        setConfirmation({ close: oldest, next: requested });
        return;
      }
      setTabs((value) => [...value, requested]);
    }
    setRecentUse((value) => [
      ...value.filter((id) => id !== requested),
      requested,
    ]);
    if (requested !== "UI-22")
      setRecent((value) => {
        const next = [
          requested,
          ...value.filter((id) => id !== requested),
        ].slice(0, 5);
        persistScreens("recent", next);
        return next;
      });
    const parent = menus.data?.find((group) =>
      group.children.some((leaf) => leaf.screenId === requested),
    );
    if (parent) setExpanded(parent.menuId);
  }, [requested, valid, tabs, menus.data]);
  useEffect(() => {
    const key = (event: KeyboardEvent) => {
      if (event.ctrlKey && event.key.toLowerCase() === "k") {
        event.preventDefault();
        setSearch("");
      }
      if (event.key === "Escape") setSearch(null);
    };
    document.addEventListener("keydown", key);
    return () => document.removeEventListener("keydown", key);
  }, []);
  useEffect(() => {
    if (!Object.values(dirty).some(Boolean)) return;
    const leave = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      event.returnValue = "";
    };
    window.addEventListener("beforeunload", leave);
    return () => window.removeEventListener("beforeunload", leave);
  }, [dirty]);

  function open(id: string) {
    setSearch(null);
    navigate(`/${id}`);
  }
  function close(id: string) {
    setTabs((value) => value.filter((item) => item !== id));
    setRecentUse((value) => value.filter((item) => item !== id));
    setDirty((value) => {
      const next = { ...value };
      delete next[id];
      return next;
    });
  }
  function toggleFavorite(id: string) {
    setFavorite((value) => {
      const next = value.includes(id)
        ? value.filter((item) => item !== id)
        : value.length < 10
          ? [...value, id]
          : value;
      persistScreens("favorites", next);
      return next;
    });
  }
  function screenLink(leaf: SharedMenuNode, draggable = false) {
    if (!leaf.screenId || (mobile && !leaf.mobile)) return null;
    const id = leaf.screenId;
    return (
      <a
        key={id}
        href={`/${id}`}
        className={styles.navLink}
        aria-current={requested === id ? "page" : undefined}
        draggable={draggable}
        onDragStart={(event) => event.dataTransfer.setData("text/plain", id)}
        onDragOver={draggable ? (event) => event.preventDefault() : undefined}
        onDrop={
          draggable
            ? (event) => {
                event.preventDefault();
                const source = event.dataTransfer.getData("text/plain");
                setFavorite((value) => {
                  if (!value.includes(source)) return value;
                  const next = value.filter((item) => item !== source);
                  next.splice(next.indexOf(id), 0, source);
                  persistScreens("favorites", next);
                  return next;
                });
              }
            : undefined
        }
        onClick={(event) => {
          event.preventDefault();
          open(id);
        }}
      >
        {leaf.name}
      </a>
    );
  }
  const nav = (
    <nav
      aria-label="전체 메뉴"
      className={`${styles.nav} ${collapsed ? styles.compact : ""}`}
    >
      <section aria-label="즐겨찾기">
        <h2>
          <Star size={14} aria-hidden="true" /> 즐겨찾기
        </h2>
        {favorite.length === 0 && (
          <p className={styles.navHint}>제목 옆 별을 눌러 추가하세요</p>
        )}
        {favorite.map((id) => {
          const leaf = leafById(id);
          return leaf && screenLink(leaf, true);
        })}
      </section>
      <section aria-label="최근 본 화면">
        <h2>
          <Clock3 size={14} aria-hidden="true" /> 최근 본 화면
        </h2>
        {recent.map((id) => {
          const leaf = leafById(id);
          return leaf && screenLink(leaf);
        })}
      </section>
      <div className={styles.menuGroups}>
        {menus.data
          ?.filter(
            (group) => !mobile || group.children.some((leaf) => leaf.mobile),
          )
          .map((group) => (
            <section
              key={group.menuId}
              className={styles.group}
              onMouseEnter={() => setHovered(group.menuId)}
              onMouseLeave={() => setHovered(null)}
              onFocus={() => setHovered(group.menuId)}
              onBlur={(event) => {
                if (
                  !event.currentTarget.contains(
                    event.relatedTarget as Node | null,
                  )
                )
                  setHovered(null);
              }}
            >
              <button
                className={styles.groupButton}
                type="button"
                title={group.name}
                aria-expanded={expanded === group.menuId}
                onClick={() =>
                  group.children.some((leaf) => leaf.screenId === "UI-16")
                    ? open("UI-16")
                    : setExpanded(
                        expanded === group.menuId ? null : group.menuId,
                      )
                }
              >
                <SharedMenuIcon name={group.icon} />
                <span>{group.name}</span>
                {!group.children.some((leaf) => leaf.screenId === "UI-16") && (
                  <ChevronDown
                    className={styles.groupChevron}
                    size={14}
                    aria-hidden="true"
                  />
                )}
              </button>
              {(expanded === group.menuId ||
                (collapsed && hovered === group.menuId)) &&
                !group.children.some((leaf) => leaf.screenId === "UI-16") && (
                  <div
                    className={collapsed ? styles.flyout : styles.groupChildren}
                  >
                    {group.children.map((leaf) => screenLink(leaf))}
                  </div>
                )}
            </section>
          ))}
      </div>
      <button
        type="button"
        className={styles.collapseButton}
        onClick={toggleMenu}
        aria-label="메뉴 접기"
      >
        {collapsed ? <ChevronsRight size={18} /> : <ChevronsLeft size={18} />}
        <span>메뉴 접기</span>
      </button>
    </nav>
  );
  const header = (
    <>
      <a
        href="/UI-16"
        className={styles.brand}
        onClick={(event) => {
          event.preventDefault();
          open("UI-16");
        }}
      >
        <span className={styles.brandMark}>
          <Grid2X2 size={20} aria-hidden="true" />
        </span>
        프로젝트·원가 관리
      </a>
      <div className={styles.headerActions}>
        <Button aria-label="화면 검색" onClick={() => setSearch("")}>
          <Search size={18} />
        </Button>
        <div className={styles.popupAnchor}>
          <Button
            aria-label="알림"
            aria-expanded={bell}
            onClick={() => setBell(!bell)}
          >
            <Bell size={18} />
          </Button>
          {bell && <div className={styles.popup}>새 알림이 없습니다</div>}
        </div>
        <div className={styles.popupAnchor}>
          <Button
            aria-label="기본 사용자"
            aria-expanded={settings}
            onClick={() => setSettings(!settings)}
          >
            <User size={18} />
            <span className={styles.userLabel}>기본 사용자</span>
            <ChevronDown size={14} aria-hidden="true" />
          </Button>
          {settings && (
            <div className={styles.popup}>
              <label>
                목록 기본 건수{" "}
                <select
                  aria-label="목록 기본 건수"
                  value={preferences.pageSize}
                  onChange={(event) =>
                    preferences.update({
                      pageSize: Number(event.target.value) as 20 | 50 | 100,
                    })
                  }
                >
                  {[20, 50, 100].map((size) => (
                    <option key={size} value={size}>
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
                    preferences.update({ dense: event.target.checked })
                  }
                />
                좁게 보기
              </label>
            </div>
          )}
        </div>
      </div>
    </>
  );
  const tabBar = (
    <div className={styles.tabs} role="tablist" aria-label="열린 화면">
      {tabs.map((id) => (
        <div key={id}>
          <button
            type="button"
            role="tab"
            aria-selected={requested === id}
            onClick={() => open(id)}
          >
            {dirty[id] ? "● " : ""}
            {leafById(id)?.name ?? id}
          </button>
          {id !== "UI-16" && (
            <button
              type="button"
              aria-label={`${leafById(id)?.name ?? id} 탭 닫기`}
              onClick={() => {
                if (dirty[id]) setConfirmation({ close: id });
                else {
                  close(id);
                  if (requested === id) open("UI-16");
                }
              }}
            >
              <X size={16} />
            </button>
          )}
        </div>
      ))}
    </div>
  );
  return (
    <ShellLayout
      header={header}
      navigation={nav}
      tabs={tabBar}
      collapsed={collapsed}
      onToggle={toggleMenu}
    >
      <ErrorBanner error={menus.error} onRetry={menus.retry} />
      {menus.loading && !menus.data && <Skeleton label="메뉴 불러오는 중" />}
      {!menus.loading && menus.data?.length === 0 && (
        <EmptyState
          reason="표시할 메뉴가 없습니다."
          nextAction={<Button onClick={menus.retry}>다시 시도</Button>}
        />
      )}
      {menus.data && !valid && (
        <Notice>
          요청한 화면을 찾을 수 없습니다. <a href="/UI-16">대시보드로</a>
        </Notice>
      )}
      {tabs.map((id) => {
        const leaf = leafById(id);
        const group = menus.data?.find((item) =>
          item.children.some((child) => child.screenId === id),
        );
        if (!leaf && id !== "UI-22") return null;
        return (
          <div
            key={id}
            role="tabpanel"
            aria-label={leaf?.name ?? id}
            hidden={requested !== id}
          >
            {mobile && leaf && !leaf.mobile ? (
              <Notice>
                PC에서 이용해 주세요{" "}
                <Button onClick={() => open("UI-16")}>대시보드로</Button>
              </Notice>
            ) : (
              <screenState.Provider
                value={{
                  screenId: id,
                  titleActionId: `screen-actions-${id}`,
                  setDirty: (value) =>
                    setDirty((old) =>
                      old[id] === value ? old : { ...old, [id]: value },
                    ),
                }}
              >
                <header className={styles.title}>
                  <div>
                    {group && (
                      <p>
                        {group.name} / {leaf?.name}
                      </p>
                    )}
                    <div className={styles.titleRow}>
                      <h1>{leaf?.name ?? id}</h1>
                      <span className={styles.screenId}>{id}</span>
                    </div>
                  </div>
                  <div className={styles.titleActions}>
                    {leaf && (
                      <Button
                        aria-label="즐겨찾기"
                        aria-pressed={favorite.includes(id)}
                        disabled={
                          !favorite.includes(id) && favorite.length >= 10
                        }
                        onClick={() => toggleFavorite(id)}
                      >
                        <Star
                          size={18}
                          fill={favorite.includes(id) ? "currentColor" : "none"}
                        />
                      </Button>
                    )}
                    <div id={`screen-actions-${id}`} />
                  </div>
                </header>
                {renderScreen?.(id) ??
                  (id === "UI-27" ? null : (
                    <SharedPlaceholderScreen screenId={id} />
                  ))}
              </screenState.Provider>
            )}
          </div>
        );
      })}
      {search !== null && (
        <Dialog title="화면 검색" onClose={() => setSearch(null)}>
          <label>
            화면명{" "}
            <input
              autoFocus
              value={search}
              onChange={(event) => setSearch(event.target.value)}
            />
          </label>
          <div className={styles.searchResults}>
            {leaves
              .filter(
                (leaf) =>
                  (!mobile || leaf.mobile) &&
                  `${leaf.name} ${leaf.screenId}`
                    .toLowerCase()
                    .includes(search.toLowerCase()),
              )
              .map((leaf) => screenLink(leaf))}
            {!leaves.some(
              (leaf) =>
                (!mobile || leaf.mobile) &&
                `${leaf.name} ${leaf.screenId}`
                  .toLowerCase()
                  .includes(search.toLowerCase()),
            ) && (
              <EmptyState
                reason="조건에 맞는 결과가 없습니다"
                nextAction={null}
              />
            )}
          </div>
        </Dialog>
      )}
      {confirmation && (
        <Dialog
          title={
            confirmation.next ? "열린 화면이 8개입니다." : "저장하지 않은 내용"
          }
          onClose={() => {
            setConfirmation(null);
            if (confirmation.next) open(recentUse[recentUse.length - 1]);
          }}
        >
          <p>
            {dirty[confirmation.close]
              ? "저장하지 않은 내용이 있습니다. 나가면 사라집니다."
              : `${leafById(confirmation.close)?.name ?? confirmation.close} 탭을 닫으시겠습니까?`}
          </p>
          <div className={styles.confirmActions}>
            <Button
              onClick={() => {
                setConfirmation(null);
                if (confirmation.next) open(recentUse[recentUse.length - 1]);
              }}
            >
              {dirty[confirmation.close] ? "계속 작성" : "취소"}
            </Button>
            <Button
              primary
              onClick={() => {
                close(confirmation.close);
                const next = confirmation.next ?? "UI-16";
                setConfirmation(null);
                open(next);
              }}
            >
              {dirty[confirmation.close] ? "나가기" : "확인"}
            </Button>
          </div>
        </Dialog>
      )}
    </ShellLayout>
  );
}
