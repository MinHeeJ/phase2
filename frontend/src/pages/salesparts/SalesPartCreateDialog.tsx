import { useEffect, useRef, useState } from "react";
import { Button, Modal } from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { getEffectiveParam } from "../shared/api";
import { useAdminWrite } from "../admin/useAdminResource";
import { failure } from "../admin/client";
import { ApiFailure } from "../../shared/api";
import { isMonth } from "../../lib/format";
import { createSalesPart, type SalesPartDetail } from "./client";
import fields from "../../shared/Inputs.module.css";
import styles from "./SalesParts.module.css";

// SCR-UI-15-M7
export function SalesPartCreateDialog({
  firstAllowedMonth,
  lastClosedMonth = null,
  onCancel,
  onSaved,
  reload,
  onDirtyChange,
}: {
  firstAllowedMonth: string | null;
  lastClosedMonth?: string | null;
  onCancel: () => void;
  onSaved: (part: SalesPartDetail) => void;
  reload: () => Promise<unknown>;
  onDirtyChange?: (dirty: boolean) => void;
}) {
  const [draft, setDraft] = useState({
    bsn_part_nm: "",
    aply_bgn_ym: firstAllowedMonth ?? "",
    ldr_nm: "",
    prft_dstrb_rt: "",
    bsn_fee_amt: "",
  });
  const [defaultsError, setDefaultsError] = useState<ApiFailure | null>(null);
  const [defaultsLoading, setDefaultsLoading] = useState(false);
  const [retry, setRetry] = useState(0);
  const [validation, setValidation] = useState<{
    field: string;
    message: string;
  } | null>(null);
  const [dirty, setDirty] = useState(false);
  const form = useRef<HTMLFormElement>(null);
  const write = useAdminWrite(reload);
  useEffect(() => {
    onDirtyChange?.(dirty);
    return () => onDirtyChange?.(false);
  }, [dirty, onDirtyChange]);
  useEffect(() => {
    const controller = new AbortController();
    const month = draft.aply_bgn_ym;
    setDefaultsError(null);
    if (!isMonth(month)) {
      setDefaultsLoading(false);
      return;
    }
    setDefaultsLoading(true);
    void Promise.allSettled([
      getEffectiveParam("PART_PROFIT_RATE", month, controller.signal),
      getEffectiveParam("PART_SALES_FEE", month, controller.signal),
    ]).then(([rate, fee]) => {
      if (controller.signal.aborted) return;
      setDraft((d) => ({
        ...d,
        prft_dstrb_rt:
          rate.status === "fulfilled" && rate.value.num_val != null
            ? String(rate.value.num_val)
            : "",
        bsn_fee_amt:
          fee.status === "fulfilled" && fee.value.num_val != null
            ? String(fee.value.num_val)
            : "",
      }));
      const rejected = [rate, fee].find((r) => r.status === "rejected");
      if (rejected?.status === "rejected")
        setDefaultsError(failure(rejected.reason));
      else if (
        (rate.status === "fulfilled" && rate.value.num_val == null) ||
        (fee.status === "fulfilled" && fee.value.num_val == null)
      )
        setDefaultsError(
          new ApiFailure(
            "선택월의 숫자 기준값이 없습니다. 직접 입력해 주세요.",
            404,
            null,
          ),
        );
      setDefaultsLoading(false);
    });
    return () => controller.abort();
  }, [draft.aply_bgn_ym, retry]);
  const change = (field: keyof typeof draft, value: string) => {
    setDirty(true);
    setValidation(null);
    write.clear();
    setDraft((d) => ({
      ...d,
      [field]: value,
      ...(field === "aply_bgn_ym"
        ? { prft_dstrb_rt: "", bsn_fee_amt: "" }
        : {}),
    }));
  };
  const error = validation ?? write.error;
  const focusError = (field: string, message: string) => {
    setValidation({ field, message });
    (form.current?.elements.namedItem(field) as HTMLElement | null)?.focus();
  };
  const submit = () => {
    if (defaultsLoading || write.pending || write.uncertain) return;
    if (!draft.bsn_part_nm.trim())
      return focusError("bsn_part_nm", "파트명을 입력해 주세요.");
    if (!isMonth(draft.aply_bgn_ym))
      return focusError("aply_bgn_ym", "올바른 연월을 입력하세요.");
    if (firstAllowedMonth && draft.aply_bgn_ym < firstAllowedMonth)
      return focusError(
        "aply_bgn_ym",
        `적용 시작월은 마감된 달${lastClosedMonth ? `(${lastClosedMonth})` : ""} 다음 달부터 고를 수 있습니다.`,
      );
    if (!draft.ldr_nm.trim())
      return focusError("ldr_nm", "파트장을 입력해 주세요.");
    if (
      !/^\d+(?:\.\d{1,2})?$/.test(draft.prft_dstrb_rt) ||
      Number(draft.prft_dstrb_rt) > 100
    )
      return focusError(
        "prft_dstrb_rt",
        "이익 배분율은 0~100 사이 숫자로 입력해 주세요.",
      );
    if (
      !/^\d+$/.test(draft.bsn_fee_amt) ||
      !Number.isSafeInteger(Number(draft.bsn_fee_amt)) ||
      Number(draft.bsn_fee_amt) < 1
    )
      return focusError("bsn_fee_amt", "영업비를 입력해 주세요.");
    let created: SalesPartDetail;
    void write.save(
      async () => {
        created = await createSalesPart({
          ...draft,
          bsn_part_nm: draft.bsn_part_nm.trim(),
          ldr_nm: draft.ldr_nm.trim(),
          prft_dstrb_rt: Number(draft.prft_dstrb_rt),
          bsn_fee_amt: Number(draft.bsn_fee_amt),
        });
      },
      () => onSaved(created),
    );
  };
  useEffect(() => {
    if (write.error?.field)
      (
        form.current?.elements.namedItem(
          write.error.field,
        ) as HTMLElement | null
      )?.focus();
  }, [write.error]);
  const input = (
    key: keyof typeof draft,
    label: string,
    type = "text",
    maxLength?: number,
  ) => (
    <div
      data-testid={`sales-part-${key.replaceAll("_", "-")}-field`}
      className={fields.field}
      key={key}
    >
      <label htmlFor={`sales-part-${key}`}>
        {label} <span aria-hidden="true">*</span>
      </label>
      <div className={fields.control}>
        <input
          id={`sales-part-${key}`}
          data-testid={`sales-part-${key.replaceAll("_", "-")}-input`}
          aria-label={label}
          name={key}
          type={type}
          required
          maxLength={maxLength}
          min={
            key === "aply_bgn_ym"
              ? (firstAllowedMonth ?? undefined)
              : key === "prft_dstrb_rt"
                ? 0
                : key === "bsn_fee_amt"
                  ? 1
                  : undefined
          }
          max={key === "prft_dstrb_rt" ? 100 : undefined}
          step={
            key === "prft_dstrb_rt"
              ? "0.01"
              : key === "bsn_fee_amt"
                ? "1"
                : undefined
          }
          disabled={
            write.pending ||
            ((key === "prft_dstrb_rt" || key === "bsn_fee_amt") &&
              defaultsLoading)
          }
          value={draft[key]}
          onChange={(e) => change(key, e.target.value)}
          className={fields.input}
          aria-invalid={error?.field === key}
          aria-describedby={
            error?.field === key ? `sales-part-error-${key}` : undefined
          }
        />
        {maxLength && (
          <small>
            {draft[key].length}/{maxLength}
          </small>
        )}
        {error?.field === key && (
          <span
            id={`sales-part-error-${key}`}
            className={fields.error}
            role="alert"
          >
            {error.message}
          </span>
        )}
      </div>
    </div>
  );
  return (
    <Modal
      title="영업 파트 추가"
      onCancel={() => {
        if (!write.pending) onCancel();
      }}
    >
      <form
        ref={form}
        data-testid="sales-part-create-dialog"
        noValidate
        onSubmit={(e) => {
          e.preventDefault();
          submit();
        }}
      >
        {input("bsn_part_nm", "파트명", "text", 30)}
        {input("aply_bgn_ym", "적용 시작월", "month")}
        <p className={styles.help}>
          마감된 달{lastClosedMonth ? `(${lastClosedMonth})` : ""} 다음 달부터
          고를 수 있습니다.
        </p>
        {!firstAllowedMonth && (
          <p className={styles.help}>
            마감 이력이 없습니다. 적용 시작월을 선택해 주세요.
          </p>
        )}
        {input("ldr_nm", "파트장", "text", 50)}
        {input("prft_dstrb_rt", "이익 배분율 (%)", "number")}
        {input("bsn_fee_amt", "영업비 (원)", "number")}
        {defaultsLoading && (
          <p role="status">선택월 기준값을 불러오는 중입니다.</p>
        )}
        <ErrorBanner
          error={defaultsError}
          onRetry={() => setRetry((n) => n + 1)}
        />
        {defaultsError && (
          <p>기준값을 다시 조회하거나 배분율과 영업비를 직접 입력해 주세요.</p>
        )}
        <ErrorBanner
          error={write.error}
          onRetry={() => {
            void write.recover();
          }}
          pending={write.pending}
        />
        <div className={styles.actions}>
          <Button
            data-testid="sales-part-create-cancel"
            type="button"
            disabled={write.pending}
            onClick={onCancel}
          >
            취소
          </Button>
          <Button
            data-testid="sales-part-create-submit"
            type="submit"
            variant="primary"
            pending={write.pending}
            disabled={defaultsLoading || write.uncertain}
          >
            추가
          </Button>
        </div>
      </form>
    </Modal>
  );
}
