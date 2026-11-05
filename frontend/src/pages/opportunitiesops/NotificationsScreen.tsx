import { useCallback, useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { Badge, Button, EmptyState, Skeleton } from "../../shared/Controls";
import { ApiFailure } from "../../shared/api";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { isScreenId } from "../shared/registry";
import {
  getNotification,
  getNotificationUnreadCount,
  listNotifications,
  markAllNotificationsRead,
  markNotificationRead,
  type NotificationItem,
  type NotificationPage,
} from "./notificationClient";
import styles from "./Notifications.module.css";

export const notificationPermissionEvent = "sicost:permissions-changed";
export const notificationSessionEvent = "sicost:session-ended";
export const notificationReadEvent = "sicost:notifications-read";
const failure = (error: unknown) =>
  error instanceof ApiFailure
    ? error
    : new ApiFailure("알림을 조회하지 못했습니다.", null, null);

export default function NotificationsScreen({
  popover = false,
  onClose,
}: {
  popover?: boolean;
  onClose?: () => void;
}) {
  const navigate = useNavigate();
  const [page, setPage] = useState(0),
    [unread, setUnread] = useState(false),
    [draftUnread, setDraftUnread] = useState(false);
  const [data, setData] = useState<NotificationPage | null>(null),
    [count, setCount] = useState<number | null>(null);
  const [error, setError] = useState<ApiFailure | null>(null),
    [loading, setLoading] = useState(true),
    [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");
  const serial = useRef(0),
    mounted = useRef(true),
    writing = useRef(false);
  const request = useRef<AbortController | null>(null);
  const clear = useCallback(() => {
    serial.current++;
    request.current?.abort();
    setData(null);
    setCount(null);
    setMessage("");
  }, []);
  const reload = useCallback(async () => {
    const generation = ++serial.current;
    request.current?.abort();
    const controller = new AbortController();
    request.current = controller;
    setLoading(true);
    setData(null);
    setCount(null);
    try {
      const [result, unreadCount] = await Promise.all([
        listNotifications(
          popover ? 0 : page,
          popover ? false : unread,
          controller.signal,
        ),
        getNotificationUnreadCount(controller.signal),
      ]);
      if (!mounted.current || generation !== serial.current) return;
      setData(result);
      setCount(unreadCount.count);
      setError(null);
    } catch (e) {
      if (!mounted.current || generation !== serial.current) return;
      setData(null);
      setCount(null);
      setError(failure(e));
      throw e;
    } finally {
      if (mounted.current && generation === serial.current) setLoading(false);
    }
  }, [page, unread, popover]);
  useEffect(() => {
    mounted.current = true;
    const refresh = () => {
      clear();
      void reload().catch(() => {});
    };
    const logout = () => {
      clear();
      setLoading(false);
      setError(new ApiFailure("로그인이 필요합니다.", 401, null));
    };
    void reload().catch(() => {});
    const timer = window.setInterval(refresh, 30000);
    window.addEventListener(notificationPermissionEvent, refresh);
    window.addEventListener(notificationSessionEvent, logout);
    window.addEventListener("focus", refresh);
    return () => {
      mounted.current = false;
      serial.current++;
      request.current?.abort();
      window.clearInterval(timer);
      window.removeEventListener(notificationPermissionEvent, refresh);
      window.removeEventListener(notificationSessionEvent, logout);
      window.removeEventListener("focus", refresh);
    };
  }, [reload, clear]);
  const act = async (row?: NotificationItem, move = false) => {
    if (writing.current) return;
    writing.current = true;
    setBusy(true);
    setError(null);
    setMessage("");
    try {
      if (row) await markNotificationRead(row.id);
      else await markAllNotificationsRead();
      const target = row && move ? await getNotification(row.id) : null;
      await reload();
      if (!mounted.current) return;
      window.dispatchEvent(new Event(notificationReadEvent));
      if (target && !target.resolved) {
        if (
          !target.screenId ||
          target.screenId === "UI-22" ||
          !isScreenId(target.screenId)
        ) {
          throw new ApiFailure("대상 화면에 접근할 수 없습니다.", 403, null);
        }
        const params = new URLSearchParams();
        for (const [key, value] of Object.entries(target.linkParams ?? {})) {
          if (
            [
              "open",
              "id",
              "projectId",
              "opportunityId",
              "partId",
              "month",
              "year",
            ].includes(key) &&
            (typeof value === "number" || typeof value === "string") &&
            String(value).length <= 80
          )
            params.set(key, String(value));
        }
        onClose?.();
        navigate(
          { pathname: `/${target.screenId}`, search: params.toString() },
          {
            state:
              target.linkParams?.workspace === "candidates"
                ? { workspace: "candidates" }
                : undefined,
          },
        );
      } else setMessage("읽음 처리했습니다.");
    } catch (e) {
      if (mounted.current) {
        clear();
        setError(failure(e));
      }
    } finally {
      writing.current = false;
      if (mounted.current) setBusy(false);
    }
  };
  return (
    <section
      data-screen-id="SCR-UI-34"
      data-testid={popover ? "notifications-popover" : "notifications-screen"}
      aria-label={popover ? "알림" : "내 알림"}
      className={`${styles.screen} ${popover ? styles.popover : ""}`}
    >
      <header className={styles.heading}>
        <h1>{popover ? "알림" : "내 알림"}</h1>
        {count !== null && (
          <Badge tone={count > 0 ? "info" : "neutral"}>
            미읽음 {count >= 100 ? "99+" : count}
          </Badge>
        )}
        {popover && (
          <Button data-testid="notifications-close" onClick={onClose}>
            닫기
          </Button>
        )}
        <Button
          data-testid="notifications-read-all"
          disabled={loading || !data || count === 0}
          pending={busy}
          onClick={() => void act()}
        >
          모두 읽음
        </Button>
      </header>
      {!popover && (
        <form
          className={styles.filters}
          onSubmit={(event) => {
            event.preventDefault();
            setPage(0);
            setUnread(draftUnread);
            if (page === 0 && unread === draftUnread)
              void reload().catch(() => {});
          }}
        >
          <label>
            <input
              data-testid="notifications-unread-filter"
              type="checkbox"
              checked={draftUnread}
              onChange={(event) => setDraftUnread(event.target.checked)}
            />
            읽지 않은 알림만
          </label>
          <Button
            data-testid="notifications-query"
            type="submit"
            disabled={busy}
          >
            조회
          </Button>
        </form>
      )}
      <ErrorBanner
        error={error}
        onRetry={() => void reload().catch(() => {})}
        pending={loading || busy}
      />
      {message && <p role="status">{message}</p>}
      {loading ? (
        <Skeleton />
      ) : (
        data && (
          <>
            {data.items.length === 0 ? (
              <EmptyState
                reason="알림이 없습니다."
                actionLabel="새로고침"
                onAction={() => void reload().catch(() => {})}
              />
            ) : (
              <ul className={styles.list}>
                {data.items.map((row) => (
                  <li
                    data-testid={`notification-row-${row.id}`}
                    key={row.id}
                    className={styles.row}
                  >
                    <div className={styles.content}>
                      <strong>{row.title}</strong>
                      {row.message && <p>{row.message}</p>}
                      <time dateTime={row.createdAt}>
                        {row.createdAt.replace("T", " ")}
                      </time>
                    </div>
                    <div className={styles.actions}>
                      <Badge tone={row.read ? "neutral" : "info"}>
                        {row.read ? "읽음" : "미읽음"}
                      </Badge>
                      {row.resolved && <Badge tone="success">해소됨</Badge>}
                      {!row.read && (
                        <Button
                          data-testid={`notification-read-${row.id}`}
                          disabled={busy}
                          onClick={() => void act(row)}
                        >
                          읽음 처리
                        </Button>
                      )}
                      {!row.resolved && row.screenId && (
                        <Button
                          data-testid={`notification-target-${row.id}`}
                          disabled={busy}
                          onClick={() => void act(row, true)}
                        >
                          대상으로 이동
                        </Button>
                      )}
                    </div>
                  </li>
                ))}
              </ul>
            )}
            {!popover && (
              <footer className={styles.actions}>
                <span>총 {data.total.toLocaleString("ko-KR")}건</span>
                <Button
                  data-testid="notifications-previous"
                  disabled={busy || page === 0}
                  onClick={() => setPage((p) => p - 1)}
                >
                  이전
                </Button>
                <span>{page + 1}</span>
                <Button
                  data-testid="notifications-next"
                  disabled={busy || (page + 1) * 20 >= data.total}
                  onClick={() => setPage((p) => p + 1)}
                >
                  다음
                </Button>
              </footer>
            )}
          </>
        )
      )}
      {popover && (
        <Button
          data-testid="notifications-view-all"
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
export function NotificationPopover({ onClose }: { onClose: () => void }) {
  return <NotificationsScreen popover onClose={onClose} />;
}
