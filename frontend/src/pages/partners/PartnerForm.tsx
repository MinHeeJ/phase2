import { useEffect, useRef, useState } from "react";
import { Button, ConfirmDialog } from "../../shared/Controls";
import { TextInput, SelectInput, TextareaInput } from "../../shared/Inputs";
import { ErrorBanner } from "../../shared/ErrorBanner";

import { useCodes } from "../shared/useCodes";
import { useAdminWrite } from "../admin/useAdminResource";
import {
  createPartner,
  updatePartner,
  partnerPayload,
  type PartnerInput,
  type PartnerDetail,
} from "./client";
import styles from "./Partners.module.css";

export const emptyPartner: PartnerInput = {
  ptnr_se_cd: "",
  ptnr_nm: "",
  biz_reg_no: "",
  ceo_nm: "",
  indty_cd: "",
  chrg_nm: "",
  chrg_tel_no: "",
  rmrk_desc: "",
  txinv_email_addr: "",
  bank_cd: "",
  acnt_no: "",
  dpstr_nm: "",
};
export function formatBusinessNumber(value: string) {
  const digits = value.replace(/\D/g, "").slice(0, 10);
  return (
    digits.slice(0, 3) +
    (digits.length > 3 ? `-${digits.slice(3, 5)}` : "") +
    (digits.length > 5 ? `-${digits.slice(5)}` : "")
  );
}
export function validatePartner(
  d: PartnerInput,
  editing: boolean,
): { field: string; message: string } | null {
  const fail = (field: string, message: string) => ({ field, message });
  if (!d.ptnr_se_cd) return fail("ptnr_se_cd", "구분을 골라 주세요.");
  if (!d.ptnr_nm.trim()) return fail("ptnr_nm", "상호를 입력해 주세요.");
  if (!editing && !/^\d{3}-\d{2}-\d{5}$/.test(d.biz_reg_no))
    return fail(
      "biz_reg_no",
      "사업자번호 10자리를 입력해 주세요. (예: 123-45-67890)",
    );
  if (d.ptnr_se_cd !== "VEND") {
    if (!d.txinv_email_addr?.trim())
      return fail(
        "txinv_email_addr",
        "고객사는 세금계산서 이메일이 꼭 필요합니다.",
      );
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(d.txinv_email_addr))
      return fail(
        "txinv_email_addr",
        "이메일 형식이 맞지 않습니다. (예: tax@company.co.kr)",
      );
  }
  if (d.ptnr_se_cd !== "CUST") {
    if (!d.bank_cd) return fail("bank_cd", "은행을 골라 주세요.");
    if (!/^[0-9-]{8,20}$/.test(d.acnt_no ?? ""))
      return fail("acnt_no", "계좌번호를 숫자와 하이픈(-)으로 입력해 주세요.");
    if (!d.dpstr_nm?.trim()) return fail("dpstr_nm", "예금주를 입력해 주세요.");
  }
  return null;
}
export function PartnerForm({
  initial,
  onCancel,
  onSaved,
  onDirtyChange,
  reload,
}: {
  initial?: PartnerDetail;
  onCancel: () => void;
  onSaved: (row: PartnerDetail | null) => void;
  onDirtyChange: (dirty: boolean) => void;
  reload: () => Promise<unknown>;
}) {
  const [draft, setDraft] = useState<PartnerInput>(
    initial ? partnerPayload(initial) : emptyPartner,
  );
  const [localError, setLocalError] = useState<{
    field: string;
    message: string;
  } | null>(null);
  const [accountConfirm, setAccountConfirm] = useState(false);
  const form = useRef<HTMLFormElement>(null);
  const kinds = useCodes("PARTNER_KIND"),
    industries = useCodes("INDUSTRY"),
    banks = useCodes("BANK");
  const write = useAdminWrite(reload);
  const dirty =
    JSON.stringify(partnerPayload(draft)) !==
    JSON.stringify(partnerPayload(initial ?? emptyPartner));
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
  const change = (field: keyof PartnerInput, value: string) => {
    write.clear();
    setLocalError(null);
    setDraft((d) => ({
      ...d,
      [field]: value,
      ...(field === "ptnr_se_cd"
        ? {
            txinv_email_addr: value === "VEND" ? null : d.txinv_email_addr,
            bank_cd: value === "CUST" ? null : d.bank_cd,
            acnt_no: value === "CUST" ? null : d.acnt_no,
            dpstr_nm: value === "CUST" ? null : d.dpstr_nm,
          }
        : {}),
    }));
  };
  const fieldError = (key: string) =>
    error?.field === key ? error.message : null;
  const text = (
    key: keyof PartnerInput,
    label: string,
    maxLength: number,
    required = false,
    readOnly = false,
  ) => (
    <TextInput
      key={key}
      name={key}
      label={label}
      maxLength={maxLength}
      required={required}
      readOnly={readOnly}
      disabled={write.pending}
      value={draft[key] ?? ""}
      onValueChange={(v) =>
        change(key, key === "biz_reg_no" ? formatBusinessNumber(v) : v)
      }
      error={fieldError(key)}
    />
  );
  const save = () => {
    let row: PartnerDetail | null = null;
    void write.save(
      async () => {
        row = initial
          ? await updatePartner(initial.ptnr_id, draft)
          : await createPartner(draft);
      },
      () => onSaved(row),
    );
  };
  const submit = () => {
    const invalid = validatePartner(draft, !!initial);
    setLocalError(invalid);
    if (invalid) return;
    if (initial && !dirty) {
      onSaved(null);
      return;
    }
    if (
      initial?.acnt_no &&
      ["bank_cd", "acnt_no", "dpstr_nm"].some(
        (k) =>
          draft[k as keyof PartnerInput] !== initial[k as keyof PartnerInput],
      )
    )
      setAccountConfirm(true);
    else save();
  };
  return (
    <form
      data-testid="partner-form"
      ref={form}
      noValidate
      className={styles.form}
      onSubmit={(e) => {
        e.preventDefault();
        submit();
      }}
    >
      <ErrorBanner error={write.error} onRetry={() => void write.recover()} />
      <ErrorBanner error={kinds.error} onRetry={kinds.retry} />
      <ErrorBanner error={industries.error} onRetry={industries.retry} />
      <ErrorBanner error={banks.error} onRetry={banks.retry} />
      <section className={styles.card}>
        <h2>기본 정보</h2>
        <div className={styles.fields}>
          <SelectInput
            name="ptnr_se_cd"
            label="구분"
            required
            value={draft.ptnr_se_cd}
            options={kinds.codes.map((c) => ({ value: c.code, label: c.name }))}
            onValueChange={(v) => change("ptnr_se_cd", v)}
            error={fieldError("ptnr_se_cd")}
            disabled={write.pending || kinds.loading}
          />
          {text("ptnr_nm", "상호", 50, true)}
          <p className={styles.help}>
            고객사이면서 협력사이면 '둘 다'를 고릅니다
          </p>
          {text("biz_reg_no", "사업자번호", 12, true, !!initial)}
          {text("ceo_nm", "대표자", 20)}
          <p className={styles.help}>
            {initial
              ? "프로젝트·청구·지급과 연결되어 있어 바꿀 수 없습니다. 잘못 넣었으면 사용 중지 후 새로 등록하세요."
              : "숫자만 입력하면 하이픈이 자동으로 들어갑니다"}
          </p>
          <SelectInput
            name="indty_cd"
            label="업종"
            value={draft.indty_cd ?? ""}
            options={industries.codes.map((c) => ({
              value: c.code,
              label: c.name,
            }))}
            onValueChange={(v) => change("indty_cd", v)}
            error={fieldError("indty_cd")}
            disabled={write.pending || industries.loading}
          />
          {text("chrg_nm", "담당자", 20)}
          {text("chrg_tel_no", "연락처", 20)}
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
      {draft.ptnr_se_cd && draft.ptnr_se_cd !== "VEND" && (
        <section className={styles.card}>
          <h2>세금계산서</h2>
          {text("txinv_email_addr", "세금계산서 이메일", 100, true)}
        </section>
      )}
      {draft.ptnr_se_cd && draft.ptnr_se_cd !== "CUST" && (
        <section className={styles.card}>
          <h2>지급 계좌</h2>
          <SelectInput
            name="bank_cd"
            label="은행"
            required
            value={draft.bank_cd ?? ""}
            options={banks.codes.map((c) => ({ value: c.code, label: c.name }))}
            onValueChange={(v) => change("bank_cd", v)}
            error={fieldError("bank_cd")}
            disabled={write.pending || banks.loading}
          />
          {text("acnt_no", "계좌번호", 20, true)}
          <p className={styles.help}>숫자와 하이픈(-)만 씁니다</p>
          {text("dpstr_nm", "예금주", 30, true)}
        </section>
      )}
      <div className={styles.actions}>
        <Button type="button" onClick={onCancel} disabled={write.pending}>
          취소
        </Button>
        <Button
          type="submit"
          variant="primary"
          pending={write.pending}
          disabled={
            write.uncertain ||
            kinds.loading ||
            !!kinds.error ||
            !!industries.error ||
            (draft.ptnr_se_cd !== "CUST" && !!banks.error)
          }
        >
          저장
        </Button>
      </div>
      {accountConfirm && (
        <ConfirmDialog
          title="지급 계좌를 바꿀까요?"
          confirmLabel="바꾸기"
          onCancel={() => setAccountConfirm(false)}
          onConfirm={() => {
            setAccountConfirm(false);
            save();
          }}
        >
          다음 지급부터 새 계좌(
          {banks.codes.find((c) => c.code === draft.bank_cd)?.name ?? "–"} …
          {draft.acnt_no?.slice(-4)})로 보냅니다. 바꾼 기록이 남습니다.
        </ConfirmDialog>
      )}
    </form>
  );
}
