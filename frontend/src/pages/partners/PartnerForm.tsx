import { useEffect, useRef, useState } from "react";
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

const blank: PartnerInput = { ptnr_se_cd: "", ptnr_nm: "", biz_reg_no: "" };
export function partnerDraft(row?: PartnerDetail): PartnerInput {
  if (!row) return { ...blank };
  const { ptnr_id: _id, ptnr_no: _no, use_yn: _use, ...input } = row;
  return input;
}
export function PartnerForm({
  initial,
  onSaved,
  onCancel,
  onDirtyChange,
}: {
  initial?: PartnerDetail;
  onSaved: (row: PartnerDetail, changed: boolean) => void;
  onCancel: () => void;
  onDirtyChange?: (dirty: boolean) => void;
}) {
  const [draft, setDraft] = useState<PartnerInput>(() => partnerDraft(initial));
  const [error, setError] = useState<ApiFailure | null>(null),
    [pending, setPending] = useState(false),
    [accountConfirm, setAccountConfirm] = useState(false);
  const form = useRef<HTMLFormElement>(null),
    saving = useRef(false);
  const kinds = useCodes("PARTNER_KIND"),
    industry = useCodes("INDUSTRY"),
    banks = useCodes("BANK");
  const dirty =
    JSON.stringify(partnerPayload(draft, !!initial)) !==
    JSON.stringify(partnerPayload(partnerDraft(initial), !!initial));
  const guard = useAdminGuard(dirty);
  useEffect(() => {
    onDirtyChange?.(dirty);
    return () => onDirtyChange?.(false);
  }, [dirty, onDirtyChange]);
  useEffect(() => {
    if (error?.field)
      form.current
        ?.querySelector<HTMLElement>(
          `[name="${error.field.replace(/[^a-z_]/g, "")}"]`,
        )
        ?.focus();
  }, [error]);
  const set = (key: keyof PartnerInput, value: string) => {
    setDraft((d) => ({
      ...d,
      [key]: value,
      ...(key === "ptnr_se_cd" && value === "VEND"
        ? { txinv_email_addr: null }
        : {}),
      ...(key === "ptnr_se_cd" && value === "CUST"
        ? { bank_cd: null, acnt_no: null, dpstr_nm: null }
        : {}),
    }));
    setError(null);
  };
  async function save() {
    if (saving.current) return;
    if (initial && !dirty) {
      onSaved(initial, false);
      return;
    }
    saving.current = true;
    setPending(true);
    setAccountConfirm(false);
    try {
      const input = partnerPayload(draft, !!initial);
      onSaved(
        initial
          ? await updatePartner(initial.ptnr_id, input)
          : await createPartner(input),
        true,
      );
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
  }
  const field = (
    key: keyof PartnerInput,
    label: string,
    max: number,
    readOnly = false,
  ) => (
    <label
      data-testid={`partner-field-${key.replaceAll("_", "-")}`}
      className={styles.field}
      key={key}
    >
      {label}
      <input
        data-testid={`partner-${key.replaceAll("_", "-")}`}
        name={key}
        aria-label={label}
        value={draft[key] ?? ""}
        maxLength={max}
        readOnly={readOnly}
        disabled={pending}
        aria-invalid={error?.field === key}
        onChange={(e) => {
          let value = e.target.value;
          if (key === "biz_reg_no") {
            const n = value.replace(/\D/g, "").slice(0, 10);
            value =
              n.length > 5
                ? `${n.slice(0, 3)}-${n.slice(3, 5)}-${n.slice(5)}`
                : n.length > 3
                  ? `${n.slice(0, 3)}-${n.slice(3)}`
                  : n;
          }
          if (value.length <= max) set(key, value);
        }}
      />
      <small>
        {(draft[key] ?? "").length}/{max}
      </small>
      {error?.field === key && <span role="alert">{error.message}</span>}
    </label>
  );
  const select = (
    key: keyof PartnerInput,
    label: string,
    source: ReturnType<typeof useCodes>,
  ) => (
    <label className={styles.field}>
      {label}
      <select
        data-testid={`partner-${key.replaceAll("_", "-")}`}
        name={key}
        aria-label={label}
        value={draft[key] ?? ""}
        disabled={pending || source.loading}
        onChange={(e) => set(key, e.target.value)}
      >
        <option value="">선택하세요</option>
        {source.codes.map((c) => (
          <option
            data-testid={`partner-${key.replaceAll("_", "-")}-option-${c.code.toLowerCase().replace(/[^a-z0-9]+/g, "-")}`}
            key={c.code}
            value={c.code}
          >
            {c.name}
          </option>
        ))}
      </select>
      {error?.field === key && <span role="alert">{error.message}</span>}
      <ErrorBanner error={source.error} onRetry={source.retry} />
    </label>
  );
  return (
    <>
      <form
        data-testid="partner-form"
        className={styles.form}
        ref={form}
        noValidate
        onSubmit={(e) => {
          e.preventDefault();
          if (
            initial?.acnt_no &&
            ["bank_cd", "acnt_no", "dpstr_nm"].some(
              (k) =>
                initial[k as keyof PartnerInput] !==
                draft[k as keyof PartnerInput],
            )
          )
            setAccountConfirm(true);
          else void save();
        }}
      >
        <ErrorBanner
          error={error}
          onRetry={() => void save()}
          pending={pending}
        />
        <fieldset disabled={pending}>
          <legend>기본 정보</legend>
          <div className={styles.fields}>
            {select("ptnr_se_cd", "구분", kinds)}
            <p>고객사이면서 협력사이면 '둘 다'를 고릅니다</p>
            {field("ptnr_nm", "상호", 50)}
            {field("biz_reg_no", "사업자번호", 12, !!initial)}
            <p>
              {initial
                ? "프로젝트·청구·지급과 연결되어 있어 바꿀 수 없습니다. 잘못 넣었으면 사용 중지 후 새로 등록하세요."
                : "숫자만 입력하면 하이픈이 자동으로 들어갑니다"}
            </p>
            {field("ceo_nm", "대표자", 20)}
            {select("indty_cd", "업종", industry)}
            {field("chrg_nm", "담당자", 20)}
            {field("chrg_tel_no", "연락처", 20)}
            <label className={styles.field}>
              비고
              <textarea
                data-testid="partner-remark"
                name="rmrk_desc"
                aria-label="비고"
                maxLength={500}
                value={draft.rmrk_desc ?? ""}
                onChange={(e) => {
                  if (e.target.value.length <= 500)
                    set("rmrk_desc", e.target.value);
                }}
              />
              <small>{draft.rmrk_desc?.length ?? 0}/500</small>
              {error?.field === "rmrk_desc" && (
                <span role="alert">{error.message}</span>
              )}
            </label>
          </div>
        </fieldset>
        {draft.ptnr_se_cd && draft.ptnr_se_cd !== "VEND" && (
          <fieldset disabled={pending}>
            <legend>세금계산서</legend>
            {field("txinv_email_addr", "세금계산서 이메일", 100)}
          </fieldset>
        )}
        {draft.ptnr_se_cd && draft.ptnr_se_cd !== "CUST" && (
          <fieldset disabled={pending}>
            <legend>지급 계좌</legend>
            {select("bank_cd", "은행", banks)}
            {field("acnt_no", "계좌번호", 20)}
            <p>숫자와 하이픈(-)만 씁니다</p>
            {field("dpstr_nm", "예금주", 30)}
          </fieldset>
        )}
        <div className={styles.actions}>
          <Button
            data-testid="partner-cancel"
            type="button"
            disabled={pending}
            onClick={() => guard.request(onCancel)}
          >
            취소
          </Button>
          <Button
            data-testid="partner-save"
            type="submit"
            variant="primary"
            pending={pending}
          >
            저장
          </Button>
        </div>
      </form>
      {guard.confirmation}
      {accountConfirm && (
        <ConfirmDialog
          title="지급 계좌를 바꿀까요?"
          confirmLabel="바꾸기"
          onCancel={() => setAccountConfirm(false)}
          onConfirm={() => void save()}
        >
          다음 지급부터 새 계좌(
          {banks.codes.find((c) => c.code === draft.bank_cd)?.name ?? "–"} …
          {draft.acnt_no?.slice(-4) ?? "–"})로 보냅니다. 바꾼 기록이 남습니다.
        </ConfirmDialog>
      )}
    </>
  );
}
