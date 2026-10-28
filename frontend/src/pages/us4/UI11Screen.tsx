import { useCallback, useState, type ReactNode } from "react";
import { Link } from "react-router-dom";
import { Badge, Button, Skeleton } from "../../shared/Controls";
import { ApiFailure } from "../../shared/api";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { MmGapBadge } from "../../shared/MmGapBadge";
import { getMmGapTolerance } from "../../shared/mmGapParameters";
import { useAdminRead } from "../admin/useAdminResource";
import { ScreenHeading } from "../shared/ScreenHeading";
import { seoulMonth } from "../shared/PeriodBar";
import { useCodes } from "../shared/useCodes";
import {
  downloadPlanComparison,
  listPlanComparison,
  planComparisonDefaults,
  projectStaffPlansPath,
  type PlanComparisonFilters,
} from "./client";
import styles from "./PlanComparison.module.css";

export interface PlanComparisonScreenProps {
  name?: string;
  groupName?: string;
  renderShortages?: (context: {
    filters: PlanComparisonFilters;
    onChange: (filters: PlanComparisonFilters) => void;
    projects: readonly { prj_id: number; prj_nm: string }[];
  }) => ReactNode;
}
// SCR-UI-11: annual comparison; the monthly renderer is supplied by the monthly feature.
export default function UI11Screen({
  name = "인원 계획 대비 투입",
  groupName = "프로젝트·투입",
  renderShortages,
}: PlanComparisonScreenProps) {
  const [filters, setFilters] = useState(() => {
    const year = Number(sessionStorage.getItem("UI-11.year"));
    return planComparisonDefaults(
      year >= 1 && year <= 9999 ? year : Number(seoulMonth().slice(0, 4)),
    );
  });
  const [draft, setDraft] = useState(filters);
  const [tab, setTab] = useState("annual");
  const [downloadError, setDownloadError] = useState<ApiFailure | null>(null);
  const [downloading, setDownloading] = useState(false);
  const list = useAdminRead(
    useCallback(() => listPlanComparison(filters), [filters]),
  );
  const tolerance = useAdminRead(getMmGapTolerance);
  const jobs = useCodes("JOB");
  const currentYear = Number((list.data?.today ?? seoulMonth()).slice(0, 4));
  const apply = (next: PlanComparisonFilters) => {
    setFilters(next);
    setDraft(next);
    sessionStorage.setItem("UI-11.year", String(next.year));
  };
  const reset = () => apply(planComparisonDefaults(filters.year));
  const shiftYear = (delta: number) =>
    apply({ ...filters, year: filters.year + delta, page: 0, projectId: "" });
  const sort = (key: string) =>
    apply({
      ...filters,
      page: 0,
      sort: `${key},${filters.sort.startsWith(`${key},asc`) ? "desc" : "asc"}`,
    });
  const download = async () => {
    setDownloading(true);
    setDownloadError(null);
    try {
      await downloadPlanComparison(filters);
    } catch (error) {
      setDownloadError(
        error instanceof ApiFailure
          ? error
          : new ApiFailure("다운로드에 실패했습니다.", null, null),
      );
    } finally {
      setDownloading(false);
    }
  };
  const results = list.data?.data;
  return (
    <section
      data-screen-id="SCR-UI-11"
      data-testid="plan-comparison-screen"
      className={styles.screen}
    >
      <ScreenHeading
        screenId="UI-11"
        name={name}
        groupName={groupName}
        actions={
          tab === "annual" && (
            <Button
              data-testid="plan-comparison-download"
              pending={downloading}
              onClick={() => void download()}
            >
              엑셀 다운로드
            </Button>
          )
        }
      />
      <div className={styles.period} aria-label="조회 기간">
        <Button
          data-testid="comparison-previous-year"
          aria-label="이전 해"
          disabled={filters.year <= 1}
          onClick={() => shiftYear(-1)}
        >
          ‹
        </Button>
        <strong>{filters.year}년</strong>
        <Button
          data-testid="comparison-next-year"
          aria-label="다음 해"
          disabled={filters.year >= 9999}
          onClick={() => shiftYear(1)}
        >
          ›
        </Button>
        {filters.year < currentYear && (
          <Badge tone="locked">지난 해 · 읽기 전용</Badge>
        )}
        {filters.year === currentYear + 1 && <Badge tone="info">계획</Badge>}
      </div>
      <div role="tablist" className={styles.tabs}>
        <button
          type="button"
          role="tab"
          data-testid="comparison-annual-tab"
          aria-selected={tab === "annual"}
          onClick={() => {
            setTab("annual");
            apply({ ...filters, page: 0 });
          }}
        >
          계획 대비 투입
        </button>
        {renderShortages && (
          <button
            type="button"
            role="tab"
            data-testid="comparison-shortages-tab"
            aria-selected={tab === "shortages"}
            onClick={() => {
              setTab("shortages");
              apply({ ...filters, page: 0 });
            }}
          >
            미충원 직무
          </button>
        )}
      </div>
      {tab === "shortages" ? (
        <div role="tabpanel" data-testid="comparison-shortages-panel">
          {renderShortages?.({
            filters,
            onChange: apply,
            projects: list.data?.projects ?? [],
          })}
        </div>
      ) : (
        <div
          role="tabpanel"
          aria-label="계획 대비 투입"
          data-testid="comparison-annual-panel"
        >
          <form
            className={styles.filters}
            onSubmit={(event) => {
              event.preventDefault();
              apply({ ...draft, page: 0 });
            }}
          >
            <label>
              프로젝트{" "}
              <select
                data-testid="comparison-project"
                aria-label="프로젝트"
                value={draft.projectId}
                onChange={(event) =>
                  setDraft({ ...draft, projectId: event.target.value })
                }
              >
                <option value="">전체</option>
                {list.data?.projects?.map((project) => (
                  <option key={project.prj_id} value={project.prj_id}>
                    {project.prj_nm}
                  </option>
                ))}
              </select>
            </label>
            <label>
              직무{" "}
              <select
                data-testid="comparison-job"
                aria-label="직무"
                value={draft.job}
                onChange={(event) =>
                  setDraft({ ...draft, job: event.target.value })
                }
              >
                <option value="">전체</option>
                {jobs.codes.map((job) => (
                  <option key={job.code} value={job.code}>
                    {job.name}
                  </option>
                ))}
              </select>
            </label>
            <label>
              차이{" "}
              <select
                data-testid="comparison-gap"
                aria-label="차이"
                value={draft.gap}
                onChange={(event) =>
                  setDraft({
                    ...draft,
                    gap: event.target.value as PlanComparisonFilters["gap"],
                  })
                }
              >
                <option value="">전체</option>
                <option value="SHORT">부족</option>
                <option value="OVER">초과</option>
                <option value="MATCH">맞음</option>
              </select>
            </label>
            <Button
              data-testid="comparison-reset"
              type="button"
              onClick={reset}
            >
              초기화
            </Button>
            <Button
              data-testid="comparison-query"
              type="submit"
              variant="primary"
            >
              조회
            </Button>
          </form>
          <p className={styles.hint}>
            실제·예정 MM은 지난 달까지의 투입과 앞으로 예정된 투입을 합한
            값입니다.
            {tolerance.data != null &&
              !tolerance.error &&
              ` 차이가 ±${tolerance.data.toFixed(2)} MM 안이면 맞음으로 봅니다.`}
          </p>
          <ErrorBanner
            error={list.error}
            onRetry={() => void list.reload().catch(() => {})}
          />
          {list.error?.field && (
            <p role="alert" className={styles.error}>
              {list.error.field}: {list.error.message}
            </p>
          )}
          <ErrorBanner
            error={tolerance.error}
            onRetry={() => void tolerance.reload().catch(() => {})}
          />
          <ErrorBanner error={jobs.error} onRetry={jobs.retry} />
          <ErrorBanner error={downloadError} onRetry={() => void download()} />
          {list.loading ? (
            <Skeleton label="계획 대비 투입 불러오는 중" />
          ) : (
            !list.error &&
            results && (
              <>
                <p className={styles.hint}>
                  총 {results.total.toLocaleString("ko-KR")}건
                </p>
                <div className={styles.tableScroll}>
                  <table className={styles.table}>
                    <thead>
                      <tr>
                        {[
                          ["prj_nm", "프로젝트"],
                          ["job_cd", "직무"],
                          ["planMm", `${filters.year}년 계획 MM`],
                          ["actualMm", "실제·예정 MM"],
                          ["gapMm", "차이"],
                        ].map(([key, label]) => (
                          <th
                            key={key}
                            aria-sort={
                              filters.sort.startsWith(`${key},`)
                                ? filters.sort.startsWith(`${key},asc`)
                                  ? "ascending"
                                  : "descending"
                                : "none"
                            }
                          >
                            <button
                              type="button"
                              data-testid={`comparison-sort-${key.replace(/[A-Z]/g, (c) => `-${c.toLowerCase()}`)}`}
                              onClick={() => sort(key)}
                            >
                              {label}
                            </button>
                          </th>
                        ))}
                      </tr>
                    </thead>
                    <tbody>
                      {results.rows.map((row) => (
                        <tr
                          key={`${row.prj_id}:${row.job_cd}`}
                          data-testid={`comparison-row-${row.prj_id}-${row.job_cd.toLowerCase()}`}
                        >
                          <td>
                            <Link
                              data-testid={`comparison-project-${row.prj_id}-${row.job_cd.toLowerCase()}`}
                              to={projectStaffPlansPath(row.prj_id)}
                            >
                              {row.prj_nm}
                            </Link>
                          </td>
                          <td>{row.job_nm}</td>
                          <td className={styles.number}>
                            {row.planMm.toFixed(2)}
                          </td>
                          <td className={styles.number}>
                            {row.actualMm.toFixed(2)}
                          </td>
                          <td>
                            {tolerance.data != null && !tolerance.error ? (
                              <MmGapBadge
                                planMm={row.planMm}
                                actualMm={row.actualMm}
                                tolerance={tolerance.data}
                              />
                            ) : (
                              "–"
                            )}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                    <tfoot>
                      <tr data-testid="comparison-totals">
                        <th colSpan={2}>합계</th>
                        <td className={styles.number}>
                          {results.totals.planMm.toFixed(2)}
                        </td>
                        <td className={styles.number}>
                          {results.totals.actualMm.toFixed(2)}
                        </td>
                        <td />
                      </tr>
                    </tfoot>
                  </table>
                </div>
                {results.rows.length === 0 && (
                  <div className={styles.empty}>
                    조건에 맞는 결과가 없습니다.
                    <Button
                      data-testid="comparison-empty-reset"
                      onClick={reset}
                    >
                      조건 초기화
                    </Button>
                  </div>
                )}
                <nav aria-label="페이지" data-testid="comparison-pagination">
                  <Button
                    data-testid="comparison-previous-page"
                    aria-label="이전 페이지"
                    disabled={results.page <= 0}
                    onClick={() =>
                      apply({ ...filters, page: results.page - 1 })
                    }
                  >
                    이전
                  </Button>
                  <span>
                    {results.page + 1} /{" "}
                    {Math.max(1, Math.ceil(results.total / filters.size))}
                  </span>
                  <Button
                    data-testid="comparison-next-page"
                    aria-label="다음 페이지"
                    disabled={
                      (results.page + 1) * filters.size >= results.total
                    }
                    onClick={() =>
                      apply({ ...filters, page: results.page + 1 })
                    }
                  >
                    다음
                  </Button>
                </nav>
              </>
            )
          )}
        </div>
      )}
    </section>
  );
}
