import { useCallback, useState } from "react";
import { Badge, Button, Skeleton } from "../../shared/Controls";
import { ApiFailure } from "../../shared/api";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { useAdminRead } from "../admin/useAdminResource";
import { ScreenHeading } from "../shared/ScreenHeading";
import { seoulMonth } from "../shared/PeriodBar";
import { useCodes } from "../shared/useCodes";
import {
  boardDefaults,
  exportWorkforceBoard,
  getWorkforceBoard,
  type BoardCell,
  type BoardFilters,
} from "./client";
import { BoardForecastPanel } from "./BoardForecastPanel";
import styles from "./WorkforceBoard.module.css";
import controls from "../../shared/Controls.module.css";

const tones = {
  퇴사: "locked",
  휴직: "locked",
  투입중: "success",
  철수예정: "warning",
  가배정: "info",
  대기: "danger",
} as const;
export default function UI28Screen({
  name = "인력 운영 보드",
  groupName = "인력 운영",
  refreshVersion = 0,
}: {
  name?: string;
  groupName?: string;
  refreshVersion?: number;
}) {
  const [filters, setFilters] = useState(() => {
    const saved = Number(sessionStorage.getItem("UI-28.year"));
    return boardDefaults(
      saved >= 1 && saved <= 9999 ? saved : Number(seoulMonth().slice(0, 4)),
    );
  });
  const [draft, setDraft] = useState(filters);
  const [tab, setTab] = useState("calendar");
  const [forecastVisited, setForecastVisited] = useState(false);
  const [selected, setSelected] = useState<{
    name: string;
    cell: BoardCell;
  } | null>(null);
  const [exporting, setExporting] = useState(false);
  const [downloadError, setDownloadError] = useState<ApiFailure | null>(null);
  const [revision, setRevision] = useState(0);
  const list = useAdminRead(
    useCallback(
      () => getWorkforceBoard(filters),
      [filters, revision, refreshVersion],
    ),
  );
  const jobs = useCodes("JOB"),
    grades = useCodes("GRADE"),
    skills = useCodes("SKILL"),
    employees = useCodes("EMP_TYPE");
  const codes = {
    job: jobs,
    grade: grades,
    skill: skills,
    emp_se_cd: employees,
  };
  const change = (key: keyof BoardFilters, value: string | boolean) =>
    setDraft((d) => ({ ...d, [key]: value || undefined }));
  const year = (year: number) => {
    if (year < 1 || year > 9999) return;
    sessionStorage.setItem("UI-28.year", String(year));
    setDraft((d) => ({ ...d, year, page: 0 }));
    setFilters((f) => ({ ...f, year, page: 0 }));
    setSelected(null);
  };
  const reset = () => {
    const next = boardDefaults(filters.year);
    setDraft(next);
    setFilters(next);
    setSelected(null);
  };
  const download = async () => {
    setExporting(true);
    setDownloadError(null);
    try {
      await exportWorkforceBoard(filters);
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
  const open = (name: string, cell: BoardCell) => setSelected({ name, cell });
  const description = (cell: BoardCell) =>
    [
      cell.status,
      `확정 ${cell.confirmed_mm} MM`,
      `가배정 ${cell.temporary_mm} MM`,
      ...cell.assignments.map(
        (a) =>
          `${a.prj_nm} ${a.current_month_mm_qty} MM ${a.bgn_dt} ~ ${a.end_dt}`,
      ),
      ...cell.temp_assignments.map(
        (t) =>
          `${t.oppt_nm ?? `필요 인력 ${t.need_id}`} ${t.mth_mm_qty} MM ${t.bgn_ym} ~ ${t.end_ym}`,
      ),
      ...cell.warnings,
    ].join(" · ");
  return (
    <section data-testid="workforce-board-screen" data-screen-id="SCR-UI-28">
      <ScreenHeading
        screenId="UI-28"
        name={name}
        groupName={groupName}
        actions={
          <>
            <Button
              data-testid="board-refresh"
              onClick={() => {
                setRevision((v) => v + 1);
                setSelected(null);
              }}
            >
              새로고침
            </Button>
            <Button
              data-testid="board-export"
              pending={exporting}
              onClick={() => void download()}
            >
              엑셀 다운로드
            </Button>
          </>
        }
      />
      <div className={styles.period}>
        <Button
          data-testid="board-year-prev"
          disabled={filters.year <= 1}
          onClick={() => year(filters.year - 1)}
        >
          이전 해
        </Button>
        <strong>{filters.year}년</strong>
        <Badge tone="neutral">YEAR</Badge>
        <Button
          data-testid="board-year-next"
          disabled={filters.year >= 9999}
          onClick={() => year(filters.year + 1)}
        >
          다음 해
        </Button>
      </div>
      <form
        data-testid="board-search-form"
        className={styles.search}
        onSubmit={(e) => {
          e.preventDefault();
          setFilters({ ...draft, page: 0 });
          setSelected(null);
        }}
      >
        {(
          [
            ["job", "직무"],
            ["grade", "등급"],
            ["skill", "스킬"],
            ["emp_se_cd", "직원구분"],
          ] as const
        ).map(([key, label]) => (
          <label
            key={key}
            data-testid={`board-filter-${key.replaceAll("_", "-")}-label`}
          >
            {label}
            <select
              data-testid={`board-filter-${key.replaceAll("_", "-")}`}
              value={draft[key] ?? ""}
              onChange={(e) => change(key, e.target.value)}
            >
              <option value="">전체</option>
              {codes[key].codes.map((c) => (
                <option
                  key={c.code}
                  value={c.code}
                  data-testid={`board-filter-${key.replaceAll("_", "-")}-option-${c.code.toLowerCase().replaceAll("_", "-")}`}
                >
                  {c.name}
                </option>
              ))}
            </select>
            {list.error?.field === key && (
              <span role="alert">{list.error.message}</span>
            )}
          </label>
        ))}
        <label>
          <input
            data-testid="board-free-only"
            type="checkbox"
            checked={draft.free_only ?? false}
            onChange={(e) => change("free_only", e.target.checked)}
          />
          비는 인원만
        </label>
        <Button data-testid="board-reset" type="button" onClick={reset}>
          초기화
        </Button>
        <Button data-testid="board-query" type="submit" variant="primary">
          조회
        </Button>
      </form>
      <ErrorBanner
        error={list.error}
        onRetry={() => void list.reload().catch(() => {})}
      />
      <ErrorBanner error={downloadError} onRetry={() => void download()} />
      {Object.entries(codes).map(([key, value]) => (
        <ErrorBanner key={key} error={value.error} onRetry={value.retry} />
      ))}
      <div role="tablist" className={controls.tabs}>
        <button
          data-testid="board-calendar-tab"
          role="tab"
          aria-selected={tab === "calendar"}
          onClick={() => setTab("calendar")}
        >
          달력
        </button>
        <button
          data-testid="board-forecast-tab"
          role="tab"
          aria-selected={tab === "forecast"}
          onClick={() => {
            setTab("forecast");
            setForecastVisited(true);
          }}
        >
          수요·공급
        </button>
      </div>
      <div
        hidden={tab !== "calendar"}
        role="tabpanel"
        data-testid="board-calendar-panel"
      >
        {list.loading ? (
          <Skeleton />
        ) : (
          !list.error &&
          list.data && (
            <>
              <div className={styles.kpis}>
                <span>
                  전체 인력 <strong>{list.data.total}명</strong>
                </span>
                {list.data.monthly_totals.find(
                  (m) => m.month === seoulMonth(),
                ) && (
                  <>
                    <span>
                      이번 달 비는 인원{" "}
                      <strong>
                        {
                          list.data.monthly_totals.find(
                            (m) => m.month === seoulMonth(),
                          )!.free_people
                        }
                        명
                      </strong>
                    </span>
                    <span>
                      이번 달 남는 MM{" "}
                      <strong>
                        {list.data.monthly_totals
                          .find((m) => m.month === seoulMonth())!
                          .remaining_mm.toFixed(2)}
                      </strong>
                    </span>
                  </>
                )}
              </div>
              <p className={styles.legend}>
                {Object.entries(tones).map(([status, tone]) => (
                  <Badge key={status} tone={tone}>
                    {status}
                  </Badge>
                ))}
              </p>
              <div className={controls.tableScroll}>
                <table className={styles.calendar}>
                  <thead>
                    <tr>
                      {(
                        [
                          ["dev_nm", "이름"],
                          ["mng_no", "관리번호"],
                          ["job_cd", "직무"],
                          ["grd_cd", "등급"],
                        ] as const
                      ).map(([key, label]) => (
                        <th
                          key={key}
                          data-testid={`board-column-${key.replaceAll("_", "-")}`}
                        >
                          <button
                            data-testid={`board-sort-${key.replaceAll("_", "-")}`}
                            onClick={() =>
                              setFilters((f) => ({
                                ...f,
                                page: 0,
                                sort: `${key},${f.sort === `${key},asc` ? "desc" : "asc"}`,
                              }))
                            }
                          >
                            {label}
                          </button>
                        </th>
                      ))}
                      {Array.from({ length: 12 }, (_, i) => (
                        <th key={i} data-testid={`board-month-column-${i + 1}`}>
                          {i + 1}월
                        </th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {list.data.items.map((row) => (
                      <tr
                        key={row.dev_id}
                        data-testid={`board-row-${row.dev_id}`}
                      >
                        <td>{row.dev_nm}</td>
                        <td>{row.mng_no}</td>
                        <td>
                          {jobs.codes.find((c) => c.code === row.job_cd)
                            ?.name ?? row.job_cd}
                        </td>
                        <td>
                          {grades.codes.find((c) => c.code === row.grd_cd)
                            ?.name ??
                            row.grd_cd ??
                            "–"}
                        </td>
                        {row.months.map((cell) => (
                          <td
                            key={cell.month}
                            data-testid={`board-month-cell-${row.dev_id}-${cell.month}`}
                          >
                            <button
                              data-testid={`board-cell-${row.dev_id}-${cell.month}`}
                              className={styles.cell}
                              title={description(cell)}
                              aria-label={`${row.dev_nm} ${cell.month} ${description(cell)}`}
                              onMouseEnter={() => open(row.dev_nm, cell)}
                              onFocus={() => open(row.dev_nm, cell)}
                              onClick={() => open(row.dev_nm, cell)}
                            >
                              <Badge
                                tone={
                                  tones[cell.status as keyof typeof tones] ??
                                  "neutral"
                                }
                              >
                                {cell.status}
                              </Badge>
                              <span className={styles.fill} aria-hidden="true">
                                <i style={{ width: `${cell.fill_ratio}%` }} />
                              </span>
                              <span>확정 {cell.confirmed_mm.toFixed(2)}</span>
                              <small>
                                가배정 {cell.temporary_mm.toFixed(2)}
                              </small>
                              {cell.assignment_count > 1 && (
                                <small>{cell.assignment_count}건</small>
                              )}
                              {cell.withdrawal_date && (
                                <small>
                                  철수 {cell.withdrawal_date.slice(5)}
                                </small>
                              )}
                              {cell.confirmed_mm > 1 && (
                                <Badge tone="danger">MM 초과</Badge>
                              )}
                            </button>
                          </td>
                        ))}
                      </tr>
                    ))}
                  </tbody>
                  <tfoot>
                    <tr>
                      <th colSpan={4}>비는 인원</th>
                      {list.data.monthly_totals.map((m) => (
                        <td
                          key={m.month}
                          data-testid={`board-free-people-total-${m.month}`}
                        >
                          {m.free_people}명
                        </td>
                      ))}
                    </tr>
                    <tr>
                      <th colSpan={4}>남는 MM</th>
                      {list.data.monthly_totals.map((m) => (
                        <td
                          key={m.month}
                          data-testid={`board-remaining-mm-total-${m.month}`}
                        >
                          {m.remaining_mm.toFixed(2)}
                        </td>
                      ))}
                    </tr>
                  </tfoot>
                </table>
              </div>
              {!list.data.items.length && (
                <div className={controls.empty}>
                  <p>조건에 맞는 결과가 없습니다.</p>
                  <Button data-testid="board-empty-reset" onClick={reset}>
                    조건 초기화
                  </Button>
                </div>
              )}
              <nav
                className={controls.pagination}
                aria-label="인력 보드 페이지"
              >
                <span>
                  총 {list.data.total}명 · {filters.page + 1}쪽 · 월 합계는 전체
                  필터 기준
                </span>
                <Button
                  data-testid="board-page-prev"
                  disabled={!filters.page}
                  onClick={() =>
                    setFilters((f) => ({ ...f, page: f.page - 1 }))
                  }
                >
                  이전 쪽
                </Button>
                <Button
                  data-testid="board-page-next"
                  disabled={
                    (filters.page + 1) * filters.size >= list.data.total
                  }
                  onClick={() =>
                    setFilters((f) => ({ ...f, page: f.page + 1 }))
                  }
                >
                  다음 쪽
                </Button>
              </nav>
            </>
          )
        )}
        {selected && (
          <aside
            className={styles.details}
            data-testid="board-cell-details"
            aria-label="월 상세"
            onKeyDown={(e) => {
              if (e.key === "Escape") setSelected(null);
            }}
          >
            <h2>
              {selected.name} · {selected.cell.month}
            </h2>
            <p>{description(selected.cell)}</p>
            <Button
              data-testid="board-detail-close"
              onClick={() => setSelected(null)}
            >
              닫기
            </Button>
          </aside>
        )}
      </div>
      {forecastVisited && (
        <div hidden={tab !== "forecast"}>
          <BoardForecastPanel
            key={`${JSON.stringify(filters)}-${revision}-${refreshVersion}`}
            filters={filters}
          />
        </div>
      )}
    </section>
  );
}
