import { useState } from "react";
import { Button, Modal } from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { useAdminWrite } from "../admin/useAdminResource";
import { createBillingAmendment } from "./client";
import type { BillingRow } from "./client";
import styles from "../developers/Developers.module.css";

export function UI13M4({
  billing,
  onClose,
  onSaved,
  reload,
}: {
  billing: BillingRow;
  onClose: () => void;
  onSaved: () => void;
  reload: () => Promise<unknown>;
}) {
  const [draft, setDraft] = useState({
    reason: "",
    sply_amt: "",
    vat_amt: "0",
    bill_dt: "",
    txinv_no: "",
    txinv_dt: "",
    rcpt_plan_dt: "",
  });
  const [local, setLocal] = useState("");
  const write = useAdminWrite(reload);
  const change = (key: keyof typeof draft, value: string) => {
    setDraft((d) => ({ ...d, [key]: value }));
    setLocal("");
  };
  return (
    <Modal title="수정 청구" onCancel={onClose}>
      <form
        data-testid="billing-amend-dialog"
        className={styles.form}
        onSubmit={(e) => {
          e.preventDefault();
          const supply = Number(draft.sply_amt),
            vat = Number(draft.vat_amt);
          if (
            !draft.reason.trim() ||
            !Number.isSafeInteger(supply) ||
            supply >= 0 ||
            !Number.isSafeInteger(vat) ||
            vat > 0
          ) {
            setLocal(
              "사유와 원 단위 음수 공급가액·0 이하 부가세를 확인하세요.",
            );
            return;
          }
          if (billing.bill_id != null)
            void write.save(
              () =>
                createBillingAmendment(billing.bill_id!, {
                  reason: draft.reason,
                  sply_amt: supply,
                  vat_amt: vat,
                  bill_dt: draft.bill_dt || null,
                  txinv_no: draft.txinv_no || null,
                  txinv_dt: draft.txinv_dt || null,
                  rcpt_plan_dt: draft.rcpt_plan_dt || null,
                }),
              onSaved,
            );
        }}
      >
        <p>
          원본 청구: {billing.bill_no} · {billing.project_name}
        </p>
        <p>
          조정 총액 {billing.adjusted_total_amt.toLocaleString("ko-KR")}원 ·
          입금 합 {billing.received_amt.toLocaleString("ko-KR")}원
        </p>
        <p>
          원본은 바뀌지 않습니다. 새 번호의 작성 청구를 저장하고 별도로
          확정하세요.
        </p>
        <label>
          수정 사유 *
          <textarea
            data-testid="billing-amend-reason"
            maxLength={500}
            required
            value={draft.reason}
            onChange={(e) => change("reason", e.target.value)}
          />
        </label>
        {(
          [
            ["sply_amt", "공급가액"],
            ["vat_amt", "부가세"],
            ["bill_dt", "청구일"],
            ["txinv_no", "세금계산서 번호"],
            ["txinv_dt", "세금계산서 작성일"],
            ["rcpt_plan_dt", "입금 예정일"],
          ] as const
        ).map(([key, label]) => (
          <label
            key={key}
            data-testid={`billing-amend-field-${key.replaceAll("_", "-")}`}
          >
            {label}
            <input
              data-testid={`billing-amend-${key.replaceAll("_", "-")}`}
              type={key.endsWith("_dt") ? "date" : "text"}
              value={draft[key]}
              onChange={(e) => change(key, e.target.value)}
            />
          </label>
        ))}
        {local && <p role="alert">{local}</p>}
        <ErrorBanner error={write.error} onRetry={() => void write.recover()} />
        {write.error?.field && (
          <p role="alert">
            {write.error.field}: {write.error.message}
          </p>
        )}
        <div className={styles.actions}>
          <Button
            data-testid="billing-amend-close"
            type="button"
            onClick={onClose}
            disabled={write.pending}
          >
            취소
          </Button>
          <Button
            data-testid="billing-amend-save"
            type="submit"
            variant="primary"
            pending={write.pending}
            disabled={write.uncertain}
          >
            저장
          </Button>
        </div>
      </form>
    </Modal>
  );
}
