import { useEffect, useState } from "react";
import {
  Badge,
  Button,
  LockedBanner,
  Skeleton,
  SuccessToast,
} from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { ApiFailure } from "../../shared/api";
import { ScreenHeading } from "../shared/ScreenHeading";
import { seoulMonth } from "../shared/PeriodBar";
import { useSharedMobile } from "../shared/PlaceholderScreen";
import tables from "../developers/Developers.module.css";
import styles from "./BusinessPlan.module.css";
import {
  getBusinessPlan,
  getBusinessPlanComparison,
  saveBusinessPlan,
  downloadBusinessPlan,
  type BusinessPlans,
  type BusinessComparison,
  type BusinessComparisonMonth,
  type BusinessMetric,
} from "./client";

const months = Array.from({ length: 12 }, (_, i) => i + 1);
const metrics = [
  { key: "revenue", label: "매출" },
  { key: "operating_profit", label: "영업이익" },
  { key: "utilization_rate", label: "가동률" },
] as const;
type MetricKey = (typeof metrics)[number]["key"];
const amount = (value: number | null | undefined, divisor = 1) =>
  value == null
    ? "–"
    : (value / divisor).toLocaleString("ko-KR", { maximumFractionDigits: 1 });
const rate = (value: number | null | undefined) =>
  value == null ? "–" : `${value.toFixed(1)}%`;

export default function UI18Screen({
  name = "사업계획 대비 실적",
  groupName = "재무·자금",
  onDirtyChange,
  onUpload,
}: {
  name?: string;
  groupName?: string;
  onDirtyChange?: (dirty: boolean) => void;
  onUpload?: (year: number, onApplied: () => Promise<void>) => void;
}) {
  const [period, setPeriod] = useState(
    () => sessionStorage.getItem("business-plan-period") ?? seoulMonth(),
  );
  const year = Number(period.slice(0, 4)),
    month = Number(period.slice(5));
  const [partId, setPartId] = useState<number | null>(null);
  const [unit, setUnit] = useState(1);
  const [metric, setMetric] = useState<MetricKey>("revenue");
  const [plans, setPlans] = useState<BusinessPlans | null>(null);
  const [comparison, setComparison] = useState<BusinessComparison | null>(null);
  const [loading, setLoading] = useState(true),
    [pending, setPending] = useState(false);
  const [error, setError] = useState<ApiFailure | null>(null),
    [writeError, setWriteError] = useState<ApiFailure | null>(null);
  const [revision, setRevision] = useState(0),
    [editing, setEditing] = useState(false),
    [dirty, setDirty] = useState(false);
  const [targetMonth, setTargetMonth] = useState(month);
  const [targetPartId, setTargetPartId] = useState<number | null>(null);
  const [revenue, setRevenue] = useState(""),
    [profit, setProfit] = useState(""),
    [utilization, setUtilization] = useState("");
  const [message, setMessage] = useState("");
  const mobile = useSharedMobile();
  const past = year < Number(seoulMonth().slice(0, 4));
  const locked =
    plans?.closed_months.includes(
      `${year}-${String(targetMonth).padStart(2, "0")}`,
    ) ?? false;
  useEffect(() => {
    onDirtyChange?.(dirty);
  }, [dirty, onDirtyChange]);
  useEffect(() => () => onDirtyChange?.(false), [onDirtyChange]);
  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError(null);
    setPlans(null);
    setComparison(null);
    Promise.all([
      getBusinessPlan(year, controller.signal),
      getBusinessPlanComparison(year, month, partId, controller.signal),
    ])
      .then(([p, c]) => {
        if (!controller.signal.aborted) {
          setPlans(p);
          setComparison(c);
        }
      })
      .catch((e: ApiFailure) => {
        if (!controller.signal.aborted) setError(e);
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [year, month, partId, revision]);
  const chooseTarget = (m: number, selectedPart = targetPartId) => {
    setTargetMonth(m);
    setTargetPartId(selectedPart);
    const row = plans?.items.find(
      (p) => p.month === m && p.partId === selectedPart,
    );
    setRevenue(row == null ? "" : String(row.target_revenue));
    setProfit(row == null ? "" : String(row.target_operating_profit));
    setUtilization(row == null ? "" : String(row.target_utilization_rate));
    setDirty(false);
  };
  const changePeriod = (value: string) => {
    if (
      dirty &&
      !window.confirm("저장하지 않은 목표를 버리고 이동하시겠습니까?")
    )
      return;
    setEditing(false);
    setDirty(false);
    setPeriod(value);
    sessionStorage.setItem("business-plan-period", value);
  };
  const reload = async () => {
    setRevision((v) => v + 1);
  };
  const save = async () => {
    if (pending || locked || past) return;
    const invalid = (text: string) => {
      setWriteError(new ApiFailure(text, 400, null));
    };
    if (targetPartId == null) return invalid("목표 파트를 선택하세요.");
    if (!/^\d+$/.test(revenue) || !Number.isSafeInteger(Number(revenue)))
      return invalid("목표매출은 0 이상의 원 단위 정수로 입력하세요.");
    if (!/^-?\d+$/.test(profit) || !Number.isSafeInteger(Number(profit)))
      return invalid("목표영업이익은 원 단위 정수로 입력하세요.");
    if (!/^\d+(\.\d{1,2})?$/.test(utilization) || Number(utilization) > 100)
      return invalid("목표가동률은 0~100, 소수 둘째 자리까지 입력하세요.");
    setPending(true);
    setWriteError(null);
    try {
      await saveBusinessPlan(year, [
        {
          year,
          month: targetMonth,
          partId: targetPartId,
          target_revenue: Number(revenue),
          target_operating_profit: Number(profit),
          target_utilization_rate: Number(utilization),
        },
      ]);
      setDirty(false);
      setEditing(false);
      setMessage("사업계획 목표를 저장했습니다.");
      await reload();
    } catch (e) {
      setWriteError(e as ApiFailure);
    } finally {
      setPending(false);
    }
  };
  const download = () => {
    if (!comparison) return;
    try {
      downloadBusinessPlan(comparison.export_file);
    } catch (e) {
      setWriteError(e as ApiFailure);
    }
  };
  return (
    <section data-testid="business-plan-screen" className={styles.content}>
      <ScreenHeading
        screenId="UI-18"
        name={name}
        groupName={groupName}
        actions={
          <>
            {!past && !mobile && (
              <>
                <Button
                  data-testid="business-plan-edit"
                  disabled={loading || !!error || pending}
                  onClick={() => {
                    chooseTarget(
                      month,
                      partId ?? plans?.parts.find((p) => p.active)?.id ?? null,
                    );
                    setEditing(true);
                  }}
                >
                  목표 입력
                </Button>
                <Button
                  data-testid="business-plan-upload"
                  disabled={!onUpload || loading || pending}
                  onClick={() => onUpload?.(year, reload)}
                >
                  목표 업로드
                </Button>
              </>
            )}
            <Button
              data-testid="business-plan-download"
              disabled={!comparison || loading}
              onClick={download}
            >
              엑셀 다운로드
            </Button>
          </>
        }
      />
      <div className={tables.search}>
        <Button
          data-testid="business-plan-previous-year"
          disabled={year <= 1 || pending}
          onClick={() =>
            changePeriod(
              `${String(year - 1).padStart(4, "0")}-${String(month).padStart(2, "0")}`,
            )
          }
        >
          이전 해
        </Button>
        <strong>{year}년</strong>
        <Button
          data-testid="business-plan-next-year"
          disabled={year >= 9999 || pending}
          onClick={() =>
            changePeriod(
              `${String(year + 1).padStart(4, "0")}-${String(month).padStart(2, "0")}`,
            )
          }
        >
          다음 해
        </Button>
        <label>
          누계 기준월{" "}
          <select
            aria-label="누계 기준월"
            data-testid="business-plan-month"
            value={month}
            disabled={pending}
            onChange={(e) =>
              changePeriod(`${year}-${e.target.value.padStart(2, "0")}`)
            }
          >
            {months.map((m) => (
              <option key={m} value={m}>
                {m}월
              </option>
            ))}
          </select>
        </label>
        <span>1~{month}월 누계</span>
        <label>
          파트{" "}
          <select
            aria-label="파트"
            data-testid="business-plan-part"
            value={partId ?? ""}
            disabled={dirty || pending}
            onChange={(e) => {
              setPartId(e.target.value ? Number(e.target.value) : null);
              setEditing(false);
            }}
          >
            <option value="">전사</option>
            {plans?.parts.map((p) => (
              <option key={p.id} value={p.id}>
                {p.name}
                {p.active ? "" : " (사용 안 함)"}
              </option>
            ))}
          </select>
        </label>
        <label>
          단위{" "}
          <select
            aria-label="단위"
            data-testid="business-plan-unit"
            value={unit}
            onChange={(e) => setUnit(Number(e.target.value))}
          >
            <option value={1}>원</option>
            <option value={1000}>천 원</option>
            <option value={1000000}>백만 원</option>
          </select>
        </label>
      </div>
      {past && (
        <LockedBanner>지난 연도는 조회·다운로드만 가능합니다.</LockedBanner>
      )}
      <ErrorBanner error={error} onRetry={() => void reload()} />
      <ErrorBanner error={writeError} />
      {message && (
        <SuccessToast message={message} onDismiss={() => setMessage("")} />
      )}
      {loading && <Skeleton />}
      {!loading && !error && plans && comparison && (
        <>
          {comparison.warnings.map((w, i) => (
            <div key={w} data-testid={`business-plan-warning-${i}`}>
              <Badge tone="warning">{w}</Badge>
            </div>
          ))}
          {plans.items.length === 0 && (
            <p className={tables.empty}>
              등록된 목표가 없습니다. 목표를 입력해 주세요.
            </p>
          )}
          <div className={styles.summary}>
            {metrics.map(({ key, label }) => {
              const value = comparison.comparison.cumulative[key];
              return (
                <section
                  key={key}
                  className={tables.card}
                  data-testid={`business-plan-summary-${key.replaceAll("_", "-")}`}
                >
                  <h2>누계 {label}</h2>
                  <dl>
                    <dt>목표</dt>
                    <dd>
                      {key === "utilization_rate"
                        ? rate(value.target)
                        : amount(value.target, unit)}
                    </dd>
                    <dt>실적</dt>
                    <dd>
                      {key === "utilization_rate"
                        ? rate(value.actual)
                        : amount(value.actual, unit)}
                    </dd>
                    <dt>달성률</dt>
                    <dd>{rate(value.achievement_rate)}</dd>
                    <dt>차이</dt>
                    <dd
                      className={
                        (value.difference ?? 0) < 0
                          ? styles.negative
                          : styles.positive
                      }
                    >
                      {key === "utilization_rate"
                        ? `${amount(value.difference)}${value.difference == null ? "" : "%p"}`
                        : amount(value.difference, unit)}
                    </dd>
                  </dl>
                </section>
              );
            })}
          </div>
          <div className={tables.actions} role="tablist" aria-label="비교 항목">
            {metrics.map(({ key, label }) => (
              <Button
                key={key}
                role="tab"
                data-testid={`business-plan-tab-${key.replaceAll("_", "-")}`}
                aria-selected={metric === key}
                onClick={() => setMetric(key)}
              >
                {label}
              </Button>
            ))}
          </div>
          <section className={tables.card}>
            <h2>
              월별 {metrics.find((m) => m.key === metric)?.label} 실적과 목표
            </h2>
            <BusinessPlanChart
              rows={comparison.comparison.months}
              metric={metric}
              testId="business-plan-monthly-chart"
              unit={metric === "utilization_rate" ? 1 : unit}
            />
          </section>
          <section className={tables.card}>
            <h2>누계 실적과 목표</h2>
            <BusinessPlanChart
              rows={comparison.comparison.cumulative_months ?? []}
              metric={metric}
              testId="business-plan-cumulative-chart"
              unit={metric === "utilization_rate" ? 1 : unit}
            />
          </section>
          <div className={tables.tableScroll}>
            <table className={tables.table} aria-label="사업계획 월별 비교">
              <thead>
                <tr>
                  <th>항목</th>
                  {months.map((m) => (
                    <th key={m}>{m}월</th>
                  ))}
                  <th>1~{month}월 누계</th>
                </tr>
              </thead>
              <tbody>
                {metrics.flatMap(({ key, label }) =>
                  (
                    [
                      "target",
                      "actual",
                      "difference",
                      "achievement_rate",
                    ] as const
                  ).map((field) => (
                    <tr
                      key={`${key}-${field}`}
                      data-testid={`business-plan-row-${key.replaceAll("_", "-")}-${field.replaceAll("_", "-")}`}
                    >
                      <th>
                        {label}{" "}
                        {
                          {
                            target: "목표",
                            actual: "실적",
                            difference: "차이",
                            achievement_rate: "달성률",
                          }[field]
                        }
                      </th>
                      {comparison.comparison.months.map((m) => (
                        <td key={m.month} className={tables.amount}>
                          {field === "target" && !m.target_present
                            ? "미입력"
                            : field === "achievement_rate"
                              ? rate(m[key][field])
                              : key === "utilization_rate"
                                ? amount(m[key][field])
                                : amount(m[key][field], unit)}
                        </td>
                      ))}
                      <td className={tables.amount}>
                        {field === "achievement_rate"
                          ? rate(comparison.comparison.cumulative[key][field])
                          : amount(
                              comparison.comparison.cumulative[key][field],
                              key === "utilization_rate" ? 1 : unit,
                            )}
                      </td>
                    </tr>
                  )),
                )}
              </tbody>
            </table>
          </div>
          {editing && !past && !mobile && (
            <section className={tables.card} data-testid="business-plan-editor">
              <h2>
                목표 입력 ·{" "}
                {plans.parts.find((p) => p.id === targetPartId)?.name ??
                  "파트 선택"}
              </h2>
              {locked && (
                <LockedBanner>
                  마감 월의 목표는 수정할 수 없습니다.
                </LockedBanner>
              )}
              <div className={styles.editor}>
                <label>
                  목표 파트
                  <select
                    aria-label="목표 파트"
                    data-testid="business-plan-target-part"
                    value={targetPartId ?? ""}
                    disabled={pending || dirty}
                    onChange={(e) =>
                      chooseTarget(
                        targetMonth,
                        e.target.value ? Number(e.target.value) : null,
                      )
                    }
                  >
                    <option value="">선택해 주세요</option>
                    {plans.parts
                      .filter((p) => p.active || p.id === targetPartId)
                      .map((p) => (
                        <option key={p.id} value={p.id}>
                          {p.name}
                        </option>
                      ))}
                  </select>
                </label>
                <label>
                  목표 월
                  <select
                    aria-label="목표 월"
                    data-testid="business-plan-target-month"
                    value={targetMonth}
                    disabled={pending || dirty}
                    onChange={(e) => chooseTarget(Number(e.target.value))}
                  >
                    {months.map((m) => (
                      <option key={m} value={m}>
                        {m}월
                      </option>
                    ))}
                  </select>
                </label>
                <label>
                  목표매출
                  <input
                    aria-label="목표매출"
                    data-testid="business-plan-target-revenue"
                    inputMode="numeric"
                    value={revenue}
                    disabled={locked || pending}
                    onChange={(e) => {
                      setRevenue(e.target.value);
                      setDirty(true);
                    }}
                  />
                </label>
                <label>
                  목표영업이익
                  <input
                    aria-label="목표영업이익"
                    data-testid="business-plan-target-profit"
                    inputMode="numeric"
                    value={profit}
                    disabled={locked || pending}
                    onChange={(e) => {
                      setProfit(e.target.value);
                      setDirty(true);
                    }}
                  />
                </label>
                <label>
                  목표가동률
                  <input
                    aria-label="목표가동률"
                    data-testid="business-plan-target-utilization"
                    inputMode="decimal"
                    value={utilization}
                    disabled={locked || pending}
                    onChange={(e) => {
                      setUtilization(e.target.value);
                      setDirty(true);
                    }}
                  />
                </label>
                <Button
                  data-testid="business-plan-save"
                  variant="primary"
                  pending={pending}
                  disabled={locked}
                  onClick={() => void save()}
                >
                  저장
                </Button>
                <Button
                  data-testid="business-plan-cancel"
                  disabled={pending}
                  onClick={() => {
                    if (
                      !dirty ||
                      window.confirm("입력한 목표를 버리시겠습니까?")
                    ) {
                      setEditing(false);
                      setDirty(false);
                    }
                  }}
                >
                  취소
                </Button>
              </div>
              <p className={tables.help}>
                금액은 원 단위입니다. 음수 영업이익 목표와 0 목표를 입력할 수
                있습니다.
              </p>
            </section>
          )}
        </>
      )}
    </section>
  );
}
function BusinessPlanChart({
  rows,
  metric,
  testId,
  unit,
}: {
  rows: BusinessComparisonMonth[];
  metric: MetricKey;
  testId: string;
  unit: number;
}) {
  const values = rows
    .flatMap((m) => [m[metric].target, m[metric].actual])
    .filter((v): v is number => v != null);
  const max = Math.max(1, ...values.map(Math.abs));
  return (
    <div data-testid={testId}>
      <div className={styles.legend}>
        <span>
          <i className={styles.targetMark} />
          목표
        </span>
        <span>
          <i className={styles.actualMark} />
          실적
        </span>
      </div>
      <div className={tables.tableScroll}>
        <svg
          viewBox="0 0 960 270"
          className={styles.chart}
          role="img"
          aria-label={
            testId.includes("cumulative")
              ? "누계 목표 대비 실적 차트"
              : "월별 목표 대비 실적 차트"
          }
        >
          <line x1="35" y1="135" x2="950" y2="135" />
          {rows.map((row, i) => {
            const x = 45 + i * 75;
            return (
              <g key={row.month}>
                <text x={x} y="260">
                  {row.month}월
                </text>
                {(["target", "actual"] as const).map((field, j) => {
                  const value: BusinessMetric["actual"] = row[metric][field];
                  if (value == null) return null;
                  const height = (Math.abs(value) / max) * 110;
                  return (
                    <rect
                      key={field}
                      className={
                        field === "target" ? styles.target : styles.actual
                      }
                      x={x + j * 23}
                      y={value < 0 ? 135 : 135 - height}
                      width="20"
                      height={height}
                    >
                      <title>
                        {row.month}월 {field === "target" ? "목표" : "실적"}:{" "}
                        {amount(value, unit)}
                      </title>
                    </rect>
                  );
                })}
              </g>
            );
          })}
        </svg>
      </div>
    </div>
  );
}
