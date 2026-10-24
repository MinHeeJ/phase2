import { useEffect, useRef, useState } from "react";
import { Button, Modal } from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { ApiFailure } from "../../shared/api";
import { isMonth } from "../../lib/format";
import { useAdminWrite } from "../admin/useAdminResource";
import { useAdminGuard } from "../admin/AdminDialogs";
import { failure } from "../admin/client";
import { getEffectiveParam } from "../shared/api";
import type { SalesPartDetail } from "../salesparts/client";
import { saveSalesPartStandard, updateSalesPartName } from "./client";
import fields from "../../shared/Inputs.module.css";
import styles from "../salesparts/SalesParts.module.css";

export function SalesPartEditDialog({
  part,
  mode,
  reload,
  onCancel,
  onSaved,
  onDirtyChange,
}: {
  part: SalesPartDetail;
  mode: "standards" | "name";
  reload: () => Promise<unknown>;
  onCancel: () => void;
  onSaved: (message: string) => void;
  onDirtyChange?: (dirty: boolean) => void;
}) {
  const current = part.currentStandard;
  const [draft, setDraft] = useState({
    bsn_part_nm: part.bsn_part_nm,
    aply_bgn_ym: part.firstAllowedMonth ?? "",
    ldr_nm: current?.ldr_nm ?? "",
    prft_dstrb_rt: current ? String(current.prft_dstrb_rt) : "",
    bsn_fee_amt: current ? String(current.bsn_fee_amt) : "",
  });
  const [dirty, setDirty] = useState(false),
    [validation, setValidation] = useState<ApiFailure | null>(null);
  const [defaultsError, setDefaultsError] = useState<ApiFailure | null>(null),
    [loading, setLoading] = useState(false),
    [retry, setRetry] = useState(0);
  const form = useRef<HTMLFormElement>(null),
    write = useAdminWrite(reload),
    guard = useAdminGuard(dirty);
  useEffect(() => {
    onDirtyChange?.(dirty);
    return () => onDirtyChange?.(false);
  }, [dirty, onDirtyChange]);
  useEffect(() => {
    if (mode !== "standards" || current || !isMonth(draft.aply_bgn_ym)) {
      setLoading(false);
      return;
    }
    const controller = new AbortController();
    setLoading(true);
    setDefaultsError(null);
    void Promise.allSettled([
      getEffectiveParam(
        "PART_PROFIT_RATE",
        draft.aply_bgn_ym,
        controller.signal,
      ),
      getEffectiveParam("PART_SALES_FEE", draft.aply_bgn_ym, controller.signal),
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
            "선택월의 기준값이 없습니다. 직접 입력해 주세요.",
            404,
            null,
          ),
        );
      setLoading(false);
    });
    return () => controller.abort();
  }, [mode, current, draft.aply_bgn_ym, retry]);
  const error = validation ?? write.error;
  useEffect(() => {
    if (error?.field)
      (
        form.current?.elements.namedItem(error.field) as HTMLElement | null
      )?.focus();
  }, [error]);
  const change = (key: keyof typeof draft, value: string) => {
    setDirty(true);
    setValidation(null);
    write.clear();
    setDraft((d) => ({
      ...d,
      [key]: value,
      ...(!current && key === "aply_bgn_ym"
        ? { prft_dstrb_rt: "", bsn_fee_amt: "" }
        : {}),
    }));
  };
  const invalid = (field: string, message: string) =>
    setValidation(new ApiFailure(message, 400, null, field));
  const submit = () => {
    if (write.pending || write.uncertain || loading) return;
    if (mode === "name") {
      if (!draft.bsn_part_nm.trim() || draft.bsn_part_nm.length > 30)
        return invalid("bsn_part_nm", "파트명을 입력해 주세요.");
      if (draft.bsn_part_nm.trim() === part.bsn_part_nm) {
        onCancel();
        return;
      }
      void write.save(
        () => updateSalesPartName(part.bsn_part_id, draft.bsn_part_nm.trim()),
        () => onSaved("파트명을 바꿨습니다."),
      );
      return;
    }
    if (!isMonth(draft.aply_bgn_ym))
      return invalid("aply_bgn_ym", "올바른 연월을 입력하세요.");
    if (part.firstAllowedMonth && draft.aply_bgn_ym < part.firstAllowedMonth)
      return invalid(
        "aply_bgn_ym",
        `적용 시작월은 마감된 달(${part.lastClosedMonth}) 다음 달부터 고를 수 있습니다.`,
      );
    if (!draft.ldr_nm.trim() || draft.ldr_nm.length > 50)
      return invalid("ldr_nm", "파트장을 입력해 주세요.");
    if (
      !/^\d+(?:\.\d{1,2})?$/.test(draft.prft_dstrb_rt) ||
      Number(draft.prft_dstrb_rt) > 100
    )
      return invalid(
        "prft_dstrb_rt",
        "이익 배분율은 0~100 사이 숫자로 입력해 주세요.",
      );
    if (
      !/^\d+$/.test(draft.bsn_fee_amt) ||
      !Number.isSafeInteger(Number(draft.bsn_fee_amt)) ||
      Number(draft.bsn_fee_amt) < 1
    )
      return invalid("bsn_fee_amt", "영업비를 입력해 주세요.");
    void write.save(
      () =>
        saveSalesPartStandard(part.bsn_part_id, {
          aply_bgn_ym: draft.aply_bgn_ym,
          ldr_nm: draft.ldr_nm.trim(),
          prft_dstrb_rt: Number(draft.prft_dstrb_rt),
          bsn_fee_amt: Number(draft.bsn_fee_amt),
        }),
      () =>
        onSaved(
          `${part.bsn_part_nm} 기준을 바꿨습니다. ${draft.aply_bgn_ym}부터 적용됩니다.`,
        ),
    );
  };
  const input = (
    key: keyof typeof draft,
    label: string,
    type = "text",
    maxLength?: number,
  ) => (
    <div className={fields.field}>
      <label htmlFor={`sales-part-edit-${key}`}>{label} *</label>
      <div className={fields.control}>
        <input
          id={`sales-part-edit-${key}`}
          data-testid={`sales-part-edit-${key.replaceAll("_", "-")}`}
          name={key}
          aria-label={label}
          required
          type={type}
          maxLength={maxLength}
          className={fields.input}
          value={draft[key]}
          disabled={
            write.pending ||
            (loading && (key === "prft_dstrb_rt" || key === "bsn_fee_amt"))
          }
          min={
            key === "aply_bgn_ym"
              ? (part.firstAllowedMonth ?? undefined)
              : key === "bsn_fee_amt"
                ? 1
                : key === "prft_dstrb_rt"
                  ? 0
                  : undefined
          }
          max={key === "prft_dstrb_rt" ? 100 : undefined}
          step={key === "prft_dstrb_rt" ? "0.01" : undefined}
          aria-invalid={error?.field === key}
          aria-describedby={
            error?.field === key ? `sales-part-edit-error-${key}` : undefined
          }
          onChange={(e) => change(key, e.target.value)}
        />
        {maxLength && (
          <small>
            {draft[key].length}/{maxLength}
          </small>
        )}
        {error?.field === key && (
          <span
            className={fields.error}
            id={`sales-part-edit-error-${key}`}
            role="alert"
          >
            {error.message}
          </span>
        )}
      </div>
    </div>
  );
  return (
    <>
      <Modal
        title={
          mode === "standards" ? `${part.bsn_part_nm} 기준 변경` : "파트명 수정"
        }
        onCancel={() => {
          if (!write.pending) guard.request(onCancel);
        }}
      >
        <form
          ref={form}
          data-screen-id={
            mode === "standards" ? "SCR-UI-15-M8" : "SCR-UI-15-M9"
          }
          data-testid="sales-part-edit-dialog"
          noValidate
          onSubmit={(e) => {
            e.preventDefault();
            submit();
          }}
        >
          {mode === "name" ? (
            <>
              {input("bsn_part_nm", "파트명", "text", 30)}
              <p className={styles.help}>
                소속 인력·프로젝트·사업계획의 파트명도 함께 바뀝니다.
              </p>
            </>
          ) : (
            <>
              {input("aply_bgn_ym", "적용 시작월", "month")}
              <p className={styles.help}>
                {part.lastClosedMonth
                  ? `마감된 달(${part.lastClosedMonth}) 다음 달부터 고를 수 있습니다.`
                  : "마감 이력이 없습니다. 적용 시작월을 직접 선택해 주세요."}
              </p>
              {input("ldr_nm", "파트장", "text", 50)}
              {input("prft_dstrb_rt", "이익 배분율 (%)", "number")}
              {input("bsn_fee_amt", "영업비 (원)", "number")}
              {loading && (
                <p role="status">선택월 기준값을 불러오는 중입니다.</p>
              )}
              <ErrorBanner
                error={defaultsError}
                onRetry={() => setRetry((n) => n + 1)}
              />
              {defaultsError && (
                <p>
                  기준값을 다시 조회하거나 배분율과 영업비를 직접 입력해 주세요.
                </p>
              )}
            </>
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
              data-testid="sales-part-edit-cancel"
              type="button"
              disabled={write.pending}
              onClick={() => guard.request(onCancel)}
            >
              취소
            </Button>
            <Button
              data-testid="sales-part-edit-submit"
              type="submit"
              variant="primary"
              pending={write.pending}
              disabled={loading || write.uncertain}
            >
              저장
            </Button>
          </div>
        </form>
      </Modal>
      {guard.confirmation}
    </>
  );
}
