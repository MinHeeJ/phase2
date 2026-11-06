import { useCallback, useEffect, useRef, useState } from "react";
import { Bell } from "lucide-react";
import { useNavigate } from "react-router-dom";
import { Badge, Button, EmptyState, Skeleton } from "../../shared/Controls";
import { ApiFailure } from "../../shared/api";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { useAdminRead } from "../admin/useAdminResource";
import { isScreenId } from "../shared/registry";
import {
  getNotification,
  getNotificationUnreadCount,
  listNotifications,
  saveNotificationsReadAll,
  updateNotificationRead,
  type SecurityNotification,
  type SecurityNotificationRead,
} from "./notificationClient";
import styles from "./Notifications.module.css";

const failure = (error: unknown) =>
  error instanceof ApiFailure
    ? error
    : new ApiFailure("알림을 처리하지 못했습니다.", null, null);
function targetPath(target: SecurityNotificationRead) {
  const screen = target.targetScreenId;
  if (!screen || !isScreenId(screen) || screen === "UI-22") return null;
  const query = new URLSearchParams();
  if (target.targetId != null) query.set("open", String(target.targetId));
  return `/${screen}${query.size ? `?${query}` : ""}`;
}
function NotificationList({
  compact = false,
  permissionVersion,
  onClose,
  onRead,
}: {
  compact?: boolean;
  permissionVersion?: string | number;
  onClose?: () => void;
  onRead?: () => void;
}) {
  const navigate = useNavigate();
  const [unreadOnly, setUnreadOnly] = useState(false);
  const [filters, setFilters] = useState({
    unreadOnly: false,
    page: 0,
    size: 20,
  });
  const [selected, setSelected] = useState<SecurityNotification | null>(null);
  const [pending, setPending] = useState(false);
  const busy = useRef(false);
  const [error, setError] = useState<ApiFailure | null>(null);
  const [success, setSuccess] = useState<string | null>(null);
  const [revision, setRevision] = useState(0);
  const read = useAdminRead(
    useCallback(async () => {
      // A permission revision discards all previously rendered notification content.
      void permissionVersion;
      void revision;
      const [page, count] = await Promise.all([
        listNotifications(compact ? { page: 0, size: 20 } : filters),
        getNotificationUnreadCount(),
      ]);
      return { ...page, unreadCount: count.unreadCount };
    }, [compact, filters, permissionVersion, revision]),
  );
  useEffect(() => {
    const refresh = () => {
      setSelected(null);
      setSuccess(null);
      setRevision((v) => v + 1);
    };
    window.addEventListener("sicost:permissions-changed", refresh);
    window.addEventListener("focus", refresh);
    const poll = window.setInterval(refresh, 60000);
    return () => {
      window.removeEventListener("sicost:permissions-changed", refresh);
      window.removeEventListener("focus", refresh);
      window.clearInterval(poll);
    };
  }, []);
  useEffect(() => {
    setSelected(null);
  }, [permissionVersion]);
  const act = async (operation: () => Promise<void>) => {
    if (busy.current) return;
    busy.current = true;
    setPending(true);
    setError(null);
    setSuccess(null);
    try {
      await operation();
    } catch (cause) {
      const problem = failure(cause);
      setSelected(null);
      if (problem.status === 403 || problem.status === 404) {
        await read.reload().catch(() => {});
        onRead?.();
      }
      setError(problem);
    } finally {
      busy.current = false;
      setPending(false);
    }
  };
  const mark = (row: SecurityNotification, move: boolean) =>
    act(async () => {
      const result = await updateNotificationRead(row.id);
      if (!result.read)
        throw new ApiFailure("읽음 상태를 저장하지 못했습니다.", null, null);
      await read.reload();
      onRead?.();
      setSelected(null);
      if (move) {
        const path = targetPath(result);
        if (!path)
          throw new ApiFailure("대상 화면에 접근할 수 없습니다.", 403, null);
        onClose?.();
        navigate(path);
      } else setSuccess("읽음 처리했습니다.");
    });
  const all = () =>
    act(async () => {
      await saveNotificationsReadAll();
      await read.reload();
      onRead?.();
      setSelected(null);
      setSuccess("현재 열람 가능한 알림을 모두 읽음 처리했습니다.");
    });
  const detail = (row: SecurityNotification) =>
    act(async () => {
      setSelected(null);
      setSelected(await getNotification(row.id));
    });
  const visible = !read.loading && !read.error && read.data;
  return (
    <section
      className={styles.screen}
      data-screen-id="SCR-UI-34"
      data-testid={compact ? "notifications-popover" : "notifications-screen"}
      aria-label={compact ? "알림" : "내 알림"}
    >
      <header className={styles.heading}>
        {compact ? <h2>알림</h2> : <h1>내 알림</h1>}
        <div className={styles.actions}>
          <Button
            data-testid="notifications-read-all"
            pending={pending}
            disabled={!visible || read.data?.unreadCount === 0}
            onClick={() => void all()}
          >
            모두 읽음
          </Button>
          {compact && (
            <Button data-testid="notifications-close" onClick={onClose}>
              닫기
            </Button>
          )}
        </div>
      </header>
      {!compact && (
        <form
          className={styles.filters}
          onSubmit={(event) => {
            event.preventDefault();
            setSelected(null);
            setFilters({ unreadOnly, page: 0, size: 20 });
          }}
        >
          <label>
            <input
              data-testid="notifications-unread-only"
              type="checkbox"
              checked={unreadOnly}
              onChange={(event) => setUnreadOnly(event.target.checked)}
            />
            읽지 않은 알림만
          </label>
          <Button
            data-testid="notifications-query"
            type="submit"
            disabled={pending}
          >
            조회
          </Button>
        </form>
      )}
      <ErrorBanner
        error={read.error}
        onRetry={() => void read.reload().catch(() => {})}
      />
      <ErrorBanner error={error} />
      {success && <p role="status">{success}</p>}
      {read.loading && (
        <div aria-label="알림 불러오는 중">
          <Skeleton />
        </div>
      )}
      {visible && (
        <>
          <p className={styles.summary}>
            총 {read.data!.total.toLocaleString("ko-KR")}건 · 미읽음{" "}
            {read.data!.unreadCount.toLocaleString("ko-KR")}건
          </p>
          {read.data!.items.length === 0 ? (
            <EmptyState
              reason="알림이 없습니다."
              actionLabel="새로고침"
              onAction={() => void read.reload().catch(() => {})}
            />
          ) : (
            <ul className={styles.list}>
              {read.data!.items.map((row) => (
                <li
                  key={row.id}
                  data-testid={`notification-row-${row.id}`}
                  className={row.read ? styles.read : styles.unread}
                >
                  <div className={styles.rowHeading}>
                    <Button
                      data-testid={`notification-detail-${row.id}`}
                      variant="text"
                      disabled={pending}
                      onClick={() => void detail(row)}
                    >
                      {row.title}
                    </Button>
                    <Badge tone={row.read ? "neutral" : "info"}>
                      {row.read ? "읽음" : "미읽음"}
                    </Badge>
                    {row.resolved && <Badge tone="success">해소</Badge>}
                  </div>
                  <p>{row.content}</p>
                  <div className={styles.rowFooter}>
                    <time dateTime={row.createdAt}>
                      {row.createdAt.replace("T", " ")}
                    </time>
                    <div className={styles.actions}>
                      {!row.read && (
                        <Button
                          data-testid={`notification-read-${row.id}`}
                          small
                          disabled={pending}
                          onClick={() => void mark(row, false)}
                        >
                          읽음
                        </Button>
                      )}
                      {!row.resolved && row.targetScreenId && (
                        <Button
                          data-testid={`notification-target-${row.id}`}
                          small
                          disabled={pending}
                          onClick={() => void mark(row, true)}
                        >
                          대상으로 이동
                        </Button>
                      )}
                    </div>
                  </div>
                </li>
              ))}
            </ul>
          )}
          {!compact && (
            <nav aria-label="알림 페이지" className={styles.pagination}>
              <Button
                data-testid="notifications-previous"
                disabled={filters.page === 0 || pending}
                onClick={() => {
                  setSelected(null);
                  setFilters({ ...filters, page: filters.page - 1 });
                }}
              >
                이전
              </Button>
              <span>{filters.page + 1} 페이지</span>
              <Button
                data-testid="notifications-next"
                disabled={
                  (filters.page + 1) * filters.size >= read.data!.total ||
                  pending
                }
                onClick={() => {
                  setSelected(null);
                  setFilters({ ...filters, page: filters.page + 1 });
                }}
              >
                다음
              </Button>
            </nav>
          )}
        </>
      )}
      {visible && selected && (
        <section
          className={styles.detail}
          data-testid="notification-detail-panel"
          aria-label="알림 상세"
        >
          <h2>{selected.title}</h2>
          <p>{selected.content}</p>
          <time>{selected.createdAt}</time>
          <Button
            data-testid="notification-detail-close"
            onClick={() => setSelected(null)}
          >
            상세 닫기
          </Button>
        </section>
      )}
      {compact && (
        <Button
          data-testid="notifications-full-view"
          variant="text"
          onClick={() => {
            onClose?.();
            navigate("/UI-34");
          }}
        >
          알림 전체 보기
        </Button>
      )}
    </section>
  );
}
export default function NotificationsScreen({
  permissionVersion,
}: {
  permissionVersion?: string | number;
}) {
  return <NotificationList permissionVersion={permissionVersion} />;
}
export function NotificationsPopover(props: {
  permissionVersion?: string | number;
  onClose: () => void;
  onRead?: () => void;
}) {
  return (
    <div className={styles.popover}>
      <NotificationList compact {...props} />
    </div>
  );
}
export function NotificationBell({
  permissionVersion,
}: {
  permissionVersion?: string | number;
}) {
  const [open, setOpen] = useState(false);
  const bell = useRef<HTMLButtonElement>(null);
  const count = useAdminRead(
    useCallback(() => {
      void permissionVersion;
      return getNotificationUnreadCount();
    }, [permissionVersion]),
  );
  const refresh = useCallback(() => {
    void count.reload().catch(() => {});
  }, [count.reload]);
  useEffect(() => {
    window.addEventListener("sicost:permissions-changed", refresh);
    window.addEventListener("focus", refresh);
    const poll = window.setInterval(refresh, 60000);
    return () => {
      window.removeEventListener("sicost:permissions-changed", refresh);
      window.removeEventListener("focus", refresh);
      window.clearInterval(poll);
    };
  }, [refresh]);
  const close = () => {
    setOpen(false);
    bell.current?.focus();
  };
  return (
    <div
      className={styles.bell}
      data-testid="notification-bell-container"
      onKeyDown={(event) => {
        if (event.key === "Escape") close();
      }}
    >
      <button
        ref={bell}
        className={styles.bellButton}
        data-testid="notification-bell"
        aria-label="알림"
        aria-expanded={open}
        onClick={() => setOpen(!open)}
      >
        <Bell size={20} aria-hidden="true" />
        {!count.loading &&
          !count.error &&
          count.data &&
          count.data.unreadCount > 0 && (
            <span
              className={styles.count}
              aria-label={`미읽음 ${count.data.unreadCount}건`}
            >
              {count.data.unreadCount > 99 ? "99+" : count.data.unreadCount}
            </span>
          )}
      </button>
      {count.error && <span role="status">알림 조회 실패</span>}
      {open && (
        <NotificationsPopover
          permissionVersion={permissionVersion}
          onClose={close}
          onRead={refresh}
        />
      )}
    </div>
  );
}
