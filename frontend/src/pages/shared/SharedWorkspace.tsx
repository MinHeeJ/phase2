import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type ReactNode,
} from "react";
import { Link, useLocation, useNavigate } from "react-router-dom";
import {
  Bell,
  Star,
  Grid2X2,
  Database,
  Folder,
  Calendar,
  Receipt,
  ChartNoAxesCombined,
  Wallet,
  LockKeyhole,
  Sparkles,
  Settings,
  Search,
  ChevronDown,
  History,
} from "lucide-react";
import { AppFrame } from "../../app/AppFrame";
import {
  Button,
  ConfirmDialog,
  EmptyState,
  Modal,
  Skeleton,
} from "../../components/UiKit";
import { ErrorBanner } from "../../components/ErrorBanner";
import { getMenus, type MenuNode } from "./api";
import { PlaceholderScreen } from "./PlaceholderScreen";
import { screenIds } from "./registry";
import { useSharedPreferences } from "./preferences";
import styles from "./SharedScreens.module.css";

export type SharedScreenContext = { onDirtyChange: (dirty: boolean) => void };
type OpenScreen = { id: string; visited: number; dirty: boolean };
const icons = {
  grid: Grid2X2,
  db: Database,
  folder: Folder,
  calendar: Calendar,
  receipt: Receipt,
  chart: ChartNoAxesCombined,
  wallet: Wallet,
  lock: LockKeyhole,
  sparkle: Sparkles,
  gear: Settings,
};
function usePhone() {
  const [phone, setPhone] = useState(
    () => window.matchMedia("(max-width: 767px)").matches,
  );
  useEffect(() => {
    const media = window.matchMedia("(max-width: 767px)");
    const update = () => setPhone(media.matches);
    media.addEventListener("change", update);
    return () => media.removeEventListener("change", update);
  }, []);
  return phone;
}
export function SharedWorkspace({
  renderScreen,
}: {
  renderScreen?: (screenId: string, context: SharedScreenContext) => ReactNode;
}) {
  const location = useLocation(),
    navigate = useNavigate(),
    phone = usePhone();
  const preferences = useSharedPreferences();
  const [menus, setMenus] = useState<MenuNode[] | null>(null);
  const [error, setError] = useState<Error | null>(null);
  const [loading, setLoading] = useState(true);
  const [revision, setRevision] = useState(0);
  const [group, setGroup] = useState<number | null>(null);
  const [notification, setNotification] = useState(false);
  const [settings, setSettings] = useState(false);
  const [notice, setNotice] = useState("");
  const [search, setSearch] = useState(false),
    [query, setQuery] = useState("");
  const [pending, setPending] = useState<{
    message: string;
    apply: () => void;
    dirty: boolean;
  } | null>(null);
  const clock = useRef(1);
  const [tabs, setTabs] = useState<OpenScreen[]>([
    { id: "UI-16", visited: 0, dirty: false },
  ]);
  const tabsRef = useRef(tabs);
  tabsRef.current = tabs;
  const [active, setActive] = useState("UI-16");
  const allLeaves = menus?.flatMap((parent) => parent.children) ?? [];
  const leaves = allLeaves.filter((leaf) => !phone || leaf.mobile);
  const searchResults = leaves.filter((leaf) =>
    `${leaf.name} ${leaf.screenId}`.toLowerCase().includes(query.toLowerCase()),
  );
  useEffect(() => {
    let live = true;
    setLoading(true);
    setError(null);
    getMenus()
      .then((value) => {
        if (live) setMenus(value);
      })
      .catch((reason: Error) => {
        if (live) setError(reason);
      })
      .finally(() => {
        if (live) setLoading(false);
      });
    return () => {
      live = false;
    };
  }, [revision]);
  useEffect(() => {
    const id = location.pathname.slice(1);
    if (!screenIds.some((value) => value === id)) {
      navigate("/UI-16", { replace: true });
      return;
    }
    const current = tabsRef.current;
    const existing = current.find((tab) => tab.id === id);
    const accept = (replaceId?: string) => {
      const visited = clock.current++;
      setTabs((previous) =>
        existing
          ? previous.map((tab) => (tab.id === id ? { ...tab, visited } : tab))
          : [
              ...previous.filter((tab) => tab.id !== replaceId),
              { id, visited, dirty: false },
            ],
      );
      setActive(id);
      preferences.visit(id);
    };
    if (!existing && current.length >= 8) {
      const candidate = current
        .filter((tab) => tab.id !== "UI-16")
        .reduce((oldest, tab) => (tab.visited < oldest.visited ? tab : oldest));
      setPending({
        message: `가장 오래 안 본 ${allLeaves.find((leaf) => leaf.screenId === candidate.id)?.name ?? candidate.id} 탭을 닫고 새 화면을 여나요?`,
        apply: () => accept(candidate.id),
        dirty: candidate.dirty,
      });
    } else accept();
    // Route changes, not metadata refreshes, activate an existing mounted screen.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [location.pathname]);
  useEffect(() => {
    const handler = (event: KeyboardEvent) => {
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "k") {
        event.preventDefault();
        setSearch(true);
      }
      if (event.key === "Escape") {
        setSearch(false);
        setNotification(false);
        setSettings(false);
      }
    };
    window.addEventListener("keydown", handler);
    return () => window.removeEventListener("keydown", handler);
  }, []);
  useEffect(() => {
    if (!tabs.some((tab) => tab.dirty)) return;
    const handler = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      event.returnValue = "";
    };
    window.addEventListener("beforeunload", handler);
    return () => window.removeEventListener("beforeunload", handler);
  }, [tabs]);
  useEffect(() => {
    const parent = menus?.find((item) =>
      item.children.some((child) => child.screenId === active),
    );
    if (parent) setGroup(parent.menuId);
  }, [menus, active]);
  const dirtyChange = useCallback(
    (id: string, dirty: boolean) =>
      setTabs((previous) =>
        previous.map((tab) => (tab.id === id ? { ...tab, dirty } : tab)),
      ),
    [],
  );
  const close = (id: string) => {
    if (id === "UI-16") return;
    const apply = () => {
      setTabs((previous) => previous.filter((tab) => tab.id !== id));
      if (id === active) navigate("/UI-16");
    };
    if (tabs.find((tab) => tab.id === id)?.dirty)
      setPending({
        message: "저장하지 않은 내용이 있습니다. 나가면 사라집니다.",
        apply,
        dirty: true,
      });
    else apply();
  };
  const leafLink = (leaf: MenuNode, draggable = false) => (
    <Link
      key={leaf.menuId}
      to={`/${leaf.screenId}`}
      title={leaf.name}
      aria-current={active === leaf.screenId ? "page" : undefined}
      draggable={draggable}
      onDragStart={(event) =>
        event.dataTransfer.setData("text/plain", leaf.screenId!)
      }
      onDragOver={(event) => {
        if (draggable) event.preventDefault();
      }}
      onDrop={(event) => {
        if (draggable) {
          event.preventDefault();
          preferences.moveFavorite(
            event.dataTransfer.getData("text/plain"),
            leaf.screenId!,
          );
        }
      }}
    >
      <span>{leaf.name}</span>
    </Link>
  );
  const selected = allLeaves.find((leaf) => leaf.screenId === active);
  const parent = menus?.find((item) =>
    item.children.some((child) => child.screenId === active),
  );
  return (
    <AppFrame
      collapsed={preferences.collapsed}
      onCollapsedChange={preferences.setCollapsed}
      header={
        <div className={styles.header}>
          <Link to="/UI-16" className={styles.brand}>
            <span className={styles.brandMark}>
              <ChartNoAxesCombined size={20} />
            </span>
            프로젝트·원가 관리
          </Link>
          <Button
            className={styles.searchTrigger}
            aria-label="화면 검색 열기"
            onClick={() => setSearch(true)}
          >
            <Search size={16} />
            <span>화면 검색</span>
            <kbd>Ctrl K</kbd>
          </Button>
          <Button
            aria-label="알림"
            aria-expanded={notification}
            variant="text"
            onClick={() => {
              setNotification(!notification);
              setSettings(false);
            }}
          >
            <Bell size={20} />
          </Button>
          <Button
            variant="text"
            aria-expanded={settings}
            onClick={() => {
              setSettings(!settings);
              setNotification(false);
            }}
          >
            기본 사용자
            <ChevronDown size={16} />
          </Button>
          {notification && (
            <div role="status" className={styles.notification}>
              <Bell size={20} />
              <span>새 알림이 없습니다</span>
            </div>
          )}
        </div>
      }
      navigation={
        <nav className={styles.navigation} aria-label="업무 메뉴">
          <span className={styles.navLabel}>
            <Star size={16} />
            즐겨찾기
          </span>
          {!preferences.favorites.length && (
            <small className={styles.navHint}>
              제목 옆 별을 눌러 추가하세요
            </small>
          )}
          {preferences.favorites
            .map((id) => leaves.find((leaf) => leaf.screenId === id))
            .filter((leaf): leaf is MenuNode => !!leaf)
            .map((leaf) => leafLink(leaf, true))}
          <span className={styles.navLabel}>
            <History size={16} />
            최근 본 화면
          </span>
          {preferences.recent
            .map((id) => leaves.find((leaf) => leaf.screenId === id))
            .filter((leaf): leaf is MenuNode => !!leaf)
            .map((leaf) => leafLink(leaf))}
          {menus?.map((item) => {
            const Icon = icons[item.icon as keyof typeof icons] ?? Folder;
            const children = item.children.filter(
              (child) => !phone || child.mobile,
            );
            if (!children.length) return null;
            if (children.some((child) => child.screenId === "UI-16"))
              return (
                <Link
                  key={item.menuId}
                  to="/UI-16"
                  title={item.name}
                  className={styles.groupButton}
                  aria-current={active === "UI-16" ? "page" : undefined}
                >
                  <Icon size={20} />
                  <span>{item.name}</span>
                </Link>
              );
            return (
              <div
                key={item.menuId}
                className={styles.menuGroup}
                onMouseEnter={() => {
                  if (preferences.collapsed) setGroup(item.menuId);
                }}
              >
                <button
                  type="button"
                  className={styles.groupButton}
                  title={item.name}
                  aria-expanded={group === item.menuId}
                  onClick={() =>
                    setGroup(group === item.menuId ? null : item.menuId)
                  }
                >
                  <Icon size={20} />
                  <span>{item.name}</span>
                  <ChevronDown size={16} className={styles.chevron} />
                </button>
                {group === item.menuId && (
                  <div className={styles.children}>
                    {children.map((child) => leafLink(child))}
                  </div>
                )}
              </div>
            );
          })}
        </nav>
      }
      screenTabs={
        <div className={styles.tabs}>
          {tabs.map((tab) => (
            <div key={tab.id} className={styles.tab}>
              <button
                type="button"
                aria-current={active === tab.id ? "page" : undefined}
                onClick={() => navigate(`/${tab.id}`)}
              >
                {tab.dirty ? "● " : ""}
                {allLeaves.find((leaf) => leaf.screenId === tab.id)?.name ??
                  tab.id}
              </button>
              {tab.id !== "UI-16" && (
                <button
                  type="button"
                  aria-label={`${tab.id} 닫기`}
                  onClick={() => close(tab.id)}
                >
                  ×
                </button>
              )}
            </div>
          ))}
        </div>
      }
    >
      {settings && (
        <div className={styles.settings}>
          <label>
            목록 기본 건수{" "}
            <select
              value={preferences.pageSize}
              onChange={(event) =>
                preferences.setPageSize(
                  Number(event.target.value) as 20 | 50 | 100,
                )
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
              onChange={(event) => preferences.setDense(event.target.checked)}
            />
            좁게 보기
          </label>
        </div>
      )}
      {error && (
        <ErrorBanner
          error={error}
          onRetry={() => setRevision((value) => value + 1)}
        />
      )}
      {loading && !menus ? (
        <Skeleton />
      ) : menus && !selected && active !== "UI-22" ? (
        <EmptyState
          reason="활성 메뉴에서 이 화면을 찾을 수 없습니다."
          action="메뉴 다시 조회"
          onAction={() => setRevision((value) => value + 1)}
        />
      ) : menus ? (
        <>
          {phone && selected && !selected.mobile ? (
            <section className={styles.placeholder}>
              <p>PC에서 이용해 주세요</p>
              <Link to="/UI-16">대시보드로</Link>
            </section>
          ) : (
            <>
              <nav aria-label="현재 위치" className={styles.breadcrumb}>
                <Link to="/UI-16">홈</Link>
                <span>›</span>
                {parent?.name}
                {parent && <span>›</span>}
                <span>{selected?.name ?? active}</span>
              </nav>
              <div className={styles.title}>
                <h1>{selected?.name ?? active}</h1>
                <Button
                  variant="text"
                  aria-pressed={preferences.favorites.includes(active)}
                  aria-label={
                    preferences.favorites.includes(active)
                      ? "즐겨찾기 해제"
                      : "즐겨찾기 추가"
                  }
                  onClick={() =>
                    setNotice(
                      preferences.toggleFavorite(active)
                        ? ""
                        : "즐겨찾기는 최대 10개까지 추가할 수 있습니다.",
                    )
                  }
                >
                  <Star
                    size={20}
                    fill={
                      preferences.favorites.includes(active)
                        ? "currentColor"
                        : "none"
                    }
                  />
                </Button>
                <span className={styles.identifier}>{active}</span>
              </div>
              {notice && (
                <p role="status" className={styles.notice}>
                  {notice}
                </p>
              )}
              {tabs.map((tab) => (
                <div
                  key={tab.id}
                  role="region"
                  aria-label={`${tab.id} 화면`}
                  hidden={tab.id !== active}
                >
                  {tab.id === "UI-27" ? (
                    renderScreen?.(tab.id, {
                      onDirtyChange: (dirty) => dirtyChange(tab.id, dirty),
                    })
                  ) : (
                    <PlaceholderScreen screenId={tab.id} />
                  )}
                </div>
              ))}
            </>
          )}
        </>
      ) : null}
      {search && (
        <Modal title="화면 검색" onClose={() => setSearch(false)}>
          <input
            className={styles.search}
            aria-label="화면 검색"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            autoFocus
          />
          <div className={styles.results}>
            {!searchResults.length && (
              <p className={styles.placeholder}>조건에 맞는 결과가 없습니다</p>
            )}
            {searchResults.map((leaf) => (
              <Button
                key={leaf.menuId}
                onClick={() => {
                  navigate(`/${leaf.screenId}`);
                  setSearch(false);
                }}
              >
                {leaf.name} · {leaf.screenId}
              </Button>
            ))}
          </div>
        </Modal>
      )}
      {pending && (
        <ConfirmDialog
          message={pending.message}
          cancelLabel={pending.dirty ? "계속 작성" : "취소"}
          confirmLabel={pending.dirty ? "나가기" : "닫기 확인"}
          onCancel={() => {
            setPending(null);
            navigate(`/${active}`, { replace: true });
          }}
          onConfirm={() => {
            if (pending.dirty && !pending.message.startsWith("저장하지"))
              setPending({
                ...pending,
                message: "저장하지 않은 내용이 있습니다. 나가면 사라집니다.",
              });
            else {
              pending.apply();
              setPending(null);
            }
          }}
        />
      )}
    </AppFrame>
  );
}
