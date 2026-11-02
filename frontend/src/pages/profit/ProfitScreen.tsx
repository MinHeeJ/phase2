import { useEffect, useState } from "react";
import { Badge, Button, LockedBanner, Skeleton } from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { ApiFailure } from "../../shared/api";
import { ScreenHeading } from "../shared/ScreenHeading";
import { seoulMonth } from "../shared/PeriodBar";
import tables from "../developers/Developers.module.css";
import styles from "./Profit.module.css";
import {
  downloadProfit,
  getFinanceProfitStructure,
  getMonthlyFinanceProfit,
  profitFormat as fmt,
  profitRows,
  type ProfitAnnual,
  type ProfitStructure,
  type ProfitValues,
  type ProfitMonth,
  type ProfitMonthValues,
  type ProfitMetric,
  type ProfitPeriod,
  type ProfitComposition,
} from "./client";

type ScreenId = "UI-07" | "UI-08" | "UI-09";
export interface ProfitScreenProps {
  name?: string;
  groupName?: string;
  onPriorUpload?: (year: number, onApplied: () => void) => void;
}
const headings = {
  "UI-07": "전체 수익 구조",
  "UI-08": "영업이익표",
  "UI-09": "순이익표",
};
const warningLabels: Record<string, string> = {
  MISSING_CLOSED_SNAPSHOT: "마감 손익 스냅샷이 없습니다.",
  MISSING_IMPORT: "이관 손익 자료가 없습니다.",
  FUTURE_ACTUAL: "미래 실적 미산정",
  MISSING_PAYROLL: "급여 원천 자료가 없습니다.",
  MISSING_TAX_PLAN: "법인세 계획 또는 마감 월할 자료가 없습니다.",
  MISSING_REVENUE_SOURCE: "전년도 매출 원천 자료가 없습니다.",
  UNCONFIRMED_BILLING_EXCLUDED: "미확정 청구는 매출에서 제외했습니다.",
};
const warningLabel = (code: string) => warningLabels[code] ?? code;
export function ProfitScreen({
  screenId,
  name = headings[screenId],
  groupName = "재무·자금",
  onPriorUpload,
}: ProfitScreenProps & { screenId: ScreenId }) {
  const storage = `profit-${screenId.toLowerCase()}-period`;
  const [period, setPeriod] = useState(
    () => sessionStorage.getItem(storage) ?? seoulMonth(),
  );
  const year = Number(period.slice(0, 4)),
    month = Number(period.slice(5));
  const [scope, setScope] = useState<ProfitPeriod>("YTD"),
    [unit, setUnit] = useState(1),
    [revision, setRevision] = useState(0);
  const [annual, setAnnual] = useState<ProfitAnnual | null>(null),
    [structure, setStructure] = useState<ProfitStructure | null>(null);
  const [error, setError] = useState<ApiFailure | null>(null),
    [downloadError, setDownloadError] = useState<ApiFailure | null>(null),
    [loading, setLoading] = useState(true);
  useEffect(() => {
    const abort = new AbortController();
    setLoading(true);
    setError(null);
    setAnnual(null);
    setStructure(null);
    const request =
      screenId === "UI-07"
        ? getFinanceProfitStructure(year, month, scope, abort.signal)
        : getMonthlyFinanceProfit(year, abort.signal);
    request
      .then((data) => {
        if (abort.signal.aborted) return;
        if ("previous_months" in data) setAnnual(data);
        else setStructure(data);
      })
      .catch((e: ApiFailure) => {
        if (!abort.signal.aborted) setError(e);
      })
      .finally(() => {
        if (!abort.signal.aborted) setLoading(false);
      });
    return () => abort.abort();
  }, [screenId, year, month, scope, revision]);
  useEffect(() => {
    if (screenId !== "UI-09") return;
    const abort = new AbortController();
    getFinanceProfitStructure(year, month, "YTD", abort.signal)
      .then((data) => {
        if (!abort.signal.aborted) setStructure(data);
      })
      .catch((e: ApiFailure) => {
        if (!abort.signal.aborted) setError(e);
      });
    return () => abort.abort();
  }, [screenId, year, month, revision]);
  const change = (y: number, m = month) => {
    const value = `${String(y).padStart(4, "0")}-${String(m).padStart(2, "0")}`;
    setPeriod(value);
    sessionStorage.setItem(storage, value);
  };
  const selected = annual?.months.find(
    (m) => Number(m.month.slice(5)) === month,
  );
  const values: ProfitValues =
    screenId === "UI-07" ? (structure ?? {}) : (selected?.cumulative ?? {});
  const previous: ProfitValues =
    screenId === "UI-07"
      ? (structure?.previous ?? {})
      : (selected?.previous ?? {});
  const months = annual?.months ?? structure?.months ?? [];
  const warnings = Array.from(
    new Set(
      months
        .filter(
          (m) =>
            Number(m.month.slice(5)) <= month ||
            m.warning.includes("FUTURE_ACTUAL"),
        )
        .flatMap((m) => m.warnings ?? [])
        .concat(structure?.warnings ?? []),
    ),
  );
  const imported = year === 2025 || months.some((m) => m.source === "IMPORT");
  const file =
    screenId === "UI-07"
      ? structure?.export_file
      : screenId === "UI-08"
        ? selected?.operating_export_file
        : selected?.net_export_file;
  const download = () => {
    if (!file) return;
    try {
      downloadProfit(file);
    } catch (e) {
      setDownloadError(e as ApiFailure);
    }
  };
  const summaryKeys: ProfitMetric[] =
    screenId === "UI-09"
      ? [
          "operating_profit",
          "non_operating_income",
          "corporate_tax",
          "net_profit",
        ]
      : ["revenue", "cost", "operating_profit", "net_profit"];
  return (
    <section
      data-testid={`profit-${screenId.toLowerCase()}-screen`}
      className={styles.content}
    >
      <ScreenHeading
        screenId={screenId}
        name={name}
        groupName={groupName}
        actions={
          <>
            {screenId === "UI-08" && year === 2025 && onPriorUpload && (
              <Button
                data-testid="profit-prior-upload"
                onClick={() =>
                  onPriorUpload(year, () => setRevision((v) => v + 1))
                }
              >
                2025 손익 이관
              </Button>
            )}
            <Button
              data-testid="profit-download"
              disabled={loading || !!error || !file}
              onClick={download}
            >
              엑셀 다운로드
            </Button>
          </>
        }
      />
      <div className={tables.search}>
        <Button
          data-testid="profit-previous-year"
          disabled={year <= 1}
          onClick={() => change(year - 1)}
        >
          이전 해
        </Button>
        <strong>{year}년</strong>
        <Button
          data-testid="profit-next-year"
          disabled={year >= 9999}
          onClick={() => change(year + 1)}
        >
          다음 해
        </Button>
        <label>
          누계 기준월{" "}
          <select
            aria-label="누계 기준월"
            data-testid="profit-month"
            value={month}
            onChange={(e) => change(year, Number(e.target.value))}
          >
            {Array.from({ length: 12 }, (_, i) => (
              <option key={i} value={i + 1}>
                {i + 1}월
              </option>
            ))}
          </select>
        </label>
        <span>1~{month}월 누계</span>
        <label>
          단위{" "}
          <select
            aria-label="단위"
            data-testid="profit-unit"
            value={unit}
            onChange={(e) => setUnit(Number(e.target.value))}
          >
            <option value={1}>원</option>
            <option value={1000}>천 원</option>
            <option value={1000000}>백만 원</option>
          </select>
        </label>
      </div>
      {screenId === "UI-07" && (
        <div role="tablist" aria-label="조회 기간" className={tables.actions}>
          {(["MONTH", "QUARTER", "YTD"] as const).map((key) => (
            <Button
              role="tab"
              aria-selected={scope === key}
              data-testid={`profit-period-${key.toLowerCase()}`}
              key={key}
              onClick={() => setScope(key)}
            >
              {{ MONTH: "월", QUARTER: "분기", YTD: "연 누계" }[key]}
            </Button>
          ))}
        </div>
      )}
      {year < Number(seoulMonth().slice(0, 4)) && (
        <LockedBanner>지난 연도는 조회·다운로드만 가능합니다.</LockedBanner>
      )}
      <ErrorBanner error={error} onRetry={() => setRevision((v) => v + 1)} />
      <ErrorBanner error={downloadError} />
      {loading && <Skeleton />}
      {!loading && !error && months.length === 0 && (
        <div className={tables.empty}>조회할 손익 자료가 없습니다.</div>
      )}
      {!loading && !error && months.length > 0 && (
        <>
          {warnings.map((warning, i) => (
            <div
              className={styles.warning}
              data-testid={`profit-warning-${i}`}
              key={warning}
            >
              {warningLabel(warning)}
            </div>
          ))}
          <div data-testid="profit-summary" className={styles.summary}>
            {summaryKeys.map((key) => (
              <section
                key={key}
                className={tables.card}
                data-testid={`profit-summary-${key.replaceAll("_", "-")}`}
              >
                <h2>{profitRows.find((r) => r.key === key)?.label}</h2>
                <strong>{fmt(values[key], unit)}</strong>
                <small>전년 동기간 {fmt(previous[key], unit)}</small>
              </section>
            ))}
          </div>
          {screenId === "UI-07" && structure && (
            <>
              <section className={tables.card}>
                <h2>수익 구조 — 매출에서 순이익까지</h2>
                <Waterfall values={structure} unit={unit} />
                <p className={tables.help}>
                  매출은 확정 청구 공급가액 기준입니다. 배분 기준 매출{" "}
                  {fmt(structure.allocation_revenue_reference, unit)}은
                  참고선이며 손익에 더하지 않습니다.
                </p>
              </section>
              <div className={styles.grid}>
                <section className={tables.card}>
                  <h2>매출·원가 추이</h2>
                  <Trend
                    rows={months.filter(
                      (m) => Number(m.month.slice(5)) <= month,
                    )}
                    keys={["revenue", "cost"]}
                    name="매출·원가 추이"
                    unit={unit}
                  />
                </section>
                <section className={tables.card}>
                  <h2>이익 추이</h2>
                  <Trend
                    rows={months.filter(
                      (m) => Number(m.month.slice(5)) <= month,
                    )}
                    keys={["operating_profit", "net_profit"]}
                    name="이익 추이"
                    unit={unit}
                  />
                </section>
              </div>
            </>
          )}
          {screenId !== "UI-07" && (
            <ProfitTable
              months={months}
              selected={selected}
              net={screenId === "UI-09"}
              unit={unit}
              month={month}
            />
          )}
          {(screenId === "UI-07" || screenId === "UI-09") &&
            (imported ? (
              <div className={tables.empty}>
                자료 없음 · 이관 연도는 구성 분석을 제공하지 않습니다.
              </div>
            ) : structure?.composition_available ? (
              <div className={styles.grid}>
                {screenId === "UI-07" && (
                  <Composition
                    title="원가 구성"
                    rows={structure.cost_breakdown}
                    unit={unit}
                    cost
                  />
                )}
                <Composition
                  title={
                    screenId === "UI-09" ? "계약 유형별 기여도" : "계약 유형별"
                  }
                  rows={structure.revenue_by_contract}
                  unit={unit}
                />
                {screenId === "UI-07" && (
                  <>
                    <Composition
                      title="고객사 상위 5곳"
                      rows={structure.revenue_by_customer}
                      unit={unit}
                    />
                    <Composition
                      title="영업 파트별"
                      rows={structure.revenue_by_part}
                      unit={unit}
                    />
                  </>
                )}
                {screenId === "UI-09" && (
                  <p>
                    {structure.contribution_note} 총액과 차이{" "}
                    {fmt(structure.contribution_difference, unit)}
                  </p>
                )}
              </div>
            ) : (
              <p className={tables.empty}>
                자료 없음 · 구성 원천을 확인해 주세요.
              </p>
            ))}
          {screenId === "UI-07" && structure && (
            <section className={tables.card}>
              <h2>숫자로 보기</h2>
              <div className={tables.tableScroll}>
                <table className={tables.table}>
                  <thead>
                    <tr>
                      <th>항목</th>
                      <th>금액</th>
                      <th>전년 동기간</th>
                      <th>차이</th>
                      <th>증감률</th>
                    </tr>
                  </thead>
                  <tbody>
                    {profitRows.map(({ key, label, cost }) => (
                      <tr
                        key={key}
                        data-testid={`profit-number-${key.replaceAll("_", "-")}`}
                      >
                        <th>{label}</th>
                        <td className={tables.amount}>
                          {fmt(values[key], unit, key.endsWith("margin"))}
                        </td>
                        <td className={tables.amount}>
                          {fmt(previous[key], unit, key.endsWith("margin"))}
                        </td>
                        <td
                          className={differenceClass(
                            structure.difference[key],
                            cost,
                          )}
                        >
                          {fmt(structure.difference[key], unit)}
                        </td>
                        <td>{fmt(structure.growth_rate[key], 1, true)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </section>
          )}
          <details className={tables.card}>
            <summary data-testid="profit-calculation">계산 방법</summary>
            <p className={styles.formula}>
              매출총이익 = 매출 − 매출원가
              <br />
              영업이익 = 매출총이익 − 판관비 + 전월 조정
              <br />
              당기순이익 = 영업이익 + 영업외수익 − 영업외비용 − 법인세(월할)
              <br />
              비율은 소수 첫째 자리 반올림, 분모가 0이거나 자료가 없으면 – 로
              표시합니다. 미확정 청구·입금·파트 정산은 손익에 더하지 않습니다.
            </p>
          </details>
        </>
      )}
    </section>
  );
}
function differenceClass(value: number | null | undefined, cost = false) {
  return value == null || value === 0
    ? tables.amount
    : value > 0 !== cost
      ? styles.positive
      : styles.negative;
}
function ProfitTable({
  months,
  selected,
  net,
  unit,
  month,
}: {
  months: ProfitMonthValues[];
  selected: ProfitMonth | undefined;
  net: boolean;
  unit: number;
  month: number;
}) {
  const rows = net
    ? profitRows.filter((r) =>
        [
          "revenue",
          "operating_profit",
          "non_operating_income",
          "non_operating_expenses",
          "corporate_tax",
          "net_profit",
          "net_margin",
        ].includes(r.key),
      )
    : profitRows.slice(0, 12);
  return (
    <div className={tables.tableScroll}>
      <table
        className={tables.table}
        aria-label={net ? "순이익표" : "영업이익표"}
      >
        <thead>
          <tr>
            <th>항목</th>
            {months.map((m) => (
              <th
                key={m.month}
                className={
                  Number(m.month.slice(5)) === month ? styles.current : ""
                }
              >
                {Number(m.month.slice(5))}월{" "}
                {m.closed ? (
                  <Badge tone="locked">마감</Badge>
                ) : m.warning.includes("FUTURE_ACTUAL") ? null : (
                  <Badge tone="info">작업중</Badge>
                )}
              </th>
            ))}
            <th>누계</th>
            <th>전년 동기간</th>
            <th>차이</th>
          </tr>
        </thead>
        <tbody>
          {rows.map(({ key, label, cost }) => {
            const current = selected?.cumulative[key],
              previous = selected?.previous[key],
              delta =
                current == null || previous == null ? null : current - previous;
            return (
              <tr
                data-testid={`profit-row-${key.replaceAll("_", "-")}`}
                key={key}
              >
                <th>{label}</th>
                {months.map((m) => (
                  <td className={tables.amount} key={m.month}>
                    {fmt(m[key], unit, key.endsWith("margin"))}
                  </td>
                ))}
                <td className={tables.amount}>
                  {fmt(current, unit, key.endsWith("margin"))}
                </td>
                <td className={tables.amount}>
                  {fmt(previous, unit, key.endsWith("margin"))}
                </td>
                <td className={differenceClass(delta, cost)}>
                  {key.endsWith("margin")
                    ? delta == null
                      ? "–"
                      : `${delta.toFixed(1)}%p`
                    : fmt(delta, unit)}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
function Waterfall({ values, unit }: { values: ProfitValues; unit: number }) {
  const fields = profitRows.filter((r) =>
    [
      "revenue",
      "cost",
      "gross_profit",
      "sga",
      "previous_adjustment",
      "operating_profit",
      "non_operating_income",
      "non_operating_expenses",
      "corporate_tax",
      "net_profit",
    ].includes(r.key),
  );
  const max = Math.max(1, ...fields.map((r) => Math.abs(values[r.key] ?? 0)));
  let running = 0;
  return (
    <div
      role="img"
      aria-label="수익 구조 폭포 차트"
      className={styles.waterfall}
    >
      {fields.map(({ key, label, cost }) => {
        const value = values[key];
        const total = [
          "revenue",
          "gross_profit",
          "operating_profit",
          "net_profit",
        ].includes(key);
        const start = total ? 0 : running;
        const end = total
          ? (value ?? 0)
          : running + (cost ? -(value ?? 0) : (value ?? 0));
        running = end;
        return (
          <div key={key} className={styles.waterfallRow}>
            <span>{label}</span>
            <span className={styles.track}>
              <i
                className={
                  cost ? styles.negative : total ? "" : styles.positive
                }
                style={{
                  width: `${value == null ? 0 : (Math.abs(value) / max) * 70}%`,
                  marginLeft: `${(Math.max(0, Math.min(start, end)) / max) * 20}%`,
                }}
              />
            </span>
            <span>{fmt(value, unit)}</span>
          </div>
        );
      })}
    </div>
  );
}
function Trend({
  rows,
  keys,
  name,
  unit,
}: {
  rows: ProfitMonthValues[];
  keys: ProfitMetric[];
  name: string;
  unit: number;
}) {
  const max = Math.max(
    1,
    ...rows.flatMap((row) => keys.map((key) => Math.abs(row[key] ?? 0))),
  );
  return (
    <div className={tables.tableScroll}>
      <svg
        className={styles.chart}
        viewBox="0 0 900 240"
        role="img"
        aria-label={name}
      >
        <line x1="25" x2="890" y1="120" y2="120" />
        {rows.map((row, i) => (
          <g key={row.month}>
            <text x={35 + i * 70} y="232">
              {Number(row.month.slice(5))}월
            </text>
            {keys.map((key, j) => {
              const value = row[key];
              if (value == null) return null;
              const height = (Math.abs(value) / max) * 95;
              return (
                <rect
                  key={key}
                  className={
                    value < 0 ? styles.negative : j ? styles.positive : ""
                  }
                  x={35 + i * 70 + j * 22}
                  y={value < 0 ? 120 : 120 - height}
                  width="19"
                  height={height}
                >
                  <title>
                    {profitRows.find((r) => r.key === key)?.label}{" "}
                    {fmt(value, unit)}
                  </title>
                </rect>
              );
            })}
          </g>
        ))}
      </svg>
    </div>
  );
}
function Composition({
  title,
  rows,
  unit,
  cost = false,
}: {
  title: string;
  rows: ProfitComposition[];
  unit: number;
  cost?: boolean;
}) {
  return (
    <section className={tables.card}>
      <h2>{title}</h2>
      {rows.length === 0 ? (
        <p className={tables.empty}>구성 자료가 없습니다.</p>
      ) : (
        <div className={tables.tableScroll}>
          <table className={tables.table}>
            <thead>
              <tr>
                <th>구분</th>
                <th>{cost ? "원가" : "매출"}</th>
                <th>비중</th>
                {!cost && (
                  <>
                    <th>매출총이익</th>
                    <th>마진</th>
                  </>
                )}
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => (
                <tr
                  key={row.code}
                  data-testid={`profit-${title === "원가 구성" ? "cost" : title === "고객사 상위 5곳" ? "customer" : title === "영업 파트별" ? "part" : "contract"}-${row.code.toLowerCase().replaceAll("_", "-")}`}
                >
                  <th>{row.name}</th>
                  <td className={tables.amount}>
                    {fmt(cost ? row.cost : row.revenue, unit)}
                  </td>
                  <td>{fmt(row.share, 1, true)}</td>
                  {!cost && (
                    <>
                      <td className={tables.amount}>
                        {fmt(row.gross_profit, unit)}
                      </td>
                      <td>{fmt(row.margin, 1, true)}</td>
                    </>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
