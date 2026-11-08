import { useCallback, useState } from "react";
import { Button } from "../../shared/Controls";
import { Can } from "../../shared/Can";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { ApiFailure } from "../../shared/api";
import { useAdminRead } from "../admin/useAdminResource";
import { accessHistory, downloadAdmin, query } from "./client";
import styles from "./SystemAdministration.module.css";

export function AccessHistoryPanel() {
  const [filters, setFilters] = useState({
    userId: "",
    from: "",
    to: "",
    type: "",
    result: "",
    screenId: "",
  });
  const [search, setSearch] = useState<Record<string, string | number>>({
    page: 1,
  });
  const history = useAdminRead(
    useCallback(() => accessHistory(search), [search]),
  );
  const [error, setError] = useState<ApiFailure | null>(null);
  return (
    <section data-testid="access-history-panel" className={styles.panel}>
      <form
        className={styles.toolbar}
        onSubmit={(e) => {
          e.preventDefault();
          setSearch({ ...filters, page: 1 });
        }}
      >
        {(["userId", "from", "to", "type", "result", "screenId"] as const).map(
          (k) => (
            <label key={k}>
              {
                {
                  userId: "사용자 ID",
                  from: "시작일",
                  to: "종료일",
                  type: "작업 유형",
                  result: "결과",
                  screenId: "화면",
                }[k]
              }
              <input
                data-testid={`access-filter-${k.toLowerCase()}`}
                type={
                  k === "from" || k === "to"
                    ? "date"
                    : k === "userId"
                      ? "number"
                      : "text"
                }
                value={filters[k]}
                onChange={(e) =>
                  setFilters((v) => ({ ...v, [k]: e.target.value }))
                }
              />
            </label>
          ),
        )}
        <Button type="submit" data-testid="access-history-search">
          검색
        </Button>
        <Can perm="UI-23.download">
          <Button
            type="button"
            data-testid="access-history-export"
            onClick={() =>
              void downloadAdmin(
                `/api/admin/access-history/export?${query(search)}`,
              ).catch((e) => setError(e as ApiFailure))
            }
          >
            내려받기
          </Button>
        </Can>
      </form>
      <ErrorBanner
        error={history.error ?? error}
        onRetry={() => void history.reload().catch(() => {})}
      />
      {history.loading ? (
        <p role="status">불러오는 중…</p>
      ) : !history.data?.items.length ? (
        <p>접근 기록이 없습니다.</p>
      ) : (
        <div className={styles.scroll}>
          <table>
            <thead>
              <tr>
                {["시각", "작업자", "유형", "결과", "화면", "대상", "IP"].map(
                  (t) => (
                    <th key={t}>{t}</th>
                  ),
                )}
              </tr>
            </thead>
            <tbody>
              {history.data.items.map((h) => (
                <tr
                  data-testid={`access-history-row-${h.accessHistoryId}`}
                  key={h.accessHistoryId}
                >
                  <td>{h.createdAt}</td>
                  <td>{h.name ?? h.userId ?? "–"}</td>
                  <td>{h.type}</td>
                  <td>{h.result}</td>
                  <td>{h.screenId}</td>
                  <td>{h.target}</td>
                  <td>{h.ipAddress}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <div className={styles.toolbar}>
        <Button
          data-testid="access-page-previous"
          disabled={Number(search.page ?? 1) <= 1}
          onClick={() =>
            setSearch((q) => ({ ...q, page: Number(q.page ?? 1) - 1 }))
          }
        >
          이전
        </Button>
        <span>{history.data?.total ?? 0}건</span>
        <Button
          data-testid="access-page-next"
          disabled={
            !history.data || Number(search.page ?? 1) * 20 >= history.data.total
          }
          onClick={() =>
            setSearch((q) => ({ ...q, page: Number(q.page ?? 1) + 1 }))
          }
        >
          다음
        </Button>
      </div>
    </section>
  );
}
