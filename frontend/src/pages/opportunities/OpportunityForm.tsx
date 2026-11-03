import { useEffect, useRef, useState } from "react";
import { Button, ConfirmDialog } from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { useAdminRead } from "../admin/useAdminResource";
import { ApiFailure } from "../../shared/api";
import { useCodes } from "../shared/useCodes";
import { getEffectiveParam } from "../shared/api";
import { seoulMonth } from "../shared/PeriodBar";
import { listPartnerOptions } from "../partnersops/client";
import { listSalesPartOptions } from "../salespartsops/client";
import {
  createOpportunity,
  updateOpportunity,
  getOpportunity,
  opportunityPayload,
  opportunityStages,
  type OpportunityDetail,
  type OpportunityWrite,
  type OpportunityNeedInput,
} from "./client";
import {
  opportunityRevenue,
  opportunityDifferenceRate,
  opportunityMonthEnd,
} from "./quote";
import styles from "./Opportunities.module.css";

const customersRead = () => listPartnerOptions("CUST");
export function OpportunityForm({
  initial,
  onSaved,
  onCancel,
  onDirtyChange,
}: {
  initial?: OpportunityDetail;
  onSaved: (row: OpportunityDetail) => void;
  onCancel: () => void;
  onDirtyChange?: (dirty: boolean) => void;
}) {
  const [draft, setDraft] = useState<OpportunityWrite>(() =>
    initial
      ? opportunityPayload(initial)
      : {
          oppt_nm: "",
          cust_ptnr_id: null,
          bsn_part_id: null,
          owner_user_id: null,
          bsn_stg_cd: "DISC",
          win_prblt_rt: 10,
          expc_amt: 0,
          cntrct_ty_cd: "",
          bgn_ym: seoulMonth(),
          mth_cnt: 6,
          next_action: null,
          next_action_dt: null,
          rmrk_desc: null,
          needs: [],
        },
  );
  const original = useRef(JSON.stringify(draft));
  const dirty = original.current !== JSON.stringify(draft);
  const [error, setError] = useState<ApiFailure | null>(null),
    [pending, setPending] = useState(false),
    [confirm, setConfirm] = useState(false),
    [uncertain, setUncertain] = useState(false);
  const busy = useRef(false),
    form = useRef<HTMLFormElement>(null);
  const jobs = useCodes("JOB"),
    grades = useCodes("GRADE"),
    types = useCodes("CONTRACT_TYPE");
  const customers = useAdminRead(customersRead),
    parts = useAdminRead(listSalesPartOptions);
  useEffect(() => {
    onDirtyChange?.(dirty);
    return () => onDirtyChange?.(false);
  }, [dirty, onDirtyChange]);
  useEffect(() => {
    const guard = (e: BeforeUnloadEvent) => {
      if (dirty) {
        e.preventDefault();
        e.returnValue = "";
      }
    };
    window.addEventListener("beforeunload", guard);
    return () => window.removeEventListener("beforeunload", guard);
  }, [dirty]);
  const change = <K extends keyof OpportunityWrite>(
    key: K,
    value: OpportunityWrite[K],
  ) => setDraft((d) => ({ ...d, [key]: value }));
  const updateNeed = (index: number, patch: Partial<OpportunityNeedInput>) =>
    change(
      "needs",
      draft.needs.map((n, i) => (i === index ? { ...n, ...patch } : n)),
    );
  const revenue = opportunityRevenue(draft.needs),
    difference =
      revenue == null
        ? null
        : revenue -
          BigInt(Number.isSafeInteger(draft.expc_amt) ? draft.expc_amt : 0);
  const leave = () => (dirty ? setConfirm(true) : onCancel());
  const fieldError = (key: string) =>
    error?.field === key ? error.message : undefined;
  const renderError = (key: string) =>
    fieldError(key) ? (
      <span role="alert" className={styles.fieldError}>
        {fieldError(key)}
      </span>
    ) : null;
  const save = async () => {
    if (busy.current || uncertain) return;
    if (!form.current?.reportValidity()) return;
    const invalidPrice = draft.needs.findIndex(
      (n) => n.expc_untprc_amt == null || n.expc_untprc_amt <= 0,
    );
    if (invalidPrice >= 0) {
      setError(
        new ApiFailure(
          "최종 단가를 1원 이상 입력하세요.",
          400,
          null,
          `needs[${invalidPrice}].expc_untprc_amt`,
        ),
      );
      return;
    }
    if (
      difference !== null &&
      difference !== 0n &&
      !draft.amount_difference_reason?.trim()
    ) {
      setError(
        new ApiFailure(
          "기회 예상금액과 견적 매출의 불일치 사유를 입력하세요.",
          400,
          null,
          "amount_difference_reason",
        ),
      );
      return;
    }
    busy.current = true;
    setPending(true);
    setError(null);
    let committed = false;
    try {
      const saved = initial
        ? await updateOpportunity(initial.oppt_id, opportunityPayload(draft))
        : await createOpportunity(opportunityPayload(draft));
      committed = true;
      const fresh = await getOpportunity(saved.oppt_id);
      onDirtyChange?.(false);
      onSaved(fresh);
    } catch (e) {
      const failure =
        e instanceof ApiFailure
          ? e
          : new ApiFailure("저장하지 못했습니다.", null, null);
      const result = committed
        ? new ApiFailure(
            "저장 후 조회에 실패했습니다. 현재 상태를 확인하세요.",
            failure.status,
            failure.requestId,
            failure.field,
            failure.code,
            true,
          )
        : failure;
      setError(result);
      setUncertain(result.uncertainWrite);
    } finally {
      busy.current = false;
      setPending(false);
    }
  };
  if (initial && initial.oppt_stts_cd !== "OPEN")
    return <p role="status">종료된 기회는 수정할 수 없습니다.</p>;
  return (
    <section data-testid="opportunity-editor" className={styles.editor}>
      <header className={styles.actions}>
        <h1>{initial ? "영업 기회 수정" : "영업 기회 등록"}</h1>
        <Button data-testid="opportunity-editor-list" onClick={leave}>
          목록
        </Button>
      </header>
      <ErrorBanner error={error} />
      {error?.field && (
        <p role="alert" className={styles.fieldError}>
          {error.field}: {error.message}
        </p>
      )}
      {[customers.error, parts.error, jobs.error, grades.error, types.error]
        .filter(Boolean)
        .map((e, i) => (
          <ErrorBanner key={i} error={e} />
        ))}
      <form
        ref={form}
        className={styles.form}
        onSubmit={(e) => {
          e.preventDefault();
          void save();
        }}
      >
        <fieldset disabled={pending}>
          <section className={styles.card}>
            <h2>기본 정보</h2>
            <div className={styles.fields}>
              {initial && (
                <label>
                  기회 번호
                  <input
                    data-testid="opportunity-number"
                    value={initial.oppt_no}
                    readOnly
                  />
                </label>
              )}
              <label>
                기회명 *
                <input
                  data-testid="opportunity-name"
                  required
                  maxLength={200}
                  value={draft.oppt_nm}
                  aria-invalid={!!fieldError("oppt_nm")}
                  onChange={(e) => change("oppt_nm", e.target.value)}
                />
                {renderError("oppt_nm")}
              </label>
              <label>
                고객사 *
                <select
                  data-testid="opportunity-customer"
                  required
                  value={draft.cust_ptnr_id ?? ""}
                  onChange={(e) =>
                    change("cust_ptnr_id", Number(e.target.value) || null)
                  }
                >
                  <option value="">선택</option>
                  {customers.data?.map((c) => (
                    <option key={c.id} value={c.id}>
                      {c.name}
                    </option>
                  ))}
                </select>
                {renderError("cust_ptnr_id")}
              </label>
              <label>
                영업 파트{draft.cntrct_ty_cd !== "IN" ? " *" : ""}
                <select
                  data-testid="opportunity-part"
                  required={draft.cntrct_ty_cd !== "IN"}
                  value={draft.bsn_part_id ?? ""}
                  onChange={(e) =>
                    change("bsn_part_id", Number(e.target.value) || null)
                  }
                >
                  <option value="">선택</option>
                  {parts.data?.map((p) => (
                    <option key={p.id} value={p.id}>
                      {p.hq ? "본사(직접 영업)" : p.name}
                    </option>
                  ))}
                </select>
                {renderError("bsn_part_id")}
              </label>
              <label>
                담당자
                <input
                  data-testid="opportunity-owner"
                  readOnly
                  value={
                    draft.owner_user_id == null
                      ? "미지정"
                      : String(draft.owner_user_id)
                  }
                />
              </label>
              <label>
                단계
                <select
                  data-testid="opportunity-stage"
                  value={draft.bsn_stg_cd}
                  onChange={(e) => {
                    const stage = e.target.value;
                    change("bsn_stg_cd", stage);
                    void getEffectiveParam(`STAGE_PROB_${stage}`, seoulMonth())
                      .then((p) => {
                        if (p.num_val != null)
                          setDraft((d) =>
                            d.bsn_stg_cd === stage
                              ? { ...d, win_prblt_rt: p.num_val! }
                              : d,
                          );
                      })
                      .catch((e) => setError(e));
                  }}
                >
                  {opportunityStages.map((s) => (
                    <option key={s.code} value={s.code}>
                      {s.name}
                    </option>
                  ))}
                </select>
                {renderError("bsn_stg_cd")}
              </label>
              <label>
                수주 확률 (%)
                <input
                  data-testid="opportunity-probability"
                  type="number"
                  min={0}
                  max={100}
                  step="0.01"
                  required
                  value={draft.win_prblt_rt}
                  onChange={(e) =>
                    change("win_prblt_rt", Number(e.target.value))
                  }
                />
                {renderError("win_prblt_rt")}
              </label>
              <label>
                예상 금액 (원) *
                <input
                  data-testid="opportunity-amount"
                  type="number"
                  min={0}
                  step={1}
                  required
                  value={draft.expc_amt}
                  onChange={(e) => change("expc_amt", Number(e.target.value))}
                />
                {renderError("expc_amt")}
              </label>
              <label>
                계약유형 *
                <select
                  data-testid="opportunity-contract-type"
                  required
                  value={draft.cntrct_ty_cd}
                  onChange={(e) => change("cntrct_ty_cd", e.target.value)}
                >
                  <option value="">선택</option>
                  {types.codes.map((t) => (
                    <option key={t.code} value={t.code}>
                      {t.name}
                    </option>
                  ))}
                </select>
              </label>
              <label>
                시작 예정월 *
                <input
                  data-testid="opportunity-start"
                  type="month"
                  required
                  value={draft.bgn_ym}
                  onChange={(e) => change("bgn_ym", e.target.value)}
                />
                {renderError("bgn_ym")}
              </label>
              <label>
                예상 기간 (개월) *
                <input
                  data-testid="opportunity-duration"
                  type="number"
                  min={1}
                  max={120}
                  step={1}
                  required
                  value={draft.mth_cnt}
                  onChange={(e) => change("mth_cnt", Number(e.target.value))}
                />
                {renderError("mth_cnt")}
              </label>
            </div>
          </section>
          <section className={styles.card}>
            <h2>다음 할 일</h2>
            <div className={styles.fields}>
              <label>
                할 일
                <input
                  data-testid="opportunity-next-action"
                  maxLength={200}
                  value={draft.next_action ?? ""}
                  onChange={(e) => change("next_action", e.target.value)}
                />
              </label>
              <label>
                기한
                <input
                  data-testid="opportunity-next-date"
                  type="date"
                  value={draft.next_action_dt ?? ""}
                  onChange={(e) =>
                    change("next_action_dt", e.target.value || null)
                  }
                />
              </label>
            </div>
          </section>
          <section className={styles.card}>
            <div className={styles.actions}>
              <h2>필요 인력</h2>
              <Button
                data-testid="opportunity-add-need"
                type="button"
                onClick={() =>
                  change("needs", [
                    ...draft.needs,
                    {
                      job_cd: "",
                      grd_cd: null,
                      psn_cnt: 1,
                      bgn_ym: draft.bgn_ym,
                      mth_cnt: draft.mth_cnt,
                      mth_mm_qty: 1,
                      expc_untprc_amt: null,
                      rmrk_desc: null,
                    },
                  ])
                }
              >
                행 추가
              </Button>
            </div>
            <div className={styles.tableScroll}>
              <table className={styles.table}>
                <thead>
                  <tr>
                    {[
                      "직무 *",
                      "등급",
                      "인원 *",
                      "시작월 *",
                      "개월 *",
                      "월 MM *",
                      "예상 단가(원)",
                      "비고",
                      "삭제",
                    ].map((h) => (
                      <th key={h}>{h}</th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {draft.needs.length === 0 && (
                    <tr>
                      <td colSpan={9}>필요 인력 행을 추가해 주세요.</td>
                    </tr>
                  )}
                  {draft.needs.map((n, i) => (
                    <tr
                      key={n.need_id ?? `new-${i}`}
                      data-testid={`opportunity-need-${i}`}
                    >
                      <td>
                        <select
                          data-testid={`need-job-${i}`}
                          aria-label={`${i + 1}번째 직무`}
                          required
                          value={n.job_cd}
                          onChange={(e) => {
                            const code = e.target.value;
                            const option = jobs.codes.find(
                              (j) => j.code === code,
                            );
                            const price =
                              option?.attr1 && /^[0-9]+$/.test(option.attr1)
                                ? Number(option.attr1)
                                : 0;
                            updateNeed(i, {
                              job_cd: code,
                              expc_untprc_amt: n.expc_untprc_amt ?? price,
                            });
                          }}
                        >
                          <option value="">선택</option>
                          {jobs.codes.map((j) => (
                            <option key={j.code} value={j.code}>
                              {j.name}
                            </option>
                          ))}
                        </select>
                      </td>
                      <td>
                        <select
                          data-testid={`need-grade-${i}`}
                          aria-label={`${i + 1}번째 등급`}
                          value={n.grd_cd ?? ""}
                          onChange={(e) =>
                            updateNeed(i, { grd_cd: e.target.value || null })
                          }
                        >
                          <option value="">미지정</option>
                          {grades.codes.map((g) => (
                            <option key={g.code} value={g.code}>
                              {g.name}
                            </option>
                          ))}
                        </select>
                      </td>
                      <td>
                        <input
                          data-testid={`need-people-${i}`}
                          aria-label={`${i + 1}번째 인원`}
                          type="number"
                          min={1}
                          max={999}
                          step={1}
                          required
                          value={n.psn_cnt}
                          onChange={(e) =>
                            updateNeed(i, { psn_cnt: Number(e.target.value) })
                          }
                        />
                      </td>
                      <td>
                        <input
                          data-testid={`need-start-${i}`}
                          aria-label={`${i + 1}번째 시작월`}
                          type="month"
                          required
                          value={n.bgn_ym}
                          onChange={(e) =>
                            updateNeed(i, { bgn_ym: e.target.value })
                          }
                        />
                      </td>
                      <td>
                        <input
                          data-testid={`need-months-${i}`}
                          aria-label={`${i + 1}번째 개월`}
                          type="number"
                          min={1}
                          max={120}
                          step={1}
                          required
                          value={n.mth_cnt}
                          onChange={(e) =>
                            updateNeed(i, { mth_cnt: Number(e.target.value) })
                          }
                        />
                      </td>
                      <td>
                        <input
                          data-testid={`need-mm-${i}`}
                          aria-label={`${i + 1}번째 월 MM`}
                          type="number"
                          min="0.01"
                          max={1}
                          step="0.01"
                          required
                          value={n.mth_mm_qty}
                          onChange={(e) =>
                            updateNeed(i, {
                              mth_mm_qty: Number(e.target.value),
                            })
                          }
                        />
                      </td>
                      <td>
                        <input
                          data-testid={`need-price-${i}`}
                          aria-label={`${i + 1}번째 예상 단가`}
                          type="number"
                          min={1}
                          step={1}
                          required
                          value={n.expc_untprc_amt ?? ""}
                          onChange={(e) =>
                            updateNeed(i, {
                              expc_untprc_amt:
                                e.target.value === ""
                                  ? null
                                  : Number(e.target.value),
                            })
                          }
                        />
                        {!n.expc_untprc_amt && (
                          <span className={styles.fieldError}>입력 필요</span>
                        )}
                        {renderError(`needs[${i}].expc_untprc_amt`)}
                      </td>
                      <td>
                        <input
                          data-testid={`need-remark-${i}`}
                          aria-label={`${i + 1}번째 비고`}
                          maxLength={200}
                          value={n.rmrk_desc ?? ""}
                          onChange={(e) =>
                            updateNeed(i, { rmrk_desc: e.target.value })
                          }
                        />
                      </td>
                      <td>
                        <Button
                          data-testid={`need-remove-${i}`}
                          type="button"
                          onClick={() =>
                            change(
                              "needs",
                              draft.needs.filter((_, j) => i !== j),
                            )
                          }
                        >
                          {i + 1}번째 줄 삭제
                        </Button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            {draft.needs.some(
              (n) =>
                n.bgn_ym < draft.bgn_ym ||
                opportunityMonthEnd(n.bgn_ym, n.mth_cnt) >
                  opportunityMonthEnd(draft.bgn_ym, draft.mth_cnt),
            ) && (
              <p role="status" className={styles.warning}>
                필요 인력 기간이 기회 기간 밖입니다. 경고 후 저장하며 실제 필요
                기간으로 예측합니다.
              </p>
            )}
          </section>
          <section className={styles.card}>
            <h2>견적 이익 점검</h2>
            <dl className={styles.quote}>
              <div>
                <dt>견적 매출</dt>
                <dd>
                  {revenue == null
                    ? "단가 입력 필요"
                    : `${revenue.toLocaleString("ko-KR")}원`}
                </dd>
              </div>
              <div>
                <dt>기회 예상금액과 차이</dt>
                <dd>
                  {difference?.toLocaleString("ko-KR") ?? "N/A"}원 /{" "}
                  {difference == null
                    ? "N/A"
                    : opportunityDifferenceRate(difference, draft.expc_amt)}
                </dd>
              </div>
              <div>
                <dt>원가·이익률·최소/목표단가·손익분기액</dt>
                <dd>저장 시 최신 원천으로 재검증</dd>
              </div>
            </dl>
            <p className={styles.warning}>
              개인 원가 API에는 팀비·경비 원천 전체가 제공되지 않아 원가
              미리보기는 미확인입니다. 저장 후 서버 견적을 확인하세요. 원가
              미확인은 이익률과 무관하게 별도 승인이 필요합니다.
            </p>
            {difference !== 0n && (
              <label>
                금액 불일치 사유{difference != null ? " *" : ""}
                <textarea
                  data-testid="opportunity-difference-reason"
                  required={difference != null && difference !== 0n}
                  value={draft.amount_difference_reason ?? ""}
                  aria-invalid={!!fieldError("amount_difference_reason")}
                  onChange={(e) =>
                    change("amount_difference_reason", e.target.value)
                  }
                />
                {renderError("amount_difference_reason")}
              </label>
            )}
          </section>
          <section className={styles.card}>
            <h2>비고</h2>
            <label>
              비고
              <textarea
                data-testid="opportunity-remark"
                value={draft.rmrk_desc ?? ""}
                onChange={(e) => change("rmrk_desc", e.target.value)}
              />
            </label>
          </section>
        </fieldset>
        <footer>
          <Button
            data-testid="opportunity-cancel"
            type="button"
            onClick={leave}
            disabled={pending}
          >
            취소
          </Button>
          <Button
            data-testid="opportunity-save"
            type="submit"
            variant="primary"
            pending={pending}
            disabled={
              uncertain ||
              customers.loading ||
              parts.loading ||
              jobs.loading ||
              grades.loading ||
              types.loading
            }
          >
            저장
          </Button>
        </footer>
      </form>
      {confirm && (
        <ConfirmDialog
          title="저장하지 않은 내용이 있습니다"
          confirmLabel="나가기"
          cancelLabel="계속 작성"
          onConfirm={onCancel}
          onCancel={() => setConfirm(false)}
        >
          나가면 입력한 내용이 사라집니다.
        </ConfirmDialog>
      )}
    </section>
  );
}
