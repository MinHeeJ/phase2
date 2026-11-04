import { useEffect, useRef, useState } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { Badge, Button, DataTable, Skeleton } from "../../shared/Controls";
import { ApiFailure } from "../../shared/api";
import { ScreenHeading } from "../shared/ScreenHeading";
import {
  createRecommendation,
  criteriaDraft,
  decideRecommendation,
  downloadCareerCard,
  getRecommendation,
  recommendationHistory,
  recommendationOptions,
  type Criteria,
  type Direction,
  type Draft,
  type History,
  type HistoryQuery,
  type Options,
  type Result,
  type Snapshot,
} from "./client";
import styles from "./StaffRecommendations.module.css";

const A: Direction = "PROJECT_TO_PERSON",
  B: Direction = "PERSON_TO_POSITION";
const labels: Record<string, string> = {
  job_scr: "직무",
  skl_scr: "스킬",
  cust_scr: "고객·업종",
  grd_scr: "등급",
  achv_scr: "성과",
  exp_scr: "비용",
  asgn_psbl_scr: "가능일",
};
// 요소별 만점(합 100). 막대 길이와 "점수/만점" 표시에 쓴다.
const maxScores: Record<string, number> = {
  job_scr: 25,
  skl_scr: 20,
  cust_scr: 15,
  grd_scr: 10,
  achv_scr: 10,
  exp_scr: 10,
  asgn_psbl_scr: 10,
};
// 서버 코드는 화면에 그대로 보이지 않게 한글로 바꾼다.
const codeLabels: Record<string, string> = {
  PENDING: "결정 대기",
  ADOPT: "채택",
  REJECT: "거절",
  CONFIRMED_OVERLAP: "확정 배정과 겹침",
  TEMP_OVERLAP: "가배정과 겹침",
  CONFIRMED_UNAVAILABLE: "퇴사·휴직 기간 포함",
  UNKNOWN_EXIT: "철수일 미정",
  LINKED: "배정 연결됨",
  UNLINKED: "미연결",
  PROJECT_TO_PERSON: "빈자리 → 개발자",
  PERSON_TO_POSITION: "개발자 → 다음 자리",
};
const label = (code: string | null | undefined) =>
  code ? (codeLabels[code] ?? code) : "";
const criteriaText = (criteria: object) => {
  const c = criteria as Record<string, unknown>;
  return [
    c.jobCode && `직무 ${c.jobCode}`,
    c.gradeCode && `등급 ${c.gradeCode}`,
    c.startYm && `시작 ${c.startYm}`,
    c.durationMonths && `${c.durationMonths}개월`,
    c.headcount && `${c.headcount}명`,
    c.requestedMm && `월 ${c.requestedMm}MM`,
    Array.isArray(c.skillCodes) && c.skillCodes.length > 0 && `스킬 ${c.skillCodes.join(", ")}`,
    c.industryCode && `업종 ${c.industryCode}`,
  ]
    .filter(Boolean)
    .join(" · ");
};
const timeText = (value: string) => value.replace("T", " ").slice(0, 16);
function ScoreBars({ breakdown }: { breakdown: Record<string, number> }) {
  return (
    <div className={styles.bars}>
      {Object.entries(breakdown).map(([key, value]) => (
        <div key={key} className={styles.bar}>
          <span>{labels[key] ?? key}</span>
          <i aria-hidden="true">
            <b
              style={{
                width: `${Math.min(100, (value / (maxScores[key] ?? 10)) * 100)}%`,
              }}
            />
          </i>
          <em>
            {value.toFixed(value % 1 ? 1 : 0)}/{maxScores[key] ?? "–"}
          </em>
        </div>
      ))}
    </div>
  );
}
const failure = (e: unknown) =>
  e instanceof ApiFailure
    ? e
    : new ApiFailure("요청을 처리하지 못했습니다.", null, null);
function ErrorMessage({ error }: { error: ApiFailure | null }) {
  return error ? (
    <div role="alert" className={styles.error}>
      {error.status === 403 ? "접근이 제한되었습니다. " : ""}
      {error.message} {error.code}{" "}
      {error.requestId && `문의번호 ${error.requestId}`}
    </div>
  ) : null;
}
function ResultFacts({ row }: { row: Result }) {
  return (
    <>
      <p>{row.reason}</p>
      <dl className={styles.scores}>
        {Object.entries(row.breakdown).map(([key, score]) => (
          <div key={key}>
            <dt>{labels[key] ?? key}</dt>
            <dd>{score.toFixed(2)}</dd>
          </div>
        ))}
      </dl>
      <p>
        스킬 {(row.skillCodes ?? []).join(", ") || "미기록"} · 철수{" "}
        {row.exitDate ?? "미확정"} · 최초 가능월{" "}
        {row.earliestStartYm ?? "판정 불가"}
      </p>
      <p>{row.evidence.join(" · ")}</p>
      <p>{row.availabilityWarnings.join(" · ")}</p>
      <div className={styles.scroll}>
        <table>
          <caption>월별 확정·ACTIVE 가배정 MM</caption>
          <thead>
            <tr>
              <th>월</th>
              <th>확정 MM</th>
              <th>ACTIVE MM</th>
              <th>요청 MM</th>
              <th>경고</th>
            </tr>
          </thead>
          <tbody>
            {row.monthByMonthMm.map((m) => (
              <tr key={m.month} data-testid={`mm-${row.resultId}-${m.month}`}>
                <td>{m.month}</td>
                <td>{m.confirmedMm.toFixed(2)}</td>
                <td>{m.activeTemporaryMm.toFixed(2)}</td>
                <td>{m.requestedMm.toFixed(2)}</td>
                <td>{m.warnings.join(" · ") || "없음"}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </>
  );
}
export default function UI30Screen({
  refreshVersion = 0,
  onDirtyChange,
}: {
  name?: string;
  groupName?: string;
  refreshVersion?: number;
  onDirtyChange?: (dirty: boolean) => void;
}) {
  const location = useLocation(),
    navigate = useNavigate();
  const [options, setOptions] = useState<Options | null>(null),
    [optionError, setOptionError] = useState<ApiFailure | null>(null),
    [revision, setRevision] = useState(0);
  const [tab, setTab] = useState<Direction | "history">(A),
    [criteria, setCriteria] = useState<Criteria>({}),
    [savedCriteria, setSavedCriteria] = useState<Criteria>({});
  const [sourceId, setSourceId] = useState(""),
    [devId, setDevId] = useState(""),
    [assumedExitYm, setAssumedExitYm] = useState(""),
    [mm, setMm] = useState("1");
  const [snapshot, setSnapshot] = useState<Snapshot | null>(null),
    [busy, setBusy] = useState(false),
    [error, setError] = useState<ApiFailure | null>(null);
  const [selected, setSelected] = useState<number[]>([]),
    [compare, setCompare] = useState<number[]>([]),
    [comparing, setComparing] = useState(false),
    [compareError, setCompareError] = useState("");
  const [detail, setDetail] = useState<Result | null>(null),
    [career, setCareer] = useState<Result | null>(null),
    [notice, setNotice] = useState("");
  const [decision, setDecision] = useState<{
      row: Result;
      direction: Direction;
      requestId: number;
      value: "ADOPT" | "REJECT";
    } | null>(null),
    [reason, setReason] = useState("");
  const [question, setQuestion] = useState(""),
    [draft, setDraft] = useState<Draft | null>(null),
    [draftBusy, setDraftBusy] = useState(false),
    [draftError, setDraftError] = useState<ApiFailure | null>(null);
  const [history, setHistory] = useState<History | null>(null),
    [historyQuery, setHistoryQuery] = useState<HistoryQuery>({
      page: 0,
      size: 50,
    }),
    [historyBusy, setHistoryBusy] = useState(false),
    [historyError, setHistoryError] = useState<ApiFailure | null>(null);
  const [dirty, setDirty] = useState(false),
    [leaving, setLeaving] = useState<(() => void) | null>(null);
  const heading = useRef<HTMLHeadingElement>(null),
    returnFocus = useRef<HTMLElement | null>(null);
  const dirtyCallback = useRef(onDirtyChange);
  dirtyCallback.current = onDirtyChange;
  useEffect(() => {
    onDirtyChange?.(dirty || !!reason.trim());
  }, [dirty, reason, onDirtyChange]);
  useEffect(() => () => dirtyCallback.current?.(false), []);
  useEffect(() => {
    const controller = new AbortController();
    setOptionError(null);
    setOptions(null);
    const query = new URLSearchParams(location.search);
    recommendationOptions(
      {
        devId: query.get("devId") ?? undefined,
        sourceId: query.get("sourceId") ?? undefined,
      },
      controller.signal,
    )
      .then((data) => {
        if (controller.signal.aborted) return;
        const chosen = data.sources.find(
          (s) => String(s.sourceId) === query.get("sourceId"),
        );
        const next = chosen?.criteria ?? {
          startYm: data.defaultStartYm,
          durationMonths: 1,
          headcount: 1,
          requestedMm: 1,
        };
        setCriteria(next);
        setSavedCriteria(next);
        if (chosen) setSourceId(String(chosen.sourceId));
        if (query.has("devId")) {
          const developer = data.developers.find(
            (d) => String(d.devId) === query.get("devId"),
          );
          if (!developer)
            throw new ApiFailure(
              "현재 개발자 원천을 확인할 수 없습니다.",
              404,
              null,
            );
          setDevId(String(developer.devId));
          setTab(B);
        }
        setOptions(data);
      })
      .catch((e) => {
        if (!controller.signal.aborted) setOptionError(failure(e));
      });
    return () => controller.abort();
  }, [location.search, revision, refreshVersion]);
  useEffect(() => {
    const warn = (event: BeforeUnloadEvent) => {
      if (dirty || reason.trim()) {
        event.preventDefault();
        event.returnValue = "";
      }
    };
    window.addEventListener("beforeunload", warn);
    return () => window.removeEventListener("beforeunload", warn);
  }, [dirty, reason]);
  const changeTab = (next: Direction | "history") => {
    const action = () => {
      setTab(next);
      setDecision(null);
      setDetail(null);
      setCareer(null);
      setComparing(false);
      setReason("");
      setError(null);
      setDirty(false);
      if (next === "history") void loadHistory({ ...historyQuery, page: 0 });
    };
    if (dirty || reason.trim()) setLeaving(() => action);
    else action();
  };
  const update = (next: Criteria) => {
    setCriteria(next);
    setDirty(true);
  };
  const closeRegion = (close: () => void) => {
    close();
    returnFocus.current?.focus();
  };
  const remember = () => {
    returnFocus.current = document.activeElement as HTMLElement;
  };
  const fieldError = (key: string) =>
    error?.field === key ? (
      <span id={`recommendation-error-${key}`} role="alert">
        {error.message}
      </span>
    ) : null;
  const fields = (
    value: Criteria,
    change: (value: Criteria) => void,
    prefix: string,
  ) => (
    <div className={styles.form}>
      <label>
        직무*
        <select
          data-testid={`${prefix}-job`}
          required
          value={value.jobCode ?? ""}
          aria-invalid={error?.field === "jobCode"}
          aria-describedby={
            error?.field === "jobCode"
              ? "recommendation-error-jobCode"
              : undefined
          }
          onChange={(e) =>
            change({ ...value, jobCode: e.target.value || undefined })
          }
        >
          <option value="">직무 선택</option>
          {options?.jobCodes.map((code) => <option key={code}>{code}</option>)}
        </select>
        {fieldError("jobCode")}
      </label>
      <label>
        시작월*
        <input
          data-testid={`${prefix}-start`}
          required
          type="month"
          value={value.startYm ?? ""}
          onChange={(e) =>
            change({ ...value, startYm: e.target.value || undefined })
          }
        />
        {fieldError("startYm")}
      </label>
      <label>
        등급
        <select
          data-testid={`${prefix}-grade`}
          value={value.gradeCode ?? ""}
          onChange={(e) =>
            change({ ...value, gradeCode: e.target.value || undefined })
          }
        >
          <option value="">전체</option>
          {options?.gradeCodes.map((code) => (
            <option key={code}>{code}</option>
          ))}
        </select>
      </label>
      <label>
        스킬
        <select
          data-testid={`${prefix}-skills`}
          multiple
          value={value.skillCodes ?? []}
          onChange={(e) =>
            change({
              ...value,
              skillCodes: Array.from(
                e.target.selectedOptions,
                (option) => option.value,
              ),
            })
          }
        >
          {options?.skillCodes.map((code) => (
            <option key={code}>{code}</option>
          ))}
        </select>
      </label>
      <label>
        고객 참조
        <input
          data-testid={`${prefix}-customer`}
          type="number"
          min="1"
          value={value.customerId ?? ""}
          onChange={(e) =>
            change({
              ...value,
              customerId: e.target.value ? Number(e.target.value) : undefined,
            })
          }
        />
      </label>
      <label>
        업종 코드
        <input
          data-testid={`${prefix}-industry`}
          value={value.industryCode ?? ""}
          onChange={(e) =>
            change({ ...value, industryCode: e.target.value || undefined })
          }
        />
      </label>
      <label>
        기간 (개월)
        <input
          data-testid={`${prefix}-duration`}
          type="number"
          min="1"
          max="36"
          step="1"
          value={value.durationMonths ?? ""}
          onChange={(e) =>
            change({
              ...value,
              durationMonths: e.target.value
                ? Number(e.target.value)
                : undefined,
            })
          }
        />
        {fieldError("durationMonths")}
      </label>
      <label>
        인원 (명)
        <input
          data-testid={`${prefix}-headcount`}
          type="number"
          min="1"
          max="100"
          step="1"
          value={value.headcount ?? ""}
          onChange={(e) =>
            change({
              ...value,
              headcount: e.target.value ? Number(e.target.value) : undefined,
            })
          }
        />
        {fieldError("headcount")}
      </label>
      <label>
        월 MM
        <input
          data-testid={`${prefix}-mm`}
          type="number"
          min="0.01"
          max="1"
          step="0.01"
          value={value.requestedMm ?? ""}
          onChange={(e) =>
            change({
              ...value,
              requestedMm: e.target.value ? Number(e.target.value) : undefined,
            })
          }
        />
        {fieldError("requestedMm")}
      </label>
    </div>
  );
  const acceptSnapshot = (data: Snapshot) => {
    setSnapshot(data);
    setSelected([]);
    setCompare([]);
    setComparing(false);
    setDetail(null);
    setCareer(null);
    setSavedCriteria(criteria);
    setDirty(false);
    queueMicrotask(() => heading.current?.focus());
  };
  const refresh = async (id: number) => {
    setBusy(true);
    setError(null);
    try {
      const data = await getRecommendation(id);
      setSnapshot(data);
    } catch (e) {
      setError(failure(e));
    } finally {
      setBusy(false);
    }
  };
  const run = async () => {
    if (tab === "history") return;
    setBusy(true);
    setError(null);
    setSnapshot(null);
    setSelected([]);
    setCompare([]);
    setDecision(null);
    setDetail(null);
    setCareer(null);
    setNotice("");
    try {
      const data = await createRecommendation(
        tab === A
          ? {
              direction: A,
              ...(sourceId ? { sourceId: Number(sourceId) } : {}),
              criteria,
            }
          : {
              direction: B,
              devId: Number(devId),
              criteria: {
                ...(assumedExitYm ? { assumedExitYm } : {}),
                requestedMm: Number(mm),
              },
            },
      );
      setSnapshot(data);
      acceptSnapshot(await getRecommendation(data.requestId));
      setNotice("추천 실행을 저장했습니다. 배정은 생성되지 않았습니다.");
    } catch (e) {
      const f = failure(e);
      setError(f);
      if (f.field)
        queueMicrotask(() =>
          document
            .querySelector<HTMLElement>(
              `[data-testid="criteria-${({ jobCode: "job", startYm: "start", durationMonths: "duration", headcount: "headcount", requestedMm: "mm" } as Record<string, string>)[f.field!] ?? f.field}"]`,
            )
            ?.focus(),
        );
    } finally {
      setBusy(false);
    }
  };
  const loadHistory = async (query: HistoryQuery) => {
    setHistoryBusy(true);
    setHistoryError(null);
    setHistoryQuery(query);
    try {
      setHistory(await recommendationHistory(query));
    } catch (e) {
      setHistoryError(failure(e));
    } finally {
      setHistoryBusy(false);
    }
  };
  const saveDecision = async () => {
    if (!decision) return;
    if (
      decision.value === "REJECT" &&
      (!reason.trim() || reason.trim().length > 500)
    ) {
      setError(
        new ApiFailure(
          "거절 사유는 1~500자로 입력하세요.",
          400,
          null,
          "reason",
        ),
      );
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await decideRecommendation(
        decision.row.resultId,
        decision.direction,
        decision.value,
        reason.trim() || undefined,
      );
      await refresh(decision.requestId);
      setDecision(null);
      setReason("");
      setNotice("결정만 저장했습니다. 실제 배정은 별도로 저장하세요.");
    } catch (e) {
      setError(failure(e));
      if (e instanceof ApiFailure && e.status === 409) {
        try {
          setSnapshot(await getRecommendation(decision.requestId));
        } catch {
          /* Original decision error remains visible. */
        }
      }
    } finally {
      setBusy(false);
    }
  };
  const exportCard = async (row: Result) => {
    setBusy(true);
    setError(null);
    try {
      await downloadCareerCard(row.devId);
      setNotice("내부용 경력 카드 다운로드가 완료되었습니다.");
    } catch (e) {
      setError(failure(e));
    } finally {
      setBusy(false);
    }
  };
  const handoff = (row: Result) => {
    if (!snapshot) return;
    const target = row.needId ? "/UI-29" : "/UI-12";
    const action = () =>
      navigate(target, {
        state: {
          recommendation: {
            direction: snapshot.direction,
            requestId: snapshot.requestId,
            resultId: row.resultId,
          },
          devId: row.devId,
          sourceIds: row.sourceIds,
          criteria: snapshot.criteria,
        },
      });
    if (dirty || reason.trim()) setLeaving(() => action);
    else action();
  };
  const resultActions = (row: Result) => (
    <div className={styles.actions}>
      <Button
        data-testid={`detail-${row.resultId}`}
        onClick={() => {
          remember();
          setDetail(row);
        }}
      >
        상세
      </Button>
      <Button
        data-testid={`adopt-${row.resultId}`}
        disabled={row.decisionStatus !== "PENDING" || busy}
        onClick={() => {
          remember();
          setDecision({
            row,
            direction: snapshot!.direction,
            requestId: snapshot!.requestId,
            value: "ADOPT",
          });
          setReason("");
          setError(null);
        }}
      >
        채택
      </Button>
      <Button
        data-testid={`reject-${row.resultId}`}
        disabled={row.decisionStatus !== "PENDING" || busy}
        onClick={() => {
          remember();
          setDecision({
            row,
            direction: snapshot!.direction,
            requestId: snapshot!.requestId,
            value: "REJECT",
          });
          setReason("");
          setError(null);
        }}
      >
        거절
      </Button>
      <Button
        data-testid={`career-${row.resultId}`}
        onClick={() => {
          remember();
          setCareer(row);
        }}
      >
        경력 카드
      </Button>
      <Button
        data-testid={`developer-${row.resultId}`}
        onClick={() => navigate(`/UI-02?open=${row.devId}`)}
      >
        개발자 원본
      </Button>
      {row.projectId && (
        <Button
          data-testid={`project-${row.resultId}`}
          onClick={() => navigate(`/UI-01?open=${row.projectId}`)}
        >
          프로젝트 원본
        </Button>
      )}
      <Button
        data-testid={`review-${row.resultId}`}
        disabled={
          row.decisionStatus !== "ADOPT" || (!row.projectId && !row.needId)
        }
        title={
          !row.projectId && !row.needId
            ? "수동 조건은 배정 원천을 먼저 선택하세요."
            : undefined
        }
        onClick={() => handoff(row)}
      >
        {row.needId ? "가배정 검토" : "확정 배정 검토"}
      </Button>
      <span>
        {label(row.decisionStatus)} {label(row.assignmentLinkStatus)}{" "}
        {row.rejectionReason}
      </span>
    </div>
  );
  const chosen = options?.developers.find((d) => String(d.devId) === devId);
  const selectedRows =
    snapshot?.results.filter((r) => selected.includes(r.resultId)) ?? [];
  const warnings = [
    ...new Set(selectedRows.flatMap((r) => r.availabilityWarnings)),
  ];
  return (
    <section
      data-testid="staff-recommendations-screen"
      data-screen-id="SCR-UI-30"
      className={styles.screen}
      aria-busy={busy}
    >
      <ScreenHeading screenId="UI-30" name="AI 인력 추천" groupName="AI" />
      <p className={styles.lead}>
        프로젝트에 맞는 인력과 철수 예정자의 다음 자리를 찾습니다 · 조회 기준일{" "}
        {new Intl.DateTimeFormat("sv-SE", { timeZone: "Asia/Seoul" }).format(
          new Date(),
        )}
      </p>
      <p className={styles.banner}>
        내부 개발/시험 전용 · 추천/채택은 배정을 만들지 않습니다
      </p>
      <div role="tablist" className={styles.tabs}>
        {(
          [
            [A, "빈자리 → 개발자"],
            [B, "개발자 → 다음 자리"],
            ["history", "추천 이력"],
          ] as const
        ).map(([key, text]) => (
          <Button
            key={key}
            data-testid={`tab-${key === A ? "a" : key === B ? "b" : "history"}`}
            role="tab"
            aria-selected={tab === key}
            disabled={busy}
            onClick={() => changeTab(key)}
          >
            {text}
          </Button>
        ))}
      </div>
      {leaving && (
        <section data-testid="leave-confirmation" className={styles.panel}>
          <p>미저장 조건/사유를 버리고 이동할까요?</p>
          <Button
            data-testid="continue-editing"
            onClick={() => setLeaving(null)}
          >
            계속 작성
          </Button>
          <Button
            data-testid="leave-screen"
            onClick={() => {
              leaving();
              setLeaving(null);
              setDirty(false);
            }}
          >
            나가기
          </Button>
        </section>
      )}
      <ErrorMessage error={optionError} />
      {optionError && (
        <Button
          data-testid="options-retry"
          onClick={() => setRevision((r) => r + 1)}
        >
          원천 다시 조회
        </Button>
      )}
      {!options && !optionError && <Skeleton label="추천 원천 불러오는 중" />}
      {tab !== "history" && options && (
        <section
          role="tabpanel"
          data-testid="recommendation-form-panel"
          className={styles.panel}
        >
          {tab === A && (
            <>
              <section
                data-testid="criteria-draft-region"
                className={styles.draft}
              >
                <label>
                  자유 서술 조건
                  <textarea
                    data-testid="criteria-question"
                    value={question}
                    maxLength={1000}
                    onChange={(e) => setQuestion(e.target.value)}
                  />
                </label>
                <Button
                  data-testid="criteria-preview"
                  pending={draftBusy}
                  disabled={!question.trim()}
                  onClick={async () => {
                    setDraftBusy(true);
                    setDraftError(null);
                    try {
                      setDraft(await criteriaDraft(question));
                    } catch (e) {
                      setDraftError(failure(e));
                    } finally {
                      setDraftBusy(false);
                    }
                  }}
                >
                  조건 초안 해석
                </Button>
                <ErrorMessage error={draftError} />
                {draft && (
                  <div data-testid="criteria-draft-panel">
                    <p>
                      {draft.status} · 모델 사용{" "}
                      {draft.modelUsed ? "예" : "아니오"}
                    </p>
                    <p>
                      {draft.clarification} {draft.warnings.join(" · ")}
                    </p>
                    {draft.status === "OK" &&
                      fields(
                        draft.criteria,
                        (c) => setDraft({ ...draft, criteria: c }),
                        "draft",
                      )}
                    <Button
                      data-testid="draft-cancel"
                      onClick={() => setDraft(null)}
                    >
                      취소
                    </Button>
                    <Button
                      data-testid="draft-apply"
                      disabled={draft.status !== "OK"}
                      onClick={() => {
                        update({ ...criteria, ...draft.criteria });
                        setDraft(null);
                        setNotice(
                          "조건 초안을 적용했습니다. 별도로 추천 실행하세요.",
                        );
                      }}
                    >
                      수정한 조건 확인·A에 적용
                    </Button>
                  </div>
                )}
              </section>
              <form
                onSubmit={(e) => {
                  e.preventDefault();
                  void run();
                }}
              >
                <label>
                  원천
                  <select
                    data-testid="recommendation-source"
                    value={sourceId}
                    onChange={(e) => {
                      setSourceId(e.target.value);
                      const source = options?.sources.find(
                        (s) => String(s.sourceId) === e.target.value,
                      );
                      if (source) update({ ...source.criteria });
                      else update({ ...savedCriteria });
                    }}
                  >
                    <option value="">수동 조건 입력</option>
                    {options?.sources.map((source) => (
                      <option key={source.sourceId} value={source.sourceId}>
                        {source.sourceType === "PROJECT"
                          ? "진행·예정 프로젝트 미충원 인력 계획"
                          : "OPEN 영업 기회 필요 인력"}{" "}
                        · {source.criteria.jobCode} · {source.criteria.startYm}{" "}
                        · 잔여 {source.remainingHeadcount}명
                      </option>
                    ))}
                  </select>
                </label>
                {fields(criteria, update, "criteria")}
                <ErrorMessage error={error} />
                <div className={styles.actions}>
                  <Button
                    data-testid="criteria-cancel"
                    type="button"
                    onClick={() => {
                      setCriteria(savedCriteria);
                      setDirty(false);
                    }}
                  >
                    조건 편집 취소
                  </Button>
                  <Button
                    data-testid="recommendation-run"
                    type="submit"
                    variant="primary"
                    pending={busy}
                    disabled={!options}
                  >
                    추천 실행
                  </Button>
                </div>
              </form>
            </>
          )}
          {tab === B && (
            <form
              id="person-to-position"
              onSubmit={(e) => {
                e.preventDefault();
                void run();
              }}
            >
              <div className={styles.form}>
                <label>
                  개발자*
                  <select
                    data-testid="next-developer"
                    required
                    value={devId}
                    onChange={(e) => {
                      setDevId(e.target.value);
                      setDirty(true);
                    }}
                  >
                    <option value="">개발자 선택</option>
                    {options?.developers.map((d) => (
                      <option key={d.devId} value={d.devId}>
                        개발자 참조 {d.devId} · 철수 {d.exitDate ?? "미확정"} ·{" "}
                        {d.extensionLikelihood ?? "미입력"} · 차기확정{" "}
                        {d.hasNextConfirmedAssignment ? "있음" : "없음"}
                      </option>
                    ))}
                  </select>
                </label>
                <label>
                  가정 철수월
                  <input
                    data-testid="assumed-exit-month"
                    type="month"
                    required={!!chosen && !chosen.exitDate}
                    value={assumedExitYm}
                    onChange={(e) => {
                      setAssumedExitYm(e.target.value);
                      setDirty(true);
                    }}
                  />
                  {fieldError("assumedExitYm")}
                </label>
                <label>
                  월 MM
                  <input
                    data-testid="next-mm"
                    type="number"
                    min="0.01"
                    max="1"
                    step="0.01"
                    value={mm}
                    required
                    onChange={(e) => {
                      setMm(e.target.value);
                      setDirty(true);
                    }}
                  />
                </label>
              </div>
              {chosen && (
                <p>
                  확정 철수일 {chosen.exitDate ?? "미확정 · 가정 철수월 필수"} ·{" "}
                  {chosen.extensionLikelihood ?? "미입력"} ·{" "}
                  {chosen.hasNextConfirmedAssignment
                    ? "차기 확정 배정 있음"
                    : "차기 확정 배정 없음"}
                  {chosen.extensionLikelihood === "H" &&
                    " · 연장 가능성 높음, 일정 확인 필요"}
                </p>
              )}
              <ErrorMessage error={error} />
              <Button
                data-testid="next-cancel"
                type="button"
                onClick={() => {
                  setAssumedExitYm("");
                  setDirty(false);
                }}
              >
                조건 편집 취소
              </Button>
              <Button
                data-testid="recommendation-next-run"
                type="submit"
                variant="primary"
                pending={busy}
                disabled={!options}
              >
                추천 실행
              </Button>
            </form>
          )}
          <p>
            외부 후보는 내부 개발자 추천에 포함하지 않습니다. 외부 후보
            바로가기는 목적지 확정 전 제공하지 않습니다.
          </p>
        </section>
      )}
      {notice && <p role="status">{notice}</p>}
      {busy && <Skeleton label="추천 처리 중" />}
      {snapshot && tab === snapshot.direction && (
        <section data-testid="recommendation-results" className={styles.panel}>
          <h2 tabIndex={-1} ref={heading}>
            추천 결과
          </h2>
          <p>
            조건 {criteriaText(snapshot.criteria)} · 조회 시각{" "}
            {timeText(snapshot.evaluatedAt)} · 추천 {snapshot.results.length} · 주의{" "}
            {
              snapshot.results.filter((r) => r.availabilityStatus !== "가능")
                .length
            }
          </p>
          {snapshot.warnings.map((warning, index) => (
            <p key={index} role="status">
              {warning}
            </p>
          ))}
          {snapshot.direction === A && (
            <>
              <p aria-live="polite">
                {selected.length}/{snapshot.criteria.headcount ?? 1}명 선정 ·{" "}
                {Math.max(
                  0,
                  (snapshot.criteria.headcount ?? 1) - selected.length,
                )}
                명 부족
              </p>
              <p>
                선정 후보 일정:{" "}
                {warnings.join(" · ") ||
                  (selectedRows.some((r) => r.monthByMonthMm.length === 0)
                    ? "판정 불가"
                    : "응답에 표시된 충돌 없음 · 자동 팀 구성하지 않음")}
              </p>
            </>
          )}
          {snapshot.results.length === 0 && (
            <p className={styles.empty}>
              {snapshot.direction === A
                ? "조건에 맞는 추천이 없습니다."
                : "조건에 맞는 다음 자리가 없습니다."}
            </p>
          )}
          <ErrorMessage error={error} />
          <Button
            data-testid="results-refresh"
            disabled={busy}
            onClick={() => void refresh(snapshot.requestId)}
          >
            다시 조회
          </Button>
          <DataTable
            rows={snapshot.results}
            rowKey={(row) => String(row.resultId)}
            total={snapshot.results.length}
            showEmpty={false}
            columns={[
              {
                key: "selection",
                label: "선정 / 비교",
                render: (row) => (
                  <div data-testid={`recommendation-row-${row.resultId}`}>
                    <label>
                      <input
                        data-testid={`select-${row.resultId}`}
                        type="checkbox"
                        checked={selected.includes(row.resultId)}
                        onChange={(e) =>
                          setSelected((s) =>
                            e.target.checked
                              ? [...s, row.resultId]
                              : s.filter((id) => id !== row.resultId),
                          )
                        }
                      />
                      선정
                    </label>
                    <label>
                      <input
                        data-testid={`compare-${row.resultId}`}
                        type="checkbox"
                        checked={compare.includes(row.resultId)}
                        onChange={(e) => {
                          if (e.target.checked && compare.length >= 3) {
                            setCompareError("최대 3명까지 비교할 수 있습니다.");
                            return;
                          }
                          setCompareError("");
                          setCompare((c) =>
                            e.target.checked
                              ? [...c, row.resultId]
                              : c.filter((id) => id !== row.resultId),
                          );
                        }}
                      />
                      비교
                    </label>
                  </div>
                ),
              },
              { key: "rank", label: "순위", render: (row) => row.rank },
              {
                key: "person",
                label:
                  snapshot.direction === A
                    ? "개발자명 / 번호 · 직무 / 등급 / 스킬"
                    : "프로젝트 / OPEN 기회 · 고객 / 직무 / 등급",
                render: (row) => (
                  <>
                    {row.developerName ?? `개발자 참조 ${row.devId}`}
                    <br />
                    {row.developerNumber ?? "미기록"}
                    <br />
                    {row.projectId
                      ? `프로젝트 참조 ${row.projectId}`
                      : row.needId
                        ? `OPEN 기회 필요인력 ${row.needId}`
                        : ""}{" "}
                    {row.customerName ?? ""}
                    <br />
                    {row.jobCode ?? "미기록"} / {row.gradeCode ?? "미기록"}
                    <br />
                    {row.skillCodes?.join(", ") || "미기록"}
                  </>
                ),
              },
              {
                key: "period",
                label:
                  snapshot.direction === A
                    ? "현재 프로젝트 / 철수 / 가용월"
                    : "원기간 / 실제 추천월 / 잔여 / 대기일 / 수주확률",
                render: (row) =>
                  snapshot.direction === A ? (
                    <>
                      {row.currentProjectNames?.join(", ") || "미기록"}
                      <br />
                      {row.exitDate ?? "미확정"}
                      <br />
                      {row.earliestStartYm ?? "판정 불가"}
                    </>
                  ) : (
                    <>
                      {row.positionStartYm} ~ {row.positionEndYm}
                      <br />
                      실제 추천월 {row.recommendedStartYm}
                      <br />
                      잔여 {row.remainingHeadcount}명 · 대기 {row.waitingDays}일{" "}
                      {row.waitingDaysEstimated && "월 단위 추정"}
                      <br />
                      수주확률{" "}
                      {row.opportunityProbability === undefined
                        ? "미기록"
                        : `${row.opportunityProbability}%`}
                    </>
                  ),
              },
              {
                key: "score",
                label: "총점 / 7요소",
                render: (row) => (
                  <>
                    <strong className={styles.total}>
                      {row.score.toFixed(1)}
                      <small>/100</small>
                    </strong>
                    <ScoreBars breakdown={row.breakdown} />
                  </>
                ),
              },
              {
                key: "availability",
                label: "일정 상태 / 연장 / 충돌",
                render: (row) => (
                  <>
                    <Badge
                      tone={
                        row.availabilityStatus === "가능"
                          ? "success"
                          : row.availabilityStatus === "확정 충돌"
                            ? "danger"
                            : row.availabilityStatus === "판정 불가"
                              ? "locked"
                              : "warning"
                      }
                    >
                      {row.availabilityStatus}
                    </Badge>
                    <p>{row.extensionLikelihood ?? "미입력"}</p>
                    {row.availabilityWarnings.map((w) => (
                      <p key={w}>{label(w)}</p>
                    ))}
                  </>
                ),
              },
              { key: "actions", label: "동작 / 결정", render: resultActions },
            ]}
          />
          {compareError && <p role="alert">{compareError}</p>}
          <Button
            data-testid="open-comparison"
            disabled={!compare.length}
            onClick={() => {
              remember();
              setComparing(true);
            }}
          >
            비교 열기
          </Button>
          {comparing && (
            <section
              data-testid="recommendation-comparison"
              className={styles.panel}
            >
              <h2>최대 3후보 나란히 비교</h2>
              <div className={styles.comparison}>
                {snapshot.results
                  .filter((r) => compare.includes(r.resultId))
                  .map((row) => (
                    <article
                      data-testid={`comparison-${row.resultId}`}
                      key={row.resultId}
                    >
                      <h3>
                        {row.developerName} · {row.score.toFixed(2)}
                      </h3>
                      <ResultFacts row={row} />
                      <p>
                        실수행{" "}
                        {(row.career ?? [])
                          .map((c) => c.projectName)
                          .join(", ") || "미기록"}
                      </p>
                    </article>
                  ))}
              </div>
              <Button
                data-testid="close-comparison"
                onClick={() => closeRegion(() => setComparing(false))}
              >
                비교 닫기
              </Button>
            </section>
          )}
        </section>
      )}
      {detail && (
        <section data-testid="recommendation-detail" className={styles.panel}>
          <h2>상세 · {detail.developerName}</h2>
          <ResultFacts row={detail} />
          <p>결손 {snapshot?.missingDataFields.join(" · ") || "없음"}</p>
          <Button
            data-testid="close-detail"
            onClick={() => closeRegion(() => setDetail(null))}
          >
            닫기
          </Button>
        </section>
      )}
      {career && (
        <section data-testid="career-panel" className={styles.panel}>
          <h2>실경력 카드 · 내부용</h2>
          <p>
            {career.developerName} {career.developerNumber} · {career.jobCode} /{" "}
            {career.gradeCode}
          </p>
          {!career.career?.length ? (
            <p>미기록</p>
          ) : (
            <DataTable
              rows={career.career}
              rowKey={(r) => `${r.projectId}-${r.startDate}`}
              total={career.career.length}
              columns={[
                {
                  key: "project",
                  label: "프로젝트명",
                  render: (r) => r.projectName,
                },
                {
                  key: "period",
                  label: "실제 기간",
                  render: (r) => `${r.startDate} ~ ${r.endDate ?? "미기록"}`,
                },
                { key: "job", label: "직무", render: (r) => r.jobCode },
                {
                  key: "skills",
                  label: "구조화 기술스택",
                  render: (r) => r.skillCodes.join(", ") || "미기록",
                },
                {
                  key: "mm",
                  label: "MM",
                  render: (r) =>
                    r.monthByMonthMm
                      .map((m) => `${m.month}: ${m.mm.toFixed(2)}`)
                      .join(" · ") || "미기록",
                },
              ]}
            />
          )}
          <ErrorMessage error={error} />
          <Button
            data-testid="career-excel"
            pending={busy}
            onClick={() => void exportCard(career)}
          >
            내부용 Excel
          </Button>
          <Button
            data-testid="close-career"
            onClick={() => closeRegion(() => setCareer(null))}
          >
            경력 닫기
          </Button>
        </section>
      )}
      {decision && (
        <section data-testid="decision-panel" className={styles.panel}>
          <h2>
            결정 확인 · {decision.row.developerName} · {decision.value}
          </h2>
          <p>
            방향 {decision.direction} / 결과 {decision.row.resultId}
          </p>
          <p>
            채택은 결정만 저장합니다. 배정은 UI-12/UI-29에서 별도로 저장합니다.
          </p>
          <ErrorMessage error={error} />
          {decision.value === "REJECT" && (
            <label>
              거절 사유
              <textarea
                data-testid="rejection-reason"
                value={reason}
                maxLength={500}
                onChange={(e) => setReason(e.target.value)}
              />
              {reason.length}/500 {fieldError("reason")}
            </label>
          )}
          <Button
            data-testid="decision-cancel"
            onClick={() =>
              closeRegion(() => {
                setDecision(null);
                setReason("");
              })
            }
          >
            취소
          </Button>
          <Button
            data-testid="decision-save"
            pending={busy}
            onClick={() => void saveDecision()}
          >
            결정 저장
          </Button>
        </section>
      )}
      {tab === "history" && (
        <section
          role="tabpanel"
          data-testid="recommendation-history"
          className={styles.panel}
        >
          <h2>추천 이력</h2>
          <form
            onSubmit={(e) => {
              e.preventDefault();
              void loadHistory({ ...historyQuery, page: 0 });
            }}
          >
            <div className={styles.form}>
              <label>
                방향
                <select
                  data-testid="history-direction"
                  value={historyQuery.direction ?? ""}
                  onChange={(e) =>
                    setHistoryQuery((q) => ({
                      ...q,
                      direction: (e.target.value || undefined) as
                        | Direction
                        | undefined,
                    }))
                  }
                >
                  <option value="">전체</option>
                  <option value={A}>빈자리 → 개발자</option>
                  <option value={B}>개발자 → 다음 자리</option>
                </select>
              </label>
              <label>
                검색
                <input
                  data-testid="history-search"
                  value={historyQuery.search ?? ""}
                  onChange={(e) =>
                    setHistoryQuery((q) => ({ ...q, search: e.target.value }))
                  }
                />
              </label>
              <label>
                결정 상태
                <select
                  data-testid="history-decision"
                  value={historyQuery.decisionStatus ?? ""}
                  onChange={(e) =>
                    setHistoryQuery((q) => ({
                      ...q,
                      decisionStatus: e.target.value || undefined,
                    }))
                  }
                >
                  <option value="">전체</option>
                  {["PENDING", "ADOPT", "REJECT"].map((v) => (
                    <option key={v}>{v}</option>
                  ))}
                </select>
              </label>
              <label>
                시작월
                <input
                  data-testid="history-from"
                  type="month"
                  value={historyQuery.fromYm ?? ""}
                  onChange={(e) =>
                    setHistoryQuery((q) => ({
                      ...q,
                      fromYm: e.target.value || undefined,
                    }))
                  }
                />
              </label>
              <label>
                종료월
                <input
                  data-testid="history-to"
                  type="month"
                  value={historyQuery.toYm ?? ""}
                  onChange={(e) =>
                    setHistoryQuery((q) => ({
                      ...q,
                      toYm: e.target.value || undefined,
                    }))
                  }
                />
              </label>
            </div>
            <Button
              data-testid="history-submit"
              type="submit"
              pending={historyBusy}
            >
              이력 조회
            </Button>
          </form>
          <ErrorMessage error={historyError} />
          {historyBusy && <Skeleton label="추천 이력 불러오는 중" />}
          {history && !historyBusy && (
            <>
              <DataTable
                rows={history.items}
                rowKey={(r) => String(r.requestId)}
                total={history.total}
                columns={[
                  {
                    key: "at",
                    label: "실행시각 / 방향",
                    render: (r) => (
                      <span data-testid={`history-row-${r.requestId}`}>
                        {timeText(r.evaluatedAt)} · {label(r.direction)}
                      </span>
                    ),
                  },
                  {
                    key: "criteria",
                    label: "조건",
                    render: (r) => criteriaText(r.criteria),
                  },
                  {
                    key: "results",
                    label: "당시 순위 / 점수 / 결정 / 배정",
                    render: (r) =>
                      r.results.map((s) => (
                        <p key={s.resultId}>
                          {s.rank}위 · {s.score.toFixed(1)}점 · {label(s.decisionStatus)}{" "}
                          · {label(s.assignmentLinkStatus) || "미연결"}
                        </p>
                      )),
                  },
                  {
                    key: "detail",
                    label: "상세",
                    render: (r) => (
                      <Button
                        data-testid={`history-detail-${r.requestId}`}
                        onClick={async () => {
                          setBusy(true);
                          setHistoryError(null);
                          try {
                            const data = await getRecommendation(r.requestId);
                            acceptSnapshot(data);
                            setTab(data.direction);
                          } catch (e) {
                            setHistoryError(failure(e));
                          } finally {
                            setBusy(false);
                          }
                        }}
                      >
                        당시 상세
                      </Button>
                    ),
                  },
                ]}
              />
              <div className={styles.actions}>
                <Button
                  data-testid="history-previous"
                  disabled={history.page <= 0}
                  onClick={() =>
                    void loadHistory({
                      ...historyQuery,
                      page: history.page - 1,
                    })
                  }
                >
                  이전 쪽
                </Button>
                <span>
                  {history.page + 1}쪽 · 전체 {history.total}건
                </span>
                <Button
                  data-testid="history-next"
                  disabled={(history.page + 1) * history.size >= history.total}
                  onClick={() =>
                    void loadHistory({
                      ...historyQuery,
                      page: history.page + 1,
                    })
                  }
                >
                  다음 쪽
                </Button>
                <select
                  data-testid="history-size"
                  aria-label="이력 건수"
                  value={historyQuery.size}
                  onChange={(e) =>
                    void loadHistory({
                      ...historyQuery,
                      page: 0,
                      size: Number(e.target.value) as 20 | 50 | 100,
                    })
                  }
                >
                  {[20, 50, 100].map((size) => (
                    <option key={size} value={size}>
                      {size}건
                    </option>
                  ))}
                </select>
              </div>
              <h3>최근 3개월 품질</h3>
              <div className={styles.scores}>
                {(
                  [
                    ["채택률", history.quality.adoption],
                    ["배정전환율", history.quality.assignmentConversion],
                    ["관측연장률", history.quality.observedExtension],
                  ] as const
                ).map(([title, rate]) => (
                  <p key={title}>
                    {title}{" "}
                    {rate.rate === null ? "–" : `${rate.rate.toFixed(2)}%`} ·{" "}
                    {rate.numerator}/{rate.denominator}건
                  </p>
                ))}
              </div>
              <p>
                유효 표본 {history.quality.validSampleCountLastThreeMonths}건 ·{" "}
                {history.quality.warnings.join(" · ")}
              </p>
            </>
          )}
        </section>
      )}
    </section>
  );
}
