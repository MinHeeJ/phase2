import { useCallback, useEffect, useRef, useState } from "react";
import { Button, Modal, Skeleton } from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { ApiFailure } from "../../shared/api";
import { getEffectiveParam, getMonthClose } from "../shared/api";
import { useAdminGuard } from "../admin/AdminDialogs";
import { useAdminRead, useAdminWrite } from "../admin/useAdminResource";
import { isMonth } from "../../lib/format";
import { createSalesPart, type SalesPartDraft } from "./client";
import styles from "./SalesParts.module.css";

function nextMonth(month: string) {
  const year = Number(month.slice(0, 4)),
    value = Number(month.slice(5));
  return value === 12
    ? `${String(year + 1).padStart(4, "0")}-01`
    : `${month.slice(0, 4)}-${String(value + 1).padStart(2, "0")}`;
}
export function SalesPartCreateDialog({
  onCancel,
  onSaved,
  reload,
  onDirtyChange,
}: {
  onCancel: () => void;
  onSaved: (name: string) => void;
  reload: () => Promise<unknown>;
  onDirtyChange?: (dirty: boolean) => void;
}) {
  const [draft, setDraft] = useState<SalesPartDraft>({
    bsn_part_nm: "",
    aply_bgn_ym: "",
    ldr_nm: "",
    prft_dstrb_rt: "",
    bsn_fee_amt: "",
  });
  const [dirty, setDirty] = useState(false),
    [touched, setTouched] = useState(false);
  const form = useRef<HTMLFormElement>(null);
  const guard = useAdminGuard(dirty);
  const readBounds = useCallback(() => getMonthClose("0001-01", "9999-12"), []);
  const bounds = useAdminRead(readBounds);
  const closed =
    bounds.data
      ?.filter((r) => r.status === "CLOSED")
      .map((r) => r.month)
      .sort()
      .at(-1) ?? null;
  const min = closed ? nextMonth(closed) : undefined;
  const [defaultsPending, setDefaultsPending] = useState(false),
    [defaultsError, setDefaultsError] = useState<ApiFailure | null>(null),
    [revision, setRevision] = useState(0);
  const write = useAdminWrite(reload);
  useEffect(() => {
    if (write.error?.field)
      Array.from(
        form.current?.querySelectorAll<HTMLInputElement>("[name]") ?? [],
      )
        .find((el) => el.name === write.error?.field)
        ?.focus();
  }, [write.error]);
  useEffect(() => {
    onDirtyChange?.(dirty);
    return () => onDirtyChange?.(false);
  }, [dirty, onDirtyChange]);
  useEffect(() => {
    if (bounds.data && !dirty)
      setDraft((d) => ({ ...d, aply_bgn_ym: min ?? "" }));
  }, [bounds.data, min, dirty]);
  useEffect(() => {
    const controller = new AbortController();
    setDefaultsError(null);
    setDraft((d) => ({ ...d, prft_dstrb_rt: "", bsn_fee_amt: "" }));
    if (!isMonth(draft.aply_bgn_ym) || (min && draft.aply_bgn_ym < min)) {
      setDefaultsPending(false);
      return () => controller.abort();
    }
    setDefaultsPending(true);
    Promise.all([
      getEffectiveParam(
        "PART_PROFIT_RATE",
        draft.aply_bgn_ym,
        controller.signal,
      ),
      getEffectiveParam("PART_SALES_FEE", draft.aply_bgn_ym, controller.signal),
    ])
      .then(([rate, fee]) => {
        if (!controller.signal.aborted) {
          if (rate.num_val === null || fee.num_val === null)
            throw new ApiFailure("운영 기준값을 확인하세요.", null, null);
          setDraft((d) => ({
            ...d,
            prft_dstrb_rt: String(rate.num_val),
            bsn_fee_amt: String(fee.num_val),
          }));
        }
      })
      .catch((e: unknown) => {
        if (!controller.signal.aborted)
          setDefaultsError(
            e instanceof ApiFailure
              ? e
              : new ApiFailure("운영 기준값을 조회할 수 없습니다.", null, null),
          );
      })
      .finally(() => {
        if (!controller.signal.aborted) setDefaultsPending(false);
      });
    return () => controller.abort();
  }, [draft.aply_bgn_ym, min, revision]);
  const errors: Partial<Record<keyof SalesPartDraft, string>> = {};
  if (!draft.bsn_part_nm.trim() || draft.bsn_part_nm.length > 30)
    errors.bsn_part_nm = "파트명을 입력해 주세요.";
  if (!isMonth(draft.aply_bgn_ym))
    errors.aply_bgn_ym = "적용 시작월을 선택해 주세요.";
  else if (closed && draft.aply_bgn_ym <= closed)
    errors.aply_bgn_ym = `적용 시작월은 마감된 달(${closed}) 다음 달부터 고를 수 있습니다.`;
  if (!draft.ldr_nm.trim() || draft.ldr_nm.length > 50)
    errors.ldr_nm = "파트장을 입력해 주세요.";
  if (
    !/^\d+(?:\.\d{1,2})?$/.test(draft.prft_dstrb_rt) ||
    Number(draft.prft_dstrb_rt) > 100
  )
    errors.prft_dstrb_rt = "이익 배분율은 0~100 사이 숫자로 입력해 주세요.";
  if (
    !/^[1-9]\d*$/.test(draft.bsn_fee_amt) ||
    BigInt(draft.bsn_fee_amt || "0") > 9223372036854775807n
  )
    errors.bsn_fee_amt = "영업비를 입력해 주세요.";
  const edit = (key: keyof SalesPartDraft, value: string) => {
    setDirty(true);
    write.clear();
    setDraft((d) => ({ ...d, [key]: value }));
  };
  const cancel = () => {
    if (!write.pending) guard.request(onCancel);
  };
  const field = (
    key: keyof SalesPartDraft,
    label: string,
    type = "text",
    maxLength?: number,
  ) => {
    const error =
      write.error?.field === key
        ? write.error.message
        : touched
          ? errors[key]
          : undefined;
    return (
      <label className={styles.field} key={key}>
        {label} *
        <input
          data-testid={`sales-part-${key.replaceAll("_", "-")}-input`}
          name={key}
          aria-label={label}
          type={type}
          maxLength={maxLength}
          min={type === "month" ? min : undefined}
          disabled={
            write.pending ||
            (key === "aply_bgn_ym" && bounds.loading) ||
            ((key === "prft_dstrb_rt" || key === "bsn_fee_amt") &&
              defaultsPending)
          }
          value={
            key === "bsn_fee_amt"
              ? draft[key].replace(/\B(?=(\d{3})+(?!\d))/g, ",")
              : draft[key]
          }
          aria-invalid={!!error}
          aria-describedby={error ? `sales-part-${key}-error` : undefined}
          inputMode={
            key === "bsn_fee_amt"
              ? "numeric"
              : key === "prft_dstrb_rt"
                ? "decimal"
                : undefined
          }
          onChange={(e) =>
            edit(
              key,
              key === "bsn_fee_amt"
                ? e.target.value.replaceAll(",", "")
                : e.target.value,
            )
          }
        />
        {error && (
          <span id={`sales-part-${key}-error`} role="alert">
            {error}
          </span>
        )}
      </label>
    );
  };
  const blocked =
    bounds.loading ||
    !!bounds.error ||
    !bounds.data ||
    defaultsPending ||
    !!defaultsError ||
    write.uncertain;
  return (
    <>
      <Modal title="영업 파트 추가" onCancel={cancel}>
        <div data-testid="sales-part-create-dialog">
          <ErrorBanner
            error={bounds.error}
            onRetry={() => {
              void bounds.reload().catch(() => {});
            }}
          />
          <ErrorBanner
            error={defaultsError}
            onRetry={() => setRevision((r) => r + 1)}
          />
          <ErrorBanner
            error={write.error}
            onRetry={() => {
              void write.recover();
            }}
          />
          {bounds.loading && <Skeleton />}
          <form
            ref={form}
            noValidate
            onSubmit={(e) => {
              e.preventDefault();
              setTouched(true);
              const first = Object.keys(errors)[0];
              if (first) {
                form.current
                  ?.querySelector<HTMLInputElement>(`[name="${first}"]`)
                  ?.focus();
                return;
              }
              if (!blocked)
                void write.save(
                  () => createSalesPart(draft),
                  () => onSaved(draft.bsn_part_nm),
                );
            }}
          >
            {field("bsn_part_nm", "파트명", "text", 30)}
            {field("aply_bgn_ym", "적용 시작월", "month")}
            <p>
              {closed
                ? `마감된 달(${closed}) 다음 달부터 고를 수 있습니다.`
                : "마감 이력이 없습니다. 적용 시작월을 직접 선택해 주세요."}
            </p>
            {field("ldr_nm", "파트장", "text", 50)}
            {field("prft_dstrb_rt", "이익 배분율 (%)")}
            {field("bsn_fee_amt", "영업비 (원)")}
            <div className={styles.actions}>
              <Button
                data-testid="sales-part-create-cancel"
                type="button"
                disabled={write.pending}
                onClick={cancel}
              >
                취소
              </Button>
              <Button
                data-testid="sales-part-create-submit"
                type="submit"
                variant="primary"
                pending={write.pending}
                disabled={blocked}
              >
                추가
              </Button>
            </div>
          </form>
        </div>
      </Modal>
      {guard.confirmation}
    </>
  );
}
