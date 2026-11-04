import { useEffect, useRef, useState } from "react";
import { Link } from "react-router-dom";
import { Badge, Button } from "../../shared/Controls";
import { ApiFailure } from "../../shared/api";
import { isScreenId } from "../shared/registry";
import { ScreenHeading } from "../shared/ScreenHeading";
import * as client from "./client";
import type {
  Answer,
  Capabilities,
  Conversation,
  Messages,
  Section,
  StoredMessage,
  StructuredQuery,
} from "./client";
import styles from "./Analysis.module.css";

const statusText = {
  OK: "분석 완료",
  PARTIAL: "일부 영역 조회 실패",
  DATA_UNAVAILABLE: "자료 부족",
  MODEL_UNAVAILABLE: "모델 연결불가 · 정형 분석을 이용하세요",
  CLARIFICATION_REQUIRED: "질문 조건 확인 필요",
  UNSUPPORTED: "지원하지 않는 작업",
};
const fieldLabels: Record<string, string> = {
  projectId: "프로젝트 ID",
  partId: "파트",
  partName: "파트",
  devId: "개발자",
  customerName: "고객",
  count: "건수",
  amount: "금액",
  projectName: "프로젝트",
  month: "월",
  revenue: "매출",
  cost: "원가",
  profit: "이익",
  marginPercent: "이익률(%)",
  operatingProfit: "영업이익",
  closedStatus: "마감 상태",
  revenueTarget: "매출 목표",
  operatingProfitTarget: "영업이익 목표",
  balance: "미수 잔액",
  overdueDays: "연체 일수",
  dueDate: "실제 납기",
  customerId: "고객 ID",
  supplyMm: "공급 MM",
  demandMm: "수요 MM",
  confirmedMm: "확정 MM",
  activeTemporaryMm: "가배정 MM",
  idleMm: "대기 MM",
  utilizationPercent: "가동률(%)",
  virtualCost: "가상 원가",
  virtualProfit: "가상 이익",
  virtualMarginPercent: "가상 이익률(%)",
  baseCost: "기준 원가",
  costChangePercent: "원가 변화(%)",
  low: "낮음",
  base: "기본",
  high: "높음",
  sourceService: "원천 서비스",
  jobCode: "직무",
  gradeCode: "등급",
  openingBalance: "기초 잔액",
  inflow: "입금",
  outflow: "출금",
  shortfall: "부족액",
  trafficLight: "신호등",
  updateGapDays: "업데이트 공백(일)",
  delayedIssueCount: "지연 이슈",
  finalRevenue: "최종 매출",
  accumulatedCost: "누적 원가",
  remainingCost: "잔여 원가",
  marginBasePercent: "최종 마진(%)",
  riskDirection: "부족 위험",
  basisDate: "기준일",
  rangeUnavailableReason: "범위 산출 제약",
};
// 분석 종류·상태 코드는 화면에 그대로 내보내지 않는다.
const intentLabels: Record<string, string> = {
  PROJECT_PROFIT_RANKING: "프로젝트 손익 순위",
  PROJECT_PROFIT_TREND: "프로젝트 손익 추이",
  MONTHLY_FINANCE: "월 재무 현황",
  RECEIVABLES_RISK: "미수 위험",
  PROJECT_PROGRESS_RISK: "프로젝트 진척 위험",
  WORKFORCE_CAPACITY: "인력 수급",
  EXIT_AND_NEXT_POSITION: "철수 예정자 다음 자리",
  SALES_PIPELINE_FORECAST: "영업 파이프라인 매출 예측",
  CASH_FORECAST: "자금 흐름",
  CLOSE_STATUS: "마감 상태",
  WHAT_IF_WORKFORCE: "인력 가정 시뮬레이션",
  WHAT_IF_PROJECT_COST: "원가 가정 시뮬레이션",
  FORECAST_PROFIT: "손익 예측",
  FORECAST_STAFF: "인력 예측",
  FORECAST_CASH: "현금 예측",
  FORECAST_RECEIVABLES: "미수 예측",
  FORECAST_TURNKEY: "턴키 예상 최종 손익",
};
const codeText = (t: string) =>
  intentLabels[t] ?? statusText[t as keyof typeof statusText] ?? t;
/** 서버 답변 문장(`2026-11 · PROJECT_PROFIT_RANKING · OK` / `revenue: 122500000`)을 사람이 읽는 말로. */
function humanize(text: string, skip: readonly string[] = []) {
  return text
    .split("\n")
    .flatMap((line) => {
      const kv = /^([A-Za-z]+): (.*)$/.exec(line);
      if (kv) {
        if (skip.includes(kv[1])) return [];
        const n = Number(kv[2]);
        return [
          `${fieldLabels[kv[1]] ?? kv[1]}: ${kv[2] !== "" && !Number.isNaN(n) ? display(n, kv[1]) : kv[2]}`,
        ];
      }
      return [line.split(" · ").map(codeText).join(" · ")];
    })
    .join("\n");
}
const shortTime = (value: string) =>
  value ? value.replace("T", " ").slice(5, 16) : "";
function display(value: unknown, key = ""): string {
  if (value === null || value === undefined)
    return /Percent|Rate|margin|utilization/i.test(key) ? "–" : "자료 없음";
  if (typeof value === "number")
    return value.toLocaleString("ko-KR", {
      maximumFractionDigits: 2,
      minimumFractionDigits: /Mm|Percent/.test(key) ? 2 : 0,
    });
  if (value === "CLOSED") return "확정";
  if (value === "OPEN") return "잠정";
  if (typeof value === "boolean") return value ? "예" : "아니오";
  return typeof value === "object" ? JSON.stringify(value) : String(value);
}
function errorText(error: unknown) {
  return error instanceof Error ? error.message : "요청에 실패했습니다.";
}
function ResultTable({ section, index }: { section: Section; index: number }) {
  const fields = [...new Set(section.rows.flatMap(Object.keys))];
  return (
    <section data-testid={`analysis-section-${index}`} className={styles.card}>
      <h3>{codeText(section.analysisType)}</h3>
      <p>
        {section.period.fromYm} ~ {section.period.toYm} · 기준일{" "}
        {section.period.basisDate}
        {section.unit && !/^[A-Z_]+$/.test(section.unit) ? ` · ${section.unit}` : ""}{" "}
        <Badge tone={section.closedStatus === "CLOSED" ? "locked" : "warning"}>
          {display(section.closedStatus)}
        </Badge>
      </p>
      {section.rows.length === 0 ? (
        <div className={styles.empty}>
          조회된 자료가 없습니다. 0으로 대체하지 않습니다.
        </div>
      ) : (
        <div className={styles.scroll}>
          <table>
            <thead>
              <tr>
                {fields.map((f) => (
                  <th
                    data-testid={`analysis-header-${index}-${fields.indexOf(f)}`}
                    key={f}
                  >
                    {fieldLabels[f] ?? f}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {section.rows.map((row, r) => (
                <tr
                  key={`${String(row.projectId ?? row.devId ?? row.month ?? r)}-${r}`}
                  data-testid={`analysis-row-${index}-${r}`}
                >
                  {fields.map((f) => (
                    <td
                      data-testid={`analysis-cell-${index}-${r}-${fields.indexOf(f)}`}
                      key={f}
                    >
                      {display(row[f], f)}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {section.chartSpec.type !== "TABLE" &&
        section.chartSpec.yFields.length > 0 && (
          <figure aria-label="동일 서버 데이터 차트" className={styles.chart}>
            <figcaption>
              표와 동일한 수치 ·{" "}
              {section.chartSpec.yFields
                .map((f) => fieldLabels[f] ?? f)
                .join(" / ")}
            </figcaption>
            <div className={styles.scroll}>
              <svg
                viewBox="0 0 640 180"
                role="img"
                aria-label={codeText(section.analysisType)}
              >
                {section.chartSpec.yFields.map((field, series) => {
                  const all = section.rows
                    .flatMap((row) =>
                      section.chartSpec.yFields.map((k) => row[k]),
                    )
                    .filter((n): n is number => typeof n === "number");
                  const max = Math.max(1, ...all.map(Math.abs));
                  const points = section.rows.map((row, i) =>
                    typeof row[field] === "number"
                      ? `${30 + (i * 560) / Math.max(1, section.rows.length - 1)},${90 - (Number(row[field]) / max) * 65}`
                      : null,
                  );
                  return (
                    <g
                      data-testid={`analysis-chart-series-${index}-${series}`}
                      key={field}
                      className={styles[`series${series % 3}`]}
                    >
                      {points.map(
                        (point, i) =>
                          point && (
                            <g
                              data-testid={`analysis-chart-point-${index}-${series}-${i}`}
                              key={i}
                            >
                              <circle
                                cx={point.split(",")[0]}
                                cy={point.split(",")[1]}
                                r="4"
                              >
                                <title>
                                  {fieldLabels[field] ?? field}:{" "}
                                  {display(section.rows[i][field], field)}
                                </title>
                              </circle>
                              {i > 0 && points[i - 1] && (
                                <line
                                  x1={points[i - 1]!.split(",")[0]}
                                  y1={points[i - 1]!.split(",")[1]}
                                  x2={point.split(",")[0]}
                                  y2={point.split(",")[1]}
                                />
                              )}
                            </g>
                          ),
                      )}
                    </g>
                  );
                })}
                <line x1="20" x2="620" y1="90" y2="90" stroke="var(--border)" />
              </svg>
            </div>
          </figure>
        )}
      {section.evaluations?.map((evaluation, i) => (
        <p key={i} data-testid={`analysis-evaluation-${index}-${i}`}>
          {Object.entries(evaluation)
            .map(([k, v]) => `${k}: ${display(v, k)}`)
            .join(" · ")}
        </p>
      ))}
    </section>
  );
}
export default function UI26Screen({
  onDirtyChange,
}: { onDirtyChange?: (dirty: boolean) => void } = {}) {
  const [capabilities, setCapabilities] = useState<Capabilities | null>(null);
  const [conversations, setConversations] = useState<Conversation[]>([]),
    [total, setTotal] = useState(0),
    [page, setPage] = useState(0);
  const [detail, setDetail] = useState<Messages | null>(null),
    [active, setActive] = useState<number | null>(null);
  const [question, setQuestion] = useState(""),
    [baseYm, setBaseYm] = useState(""),
    [answer, setAnswer] = useState<Answer | null>(null);
  const [error, setError] = useState(""),
    [fieldError, setFieldError] = useState<string | null>(null),
    [pending, setPending] = useState(false),
    [loading, setLoading] = useState(true);
  const [collapsed, setCollapsed] = useState(true),
    [structured, setStructured] = useState(false),
    [query, setQuery] = useState<StructuredQuery>({
      intent: "",
      limit: 5,
      sort: "PROFIT_DESC",
      periodType: "MONTH",
    });
  const [filterText, setFilterText] = useState<Record<string, string>>({});
  const [deleteTarget, setDeleteTarget] = useState<number | null>(null),
    [leave, setLeave] = useState<(() => void) | null>(null);
  const [feedbackTarget, setFeedbackTarget] = useState<number | null>(null),
    [rating, setRating] = useState<"HELPFUL" | "INACCURATE" | "">(""),
    [reason, setReason] = useState("");
  const [downloadStatus, setDownloadStatus] = useState("");
  const [answerQuery, setAnswerQuery] = useState<StructuredQuery | undefined>();
  const threadRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const el = threadRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [detail, answer, pending]);
  const abort = useRef<AbortController | null>(null),
    questionRef = useRef<HTMLTextAreaElement>(null),
    version = useRef(0);
  const dirtyCallback = useRef(onDirtyChange);
  dirtyCallback.current = onDirtyChange;
  useEffect(() => {
    onDirtyChange?.(!!question.trim() || !!reason.trim());
  }, [question, reason, onDirtyChange]);
  useEffect(() => () => dirtyCallback.current?.(false), []);
  const capability = capabilities?.intents.find(
    (c) => c.intent === query.intent,
  );
  async function refreshList(selectedPage = page) {
    const data = await client.listAnalysisConversations(selectedPage);
    setConversations(data.items);
    setTotal(data.total);
  }
  async function load() {
    setLoading(true);
    setError("");
    try {
      const [caps, list] = await Promise.all([
        client.getAnalysisCapabilities(),
        client.listAnalysisConversations(),
      ]);
      setCapabilities(caps);
      setConversations(list.items);
      setTotal(list.total);
      setQuery((q) => ({
        ...q,
        intent: q.intent || caps.intents.find((c) => c.available)?.intent || "",
      }));
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }
  useEffect(() => {
    void load();
    return () => {
      version.current++;
      abort.current?.abort();
    };
  }, []);
  function failed(e: unknown) {
    setError(errorText(e));
    setFieldError(e instanceof ApiFailure ? e.field : null);
  }
  async function open(id: number) {
    const token = ++version.current;
    setError("");
    try {
      const data = await client.getAnalysisConversation(id);
      if (version.current !== token) return;
      setActive(id);
      setDetail(data);
      setAnswer(null);
      setAnswerQuery(undefined);
      setFeedbackTarget(null);
      setReason("");
      setDownloadStatus("");
    } catch (e) {
      if (version.current === token) failed(e);
    }
  }
  async function create() {
    setError("");
    try {
      const created = await client.createAnalysisConversation();
      await refreshList();
      await open(created.id);
      setQuestion("");
      setReason("");
    } catch (e) {
      failed(e);
    }
  }
  function navigate(action: () => void) {
    if (question.trim() || reason.trim()) setLeave(() => action);
    else action();
  }
  async function send() {
    if (pending) return;
    if (!question.trim() || question.length > 1000) {
      setError("질문은 1~1000자로 입력하세요.");
      setFieldError("question");
      questionRef.current?.focus();
      return;
    }
    if (baseYm && !/^\d{4}-(0[1-9]|1[0-2])$/.test(baseYm)) {
      setError("기준월은 YYYY-MM 형식입니다.");
      setFieldError("baseYm");
      return;
    }
    const controller = new AbortController();
    abort.current = controller;
    const token = ++version.current;
    setPending(true);
    setError("");
    setFieldError(null);
    try {
      let id = active;
      if (id === null) {
        const created = await client.createAnalysisConversation();
        id = created.id;
        if (controller.signal.aborted || version.current !== token) return;
        const history = await client.getAnalysisConversation(id);
        if (controller.signal.aborted || version.current !== token) return;
        setActive(id);
        setDetail(history);
        await refreshList();
      }
      if (controller.signal.aborted || version.current !== token) return;
      const context =
        detail && detail.items.length === detail.total
          ? detail.items
              .filter((m) => m.status === "OK" || m.status === "PARTIAL")
              .slice(-1)[0]?.messageId
          : undefined;
      const response = await client.createAnalysisMessage(
        id,
        {
          question,
          ...(baseYm ? { baseYm } : {}),
          ...(context ? { contextMessageId: context } : {}),
        },
        controller.signal,
      );
      if (controller.signal.aborted || version.current !== token) return;
      const history = await client.getAnalysisConversation(id);
      if (controller.signal.aborted || version.current !== token) return;
      setAnswer(response);
      setAnswerQuery(undefined);
      setDownloadStatus("");
      setFeedbackTarget(null);
      setReason("");
      setQuestion("");
      setDetail(history);
      await refreshList();
    } catch (e) {
      if (!controller.signal.aborted) failed(e);
    } finally {
      if (version.current === token) setPending(false);
    }
  }
  function cancel() {
    const token = ++version.current;
    abort.current?.abort();
    setPending(false);
    setError(
      "요청을 중단했습니다. 초안은 유지됩니다. 완료 경합 여부는 대화 이력에서 확인하세요.",
    );
    if (active !== null)
      void client
        .getAnalysisConversation(active)
        .then((data) => {
          if (version.current === token) setDetail(data);
        })
        .catch((e) => {
          if (version.current === token) failed(e);
        });
  }
  function editConditions(value?: StructuredQuery) {
    setStructured(true);
    if (value) {
      setQuery({ ...value });
      setFilterText(
        Object.fromEntries(
          Object.entries(value.filters ?? {}).map(([k, v]) => [k, v.join(",")]),
        ),
      );
    }
  }
  async function analyze() {
    if (pending) return;
    setError("");
    setFieldError(null);
    let token: number | undefined;
    let controller: AbortController | undefined;
    try {
      if (!query.intent || !capability?.available)
        throw new Error(
          capability?.unavailableReason ?? "분석 의도를 선택하세요.",
        );
      if (query.limit === undefined || query.limit < 1 || query.limit > 20)
        throw new Error("건수는 1~20입니다.");
      const filters: Record<string, number[] | string[]> = {};
      let ids = 0;
      for (const key of capability.allowedFilters) {
        const values = (filterText[key] ?? "")
          .split(",")
          .map((v) => v.trim())
          .filter(Boolean);
        if (values.length > 20) throw new Error("필터는 최대 20개입니다.");
        if (key.endsWith("Ids")) {
          if (values.some((v) => !/^[1-9][0-9]*$/.test(v)))
            throw new Error("양의 정수 ID를 입력하세요.");
          ids += values.length;
          filters[key] = values.map(Number);
        } else filters[key] = values;
      }
      if (ids > 20) throw new Error("전체 ID는 최대 20개입니다.");
      const body = { ...query, ...(baseYm ? { baseYm } : {}), filters };
      setPending(true);
      controller = new AbortController();
      abort.current = controller;
      token = ++version.current;
      const response = await client.createStructuredAnalysis(
        body,
        controller.signal,
      );
      if (controller.signal.aborted || version.current !== token) return;
      setAnswer(response);
      setAnswerQuery({ ...body, ...response.interpretedQuery });
      setQuery(body);
      setDownloadStatus("");
      setFeedbackTarget(null);
      setReason("");
    } catch (e) {
      if (!controller?.signal.aborted) failed(e);
    } finally {
      if (token !== undefined && version.current === token) setPending(false);
    }
  }
  async function feedback() {
    if (active === null || feedbackTarget === null || !rating) {
      setError("평가를 선택하세요.");
      return;
    }
    if (reason.length > 500) {
      setError("평가 사유는 500자 이하입니다.");
      setFieldError("reason");
      return;
    }
    try {
      await client.saveAnalysisFeedback(active, feedbackTarget, {
        rating,
        reason,
      });
      setDetail(await client.getAnalysisConversation(active));
      setFeedbackTarget(null);
      setReason("");
      setError("");
    } catch (e) {
      failed(e);
    }
  }
  async function download() {
    if (!answer) return;
    setDownloadStatus("");
    try {
      await client.exportAnalysis(
        answer.messageId ? active : null,
        answer,
        answerQuery,
      );
      setDownloadStatus(
        "현재 원천을 재조회한 Excel을 다운로드했습니다. 과거 답변과 다를 수 있습니다.",
      );
    } catch (e) {
      setDownloadStatus(errorText(e));
    }
  }
  function answerCard(
    caption?: string | null,
    rated?: StoredMessage["feedback"],
  ) {
    if (!answer) return null;
    return (
          <article data-testid="analysis-answer" className={`${styles.bot} ${styles.answer}`}>
            <div className={styles.answerHead}>
              <Badge tone={answer.status === "OK" ? "success" : "warning"}>
                {statusText[answer.status]}
              </Badge>
              {answer.modelUsed && (
                <span className={styles.hint}>AI가 질문을 해석함</span>
              )}
            </div>
            {caption && <p className={styles.hint}>{humanize(caption)}</p>}
            {rated && (
              <p className={styles.hint}>
                최신 평가: {rated.rating} {rated.reason ?? ""}
              </p>
            )}
            <p className={styles.answerText}>
              {humanize(answer.answer, Object.keys(answer.metrics))
                .split("\n")
                .filter((line, i) => !(caption && i === 0 && /^\d{4}-\d{2} · /.test(line)))
                .join("\n")}
            </p>
            <dl className={styles.metrics}>
              {Object.entries(answer.metrics).map(([key, value]) => (
                <div
                  data-testid={`analysis-metric-${Object.keys(answer.metrics).indexOf(key)}`}
                  key={key}
                >
                  <dt>{fieldLabels[key] ?? key}</dt>
                  <dd>{display(value, key)}</dd>
                </div>
              ))}
            </dl>
            {answer.table.map((section, i) => (
              <ResultTable
                key={`${section.analysisType}-${i}`}
                section={section}
                index={i}
              />
            ))}
            <h3>근거</h3>
            {answer.sourceRefs.map((source, i) => (
              <p data-testid={`analysis-source-ref-${i}`} key={i}>
                {source.sourceService} · {source.baseYm} ·{" "}
                {display(source.closedStatus)} · {shortTime(source.generatedAt)} 기준{" "}
                {source.sourceScreenIds.filter(isScreenId).map((id) => (
                  <Link
                    key={id}
                    data-testid={`analysis-source-${i}-${id.toLowerCase()}`}
                    to={`/${id}`}
                  >
                    원본 화면 {id}
                  </Link>
                ))}
              </p>
            ))}
            {answer.assumptions.map((v, i) => (
              <p
                data-testid={`analysis-answer-assumption-${i}`}
                key={`a-${i}`}
              >
                가정: {v}
              </p>
            ))}
            {answer.warnings.map((v, i) => (
              <p
                data-testid={`analysis-warning-${i}`}
                key={`w-${i}`}
                className={styles.notice}
              >
                {v}
              </p>
            ))}
            {answer.areaErrors.map((v, i) => (
              <p
                data-testid={`analysis-area-error-${i}`}
                key={i}
                role="alert"
              >
                {codeText(v.analysisType)}: {v.message}
              </p>
            ))}
            {answer.clarification && (
              <p role="status">{answer.clarification}</p>
            )}
            <div className={styles.answerActions}>
            <Button
              data-testid="analysis-edit-conditions"
              onClick={() => editConditions(answer.interpretedQuery)}
            >
              조건 수정
            </Button>
            <Button
              data-testid="analysis-excel"
              onClick={() => void download()}
            >
              Excel
            </Button>
            {answer.messageId && (
              <Button
                data-testid="analysis-feedback-open"
                onClick={() => {
                  setFeedbackTarget(answer.messageId!);
                  setRating("");
                  setReason("");
                }}
              >
                평가
              </Button>
            )}
            </div>
            <p role="status">{downloadStatus}</p>
          </article>
    );
  }
  function summary(message: StoredMessage) {
    const full = answer && answer.messageId === message.messageId;
    return (
      <div
        key={message.messageId}
        className={styles.pair}
        data-testid={`analysis-message-${message.messageId}`}
      >
        <p className={styles.user}>{message.questionSummary}</p>
        {full ? (
          answerCard(message.answerSummary, message.feedback)
        ) : (
          <article className={styles.bot}>
            <p className={styles.botText}>
              {message.answerSummary && humanize(message.answerSummary)}
            </p>
            <small className={styles.hint}>
              요약 이력입니다. 원본 표는 저장하지 않습니다. 조건 재조회는 현재
              값입니다.
            </small>
            {message.feedback && (
              <p className={styles.hint}>
                최신 평가: {message.feedback.rating}{" "}
                {message.feedback.reason ?? ""}
              </p>
            )}
            <div className={styles.answerActions}>
              <Button
                data-testid={`analysis-history-feedback-${message.messageId}`}
                onClick={() => {
                  setFeedbackTarget(message.messageId);
                  setRating(message.feedback?.rating ?? "");
                  setReason("");
                }}
              >
                평가
              </Button>
              {message.criteria && (
                <Button
                  data-testid={`analysis-requery-${message.messageId}`}
                  onClick={() => editConditions(message.criteria)}
                >
                  조건 재조회
                </Button>
              )}
            </div>
          </article>
        )}
      </div>
    );
  }
  const thinking = pending && !!question.trim();
  const hasThread = !!answer || !!detail?.items.length || thinking;
  const suggestions = (small: boolean) => (
    <div className={small ? styles.chipsSmall : styles.chips}>
      {capabilities?.suggestedQuestions.map((q, i) => (
        <Button
          key={q}
          data-testid={`analysis-suggestion-${i}`}
          disabled={pending}
          onClick={() => {
            setQuestion(q);
            questionRef.current?.focus();
          }}
        >
          {q}
        </Button>
      ))}
    </div>
  );
  return (
    <section
      className={styles.page}
      data-testid="analysis-screen"
      data-screen-id="SCR-UI-26"
    >
      <ScreenHeading screenId="UI-26" name="AI 분석 도우미" groupName="AI" />
      <div className={styles.layout}>
        <aside
          className={`${styles.recent} ${collapsed ? styles.collapsed : ""}`}
        >
          <Button
            variant="primary"
            data-testid="analysis-new"
            disabled={pending}
            onClick={() => navigate(() => void create())}
          >
            + 새 대화
          </Button>
          <h2>최근 대화</h2>
          {!loading && !error && conversations.length === 0 && (
            <p>표시할 대화가 없습니다.</p>
          )}
          <ul className={styles.convList}>
            {conversations.map((c) => (
              <li
                key={c.id}
                data-testid={`analysis-conversation-${c.id}`}
                className={c.id === active ? styles.convActive : undefined}
              >
                <button
                  type="button"
                  className={styles.convOpen}
                  disabled={pending}
                  data-testid={`analysis-open-${c.id}`}
                  title={c.questionSummary ?? "새 대화"}
                  onClick={() => navigate(() => void open(c.id))}
                >
                  <span>{c.questionSummary ?? "새 대화"}</span>
                  <small>{shortTime(c.updatedAt)}</small>
                </button>
                <button
                  type="button"
                  className={styles.convHide}
                  disabled={pending}
                  data-testid={`analysis-hide-${c.id}`}
                  aria-label="대화 표시 삭제"
                  title="대화 표시 삭제"
                  onClick={() => setDeleteTarget(c.id)}
                >
                  ×
                </button>
              </li>
            ))}
          </ul>
          {deleteTarget !== null && (
            <div
              role="group"
              aria-label="표시 삭제 확인"
              data-testid="analysis-delete-confirmation"
              className={styles.notice}
            >
              대화 #{deleteTarget} · 내 목록/표시기록에서 제거합니다.
              감사·보존정책의 물리삭제와 다릅니다.
              <div className={styles.answerActions}>
                <Button
                  data-testid="analysis-hide-confirm"
                  onClick={() =>
                    void (async () => {
                      try {
                        await client.hideAnalysisConversation(deleteTarget);
                        await refreshList();
                        if (active === deleteTarget) {
                          setActive(null);
                          setDetail(null);
                          setAnswer(null);
                        }
                        setDeleteTarget(null);
                      } catch (e) {
                        failed(e);
                      }
                    })()
                  }
                >
                  표시 삭제 확인
                </Button>
                <Button
                  data-testid="analysis-hide-cancel"
                  onClick={() => setDeleteTarget(null)}
                >
                  취소
                </Button>
              </div>
            </div>
          )}
          {total > 50 && (
            <div className={styles.pager}>
              <Button
                data-testid="analysis-previous"
                disabled={page === 0}
                onClick={() => {
                  setPage((p) => p - 1);
                  void refreshList(page - 1).catch(failed);
                }}
              >
                이전
              </Button>
              <Button
                data-testid="analysis-next"
                disabled={(page + 1) * 50 >= total}
                onClick={() => {
                  setPage((p) => p + 1);
                  void refreshList(page + 1).catch(failed);
                }}
              >
                다음
              </Button>
            </div>
          )}
        </aside>
        <main data-testid="analysis-main" className={styles.content}>
          <div className={styles.chatHead}>
            <span className={styles.model}>
              <i
                className={
                  capabilities?.modelStatus === "준비" ? styles.dotOn : styles.dotOff
                }
              />
              AI 모델{" "}
              <Badge
                tone={capabilities?.modelStatus === "준비" ? "success" : "warning"}
              >
                {capabilities?.modelStatus ?? "확인 중"}
              </Badge>
              <span className={styles.hint}>
                내부 개발/시험 전용 · 조회/가정만 수행
              </span>
            </span>
            <span className={styles.headActions}>
              <Button
                className={styles.mobileOnly}
                data-testid="analysis-collapse"
                aria-expanded={!collapsed}
                onClick={() => setCollapsed((v) => !v)}
              >
                {collapsed ? "최근 대화 펼치기" : "최근 접기"}
              </Button>
              <Button
                data-testid="analysis-structured-open"
                onClick={() => editConditions(answer?.interpretedQuery)}
              >
                정형 분석
              </Button>
            </span>
          </div>
          <div className={styles.thread} ref={threadRef}>
          {loading && <p role="status">분석 기능과 최근 대화 불러오는 중</p>}
          {leave && (
            <div
              className={styles.notice}
              role="group"
              aria-label="미저장 초안 확인"
            >
              미저장 질문/평가를 버리고 이동할까요?
              <Button data-testid="analysis-stay" onClick={() => setLeave(null)}>
                계속 작성
              </Button>
              <Button
                data-testid="analysis-leave"
                onClick={() => {
                  const action = leave;
                  setLeave(null);
                  setQuestion("");
                  setReason("");
                  action();
                }}
              >
                나가기
              </Button>
            </div>
          )}
          {structured && (
            <form
              data-testid="analysis-structured-panel"
              className={styles.card}
              onSubmit={(e) => {
                e.preventDefault();
                void analyze();
              }}
            >
              <h2>정형 분석 · 모델 없이 실제 자료 조회</h2>
              <div className={styles.fields}>
                <label>
                  허용 의도
                  <select
                    data-testid="analysis-intent"
                    value={query.intent}
                    onChange={(e) => {
                      setQuery({
                        intent: e.target.value,
                        limit: 5,
                        sort: "PROFIT_DESC",
                        periodType: "MONTH",
                      });
                      setFilterText({});
                    }}
                  >
                    {capabilities?.intents.map((c) => (
                      <option
                        data-testid={`analysis-intent-option-${c.intent.toLowerCase().replace(/_/g, "-")}`}
                        key={c.intent}
                        value={c.intent}
                      >
                        {c.intent}
                        {!c.available ? " · 원천 미연결" : ""}
                      </option>
                    ))}
                  </select>
                </label>
                <label>
                  시작월
                  <input
                    type="month"
                    data-testid="analysis-from"
                    value={query.fromYm ?? ""}
                    onChange={(e) =>
                      setQuery({
                        ...query,
                        fromYm: e.target.value || undefined,
                      })
                    }
                  />
                </label>
                <label>
                  종료월
                  <input
                    type="month"
                    data-testid="analysis-to"
                    value={query.toYm ?? ""}
                    onChange={(e) =>
                      setQuery({ ...query, toYm: e.target.value || undefined })
                    }
                  />
                </label>
                <label>
                  기간유형
                  <select
                    data-testid="analysis-period"
                    value={query.periodType}
                    onChange={(e) =>
                      setQuery({
                        ...query,
                        periodType: e.target.value as "MONTH" | "YTD",
                      })
                    }
                  >
                    <option value="MONTH">월</option>
                    <option value="YTD">연누계</option>
                  </select>
                </label>
                <label>
                  정렬
                  <select
                    data-testid="analysis-sort"
                    value={query.sort}
                    onChange={(e) =>
                      setQuery({ ...query, sort: e.target.value })
                    }
                  >
                    {[
                      "PROFIT_ASC",
                      "PROFIT_DESC",
                      "MARGIN_ASC",
                      "MARGIN_DESC",
                    ].map((s) => (
                      <option
                        data-testid={`analysis-sort-option-${s.toLowerCase().replace(/_/g, "-")}`}
                        key={s}
                      >
                        {s}
                      </option>
                    ))}
                  </select>
                </label>
                <label>
                  건수
                  <input
                    data-testid="analysis-limit"
                    type="number"
                    min="1"
                    max="20"
                    value={query.limit ?? 5}
                    onChange={(e) =>
                      setQuery({ ...query, limit: Number(e.target.value) })
                    }
                  />
                </label>
                {capability?.allowedFilters.map((key) => (
                  <label
                    data-testid={`analysis-filter-label-${key.replace(/[A-Z]/g, (c) => `-${c.toLowerCase()}`)}`}
                    key={key}
                  >
                    {key} (쉼표 구분)
                    <input
                      data-testid={`analysis-filter-${key.replace(/[A-Z]/g, (c) => `-${c.toLowerCase()}`)}`}
                      value={filterText[key] ?? ""}
                      onChange={(e) =>
                        setFilterText({ ...filterText, [key]: e.target.value })
                      }
                    />
                  </label>
                ))}
              </div>
              <p>
                필수 조건: {capability?.requiredFields.join(", ")} · 최대{" "}
                {capability?.maxMonths}개월 · 전체 ID 20개 이하
              </p>
              {query.intent === "WHAT_IF_PROJECT_COST" && (
                <div className={styles.fields}>
                  {(["revenue", "cost", "costChangePercent"] as const).map(
                    (field) => (
                      <label
                        data-testid={`analysis-virtual-label-${field.replace(/[A-Z]/g, (c) => `-${c.toLowerCase()}`)}`}
                        key={field}
                      >
                        {field === "revenue"
                          ? "가상 매출"
                          : field === "cost"
                            ? "가상 원가"
                            : "원가 변화율(%)"}
                        <input
                          data-testid={`analysis-virtual-${field.replace(/[A-Z]/g, (c) => `-${c.toLowerCase()}`)}`}
                          type="number"
                          step={field === "costChangePercent" ? "0.01" : "1"}
                          min={field === "costChangePercent" ? -50 : 0}
                          max={field === "costChangePercent" ? 100 : undefined}
                          required
                          value={query.virtualCostAssumption?.[field] ?? ""}
                          onChange={(e) =>
                            setQuery({
                              ...query,
                              virtualCostAssumption: {
                                revenue: 0,
                                cost: 0,
                                costChangePercent: 0,
                                ...query.virtualCostAssumption,
                                [field]: Number(e.target.value),
                              },
                            })
                          }
                        />
                      </label>
                    ),
                  )}
                  <p>
                    독립 가상값만 입력하세요. 실제 개인 급여/원가와 결합할 수
                    없으며 이력에 저장하지 않습니다.
                  </p>
                </div>
              )}
              {["WHAT_IF_WORKFORCE", "FORECAST_STAFF"].includes(
                query.intent,
              ) && (
                <div>
                  {(query.workforceAssumptions ?? []).map((a, i) => (
                    <div
                      key={i}
                      data-testid={`analysis-assumption-${i}`}
                      className={styles.fields}
                    >
                      <label>
                        가정 유형
                        <select
                          data-testid={`analysis-assumption-type-${i}`}
                          value={a.type}
                          onChange={(e) =>
                            setQuery({
                              ...query,
                              workforceAssumptions:
                                query.workforceAssumptions?.map((v, n) =>
                                  n === i
                                    ? {
                                        type: e.target.value,
                                        probability: v.probability,
                                      }
                                    : v,
                                ),
                            })
                          }
                        >
                          <option value="OPPORTUNITY_WIN">기회 수주</option>
                          <option value="EXTENSION">배정 연장</option>
                        </select>
                      </label>
                      <label>
                        원천 ID
                        <input
                          type="number"
                          min="1"
                          data-testid={`analysis-assumption-id-${i}`}
                          value={a.opportunityId ?? a.assignmentId ?? ""}
                          onChange={(e) =>
                            setQuery({
                              ...query,
                              workforceAssumptions:
                                query.workforceAssumptions?.map((v, n) =>
                                  n === i
                                    ? {
                                        type: v.type,
                                        probability: v.probability,
                                        [v.type === "EXTENSION"
                                          ? "assignmentId"
                                          : "opportunityId"]: Number(
                                          e.target.value,
                                        ),
                                      }
                                    : v,
                                ),
                            })
                          }
                        />
                      </label>
                      <label>
                        확률(%)
                        <input
                          type="number"
                          min="0"
                          max="100"
                          data-testid={`analysis-assumption-probability-${i}`}
                          value={a.probability}
                          onChange={(e) =>
                            setQuery({
                              ...query,
                              workforceAssumptions:
                                query.workforceAssumptions?.map((v, n) =>
                                  n === i
                                    ? {
                                        ...v,
                                        probability: Number(e.target.value),
                                      }
                                    : v,
                                ),
                            })
                          }
                        />
                      </label>
                      <Button
                        type="button"
                        data-testid={`analysis-assumption-remove-${i}`}
                        onClick={() =>
                          setQuery({
                            ...query,
                            workforceAssumptions:
                              query.workforceAssumptions?.filter(
                                (_, n) => n !== i,
                              ),
                          })
                        }
                      >
                        가정 제거
                      </Button>
                    </div>
                  ))}
                  <Button
                    type="button"
                    data-testid="analysis-assumption-add"
                    disabled={(query.workforceAssumptions?.length ?? 0) >= 3}
                    onClick={() =>
                      setQuery({
                        ...query,
                        workforceAssumptions: [
                          ...(query.workforceAssumptions ?? []),
                          { type: "OPPORTUNITY_WIN", probability: 100 },
                        ],
                      })
                    }
                  >
                    인력 가정 추가 (최대 3개)
                  </Button>
                </div>
              )}
              <Button
                type="submit"
                data-testid="analysis-run"
                pending={pending}
                disabled={!capability?.available}
              >
                분석 실행
              </Button>
              <Button
                type="button"
                data-testid="analysis-structured-cancel"
                onClick={() => setStructured(false)}
              >
                조건 수정 취소
              </Button>
            </form>
          )}
          {detail?.items.map(summary)}
          {detail && detail.total > detail.items.length && (
            <Button
              data-testid="analysis-more-messages"
              onClick={() =>
                void client
                  .getAnalysisConversation(
                    detail.conversation.id,
                    detail.page + 1,
                  )
                  .then((next) =>
                    setDetail({
                      ...next,
                      items: [...detail.items, ...next.items],
                    }),
                  )
                  .catch(failed)
              }
            >
              이전 메시지 더 보기
            </Button>
          )}
          {answer &&
            !detail?.items.some((m) => m.messageId === answer.messageId) &&
            answerCard()}
          {feedbackTarget !== null && (
            <form
              data-testid="analysis-feedback-panel"
              className={styles.card}
              onSubmit={(e) => {
                e.preventDefault();
                void feedback();
              }}
            >
              <h3>메시지 #{feedbackTarget} 평가</h3>
              <label>
                평가 선택
                <select
                  data-testid="analysis-feedback-rating"
                  value={rating}
                  onChange={(e) => setRating(e.target.value as typeof rating)}
                >
                  <option value="">선택</option>
                  <option value="HELPFUL">HELPFUL · 도움됨</option>
                  <option value="INACCURATE">INACCURATE · 부정확</option>
                </select>
              </label>
              <label>
                평가 사유
                <textarea
                  data-testid="analysis-feedback-reason"
                  value={reason}
                  onChange={(e) => setReason(e.target.value)}
                  aria-invalid={reason.length > 500}
                />
              </label>
              <p>{reason.length}/500</p>
              <Button type="submit" data-testid="analysis-feedback-submit">
                평가 제출
              </Button>
              <Button
                type="button"
                data-testid="analysis-feedback-cancel"
                onClick={() => {
                  setFeedbackTarget(null);
                  setReason("");
                }}
              >
                평가 취소
              </Button>
            </form>
          )}
          {!hasThread && !loading && !structured && (
            <div className={styles.welcome}>
              <h2>무엇을 분석해 드릴까요?</h2>
              <p>
                추천 질문을 선택하거나 정형 분석으로 실제 자료를 조회하세요.
              </p>
              {suggestions(false)}
            </div>
          )}
          {thinking && (
            <div className={styles.pair}>
              <p className={styles.user}>{question}</p>
              <div className={styles.bot} aria-live="polite">
                <span className={styles.typing}>
                  <i />
                  <i />
                  <i />
                </span>
                질문 해석 → 자료 조회 → 답변 구성 중
              </div>
            </div>
          )}
          </div>
          {error && (
            <div role="alert" className={styles.error}>
              {error}
              {fieldError && <span> · 입력: {fieldError}</span>}
              <Button data-testid="analysis-retry" onClick={() => void load()}>
                다시 조회
              </Button>
            </div>
          )}
          {hasThread && suggestions(true)}
          <form
            data-testid="analysis-composer"
            className={styles.composer}
            onSubmit={(e) => {
              e.preventDefault();
              void send();
            }}
          >
            <textarea
              ref={questionRef}
              aria-label="질문"
              rows={1}
              placeholder="예: 이번 달 적자가 큰 프로젝트 3개 (Enter 전송 · Shift+Enter 줄바꿈)"
              data-testid="analysis-question"
              value={question}
              disabled={pending}
              onChange={(e) => setQuestion(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === "Enter" && !e.shiftKey && !e.nativeEvent.isComposing) {
                  e.preventDefault();
                  void send();
                }
              }}
              aria-invalid={question.length > 1000 || fieldError === "question"}
            />
            <div className={styles.composerBar}>
              <label className={styles.month}>
                기준월
                <input
                  data-testid="analysis-base-month"
                  type="month"
                  value={baseYm}
                  onChange={(e) => setBaseYm(e.target.value)}
                />
              </label>
              <span className={styles.counter}>{question.length}/1000</span>
              <p role="status" aria-live="polite" className={styles.sendState}>
                {pending ? "처리 중" : "전송 준비"}
              </p>
            <Button
              variant="primary"
              type="submit"
              data-testid="analysis-send"
              disabled={pending}
            >
              전송
            </Button>
            <Button
              type="button"
              data-testid="analysis-stop"
              disabled={!pending}
              onClick={cancel}
            >
              중단
            </Button>
            </div>
          </form>
        </main>
      </div>
    </section>
  );
}
