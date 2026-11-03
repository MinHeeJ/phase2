import { useCallback } from "react";
import { Button, Pagination, Skeleton } from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { useAdminRead } from "../admin/useAdminResource";
import { useCodes } from "../shared/useCodes";
import { getCandidateShortages, type CandidateShortageFilters } from "./client";
import styles from "../developers/Developers.module.css";

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
                    <th>등급</th>
                    <th>6개월 최대 부족</th>
                    <th>최대 부족 인원</th>
                    <th>첫 부족월</th>
                    <th>채용 권장월</th>
                    <th>후보 수</th>
                    <th>바로가기</th>
                  </tr>
                </thead>
                <tbody>
                  {read.data.items.length === 0 ? (
                    <tr>
                      <td className={styles.empty} colSpan={8}>
                        조건에 맞는 부족 직무가 없습니다.
                      </td>
                    </tr>
                  ) : (
                    read.data.items.map((row) => (
                      <tr
                        key={`${row.job_cd}-${row.grd_cd ?? ""}`}
                        data-testid={`candidate-shortage-${row.job_cd.toLowerCase()}-${(row.grd_cd || "none").toLowerCase()}`}
                      >
                        <td>{name(row.job_cd, jobs)}</td>
                        <td>{name(row.grd_cd, grades)}</td>
                        <td className={styles.amount}>
                          {row.maximum_shortage_mm.toFixed(2)} MM
                        </td>
                        <td>{row.maximum_shortage_people}명</td>
                        <td>{row.first_shortage_month}</td>
                        <td>
                          {row.review_immediately
                            ? "즉시 검토"
                            : row.recommended_hiring_month}
                        </td>
                        <td>{row.candidate_count}명</td>
                        <td>
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
                            외부 후보 보기
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
