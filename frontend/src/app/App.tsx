import { useEffect, useRef, useState } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import {
  Menu,
  Bell,
  Star,
  ChevronDown,
  LayoutGrid,
  Database,
  Folder,
  Calendar,
  Receipt,
  ChartNoAxesCombined,
  Wallet,
  Lock,
  Sparkles,
  Settings,
  PanelLeftClose,
  Search,
  Clock3,
  FileClock,
  ChevronRight,
  type LucideIcon,
} from "lucide-react";
import { api, type MenuNode } from "../services/api";
import { ErrorBanner, Loading, Modal } from "../components/Common";
import { CommonCodes } from "../features/common-codes/CommonCodes";
import { usePreferences } from "../stores/preferences";
import { periodType, todayMonth } from "../screens/registry";
import styles from "./App.module.css";
const icons: Record<string, LucideIcon> = {
  grid: LayoutGrid,
  db: Database,
  folder: Folder,
  calendar: Calendar,
  receipt: Receipt,
  chart: ChartNoAxesCombined,
  wallet: Wallet,
  lock: Lock,
  sparkle: Sparkles,
  gear: Settings,
};
export function App() {
  const location = useLocation();
  const navigate = useNavigate();
  const id = location.pathname.slice(1) || "UI-16";
  const pref = usePreferences();
  const [menus, setMenus] = useState<MenuNode[]>([]);
  const [error, setError] = useState<unknown>();
  const [loading, setLoading] = useState(true);
  const [revision, setRevision] = useState(0);
  const [expanded, setExpanded] = useState<number>();
  const [hover, setHover] = useState<number>();
  const [mobile, setMobile] = useState(window.innerWidth < 768);
  const [mobileOpen, setMobileOpen] = useState(false);
  const [settings, setSettings] = useState(false);
  const [bell, setBell] = useState(false);
  const [search, setSearch] = useState(false);
  const [keyword, setKeyword] = useState("");
  const [tabs, setTabs] = useState<string[]>(["UI-16"]);
  const [visits, setVisits] = useState<Record<string, number>>({});
  const [dirty, setDirty] = useState(false);
  const [pending, setPending] = useState<() => void>();
  const [overflow, setOverflow] = useState<{
    target: string;
    victim: string;
  }>();
  const [toast, setToast] = useState("");
  const [codeVisited, setCodeVisited] = useState(false);
  const [actionsHost, setActionsHost] = useState<HTMLDivElement | null>(null);
  const [periods, setPeriods] = useState<Record<string, string>>({});
  const toastTimer = useRef<ReturnType<typeof setTimeout>>();
  const leaves = menus.flatMap((m) => m.children);
  const screen = leaves.find((s) => s.screenId === id);
  const parent = menus.find((m) => m.children.some((s) => s.screenId === id));
  useEffect(() => {
    if (location.pathname === "/") navigate("/UI-16", { replace: true });
  }, [location.pathname, navigate]);
  useEffect(() => {
    const c = new AbortController();
    setLoading(true);
    api<MenuNode[]>("/api/menus", "GET", undefined, c.signal)
      .then(setMenus)
      .catch((e) => {
        if (!c.signal.aborted) setError(e);
      })
      .finally(() => {
        if (!c.signal.aborted) setLoading(false);
      });
    return () => c.abort();
  }, [revision]);
  useEffect(() => {
    const resize = () => setMobile(window.innerWidth < 768);
    window.addEventListener("resize", resize);
    return () => window.removeEventListener("resize", resize);
  }, []);
  useEffect(() => {
    setExpanded(parent?.menuId);
    if (screen || id === "UI-22") {
      pref.visit(id);
      setTabs((prev) => (prev.includes(id) ? prev : [...prev, id]));
      setVisits((prev) => ({ ...prev, [id]: Date.now() }));
    }
    if (id === "UI-27") setCodeVisited(true);
  }, [id, screen?.screenId]);
  useEffect(() => {
    const key = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "k") {
        e.preventDefault();
        setSearch(true);
      }
      if (e.key === "Escape") setSearch(false);
    };
    window.addEventListener("keydown", key);
    return () => window.removeEventListener("keydown", key);
  }, []);
  useEffect(() => {
    const unload = (e: BeforeUnloadEvent) => {
      if (dirty) {
        e.preventDefault();
        e.returnValue = "";
      }
    };
    window.addEventListener("beforeunload", unload);
    return () => window.removeEventListener("beforeunload", unload);
  }, [dirty]);
  useEffect(
    () => () => {
      if (toastTimer.current) clearTimeout(toastTimer.current);
    },
    [],
  );
  function success(text: string) {
    setToast(text);
    if (toastTimer.current) clearTimeout(toastTimer.current);
    toastTimer.current = setTimeout(() => setToast(""), 3000);
  }
  function guard(action: () => void) {
    if (dirty) setPending(() => action);
    else action();
  }
  function go(target: string) {
    if (target === id) {
      setSearch(false);
      setMobileOpen(false);
      return;
    }
    guard(() => {
      if (!tabs.includes(target) && tabs.length >= 8) {
        const victim = tabs
          .filter((x) => x !== "UI-16")
          .sort((a, b) => (visits[a] ?? 0) - (visits[b] ?? 0))[0];
        setOverflow({ target, victim });
        return;
      }
      navigate(`/${target}`);
      setSearch(false);
      setMobileOpen(false);
    });
  }
  function closeTab(target: string) {
    guard(() => {
      setTabs((prev) => prev.filter((x) => x !== target));
      if (target === id) navigate("/UI-16");
    });
  }
  const restricted = mobile && screen?.mobile === false;
  const sideLink = (s: MenuNode) => (
    <button
      data-testid={`screen-link-${s.menuId}`}
      key={s.menuId}
      aria-current={s.screenId === id ? "page" : undefined}
      className={s.screenId === id ? styles.active : ""}
      onClick={() => s.screenId && go(s.screenId)}
    >
      {s.name}
    </button>
  );
  return (
    <div data-testid="app-shell" className={styles.shell}>
      <header className={styles.header}>
        <button
          data-testid="menu-toggle"
          aria-label="메뉴 열기/접기"
          onClick={() => {
            if (mobile) setMobileOpen((v) => !v);
            else pref.set({ collapsed: !pref.collapsed });
          }}
        >
          <Menu size={20} />
        </button>
        <button
          data-testid="brand-home"
          className={styles.brand}
          onClick={() => go("UI-16")}
        >
          <span className={styles.brandIcon}>
            <LayoutGrid size={20} />
          </span>
          프로젝트·원가 관리
        </button>
        <div className={styles.headerRight}>
          <button
            data-testid="screen-search-open"
            aria-label="화면 검색"
            onClick={() => setSearch(true)}
          >
            <Search size={18} />
            <small>Ctrl K</small>
          </button>
          <div className={styles.popupAnchor}>
            <button
              data-testid="notifications-toggle"
              aria-label="알림"
              onClick={() => setBell((v) => !v)}
            >
              <Bell size={18} />
            </button>
            {bell && <div className={styles.popup}>새 알림이 없습니다</div>}
          </div>
          <div className={styles.popupAnchor}>
            <button
              data-testid="user-settings-toggle"
              onClick={() => setSettings((v) => !v)}
            >
              기본 사용자 <ChevronDown size={14} />
            </button>
            {settings && (
              <div className={styles.popup}>
                <label className="field">
                  목록 기본 건수
                  <select
                    data-testid="page-size-select"
                    value={pref.size}
                    onChange={(e) => pref.set({ size: Number(e.target.value) })}
                  >
                    {[20, 50, 100].map((n) => (
                      <option
                        data-testid={`page-size-option-${n}`}
                        key={n}
                        value={n}
                      >
                        {n}건
                      </option>
                    ))}
                  </select>
                </label>
                <label>
                  <input
                    data-testid="dense-view-toggle"
                    type="checkbox"
                    checked={pref.dense}
                    onChange={(e) => pref.set({ dense: e.target.checked })}
                  />{" "}
                  좁게 보기
                </label>
              </div>
            )}
          </div>
        </div>
      </header>
      <div className={styles.body}>
        <aside
          className={`${styles.sidebar} ${pref.collapsed ? styles.collapsed : ""} ${mobileOpen ? styles.mobileOpen : ""}`}
        >
          <div className={styles.personal}>
            <small className={styles.personalLabel}>
              <Star size={14} />
              즐겨찾기
            </small>
            {!pref.favorites.length && (
              <p className={styles.personalEmpty}>
                제목의 별을 눌러 추가하세요
              </p>
            )}
            {pref.favorites.map((f) => {
              const s = leaves.find((l) => l.screenId === f);
              return s && (!mobile || s.mobile) ? (
                <div
                  data-testid={`favorite-item-${f.toLowerCase()}`}
                  draggable
                  key={f}
                  onDragStart={(e) => e.dataTransfer.setData("text/plain", f)}
                  onDragOver={(e) => e.preventDefault()}
                  onDrop={(e) => {
                    e.preventDefault();
                    pref.reorder(e.dataTransfer.getData("text/plain"), f);
                  }}
                >
                  {sideLink(s)}
                </div>
              ) : null;
            })}
            <small className={styles.personalLabel}>
              <Clock3 size={14} />
              최근 본 화면
            </small>
            {pref.recent.map((f) => {
              const s = leaves.find((l) => l.screenId === f);
              return s && (!mobile || s.mobile) ? sideLink(s) : null;
            })}
          </div>
          <nav aria-label="주 메뉴">
            {menus.map((group) => {
              const Icon = icons[group.icon ?? ""] ?? Folder;
              const children = group.children.filter(
                (s) => !mobile || s.mobile,
              );
              if (!children.length) return null;
              return (
                <div
                  data-testid={`menu-group-${group.menuId}`}
                  key={group.menuId}
                  className={styles.menuGroup}
                  onMouseEnter={() => setHover(group.menuId)}
                  onMouseLeave={() => setHover(undefined)}
                >
                  <button
                    data-testid={`menu-group-toggle-${group.menuId}`}
                    title={group.name}
                    aria-expanded={
                      !group.children.some((s) => s.screenId === "UI-16") &&
                      expanded === group.menuId
                    }
                    className={`${styles.groupButton} ${parent?.menuId === group.menuId ? styles.currentGroup : ""}`}
                    onClick={() => {
                      if (group.children.some((s) => s.screenId === "UI-16"))
                        go("UI-16");
                      else
                        setExpanded((v) =>
                          v === group.menuId ? undefined : group.menuId,
                        );
                    }}
                  >
                    <Icon size={18} />
                    <span>{group.name}</span>
                    {!group.children.some((s) => s.screenId === "UI-16") && (
                      <ChevronDown size={14} />
                    )}
                  </button>
                  <div
                    className={`${styles.children} ${expanded === group.menuId && !group.children.some((s) => s.screenId === "UI-16") ? styles.expanded : ""} ${hover === group.menuId && !group.children.some((s) => s.screenId === "UI-16") ? styles.hovered : ""}`}
                  >
                    {children.map(sideLink)}
                  </div>
                </div>
              );
            })}
          </nav>
          <button
            data-testid="sidebar-collapse"
            className={styles.collapseButton}
            onClick={() => {
              pref.set({ collapsed: !pref.collapsed });
              setMobileOpen(false);
            }}
          >
            <PanelLeftClose size={18} />
            <span>메뉴 접기</span>
          </button>
        </aside>
        <main data-testid="app-main" className={styles.main}>
          <div className={styles.tabbar}>
            {tabs.map((t) => (
              <div
                data-testid={`screen-tab-${t.toLowerCase()}`}
                className={t === id ? styles.selectedTab : ""}
                key={t}
              >
                <button
                  data-testid={`screen-tab-open-${t.toLowerCase()}`}
                  onClick={() => go(t)}
                >
                  {dirty && t === "UI-27" ? "● " : ""}
                  {leaves.find((s) => s.screenId === t)?.name ?? t}
                </button>
                {t !== "UI-16" && (
                  <button
                    data-testid={`screen-tab-close-${t.toLowerCase()}`}
                    aria-label={`${t} 탭 닫기`}
                    onClick={() => closeTab(t)}
                  >
                    ×
                  </button>
                )}
              </div>
            ))}
          </div>
          <div className={styles.content}>
            <ErrorBanner
              error={error}
              retry={() => {
                setError(undefined);
                setRevision((v) => v + 1);
              }}
            />
            {loading && !menus.length ? (
              <Loading />
            ) : !menus.length && !error ? (
              <section className="panel empty">
                <Folder size={32} />
                <h1>표시할 메뉴가 없습니다</h1>
                <p>메뉴 데이터를 확인한 뒤 다시 불러와 주세요.</p>
                <button onClick={() => setRevision((v) => v + 1)}>
                  다시 시도
                </button>
              </section>
            ) : restricted ? (
              <section className="panel empty">
                <h1>PC에서 이용해 주세요</h1>
                <button
                  data-testid="restricted-home"
                  onClick={() => go("UI-16")}
                >
                  대시보드로
                </button>
              </section>
            ) : (
              <>
                <div className={styles.title}>
                  <small className={styles.breadcrumb}>
                    <LayoutGrid size={14} /> {parent?.name}{" "}
                    <ChevronRight size={12} /> {screen?.name ?? id}
                  </small>
                  <div>
                    <h1>{screen?.name ?? id}</h1>
                    <button
                      data-testid="favorite-toggle"
                      aria-label="즐겨찾기"
                      aria-pressed={pref.favorites.includes(id)}
                      disabled={
                        !pref.favorites.includes(id) &&
                        pref.favorites.length >= 10
                      }
                      onClick={() => pref.star(id)}
                    >
                      <Star
                        size={18}
                        fill={
                          pref.favorites.includes(id) ? "currentColor" : "none"
                        }
                      />
                    </button>
                    <span className="badge">{id}</span>
                    <div ref={setActionsHost} className={styles.titleActions} />
                  </div>
                </div>
                {screen && periodType(id) !== "NONE" && (
                  <Period
                    id={id}
                    value={periods[id] ?? todayMonth()}
                    onChange={(value) =>
                      setPeriods((s) => ({ ...s, [id]: value }))
                    }
                  />
                )}{" "}
                {id !== "UI-27" && (screen || id === "UI-22") && (
                  <section className={`panel ${styles.placeholder}`}>
                    <span className={styles.placeholderIcon}>
                      <FileClock size={28} strokeWidth={1.5} />
                    </span>
                    <p>이 화면은 다음 단계에서 만듭니다.</p>
                  </section>
                )}
                {!screen && id !== "UI-22" && !loading && (
                  <section className="panel">
                    <p>화면을 찾을 수 없습니다.</p>
                    <button
                      data-testid="not-found-home"
                      onClick={() => go("UI-16")}
                    >
                      대시보드로
                    </button>
                  </section>
                )}
              </>
            )}
            {codeVisited && (
              <div
                data-testid="common-codes-panel"
                hidden={id !== "UI-27" || restricted}
              >
                <CommonCodes
                  actionsHost={actionsHost}
                  active={id === "UI-27" && !restricted}
                  guard={guard}
                  setDirty={setDirty}
                  toast={success}
                />
              </div>
            )}
          </div>
        </main>
      </div>
      {toast && (
        <div className="toast" role="status">
          {toast}
        </div>
      )}
      {pending && (
        <Modal
          small
          title="저장하지 않은 내용이 있습니다. 나가면 사라집니다."
          onClose={() => setPending(undefined)}
        >
          <div className="actions">
            <button
              data-testid="unsaved-continue"
              onClick={() => setPending(undefined)}
            >
              계속 작성
            </button>
            <button
              data-testid="unsaved-leave"
              className="primary"
              onClick={() => {
                const action = pending;
                setPending(undefined);
                setDirty(false);
                window.dispatchEvent(new Event("si.discard"));
                action();
              }}
            >
              나가기
            </button>
          </div>
        </Modal>
      )}
      {overflow && (
        <Modal
          small
          title="가장 오래 안 본 탭을 닫고 새 화면을 여나요?"
          onClose={() => setOverflow(undefined)}
        >
          <p>
            {leaves.find((s) => s.screenId === overflow.victim)?.name ??
              overflow.victim}
          </p>
          <div className="actions">
            <button
              data-testid="tab-overflow-cancel"
              onClick={() => setOverflow(undefined)}
            >
              취소
            </button>
            <button
              data-testid="tab-overflow-confirm"
              className="primary"
              onClick={() => {
                setTabs((prev) => prev.filter((x) => x !== overflow.victim));
                navigate(`/${overflow.target}`);
                setOverflow(undefined);
              }}
            >
              닫기 확인
            </button>
          </div>
        </Modal>
      )}
      {search && (
        <Modal title="화면 검색" onClose={() => setSearch(false)}>
          <input
            data-testid="screen-search-input"
            aria-label="화면 이름"
            autoFocus
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
          />
          <div className={styles.searchResults}>
            {leaves
              .filter(
                (s) =>
                  (!mobile || s.mobile) &&
                  `${s.name} ${s.screenId}`
                    .toLowerCase()
                    .includes(keyword.toLowerCase()),
              )
              .map(sideLink)}
          </div>
          <small>Esc로 닫기</small>
        </Modal>
      )}
    </div>
  );
}
function Period({
  id,
  value,
  onChange,
}: {
  id: string;
  value: string;
  onChange: (s: string) => void;
}) {
  const type = periodType(id);
  const [rows, setRows] = useState<{ month: string; status: string }[]>([]);
  const [error, setError] = useState<unknown>();
  const [loading, setLoading] = useState(false);
  const [revision, setRevision] = useState(0);
  useEffect(() => {
    if (type !== "MONTH") return;
    const c = new AbortController();
    setLoading(true);
    setError(undefined);
    api<{ month: string; status: string }[]>(
      `/api/month-close?from=${value}&to=${value}`,
      "GET",
      undefined,
      c.signal,
    )
      .then(setRows)
      .catch((e) => {
        if (!c.signal.aborted) setError(e);
      })
      .finally(() => {
        if (!c.signal.aborted) setLoading(false);
      });
    return () => c.abort();
  }, [value, type, revision]);
  const closed = rows.some((r) => r.month === value && r.status === "CLOSED");
  function move(delta: number) {
    const [y, m] = value.split("-").map(Number);
    const d = new Date(
      Date.UTC(y, m - 1 + (type === "MONTH" ? delta : delta * 12), 1),
    );
    onChange(
      `${d.getUTCFullYear()}-${String(d.getUTCMonth() + 1).padStart(2, "0")}`,
    );
  }
  return (
    <section className="panel periodPanel">
      <ErrorBanner error={error} retry={() => setRevision((v) => v + 1)} />
      <div className="periodControls">
        <Calendar size={18} aria-hidden="true" />
        <small>조회 기간</small>
        <button aria-label="이전 기간" onClick={() => move(-1)}>
          ‹
        </button>
        {type === "YEAR" ? (
          <strong>{value.slice(0, 4)}년</strong>
        ) : (
          <input
            aria-label="조회 기간"
            type="month"
            value={value}
            onChange={(e) => {
              if (e.target.value) onChange(e.target.value);
            }}
          />
        )}
        <button aria-label="다음 기간" onClick={() => move(1)}>
          ›
        </button>
        {type === "YEAR_CUM" && <span>1~{Number(value.slice(5))}월 누계</span>}
        {type === "MONTH" &&
          (loading ? (
            <small>불러오는 중…</small>
          ) : (
            !error && (
              <span className={`badge ${closed ? "locked" : "info"}`}>
                {closed ? "마감" : "작업중"}
              </span>
            )
          ))}
      </div>
      {closed && !loading && !error && (
        <div className="notice">
          {value}는 마감되었습니다. 고칠 내용은 다음 달 전월 조정으로
          입력하세요.
        </div>
      )}
    </section>
  );
}
