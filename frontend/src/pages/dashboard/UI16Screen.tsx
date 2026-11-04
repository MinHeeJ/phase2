import { useEffect, useRef, useState, type ReactNode } from "react";
import { Link, useLocation } from "react-router-dom";
import { Badge, Button, Skeleton } from "../../shared/Controls";
import { ApiFailure } from "../../shared/api";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { ScreenHeading } from "../shared/ScreenHeading";
import { seoulMonth } from "../shared/PeriodBar";
import {
  getDashboard,
  downloadDashboard,
  type DashboardCard,
  type DashboardComparison,
  type DashboardData,
} from "../apiops/dashboardClient";
import tables from "../developers/Developers.module.css";
import styles from "../apiops/Dashboard.module.css";
import { CashChart, WorkforceChart } from "./DashboardCharts";
import { opportunityStages } from "../opportunities/client";

const ratio = (value: number | null, suffix = "%") =>
  value == null ? "–" : `${value.toFixed(1)}${suffix}`;
const signed = (value: number | null, suffix = "%") =>
  value == null ? "–" : `${value > 0 ? "+" : ""}${value.toFixed(1)}${suffix}`;
const comparison = (value: DashboardComparison) =>
  `${value.label} ${signed(value.changePercent)}`;
const mm = (value: number | null) => (value == null ? "–" : value.toFixed(2));
const failure = (value: unknown) =>
  value instanceof ApiFailure
    ? value
    : new ApiFailure("조회하지 못했습니다.", null, null);
const actionTargets: Record<string, string> = {
  OVERDUE_RECEIVABLE: "UI-13",
  CASH_SHORTAGE: "UI-21",
  WORKFORCE_SHORTAGE: "UI-17",
  RISK_PROJECT: "UI-31",
  MM_EXCESS: "UI-12",
  UNCONTRACTED_ASSIGNMENT: "UI-12",
  WITHDRAWAL: "UI-05",
  UNASSIGNED_PART: "UI-01",
  OVERDUE_ISSUE: "UI-31",
  STALE_PROJECT_UPDATE: "UI-31",
  BILLING_GAP: "UI-13",
  PENDING_QUOTE_APPROVAL: "UI-29",
  UNCLOSED_PREVIOUS_MONTH: "UI-14",
  STALE_OPPORTUNITY: "UI-29",
  LOW_MARGIN: "UI-04",
  UNCONFIRMED_ADDITIONAL_CONTRACT: "UI-01",
};
// 이동할 화면은 화면 ID가 아니라 메뉴 이름으로 보여 준다.
const screenNames: Record<string, string> = {
  "UI-01": "프로젝트",
  "UI-04": "프로젝트 원가 분석",
  "UI-05": "철수 리스트",
  "UI-12": "투입 배정",
  "UI-13": "청구·입금",
  "UI-14": "월 마감",
  "UI-17": "인력 수요 예측",
  "UI-21": "자금수지표",
  "UI-29": "영업 파이프라인",
  "UI-31": "프로젝트 현황판",
};
const costCauseNames: Record<string, string> = {
  OVER_MM: "투입 초과",
  COST_RATE: "단가 대비 원가",
  FREE_SUPPORT: "무상지원",
};
const stageNames: Record<string, string> = Object.fromEntries(
  opportunityStages.map((stage) => [stage.code, stage.name]),
);
const asOfText = (value: string) => {
  const time = new Date(value);
  return Number.isNaN(time.getTime())
    ? value
    : time.toLocaleString("ko-KR", {
        timeZone: "Asia/Seoul",
        year: "numeric",
        month: "2-digit",
        day: "2-digit",
        hour: "2-digit",
        minute: "2-digit",
        hour12: false,
      });
};
function CardStatus({
  card,
  id,
  retry,
  pending,
}: {
  card: DashboardCard;
  id: string;
  retry: () => void;
  pending: boolean;
}) {
  if (card.state === "READY") return null;
  return (
    <div
      className={styles.cardStatus}
      role={card.state === "ERROR" ? "alert" : "status"}
    >
      <Badge tone={card.state === "ERROR" ? "danger" : "warning"}>
        {card.state === "ERROR"
          ? "조회 실패"
          : card.state === "PARTIAL"
            ? "일부 자료 없음"
            : "자료 없음"}
      </Badge>
      <span>{card.message ?? card.error?.message ?? "자료 없음"}</span>
      <Button
        data-testid={`dashboard-${id}-retry`}
        small
        pending={pending}
        onClick={retry}
      >
        다시 조회
      </Button>
    </div>
  );
}
function Panel({
  title,
  id,
  card,
  retry,
  pending,
  children,
  more,
}: {
  title: string;
  id: string;
  card: DashboardCard;
  retry: () => void;
  pending: boolean;
  children: ReactNode;
  more?: ReactNode;
}) {
  return (
    <section className={styles.panel} data-testid={`dashboard-${id}-panel`}>
      <header className={styles.panelHeading}>
        <h2>{title}</h2>
        {more}
      </header>
      <CardStatus card={card} id={id} retry={retry} pending={pending} />
      {children}
    </section>
  );
}
export default function UI16Screen({
  name = "대시보드",
  groupName = "대시보드",
}: {
  name?: string;
  groupName?: string;
  onDirtyChange?: (dirty: boolean) => void;
}) {
  const location = useLocation();
  const [month, setMonth] = useState<string | undefined>();
  const [data, setData] = useState<DashboardData | null>(null);
  const [pending, setPending] = useState(true),
    [revision, setRevision] = useState(0);
  const [error, setError] = useState<ApiFailure | null>(null),
    [downloadError, setDownloadError] = useState<ApiFailure | null>(null);
  const [exporting, setExporting] = useState(false),
    [unit, setUnit] = useState(1);
  const request = useRef(0);
  const visible = useRef(true);
  const root = useRef<HTMLElement>(null);
  const retry = () => setRevision((value) => value + 1);
  useEffect(() => {
    const observer = new MutationObserver(() => {
      const active = !root.current?.closest("[hidden]");
      if (active && !visible.current) retry();
      visible.current = active;
    });
    const host = root.current?.parentElement;
    if (host)
      observer.observe(host, { attributes: true, attributeFilter: ["hidden"] });
    return () => observer.disconnect();
  }, []);
  useEffect(() => {
    if (location.pathname !== "/UI-16" && location.pathname !== "/") return;
    const serial = ++request.current,
      abort = new AbortController();
    setPending(true);
    setError(null);
    getDashboard(month, abort.signal)
      .then((result) => {
        if (
          abort.signal.aborted ||
          serial !== request.current ||
          (month !== undefined && result.basisMonth !== month)
        )
          return;
        setData(result);
      })
      .catch((reason) => {
        if (!abort.signal.aborted && serial === request.current)
          setError(failure(reason));
      })
      .finally(() => {
        if (!abort.signal.aborted && serial === request.current)
          setPending(false);
      });
    return () => abort.abort();
  }, [month, revision, location.key, location.pathname]);
  const basis = month ?? data?.basisMonth ?? seoulMonth();
  const today = data
    ? new Intl.DateTimeFormat("sv-SE", {
        timeZone: "Asia/Seoul",
        year: "numeric",
        month: "2-digit",
        day: "2-digit",
      }).format(new Date(data.asOf))
    : null;
  const current = today?.slice(0, 7) ?? seoulMonth();
  const change = (value: string) => {
    if (/^(?!0000)\d{4}-(0[1-9]|1[0-2])$/.test(value) && value <= current)
      setMonth(value);
  };
  const shift = (delta: number) => {
    const [year, m] = basis.split("-").map(Number),
      index = year * 12 + m - 1 + delta;
    change(
      `${String(Math.floor(index / 12)).padStart(4, "0")}-${String((index % 12) + 1).padStart(2, "0")}`,
    );
  };
  const money = (value: number | null) =>
    value == null
      ? "–"
      : `${(value / unit).toLocaleString("ko-KR", { maximumFractionDigits: unit === 1 ? 0 : 1 })} ${unit === 1 ? "원" : unit === 1000 ? "천 원" : "백만 원"}`;
  const download = async () => {
    if (!data || data.basisMonth !== basis) return;
    setExporting(true);
    setDownloadError(null);
    try {
      await downloadDashboard(data.basisMonth);
    } catch (e) {
      setDownloadError(failure(e));
    } finally {
      setExporting(false);
    }
  };
  const more = (id: string, target: string, targetMonth = data?.basisMonth) => (
    <Link
      data-testid={`dashboard-${id}-more`}
      className={tables.linkButton}
      to={`/${target}`}
      state={targetMonth ? { closingMonth: targetMonth } : undefined}
    >
      자세히
    </Link>
  );
  const statusProps = { retry, pending };
  return (
    <section
      ref={root}
      data-screen-id="SCR-UI-16"
      data-testid="dashboard-screen"
      className={styles.dashboard}
    >
      <ScreenHeading
        screenId="UI-16"
        name={name}
        groupName={groupName}
        actions={
          <Button
            data-testid="dashboard-export"
            disabled={!data || pending || data.basisMonth !== basis}
            pending={exporting}
            onClick={() => void download()}
          >
            엑셀 다운로드
          </Button>
        }
      />
      <div className={tables.search} aria-label="기준 바">
        <Button
          data-testid="dashboard-prev-month"
          aria-label="이전 월"
          disabled={basis <= "0001-01"}
          onClick={() => shift(-1)}
        >
          ‹ 이전 월
        </Button>
        <label>
          기준월{" "}
          <input
            data-testid="dashboard-month"
            aria-label="기준월"
            type="month"
            value={basis}
            min="0001-01"
            max={current}
            onChange={(event) => change(event.target.value)}
          />
        </label>
        <Button
          data-testid="dashboard-next-month"
          aria-label="다음 월"
          disabled={basis >= current}
          onClick={() => shift(1)}
        >
          다음 월 ›
        </Button>
        <strong>
          {basis.slice(0, 4)}년 {Number(basis.slice(5))}월 기준
        </strong>
        {data && (
          <>
            <span>오늘 {today}</span>
            <Badge tone={data.closeStatus === "CLOSED" ? "locked" : "info"}>
              {data.closeStatus === "CLOSED" ? "마감" : "작업중"}
            </Badge>
            <span>
              {data.lastClosedMonth
                ? `${data.lastClosedMonth}까지 마감`
                : "마감 이력 없음"}
            </span>
          </>
        )}
        <label>
          단위{" "}
          <select
            data-testid="dashboard-unit"
            aria-label="금액 단위"
            value={unit}
            onChange={(event) => setUnit(Number(event.target.value))}
          >
            <option value={1}>원</option>
            <option value={1000}>천 원</option>
            <option value={1000000}>백만 원</option>
          </select>
        </label>
        <Button
          data-testid="dashboard-refresh"
          pending={pending}
          onClick={retry}
        >
          새로고침
        </Button>
      </div>
      <ErrorBanner error={error} onRetry={retry} pending={pending} />
      <ErrorBanner error={downloadError} />
      {pending && !data && (
        <div className={styles.kpis}>
          {["매출", "영업이익", "누계", "가동률", "미수"].map((label) => (
            <Skeleton key={label} label={`${label} 불러오는 중`} />
          ))}
        </div>
      )}
      {data && (
        <>
          <p role="status">
            {pending || error || data.basisMonth !== basis
              ? `이전 조회 결과 · ${data.basisMonth} · `
              : ""}
            기준 시각 {asOfText(data.asOf)} · 열린 월은 조회 중 원천이 변경될 수
            있습니다.
          </p>
          <div className={styles.kpis}>
            {[
              {
                id: "revenue",
                title: "이번 달 매출",
                target: "UI-08",
                card: data.kpis.revenue,
                value: money(data.kpis.revenue.amount),
                lines: [
                  comparison(data.kpis.revenue.previous),
                  data.kpis.revenue.targetAmount === null
                    ? "목표 없음"
                    : `목표 ${money(data.kpis.revenue.targetAmount)} · ${ratio(data.kpis.revenue.achievementPercent)}`,
                  "VAT 제외",
                ],
              },
              {
                id: "profit",
                title: "이번 달 영업이익",
                target: "UI-08",
                card: data.kpis.operatingProfit,
                value: money(data.kpis.operatingProfit.amount),
                lines: [
                  `이익률 ${ratio(data.kpis.operatingProfit.marginPercent)}`,
                  comparison(data.kpis.operatingProfit.previous),
                  "VAT 제외",
                ],
              },
              {
                id: "ytd",
                title: "누계 영업이익",
                target: "UI-18",
                card: data.kpis.ytdOperatingProfit,
                value: money(data.kpis.ytdOperatingProfit.amount),
                lines: [
                  `${data.kpis.ytdOperatingProfit.fromMonth}~${data.kpis.ytdOperatingProfit.toMonth}`,
                  data.kpis.ytdOperatingProfit.targetAmount === null
                    ? "목표 없음"
                    : `목표 달성률 ${ratio(data.kpis.ytdOperatingProfit.achievementPercent)}`,
                  "VAT 제외",
                ],
              },
              {
                id: "utilization",
                title: "가동률",
                target: "UI-28",
                card: data.kpis.utilization,
                value: ratio(data.kpis.utilization.utilizationPercent),
                lines: [
                  `목표 ${ratio(data.kpis.utilization.targetPercent)} · 목표 대비 ${signed(data.kpis.utilization.targetDifferencePp, "%p")}`,
                  `대기 인원 ${data.kpis.utilization.idleDeveloperCount ?? "–"}명`,
                  `확정 ${mm(data.kpis.utilization.confirmedMm)} / 가능 ${mm(data.kpis.utilization.availableMm)} MM`,
                ],
              },
              {
                id: "receivables",
                title: "미수금",
                target: "UI-13",
                card: data.kpis.receivables,
                value: money(data.kpis.receivables.amount),
                lines: [
                  `연체 ${data.kpis.receivables.overdueCount ?? "–"}건 · ${money(data.kpis.receivables.overdueAmount)}`,
                  "오늘 현재 · VAT 포함",
                ],
              },
            ].map((item) => (
              <article
                key={item.id}
                className={styles.kpi}
                data-testid={`dashboard-${item.id}-card`}
              >
                <Link
                  data-testid={`dashboard-${item.id}-link`}
                  to={`/${item.target}`}
                  state={{
                    closingMonth:
                      item.id === "receivables" ? current : data.basisMonth,
                  }}
                  className={styles.kpiLink}
                >
                  <h2>{item.title}</h2>
                  <strong>{item.value}</strong>
                  {item.lines.map((line, index) => (
                    <span key={index}>{line}</span>
                  ))}
                </Link>
                <CardStatus card={item.card} id={item.id} {...statusProps} />
              </article>
            ))}
          </div>
          <div className={styles.main}>
            <Panel
              title="확인할 일"
              id="actions"
              card={data.actions}
              {...statusProps}
            >
              <p>
                총 {data.actions.totalKinds}건 ·{" "}
                <Badge tone="danger">급함 {data.actions.urgentKinds}건</Badge> ·
                오늘 현재
              </p>
              {data.actions.state === "READY" && !data.actions.items.length && (
                <p>확인할 일이 없습니다.</p>
              )}
              {data.actions.items.map((item) => (
                <div
                  key={item.type}
                  className={styles.action}
                  data-testid={`dashboard-action-${item.type.toLowerCase().replaceAll("_", "-")}`}
                >
                  {item.state === "UNDETERMINED" ? (
                    <>
                      <Badge tone="warning">판정 불가</Badge>
                      <span>{item.title}</span>
                      <small>{item.description}</small>
                    </>
                  ) : (
                    <Link
                      className={styles.actionLink}
                      data-testid={`dashboard-action-link-${item.type.toLowerCase().replaceAll("_", "-")}`}
                      to={`/${actionTargets[item.type] ?? "UI-16"}`}
                      state={
                        item.sourceMonth
                          ? { closingMonth: item.sourceMonth }
                          : undefined
                      }
                    >
                      <Badge
                        tone={item.severity === "URGENT" ? "danger" : "warning"}
                      >
                        {item.severity === "URGENT" ? "급함" : "주의"}
                      </Badge>
                      <span className={styles.alertText}>
                        <strong>
                          {item.title} · {item.affectedCount ?? "–"}
                          {item.unit === "PERSON"
                            ? "명"
                            : item.unit === "MONTH"
                              ? "개월"
                              : "건"}
                        </strong>
                        <small>
                          {item.description}
                          {item.sourceMonth && ` · ${item.sourceMonth}`}
                        </small>
                      </span>
                      <em className={styles.actionTarget}>
                        {screenNames[actionTargets[item.type] ?? ""] ?? "대시보드"} ›
                      </em>
                    </Link>
                  )}
                </div>
              ))}
            </Panel>
            <div className={styles.stack}>
              <Panel
                title="이번 달 마감 진행"
                id="closing"
                card={data.closeProgress}
                {...statusProps}
                more={
                  <Link
                    className={tables.linkButton}
                    data-testid="dashboard-closing-link"
                    to="/UI-14"
                    state={{ closingMonth: data.basisMonth }}
                  >
                    월 마감
                  </Link>
                }
              >
                <p>
                  {data.closeProgress.status === "CLOSED"
                    ? "마감 완료 · 9항목 점검 이력"
                    : data.closeProgress.status === "NOT_CHECKED"
                      ? "점검 전"
                      : "저장된 점검"}{" "}
                  · {data.closeProgress.completedCount ?? "–"}/
                  {data.closeProgress.totalCount}
                </p>
                {data.closeProgress.completedCount !== null && (
                  <progress
                    aria-label="마감 진행"
                    value={data.closeProgress.completedCount}
                    max={data.closeProgress.totalCount}
                  />
                )}
                {data.closeProgress.incompleteChecks.map((check) => (
                  <Link
                    data-testid={`dashboard-check-${check.code.toLowerCase().replaceAll("_", "-")}`}
                    key={check.code}
                    to="/UI-14"
                    state={{
                      closingMonth: data.basisMonth,
                      closingCheck: check.code,
                    }}
                  >
                    <Badge
                      tone={check.status === "FAIL" ? "danger" : "warning"}
                    >
                      {check.status === "FAIL" ? "차단" : "주의"}
                    </Badge>{" "}
                    {check.name} · {check.issueCount}건 · {check.description}
                  </Link>
                ))}
              </Panel>
              <Panel
                title="프로젝트 경보"
                id="alerts"
                card={data.projectAlerts}
                {...statusProps}
                more={more("alerts", "UI-04")}
              >
                {data.projectAlerts.state === "READY" &&
                  !data.projectAlerts.items.length && (
                    <p>프로젝트 경보가 없습니다.</p>
                  )}
                {data.projectAlerts.items.map((item) => (
                  <Link
                    className={styles.alertLink}
                    data-testid={`dashboard-project-${item.projectId}`}
                    key={item.projectId}
                    to="/UI-04"
                    state={{
                      projectId: item.projectId,
                      closingMonth: current,
                      costTab: "causes",
                    }}
                  >
                    <span className={styles.alertText}>
                      <strong>{item.projectName}</strong>
                      <small>
                        {item.dataState === "MISSING_COST"
                          ? "자료 부족"
                          : `마진율 ${ratio(item.marginPercent)} · 예산 소진율 ${ratio(item.budgetUsagePercent)}`}
                      </small>
                      <small>
                        주원인:{" "}
                        {item.primaryCostCause
                          ? (costCauseNames[item.primaryCostCause.name] ??
                            item.primaryCostCause.name)
                          : "분석 자료 없음"}
                      </small>
                    </span>
                    <span className={styles.alertBadges}>
                      {item.statuses.map((status) => (
                        <Badge
                          key={status}
                          tone={item.severity === "RISK" ? "danger" : "warning"}
                        >
                          {status}
                        </Badge>
                      ))}
                    </span>
                  </Link>
                ))}
              </Panel>
            </div>
          </div>
          <div className={styles.main}>
            <Panel
              title="매출·원가 추이"
              id="trend"
              card={data.revenueCostTrend}
              {...statusProps}
            >
              <p>
                {data.basisMonth.slice(0, 4)}년 · VAT 제외 · 매출 / 원가 / 목표
                매출
              </p>
              <TrendChart data={data.revenueCostTrend.months} money={money} />
              <details>
                <summary data-testid="dashboard-trend-table-toggle">
                  월별 수치 표
                </summary>
                <div className={tables.tableScroll}>
                  <table className={tables.table}>
                    <caption>매출·원가 추이 · 표시 단위 적용</caption>
                    <thead>
                      <tr>
                        <th>월</th>
                        <th>매출</th>
                        <th>원가</th>
                        <th>목표 매출</th>
                      </tr>
                    </thead>
                    <tbody>
                      {data.revenueCostTrend.months.map((point) => (
                        <tr
                          data-testid={`dashboard-trend-${point.month}`}
                          key={point.month}
                        >
                          <th>{point.month}</th>
                          <td>{money(point.revenue)}</td>
                          <td>{money(point.cost)}</td>
                          <td>{money(point.targetRevenue)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </details>
            </Panel>
            <Panel
              title="앞으로 6개월 인력 수급"
              id="workforce"
              card={data.workforceForecast}
              {...statusProps}
              more={more(
                "workforce",
                "UI-17",
                data.workforceForecast.fromMonth,
              )}
            >
              <p>오늘 기준 예측 · 단위 MM · 가용 인력 / 필요 인력</p>
              <WorkforceChart months={data.workforceForecast.months} />
              <div className={tables.tableScroll}>
                <table className={tables.table}>
                  <caption>6개월 인력 수급</caption>
                  <thead>
                    <tr>
                      <th>월</th>
                      <th>가용</th>
                      <th>필요</th>
                      <th>부족</th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.workforceForecast.months.map((point) => (
                      <tr
                        className={
                          point.isShortage ? styles.shortage : undefined
                        }
                        data-testid={`dashboard-workforce-${point.month}`}
                        key={point.month}
                      >
                        <th>{point.month}</th>
                        <td>{mm(point.availableMm)}</td>
                        <td>{mm(point.requiredMm)}</td>
                        <td>
                          {point.shortageMm != null && point.shortageMm < 0
                            ? `여유 ${mm(-point.shortageMm)}`
                            : mm(point.shortageMm)}{" "}
                          {point.isShortage && (
                            <Badge tone="danger">부족</Badge>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              {data.workforceForecast.assumptions.map((text) => (
                <small key={text}>{text}</small>
              ))}
            </Panel>
          </div>
          <div className={styles.lower}>
            <Panel
              title="영업 파트별 이번 달"
              id="parts"
              card={data.partSummary}
              {...statusProps}
              more={more("parts", "UI-19")}
            >
              <div className={tables.tableScroll}>
                <table className={tables.table}>
                  <caption>{data.basisMonth} · VAT 제외</caption>
                  <thead>
                    <tr>
                      <th>파트명</th>
                      <th>매출</th>
                      <th>{data.partSummary.marginLabel}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.partSummary.items.map((item) => (
                      <tr
                        data-testid={`dashboard-part-${item.partId ?? "unassigned"}`}
                        key={`${item.classification}-${item.partId}`}
                      >
                        <th>{item.partName}</th>
                        <td>{money(item.revenue)}</td>
                        <td>
                          {item.marginState === "MISSING_COST"
                            ? "산정 불가"
                            : ratio(item.partMarginPercent)}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </Panel>
            <Panel
              title="영업 파이프라인"
              id="pipeline"
              card={data.pipeline}
              {...statusProps}
              more={more("pipeline", "UI-29", undefined)}
            >
              <p>진행 기회 {data.pipeline.openOpportunityCount ?? "–"}건</p>
              <p>
                확률 반영 예상 매출{" "}
                {money(data.pipeline.weightedExpectedRevenue)}
              </p>
              <h3>
                3개월 내 시작 기회 · 전체{" "}
                {data.pipeline.startingOpportunityTotal ?? "–"}건
              </h3>
              {data.pipeline.state === "READY" &&
                !data.pipeline.startingOpportunities.length && (
                  <p>시작 예정 기회가 없습니다.</p>
                )}
              {data.pipeline.startingOpportunities.map((item) => (
                <div
                  className={styles.action}
                  data-testid={`dashboard-opportunity-${item.opportunityId}`}
                  key={item.opportunityId}
                >
                  <span className={styles.alertText}>
                    <strong>{item.name}</strong>
                    <small>
                      시작 {item.startDate} · 확률 {item.probabilityPercent}% ·
                      예상 {money(item.weightedExpectedAmount)}
                    </small>
                  </span>
                  <Badge>{stageNames[item.stage] ?? item.stage}</Badge>
                </div>
              ))}
            </Panel>
            <Panel
              title="자금 (앞으로 3개월)"
              id="cash"
              card={data.cashForecast}
              {...statusProps}
              more={more("cash", "UI-21", data.cashForecast.fromMonth)}
            >
              <p>오늘 기준 예측 · VAT 포함</p>
              <CashChart months={data.cashForecast.months} money={money} />
              {data.cashForecast.months.map((point) => (
                <div
                  className={styles.action}
                  data-testid={`dashboard-cash-${point.month}`}
                  key={point.month}
                >
                  <strong>{point.month}</strong>
                  <span>
                    예상 최종 잔액 {money(point.expectedFinalBalance)}
                  </span>
                  <small>
                    최소 운영자금 {money(point.minimumOperatingCash)}
                  </small>
                  {point.isShortage === null ? (
                    <Badge tone="warning">자료 부족</Badge>
                  ) : point.isShortage ? (
                    <Badge tone="danger">부족</Badge>
                  ) : (
                    <Badge tone="success">정상</Badge>
                  )}
                </div>
              ))}
            </Panel>
          </div>
          {!!data.warnings.length && (
            <details>
              <summary data-testid="dashboard-warnings-toggle">
                원천 조회 안내 {data.warnings.length}건
              </summary>
              <ul>
                {data.warnings.map((warning, index) => (
                  <li key={`${warning.section}-${index}`}>
                    {warning.message}
                    {warning.basisMonth && ` · ${warning.basisMonth}`}
                  </li>
                ))}
              </ul>
            </details>
          )}
        </>
      )}
    </section>
  );
}
function TrendChart({
  data,
  money,
}: {
  data: DashboardData["revenueCostTrend"]["months"];
  money: (value: number | null) => string;
}) {
  const values = data
    .flatMap((point) => [point.revenue, point.cost, point.targetRevenue])
    .filter((v): v is number => v !== null);
  if (!values.length) return <p>추이 자료 없음</p>;
  const min = Math.min(0, ...values),
    max = Math.max(1, ...values),
    range = max - min;
  const y = (value: number) => 165 - ((value - min) / range) * 140;
  const series = [
    { key: "revenue", label: "매출", color: "var(--primary)" },
    { key: "cost", label: "원가", color: "var(--warning)" },
    { key: "targetRevenue", label: "목표 매출", color: "var(--text-sub)" },
  ] as const;
  return (
    <svg
      className={styles.chart}
      viewBox="0 0 600 200"
      role="img"
      aria-label="월별 매출·원가·목표 매출 추이"
    >
      <line x1="20" y1={y(0)} x2="580" y2={y(0)} stroke="var(--divider)" />
      {series.map((item) => (
        <g key={item.key} stroke={item.color} fill={item.color}>
          {data.map((point, index) => {
            const value = point[item.key],
              previous = data[index - 1]?.[item.key];
            if (value === null) return null;
            const x = 24 + index * 50;
            return (
              <g
                key={point.month}
                tabIndex={0}
                data-testid={`dashboard-trend-point-${item.key === "targetRevenue" ? "target" : item.key}-${point.month}`}
                aria-label={`${point.month} ${item.label} ${money(value)}`}
              >
                <title>
                  {point.month} {item.label} {money(value)}
                </title>
                {index > 0 && previous != null && (
                  <line
                    x1={x - 50}
                    y1={y(previous)}
                    x2={x}
                    y2={y(value)}
                    strokeWidth="2"
                    strokeDasharray={
                      item.key === "targetRevenue" ? "5 4" : undefined
                    }
                  />
                )}
                <circle cx={x} cy={y(value)} r="3" />
              </g>
            );
          })}
        </g>
      ))}
      {data.map((point, index) => (
        <text
          key={point.month}
          x={24 + index * 50}
          y="190"
          textAnchor="middle"
          fontSize="11"
          fill="var(--text-sub)"
        >
          {Number(point.month.slice(5))}월
        </text>
      ))}
    </svg>
  );
}
