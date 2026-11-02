import { useCallback, useState, type FormEvent } from "react";
import {
  Badge,
  Button,
  Modal,
  Skeleton,
  SuccessToast,
} from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { ApiFailure, readData } from "../../shared/api";
import { useAdminRead, useAdminWrite } from "../admin/useAdminResource";
import { ScreenHeading } from "../shared/ScreenHeading";
import { seoulMonth } from "../shared/PeriodBar";
import tables from "../developers/Developers.module.css";
import styles from "./CashFlow.module.css";
import {
  getFinanceCashflow,
  getCashOpening,
  getFinanceOtherInflows,
  saveCashOpening,
  postFinanceOtherInflows,
  putFinanceOtherInflows,
  downloadCashflow,
  type CashInflow,
  type CashFlowMode,
  type CashFlowMonth,
} from "./client";

const money = (v: number | null | undefined, unit = 1) =>
  v == null ? "–" : Math.trunc(v / unit).toLocaleString("ko-KR");
const warnings: Record<string, string> = {
  RISK: "위험",
  CAUTION: "주의",
  MISSING_CLOSED_SNAPSHOT: "마감 잔액 자료 없음",
  BEFORE_INITIAL_MONTH: "시작 월 이전",
};
const categoryLabels: Record<string, string> = {
  BILLING: "청구·입금",
  SALARY: "인건비",
  FREELANCE: "계약직·프리랜서",
  SERVICE: "용역비",
  UNPAID: "미지급 인건비",
  EXPENSE: "제경비",
  SGA: "판관비",
  NOI: "영업외 현금 수입",
  NOE: "영업외 현금 지출",
  CORP_TAX: "법인세 납부",
  MISC: "잡수익",
  LOAN: "대여금",
  LOAN_REPAY: "대여금 상환",
  TAX: "세금",
  INSURANCE: "4대보험",
  SEVERANCE: "퇴직금·퇴직연금",
  SALES_FEE: "영업비",
  ETC: "기타 수입·지급",
};
export default function UI21Screen({
  name = "자금수지표",
  groupName = "재무·자금",
}: {
  name?: string;
  groupName?: string;
}) {
  const current = seoulMonth(),
    currentYear = Number(current.slice(0, 4));
  const [year, setYear] = useState(() =>
    Number(sessionStorage.getItem("cashflow-year") ?? currentYear),
  );
  const [mode, setMode] = useState<CashFlowMode>("BOTH"),
    [unit, setUnit] = useState(1);
  const [opening, setOpening] = useState(false),
    [inflow, setInflow] = useState<CashInflow | "new" | null>(null),
    [success, setSuccess] = useState(false);
  const read = useCallback(() => getFinanceCashflow(year, mode), [year, mode]),
    flow = useAdminRead(read);
  const readInflows = useCallback(() => getFinanceOtherInflows(), []),
    inflows = useAdminRead(readInflows);
  const readClose = useCallback(
      () =>
        readData<{ month: string; status: string }[]>(
          `/api/month-close?from=${year}-01&to=${year}-12`,
        ),
      [year],
    ),
    close = useAdminRead(readClose);
  const readOpening = useCallback(async () => {
      try {
        return await getCashOpening(current);
      } catch (e) {
        if (e instanceof ApiFailure && e.status === 404) return null;
        throw e;
      }
    }, [current]),
    initial = useAdminRead(readOpening);
  const data =
    !flow.loading && !flow.error && flow.data?.year === year ? flow.data : null;
  const closed = (date: string | null) =>
    date != null &&
    close.data?.some(
      (c) => c.month === date.slice(0, 7) && c.status === "CLOSED",
    );
  const editable = year === currentYear && !close.loading && !close.error;
  const reload = async () => {
    await Promise.all([
      flow.reload(),
      inflows.reload(),
      initial.reload(),
      close.reload(),
    ]);
  };
  const changeYear = (value: number) => {
    if (value < 1 || value > 9999) return;
    sessionStorage.setItem("cashflow-year", String(value));
    setYear(value);
  };
  const rows = (inflows.data?.items ?? []).filter((r) =>
    [r.month, r.planned_date, r.actual_date].some((d) =>
      d?.startsWith(String(year)),
    ),
  );
  const fields: [keyof CashFlowMonth, string, boolean][] = [
    ["planned_opening", "전월이월 예정", false],
    ["planned_inflow", "예정 수입", true],
    ["planned_outflow", "예정 지출", true],
    ["planned_closing", "월말 예정 잔액", false],
    ["actual_opening", "전월이월 실적", false],
    ["actual_inflow", "실적 수입", true],
    ["actual_outflow", "실적 지출", true],
    ["actual_closing", "월말 실적 잔액", false],
    ["forecast_closing", "예상 최종 잔액", false],
  ];
  return (
    <section data-testid="cashflow-screen" aria-label="자금수지표 화면">
      <ScreenHeading
        screenId="UI-21"
        name={name}
        groupName={groupName}
        actions={
          <Button
            data-testid="cashflow-download"
            disabled={!data?.export_file}
            onClick={() =>
              data?.export_file && downloadCashflow(data.export_file)
            }
          >
            엑셀 다운로드
          </Button>
        }
      />
      <div className={tables.search}>
        <Button
          data-testid="cashflow-year-previous"
          aria-label="이전 연도"
          onClick={() => changeYear(year - 1)}
        >
          ‹
        </Button>
        <strong>{year}년</strong>
        <Button
          data-testid="cashflow-year-next"
          aria-label="다음 연도"
          onClick={() => changeYear(year + 1)}
        >
          ›
        </Button>
        <label>
          표시{" "}
          <select
            data-testid="cashflow-mode"
            aria-label="표시"
            value={mode}
            onChange={(e) => setMode(e.target.value as CashFlowMode)}
          >
            <option value="BOTH">예정·실적</option>
            <option value="PLAN">예정</option>
            <option value="ACTUAL">실적</option>
          </select>
        </label>
        <Button
          data-testid="cashflow-query"
          onClick={() => void reload().catch(() => {})}
        >
          조회
        </Button>
        {year !== currentYear && (
          <span>과거·미래 연도는 조회·다운로드만 가능합니다.</span>
        )}
        {editable && (
          <>
            <Button
              data-testid="cashflow-opening-button"
              disabled={initial.loading || !!initial.error || !!initial.data}
              onClick={() => setOpening(true)}
            >
              기초 잔액 입력
            </Button>
            <Button
              data-testid="cashflow-inflow-add"
              onClick={() => setInflow("new")}
            >
              기타 수입 등록
            </Button>
          </>
        )}
      </div>
      <ErrorBanner
        error={flow.error}
        onRetry={() => void flow.reload().catch(() => {})}
      />
      <ErrorBanner
        error={close.error}
        onRetry={() => void close.reload().catch(() => {})}
      />
      <ErrorBanner error={initial.error} />
      {flow.loading && <Skeleton />}
      {data && (
        <>
          {data.first_risk_month && (
            <p className={styles.danger} role="alert">
              자금 부족 예상: {data.first_risk_month}
            </p>
          )}
          <div className={tables.card}>
            <h2>월말 잔액 흐름</h2>
            <BalanceChart months={data.months} />
          </div>
          <div className={tables.toolbar}>
            <p>
              부가세 포함 현금 · 전월이월·이월금은 합계에 중복 가산하지
              않습니다.
            </p>
            <label>
              단위{" "}
              <select
                data-testid="cashflow-unit"
                aria-label="단위"
                value={unit}
                onChange={(e) => setUnit(Number(e.target.value))}
              >
                <option value={1}>원</option>
                <option value={1000}>천 원</option>
                <option value={1000000}>백만 원</option>
              </select>
            </label>
          </div>
          <div className={tables.tableScroll}>
            <table className={tables.table} aria-label="월별 자금수지">
              <thead>
                <tr>
                  <th>항목</th>
                  {data.months.map((m) => (
                    <th key={m.month}>
                      {Number(m.month.slice(5))}월 {m.closed ? "실적" : "예정"}
                    </th>
                  ))}
                  <th>상반기</th>
                  <th>하반기</th>
                  <th>연간</th>
                </tr>
              </thead>
              <tbody>
                {fields
                  .filter(
                    ([key]) =>
                      mode === "BOTH" ||
                      key === "forecast_closing" ||
                      (mode === "PLAN"
                        ? key.startsWith("planned")
                        : key.startsWith("actual")),
                  )
                  .map(([key, label, sum]) => (
                    <tr
                      key={key}
                      data-testid={`cashflow-metric-${key.replaceAll("_", "-")}`}
                    >
                      <th scope="row">{label}</th>
                      {data.months.map((m) => (
                        <td key={m.month} className={tables.amount}>
                          {money(m[key] as number | null, unit)}
                        </td>
                      ))}
                      {[
                        [0, 6],
                        [6, 12],
                        [0, 12],
                      ].map(([start, end]) => {
                        const values = data.months
                          .filter(
                            (m) =>
                              Number(m.month.slice(5)) > start &&
                              Number(m.month.slice(5)) <= end,
                          )
                          .map((m) => m[key] as number | null);
                        return (
                          <td key={start + ":" + end} className={tables.amount}>
                            {sum &&
                            values.length &&
                            values.every((v) => v != null)
                              ? money(
                                  values.reduce<number>((a, v) => a + v!, 0),
                                  unit,
                                )
                              : "–"}
                          </td>
                        );
                      })}
                    </tr>
                  ))}
                {Array.from(
                  new Set(
                    data.months.flatMap((m) =>
                      (m.categories ?? []).map((c) => c.code),
                    ),
                  ),
                ).map((code) => (
                  <tr
                    key={code}
                    data-testid={`cashflow-category-${code.toLowerCase().replaceAll("_", "-")}`}
                  >
                    <th scope="row">{categoryLabels[code] ?? code}</th>
                    {data.months.map((m) => {
                      const c = m.categories?.find((c) => c.code === code);
                      return (
                        <td key={m.month} className={tables.amount}>
                          {mode !== "ACTUAL" && (
                            <span>
                              예정{" "}
                              {money(
                                c ? c.planned_inflow - c.planned_outflow : 0,
                                unit,
                              )}
                            </span>
                          )}
                          {mode === "BOTH" && <br />}
                          {mode !== "PLAN" && (
                            <span>
                              실적{" "}
                              {money(
                                m.actual_closing == null
                                  ? null
                                  : c
                                    ? (c.actual_inflow ?? 0) -
                                      (c.actual_outflow ?? 0)
                                    : 0,
                                unit,
                              )}
                            </span>
                          )}
                        </td>
                      );
                    })}
                    <td colSpan={3}>수입 + / 지출 −</td>
                  </tr>
                ))}
                <tr data-testid="cashflow-warning-row">
                  <th scope="row">상태</th>
                  {data.months.map((m) => (
                    <td key={m.month}>
                      {m.warning ? (
                        <Badge
                          tone={
                            m.warning === "RISK"
                              ? "danger"
                              : m.warning === "CAUTION"
                                ? "warning"
                                : "neutral"
                          }
                        >
                          {warnings[m.warning] ?? m.warning}
                        </Badge>
                      ) : (
                        <Badge tone={m.closed ? "locked" : "info"}>
                          {m.closed ? "마감" : "작업중"}
                        </Badge>
                      )}
                    </td>
                  ))}
                  <td colSpan={3} />
                </tr>
              </tbody>
            </table>
          </div>
          <nav className={tables.actions} aria-label="자금 원천">
            <a data-testid="cashflow-receipts-link" href="/UI-13">
              입금 상세
            </a>
            <a data-testid="cashflow-payments-link" href="/UI-20">
              지급 상세·대여금 상환
            </a>
            <a data-testid="cashflow-entries-link" href="/UI-10">
              재무 항목·법인세
            </a>
          </nav>
        </>
      )}
      <h2>기타 수입·대여금</h2>
      <p>대여금 상환은 지급 관리에서 LOAN_REPAY 유형으로 등록합니다.</p>
      <ErrorBanner
        error={inflows.error}
        onRetry={() => void inflows.reload().catch(() => {})}
      />
      {inflows.loading ? (
        <Skeleton />
      ) : (
        !inflows.error && (
          <div className={tables.tableScroll}>
            <table className={tables.table} aria-label="기타 수입">
              <thead>
                <tr>
                  {[
                    "구분",
                    "항목",
                    "금액",
                    "예정일",
                    "입금일",
                    "비고",
                    "작업",
                  ].map((c) => (
                    <th key={c}>{c}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {rows.length === 0 && (
                  <tr>
                    <td colSpan={7} className={tables.empty}>
                      등록된 기타 수입이 없습니다.
                    </td>
                  </tr>
                )}
                {rows.map((r) => (
                  <tr
                    key={r.inflw_id}
                    data-testid={`cashflow-inflow-row-${r.inflw_id}`}
                  >
                    <td>
                      {r.inflw_se_cd === "LOAN"
                        ? "대여금"
                        : r.inflw_se_cd === "MISC"
                          ? "잡수익"
                          : "기타"}
                    </td>
                    <td>{r.inflw_item_nm}</td>
                    <td className={tables.amount}>{money(r.amount)}</td>
                    <td>{r.planned_date ?? "–"}</td>
                    <td>{r.actual_date ?? "–"}</td>
                    <td>{r.remark}</td>
                    <td>
                      {editable &&
                      !closed(r.planned_date) &&
                      !closed(r.actual_date) ? (
                        <Button
                          data-testid={`cashflow-inflow-edit-${r.inflw_id}`}
                          onClick={() => setInflow(r)}
                        >
                          수정
                        </Button>
                      ) : (
                        <Badge tone="locked">읽기 전용</Badge>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )
      )}
      {opening && (
        <OpeningDialog
          month={current}
          closed={closed}
          onCancel={() => setOpening(false)}
          onRefresh={reload}
          onSuccess={() => {
            setOpening(false);
            setSuccess(true);
          }}
        />
      )}
      {inflow && (
        <InflowDialog
          input={inflow === "new" ? null : inflow}
          month={current}
          closed={closed}
          onCancel={() => setInflow(null)}
          onRefresh={reload}
          onSuccess={() => {
            setInflow(null);
            setSuccess(true);
          }}
        />
      )}
      {success && (
        <SuccessToast
          message="저장했습니다."
          onDismiss={() => setSuccess(false)}
        />
      )}
    </section>
  );
}
function BalanceChart({ months }: { months: CashFlowMonth[] }) {
  const values = months
    .flatMap((m) => [m.planned_closing, m.actual_closing, m.forecast_closing])
    .filter((v): v is number => v != null);
  if (!values.length)
    return <p className={tables.empty}>표시할 잔액 자료가 없습니다.</p>;
  const min = Math.min(0, ...values),
    max = Math.max(1, ...values),
    y = (v: number) => 260 - ((v - min) / (max - min)) * 230;
  return (
    <>
      <svg
        viewBox="0 0 960 310"
        role="img"
        aria-label="예정·실적·예상 최종 잔액 차트"
        className={styles.chart}
      >
        <line x1="30" x2="930" y1={y(0)} y2={y(0)} stroke="var(--divider)" />
        {(
          ["planned_closing", "actual_closing", "forecast_closing"] as const
        ).map((key, i) => (
          <g key={key}>
            {months.map((m, n) => {
              const value = m[key],
                previous = months[n - 1]?.[key];
              return value == null ? null : (
                <g key={m.month}>
                  <title>
                    {m.month}: {money(value)}원
                  </title>
                  {previous != null && (
                    <line
                      x1={30 + (n - 1) * 80}
                      x2={30 + n * 80}
                      y1={y(previous)}
                      y2={y(value)}
                      className={styles[`series${i}`]}
                    />
                  )}
                  <circle
                    cx={30 + n * 80}
                    cy={y(value)}
                    r="3"
                    className={styles[`series${i}`]}
                  />
                </g>
              );
            })}
          </g>
        ))}
        {months.map((m, n) => (
          <text key={m.month} x={30 + n * 80} y="295" textAnchor="middle">
            {Number(m.month.slice(5))}월
          </text>
        ))}
      </svg>
      <p className={styles.legend}>
        <span>예정 잔액</span>
        <span>실적 잔액</span>
        <span>예상 최종</span>
      </p>
    </>
  );
}
function OpeningDialog({
  month,
  closed,
  onCancel,
  onRefresh,
  onSuccess,
}: {
  month: string;
  closed: (date: string | null) => boolean | undefined;
  onCancel: () => void;
  onRefresh: () => Promise<unknown>;
  onSuccess: () => void;
}) {
  const [date, setDate] = useState(month),
    [amount, setAmount] = useState(""),
    [invalid, setInvalid] = useState(false);
  const write = useAdminWrite(onRefresh);
  const save = (e: FormEvent) => {
    e.preventDefault();
    if (
      !/^\d+$/.test(amount) ||
      !Number.isSafeInteger(Number(amount)) ||
      !date
    ) {
      setInvalid(true);
      return;
    }
    void write.save(
      () => saveCashOpening({ month: date, amount: Number(amount) }),
      onSuccess,
    );
  };
  return (
    <Modal title="기초 잔액 입력" onCancel={onCancel}>
      <form
        className={tables.form}
        onSubmit={save}
        data-testid="cashflow-opening-dialog"
      >
        <p>자금 시작 월의 기초 잔액은 전체 기간에 최초 한 번만 입력합니다.</p>
        <label>
          시작 월
          <input
            data-testid="cashflow-opening-month"
            aria-label="시작 월"
            type="month"
            value={date}
            onChange={(e) => setDate(e.target.value)}
          />
        </label>
        <label>
          기초 잔액
          <input
            data-testid="cashflow-opening-amount"
            aria-label="기초 잔액"
            inputMode="numeric"
            value={amount}
            onChange={(e) => setAmount(e.target.value)}
          />
        </label>
        {invalid && <p role="alert">0 이상 원 단위 정수를 입력하세요.</p>}
        {closed(date) && <p>마감된 달은 입력할 수 없습니다.</p>}
        <ErrorBanner error={write.error} />
        <div className={tables.actions}>
          <Button
            data-testid="cashflow-opening-cancel"
            type="button"
            onClick={onCancel}
            disabled={write.pending}
          >
            취소
          </Button>
          <Button
            data-testid="cashflow-opening-save"
            type="submit"
            variant="primary"
            pending={write.pending}
            disabled={closed(date) || write.uncertain}
          >
            저장
          </Button>
        </div>
      </form>
    </Modal>
  );
}
function InflowDialog({
  input,
  month,
  closed,
  onCancel,
  onRefresh,
  onSuccess,
}: {
  input: CashInflow | null;
  month: string;
  closed: (date: string | null) => boolean | undefined;
  onCancel: () => void;
  onRefresh: () => Promise<unknown>;
  onSuccess: () => void;
}) {
  const [name, setName] = useState(input?.inflw_item_nm ?? ""),
    [amount, setAmount] = useState(input ? String(input.amount) : ""),
    [code, setCode] = useState<CashInflow["inflw_se_cd"]>(
      input?.inflw_se_cd ?? "MISC",
    );
  const [plan, setPlan] = useState(input?.planned_date ?? ""),
    [actual, setActual] = useState(input?.actual_date ?? ""),
    [remark, setRemark] = useState(input?.remark ?? ""),
    [invalid, setInvalid] = useState(false);
  const write = useAdminWrite(onRefresh),
    locked = closed(plan) || closed(actual);
  const save = (e: FormEvent) => {
    e.preventDefault();
    if (
      !name.trim() ||
      name.length > 100 ||
      !/^\d+$/.test(amount) ||
      !Number.isSafeInteger(Number(amount)) ||
      (!plan && !actual) ||
      remark.length > 500
    ) {
      setInvalid(true);
      return;
    }
    const payload: CashInflow = {
      inflw_id: input?.inflw_id,
      month: (plan || actual || month).slice(0, 7),
      amount: Number(amount),
      inflw_se_cd: code,
      inflw_item_nm: name,
      planned_date: plan || null,
      actual_date: actual || null,
      remark: remark || null,
    };
    void write.save(
      () =>
        input
          ? putFinanceOtherInflows(payload)
          : postFinanceOtherInflows(payload),
      onSuccess,
    );
  };
  return (
    <Modal
      title={input ? "기타 수입 수정" : "기타 수입 등록"}
      onCancel={onCancel}
    >
      <form
        data-testid="cashflow-inflow-dialog"
        className={tables.form}
        onSubmit={save}
      >
        <label>
          구분
          <select
            data-testid="cashflow-inflow-code"
            aria-label="구분"
            value={code}
            onChange={(e) =>
              setCode(e.target.value as CashInflow["inflw_se_cd"])
            }
          >
            <option value="MISC">잡수익</option>
            <option value="LOAN">대여금</option>
            <option value="ETC">기타</option>
          </select>
        </label>
        <label>
          항목
          <input
            data-testid="cashflow-inflow-name"
            aria-label="항목"
            maxLength={100}
            value={name}
            onChange={(e) => setName(e.target.value)}
          />
        </label>
        <label>
          금액
          <input
            data-testid="cashflow-inflow-amount"
            aria-label="금액"
            inputMode="numeric"
            value={amount}
            onChange={(e) => setAmount(e.target.value)}
          />
        </label>
        <label>
          예정일
          <input
            data-testid="cashflow-inflow-plan"
            aria-label="예정일"
            type="date"
            value={plan}
            onChange={(e) => setPlan(e.target.value)}
          />
        </label>
        <label>
          입금일
          <input
            data-testid="cashflow-inflow-actual"
            aria-label="입금일"
            type="date"
            value={actual}
            onChange={(e) => setActual(e.target.value)}
          />
        </label>
        <label>
          비고
          <textarea
            data-testid="cashflow-inflow-remark"
            aria-label="비고"
            maxLength={500}
            value={remark}
            onChange={(e) => setRemark(e.target.value)}
          />
        </label>
        {invalid && (
          <p role="alert">
            항목·0 이상 원 단위 금액·예정일 또는 입금일을 확인하세요.
          </p>
        )}
        {locked && <p>마감된 달은 변경할 수 없습니다.</p>}
        <ErrorBanner error={write.error} />
        <div className={tables.actions}>
          <Button
            data-testid="cashflow-inflow-cancel"
            type="button"
            onClick={onCancel}
            disabled={write.pending}
          >
            취소
          </Button>
          <Button
            data-testid="cashflow-inflow-save"
            type="submit"
            variant="primary"
            pending={write.pending}
            disabled={locked || write.uncertain}
          >
            저장
          </Button>
        </div>
      </form>
    </Modal>
  );
}
