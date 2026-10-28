import { useState } from "react";
import { Button, Modal } from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import type { ApiFailure } from "../../shared/api";
import {
  createBillingAmendment,
  billingFailure,
  type BillingRow,
} from "./client";
import styles from "../developers/Developers.module.css";

export function UI13M4({
  billing,
  onClose,
  onSaved,
}: {
  billing: BillingRow;
  onClose: () => void;
  onSaved: () => Promise<unknown>;
}) {
  const [values, setValues] = useState({
    reason: "",
    sply_amt: "",
    vat_amt: "",
    bill_dt: "",
    txinv_no: "",
    txinv_dt: "",
    rcpt_plan_dt: "",
  });
  const [error, setError] = useState<ApiFailure | null>(null);
  const [pending, setPending] = useState(false);
  const close = () => {
    if (
      !pending &&
      (!Object.values(values).some(Boolean) ||
        window.confirm("입력을 버리고 닫을까요?"))
    )
      onClose();
  };
  const fields = [
    ["reason", "수정 사유"],
    ["sply_amt", "공급가액"],
    ["vat_amt", "부가세"],
    ["bill_dt", "청구일"],
    ["txinv_no", "세금계산서 번호"],
    ["txinv_dt", "작성일"],
    ["rcpt_plan_dt", "입금 예정일"],
  ] as const;
  const supply = Number(values.sply_amt),
    vat = Number(values.vat_amt);
  const valid =
    values.reason.trim() &&
    /^-\d+$/.test(values.sply_amt) &&
    /^-?\d+$/.test(values.vat_amt) &&
    Number.isSafeInteger(supply) &&
    supply < 0 &&
    Number.isSafeInteger(vat) &&
    vat <= 0;
  return (
    <Modal title="수정 청구" onCancel={close}>
      <form
        data-testid="billing-amendment-dialog"
        className={styles.form}
        onSubmit={async (e) => {
          e.preventDefault();
          if (!valid || billing.bill_id == null || pending) return;
          setPending(true);
          setError(null);
          try {
            await createBillingAmendment(billing.bill_id, {
              ...values,
              sply_amt: supply,
              vat_amt: vat,
              bill_dt: values.bill_dt || null,
              txinv_dt: values.txinv_dt || null,
              rcpt_plan_dt: values.rcpt_plan_dt || null,
            });
            await onSaved();
            onClose();
          } catch (e) {
            setError(billingFailure(e));
          } finally {
            setPending(false);
          }
        }}
      >
        <p>
          원본 {billing.bill_no} · {billing.project_name}
        </p>
        <p>
          조정 청구 총액 {billing.adjusted_total_amt.toLocaleString("ko-KR")}원
          · 입금 합 {billing.received_amt.toLocaleString("ko-KR")}원
        </p>
        <p>
          음수 수정 청구는 새 DRAFT로 저장되며 별도 확정 전에는 미수에 반영되지
          않습니다.
        </p>
        {fields.map(([key, label]) => (
          <label key={key}>
            {label}
            <input
              data-testid={`billing-amendment-${key.replaceAll("_", "-")}`}
              type={key.endsWith("dt") ? "date" : "text"}
              required={["reason", "sply_amt", "vat_amt"].includes(key)}
              value={values[key]}
              aria-invalid={error?.field === key}
              onChange={(e) => setValues({ ...values, [key]: e.target.value })}
            />
          </label>
        ))}
        {!valid && <p>사유, 음수 공급가액과 0 이하 부가세를 입력하세요.</p>}
        {error && <ErrorBanner error={error} />}
        <div className={styles.actions}>
          <Button
            data-testid="billing-amendment-close"
            type="button"
            onClick={close}
          >
            취소
          </Button>
          <Button
            data-testid="billing-amendment-save"
            type="submit"
            disabled={!valid}
            pending={pending}
          >
            저장
          </Button>
        </div>
      </form>
    </Modal>
  );
}
