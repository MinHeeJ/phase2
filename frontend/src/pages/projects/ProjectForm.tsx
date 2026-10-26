import { useCallback, useEffect, useRef, useState } from "react";
import { Button } from "../../shared/Controls";
import {
  TextInput,
  TextareaInput,
  SelectInput,
  DateInput,
  MoneyInput,
} from "../../shared/Inputs";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { useAdminRead, useAdminWrite } from "../admin/useAdminResource";
import { useCodes } from "../shared/useCodes";
import { getEffectiveParam } from "../shared/api";
import { seoulMonth } from "../shared/PeriodBar";
import { listPartnerOptions } from "../partnersops/client";
import { listSalesPartOptions } from "../salespartsops/client";
import { listDeveloperOptions } from "../developersops/client";
import {
  createProject,
  updateProject,
  emptyProject,
  projectPayload,
  listProjectOptions,
  listProjectContractChanges,
  type ProjectInput,
  type ProjectDetail,
} from "./client";
import styles from "./Projects.module.css";

const customersRead = () => listPartnerOptions("CUST");
const developersRead = () => listDeveloperOptions("");
const budgetRead = () => getEffectiveParam("BUDGET_RATE", seoulMonth());
export function validateProject(
  d: ProjectInput,
  editing: boolean,
  currentEnd?: string,
) {
  const fail = (field: string, message: string) => ({ field, message });
  if (!d.prj_nm.trim() || d.prj_nm.trim().length > 100)
    return fail("prj_nm", "프로젝트명을 100자 이내로 입력하세요.");
  if (!d.cntrct_ty_cd) return fail("cntrct_ty_cd", "계약유형을 골라 주세요.");
  if (d.cntrct_ty_cd !== "IN" && !d.cust_ptnr_id)
    return fail("cust_ptnr_id", "고객사를 골라 주세요.");
  if (!d.cntrct_bgn_dt)
    return fail("cntrct_bgn_dt", "계약 시작일을 입력하세요.");
  if (!d.cntrct_end_dt || d.cntrct_end_dt < d.cntrct_bgn_dt)
    return fail("cntrct_end_dt", "계약 종료일을 확인하세요.");
  if (
    d.prfm_bgn_dt &&
    (d.prfm_bgn_dt < d.cntrct_bgn_dt ||
      d.prfm_bgn_dt > (currentEnd ?? d.cntrct_end_dt))
  )
    return fail("prfm_bgn_dt", "수행 시작일을 확인하세요.");
  if (!editing && d.cntrct_ty_cd !== "IN" && !d.bsn_part_id)
    return fail("bsn_part_id", "영업 파트를 골라 주세요.");
  if (
    d.prj_stts_cd === "PLAN" &&
    d.win_prblt_rt != null &&
    (d.win_prblt_rt < 0 || d.win_prblt_rt > 100)
  )
    return fail("win_prblt_rt", "0~100 사이로 입력하세요.");
  if (d.cntrct_ty_cd !== "IN") {
    if (d.init_cntrct_amt == null || d.init_cntrct_amt < 1)
      return fail("init_cntrct_amt", "계약금액을 1원 이상 입력하세요.");
    if (d.trgt_mrgn_rt != null && (d.trgt_mrgn_rt < 0 || d.trgt_mrgn_rt > 100))
      return fail("trgt_mrgn_rt", "0~100 사이로 입력하세요.");
  }
  return null;
}
export function ProjectForm({
  initial,
  onCancel,
  onSaved,
  onDirtyChange,
}: {
  initial?: ProjectDetail;
  onCancel: () => void;
  onSaved: (row: ProjectDetail, changed: boolean) => void;
  onDirtyChange: (dirty: boolean) => void;
}) {
  const [draft, setDraft] = useState<ProjectInput>(() =>
    projectPayload(initial ?? emptyProject),
  );
  const [budgetInitialized, setBudgetInitialized] = useState(!!initial);
  const [localError, setLocalError] = useState<{
    field: string;
    message: string;
  } | null>(null);
  const form = useRef<HTMLFormElement>(null);
  const types = useCodes("CONTRACT_TYPE"),
    statuses = useCodes("PROJECT_STATUS"),
    settlements = useCodes("SETTLE_METHOD"),
    industries = useCodes("INDUSTRY"),
    skills = useCodes("SKILL");
  const customers = useAdminRead(customersRead),
    parts = useAdminRead(listSalesPartOptions),
    developers = useAdminRead(developersRead),
    previous = useAdminRead(listProjectOptions),
    budget = useAdminRead(budgetRead);
  const contractRead = useCallback(
    () =>
      initial
        ? listProjectContractChanges(initial.prj_id)
        : Promise.resolve({ items: [] }),
    [initial?.prj_id],
  );
  const contracts = useAdminRead(contractRead);
  const locked = !!contracts.data?.items.some(
    (c) => c.cntrct_chg_stts_cd === "CONFIRM",
  );
  const write = useAdminWrite(async () => {});
  const original = initial ?? {
    ...emptyProject,
    trgt_mrgn_rt:
      !budgetInitialized || budget.data?.num_val == null
        ? null
        : 100 - budget.data.num_val,
  };
  const dirty =
    JSON.stringify(projectPayload(draft)) !==
    JSON.stringify(projectPayload(original));
  useEffect(() => {
    onDirtyChange(dirty);
    return () => onDirtyChange(false);
  }, [dirty, onDirtyChange]);
  useEffect(() => {
    if (!initial && budget.data != null) {
      if (budget.data.num_val != null)
        setDraft((d) =>
          d.trgt_mrgn_rt == null
            ? { ...d, trgt_mrgn_rt: 100 - budget.data!.num_val! }
            : d,
        );
      setBudgetInitialized(true);
    }
  }, [budget.data, initial]);
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
  const error = localError ?? write.error;
  useEffect(() => {
    if (error?.field)
      form.current
        ?.querySelector<HTMLElement>(
          `[name="${error.field.replace(/[^a-z_]/g, "")}"]`,
        )
        ?.focus();
  }, [error]);
  const change = <K extends keyof ProjectInput>(
    key: K,
    value: ProjectInput[K],
  ) => {
    setLocalError(null);
    write.clear();
    setDraft((d) => {
      const next = { ...d, [key]: value };
      if (key === "cntrct_ty_cd")
        next.stlm_mthd_cd =
          value === "IN" ? null : value === "TK" ? "STAGE" : "MONTH";
      if (
        (key === "init_cntrct_amt" || key === "trgt_mrgn_rt") &&
        next.init_cntrct_amt != null &&
        next.trgt_mrgn_rt != null &&
        Number.isSafeInteger(next.init_cntrct_amt) &&
        next.trgt_mrgn_rt >= 0 &&
        next.trgt_mrgn_rt <= 100
      ) {
        const amount =
          initial && next.init_cntrct_amt === initial.init_cntrct_amt
            ? (initial.cur_cntrct_amt ?? next.init_cntrct_amt)
            : next.init_cntrct_amt;
        next.trgt_cst_amt = Number(
          (BigInt(amount) *
            BigInt(Math.round((100 - next.trgt_mrgn_rt) * 100))) /
            10000n,
        );
      }
      return next;
    });
  };
  const fieldError = (key: string) =>
    error?.field === key ? error.message : null;
  const text = (
    key: "prj_nm" | "duty_fld_nm",
    label: string,
    maxLength: number,
    required = false,
  ) => (
    <TextInput
      name={key}
      label={label}
      value={draft[key] ?? ""}
      maxLength={maxLength}
      required={required}
      disabled={write.pending}
      error={fieldError(key)}
      onValueChange={(v) => change(key, v)}
    />
  );
  const select = (
    key: "cntrct_ty_cd" | "prj_stts_cd" | "stlm_mthd_cd" | "indty_cd",
    label: string,
    source: ReturnType<typeof useCodes>,
    allowed?: string[],
  ) => {
    const options = source.codes
      .filter((c) => !allowed || allowed.includes(c.code))
      .map((c) => ({ value: c.code, label: c.name }));
    const value = draft[key];
    if (value && !options.some((o) => o.value === value))
      options.push({ value, label: value });
    return (
      <SelectInput
        name={key}
        label={label}
        value={value ?? ""}
        options={options}
        disabled={
          write.pending ||
          (key === "stlm_mthd_cd" && draft.cntrct_ty_cd === "TK")
        }
        error={fieldError(key)}
        onValueChange={(v) => change(key, v)}
      />
    );
  };
  const money = (
    key: "quot_amt" | "init_cntrct_amt" | "trgt_cst_amt",
    label: string,
  ) => (
    <MoneyInput
      name={key}
      label={label}
      value={draft[key]}
      readOnly={locked && key === "init_cntrct_amt"}
      disabled={write.pending}
      required={key === "init_cntrct_amt"}
      error={fieldError(key)}
      onValueChange={(v) => change(key, v)}
    />
  );
  const percent = (key: "win_prblt_rt" | "trgt_mrgn_rt", label: string) => (
    <label className={styles.numberField}>
      {label}
      <input
        data-testid={`project-${key.replaceAll("_", "-")}`}
        aria-label={label}
        name={key}
        type="number"
        min="0"
        max="100"
        step="0.01"
        value={draft[key] ?? ""}
        onChange={(e) =>
          change(key, e.target.value === "" ? null : Number(e.target.value))
        }
      />
      {fieldError(key) && <span role="alert">{fieldError(key)}</span>}
    </label>
  );
  const sources = [types, statuses, settlements, industries, skills];
  const reads = [customers, parts, developers, previous, budget, contracts];
  const unavailable =
    !budgetInitialized ||
    sources.some((s) => s.loading || !!s.error) ||
    reads.some((s) => s.loading || !!s.error);
  return (
    <form
      ref={form}
      data-testid="project-form"
      className={styles.form}
      noValidate
      onSubmit={(e) => {
        e.preventDefault();
        const invalid = validateProject(
          draft,
          !!initial,
          initial && draft.cntrct_end_dt === initial.cntrct_end_dt
            ? initial.cur_end_dt
            : undefined,
        );
        setLocalError(invalid);
        if (invalid) return;
        if (initial && !dirty) {
          onSaved(initial, false);
          return;
        }
        let saved: ProjectDetail;
        void write.save(
          async () => {
            saved = initial
              ? await updateProject(initial.prj_id, draft)
              : await createProject(draft);
          },
          () => onSaved(saved, true),
        );
      }}
    >
      <ErrorBanner error={write.error} onRetry={() => void write.recover()} />
      {sources.map((s, i) => (
        <ErrorBanner key={`code-${i}`} error={s.error} onRetry={s.retry} />
      ))}
      {reads.map((s, i) => (
        <ErrorBanner
          key={`read-${i}`}
          error={s.error}
          onRetry={() => void s.reload().catch(() => {})}
        />
      ))}
      {initial && (
        <section className={styles.card}>
          <h2>현재 계약 정보</h2>
          <div className={styles.fields}>
            {(
              [
                ["prj_no", "프로젝트ID"],
                ["cur_cntrct_amt", "현재 계약금액"],
                ["cur_end_dt", "현재 종료일"],
                ["cntrct_mm_qty", "계약 MM"],
              ] as const
            ).map(([key, label]) => (
              <TextInput
                key={key}
                name={key}
                label={label}
                value={String(initial[key] ?? "–")}
                readOnly
                onValueChange={() => {}}
              />
            ))}
          </div>
        </section>
      )}
      <section className={styles.card}>
        <h2>기본 정보</h2>
        <div className={styles.fields}>
          {text("prj_nm", "프로젝트명", 100, true)}
          {draft.cntrct_ty_cd !== "IN" && (
            <SelectInput
              name="cust_ptnr_id"
              label="고객사"
              value={draft.cust_ptnr_id?.toString() ?? ""}
              required
              options={[
                ...(customers.data ?? []).map((c) => ({
                  value: c.id,
                  label: c.name,
                })),
                ...(initial?.cust_ptnr_id &&
                !customers.data?.some(
                  (c) => Number(c.id) === initial.cust_ptnr_id,
                )
                  ? [
                      {
                        value: String(initial.cust_ptnr_id),
                        label: initial.custName ?? String(initial.cust_ptnr_id),
                      },
                    ]
                  : []),
              ]}
              error={fieldError("cust_ptnr_id")}
              onValueChange={(v) =>
                change("cust_ptnr_id", v ? Number(v) : null)
              }
            />
          )}
          {select("cntrct_ty_cd", "계약유형", types)}
          {select(
            "prj_stts_cd",
            "상태",
            statuses,
            initial ? ["PLAN", "RUN", "HOLD", "END"] : ["PLAN", "RUN"],
          )}
          {draft.prj_stts_cd === "PLAN" && percent("win_prblt_rt", "수주 확률")}
          {(
            [
              ["cntrct_dt", "계약일"],
              ["cntrct_bgn_dt", "계약 시작일"],
              ["cntrct_end_dt", "계약 종료일"],
              ["prfm_bgn_dt", "수행 시작일"],
            ] as const
          ).map(([key, label]) => (
            <DateInput
              key={key}
              name={key}
              label={label}
              value={draft[key] ?? ""}
              required={key === "cntrct_bgn_dt" || key === "cntrct_end_dt"}
              readOnly={locked && key === "cntrct_end_dt"}
              error={fieldError(key)}
              onValueChange={(v) => change(key, v)}
            />
          ))}
          {draft.cntrct_ty_cd !== "IN" && (
            <>
              {initial ? (
                <TextInput
                  name="bsn_part_id"
                  label="영업 파트"
                  value={initial.partName ?? "미지정"}
                  readOnly
                  onValueChange={() => {}}
                />
              ) : (
                <SelectInput
                  name="bsn_part_id"
                  label="영업 파트"
                  value={draft.bsn_part_id?.toString() ?? ""}
                  required
                  options={(parts.data ?? []).map((p) => ({
                    value: p.id,
                    label: p.hq ? "본사(직접 영업)" : p.name,
                  }))}
                  error={fieldError("bsn_part_id")}
                  onValueChange={(v) =>
                    change("bsn_part_id", v ? Number(v) : null)
                  }
                />
              )}
              <p className={styles.help}>
                본사가 직접 영업했으면 「본사(직접 영업)」 — 파트 이익금
                정산에서 빠집니다. 내부(자체 개발)는 이 칸이 없습니다.
              </p>
              {select(
                "stlm_mthd_cd",
                "정산 방식",
                settlements,
                draft.cntrct_ty_cd === "TK"
                  ? ["STAGE"]
                  : ["MONTH", "DAY", "HOUR"],
              )}
            </>
          )}
          <SelectInput
            name="pm_dev_id"
            label="PM"
            value={draft.pm_dev_id?.toString() ?? ""}
            options={[
              ...(developers.data ?? []).map((d) => ({
                value: String(d.id),
                label: d.name,
              })),
              ...(initial?.pm_dev_id &&
              !developers.data?.some((d) => d.id === initial.pm_dev_id)
                ? [
                    {
                      value: String(initial.pm_dev_id),
                      label: initial.pmName ?? String(initial.pm_dev_id),
                    },
                  ]
                : []),
            ]}
            error={fieldError("pm_dev_id")}
            onValueChange={(v) => change("pm_dev_id", v ? Number(v) : null)}
          />
          <label>
            <input
              data-testid="project-free-support"
              type="checkbox"
              checked={draft.free_sprt_yn === "Y"}
              onChange={(e) =>
                change("free_sprt_yn", e.target.checked ? "Y" : "N")
              }
            />
            무상지원
          </label>
        </div>
      </section>
      {draft.cntrct_ty_cd !== "IN" && (
        <section className={styles.card}>
          <h2>금액</h2>
          <div className={styles.fields}>
            {money("quot_amt", "견적 금액")}
            {money("init_cntrct_amt", initial ? "최초 계약금액" : "계약금액")}
            {percent("trgt_mrgn_rt", "목표 마진율")}
            {money("trgt_cst_amt", "목표 원가")}
            <p className={styles.help}>
              ⓘ 목표 원가 = 현재 계약금액 × (100 − 목표 마진율) ÷ 100, 원 단위
              버림. 직접 수정할 수 있습니다.
            </p>
            {locked && (
              <p className={styles.help}>
                확정 계약 변경이 있어 최초 계약금액·계약 종료일은 수정할 수
                없습니다.
              </p>
            )}
          </div>
        </section>
      )}
      <section className={styles.card}>
        <h2>기타</h2>
        <div className={styles.fields}>
          {select("indty_cd", "업종", industries)}
          {text("duty_fld_nm", "업무 분야", 100)}
          <fieldset className={styles.multiple}>
            <legend>기술 스택</legend>
            {[
              ...skills.codes.map((c) => ({ code: c.code, name: c.name })),
              ...draft.prj_skl
                .filter((c) => !skills.codes.some((s) => s.code === c))
                .map((code) => ({ code, name: code })),
            ].map((c) => (
              <label key={c.code}>
                <input
                  data-testid={`project-skill-${c.code.toLowerCase()}`}
                  type="checkbox"
                  checked={draft.prj_skl.includes(c.code)}
                  onChange={(e) =>
                    change(
                      "prj_skl",
                      e.target.checked
                        ? [...draft.prj_skl, c.code]
                        : draft.prj_skl.filter((v) => v !== c.code),
                    )
                  }
                />
                {c.name}
              </label>
            ))}
            {fieldError("prj_skl") && (
              <span role="alert">{fieldError("prj_skl")}</span>
            )}
          </fieldset>
          <SelectInput
            name="prev_prj_id"
            label="선행 프로젝트"
            value={draft.prev_prj_id?.toString() ?? ""}
            options={(previous.data ?? [])
              .filter((p) => p.id !== initial?.prj_id)
              .map((p) => ({
                value: String(p.id),
                label: `${p.no} · ${p.name}`,
              }))}
            error={fieldError("prev_prj_id")}
            onValueChange={(v) => change("prev_prj_id", v ? Number(v) : null)}
          />
          <TextareaInput
            name="rmrk_desc"
            label="비고"
            maxLength={500}
            value={draft.rmrk_desc ?? ""}
            error={fieldError("rmrk_desc")}
            onValueChange={(v) => change("rmrk_desc", v)}
          />
        </div>
      </section>
      <div className={styles.actions}>
        <Button
          data-testid="project-cancel"
          type="button"
          disabled={write.pending}
          onClick={onCancel}
        >
          취소
        </Button>
        <Button
          data-testid="project-save"
          type="submit"
          variant="primary"
          pending={write.pending}
          disabled={unavailable || write.uncertain}
        >
          저장
        </Button>
      </div>
    </form>
  );
}
