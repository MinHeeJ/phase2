import { useCallback, useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { Badge, Button, Skeleton } from "../../shared/Controls";
import { ApiFailure } from "../../shared/api";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { ScreenHeading } from "../shared/ScreenHeading";
import {
  listNotifications,
  notificationCount,
  readAllNotifications,
  readNotification,
  type NotificationItem,
  type NotificationPage,
} from "./notificationClient";
import styles from "./Notifications.module.css";

export default function NotificationsScreen({
  popover = false,
  onClose,
}: {
  popover?: boolean;
  onClose?: () => void;
}) {
  const navigate = useNavigate();
  const [page, setPage] = useState(0),
    [unread, setUnread] = useState(false);
  const [data, setData] = useState<NotificationPage | null>(null),
    [count, setCount] = useState<number | null>(null);
  const [loading, setLoading] = useState(true),
    [pending, setPending] = useState(false);
  const [error, setError] = useState<ApiFailure | null>(null),
    [message, setMessage] = useState("");
  const request = useRef<AbortController>();
  const busy = useRef(false);
  const reload = useCallback(async () => {
    request.current?.abort();
    const controller = new AbortController();
    request.current = controller;
    setLoading(true);
    setError(null);
    setData(null);
    setCount(null);
    try {
      const [result, total] = await Promise.all([
        listNotifications(popover ? 0 : page, unread, controller.signal),
        notificationCount(controller.signal),
      ]);
      if (!controller.signal.aborted) {
        setData(result);
        setCount(total.count);
      }
    } catch (e) {
      if (!controller.signal.aborted)
        setError(
          e instanceof ApiFailure
            ? e
            : new ApiFailure("알림 조회에 실패했습니다.", null, null),
        );
    } finally {
      if (!controller.signal.aborted) setLoading(false);
    }
  }, [page, unread, popover]);
  useEffect(() => {
    void reload();
    return () => request.current?.abort();
  }, [reload]);
  useEffect(() => {
    const refresh = () => {
      void reload();
    };
    window.addEventListener("permissions-changed", refresh);
    return () => window.removeEventListener("permissions-changed", refresh);
  }, [reload]);
  async function run(item?: NotificationItem) {
    if (busy.current) return;
    busy.current = true;
    setPending(true);
    setError(null);
    setMessage("");
    try {
      if (item) await readNotification(item.notificationId);
      else await readAllNotifications();
      await reload();
      setMessage("읽음 처리되었습니다.");
      if (item?.targetScreenId && /^UI-\d{2}$/.test(item.targetScreenId)) {
        const query = new URLSearchParams();
        for (const [key, value] of Object.entries(item.target ?? {})) {
          if (
            /^[a-zA-Z][a-zA-Z0-9_]{0,49}$/.test(key) &&
            (typeof value === "string" || typeof value === "number") &&
            String(value).length <= 200
          )
            query.set(key, String(value));
        }
        navigate(`/${item.targetScreenId}${query.size ? `?${query}` : ""}`);
        onClose?.();
      }
    } catch (e) {
      setData(null);
      setCount(null);
      setError(
        e instanceof ApiFailure
          ? e
          : new ApiFailure("읽음 처리에 실패했습니다.", null, null),
      );
    } finally {
      busy.current = false;
      setPending(false);
    }
  }
  return (
    <section
      data-screen-id="UI-34"
      data-testid={popover ? "notifications-popover" : "notifications-screen"}
      className={styles.screen}
      aria-busy={loading || pending}
    >
      {popover ? (
        <div className={styles.toolbar}>
          <h2>알림 {count === null ? "" : count > 99 ? "99+" : count}</h2>
          <Button data-testid="notifications-close" onClick={onClose}>
            닫기
          </Button>
        </div>
      ) : (
        <ScreenHeading screenId="UI-34" name="내 알림" />
      )}
      <div className={styles.toolbar}>
        {!popover && (
          <label>
            <input
              data-testid="notifications-unread-filter"
              type="checkbox"
              checked={unread}
              onChange={(e) => {
                setPage(0);
                setUnread(e.target.checked);
              }}
            />{" "}
            읽지 않은 알림만
          </label>
        )}
        <Button
          data-testid="notifications-refresh"
          disabled={loading || pending}
          onClick={() => void reload()}
        >
          조회
        </Button>
        <Button
          data-testid="notifications-read-all"
          disabled={loading || pending || !data?.items.length}
          onClick={() => void run()}
        >
          모두 읽음
        </Button>
        {count !== null && (
          <span aria-label="미읽음 수">{count > 99 ? "99+" : count}</span>
        )}
      </div>
      <ErrorBanner error={error} onRetry={() => void reload()} />
      {loading && <Skeleton />}
      {message && <p role="status">{message}</p>}
      {!loading && data?.items.length === 0 && (
        <div className={styles.empty}>
          <h2>새 알림이 없습니다</h2>
          <p>현재 열람할 수 있는 알림이 없습니다.</p>
        </div>
      )}
      {data?.items.map((item) => (
        <article
          key={item.notificationId}
          className={styles.row}
          data-testid={`notification-${item.notificationId}`}
        >
          <div>
            <strong>{item.title}</strong>
            <p>{item.message}</p>
            <time>{item.createdAt.replace("T", " ")}</time>
          </div>
          <Badge tone={item.read ? "neutral" : "info"}>
            {item.read ? "읽음" : "미읽음"}
          </Badge>
          <Button
            data-testid={`notification-open-${item.notificationId}`}
            disabled={pending}
            onClick={() => void run(item)}
          >
            {item.targetScreenId ? "대상으로 이동" : "읽음"}
          </Button>
        </article>
      ))}
      {popover ? (
        <Button
          data-testid="notifications-all"
          onClick={() => {
            navigate("/UI-34");
            onClose?.();
          }}
        >
          알림 전체 보기
        </Button>
      ) : (
        <nav className={styles.toolbar} aria-label="알림 페이지">
          <Button
            data-testid="notifications-previous"
            disabled={page === 0 || loading || pending}
            onClick={() => setPage((p) => p - 1)}
          >
            이전
          </Button>
          <span>{page + 1}</span>
          <Button
            data-testid="notifications-next"
            disabled={
              !data ||
              (page + 1) * data.size >= data.total ||
              loading ||
              pending
            }
            onClick={() => setPage((p) => p + 1)}
          >
            다음
          </Button>
        </nav>
      )}
    </section>
  );
}
