import { useCallback, useState, type ReactNode } from "react";
import { useNavigate } from "react-router-dom";
import { Badge, Button, Skeleton } from "../../shared/Controls";
import { ApiFailure } from "../../shared/api";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { ScreenHeading } from "../shared/ScreenHeading";
import { seoulMonth } from "../shared/PeriodBar";
import { useCodes } from "../shared/useCodes";
import { useAdminRead } from "../admin/useAdminResource";
import {
  exportWithdrawals,
  listWithdrawals,
  withdrawalDefaults,
  type WithdrawalFilters,
  type WithdrawalRow,
} from "./client";
import styles from "./Withdrawals.module.css";

export interface WithdrawalScreenContext {
  today: string;
  year: number;
  refresh: () => Promise<unknown>;
}
export interface WithdrawalsScreenProps {
  name?: string;
  groupName?: string;
  onDirtyChange?: (dirty: boolean) => void;
  renderChance?: (
    row: WithdrawalRow,
    context: WithdrawalScreenContext,
  ) => ReactNode;
  renderDispatch?: (row: WithdrawalRow) => ReactNode;
  refreshVersion?: number;
}
export default function UI05Screen({
  name = "철수 리스트",
  groupName = "프로젝트·투입",
  renderChance,
  renderDispatch,
  refreshVersion = 0,
}: WithdrawalsScreenProps) {
  const navigate = useNavigate();
  const [filters, setFilters] = useState(() => {
    const saved = Number(sessionStorage.getItem("UI-05.year"));
    return withdrawalDefaults(
      saved >= 1 && saved <= 9999 ? saved : Number(seoulMonth().slice(0, 4)),
    );
  });
  const [draft, setDraft] = useState(filters);
  const [refreshingChance, setRefreshingChance] = useState(false);
  const list = useAdminRead(
    useCallback(() => listWithdrawals(filters), [filters, refreshVersion]),
  );
  const jobs = useCodes("JOB"),
    chances = useCodes("EXT_POSSIBILITY");
  const [exporting, setExporting] = useState(false),
    [downloadError, setDownloadError] = useState<ApiFailure | null>(null);
  const currentYear = Number((list.data?.today ?? seoulMonth()).slice(0, 4));
  const context: WithdrawalScreenContext = {
    today: list.data?.today ?? "",
    year: filters.year,
    refresh: async () => {
      setRefreshingChance(true);
      try {
        return await list.reload();
      } finally {
        setRefreshingChance(false);
      }
    },
  };
  const year = (value: number) => {
    if (value < 1 || value > 9999) return;
    sessionStorage.setItem("UI-05.year", String(value));
    setFilters((f) => ({ ...f, year: value, page: 0 }));
    setDraft((f) => ({ ...f, year: value, page: 0 }));
  };
  const reset = () => {
    const f = withdrawalDefaults(filters.year);
    setFilters(f);
    setDraft(f);
  };
  const change = <K extends keyof WithdrawalFilters>(
    key: K,
    value: WithdrawalFilters[K],
  ) => setDraft((d) => ({ ...d, [key]: value }));
  const sort = (key: string) =>
    setFilters((f) => ({
      ...f,
      page: 0,
      sort: `${key},${f.sort === `${key},asc` ? "desc" : "asc"}`,
    }));
  const download = async () => {
    if (exporting) return;
    setExporting(true);
    setDownloadError(null);
    try {
      await exportWithdrawals(filters);
    } catch (e) {
      setDownloadError(
        e instanceof ApiFailure
          ? e
          : new ApiFailure("다운로드하지 못했습니다.", null, null),
      );
    } finally {
      setExporting(false);
    }
  };
  const fieldError = (key: string) =>
    list.error?.field === key ? (
      <span role="alert">{list.error.message}</span>
    ) : null;
  const headings = [
    ["dev_nm", "이름"],
    ["asgn_no", "투입ID"],
    ["prj_nm", "프로젝트"],
    ["job_cd", "직무"],
    ["end_dt", "철수일"],
    ["remaining_days", "남은 일수"],
    ["", "연장·조기 철수"],
    ["", "파견 만료"],
    ["ext_psblty_cd", "연장 가능성"],
    ["", "다음 배정"],
  ];
  return (
    <section data-testid="withdrawals-screen" data-screen-id="SCR-UI-05">
      <ScreenHeading
        screenId="UI-05"
        name={name}
        groupName={groupName}
        actions={
          <Button
            data-testid="withdrawal-export"
            pending={exporting}
            onClick={() => void download()}
          >
            엑셀 다운로드
          </Button>
        }
      />
      <div className={styles.period}>
        <Button
          data-testid="withdrawal-year-prev"
          disabled={filters.year <= 1}
          onClick={() => year(filters.year - 1)}
        >
          이전 해
        </Button>
        <span>
          {filters.year}년{" "}
          {filters.year < currentYear && (
            <Badge tone="locked">지난 해 · 읽기 전용</Badge>
          )}
        </span>
        <Button
          data-testid="withdrawal-year-next"
          disabled={filters.year >= 9999}
          onClick={() => year(filters.year + 1)}
        >
          다음 해
        </Button>
      </div>
      {list.data && filters.year === currentYear && (
        <div
          data-testid="withdrawal-summary"
          className={
            list.data.summary.noNextPeople > 0 ? styles.warning : styles.info
          }
        >
          3개월 안 철수 {list.data.summary.soonPeople}명 중 연장 가능성이 높지
          않고 다음 배정도 없는 사람 {list.data.summary.noNextPeople}명입니다.
        </div>
      )}
      <div className={styles.info}>
        {filters.year}년에 철수하는(했던) 인력입니다. [기간] 조건으로 '3개월
        안'만 볼 수 있습니다. 연장 가능성을 바꾸면 투입 배정과 수요 예측에 바로
        반영됩니다.
      </div>
      <form
        className={styles.search}
        onSubmit={(e) => {
          e.preventDefault();
          setFilters({ ...draft, page: 0, sort: filters.sort });
        }}
      >
        <label>
          기간
          <select
            data-testid="withdrawal-period"
            value={draft.period ?? ""}
            onChange={(e) =>
              change(
                "period",
                (e.target.value || undefined) as WithdrawalFilters["period"],
              )
            }
          >
            <option value="">전체</option>
            <option value="SOON">3개월 안</option>
            <option value="LATER">3개월 뒤</option>
            <option value="DONE">이미 철수</option>
          </select>
          {fieldError("period")}
        </label>
        <label>
          연장 가능성
          <select
            data-testid="withdrawal-ext"
            value={draft.ext ?? ""}
            onChange={(e) =>
              change(
                "ext",
                (e.target.value || undefined) as WithdrawalFilters["ext"],
              )
            }
          >
            <option value="">전체</option>
            {chances.codes.map((c) => (
              <option key={c.code} value={c.code}>
                {c.name}
              </option>
            ))}
            <option value="NONE">미정</option>
          </select>
          {fieldError("ext")}
        </label>
        <label>
          직무
          <select
            data-testid="withdrawal-job"
            value={draft.job ?? ""}
            onChange={(e) => change("job", e.target.value || undefined)}
          >
            <option value="">전체</option>
            {jobs.codes.map((c) => (
              <option key={c.code} value={c.code}>
                {c.name}
              </option>
            ))}
          </select>
          {fieldError("job")}
        </label>
        <label>
          이름·관리번호
          <input
            data-testid="withdrawal-search"
            value={draft.q ?? ""}
            maxLength={200}
            onChange={(e) => change("q", e.target.value || undefined)}
          />
          {fieldError("q")}
        </label>
        <label>
          <input
            data-testid="withdrawal-dispatch-filter"
            type="checkbox"
            checked={draft.dispatchWithin60 ?? false}
            onChange={(e) => change("dispatchWithin60", e.target.checked)}
          />
          파견 만료 60일 안{fieldError("dispatchWithin60")}
        </label>
        <Button data-testid="withdrawal-reset" type="button" onClick={reset}>
          초기화
        </Button>
        <Button data-testid="withdrawal-query" type="submit" variant="primary">
          조회
        </Button>
      </form>
      <ErrorBanner
        error={list.error}
        onRetry={() => void list.reload().catch(() => {})}
        pending={list.loading}
      />
      <ErrorBanner error={jobs.error} onRetry={jobs.retry} />
      <ErrorBanner error={chances.error} onRetry={chances.retry} />
      <ErrorBanner
        error={downloadError}
        onRetry={() => void download()}
        pending={exporting}
      />
      {list.loading && !refreshingChance ? (
        <Skeleton />
      ) : (
        !list.error &&
        list.data && (
          <>
            <div className={styles.toolbar}>
              총 {list.data.total}건 · 50건씩
            </div>
            <div className={styles.tableScroll}>
              <table className={styles.table}>
                <thead>
                  <tr>
                    {headings.map(([key, label]) => (
                      <th key={label}>
                        {key ? (
                          <button
                            type="button"
                            data-testid={`withdrawal-sort-${key.replaceAll("_", "-")}`}
                            onClick={() => sort(key)}
                          >
                            {label}
                            {filters.sort.startsWith(`${key},`)
                              ? filters.sort.startsWith(`${key},desc`)
                                ? " ↓"
                                : " ↑"
                              : ""}
                          </button>
                        ) : (
                          label
                        )}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {list.data.rows.map((row) => (
                    <tr
                      key={row.asgn_id}
                      data-testid={`withdrawal-row-${row.asgn_id}`}
                      tabIndex={0}
                      onClick={() =>
                        navigate("/UI-12", {
                          state: { assignmentId: row.asgn_id },
                        })
                      }
                      onKeyDown={(e) => {
                        if (e.target === e.currentTarget && e.key === "Enter")
                          navigate("/UI-12", {
                            state: { assignmentId: row.asgn_id },
                          });
                      }}
                    >
                      <td>
                        <span onClick={(e) => e.stopPropagation()}>
                          {row.dev_nm}
                        </span>
                        <small>{row.mng_no}</small>
                      </td>
                      <td>{row.asgn_no}</td>
                      <td>
                        <button
                          className={styles.textButton}
                          data-testid={`withdrawal-project-${row.asgn_id}`}
                          onClick={(e) => {
                            e.stopPropagation();
                            navigate(
                              `/UI-01?open=${encodeURIComponent(row.prj_id)}`,
                            );
                          }}
                        >
                          {row.prj_nm}
                        </button>
                      </td>
                      <td>{row.job_nm}</td>
                      <td>
                        {row.end_dt}
                        {row.plan_end_dt && row.plan_end_dt !== row.end_dt && (
                          <small>처음 계획 {row.plan_end_dt}</small>
                        )}
                      </td>
                      <td>
                        <Badge
                          tone={
                            row.remaining_days < 0
                              ? "locked"
                              : row.remaining_days <= 30
                                ? "danger"
                                : row.remaining_days <= 60
                                  ? "warning"
                                  : "neutral"
                          }
                        >
                          {row.remaining_days < 0
                            ? "철수 완료"
                            : `D-${row.remaining_days}`}
                        </Badge>
                      </td>
                      <td>
                        {row.extend_count > 0 && (
                          <span>연장 {row.extend_count}회 </span>
                        )}
                        {row.early_end && (
                          <Badge tone="warning">조기 철수 이력</Badge>
                        )}
                        {row.asgn_stts_cd === "REPLACED" && (
                          <Badge tone="locked">
                            교체 → {row.replacement_name ?? "–"}
                          </Badge>
                        )}
                        {row.extend_count === 0 &&
                          !row.early_end &&
                          row.asgn_stts_cd !== "REPLACED" &&
                          "–"}
                      </td>
                      <td>
                        {renderDispatch ? (
                          renderDispatch(row)
                        ) : (
                          <Badge tone={row.dispatch_tone}>
                            {row.dispatch_display}
                          </Badge>
                        )}
                      </td>
                      <td onClick={(e) => e.stopPropagation()}>
                        {renderChance ? (
                          renderChance(row, context)
                        ) : (
                          <span title={row.chance_lock_reason ?? undefined}>
                            {row.ext_psblty_nm}
                          </span>
                        )}
                      </td>
                      <td>
                        <Badge
                          tone={
                            row.next_display === "없음" ? "warning" : "neutral"
                          }
                        >
                          {row.next_display}
                        </Badge>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            {list.data.rows.length === 0 && (
              <div className={styles.empty} data-testid="withdrawal-empty">
                <p>조건에 맞는 결과가 없습니다.</p>
                <Button data-testid="withdrawal-empty-reset" onClick={reset}>
                  조건 초기화
                </Button>
              </div>
            )}
            <nav className={styles.pager} aria-label="철수 목록 페이지">
              <Button
                data-testid="withdrawal-page-prev"
                disabled={filters.page === 0}
                onClick={() => setFilters((f) => ({ ...f, page: f.page - 1 }))}
              >
                이전 쪽
              </Button>
              <span>
                {filters.page + 1}쪽 · 총 {list.data.total}건
              </span>
              <Button
                data-testid="withdrawal-page-next"
                disabled={(filters.page + 1) * 50 >= list.data.total}
                onClick={() => setFilters((f) => ({ ...f, page: f.page + 1 }))}
              >
                다음 쪽
              </Button>
            </nav>
          </>
        )
      )}
    </section>
  );
}
