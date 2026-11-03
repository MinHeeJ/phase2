import { useCallback, useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  Badge,
  Button,
  DataTable,
  EmptyState,
  Skeleton,
} from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { useAdminRead } from "../admin/useAdminResource";
import { ScreenHeading } from "../shared/ScreenHeading";
import { seoulMonth } from "../shared/PeriodBar";
import { useCodes } from "../shared/useCodes";
import { candidateNavigation } from "../opportunitiesops/client";
import {
  getWorkforceForecast,
  type WorkforceForecastFilters,
  type WorkforceForecastPage,
} from "./client";
import styles from "./WorkforceDemand.module.css";

type Month = WorkforceForecastPage["items"][number];
const mm = (value: number) => value.toFixed(2);

export function WorkforceDemandContent({
  data,
}: {
  data: WorkforceForecastPage;
}) {
  const navigate = useNavigate();
  const monthly = data.months.map((month) => {
    const rows = data.items.filter((row) => row.month === month);
    return {
      month,
      supply: rows.reduce((sum, row) => sum + row.supply_mm, 0),
      demand: rows.reduce((sum, row) => sum + row.demand_mm, 0),
    };
  });
  const maximum = Math.max(
    1,
    ...monthly.flatMap((row) => [row.supply, row.demand]),
  );
  const buckets = new Map<
    string,
    { key: string; job: string; grade: string; rows: Month[] }
  >();
  data.items.forEach((row) => {
    const key = `${row.job_cd}:${row.grd_cd ?? ""}`;
    if (!buckets.has(key))
      buckets.set(key, {
        key,
        job: row.job_cd,
        grade: row.grd_cd ?? "",
        rows: [],
      });
    buckets.get(key)!.rows.push(row);
  });
  const warnings = [...new Set(data.items.flatMap((row) => row.warnings))];
  const shortage = data.shortages[0];
  const first = data.shortages.map((row) => row.first_shortage_month).sort()[0];
  return (
    <div data-testid="workforce-demand-content" className={styles.screen}>
      <p className={styles.notice}>
        예측 값 · 확정 손익·청구·마감에는 반영되지 않습니다. 가배정은 공급에서
        차감하지 않습니다.
      </p>
      <div className={styles.kpis}>
        <div>
          최대 부족
          <strong>
            {shortage
              ? `${mm(Math.max(...data.shortages.map((row) => row.maximum_shortage_mm)))} MM`
              : "0.00 MM"}
          </strong>
        </div>
        <div>
          첫 부족월<strong>{first ?? "–"}</strong>
        </div>
        <div>
          부족 직무
          <strong>
            {new Set(data.shortages.map((row) => row.job_cd)).size}개
          </strong>
        </div>
      </div>
      {warnings.length > 0 && (
        <div role="status" className={styles.warning}>
          {warnings.join(" · ")}
        </div>
      )}
      <section
        className={styles.card}
        aria-labelledby="workforce-chart-heading"
      >
        <h2 id="workforce-chart-heading">월별 가용 vs 필요</h2>
        <p>
          <Badge tone="info">가용 공급</Badge>{" "}
          <Badge tone="warning">필요 수요</Badge>
        </p>
        <div
          role="img"
          aria-label="월별 공급 수요 MM 비교"
          className={styles.chart}
        >
          {monthly.map((row) => (
            <div
              key={row.month}
              data-testid={`demand-month-${row.month}`}
              className={styles.month}
            >
              <div className={styles.bars}>
                <div
                  className={styles.supply}
                  style={{ height: `${(160 * row.supply) / maximum}px` }}
                  title={`공급 ${mm(row.supply)} MM`}
                />
                <div
                  className={styles.demand}
                  style={{ height: `${(160 * row.demand) / maximum}px` }}
                  title={`수요 ${mm(row.demand)} MM`}
                />
              </div>
              <span>{row.month}</span>
              <small>
                공급 {mm(row.supply)} / 수요 {mm(row.demand)}
              </small>
            </div>
          ))}
        </div>
      </section>
      <section className={styles.card}>
        <h2>직무·등급별 월 차이</h2>
        <p>남는 MM = 공급 − 수요 · 부족이 0.40 MM를 초과하면 채용 검토</p>
        <DataTable
          rows={[...buckets.values()]}
          total={buckets.size}
          rowKey={(row) => row.key}
          columns={[
            { key: "job", label: "직무", render: (row) => row.job || "미분류" },
            {
              key: "grade",
              label: "등급",
              render: (row) => row.grade || "미분류",
            },
            ...data.months.map((month) => ({
              key: month,
              label: month,
              kind: "number" as const,
              render: (bucket: { rows: Month[] }) => {
                const row = bucket.rows.find((item) => item.month === month);
                return row ? (
                  <Badge
                    tone={
                      row.shortage_mm > 0.4
                        ? "danger"
                        : row.remaining_mm < 0
                          ? "warning"
                          : "success"
                    }
                  >
                    {mm(row.remaining_mm)}
                  </Badge>
                ) : (
                  "–"
                );
              },
            })),
          ]}
        />
      </section>
      <section className={styles.card}>
        <h2>월별 수요·공급 원값</h2>
        <DataTable
          rows={data.items}
          total={data.total}
          rowKey={(row) => `${row.month}:${row.job_cd}:${row.grd_cd}`}
          columns={[
            { key: "month", label: "월", render: (row) => row.month },
            {
              key: "job",
              label: "직무·등급",
              render: (row) => `${row.job_cd} ${row.grd_cd || "미분류"}`,
            },
            {
              key: "supply",
              label: "공급 MM",
              kind: "number",
              render: (row) => mm(row.supply_mm),
            },
            {
              key: "demand",
              label: "수요 MM",
              kind: "number",
              render: (row) => mm(row.demand_mm),
            },
            {
              key: "temp",
              label: "가배정 (별도)",
              kind: "number",
              render: (row) => mm(row.temporary_mm),
            },
            {
              key: "utilization",
              label: `가동률 (목표 ${data.target_utilization_rate}%)`,
              kind: "number",
              render: (row) =>
                row.utilization_rate == null
                  ? "–"
                  : `${mm(row.utilization_rate)}%`,
            },
            {
              key: "cost",
              label: "대기 비용 (추정)",
              kind: "number",
              render: (row) => `${row.idle_cost.toLocaleString("ko-KR")}원`,
            },
          ]}
        />
      </section>
      <section className={styles.card}>
        <h2>부족 상위 직무·등급</h2>
        <DataTable
          rows={data.shortages}
          total={data.shortages.length}
          rowKey={(row) => `${row.job_cd}:${row.grd_cd}`}
          columns={[
            {
              key: "job",
              label: "직무·등급",
              render: (row) => `${row.job_cd} ${row.grd_cd || "미분류"}`,
            },
            {
              key: "maximum",
              label: "6개월 최대 부족",
              render: (row) =>
                `${mm(row.maximum_shortage_mm)} MM / ${row.maximum_shortage_people}명`,
            },
            {
              key: "first",
              label: "첫 부족월",
              render: (row) => row.first_shortage_month,
            },
            {
              key: "hiring",
              label: "채용 시작 권장",
              render: (row) =>
                row.review_immediately
                  ? "즉시 검토"
                  : row.recommended_hiring_month,
            },
            {
              key: "candidates",
              label: "부족 직무와 맞는 후보",
              render: (row) => (
                <Button
                  data-testid={`demand-candidates-${row.job_cd.toLowerCase()}-${(row.grd_cd || "none").toLowerCase()}`}
                  onClick={() => {
                    const target = candidateNavigation(row);
                    navigate(target.pathname, { state: target.state });
                  }}
                >
                  {row.candidate_count}명 보기
                </Button>
              ),
            },
          ]}
        />
      </section>
    </div>
  );
}

export default function WorkforceDemandScreen({
  name = "인력 수요 예측",
}: {
  name?: string;
}) {
  const initialYear =
    Number(sessionStorage.getItem("UI-17.year")) ||
    Number(seoulMonth().slice(0, 4));
  const [filters, setFilters] = useState<WorkforceForecastFilters>({
    year: initialYear,
    months: 6,
  });
  const read = useAdminRead(
    useCallback(() => getWorkforceForecast(filters), [filters]),
  );
  const jobs = useCodes("JOB"),
    grades = useCodes("GRADE");
  const year = (value: number) => {
    setFilters((current) => ({ ...current, year: value }));
    sessionStorage.setItem("UI-17.year", String(value));
  };
  return (
    <section
      className={styles.screen}
      data-testid="workforce-demand-screen"
      data-screen-id="SCR-UI-17"
    >
      <ScreenHeading screenId="UI-17" name={name} groupName="인력 계획" />
      <div className={styles.period}>
        <Button
          data-testid="demand-year-prev"
          disabled={filters.year === 1}
          onClick={() => year(filters.year! - 1)}
        >
          이전 해
        </Button>
        <strong>{filters.year}년</strong>
        <Button
          data-testid="demand-year-next"
          disabled={filters.year === 9999}
          onClick={() => year(filters.year! + 1)}
        >
          다음 해
        </Button>
        <Button
          data-testid="demand-refresh"
          onClick={() => void read.reload().catch(() => {})}
        >
          새로고침
        </Button>
      </div>
      <div className={styles.filters}>
        {(
          [
            ["job", "직무", jobs],
            ["grade", "등급", grades],
          ] as const
        ).map(([key, label, options]) => (
          <label key={key}>
            {label}
            <select
              data-testid={`demand-${key}`}
              disabled={options.loading || !!options.error}
              value={filters[key] ?? ""}
              onChange={(event) =>
                setFilters((current) => ({
                  ...current,
                  [key]: event.target.value,
                }))
              }
            >
              <option value="">전체</option>
              {options.codes.map((code) => (
                <option key={code.code} value={code.code}>
                  {code.name}
                </option>
              ))}
            </select>
          </label>
        ))}
        <Button
          data-testid="demand-reset"
          onClick={() => setFilters({ year: filters.year, months: 6 })}
        >
          조건 초기화
        </Button>
      </div>
      <ErrorBanner error={jobs.error} onRetry={jobs.retry} />
      <ErrorBanner error={grades.error} onRetry={grades.retry} />
      <ErrorBanner
        error={read.error}
        onRetry={() => void read.reload().catch(() => {})}
      />
      {read.loading ? (
        <Skeleton />
      ) : (
        !read.error &&
        read.data &&
        (read.data.items.length > 0 ? (
          <WorkforceDemandContent data={read.data} />
        ) : (
          <EmptyState
            reason={read.data.message ?? "조건에 맞는 예측 결과가 없습니다."}
            actionLabel="조건 초기화"
            onAction={() => setFilters({ year: filters.year, months: 6 })}
          />
        ))
      )}
    </section>
  );
}
