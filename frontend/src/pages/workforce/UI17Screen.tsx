import { useCallback, useState } from "react";
import { useNavigate } from "react-router-dom";
import { Button, Pagination, Skeleton } from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { useAdminRead } from "../admin/useAdminResource";
import { PeriodBar, seoulMonth } from "../shared/PeriodBar";
import { ScreenHeading } from "../shared/ScreenHeading";
import { useCodes } from "../shared/useCodes";
import { getForecast, type ForecastFilters, type Shortage } from "./client";
import styles from "../developers/Developers.module.css";
import chart from "./Workforce.module.css";

export function ShortageTable({ rows }: { rows: Shortage[] }) {
  const navigate = useNavigate();
  return (
    <div className={styles.tableScroll}>
      <table className={styles.table}>
        <thead>
          <tr>
            <th>부족 직무</th>
            <th>등급</th>
            <th>최대 부족 MM</th>
            <th>최대 부족 인원</th>
            <th>첫 부족월</th>
            <th>채용 권장월</th>
            <th>맞는 후보</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr
              key={`${r.job_cd}-${r.grd_cd ?? ""}`}
              data-testid={`workforce-shortage-${r.job_cd.toLowerCase()}-${r.grd_cd?.toLowerCase() ?? "all"}`}
            >
              <td>{r.job_cd}</td>
              <td>{r.grd_cd ?? "미지정"}</td>
              <td>{r.maximum_shortage_mm.toFixed(2)}</td>
              <td>{r.maximum_shortage_people}</td>
              <td>{r.first_shortage_month}</td>
              <td>
                {r.recommended_hiring_month}{" "}
                {r.review_immediately && "· 즉시 검토"}
              </td>
              <td>
                <Button
                  data-testid={`workforce-candidates-${r.job_cd.toLowerCase()}-${r.grd_cd?.toLowerCase() ?? "all"}`}
                  onClick={() =>
                    navigate("/UI-33", {
                      state: {
                        workspace: "candidates",
                        candidateFilters: {
                          job: r.job_cd,
                          grade: r.grd_cd ?? "",
                          shortage_month: r.first_shortage_month,
                        },
                      },
                    })
                  }
                >
                  외부 후보 보기 ({r.candidate_count})
                </Button>
              </td>
            </tr>
          ))}
          {rows.length === 0 && (
            <tr data-testid="workforce-no-shortages">
              <td colSpan={7}>0.40 MM를 초과하는 부족 직무가 없습니다.</td>
            </tr>
          )}
        </tbody>
      </table>
    </div>
  );
}

export default function UI17Screen() {
  const [period, setPeriod] = useState(seoulMonth());
  const [filters, setFilters] = useState<ForecastFilters>({
    year: Number(period.slice(0, 4)),
    job: "",
    grade: "",
    page: 0,
    size: 50,
    sort: "month,asc",
  });
  const jobs = useCodes("JOB"),
    grades = useCodes("GRADE");
  const read = useAdminRead(useCallback(() => getForecast(filters), [filters]));
  return (
    <section data-testid="workforce-forecast" data-screen-id="SCR-UI-17">
      <ScreenHeading
        screenId="UI-17"
        name="직무·등급 인력 수요 예측"
        groupName="인력 운영"
      />
      <PeriodBar
        screenId="UI-17"
        month={period}
        onMonthChange={(value) => {
          setPeriod(value);
          setFilters((f) => ({
            ...f,
            year: Number(value.slice(0, 4)),
            page: 0,
          }));
        }}
        onRefresh={() => void read.reload().catch(() => {})}
      />
      <div className={styles.search}>
        <label>
          직무
          <select
            data-testid="workforce-job"
            value={filters.job}
            onChange={(e) =>
              setFilters((f) => ({ ...f, job: e.target.value, page: 0 }))
            }
          >
            <option value="">전체</option>
            {jobs.codes.map((c) => (
              <option key={c.code} value={c.code}>
                {c.name}
              </option>
            ))}
          </select>
        </label>
        <label>
          등급
          <select
            data-testid="workforce-grade"
            value={filters.grade}
            onChange={(e) =>
              setFilters((f) => ({ ...f, grade: e.target.value, page: 0 }))
            }
          >
            <option value="">전체</option>
            {grades.codes.map((c) => (
              <option key={c.code} value={c.code}>
                {c.name}
              </option>
            ))}
          </select>
        </label>
      </div>
      <ErrorBanner
        error={jobs.error ?? grades.error}
        onRetry={() => {
          jobs.retry();
          grades.retry();
        }}
      />
      <ErrorBanner
        error={read.error}
        onRetry={() => void read.reload().catch(() => {})}
      />
      {read.loading ? (
        <Skeleton label="수요·공급 예측 중" />
      ) : (
        !read.error &&
        read.data && (
          <>
            {read.data.message ? (
              <p role="status">{read.data.message}</p>
            ) : (
              <>
                <p>
                  예측 기간: {read.data.months.join(" · ")} · 목표 가동률{" "}
                  {read.data.target_utilization_rate}% · 비용은 추정값입니다.
                </p>
                <div
                  className={chart.graph}
                  aria-label="직무별 월 공급·수요"
                  data-testid="workforce-forecast-chart"
                >
                  {read.data.items.map((row) => {
                    const scale = Math.max(row.supply_mm, row.demand_mm, 1);
                    return (
                      <div
                        key={`${row.month}-${row.job_cd}-${row.grd_cd ?? ""}`}
                        data-testid={`workforce-chart-${row.month}-${row.job_cd.toLowerCase()}-${row.grd_cd?.toLowerCase() ?? "all"}`}
                      >
                        <strong>
                          {row.month} {row.job_cd} {row.grd_cd}
                        </strong>
                        <div
                          className={chart.bar}
                          style={{ width: `${(row.supply_mm / scale) * 100}%` }}
                        >
                          공급 {row.supply_mm.toFixed(2)}
                        </div>
                        <div
                          className={`${chart.bar} ${chart.demand}`}
                          style={{ width: `${(row.demand_mm / scale) * 100}%` }}
                        >
                          수요 {row.demand_mm.toFixed(2)}
                        </div>
                      </div>
                    );
                  })}
                </div>
                <div className={styles.tableScroll}>
                  <table className={styles.table}>
                    <thead>
                      <tr>
                        <th>월</th>
                        <th>직무 / 등급</th>
                        <th>공급 MM</th>
                        <th>수요 MM</th>
                        <th>차이 MM</th>
                        <th>가동률</th>
                        <th>대기 비용 (추정)</th>
                        <th>경고</th>
                      </tr>
                    </thead>
                    <tbody>
                      {read.data.items.map((r) => (
                        <tr
                          key={`${r.month}-${r.job_cd}-${r.grd_cd ?? ""}`}
                          data-testid={`workforce-row-${r.month}-${r.job_cd.toLowerCase()}-${r.grd_cd?.toLowerCase() ?? "all"}`}
                        >
                          <td>{r.month}</td>
                          <td>
                            {r.job_cd} / {r.grd_cd ?? "미지정"}
                          </td>
                          <td>{r.supply_mm.toFixed(2)}</td>
                          <td>{r.demand_mm.toFixed(2)}</td>
                          <td>{r.remaining_mm.toFixed(2)}</td>
                          <td>
                            {r.utilization_rate === null
                              ? "–"
                              : `${r.utilization_rate.toFixed(2)}%`}
                          </td>
                          <td>{r.idle_cost.toLocaleString("ko-KR")}원</td>
                          <td>{r.warnings.join(" · ")}</td>
                        </tr>
                      ))}
                      {read.data.items.length === 0 && (
                        <tr data-testid="workforce-empty-row">
                          <td colSpan={8}>조건에 맞는 예측 원천이 없습니다.</td>
                        </tr>
                      )}
                    </tbody>
                  </table>
                </div>
                <Pagination
                  page={filters.page + 1}
                  size={filters.size}
                  total={read.data.total}
                  onPage={(page) =>
                    setFilters((f) => ({ ...f, page: page - 1 }))
                  }
                  onSize={(size) =>
                    setFilters((f) => ({ ...f, size, page: 0 }))
                  }
                />
                <h2>부족 직무와 채용 권장 시점</h2>
                <ShortageTable rows={read.data.shortages} />
              </>
            )}
          </>
        )
      )}
    </section>
  );
}
