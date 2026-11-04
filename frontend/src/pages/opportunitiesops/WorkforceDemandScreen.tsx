import { useCallback, useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  Badge,
  Button,
  DataTable,
  EmptyState,
  Pagination,
  Skeleton,
} from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { useAdminRead } from "../admin/useAdminResource";
import { useCodes } from "../shared/useCodes";
import { ScreenHeading } from "../shared/ScreenHeading";
import { seoulMonth } from "../shared/PeriodBar";
import {
  candidateNavigation,
  getOpportunityForecast,
  type ForecastFilters,
  type ForecastPage,
} from "./client";
import styles from "./OpportunityLifecycle.module.css";

const money = (n: number) => n.toLocaleString("ko-KR");
export function ForecastContent({ data }: { data: ForecastPage }) {
  const navigate = useNavigate();
  const max = Math.max(1, ...data.items.map((r) => r.all_won_total));
  return (
    <div data-testid="opportunity-forecast-content">
      <p>예측 값 · 부가세 제외 · 확정 손익·청구·마감에는 반영되지 않습니다.</p>
      <div className={styles.kpis}>
        {[
          ["확률 반영 총액", money(data.kpi.weighted_total)],
          ["기회 기여", money(data.kpi.opportunity_total)],
          ["모두 수주 참고", money(data.kpi.all_won_total)],
          ["평균 수요", data.kpi.average_demand_mm.toFixed(2)],
          ["평균 공급", data.kpi.average_supply_mm.toFixed(2)],
          ["채용 검토 직무", String(data.kpi.hiring_job_count)],
        ].map(([label, value]) => (
          <div key={label}>
            {label}
            <strong>{value}</strong>
          </div>
        ))}
      </div>
      {data.workforce.message && <p role="status">{data.workforce.message}</p>}
      <section className={styles.card}>
        <h2>앞으로 6개월 예상 매출</h2>
        <p>
          <Badge tone="success">영업 기회</Badge>{" "}
          <Badge tone="info">진행 중 프로젝트</Badge>
        </p>
        <div
          role="img"
          aria-label="프로젝트와 영업 기회 월별 예상 매출"
          className={styles.graph}
        >
          {data.items.map((r) => (
            <div
              key={r.month}
              className={styles.stack}
              data-testid={`revenue-stack-${r.month}`}
              title={`${r.month} 프로젝트 ${money(r.project_revenue)}원 / 기회 ${money(r.opportunity_revenue)}원`}
            >
              <i
                style={{ height: `${(160 * r.opportunity_revenue) / max}px` }}
              />
              <i style={{ height: `${(160 * r.project_revenue) / max}px` }} />
              <span>{r.month}</span>
            </div>
          ))}
        </div>
        <DataTable
          rows={data.items}
          total={data.total}
          rowKey={(r) => r.month}
          columns={[
            { key: "month", label: "월", render: (r) => r.month },
            {
              key: "project",
              label: "진행 중 프로젝트",
              kind: "number",
              render: (r) => money(r.project_revenue),
            },
            {
              key: "opportunity",
              label: "영업 기회",
              kind: "number",
              render: (r) => money(r.opportunity_revenue),
            },
            {
              key: "weighted",
              label: "확률 반영",
              kind: "number",
              render: (r) => money(r.weighted_total),
            },
            {
              key: "all",
              label: "모두 수주 참고",
              kind: "number",
              render: (r) => money(r.all_won_total),
            },
          ]}
        />
      </section>
      <section className={styles.card}>
        <h2>인력 수요와 공급</h2>
        <DataTable
          rows={data.workforce.items}
          total={data.workforce.total}
          rowKey={(r) => `${r.month}:${r.job_cd}:${r.grd_cd}`}
          columns={[
            { key: "month", label: "월", render: (r) => r.month },
            { key: "job", label: "직무", render: (r) => r.job_cd },
            {
              key: "grade",
              label: "등급",
              render: (r) => r.grd_cd || "미분류",
            },
            {
              key: "supply",
              label: "공급 MM",
              kind: "number",
              render: (r) => r.supply_mm.toFixed(2),
            },
            {
              key: "demand",
              label: "수요 MM",
              kind: "number",
              render: (r) => r.demand_mm.toFixed(2),
            },
            {
              key: "remaining",
              label: "남는 MM",
              kind: "number",
              render: (r) => (
                <Badge tone={r.shortage_mm > 0.4 ? "danger" : "neutral"}>
                  {r.remaining_mm.toFixed(2)}
                </Badge>
              ),
            },
            {
              key: "temporary",
              label: "가배정 (별도)",
              kind: "number",
              render: (r) => r.temporary_mm.toFixed(2),
            },
            {
              key: "warnings",
              label: "원천 안내",
              render: (r) => r.warnings.join(" · "),
            },
          ]}
        />
      </section>
      <section className={styles.card}>
        <h2>채용·외부 인력 검토</h2>
        <DataTable
          rows={data.workforce.shortages}
          total={data.workforce.shortages.length}
          rowKey={(r) => `${r.job_cd}:${r.grd_cd}`}
          columns={[
            {
              key: "job",
              label: "직무",
              render: (r) => `${r.job_cd} ${r.grd_cd ?? ""}`,
            },
            {
              key: "first",
              label: "부족 시작",
              render: (r) => r.first_shortage_month,
            },
            {
              key: "max",
              label: "가장 많이 부족",
              render: (r) =>
                `${r.maximum_shortage_mm.toFixed(2)} MM / ${r.maximum_shortage_people}명`,
            },
            {
              key: "hire",
              label: "채용 시작 권장",
              render: (r) =>
                r.review_immediately ? "즉시 검토" : r.recommended_hiring_month,
            },
            {
              key: "candidates",
              label: "외부 인력 후보",
              render: (r) => (
                <Button
                  data-testid={`forecast-candidates-${r.job_cd.toLowerCase()}-${(r.grd_cd ?? "none").toLowerCase()}`}
                  onClick={() => {
                    const target = candidateNavigation(r);
                    navigate(target.pathname, { state: target.state });
                  }}
                >
                  {r.candidate_count}명 보기
                </Button>
              ),
            },
          ]}
        />
      </section>
    </div>
  );
}
export function OpportunityScenarios({ data }: { data: ForecastPage }) {
  const [scenarios, setScenarios] = useState<
    { id: number; revenue: number; mm: number }[]
  >([]);
  const [sequence, setSequence] = useState(0);
  return (
    <section className={styles.card} data-testid="workforce-scenarios">
      <h2>시나리오 비교</h2>
      <p>
        이 브라우저에서 최대 3개만 비교합니다. 원본 확률·인원 계획은 변경되지
        않습니다.
      </p>
      <Button
        data-testid="scenario-add"
        disabled={scenarios.length >= 3}
        onClick={() => {
          setSequence(sequence + 1);
          setScenarios([...scenarios, { id: sequence + 1, revenue: 0, mm: 0 }]);
        }}
      >
        시나리오 추가
      </Button>
      <Button data-testid="scenario-reset" onClick={() => setScenarios([])}>
        비교 초기화
      </Button>
      {scenarios.map((s, index) => (
        <div
          className={styles.form}
          key={s.id}
          data-testid={`scenario-${s.id}`}
        >
          <h3>시나리오 {index + 1}</h3>
          <label>
            월 매출 증감(원)
            <input
              data-testid={`scenario-revenue-${s.id}`}
              type="number"
              value={s.revenue}
              onChange={(e) =>
                setScenarios(
                  scenarios.map((v) =>
                    v.id === s.id
                      ? { ...v, revenue: Number(e.target.value) }
                      : v,
                  ),
                )
              }
            />
          </label>
          <label>
            추가 인력 MM
            <input
              data-testid={`scenario-mm-${s.id}`}
              type="number"
              step="0.01"
              value={s.mm}
              onChange={(e) =>
                setScenarios(
                  scenarios.map((v) =>
                    v.id === s.id ? { ...v, mm: Number(e.target.value) } : v,
                  ),
                )
              }
            />
          </label>
          <DataTable
            rows={data.workforce.items}
            total={data.workforce.total}
            rowKey={(r) => `${r.month}:${r.job_cd}:${r.grd_cd}`}
            columns={[
              {
                key: "month",
                label: "월·직무",
                render: (r) => `${r.month} ${r.job_cd} ${r.grd_cd ?? ""}`,
              },
              {
                key: "source",
                label: "원본 공급 / 수요 / 잔여",
                render: (r) =>
                  `${r.supply_mm.toFixed(2)} / ${r.demand_mm.toFixed(2)} / ${r.remaining_mm.toFixed(2)}`,
              },
              {
                key: "delta",
                label: "가정 인건비 증감",
                render: (r) => money(Math.trunc(s.mm * r.average_labor_cost)),
              },
              {
                key: "profit",
                label: "가정 영업이익",
                render: (r) =>
                  r.baseline_operating_profit == null
                    ? "– (기준 원천 없음)"
                    : money(
                        r.baseline_operating_profit +
                          s.revenue -
                          Math.trunc(s.mm * r.average_labor_cost),
                      ),
              },
              {
                key: "warning",
                label: "안내",
                render: (r) =>
                  r.average_labor_cost === 0
                    ? "원가 미확인 · 인건비 증감 0원"
                    : "추정",
              },
            ]}
          />
          <Button
            data-testid={`scenario-remove-${s.id}`}
            onClick={() => setScenarios(scenarios.filter((v) => v.id !== s.id))}
          >
            시나리오 삭제
          </Button>
        </div>
      ))}
    </section>
  );
}
// SCR-UI-17 uses the shared six-month calculation; SCR-UI-28 embeds the same content without replacing its calendar.
export default function WorkforceDemandScreen({
  screenId = "UI-17",
  name = "인력 수요 예측",
  scenarios = false,
}: {
  screenId?: "UI-17" | "UI-28";
  name?: string;
  scenarios?: boolean;
}) {
  const [filters, setFilters] = useState<ForecastFilters>(() => ({
    year:
      Number(sessionStorage.getItem(`${screenId}.year`)) ||
      Number(seoulMonth().slice(0, 4)),
    size: 50,
    page: 0,
  }));
  const [revision, setRevision] = useState(0);
  const read = useAdminRead(
    useCallback(() => getOpportunityForecast(filters), [filters, revision]),
  );
  const jobs = useCodes("JOB"),
    grades = useCodes("GRADE");
  const year = (value: number) => {
    setFilters({ ...filters, year: value, page: 0 });
    sessionStorage.setItem(`${screenId}.year`, String(value));
  };
  return (
    <section
      className={styles.screen}
      data-screen-id={`SCR-${screenId}`}
      data-testid="workforce-demand-screen"
    >
      <ScreenHeading screenId={screenId} name={name} groupName="인력 계획" />
      <div className={styles.actions}>
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
          onClick={() => setRevision(revision + 1)}
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
        ).map(([key, label, values]) => (
          <label key={key}>
            {label}
            <select
              data-testid={`demand-${key}`}
              value={filters[key] ?? ""}
              onChange={(e) =>
                setFilters({ ...filters, [key]: e.target.value, page: 0 })
              }
            >
              <option value="">전체</option>
              {values.codes.map((c) => (
                <option key={c.code} value={c.code}>
                  {c.name}
                </option>
              ))}
            </select>
          </label>
        ))}
      </div>
      <ErrorBanner
        error={read.error}
        onRetry={() => void read.reload().catch(() => {})}
      />
      {read.loading ? (
        <Skeleton />
      ) : (
        !read.error &&
        read.data && (
          <>
            {read.data.workforce.months.length === 0 ||
            (read.data.total === 0 && read.data.workforce.total === 0) ? (
              <EmptyState
                reason={
                  read.data.workforce.message ??
                  "조건에 맞는 예측 결과가 없습니다."
                }
                actionLabel="조건 초기화"
                onAction={() => setFilters({})}
              />
            ) : (
              <ForecastContent data={read.data} />
            )}
            {scenarios && (
              <OpportunityScenarios key={revision} data={read.data} />
            )}
            <Pagination
              page={(filters.page ?? 0) + 1}
              size={filters.size ?? 50}
              total={Math.max(read.data.total, read.data.workforce.total)}
              onPage={(page) => setFilters({ ...filters, page: page - 1 })}
              onSize={(size) => setFilters({ ...filters, size, page: 0 })}
            />
          </>
        )
      )}
    </section>
  );
}
