import { useEffect, useRef, useState } from "react";
import { Badge, Button, Skeleton } from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { ApiFailure } from "../../shared/api";
import {
  getStaffRecommendationOptions,
  type RecommendationCriteria,
  type RecommendationDirection,
  type RecommendationOptions,
  type RecommendationSourceOption,
  type RecommendationSubmission,
} from "./client";
import styles from "./RecommendationConditions.module.css";

type Props = {
  direction: RecommendationDirection;
  initialSourceId?: number;
  initialDevId?: number;
  readOnly?: boolean;
  onRecommend: (input: RecommendationSubmission) => Promise<unknown>;
};
const failure = (error: unknown) =>
  error instanceof ApiFailure
    ? error
    : new ApiFailure("조건 조회에 실패했습니다.", null, null);

export function RecommendationConditions({
  direction,
  initialSourceId,
  initialDevId,
  readOnly = false,
  onRecommend,
}: Props) {
  const [options, setOptions] = useState<RecommendationOptions | null>(null);
  const [error, setError] = useState<ApiFailure | null>(null);
  const [loading, setLoading] = useState(true);
  const [pending, setPending] = useState(false);
  const [version, setVersion] = useState(0);
  const [source, setSource] = useState<RecommendationSourceOption | null>(null);
  const [kind, setKind] = useState("MANUAL");
  const [devId, setDevId] = useState("");
  const [criteria, setCriteria] = useState<RecommendationCriteria>({
    jobCode: "",
    startYm: "",
  });
  const [assumedExitYm, setAssumedExitYm] = useState("");
  const [mm, setMm] = useState("");
  const [fields, setFields] = useState<Record<string, string>>({});
  const [message, setMessage] = useState("");
  const form = useRef<HTMLFormElement>(null);
  const busy = useRef(false);
  const sequence = useRef(0);
  useEffect(() => {
    const controller = new AbortController();
    sequence.current++;
    setLoading(true);
    setOptions(null);
    setError(null);
    setSource(null);
    setFields({});
    setMessage("");
    setKind("MANUAL");
    setDevId("");
    setAssumedExitYm("");
    setMm("");
    void getStaffRecommendationOptions(
      { direction, sourceId: initialSourceId, devId: initialDevId },
      controller.signal,
    )
      .then((data) => {
        if (controller.signal.aborted) return;
        setOptions(data);
        const selected = data.sources.find(
          (item) => item.sourceId === initialSourceId,
        );
        setSource(selected ?? null);
        setKind(selected?.sourceType ?? "MANUAL");
        setCriteria(
          selected?.criteria ?? { jobCode: "", startYm: data.defaultStartYm },
        );
        if (
          initialDevId &&
          data.developers.some((item) => item.devId === initialDevId)
        )
          setDevId(String(initialDevId));
      })
      .catch((reason) => {
        if (!controller.signal.aborted) setError(failure(reason));
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [direction, initialSourceId, initialDevId, version]);
  const selectedDeveloper = options?.developers.find(
    (item) => String(item.devId) === devId,
  );
  const change = <K extends keyof RecommendationCriteria>(
    key: K,
    value: RecommendationCriteria[K],
  ) => {
    setCriteria((previous) => ({ ...previous, [key]: value }));
    setFields((previous) => ({ ...previous, [key]: "" }));
    setMessage("");
  };
  const chooseSource = async (value: string) => {
    const current = ++sequence.current;
    setSource(null);
    setError(null);
    setPending(true);
    if (!value) {
      setPending(false);
      return;
    }
    try {
      const fresh = await getStaffRecommendationOptions({
        direction,
        sourceId: Number(value),
      });
      if (current !== sequence.current) return;
      const selected = fresh.sources.find(
        (item) => item.sourceId === Number(value),
      );
      if (!selected)
        throw new ApiFailure("선택한 원천이 없습니다.", 404, null, "sourceId");
      setSource(selected);
      setCriteria(selected.criteria);
      setFields({});
    } catch (reason) {
      if (current === sequence.current) setError(failure(reason));
    } finally {
      if (current === sequence.current) setPending(false);
    }
  };
  const submit = async () => {
    if (busy.current || readOnly || !options) return;
    const invalid: Record<string, string> = {};
    const validMonth = (value: string) => /^\d{4}-(0[1-9]|1[0-2])$/.test(value);
    if (direction === "PROJECT_TO_PERSON") {
      if (kind !== "MANUAL" && !source) invalid.sourceId = "원천을 선택하세요.";
      if (!options.jobCodes.includes(criteria.jobCode))
        invalid.jobCode = "직무를 선택하세요.";
      if (!validMonth(criteria.startYm))
        invalid.startYm = "시작월을 YYYY-MM으로 입력하세요.";
      if (
        criteria.gradeCode &&
        !options.gradeCodes.includes(criteria.gradeCode)
      )
        invalid.gradeCode = "활성 등급을 선택하세요.";
      if (
        criteria.skillCodes?.some((code) => !options.skillCodes.includes(code))
      )
        invalid.skillCodes = "활성 스킬을 선택하세요.";
      if (
        criteria.durationMonths !== undefined &&
        (!Number.isInteger(criteria.durationMonths) ||
          criteria.durationMonths < 1 ||
          criteria.durationMonths > 36)
      )
        invalid.durationMonths = "기간은 1~36개월입니다.";
      if (
        criteria.headcount !== undefined &&
        (!Number.isInteger(criteria.headcount) ||
          criteria.headcount < 1 ||
          criteria.headcount > 100)
      )
        invalid.headcount = "인원은 1~100명입니다.";
      if (criteria.requestedMm !== undefined && !validMm(criteria.requestedMm))
        invalid.requestedMm = "월 MM은 0.01~1.00, 소수 둘째 자리까지입니다.";
    } else {
      if (!selectedDeveloper) invalid.devId = "개발자를 선택하세요.";
      if (
        (!selectedDeveloper?.exitDate || assumedExitYm) &&
        !validMonth(assumedExitYm)
      )
        invalid.assumedExitYm = "미확정 철수는 가정 철수월이 필요합니다.";
      if (mm && !validMm(Number(mm)))
        invalid.requestedMm = "월 MM은 0.01~1.00, 소수 둘째 자리까지입니다.";
    }
    setFields(invalid);
    if (Object.keys(invalid).length) {
      form.current
        ?.querySelector<HTMLElement>(`[name="${Object.keys(invalid)[0]}"]`)
        ?.focus();
      return;
    }
    busy.current = true;
    setPending(true);
    setError(null);
    setMessage("");
    try {
      if (source || selectedDeveloper)
        await getStaffRecommendationOptions({
          direction,
          sourceId: source?.sourceId,
          devId: selectedDeveloper?.devId,
        });
      const input: RecommendationSubmission =
        direction === "PROJECT_TO_PERSON"
          ? {
              direction,
              ...(source ? { sourceId: source.sourceId } : {}),
              criteria,
            }
          : {
              direction,
              devId: Number(devId),
              criteria: {
                ...(assumedExitYm ? { assumedExitYm } : {}),
                ...(mm ? { requestedMm: Number(mm) } : {}),
              },
            };
      await onRecommend(input);
      setMessage(
        "추천 요청을 처리했습니다. 추천·채택만으로 배정은 생성되지 않습니다.",
      );
    } catch (reason) {
      const next = failure(reason);
      setError(next);
      if (next.field)
        setFields({ [next.field.replace(/^criteria\./, "")]: next.message });
    } finally {
      busy.current = false;
      setPending(false);
    }
  };
  const fieldError = (name: string) =>
    fields[name] ? (
      <small id={`ai-condition-${name}-error`} role="alert">
        {fields[name]}
      </small>
    ) : null;
  const a11y = (name: string) => ({
    name,
    "aria-invalid": !!fields[name],
    "aria-describedby": fields[name] ? `ai-condition-${name}-error` : undefined,
  });
  if (loading)
    return (
      <section data-testid="ai-conditions-loading" aria-busy="true">
        <Skeleton label="추천 조건 불러오는 중" />
      </section>
    );
  return (
    <section
      className={styles.panel}
      data-testid="ai-recommendation-conditions"
      aria-busy={pending}
    >
      <div className={styles.notice}>
        내부 개발/시험 전용 · 추천/채택은 배정을 만들지 않습니다
      </div>
      <ErrorBanner error={error} />
      {error && (
        <Button
          data-testid="ai-options-retry"
          disabled={pending}
          onClick={() => setVersion((value) => value + 1)}
        >
          조건 다시 조회
        </Button>
      )}
      {error?.status === 403 && (
        <p>현재 환경에서는 AI 인력 추천을 사용할 수 없습니다.</p>
      )}
      {options && error?.status !== 403 && (
        <form
          data-testid="ai-conditions-form"
          ref={form}
          noValidate
          onSubmit={(event) => {
            event.preventDefault();
            void submit();
          }}
        >
          <fieldset disabled={pending || readOnly}>
            <legend>
              {direction === "PROJECT_TO_PERSON"
                ? "빈자리 → 개발자"
                : "개발자 → 다음 자리"}
            </legend>
            {direction === "PROJECT_TO_PERSON" ? (
              <>
                <div className={styles.grid}>
                  <label>
                    원천 유형
                    <select
                      data-testid="ai-source-kind"
                      value={kind}
                      onChange={(event) => {
                        sequence.current++;
                        setKind(event.target.value);
                        setSource(null);
                        setError(null);
                        setCriteria({
                          jobCode: "",
                          startYm: options.defaultStartYm,
                        });
                        setFields({});
                      }}
                    >
                      <option value="MANUAL">수동 조건 입력</option>
                      <option value="PROJECT">
                        진행·예정 프로젝트 미충원 인력 계획
                      </option>
                      <option value="OPP">OPEN 영업 기회 필요 인력</option>
                    </select>
                  </label>
                  {kind !== "MANUAL" && (
                    <label>
                      원천
                      <select
                        {...a11y("sourceId")}
                        data-testid="ai-source-select"
                        value={source?.sourceId ?? ""}
                        onChange={(event) =>
                          void chooseSource(event.target.value)
                        }
                      >
                        <option value="">선택</option>
                        {options.sources
                          .filter((item) => item.sourceType === kind)
                          .map((item) => (
                            <option
                              data-testid={`ai-source-option-${item.sourceId}`}
                              key={item.sourceId}
                              value={item.sourceId}
                            >
                              {item.sourceType === "PROJECT"
                                ? `프로젝트 #${item.projectId}`
                                : `필요 인력 #${item.needId}`}{" "}
                              · {item.criteria.jobCode} ·{" "}
                              {item.criteria.startYm} · 잔여{" "}
                              {item.remainingHeadcount}명
                            </option>
                          ))}
                      </select>
                      {fieldError("sourceId")}
                    </label>
                  )}
                  <label>
                    직무 *
                    <select
                      {...a11y("jobCode")}
                      data-testid="ai-job-select"
                      value={criteria.jobCode}
                      onChange={(event) =>
                        change("jobCode", event.target.value)
                      }
                    >
                      <option value="">선택</option>
                      {options.jobCodes.map((code) => (
                        <option
                          data-testid={`ai-job-option-${options.jobCodes.indexOf(code)}`}
                          key={code}
                        >
                          {code}
                        </option>
                      ))}
                    </select>
                    {fieldError("jobCode")}
                  </label>
                  <label>
                    시작월 *
                    <input
                      {...a11y("startYm")}
                      data-testid="ai-start-month"
                      type="month"
                      value={criteria.startYm}
                      onChange={(event) =>
                        change("startYm", event.target.value)
                      }
                    />
                    {fieldError("startYm")}
                  </label>
                  <label>
                    등급
                    <select
                      {...a11y("gradeCode")}
                      data-testid="ai-grade-select"
                      value={criteria.gradeCode ?? ""}
                      onChange={(event) =>
                        change("gradeCode", event.target.value || undefined)
                      }
                    >
                      <option value="">미지정</option>
                      {options.gradeCodes.map((code) => (
                        <option
                          data-testid={`ai-grade-option-${options.gradeCodes.indexOf(code)}`}
                          key={code}
                        >
                          {code}
                        </option>
                      ))}
                    </select>
                    {fieldError("gradeCode")}
                  </label>
                  <label>
                    스킬
                    <select
                      {...a11y("skillCodes")}
                      multiple
                      data-testid="ai-skills-select"
                      value={criteria.skillCodes ?? []}
                      onChange={(event) =>
                        change(
                          "skillCodes",
                          Array.from(
                            event.target.selectedOptions,
                            (item) => item.value,
                          ),
                        )
                      }
                    >
                      {options.skillCodes.map((code) => (
                        <option
                          data-testid={`ai-skill-option-${options.skillCodes.indexOf(code)}`}
                          key={code}
                        >
                          {code}
                        </option>
                      ))}
                    </select>
                    {fieldError("skillCodes")}
                  </label>
                  <label>
                    기간 (개월)
                    <input
                      {...a11y("durationMonths")}
                      data-testid="ai-duration"
                      type="number"
                      min="1"
                      max="36"
                      step="1"
                      value={criteria.durationMonths ?? ""}
                      onChange={(event) =>
                        change(
                          "durationMonths",
                          optionalNumber(event.target.value),
                        )
                      }
                    />
                    {fieldError("durationMonths")}
                  </label>
                  <label>
                    인원 (명)
                    <input
                      {...a11y("headcount")}
                      data-testid="ai-headcount"
                      type="number"
                      min="1"
                      max="100"
                      step="1"
                      value={criteria.headcount ?? ""}
                      onChange={(event) =>
                        change("headcount", optionalNumber(event.target.value))
                      }
                    />
                    {fieldError("headcount")}
                  </label>
                  <label>
                    월 MM
                    <input
                      {...a11y("requestedMm")}
                      data-testid="ai-requested-mm"
                      type="number"
                      min="0.01"
                      max="1"
                      step="0.01"
                      value={criteria.requestedMm ?? ""}
                      onChange={(event) =>
                        change(
                          "requestedMm",
                          optionalNumber(event.target.value),
                        )
                      }
                    />
                    {fieldError("requestedMm")}
                  </label>
                </div>
                {kind !== "MANUAL" &&
                  !options.sources.some((item) => item.sourceType === kind) && (
                    <p className={styles.empty}>
                      선택 가능한 미충원 원천이 없습니다. 수동 조건을 입력할 수
                      있습니다.
                    </p>
                  )}
                {source && (
                  <p role="status">
                    현재 원천 자동 채움 · 잔여 {source.remainingHeadcount}명 ·
                    조건은 수정 가능합니다.
                  </p>
                )}
              </>
            ) : (
              <>
                {options.developers.length === 0 && (
                  <p className={styles.empty}>
                    3개월 내 철수 예정자가 없습니다.
                  </p>
                )}
                <div className={styles.scroll}>
                  <table className={styles.table}>
                    <thead>
                      <tr>
                        <th>선택</th>
                        <th>개발자</th>
                        <th>확정 철수일</th>
                        <th>연장 가능성</th>
                        <th>차기 확정 배정</th>
                      </tr>
                    </thead>
                    <tbody>
                      {options.developers.map((item) => (
                        <tr
                          key={item.devId}
                          data-testid={`ai-exit-row-${item.devId}`}
                        >
                          <td>
                            <input
                              type="radio"
                              {...a11y("devId")}
                              data-testid={`ai-dev-${item.devId}`}
                              aria-label={`개발자 #${item.devId} 선택`}
                              checked={String(item.devId) === devId}
                              onChange={() => {
                                setDevId(String(item.devId));
                                setAssumedExitYm(item.assumedExitYm ?? "");
                                setFields({});
                              }}
                            />
                          </td>
                          <td>개발자 #{item.devId}</td>
                          <td>{item.exitDate ?? "미확정"}</td>
                          <td>
                            <Badge
                              tone={
                                item.extensionLikelihood === "H"
                                  ? "warning"
                                  : item.extensionLikelihood === "L"
                                    ? "success"
                                    : item.extensionLikelihood === "M"
                                      ? "info"
                                      : "neutral"
                              }
                            >
                              {item.extensionLikelihood ?? "미입력"}
                            </Badge>
                          </td>
                          <td>
                            <Badge
                              tone={
                                item.hasNextConfirmedAssignment
                                  ? "warning"
                                  : "neutral"
                              }
                            >
                              {item.hasNextConfirmedAssignment
                                ? "있음"
                                : "없음"}
                            </Badge>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
                {fieldError("devId")}
                {selectedDeveloper?.extensionLikelihood === "H" && (
                  <p className={styles.warning}>
                    연장 가능성 높음, 일정 확인 필요
                  </p>
                )}
                {selectedDeveloper && !selectedDeveloper.exitDate && (
                  <p className={styles.warning}>
                    UNKNOWN_EXIT · 판정 불가 · 미확정 철수면 가정 철수월 필수
                  </p>
                )}
                <div className={styles.grid}>
                  <label>
                    가정 철수월
                    <input
                      {...a11y("assumedExitYm")}
                      data-testid="ai-assumed-exit"
                      type="month"
                      value={assumedExitYm}
                      onChange={(event) => setAssumedExitYm(event.target.value)}
                    />
                    {fieldError("assumedExitYm")}
                  </label>
                  <label>
                    월 MM
                    <input
                      {...a11y("requestedMm")}
                      data-testid="ai-next-mm"
                      type="number"
                      min="0.01"
                      max="1"
                      step="0.01"
                      value={mm}
                      onChange={(event) => setMm(event.target.value)}
                    />
                    {fieldError("requestedMm")}
                  </label>
                </div>
              </>
            )}
          </fieldset>
          <p>
            외부 후보는 내부 개발자 추천에 포함하지 않습니다. 외부 후보
            바로가기는 목적지 확인 전 제공하지 않습니다.
          </p>
          {readOnly ? (
            <Badge tone="locked">읽기 전용 · 추천 실행 불가</Badge>
          ) : (
            <div className={styles.actions}>
              <Button
                type="button"
                data-testid="ai-condition-cancel"
                disabled={pending}
                onClick={() => {
                  setCriteria(
                    source?.criteria ?? {
                      jobCode: "",
                      startYm: options.defaultStartYm,
                    },
                  );
                  setAssumedExitYm("");
                  setMm("");
                  setFields({});
                  setMessage("");
                }}
              >
                조건 편집 취소
              </Button>
              <Button
                type="submit"
                variant="primary"
                data-testid="ai-recommend-submit"
                pending={pending}
              >
                추천 실행
              </Button>
            </div>
          )}
        </form>
      )}
      {message && <p role="status">{message}</p>}
    </section>
  );
}
function optionalNumber(value: string) {
  return value === "" ? undefined : Number(value);
}
function validMm(value: number) {
  return (
    Number.isFinite(value) &&
    value >= 0.01 &&
    value <= 1 &&
    Math.abs(Math.round(value * 100) - value * 100) < 0.000001
  );
}
