import { useCallback } from "react";
import { Button, Pagination, Skeleton } from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { useAdminRead } from "../admin/useAdminResource";
import { useCodes } from "../shared/useCodes";
import { getCandidateShortages, type CandidateShortageFilters } from "./client";
import styles from "../developers/Developers.module.css";
import shortageStyles from "./CandidateShortages.module.css";

export interface CandidateShortageSelection {
  job: string;
  grade?: string;
  shortage_month: string;
}
// SCR-UI-33: shortage shortcuts embedded in the candidate workspace, also reusable by UI-17/29.
export function CandidateShortagesPanel({
  filters,
  onFilters,
  onCandidates,
}: {
  filters: CandidateShortageFilters;
  onFilters: (filters: CandidateShortageFilters) => void;
  onCandidates: (selection: CandidateShortageSelection) => void;
}) {
  const load = useCallback(() => getCandidateShortages(filters), [filters]),
    read = useAdminRead(load);
  const jobs = useCodes("JOB"),
    grades = useCodes("GRADE");
  const name = (code: string | null, source: typeof jobs) =>
    source.codes.find((c) => c.code === code)?.name ?? code ?? "–";
  return (
    <section
      className={styles.card}
      data-testid="candidate-shortages-panel"
      data-screen-id="SCR-UI-33"
    >
      <h2>부족 직무와 맞는 후보</h2>
      <p className={styles.help}>
        다음 달부터 최대 6개월 · 0.40 MM 초과 부족 직무 · 연락 전·연락함·면접
        예정 후보
      </p>
      <ErrorBanner
        error={read.error}
        onRetry={() => void read.reload().catch(() => {})}
      />
      <ErrorBanner error={jobs.error} onRetry={jobs.retry} />
      <ErrorBanner error={grades.error} onRetry={grades.retry} />
      {read.loading ? (
        <Skeleton />
      ) : (
        !read.error &&
        read.data && (
          <>
            <div className={styles.tableScroll}>
              <table className={styles.table}>
                <thead>
                  <tr>
                    <th>직무</th>
                    <th>부족 시작</th>
                    <th>가장 많이 부족</th>
                    <th>그때까지 투입 가능한 후보</th>
                  </tr>
                </thead>
                <tbody>
                  {read.data.items.length === 0 ? (
                    <tr>
                      <td className={styles.empty} colSpan={4}>
                        조건에 맞는 부족 직무가 없습니다.
                      </td>
                    </tr>
                  ) : (
                    read.data.items.map((row) => (
                      <tr
                        key={`${row.job_cd}-${row.grd_cd ?? ""}`}
                        data-testid={`candidate-shortage-${row.job_cd.toLowerCase()}-${(row.grd_cd || "none").toLowerCase()}`}
                      >
                        <td>
                          {name(row.job_cd, jobs)}
                          <div className={styles.help}>
                            {name(row.grd_cd, grades)}
                          </div>
                        </td>
                        <td>
                          {row.first_shortage_month}
                          <div className={styles.help}>
                            채용 권장:{" "}
                            {row.review_immediately
                              ? "즉시 검토"
                              : row.recommended_hiring_month}
                          </div>
                        </td>
                        <td
                          className={`${styles.amount} ${shortageStyles.shortage}`}
                        >
                          {row.maximum_shortage_mm.toFixed(2)} MM
                          <div>
                            최대 <span>{row.maximum_shortage_people}명</span>
                          </div>
                        </td>
                        <td>
                          <span>{row.candidate_count}명</span>{" "}
                          <Button
                            small
                            data-testid={`candidate-shortage-open-${row.job_cd.toLowerCase()}-${(row.grd_cd || "none").toLowerCase()}`}
                            onClick={() =>
                              onCandidates({
                                job: row.job_cd,
                                ...(row.grd_cd ? { grade: row.grd_cd } : {}),
                                shortage_month: row.first_shortage_month,
                              })
                            }
                          >
                            후보 보기
                          </Button>
                        </td>
                      </tr>
                    ))
                  )}
                </tbody>
              </table>
            </div>
            <Pagination
              page={read.data.page + 1}
              size={read.data.size}
              total={read.data.total}
              onPage={(page) => onFilters({ ...filters, page: page - 1 })}
              onSize={(size) => onFilters({ ...filters, page: 0, size })}
            />
          </>
        )
      )}
    </section>
  );
}
