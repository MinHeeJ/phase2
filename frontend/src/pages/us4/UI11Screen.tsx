import { useCallback, useState } from "react";
import { Button, Skeleton } from "../../shared/Controls";
import { MmGapBadge } from "../../shared/MmGapBadge";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { useAdminRead } from "../admin/useAdminResource";
import { ScreenHeading } from "../shared/ScreenHeading";
import { seoulMonth } from "../shared/PeriodBar";
import { useCodes } from "../shared/useCodes";
import { listProjectOptions } from "../projects/client";
import styles from "../staffplans/StaffPlans.module.css";
import {
  downloadPlanComparison,
  listPlanComparison,
  type ComparisonFilters,
} from "./client";
import { ShortagesPanel } from "../us5/ShortagesPanel";

export default function UI11Screen({
  name = "인원 계획 대비 투입",
  groupName = "프로젝트·투입",
  openProject,
}: {
  name?: string;
  groupName?: string;
  openProject?: (id: number) => void;
}) {
  const [filters, setFilters] = useState<ComparisonFilters>(() => ({
    year: Number(seoulMonth().slice(0, 4)),
    page: 0,
    size: 50,
  }));
  const [draft, setDraft] = useState(filters);
  const list = useAdminRead(
    useCallback(() => listPlanComparison(filters), [filters]),
  );
  const projects = useAdminRead(listProjectOptions);
  const jobs = useCodes("JOB");
  const [downloadError, setDownloadError] = useState("");
  const [tab, setTab] = useState<"annual" | "shortages">("annual");
  const reset = () => {
    const next = { year: filters.year, page: 0, size: 50 };
    setDraft(next);
    setFilters(next);
  };
  const changeYear = (year: number) => {
    const next = { ...filters, year, page: 0 };
    setFilters(next);
    setDraft(next);
  };
  return (
    <section data-screen-id="SCR-UI-11" data-testid="plan-comparison-screen">
      <ScreenHeading
        screenId="UI-11"
        name={name}
        groupName={groupName}
        actions={
          tab === "annual" ? (
            <Button
              data-testid="comparison-export"
              onClick={() => {
                setDownloadError("");
                void downloadPlanComparison(filters).catch(() =>
                  setDownloadError("다운로드에 실패했습니다."),
                );
              }}
            >
              엑셀 다운로드
            </Button>
          ) : undefined
        }
      />
      <div role="tablist" aria-label="계획 대비 투입">
        <Button
          role="tab"
          aria-selected={tab === "annual"}
          onClick={() => setTab("annual")}
        >
          연간 비교
        </Button>
        <Button
          role="tab"
          aria-selected={tab === "shortages"}
          onClick={() => setTab("shortages")}
        >
          미충원 직무
        </Button>
      </div>
      <div className={styles.toolbar}>
        <Button
          data-testid="comparison-previous-year"
          disabled={filters.year <= 1}
          onClick={() => changeYear(filters.year - 1)}
        >
          ‹ 이전 연도
        </Button>
        <strong>{filters.year}년</strong>
        <Button
          data-testid="comparison-next-year"
          disabled={filters.year >= 9999}
          onClick={() => changeYear(filters.year + 1)}
        >
          다음 연도 ›
        </Button>
      </div>
      <div className={styles.toolbar}>
        <label>
          프로젝트{" "}
          <select
            data-testid="comparison-project"
            value={draft.projectId ?? ""}
            onChange={(e) =>
              setDraft({
                ...draft,
                projectId: e.target.value ? Number(e.target.value) : undefined,
              })
            }
          >
            <option value="">전체</option>
            {projects.data?.map((p) => (
              <option key={p.id} value={p.id}>
                {p.name}
              </option>
            ))}
          </select>
        </label>
        <label>
          직무{" "}
          <select
            data-testid="comparison-job"
            value={draft.job ?? ""}
            onChange={(e) => setDraft({ ...draft, job: e.target.value })}
          >
            <option value="">전체</option>
            {jobs.codes.map((j) => (
              <option key={j.code} value={j.code}>
                {j.name}
              </option>
            ))}
          </select>
        </label>
        {tab === "annual" && (
          <label>
            차이{" "}
            <select
              data-testid="comparison-gap"
              value={draft.gap ?? ""}
              onChange={(e) => setDraft({ ...draft, gap: e.target.value })}
            >
              <option value="">전체</option>
              <option value="SHORT">부족</option>
              <option value="OVER">초과</option>
              <option value="MATCH">맞음</option>
            </select>
          </label>
        )}
        <Button
          data-testid="comparison-search"
          onClick={() => setFilters({ ...draft, page: 0 })}
        >
          조회
        </Button>
        <Button data-testid="comparison-reset" onClick={reset}>
          초기화
        </Button>
      </div>
      {tab === "annual" && (
        <ErrorBanner
          error={list.error}
          onRetry={() => void list.reload().catch(() => {})}
        />
      )}
      <ErrorBanner
        error={projects.error}
        onRetry={() => void projects.reload().catch(() => {})}
      />
      <ErrorBanner error={jobs.error} onRetry={jobs.retry} />
      {downloadError && <p role="alert">{downloadError}</p>}
      {tab === "shortages" ? (
        <ShortagesPanel
          year={filters.year}
          projectId={filters.projectId}
          job={filters.job}
          onReset={reset}
          onOpenProject={(id) => openProject?.(id)}
        />
      ) : list.loading ? (
        <Skeleton />
      ) : (
        list.data && (
          <>
            <p className={styles.hint}>
              실제·예정 MM은 지난 달까지의 투입과 앞으로 예정된 투입을 합한
              값입니다. 차이가 ±{list.data.tolerance.toFixed(2)} MM 안이면
              맞음으로 봅니다.
            </p>
            {list.data.rows.length === 0 ? (
              <p className={styles.empty}>
                조건에 맞는 결과가 없습니다.{" "}
                <Button data-testid="comparison-empty-reset" onClick={reset}>
                  조건 초기화
                </Button>
              </p>
            ) : (
              <div className={styles.tableScroll}>
                <table className={styles.table}>
                  <thead>
                    <tr>
                      <th>프로젝트</th>
                      <th>직무</th>
                      <th>{filters.year}년 계획 MM</th>
                      <th>실제·예정 MM</th>
                      <th>차이</th>
                    </tr>
                  </thead>
                  <tbody>
                    {list.data.rows.map((row) => (
                      <tr
                        key={`${row.projectId}:${row.job}`}
                        data-testid={`comparison-row-${row.projectId}-${row.job}`}
                      >
                        <td>
                          <Button
                            variant="text"
                            data-testid={`comparison-project-${row.projectId}-${row.job}`}
                            onClick={() => openProject?.(row.projectId)}
                          >
                            {row.projectName}
                          </Button>
                        </td>
                        <td>{row.jobName}</td>
                        <td>{row.planMm.toFixed(2)}</td>
                        <td>{row.actualMm.toFixed(2)}</td>
                        <td>
                          <MmGapBadge
                            plan={row.planMm}
                            actual={row.actualMm}
                            tolerance={list.data!.tolerance}
                          />
                        </td>
                      </tr>
                    ))}
                  </tbody>
                  <tfoot>
                    <tr>
                      <th>합계</th>
                      <td></td>
                      <td>{list.data.totals.planMm.toFixed(2)}</td>
                      <td>{list.data.totals.actualMm.toFixed(2)}</td>
                      <td></td>
                    </tr>
                  </tfoot>
                </table>
              </div>
            )}
            <div className={styles.toolbar}>
              <span>총 {list.data.total}건</span>
              <Button
                data-testid="comparison-previous-page"
                disabled={filters.page === 0}
                onClick={() =>
                  setFilters({ ...filters, page: filters.page - 1 })
                }
              >
                이전
              </Button>
              <Button
                data-testid="comparison-next-page"
                disabled={(filters.page + 1) * filters.size >= list.data.total}
                onClick={() =>
                  setFilters({ ...filters, page: filters.page + 1 })
                }
              >
                다음
              </Button>
            </div>
          </>
        )
      )}
    </section>
  );
}
