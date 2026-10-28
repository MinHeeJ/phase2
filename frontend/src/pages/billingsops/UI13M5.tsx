import { useState } from "react";
import { Button, Modal } from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import type { ApiFailure } from "../../shared/api";
import {
  billingFailure,
  writeBilling,
  type BillingRow,
  type Receipt,
} from "./client";
import styles from "../developers/Developers.module.css";

export function UI13M5({
  billing,
  receipt,
  mode = "create",
  onClose,
  onSaved,
}: {
  billing: BillingRow;
  receipt?: Receipt;
  mode?: "create" | "edit" | "delete";
  onClose: () => void;
  onSaved: () => Promise<unknown>;
}) {
  const [date, setDate] = useState(receipt?.rcpt_dt ?? ""),
    [amount, setAmount] = useState(String(receipt?.rcpt_amt ?? "")),
    [note, setNote] = useState(receipt?.rmrk_desc ?? "");
  const [pending, setPending] = useState(false),
    [error, setError] = useState<ApiFailure | null>(null);
  const close = () => {
    if (!pending) onClose();
  };
  return (
    <Modal
      title={
        mode === "delete"
          ? "입금 삭제"
          : mode === "edit"
            ? "입금 수정"
            : "입금 등록"
      }
      onCancel={close}
    >
      <form
        data-testid="billing-receipt-dialog"
        className={styles.form}
        onSubmit={async (e) => {
          e.preventDefault();
          if (pending || billing.bill_id == null) return;
          if (
            mode !== "delete" &&
            (!date ||
              !/^\d+$/.test(amount) ||
              !Number.isSafeInteger(Number(amount)) ||
              Number(amount) <= 0)
          )
            return;
          if (mode !== "create" && !receipt) return;
          setPending(true);
          setError(null);
          try {
            const path = `/api/billings/${billing.bill_id}/receipts${mode === "create" ? "" : `/${receipt!.receipt_id}`}`;
            await writeBilling(
              path,
              mode === "create" ? "POST" : mode === "edit" ? "PUT" : "DELETE",
              mode === "delete"
                ? undefined
                : {
                    rcpt_dt: date,
                    rcpt_amt: Number(amount),
                    rmrk_desc: note || null,
                  },
            );
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
          {billing.bill_no} · 조정 청구 총액{" "}
          {billing.adjusted_total_amt.toLocaleString("ko-KR")}원 · 미수금{" "}
          {billing.receivable_amt.toLocaleString("ko-KR")}원
        </p>
        {mode === "delete" ? (
          <p>
            선택한 입금을 삭제하시겠습니까? {receipt?.rcpt_dt} ·{" "}
            {receipt?.rcpt_amt.toLocaleString("ko-KR")}원
          </p>
        ) : (
          <>
            <label>
              입금일
              <input
                data-testid="billing-receipt-date"
                type="date"
                required
                value={date}
                onChange={(e) => setDate(e.target.value)}
              />
            </label>
            <label>
              입금액 (부가세 포함)
              <input
                data-testid="billing-receipt-amount"
                required
                inputMode="numeric"
                value={amount}
                onChange={(e) => setAmount(e.target.value)}
              />
            </label>
            <label>
              비고
              <input
                data-testid="billing-receipt-note"
                maxLength={200}
                value={note}
                onChange={(e) => setNote(e.target.value)}
              />
            </label>
          </>
        )}
        {error && <ErrorBanner error={error} />}
        <div className={styles.actions}>
          <Button
            data-testid="billing-receipt-close"
            type="button"
            onClick={close}
          >
            취소
          </Button>
          <Button
            data-testid="billing-receipt-save"
            type="submit"
            pending={pending}
          >
            {mode === "delete" ? "삭제 확인" : "저장"}
          </Button>
        </div>
      </form>
    </Modal>
  );
}
