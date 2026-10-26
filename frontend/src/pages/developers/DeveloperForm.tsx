import { useEffect, useRef, useState } from "react";
import { Button, Badge } from "../../shared/Controls";
import {
  TextInput,
  TextareaInput,
  SelectInput,
  DateInput,
  MoneyInput,
} from "../../shared/Inputs";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { useCodes } from "../shared/useCodes";
import { useAdminRead, useAdminWrite } from "../admin/useAdminResource";
import { listPartnerOptions } from "../partnersops/client";
import { listSalesPartOptions } from "../salespartsops/client";
import {
  createDeveloper,
  updateDeveloper,
  developerPayload,
  emptyDeveloper,
  type DeveloperInput,
  type DeveloperDetail,
} from "./client";
import styles from "./Developers.module.css";

const readPartners = () => listPartnerOptions("VEND");
export function useDeveloperCodes() {
  const employees = useCodes("EMP_TYPE"),
    positions = useCodes("POSITION"),
    duties = useCodes("WORK_TYPE"),
    grades = useCodes("GRADE"),
    jobs = useCodes("JOB"),
    skills = useCodes("SKILL");
  return {
    EMP_TYPE: employees,
    POSITION: positions,
    WORK_TYPE: duties,
    GRADE: grades,
    JOB: jobs,
    SKILL: skills,
  };
}
export function validateDeveloper(d: DeveloperInput, editing: boolean) {
  const fail = (field: string, message: string) => ({ field, message });
  if (!d.dev_nm.trim()) return fail("dev_nm", "이름을 입력해 주세요.");
  if (!d.emp_se_cd) return fail("emp_se_cd", "직원구분을 골라 주세요.");
  if (d.emp_se_cd === "OS" && !d.vndr_ptnr_id)
    return fail("vndr_ptnr_id", "외주는 협력사를 골라 주세요.");
  if (!d.main_job_cd) return fail("main_job_cd", "주 직무를 골라 주세요.");
  if (!editing && d.mng_no && !/^[A-Z0-9-]{1,20}$/.test(d.mng_no))
    return fail(
      "mng_no",
      "관리번호는 영문 대문자·숫자·하이픈 1~20자로 입력하세요.",
    );
  if (d.email_addr && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(d.email_addr))
    return fail("email_addr", "이메일 형식이 맞지 않습니다.");
  return null;
}
export function DeveloperForm({
  initial,
  onCancel,
  onSaved,
  onDirtyChange,
  reload,
}: {
  initial?: DeveloperDetail;
  onCancel: () => void;
  onSaved: (row: DeveloperDetail | null, changed: boolean) => void;
  onDirtyChange: (dirty: boolean) => void;
  reload: () => Promise<unknown>;
}) {
  const [draft, setDraft] = useState<DeveloperInput>(() =>
    developerPayload(initial ?? emptyDeveloper),
  );
  const [localError, setLocalError] = useState<{
    field: string;
    message: string;
  } | null>(null);
  const form = useRef<HTMLFormElement>(null),
    groups = useDeveloperCodes();
  const partners = useAdminRead(readPartners),
    parts = useAdminRead(listSalesPartOptions),
    write = useAdminWrite(reload);
  const normalized = developerPayload(draft),
    original = developerPayload(initial ?? emptyDeveloper);
  const dirty = JSON.stringify(normalized) !== JSON.stringify(original);
  useEffect(() => {
    onDirtyChange(dirty);
    return () => onDirtyChange(false);
  }, [dirty, onDirtyChange]);
  const error = localError ?? write.error;
  useEffect(() => {
    if (error?.field)
      form.current
        ?.querySelector<HTMLElement>(
          `[name="${error.field.replace(/[^a-z_]/g, "")}"]`,
        )
        ?.focus();
  }, [error]);
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
  const change = <K extends keyof DeveloperInput>(
    key: K,
    value: DeveloperInput[K],
  ) => {
    setLocalError(null);
    write.clear();
    setDraft((d) => ({
      ...d,
      [key]: value,
      ...(key === "emp_se_cd" ? { vndr_ptnr_id: null } : {}),
      ...(key === "main_job_cd"
        ? { sub_job: d.sub_job.filter((c) => c !== value) }
        : {}),
    }));
  };
  const fieldError = (key: string) =>
    error?.field === key ? error.message : null;
  const select = (
    key: "emp_se_cd" | "pstn_cd" | "duty_se_cd" | "grd_cd" | "main_job_cd",
    label: string,
    group: keyof typeof groups,
    required = false,
  ) => {
    const source = groups[group];
    const options = source.codes.map((c) => ({ value: c.code, label: c.name }));
    const value = draft[key];
    if (value && !options.some((c) => c.value === value))
      options.push({
        value,
        label: initial?.code_names?.[`${group}:${value}`] ?? value,
      });
    return (
      <SelectInput
        name={key}
        label={label}
        value={value ?? ""}
        required={required}
        disabled={write.pending || source.loading}
        options={options}
        onValueChange={(v) => change(key, v)}
        error={fieldError(key)}
      />
    );
  };
  const text = (
    key: "dev_nm" | "mng_no" | "tel_no" | "email_addr",
    label: string,
    maxLength: number,
    required = false,
  ) => (
    <TextInput
      name={key}
      label={label}
      maxLength={maxLength}
      required={required}
      readOnly={key === "mng_no" && !!initial}
      disabled={write.pending}
      value={draft[key] ?? ""}
      onValueChange={(v) => change(key, v)}
      error={fieldError(key)}
    />
  );
  const multiple = (
    key: "sub_job" | "skill",
    label: string,
    group: "JOB" | "SKILL",
  ) => {
    const options = groups[group].codes.filter(
      (c) => key !== "sub_job" || c.code !== draft.main_job_cd,
    );
    const missing = draft[key]
      .filter((c) => !options.some((o) => o.code === c))
      .map((code) => ({
        code,
        name: initial?.code_names?.[`${group}:${code}`] ?? code,
      }));
    return (
      <fieldset className={styles.multiple} disabled={write.pending}>
        <legend>{label}</legend>
        {[...options, ...missing].map((c) => (
          <label
            key={c.code}
            data-testid={`developer-${key.replace("_", "-")}-label-${c.code.toLowerCase().replaceAll("_", "-")}`}
          >
            <input
              data-testid={`developer-${key.replace("_", "-")}-${c.code.toLowerCase()}`}
              name={key}
              type="checkbox"
              checked={draft[key].includes(c.code)}
              onChange={(e) =>
                change(
                  key,
                  e.target.checked
                    ? [...draft[key], c.code]
                    : draft[key].filter((v) => v !== c.code),
                )
              }
            />
            {c.name}
          </label>
        ))}
        {draft[key].map((c) => (
          <Badge key={c}>
            {groups[group].codes.find((o) => o.code === c)?.name ??
              initial?.code_names?.[`${group}:${c}`] ??
              c}
          </Badge>
        ))}
        {fieldError(key) && <span role="alert">{fieldError(key)}</span>}
      </fieldset>
    );
  };
  const submit = () => {
    const invalid = validateDeveloper(draft, !!initial);
    setLocalError(invalid);
    if (invalid) return;
    if (initial && !dirty) {
      onSaved(initial, false);
      return;
    }
    let saved: DeveloperDetail | null = null,
      changed = true;
    void write.save(
      async () => {
        if (initial) {
          const result = await updateDeveloper(initial.dev_id, draft);
          saved = result.developer;
          changed = result.changed;
        } else saved = await createDeveloper(draft);
      },
      () => onSaved(saved, changed),
    );
  };
  const sources = Object.values(groups),
    unavailable =
      sources.some((g) => g.loading || !!g.error) ||
      partners.loading ||
      parts.loading ||
      !!partners.error ||
      !!parts.error;
  return (
    <form
      ref={form}
      data-testid="developer-form"
      className={styles.form}
      noValidate
      onSubmit={(e) => {
        e.preventDefault();
        submit();
      }}
    >
      <ErrorBanner error={write.error} onRetry={() => void write.recover()} />
      {sources.map((g, i) => (
        <ErrorBanner key={i} error={g.error} onRetry={g.retry} />
      ))}
      <ErrorBanner
        error={partners.error}
        onRetry={() => void partners.reload().catch(() => {})}
      />
      <ErrorBanner
        error={parts.error}
        onRetry={() => void parts.reload().catch(() => {})}
      />
      <section className={styles.card}>
        <h2>기본 정보</h2>
        <div className={styles.fields}>
          {text("dev_nm", "이름", 50, true)}
          {text("mng_no", "관리번호", 20)}
          <p className={styles.help}>
            {initial
              ? "다른 자료와 연결되어 있어 바꿀 수 없습니다."
              : "비우면 저장할 때 자동으로 붙습니다"}
          </p>
          {select("emp_se_cd", "직원구분", "EMP_TYPE", true)}
          {draft.emp_se_cd === "OS" ? (
            <SelectInput
              name="vndr_ptnr_id"
              label="소속"
              required
              value={draft.vndr_ptnr_id?.toString() ?? ""}
              disabled={write.pending || partners.loading}
              options={(partners.data ?? []).map((p) => ({
                value: p.id,
                label: p.name,
              }))}
              onValueChange={(v) =>
                change("vndr_ptnr_id", v ? Number(v) : null)
              }
              error={fieldError("vndr_ptnr_id")}
            />
          ) : (
            <TextInput
              name="affiliation"
              label="소속"
              value="자사"
              readOnly
              onValueChange={() => {}}
            />
          )}
          {select("pstn_cd", "직위", "POSITION")}
          {select("duty_se_cd", "업무 구분", "WORK_TYPE")}
          {select("grd_cd", "등급", "GRADE")}
          {select("main_job_cd", "주 직무", "JOB", true)}
          {multiple("sub_job", "보조 직무", "JOB")}
          {multiple("skill", "스킬", "SKILL")}
          <SelectInput
            name="bsn_part_id"
            label="영업 파트"
            value={draft.bsn_part_id?.toString() ?? ""}
            disabled={write.pending || parts.loading}
            options={(parts.data ?? []).map((p) => ({
              value: p.id,
              label: p.name,
            }))}
            onValueChange={(v) => change("bsn_part_id", v ? Number(v) : null)}
            error={fieldError("bsn_part_id")}
          />
          <DateInput
            name="hire_dt"
            label="입사일"
            value={draft.hire_dt ?? ""}
            onValueChange={(v) => change("hire_dt", v)}
            error={fieldError("hire_dt")}
            disabled={write.pending}
          />
          {text("tel_no", "연락처", 30)}
          {text("email_addr", "이메일", 100)}
          <TextareaInput
            name="rmrk_desc"
            label="비고"
            maxLength={500}
            value={draft.rmrk_desc ?? ""}
            onValueChange={(v) => change("rmrk_desc", v)}
            error={fieldError("rmrk_desc")}
            disabled={write.pending}
          />
        </div>
      </section>
      <section className={styles.card}>
        <h2>금액</h2>
        <MoneyInput
          name="dflt_untprc_amt"
          label="월 단가"
          value={draft.dflt_untprc_amt}
          onValueChange={(v) => change("dflt_untprc_amt", v)}
          error={fieldError("dflt_untprc_amt")}
          disabled={write.pending}
        />
      </section>
      <div className={styles.actions}>
        <Button
          type="button"
          data-testid="developer-cancel"
          onClick={onCancel}
          disabled={write.pending}
        >
          취소
        </Button>
        <Button
          data-testid="developer-save"
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
