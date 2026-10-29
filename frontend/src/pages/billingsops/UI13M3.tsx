import { useState } from "react";
import { Button, Modal } from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { useAdminWrite } from "../admin/useAdminResource";
import { cancelBilling } from "./client";
import type { BillingRow } from "./client";
import styles from "../developers/Developers.module.css";

export function UI13M3({
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
  const [reason, setReason] = useState("");
  const write = useAdminWrite(reload);
  return (
    <Modal title="청구 취소" onCancel={onClose}>
      <form
        data-testid="billing-cancel-dialog"
        className={styles.form}
        onSubmit={(e) => {
          e.preventDefault();
          if (billing.bill_id != null && reason.trim())
            void write.save(
              () => cancelBilling(billing.bill_id!, reason),
              onSaved,
            );
        }}
      >
        <p>
          {billing.bill_no} · {billing.project_name}
        </p>
        <p>청구는 삭제되지 않으며 취소 상태와 사유가 이력에 남습니다.</p>
        <label>
          취소 사유 *
          <textarea
            data-testid="billing-cancel-reason"
            required
            maxLength={500}
            value={reason}
            onChange={(e) => setReason(e.target.value)}
          />
        </label>
        <ErrorBanner error={write.error} onRetry={() => void write.recover()} />
        <Button
          data-testid="billing-cancel-close"
          type="button"
          onClick={onClose}
          disabled={write.pending}
        >
          닫기
        </Button>
        <Button
          data-testid="billing-cancel-submit"
          type="submit"
          variant="primary"
          pending={write.pending}
          disabled={!reason.trim() || write.uncertain}
        >
          청구 취소 확인
        </Button>
      </form>
    </Modal>
  );
}
