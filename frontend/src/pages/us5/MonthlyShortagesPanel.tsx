import { useCallback, useEffect, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { Badge, Button, Skeleton } from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { ApiFailure } from "../../shared/api";
import { useAdminRead } from "../admin/useAdminResource";
import { useCodes } from "../shared/useCodes";
import {
  listMonthlyShortages,
  downloadMonthlyShortages,
  type MonthlyShortageFilters,
} from "./client";
import styles from "./MonthlyShortages.module.css";

export interface MonthlyShortagesPanelProps {
  filters: MonthlyShortageFilters;
  onFiltersChange: (filters: MonthlyShortageFilters) => void;
  projects: readonly { prj_id: number; prj_nm: string }[];
}
// SCR-UI-11: monthly tab; year/project/job are owned by the annual comparison screen.
export function MonthlyShortagesPanel({
  filters,
  onFiltersChange,
  projects,
}: MonthlyShortagesPanelProps) {
  const navigate = useNavigate();
  const [draft, setDraft] = useState(filters),
    [page, setPage] = useState(0);
  const [revision, setRevision] = useState(0),
    [downloading, setDownloading] = useState(false);
  const [downloadError, setDownloadError] = useState<ApiFailure | null>(null);
  const jobs = useCodes("JOB");
  useEffect(() => {
    setDraft(filters);
    setPage(0);
  }, [filters.year, filters.projectId, filters.job]);
  const result = useAdminRead(
    useCallback(
      () => listMonthlyShortages(filters, page),
      [filters.year, filters.projectId, filters.job, page, revision],
    ),
  );
  const filtered = filters.projectId !== undefined || !!filters.job;
  const reset = () => {
    const next = { year: filters.year };
    setDraft(next);
    setPage(0);
    onFiltersChange(next);
    setRevision((v) => v + 1);
  };
  const exportRows = async () => {
    setDownloading(true);
    setDownloadError(null);
    try {
      await downloadMonthlyShortages(filters);
    } catch (error) {
      setDownloadError(
        error instanceof ApiFailure
          ? error
          : new ApiFailure("파일을 내려받을 수 없습니다.", null, null),
      );
    } finally {
      setDownloading(false);
    }
  };
  const openProject = (id: number) =>
    navigate(`/UI-01?open=${id}&tab=staff-plans`);
  return (
    <section
      role="tabpanel"
      aria-label="미충원 직무"
      data-testid="monthly-shortages-panel"
    >
      <form
        className={styles.search}
        onSubmit={(event) => {
          event.preventDefault();
          setPage(0);
          onFiltersChange(draft);
          setRevision((v) => v + 1);
        }}
      >
        <label>
          프로젝트
          <select
            aria-label="프로젝트"
            data-testid="shortage-project"
            value={draft.projectId ?? ""}
            aria-invalid={result.error?.field === "projectId"}
            onChange={(event) =>
              setDraft({
                ...draft,
                projectId: event.target.value
                  ? Number(event.target.value)
                  : undefined,
              })
            }
          >
            <option value="">전체</option>
            {projects.map((project) => (
              <option key={project.prj_id} value={project.prj_id}>
                {project.prj_nm}
              </option>
            ))}
          </select>
          {result.error?.field === "projectId" && (
            <span role="alert">{result.error.message}</span>
          )}
        </label>
        <label>
          직무
          <select
            aria-label="직무"
            data-testid="shortage-job"
            value={draft.job ?? ""}
            disabled={jobs.loading}
            aria-invalid={result.error?.field === "job"}
            onChange={(event) =>
              setDraft({ ...draft, job: event.target.value || undefined })
            }
          >
            <option value="">전체</option>
            {jobs.codes.map((job) => (
              <option key={job.code} value={job.code}>
                {job.name}
              </option>
            ))}
          </select>
          {result.error?.field === "job" && (
            <span role="alert">{result.error.message}</span>
          )}
        </label>
        <Button data-testid="shortage-reset" type="button" onClick={reset}>
          초기화
        </Button>
        <Button data-testid="shortage-search" variant="primary" type="submit">
          조회
        </Button>
      </form>
      <ErrorBanner error={jobs.error} onRetry={jobs.retry} />
      <div className={styles.toolbar}>
        <span>총 {result.data?.total ?? 0}건</span>
        <Button
          data-testid="shortage-export"
          pending={downloading}
          onClick={() => void exportRows()}
        >
          엑셀 다운로드
        </Button>
      </div>
      <ErrorBanner error={downloadError} />
      <ErrorBanner
        error={result.error}
        pending={result.loading}
        onRetry={() => void result.reload().catch(() => {})}
      />
      {result.loading ? (
        <Skeleton label="미충원 직무 불러오는 중" />
      ) : (
        !result.error &&
        result.data && (
          <>
            {result.data.rows.length === 0 ? (
              <div className={styles.empty}>
                <p>
                  {filtered
                    ? "조건에 맞는 결과가 없습니다."
                    : "미충원 직무가 없습니다."}
                </p>
                {filtered && (
                  <Button data-testid="shortage-empty-reset" onClick={reset}>
                    조건 초기화
                  </Button>
                )}
              </div>
            ) : (
              <div className={styles.tableScroll}>
                <table className={styles.table}>
                  <thead>
                    <tr>
                      <th>프로젝트</th>
                      <th>직무</th>
                      <th>월</th>
                      <th>계획 MM</th>
                      <th>실제·예정 MM</th>
                      <th>부족</th>
                    </tr>
                  </thead>
                  <tbody>
                    {result.data.rows.map((row) => (
                      <tr
                        key={`${row.prj_id}-${row.job_cd}-${row.plan_ym}`}
                        data-testid={`shortage-row-${row.prj_id}-${row.job_cd.toLowerCase().replaceAll("_", "-")}-${row.plan_ym}`}
                        onClick={() => openProject(row.prj_id)}
                      >
                        <td>
                          <Link
                            data-testid={`shortage-project-${row.prj_id}-${row.job_cd.toLowerCase().replaceAll("_", "-")}-${row.plan_ym}`}
                            to={`/UI-01?open=${row.prj_id}&tab=staff-plans`}
                            onClick={(event) => event.stopPropagation()}
                          >
                            {row.prj_nm}
                          </Link>
                        </td>
                        <td>{row.job_nm}</td>
                        <td>{row.plan_ym}</td>
                        <td className={styles.number}>
                          {row.planMm.toFixed(2)}
                        </td>
                        <td className={styles.number}>
                          {row.actualMm.toFixed(2)}
                        </td>
                        <td>
                          <Badge tone="danger">
                            부족 {row.shortageMm.toFixed(2)}
                          </Badge>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
            <nav className={styles.pager} aria-label="미충원 페이지">
              <Button
                data-testid="shortage-previous"
                disabled={page === 0}
                onClick={() => setPage((v) => v - 1)}
              >
                이전
              </Button>
              <span>
                {page + 1} / {Math.max(1, Math.ceil(result.data.total / 50))}
              </span>
              <Button
                data-testid="shortage-next"
                disabled={(page + 1) * 50 >= result.data.total}
                onClick={() => setPage((v) => v + 1)}
              >
                다음
              </Button>
              <span>50건씩</span>
            </nav>
          </>
        )
      )}
    </section>
  );
}
