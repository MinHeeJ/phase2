import { useState } from "react";
import { Button, Modal } from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { type ApiFailure } from "../../shared/api";
import { cancelBilling, billingFailure, type BillingRow } from "./client";
import styles from "../developers/Developers.module.css";

export function UI13M3({
  billing,
  onClose,
  onSaved,
}: {
  billing: BillingRow;
  onClose: () => void;
  onSaved: () => Promise<unknown>;
}) {
  const [reason, setReason] = useState("");
  const [error, setError] = useState<ApiFailure | null>(null);
  const [pending, setPending] = useState(false);
  const close = () => {
    if (!pending && (!reason || window.confirm("입력을 버리고 닫을까요?")))
      onClose();
  };
  return (
    <Modal title="청구 취소" onCancel={close} confirmation>
      <form
        data-testid="billing-cancel-dialog"
        className={styles.form}
        onSubmit={async (e) => {
          e.preventDefault();
          if (billing.bill_id == null || !reason.trim() || pending) return;
          setPending(true);
          setError(null);
          try {
            await cancelBilling(billing.bill_id, reason);
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
          {billing.bill_no} · {billing.project_name}
        </p>
        <p>
          취소한 청구는 이력에 남습니다. 입금 또는 수정 청구가 있거나 마감된
          월은 취소할 수 없습니다.
        </p>
        <label>
          취소 사유
          <textarea
            data-testid="billing-cancel-reason"
            required
            maxLength={500}
            value={reason}
            onChange={(e) => setReason(e.target.value)}
          />
        </label>
        {error && <ErrorBanner error={error} />}
        <div className={styles.actions}>
          <Button
            data-testid="billing-cancel-close"
            type="button"
            onClick={close}
            disabled={pending}
          >
            닫기
          </Button>
          <Button
            data-testid="billing-cancel-submit"
            type="submit"
            pending={pending}
          >
            청구 취소 확인
          </Button>
        </div>
      </form>
    </Modal>
  );
}
