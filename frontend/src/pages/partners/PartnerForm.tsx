import { useEffect, useRef, useState } from "react";
import { TextInput, SelectInput, TextareaInput } from "../../shared/Inputs";
import { Button, ConfirmDialog } from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { ApiFailure } from "../../shared/api";
import { useCodes } from "../shared/useCodes";
import { useAdminGuard } from "../admin/AdminDialogs";
import {
  createPartner,
  updatePartner,
  partnerPayload,
  type PartnerInput,
  type PartnerDetail,
} from "./client";
import styles from "./Partners.module.css";

export const blankPartner: PartnerInput = {
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
export function formatPartnerBusinessNumber(value: string) {
  const digits = value.replace(/\D/g, "").slice(0, 10);
  return (
    digits.slice(0, 3) +
    (digits.length > 3 ? `-${digits.slice(3, 5)}` : "") +
    (digits.length > 5 ? `-${digits.slice(5)}` : "")
  );
}
function invalidPartner(
  value: PartnerInput,
  editing: boolean,
): { field: string; message: string } | null {
  if (!value.ptnr_se_cd)
    return { field: "ptnr_se_cd", message: "구분을 골라 주세요." };
  if (!value.ptnr_nm.trim())
    return { field: "ptnr_nm", message: "상호를 입력해 주세요." };
  if (!editing && !/^\d{3}-\d{2}-\d{5}$/.test(value.biz_reg_no ?? ""))
    return {
      field: "biz_reg_no",
      message: "사업자번호 10자리를 입력해 주세요. (예: 123-45-67890)",
    };
  if (value.ptnr_se_cd !== "VEND") {
    if (!value.txinv_email_addr?.trim())
      return {
        field: "txinv_email_addr",
        message: "고객사는 세금계산서 이메일이 꼭 필요합니다.",
      };
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value.txinv_email_addr))
      return {
        field: "txinv_email_addr",
        message: "이메일 형식이 맞지 않습니다. (예: tax@company.co.kr)",
      };
  }
  if (value.ptnr_se_cd !== "CUST") {
    for (const field of ["bank_cd", "acnt_no", "dpstr_nm"] as const)
      if (!value[field]?.trim())
        return {
          field,
          message: "협력사는 지급 계좌(은행·계좌번호·예금주)가 꼭 필요합니다.",
        };
    if (!/^[0-9-]{8,20}$/.test(value.acnt_no ?? ""))
      return {
        field: "acnt_no",
        message: "계좌번호를 숫자와 하이픈(-)으로 입력해 주세요.",
      };
  }
  return null;
}
export function PartnerForm({
  initial,
  onCancel,
  onSaved,
  onDirtyChange,
}: {
  initial?: PartnerDetail;
  onCancel: () => void;
  onSaved: (partner: PartnerDetail, changed: boolean) => void;
  onDirtyChange?: (dirty: boolean) => void;
}) {
  const editing = !!initial;
  const [draft, setDraft] = useState<PartnerInput>(() =>
    initial ? partnerPayload(initial, false) : { ...blankPartner },
  );
  const baseline = useRef(JSON.stringify(draft));
  const dirty = JSON.stringify(draft) !== baseline.current;
  const guard = useAdminGuard(dirty);
  const [pending, setPending] = useState(false),
    [accountConfirm, setAccountConfirm] = useState(false);
  const [error, setError] = useState<ApiFailure | null>(null);
  const form = useRef<HTMLFormElement>(null),
    saving = useRef(false);
  const kinds = useCodes("PARTNER_KIND"),
    industries = useCodes("INDUSTRY"),
    banks = useCodes("BANK");
  useEffect(() => {
    onDirtyChange?.(dirty);
    return () => onDirtyChange?.(false);
  }, [dirty, onDirtyChange]);
  useEffect(() => {
    if (error?.field)
      Array.from(form.current?.querySelectorAll<HTMLElement>("[name]") ?? [])
        .find((el) => el.getAttribute("name") === error.field)
        ?.focus();
  }, [error]);
  const change = (field: keyof PartnerInput, value: string) => {
    setError(null);
    setDraft((d) => ({
      ...d,
      [field]: value,
      ...(field === "ptnr_se_cd" && value === "CUST"
        ? { bank_cd: null, acnt_no: null, dpstr_nm: null }
        : {}),
      ...(field === "ptnr_se_cd" && value === "VEND"
        ? { txinv_email_addr: null }
        : {}),
    }));
  };
  const save = async () => {
    if (saving.current) return;
    saving.current = true;
    setPending(true);
    setAccountConfirm(false);
    setError(null);
    try {
      const payload = partnerPayload(draft, editing);
      const result = initial
        ? await updatePartner(initial.ptnr_id, payload)
        : await createPartner(payload);
      baseline.current = JSON.stringify(draft);
      onDirtyChange?.(false);
      onSaved(result, dirty);
    } catch (e) {
      setError(
        e instanceof ApiFailure
          ? e
          : new ApiFailure("요청을 처리할 수 없습니다.", null, null),
      );
    } finally {
      saving.current = false;
      setPending(false);
    }
  };
  const submit = () => {
    const invalid = invalidPartner(draft, editing);
    if (invalid) {
      setError(new ApiFailure(invalid.message, 400, null, invalid.field));
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
    else void save();
  };
  const fieldError = (key: string) =>
    error?.field === key ? error.message : null;
  const text = (
    key: keyof PartnerInput,
    label: string,
    maxLength: number,
    required = false,
  ) => (
    <TextInput
      key={key}
      label={label}
      name={key}
      maxLength={maxLength}
      required={required}
      disabled={pending}
      value={draft[key] ?? ""}
      error={fieldError(key)}
      onValueChange={(v) => change(key, v)}
    />
  );
  return (
    <section data-testid="partner-form" className={styles.formRegion}>
      {!editing && <h2>거래처 등록</h2>}
      <ErrorBanner error={error} onRetry={() => setError(null)} />
      <form
        data-testid="partner-details-form"
        ref={form}
        noValidate
        onSubmit={(e) => {
          e.preventDefault();
          if (!pending) submit();
        }}
      >
        <h3>기본 정보</h3>
        <div className={styles.formGrid}>
          <SelectInput
            label="구분"
            name="ptnr_se_cd"
            required
            value={draft.ptnr_se_cd}
            options={kinds.codes.map((c) => ({ value: c.code, label: c.name }))}
            disabled={pending || kinds.loading}
            error={fieldError("ptnr_se_cd")}
            onValueChange={(v) => change("ptnr_se_cd", v)}
          />
          {text("ptnr_nm", "상호", 50, true)}
          <TextInput
            label="사업자번호"
            name="biz_reg_no"
            required={!editing}
            readOnly={editing}
            maxLength={12}
            value={draft.biz_reg_no ?? ""}
            disabled={pending}
            error={fieldError("biz_reg_no")}
            onValueChange={(v) =>
              change("biz_reg_no", formatPartnerBusinessNumber(v))
            }
          />
          {text("ceo_nm", "대표자", 20)}
          <SelectInput
            label="업종"
            name="indty_cd"
            value={draft.indty_cd ?? ""}
            options={industries.codes.map((c) => ({
              value: c.code,
              label: c.name,
            }))}
            disabled={pending || industries.loading}
            error={fieldError("indty_cd")}
            onValueChange={(v) => change("indty_cd", v)}
          />
          {text("chrg_nm", "담당자", 20)}
          {text("chrg_tel_no", "연락처", 20)}
        </div>
        <p>고객사이면서 협력사이면 '둘 다'를 고릅니다</p>
        <p>
          {editing
            ? "프로젝트·청구·지급과 연결되어 있어 바꿀 수 없습니다. 잘못 넣었으면 사용 중지 후 새로 등록하세요."
            : "숫자만 입력하면 하이픈이 자동으로 들어갑니다"}
        </p>
        <TextareaInput
          label="비고"
          name="rmrk_desc"
          maxLength={500}
          value={draft.rmrk_desc ?? ""}
          disabled={pending}
          error={fieldError("rmrk_desc")}
          onValueChange={(v) => change("rmrk_desc", v)}
        />
        {draft.ptnr_se_cd && draft.ptnr_se_cd !== "VEND" && (
          <section data-testid="partner-tax-section">
            <h3>세금계산서</h3>
            {text("txinv_email_addr", "세금계산서 이메일", 100, true)}
          </section>
        )}
        {draft.ptnr_se_cd && draft.ptnr_se_cd !== "CUST" && (
          <section data-testid="partner-bank-section">
            <h3>지급 계좌</h3>
            <SelectInput
              label="은행"
              name="bank_cd"
              required
              value={draft.bank_cd ?? ""}
              options={banks.codes.map((c) => ({
                value: c.code,
                label: c.name,
              }))}
              disabled={pending || banks.loading}
              error={fieldError("bank_cd")}
              onValueChange={(v) => change("bank_cd", v)}
            />
            {text("acnt_no", "계좌번호", 20, true)}
            <p>숫자와 하이픈(-)만 씁니다</p>
            {text("dpstr_nm", "예금주", 30, true)}
          </section>
        )}
        <ErrorBanner error={kinds.error} onRetry={kinds.retry} />
        <ErrorBanner error={industries.error} onRetry={industries.retry} />
        <ErrorBanner error={banks.error} onRetry={banks.retry} />
        <div className={styles.actions}>
          <Button
            data-testid="partner-cancel-button"
            type="button"
            disabled={pending}
            onClick={() => guard.request(onCancel)}
          >
            취소
          </Button>
          <Button
            data-testid="partner-save-button"
            type="submit"
            variant="primary"
            pending={pending}
            disabled={
              !!(kinds.error || industries.error || banks.error) ||
              kinds.loading ||
              industries.loading ||
              banks.loading
            }
          >
            저장
          </Button>
        </div>
      </form>
      {accountConfirm && (
        <ConfirmDialog
          title="지급 계좌를 바꿀까요?"
          confirmLabel="바꾸기"
          onCancel={() => setAccountConfirm(false)}
          onConfirm={() => void save()}
        >
          다음 지급부터 새 계좌(
          {banks.codes.find((c) => c.code === draft.bank_cd)?.name ??
            draft.bank_cd ??
            "–"}{" "}
          …{draft.acnt_no?.slice(-4) ?? "–"})로 보냅니다. 바꾼 기록이 남습니다.
        </ConfirmDialog>
      )}
      {guard.confirmation}
    </section>
  );
}
