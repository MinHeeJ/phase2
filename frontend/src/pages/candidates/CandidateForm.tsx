import { useEffect, useRef, useState } from "react";
import { Button, ConfirmDialog } from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { useCodes } from "../shared/useCodes";
import { useAdminRead, useAdminWrite } from "../admin/useAdminResource";
import { listPartnerOptions } from "../partnersops/client";
import {
  candidatePayload,
  createCandidate,
  updateCandidate,
  emptyCandidate,
  type CandidateInput,
  type CandidateDetail,
} from "./client";
import pool from "../developers/Developers.module.css";
import styles from "./Candidates.module.css";

const vendors = () => listPartnerOptions("VEND");
export function CandidateForm({
  initial,
  onSaved,
  onCancel,
  onDirtyChange,
}: {
  initial?: CandidateDetail;
  onSaved: (row: CandidateDetail) => Promise<unknown>;
  onCancel: () => void;
  onDirtyChange?: (value: boolean) => void;
}) {
  const [draft, setDraft] = useState<CandidateInput>(() =>
    candidatePayload(initial ?? emptyCandidate),
  );
  const [local, setLocal] = useState<{ field: string; message: string } | null>(
    null,
  );
  const [discard, setDiscard] = useState(false);
  const jobs = useCodes("JOB"),
    grades = useCodes("GRADE"),
    skills = useCodes("SKILL"),
    types = useCodes("EMP_TYPE"),
    statuses = useCodes("CONTACT_STATUS"),
    sources = useCodes("CANDIDATE_SOURCE");
  const partners = useAdminRead(vendors);
  const dirty =
    JSON.stringify(candidatePayload(draft)) !==
    JSON.stringify(candidatePayload(initial ?? emptyCandidate));
  useEffect(() => {
    onDirtyChange?.(dirty);
    return () => onDirtyChange?.(false);
  }, [dirty, onDirtyChange]);
  const savedRow = useRef<CandidateDetail | null>(null);
  const write = useAdminWrite(async () => {
    if (savedRow.current) await onSaved(savedRow.current);
  });
  const error = local ?? write.error;
  const change = <K extends keyof CandidateInput>(
    key: K,
    value: CandidateInput[K],
  ) => {
    setDraft((d) => ({ ...d, [key]: value }));
    setLocal(null);
  };
  const fieldError = (key: string) =>
    error?.field === key ? <span role="alert">{error.message}</span> : null;
  const save = async () => {
    for (const [field, message] of [
      ["cand_nm", "이름을 입력해 주세요."],
      ["job_cd", "직무를 골라 주세요."],
      ["avail_dt", "가능일을 입력해 주세요."],
    ] as const) {
      if (!draft[field].trim()) {
        setLocal({ field, message });
        return;
      }
    }
    if (
      draft.hope_untprc_amt == null ||
      !Number.isSafeInteger(draft.hope_untprc_amt) ||
      draft.hope_untprc_amt < 0
    ) {
      setLocal({
        field: "hope_untprc_amt",
        message: "희망단가는 0 이상의 정수로 입력하세요.",
      });
      return;
    }
    if (draft.cand_se_cd === "OS" && !draft.vndr_ptnr_id) {
      setLocal({
        field: "vndr_ptnr_id",
        message: "외주는 사용 중인 협력사를 골라 주세요.",
      });
      return;
    }
    await write.save(
      async () => {
        savedRow.current = initial
          ? await updateCandidate(initial.cand_id, draft)
          : await createCandidate(draft);
      },
      () => {},
    );
  };
  const cancel = () => (dirty ? setDiscard(true) : onCancel());
  return (
    <section data-testid="candidate-editor" className={pool.card}>
      <h2>{initial ? "후보 수정" : "후보 등록"}</h2>
      <ErrorBanner
        error={write.error}
        onRetry={write.uncertain ? write.recover : undefined}
      />
      {[jobs, grades, skills, types, statuses, sources].map((group, i) => (
        <ErrorBanner key={i} error={group.error} onRetry={group.retry} />
      ))}
      <ErrorBanner
        error={partners.error}
        onRetry={() => void partners.reload().catch(() => {})}
      />
      {initial && (
        <p>
          후보번호 {initial.cand_no}{" "}
          {initial.dev_id && `· 연결 개발자 ${initial.dev_id}`}
        </p>
      )}
      <form
        data-testid="candidate-form"
        className={styles.form}
        onSubmit={(e) => {
          e.preventDefault();
          void save();
        }}
        noValidate
      >
        <div className={pool.fields}>
          <label>
            이름
            <input
              data-testid="candidate-name"
              name="cand_nm"
              value={draft.cand_nm}
              maxLength={50}
              onChange={(e) => change("cand_nm", e.target.value)}
            />
            {fieldError("cand_nm")}
          </label>
          <label>
            구분
            <select
              data-testid="candidate-type"
              value={draft.cand_se_cd}
              onChange={(e) =>
                setDraft((d) => ({
                  ...d,
                  cand_se_cd: e.target.value,
                  vndr_ptnr_id: e.target.value === "FL" ? null : d.vndr_ptnr_id,
                }))
              }
            >
              {types.codes
                .filter((c) => ["FL", "OS"].includes(c.code))
                .map((c) => (
                  <option
                    key={c.code}
                    value={c.code}
                    data-testid={`candidate-type-option-${c.code.toLowerCase().replaceAll("_", "-")}`}
                  >
                    {c.name}
                  </option>
                ))}
            </select>
            {fieldError("cand_se_cd")}
          </label>
          <label>
            업체
            <select
              data-testid="candidate-vendor"
              value={draft.vndr_ptnr_id ?? ""}
              disabled={draft.cand_se_cd !== "OS"}
              onChange={(e) =>
                change(
                  "vndr_ptnr_id",
                  e.target.value ? Number(e.target.value) : null,
                )
              }
            >
              <option value="">선택</option>
              {partners.data?.map((p) => (
                <option
                  key={p.id}
                  value={p.id}
                  data-testid={`candidate-vendor-option-${p.id}`}
                >
                  {p.name}
                </option>
              ))}
            </select>
            {fieldError("vndr_ptnr_id")}
          </label>
          <label>
            직무
            <select
              data-testid="candidate-job"
              value={draft.job_cd}
              onChange={(e) => change("job_cd", e.target.value)}
            >
              <option value="">선택</option>
              {jobs.codes.map((c) => (
                <option key={c.code} value={c.code}>
                  {c.name}
                </option>
              ))}
            </select>
            {fieldError("job_cd")}
          </label>
          <label>
            등급
            <select
              data-testid="candidate-grade"
              value={draft.grd_cd ?? ""}
              onChange={(e) => change("grd_cd", e.target.value || null)}
            >
              <option value="">선택</option>
              {grades.codes.map((c) => (
                <option key={c.code} value={c.code}>
                  {c.name}
                </option>
              ))}
            </select>
            {fieldError("grd_cd")}
          </label>
          <label>
            희망단가(월)
            <input
              data-testid="candidate-price"
              type="number"
              min="0"
              step="1"
              value={draft.hope_untprc_amt ?? ""}
              onChange={(e) =>
                change(
                  "hope_untprc_amt",
                  e.target.value === "" ? null : Number(e.target.value),
                )
              }
            />
            {fieldError("hope_untprc_amt")}
          </label>
          <label>
            가능일
            <input
              data-testid="candidate-available"
              type="date"
              value={draft.avail_dt}
              onChange={(e) => change("avail_dt", e.target.value)}
            />
            {fieldError("avail_dt")}
          </label>
          <label>
            상태
            <select
              data-testid="candidate-status"
              value={draft.cntct_stts_cd}
              disabled={
                !!initial &&
                (initial.contacts.length > 0 || initial.dev_id != null)
              }
              onChange={(e) => change("cntct_stts_cd", e.target.value)}
            >
              {statuses.codes.map((c) => (
                <option key={c.code} value={c.code}>
                  {c.name}
                </option>
              ))}
            </select>
            {fieldError("cntct_stts_cd")}
          </label>
          <label>
            연락처
            <input
              data-testid="candidate-phone"
              maxLength={30}
              value={draft.tel_no ?? ""}
              onChange={(e) => change("tel_no", e.target.value)}
            />
            {fieldError("tel_no")}
          </label>
          <label>
            출처
            <select
              data-testid="candidate-source"
              value={draft.source ?? ""}
              onChange={(e) => change("source", e.target.value || null)}
            >
              <option value="">선택</option>
              {sources.codes.map((c) => (
                <option key={c.code} value={c.code}>
                  {c.name}
                </option>
              ))}
            </select>
            {fieldError("source")}
          </label>
        </div>
        <fieldset className={pool.multiple}>
          <legend>스킬</legend>
          {skills.codes.map((c) => (
            <label key={c.code}>
              <input
                data-testid={`candidate-skill-${c.code.toLowerCase().replaceAll("_", "-")}`}
                type="checkbox"
                checked={draft.skill.includes(c.code)}
                onChange={(e) =>
                  change(
                    "skill",
                    e.target.checked
                      ? [...draft.skill, c.code]
                      : draft.skill.filter((v) => v !== c.code),
                  )
                }
              />
              {c.name}
            </label>
          ))}
          {fieldError("skill")}
        </fieldset>
        <label>
          메모
          <textarea
            data-testid="candidate-memo"
            value={draft.rmrk_desc ?? ""}
            onChange={(e) => change("rmrk_desc", e.target.value)}
          />
          {fieldError("rmrk_desc")}
        </label>
        {initial && (initial.contacts.length > 0 || initial.dev_id != null) && (
          <p>현재 상태는 최신 연락 기록 또는 개발자 전환 결과로 관리합니다.</p>
        )}
        <div className={pool.actions}>
          <Button
            data-testid="candidate-cancel"
            type="button"
            disabled={write.pending}
            onClick={cancel}
          >
            취소
          </Button>
          <Button
            data-testid="candidate-save"
            type="submit"
            variant="primary"
            pending={write.pending}
            disabled={write.uncertain}
          >
            저장
          </Button>
        </div>
      </form>
      {discard && (
        <ConfirmDialog
          title="미저장 변경"
          confirmLabel="나가기"
          cancelLabel="계속 작성"
          onConfirm={onCancel}
          onCancel={() => setDiscard(false)}
        >
          저장하지 않은 내용을 버리시겠습니까?
        </ConfirmDialog>
      )}
    </section>
  );
}
