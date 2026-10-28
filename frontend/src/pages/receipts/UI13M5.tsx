import { useEffect, useRef, useState } from "react";
import { Button, LockedBanner, Modal } from "../../shared/Controls";
import { TextInput, MoneyInput, TextareaInput } from "../../shared/Inputs";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { useAdminWrite } from "../admin/useAdminResource";
import { monthStatus, type MonthClose } from "../shared/api";
import { useDeveloperReadOnly } from "../developers/useDeveloperReadOnly";
import { seoulMonth } from "../shared/PeriodBar";
import { isDate } from "../../lib/format";
import * as client from "./client";
import type { BillingReceipt, BillingReceiptDetail } from "./client";
import styles from "../developers/Developers.module.css";

export function UI13M5({
  detail,
  receipt,
  mode,
  year,
  months,
  reload,
  onCancel,
  onSaved,
}: {
  detail: BillingReceiptDetail;
  receipt?: BillingReceipt;
  mode: "create" | "update" | "delete";
  year: number;
  months: MonthClose[];
  reload: () => Promise<unknown>;
  onCancel: () => void;
  onSaved: () => void;
}) {
  const [date, setDate] = useState(receipt?.rcpt_dt ?? "");
  const [amount, setAmount] = useState<number | null>(
    receipt?.rcpt_amt ?? null,
  );
  const [note, setNote] = useState(receipt?.rmrk_desc ?? "");
  const [dirty, setDirty] = useState(false);
  const [leaving, setLeaving] = useState(false);
  const [localError, setLocalError] = useState<{
    field: string;
    message: string;
  } | null>(null);
  const form = useRef<HTMLFormElement>(null);
  const write = useAdminWrite(reload);
  const mobile = useDeveloperReadOnly();
  const deleting = mode === "delete";
  const oldClosed =
    !!receipt && monthStatus(months, receipt.rcpt_dt.slice(0, 7)) === "CLOSED";
  const newClosed =
    !deleting && !!date && monthStatus(months, date.slice(0, 7)) === "CLOSED";
  const readonly =
    mobile ||
    year < Number(seoulMonth().slice(0, 4)) ||
    detail.billing.bill_stts_cd !== "CONFIRM" ||
    detail.original_bill_id != null;
  const blocked =
    readonly || oldClosed || newClosed || write.pending || write.uncertain;
  const error = localError ?? write.error;
  useEffect(() => {
    if (error?.field)
      form.current
        ?.querySelector<HTMLElement>(
          `[name="${error.field.replace(/[^a-z_]/g, "")}"]`,
        )
        ?.focus();
  }, [error]);
  const cancel = () => {
    if (write.pending) return;
    if (dirty) setLeaving(true);
    else onCancel();
  };
  const changed = () => {
    setDirty(true);
    setLocalError(null);
    write.clear();
  };
  const fieldError = (field: string) =>
    error?.field === field ? error.message : null;
  const save = () => {
    if (blocked) return;
    if (!deleting) {
      const invalidMoney =
        form.current?.querySelector<HTMLInputElement>('[name="rcpt_amt"]')
          ?.validity.customError;
      const field = !isDate(date)
        ? "rcpt_dt"
        : invalidMoney ||
            amount == null ||
            !Number.isSafeInteger(amount) ||
            amount <= 0
          ? "rcpt_amt"
          : null;
      if (field) {
        setLocalError({ field, message: "입금일과 양수 입금액을 입력하세요." });
        return;
      }
    }
    void write.save(async () => {
      const id = detail.billing.bill_id;
      if (mode === "delete" && receipt)
        await client.deleteBillingReceipt(id, receipt.receipt_id);
      else if (mode === "update" && receipt)
        await client.updateBillingReceipt(id, receipt.receipt_id, {
          rcpt_dt: date,
          rcpt_amt: amount!,
          rmrk_desc: note || null,
        });
      else if (mode === "create")
        await client.createBillingReceipt(id, {
          rcpt_dt: date,
          rcpt_amt: amount!,
          rmrk_desc: note || null,
        });
      else throw new Error("선택한 입금이 없습니다.");
    }, onSaved);
  };
  return (
    <Modal
      title={
        deleting ? "입금 삭제" : mode === "update" ? "입금 수정" : "입금 등록"
      }
      confirmation={deleting}
      onCancel={cancel}
    >
      <form
        ref={form}
        noValidate
        className={styles.form}
        data-testid="receipt-form"
        data-screen-id="SCR-UI-13-M5"
        onSubmit={(event) => {
          event.preventDefault();
          save();
        }}
      >
        <p>청구번호 {detail.billing.bill_no}</p>
        <p>
          조정 청구 총액{" "}
          {detail.billing.adjusted_total_amt.toLocaleString("ko-KR")}원 · 미수금{" "}
          {detail.billing.receivable_amt.toLocaleString("ko-KR")}원
        </p>
        {oldClosed || newClosed ? (
          <LockedBanner>마감된 입금 월은 변경할 수 없습니다.</LockedBanner>
        ) : null}
        {readonly && (
          <LockedBanner>
            현재 조회에서는 입금을 변경할 수 없습니다.
          </LockedBanner>
        )}
        <ErrorBanner
          error={write.error}
          pending={write.pending}
          onRetry={() => void write.recover()}
        />
        <TextInput
          name="rcpt_dt"
          label="입금일"
          type="date"
          required
          value={date}
          disabled={write.pending || write.uncertain || readonly || oldClosed}
          readOnly={deleting}
          error={fieldError("rcpt_dt")}
          onValueChange={(value) => {
            changed();
            setDate(value);
          }}
        />
        <MoneyInput
          name="rcpt_amt"
          label="입금액 (부가세 포함)"
          required
          value={amount}
          disabled={write.pending || write.uncertain || readonly || oldClosed}
          readOnly={deleting}
          error={fieldError("rcpt_amt")}
          onValueChange={(value) => {
            changed();
            setAmount(value);
          }}
        />
        <TextareaInput
          name="rmrk_desc"
          label="비고"
          maxLength={200}
          value={note}
          disabled={write.pending || write.uncertain || readonly || oldClosed}
          readOnly={deleting}
          error={fieldError("rmrk_desc")}
          onValueChange={(value) => {
            changed();
            setNote(value);
          }}
        />
        {deleting && (
          <p>이 입금을 삭제할까요? 삭제 전 기록은 변경 이력에 보존됩니다.</p>
        )}
        <div className={styles.actions}>
          <Button
            type="button"
            data-testid="receipt-cancel"
            disabled={write.pending}
            onClick={cancel}
          >
            취소
          </Button>
          {!readonly && (
            <Button
              type="submit"
              variant="primary"
              data-testid="receipt-save"
              disabled={blocked}
              pending={write.pending}
            >
              {deleting ? "삭제 확인" : "저장"}
            </Button>
          )}
        </div>
      </form>
      {leaving && (
        <Modal
          title="작성 중인 내용을 버릴까요?"
          confirmation
          onCancel={() => setLeaving(false)}
        >
          <div data-testid="receipt-discard-dialog">
            <Button
              data-testid="receipt-keep"
              onClick={() => setLeaving(false)}
            >
              계속 작성
            </Button>
            <Button data-testid="receipt-discard" onClick={onCancel}>
              나가기
            </Button>
          </div>
        </Modal>
      )}
    </Modal>
  );
}
