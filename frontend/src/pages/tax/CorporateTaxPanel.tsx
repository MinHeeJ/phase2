import { useEffect, useState } from "react";
import { Badge, Button, LockedBanner } from "../../shared/Controls";
import { ApiFailure } from "../../shared/api";
import { Can } from "../../shared/Can";
import { formatMoney, isDate } from "../../lib/format";
import { useDeveloperReadOnly } from "../developers/useDeveloperReadOnly";
import tables from "../developers/Developers.module.css";
import {
  getTaxPlan,
  getTaxPayments,
  saveTaxPlan,
  saveTaxPayments,
  type TaxPlan,
  type TaxPayments,
  type TaxPayment,
} from "./client";

const money = (value: number | null | undefined) =>
  value == null ? "–" : formatMoney(BigInt(value));
const validMoney = (value: string) =>
  /^\d+$/.test(value) && Number.isSafeInteger(Number(value));
const label = (type: string) => (type === "FINAL" ? "확정 신고" : "중간예납");

export function CorporateTaxPanel({
  year,
  month,
  readOnly = false,
  onSaved,
}: {
  year: number;
  month: string;
  readOnly?: boolean;
  onSaved?: () => void | Promise<void>;
}) {
  const mobile = useDeveloperReadOnly();
  const [plan, setPlan] = useState<TaxPlan | null>(null);
  const [payments, setPayments] = useState<TaxPayments | null>(null);
  const [loaded, setLoaded] = useState<number | null>(null);
  const [revision, setRevision] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [writeError, setWriteError] = useState("");
  const [success, setSuccess] = useState("");
  const [annual, setAnnual] = useState("");
  const [reason, setReason] = useState("");
  const [pending, setPending] = useState(false);
  const [uncertain, setUncertain] = useState(false);
  const [draft, setDraft] = useState<TaxPayment | null>(null);
  const [planned, setPlanned] = useState("");
  const [actual, setActual] = useState("");
  useEffect(() => {
    let active = true;
    setLoading(true);
    setError("");
    setWriteError("");
    setDraft(null);
    setSuccess("");
    setUncertain(false);
    Promise.all([
      getTaxPlan(year).catch((failure) => {
        if (failure instanceof ApiFailure && failure.status === 404)
          return null;
        throw failure;
      }),
      getTaxPayments(year),
    ])
      .then(([nextPlan, nextPayments]) => {
        if (active) {
          setPlan(nextPlan);
          setPayments(nextPayments);
          setAnnual(nextPlan ? String(nextPlan.annual_amount) : "");
          setReason("");
          setLoaded(year);
        }
      })
      .catch((failure: Error) => {
        if (active) setError(failure.message);
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [year, revision]);
  useEffect(() => {
    setDraft(null);
    setWriteError("");
    setSuccess("");
  }, [month, readOnly]);
  const current = loaded === year && !loading && !error;
  const selected = plan?.months.find((row) => row.month === month);
  const closed = !!selected?.closed;
  const locked = readOnly || closed || plan?.editable === false;
  const disabled = pending || uncertain || !current || locked;
  const saveAnnual = async () => {
    if (disabled) return;
    if (!validMoney(annual) || !reason.trim() || reason.length > 500) {
      setWriteError("0 이상의 원 단위 정수와 변경 사유를 입력하세요.");
      return;
    }
    setPending(true);
    setWriteError("");
    setSuccess("");
    try {
      setPlan(await saveTaxPlan(year, Number(annual), reason));
      setReason("");
      setSuccess("예상액을 저장했습니다. 미마감 월할을 다시 계산했습니다.");
      await onSaved?.();
    } catch (failure) {
      setWriteError((failure as Error).message);
      setUncertain(failure instanceof ApiFailure && failure.uncertainWrite);
    } finally {
      setPending(false);
    }
  };
  const edit = (row?: TaxPayment) => {
    if (!payments || disabled) return;
    const suggestion = payments.suggestions.find(
      (item) => !payments.locked_types.includes(item.tax_pay_se_cd),
    );
    if (!row && !suggestion) return;
    const value = row ?? {
      tax_pay_se_cd: suggestion!.tax_pay_se_cd,
      planned_date: suggestion!.suggested_date,
      planned_amount: suggestion!.suggested_amount,
      actual_date: null,
      actual_amount: null,
    };
    setDraft({ ...value });
    setPlanned(
      value.planned_amount == null ? "" : String(value.planned_amount),
    );
    setActual(value.actual_amount == null ? "" : String(value.actual_amount));
    setWriteError("");
    setSuccess("");
  };
  const savePayment = async () => {
    if (!draft || disabled) return;
    if (
      !isDate(draft.planned_date) ||
      !validMoney(planned) ||
      !!draft.actual_date !== (actual !== "") ||
      (actual !== "" &&
        (!validMoney(actual) || !isDate(draft.actual_date ?? "")))
    ) {
      setWriteError(
        "예정일과 0 이상의 예정 금액을 입력하고, 실제 납부일·금액은 함께 입력하세요.",
      );
      return;
    }
    setPending(true);
    setWriteError("");
    setSuccess("");
    try {
      setPayments(
        await saveTaxPayments(year, [
          {
            ...draft,
            planned_amount: Number(planned),
            actual_date: draft.actual_date || null,
            actual_amount: actual === "" ? null : Number(actual),
          },
        ]),
      );
      setDraft(null);
      setSuccess("납부 일정·실적을 저장했습니다.");
      await onSaved?.();
    } catch (failure) {
      setWriteError((failure as Error).message);
      setUncertain(failure instanceof ApiFailure && failure.uncertainWrite);
    } finally {
      setPending(false);
    }
  };
  if (mobile)
    return (
      <section data-testid="corporate-tax-panel">
        <h2>PC에서 이용해 주세요</h2>
        <p>재무 항목·법인세는 큰 표를 다루는 화면입니다.</p>
      </section>
    );
  return (
    <section
      className={tables.card}
      data-testid="corporate-tax-panel"
      aria-label="법인세"
    >
      <h2>
        법인세{" "}
        <small>{year}년 · 1년 예상액을 달마다 나눠 순이익표에 반영</small>
      </h2>
      {loading && <p role="status">법인세를 불러오는 중입니다.</p>}
      {error && (
        <p role="alert">
          {error}{" "}
          <Button
            data-testid="tax-retry"
            onClick={() => setRevision((v) => v + 1)}
          >
            다시 조회
          </Button>
        </p>
      )}
      {current && (
        <>
          {readOnly ? (
            <LockedBanner>과거 기간은 조회만 가능합니다.</LockedBanner>
          ) : closed ? (
            <LockedBanner>마감된 월은 조회만 가능합니다.</LockedBanner>
          ) : (
            plan?.editable === false && (
              <LockedBanner>마감 상태와 월할 스냅샷을 확인하세요.</LockedBanner>
            )
          )}
          {!plan && (
            <p role="status">연간 예상 법인세가 아직 입력되지 않았습니다.</p>
          )}
          {plan?.months.some((row) => row.warning) && (
            <p role="alert">
              마감 월할 자료가 없거나 예상액이 마감 합계보다 작습니다. 원천을
              확인하세요. 자료 없음은 –로 표시합니다.
            </p>
          )}
          <div className={tables.form}>
            <div className={tables.search}>
              <label>
                연간 예상 법인세{" "}
                <input
                  data-testid="tax-annual"
                  aria-label="연간 예상 법인세"
                  inputMode="numeric"
                  value={annual}
                  disabled={disabled}
                  onChange={(e) => setAnnual(e.target.value)}
                />{" "}
                원
              </label>
              <label>
                이번 달 법인세{" "}
                <input
                  data-testid="tax-current-amount"
                  aria-label="이번 달 법인세"
                  readOnly
                  value={money(selected?.amount)}
                />
              </label>
              <Badge tone={closed ? "locked" : "neutral"}>
                {closed ? "마감 확정" : "계산"}
              </Badge>
            </div>
            <p className={tables.help}>
              세무 담당 예상액. 바꾸면 마감된 달은 그대로 두고, 남은 달에 차이를
              나눠 반영합니다. 나머지 원은 마지막 미마감 월에 반영합니다.
            </p>
            {!locked && (
              <Can perm="UI-10.update">
                <label>
                  변경 사유{" "}
                  <textarea
                    data-testid="tax-reason"
                    aria-label="변경 사유"
                    maxLength={500}
                    value={reason}
                    disabled={disabled}
                    onChange={(e) => setReason(e.target.value)}
                  />
                </label>
                <div className={tables.actions}>
                  <Button
                    data-testid="tax-plan-save"
                    variant="primary"
                    pending={pending}
                    disabled={disabled}
                    onClick={() => void saveAnnual()}
                  >
                    예상액 저장
                  </Button>
                </div>
              </Can>
            )}
          </div>
          <p className={tables.help}>
            순이익표에는 매달 나눈 금액(월할)이, 자금수지표에는 실제로 내는
            달에만 잡힙니다. 확정 신고 예상액 = 연간 예상 법인세 − 중간예납.
          </p>
          <h3>월별 법인세</h3>
          <div className={tables.tableScroll}>
            <table className={tables.table}>
              <thead>
                <tr>
                  <th>월</th>
                  <th>법인세(월할)</th>
                  <th>상태</th>
                </tr>
              </thead>
              <tbody>
                {!plan?.months.length ? (
                  <tr>
                    <td colSpan={3}>
                      예상액을 입력하면 월할을 조회할 수 있습니다.
                    </td>
                  </tr>
                ) : (
                  plan.months.map((row) => (
                    <tr key={row.month} data-testid={`tax-month-${row.month}`}>
                      <td>{row.month}</td>
                      <td className={tables.amount}>{money(row.amount)}</td>
                      <td>
                        <Badge tone={row.closed ? "locked" : "info"}>
                          {row.closed ? "마감 확정" : "작업중"}
                        </Badge>
                        {row.warning && " 자료 없음"}
                      </td>
                    </tr>
                  ))
                )}
              </tbody>
            </table>
          </div>
          <div className={tables.toolbar}>
            <h3>납부 일정·실적</h3>
            {!locked && (
              <Can perm="UI-10.update">
                <Button
                  data-testid="tax-payment-add"
                  disabled={
                    disabled ||
                    !payments?.suggestions.some(
                      (s) => !payments.locked_types.includes(s.tax_pay_se_cd),
                    )
                  }
                  onClick={() => edit()}
                >
                  납부 일정 추가
                </Button>
              </Can>
            )}
          </div>
          <div className={tables.tableScroll}>
            <table className={tables.table}>
              <thead>
                <tr>
                  <th>납부일</th>
                  <th>구분</th>
                  <th>금액</th>
                  <th>실제 납부일</th>
                  <th>실제 납부액</th>
                  <th>상태</th>
                  <th>작업</th>
                </tr>
              </thead>
              <tbody>
                {!payments?.payments.length ? (
                  <tr>
                    <td colSpan={7}>등록된 납부 일정이 없습니다.</td>
                  </tr>
                ) : (
                  payments.payments.map((row) => (
                    <tr
                      key={row.tax_pay_id}
                      data-testid={`tax-payment-${row.tax_pay_id}`}
                    >
                      <td>{row.planned_date}</td>
                      <td>
                        {year}년 {label(row.tax_pay_se_cd)}
                      </td>
                      <td className={tables.amount}>
                        {money(row.planned_amount)}
                      </td>
                      <td>{row.actual_date ?? "–"}</td>
                      <td className={tables.amount}>
                        {money(row.actual_amount)}
                      </td>
                      <td>
                        <Badge tone={row.actual_date ? "success" : "info"}>
                          {row.actual_date ? "납부" : "예정"}
                        </Badge>
                      </td>
                      <td>
                        {!locked &&
                          !payments.locked_types.includes(
                            row.tax_pay_se_cd,
                          ) && (
                            <Can perm="UI-10.update">
                              <Button
                                data-testid={`tax-payment-edit-${row.tax_pay_id}`}
                                disabled={disabled}
                                onClick={() => edit(row)}
                              >
                                수정
                              </Button>
                            </Can>
                          )}
                      </td>
                    </tr>
                  ))
                )}
              </tbody>
            </table>
          </div>
          {draft && !locked && (
            <div className={tables.form} data-testid="tax-payment-form">
              <h3>납부 일정·실적 입력</h3>
              <div className={tables.search}>
                <label>
                  납부 구분{" "}
                  <select
                    data-testid="tax-payment-type"
                    aria-label="납부 구분"
                    disabled={pending || uncertain || !!draft.tax_pay_id}
                    value={draft.tax_pay_se_cd}
                    onChange={(e) => {
                      const suggestion = payments!.suggestions.find(
                        (row) => row.tax_pay_se_cd === e.target.value,
                      )!;
                      setDraft({
                        ...draft,
                        tax_pay_se_cd: suggestion.tax_pay_se_cd,
                        planned_date: suggestion.suggested_date,
                      });
                      setPlanned(
                        suggestion.suggested_amount == null
                          ? ""
                          : String(suggestion.suggested_amount),
                      );
                    }}
                  >
                    {payments?.suggestions.map((s) => (
                      <option
                        key={s.tax_pay_se_cd}
                        disabled={payments.locked_types.includes(
                          s.tax_pay_se_cd,
                        )}
                        value={s.tax_pay_se_cd}
                      >
                        {label(s.tax_pay_se_cd)}
                      </option>
                    ))}
                  </select>
                </label>
                <label>
                  예정 납부일{" "}
                  <input
                    data-testid="tax-payment-planned-date"
                    aria-label="예정 납부일"
                    type="date"
                    value={draft.planned_date}
                    disabled={pending || uncertain}
                    onChange={(e) =>
                      setDraft({ ...draft, planned_date: e.target.value })
                    }
                  />
                </label>
                <label>
                  예정 금액{" "}
                  <input
                    data-testid="tax-payment-planned-amount"
                    aria-label="예정 금액"
                    inputMode="numeric"
                    value={planned}
                    disabled={pending || uncertain}
                    onChange={(e) => setPlanned(e.target.value)}
                  />
                </label>
                <label>
                  실제 납부일{" "}
                  <input
                    data-testid="tax-payment-actual-date"
                    aria-label="실제 납부일"
                    type="date"
                    value={draft.actual_date ?? ""}
                    disabled={pending || uncertain}
                    onChange={(e) =>
                      setDraft({ ...draft, actual_date: e.target.value })
                    }
                  />
                </label>
                <label>
                  실제 납부액{" "}
                  <input
                    data-testid="tax-payment-actual-amount"
                    aria-label="실제 납부액"
                    inputMode="numeric"
                    value={actual}
                    disabled={pending || uncertain}
                    onChange={(e) => setActual(e.target.value)}
                  />
                </label>
              </div>
              <p className={tables.help}>
                3월 확정 신고·8월 중간예납은 기본 제안입니다. 실제 예정일은
                별도로 입력하세요. 실적은 지급 관리에서 후보를 생성하고 수령
                계좌를 확인한 후 동일 지급 완료 경로로 기록합니다. 완료
                취소·부분 지급은 지급 관리에서 처리하세요.
              </p>
              <div className={tables.actions}>
                <Button
                  data-testid="tax-payment-cancel"
                  disabled={pending}
                  onClick={() => setDraft(null)}
                >
                  취소
                </Button>
                <Button
                  data-testid="tax-payment-save"
                  variant="primary"
                  pending={pending}
                  disabled={disabled}
                  onClick={() => void savePayment()}
                >
                  납부 저장
                </Button>
              </div>
            </div>
          )}
          <h3>예상액 변경 이력</h3>
          <div className={tables.tableScroll}>
            <table className={tables.table}>
              <thead>
                <tr>
                  <th>변경 일시</th>
                  <th>변경 전</th>
                  <th>변경 후</th>
                  <th>사유</th>
                </tr>
              </thead>
              <tbody>
                {!plan?.history.length ? (
                  <tr>
                    <td colSpan={4}>변경 이력이 없습니다.</td>
                  </tr>
                ) : (
                  plan.history.map((row, index) => (
                    <tr
                      key={`${row.changed_at}-${index}`}
                      data-testid={`tax-history-${index}`}
                    >
                      <td>{row.changed_at}</td>
                      <td>{money(row.before_amount)}</td>
                      <td>{money(row.after_amount)}</td>
                      <td>{row.reason}</td>
                    </tr>
                  ))
                )}
              </tbody>
            </table>
          </div>
        </>
      )}
      {writeError && <p role="alert">{writeError}</p>}
      {uncertain && (
        <p role="status">
          처리 결과를 다시 조회한 후 저장하세요.{" "}
          <Button
            data-testid="tax-recover"
            onClick={() => setRevision((v) => v + 1)}
          >
            처리 결과 조회
          </Button>
        </p>
      )}
      {success && <p role="status">{success}</p>}
    </section>
  );
}
