import { useCallback, useEffect, useRef, useState } from "react";
import {
  Badge,
  Button,
  ConfirmDialog,
  LockedBanner,
  Modal,
  Skeleton,
} from "../../shared/Controls";
import { Can } from "../../shared/Can";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { formatMoney, isDate } from "../../lib/format";
import { useAdminRead, useAdminWrite } from "../admin/useAdminResource";
import { useDeveloperReadOnly } from "../developers/useDeveloperReadOnly";
import { getMonthClose, monthStatus } from "../shared/api";
import { seoulMonth } from "../shared/PeriodBar";
import * as client from "./client";
import table from "../developers/Developers.module.css";
import styles from "./Receipts.module.css";

export interface BillingReceiptPanelProps {
  billId: number;
  year?: number;
  readOnly?: boolean;
  onChanged?: () => Promise<unknown>;
}
type Editing =
  | { mode: "create" }
  | { mode: "update" | "delete"; receipt: client.BillingReceipt };

export function BillingReceiptPanel({
  billId,
  year,
  readOnly = false,
  onChanged,
}: BillingReceiptPanelProps) {
  const mobile = useDeveloperReadOnly();
  const state = useAdminRead(
    useCallback(async () => {
      const detail = await client.listBillingReceipts(billId);
      const months = detail.receipts
        .map((row) => row.rcpt_dt.slice(0, 7))
        .sort();
      const closures = months.length
        ? await getMonthClose(months[0], months[months.length - 1])
        : [];
      return { detail, closures };
    }, [billId]),
  );
  const [editing, setEditing] = useState<Editing | null>(null);
  const [message, setMessage] = useState("");
  const billing = state.data?.detail.billing;
  const revenueMonth =
    billing?.contract_type === "TK" ? billing.bill_dt : billing?.atrb_ym;
  const past =
    (year ?? Number(revenueMonth?.slice(0, 4) ?? seoulMonth().slice(0, 4))) <
    Number(seoulMonth().slice(0, 4));
  const readonly = readOnly || mobile || past;
  const eligible =
    billing?.bill_stts_cd === "CONFIRM" &&
    state.data?.detail.original_bill_id == null;
  useEffect(() => {
    setEditing(null);
    setMessage("");
  }, [billId, readonly]);
  const refresh = async () => {
    await state.reload();
    await onChanged?.();
  };
  return (
    <section
      data-testid="billing-receipt-panel"
      aria-label="입금 이력"
      className={styles.panel}
    >
      <div className={table.toolbar}>
        <h3>입금 이력</h3>
        {!readonly && eligible && (
          <Can perm="UI-13.receipt">
            <Button
              data-testid="receipt-add"
              disabled={state.loading || !!state.error}
              onClick={() => {
                setMessage("");
                setEditing({ mode: "create" });
              }}
            >
              입금 등록
            </Button>
          </Can>
        )}
      </div>
      {past && (
        <LockedBanner>과거 연도는 조회·다운로드만 가능합니다.</LockedBanner>
      )}
      {!state.loading && billing && !eligible && (
        <p>확정 원본 청구만 입금을 등록·수정·삭제할 수 있습니다.</p>
      )}
      {message && (
        <p role="status" data-testid="receipt-success">
          {message}
        </p>
      )}
      <ErrorBanner
        error={state.error}
        onRetry={() => void state.reload().catch(() => {})}
      />
      {state.loading ? (
        <Skeleton />
      ) : (
        !state.error &&
        state.data && (
          <>
            <div className={styles.summary} data-testid="receipt-balance">
              <span>
                누적 입금{" "}
                <strong>{formatMoney(BigInt(billing!.received_amt))}원</strong>
              </span>
              <span>
                조정 청구 총액{" "}
                <strong>
                  {formatMoney(BigInt(billing!.adjusted_total_amt))}원
                </strong>
              </span>
              <span>
                미수금{" "}
                <strong>
                  {formatMoney(BigInt(billing!.receivable_amt))}원
                </strong>
              </span>
              {billing!.bill_stts_cd === "CONFIRM" && (
                <Badge
                  tone={billing!.receivable_amt === 0 ? "success" : "warning"}
                >
                  {billing!.receivable_amt === 0 ? "입금 완료" : "미수"}
                </Badge>
              )}
            </div>
            <div className={table.tableScroll}>
              <table className={table.table} aria-label="입금 내역">
                <thead>
                  <tr>
                    <th>입금일</th>
                    <th className={table.amount}>입금액(부가세 포함)</th>
                    <th>비고</th>
                    <th>작업</th>
                  </tr>
                </thead>
                <tbody>
                  {!state.data.detail.receipts.length && (
                    <tr>
                      <td colSpan={4}>등록된 입금이 없습니다.</td>
                    </tr>
                  )}
                  {state.data.detail.receipts.map((receipt) => {
                    const closed =
                      monthStatus(
                        state.data!.closures,
                        receipt.rcpt_dt.slice(0, 7),
                      ) === "CLOSED";
                    return (
                      <tr
                        key={receipt.receipt_id}
                        data-testid={`receipt-row-${receipt.receipt_id}`}
                      >
                        <td>
                          {receipt.rcpt_dt}{" "}
                          {closed && <Badge tone="locked">마감</Badge>}
                        </td>
                        <td className={table.amount}>
                          {formatMoney(BigInt(receipt.rcpt_amt))}
                        </td>
                        <td>{receipt.rmrk_desc ?? "–"}</td>
                        <td>
                          {!readonly && eligible && !closed && (
                            <Can perm="UI-13.receipt">
                              <div className={table.actions}>
                                <Button
                                  small
                                  data-testid={`receipt-edit-${receipt.receipt_id}`}
                                  onClick={() =>
                                    setEditing({ mode: "update", receipt })
                                  }
                                >
                                  입금 수정
                                </Button>
                                <Button
                                  small
                                  data-testid={`receipt-delete-${receipt.receipt_id}`}
                                  onClick={() =>
                                    setEditing({ mode: "delete", receipt })
                                  }
                                >
                                  입금 삭제
                                </Button>
                              </div>
                            </Can>
                          )}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          </>
        )
      )}
      {!readonly && eligible && editing && state.data && (
        <UI13M5
          key={
            editing.mode === "create"
              ? "create"
              : `${editing.mode}-${editing.receipt.receipt_id}`
          }
          billId={billId}
          detail={state.data.detail}
          {...editing}
          onCancel={() => setEditing(null)}
          reload={refresh}
          onSaved={() => {
            setEditing(null);
            setMessage(
              editing.mode === "delete" ? "삭제했습니다." : "저장했습니다.",
            );
          }}
        />
      )}
    </section>
  );
}

export type UI13M5Props = Editing & {
  billId: number;
  detail: client.ReceiptBillingDetail;
  readOnly?: boolean;
  onCancel: () => void;
  reload: () => Promise<unknown>;
  onSaved: () => void;
};
export function UI13M5(props: UI13M5Props) {
  const initial = props.mode === "create" ? null : props.receipt;
  const [date, setDate] = useState(initial?.rcpt_dt ?? "");
  const [amount, setAmount] = useState(initial ? String(initial.rcpt_amt) : "");
  const [remark, setRemark] = useState(initial?.rmrk_desc ?? "");
  const [dirty, setDirty] = useState(false);
  const [leaving, setLeaving] = useState(false);
  const [localError, setLocalError] = useState<{
    field: string;
    message: string;
  } | null>(null);
  const form = useRef<HTMLFormElement>(null);
  const mobile = useDeveloperReadOnly();
  const write = useAdminWrite(props.reload);
  const closure = useAdminRead(
    useCallback(async () => {
      const months = [initial?.rcpt_dt, isDate(date) ? date : null]
        .filter((v): v is string => !!v)
        .map((v) => v.slice(0, 7))
        .sort();
      return months.length
        ? await getMonthClose(months[0], months[months.length - 1])
        : [];
    }, [initial?.rcpt_dt, date]),
  );
  const closed =
    !!closure.data &&
    [initial?.rcpt_dt, date].some(
      (value) =>
        value && monthStatus(closure.data!, value.slice(0, 7)) === "CLOSED",
    );
  const blocked =
    props.readOnly || mobile || closed || closure.loading || !!closure.error;
  const error = localError ?? write.error;
  useEffect(() => {
    if (error?.field)
      form.current
        ?.querySelector<HTMLElement>(
          `[name="${error.field.replace(/[^a-z_]/g, "")}"]`,
        )
        ?.focus();
  }, [error]);
  const change = () => {
    setDirty(true);
    setLocalError(null);
    write.clear();
  };
  const cancel = () => {
    if (!write.pending) {
      if (dirty) setLeaving(true);
      else props.onCancel();
    }
  };
  const save = () => {
    if (blocked || write.pending || write.uncertain) return;
    if (props.mode !== "delete") {
      let invalid = null;
      if (!isDate(date))
        invalid = { field: "rcpt_dt", message: "입금일을 입력하세요." };
      else if (
        !/^\d+$/.test(amount) ||
        !Number.isSafeInteger(Number(amount)) ||
        Number(amount) <= 0
      )
        invalid = {
          field: "rcpt_amt",
          message: "입금액은 원 단위 양수로 입력하세요.",
        };
      else if (
        Number(amount) >
        props.detail.billing.receivable_amt + (initial?.rcpt_amt ?? 0)
      )
        invalid = {
          field: "rcpt_amt",
          message: "입금액이 조정 후 청구 잔액을 초과합니다.",
        };
      else if (remark.length > 200)
        invalid = {
          field: "rmrk_desc",
          message: "비고는 200자 이하로 입력하세요.",
        };
      setLocalError(invalid);
      if (invalid) return;
    }
    const input = {
      rcpt_dt: date,
      rcpt_amt: Number(amount),
      rmrk_desc: remark || null,
    };
    void write.save(
      () =>
        props.mode === "delete"
          ? client.deleteBillingReceipt(props.billId, props.receipt.receipt_id)
          : props.mode === "update"
            ? client.updateBillingReceipt(
                props.billId,
                props.receipt.receipt_id,
                input,
              )
            : client.createBillingReceipt(props.billId, input),
      props.onSaved,
    );
  };
  const disabled =
    write.pending || write.uncertain || !!props.readOnly || mobile;
  const title =
    props.mode === "create"
      ? "입금 등록"
      : props.mode === "update"
        ? "입금 수정"
        : "입금 삭제";
  return (
    <>
      <Modal title={title} onCancel={cancel}>
        <form
          data-testid="receipt-modal"
          data-screen-id="SCR-UI-13-M5"
          ref={form}
          noValidate
          className={styles.form}
          onSubmit={(event) => {
            event.preventDefault();
            save();
          }}
        >
          <p>
            {props.detail.billing.bill_no} · {props.detail.billing.project_name}
          </p>
          <p>
            조정 청구 총액{" "}
            {formatMoney(BigInt(props.detail.billing.adjusted_total_amt))}원 ·
            미수금 {formatMoney(BigInt(props.detail.billing.receivable_amt))}원
          </p>
          <ErrorBanner
            error={write.error}
            pending={write.pending}
            onRetry={() => void write.recover()}
          />
          <ErrorBanner
            error={closure.error}
            onRetry={() => void closure.reload().catch(() => {})}
          />
          {closed && (
            <LockedBanner>
              입금일 월이 마감되어 등록·수정·삭제할 수 없습니다.
            </LockedBanner>
          )}
          {props.mode === "delete" ? (
            <p>
              이 입금을 삭제하시겠습니까? {initial!.rcpt_dt} ·{" "}
              {formatMoney(BigInt(initial!.rcpt_amt))}원
            </p>
          ) : (
            <>
              <label className={styles.field}>
                입금일 *
                <input
                  data-testid="receipt-date"
                  aria-label="입금일"
                  name="rcpt_dt"
                  type="date"
                  required
                  value={date}
                  disabled={disabled}
                  aria-invalid={error?.field === "rcpt_dt"}
                  onChange={(event) => {
                    setDate(event.target.value);
                    change();
                  }}
                />
                {error?.field === "rcpt_dt" && (
                  <span role="alert">{error.message}</span>
                )}
              </label>
              <label className={styles.field}>
                입금액(부가세 포함) *
                <input
                  data-testid="receipt-amount"
                  aria-label="입금액(부가세 포함)"
                  name="rcpt_amt"
                  inputMode="numeric"
                  required
                  value={amount}
                  disabled={disabled}
                  aria-invalid={error?.field === "rcpt_amt"}
                  onChange={(event) => {
                    setAmount(event.target.value);
                    change();
                  }}
                />
                {error?.field === "rcpt_amt" && (
                  <span role="alert">{error.message}</span>
                )}
              </label>
              <label className={styles.field}>
                비고
                <textarea
                  data-testid="receipt-remark"
                  aria-label="비고"
                  name="rmrk_desc"
                  maxLength={200}
                  value={remark}
                  disabled={disabled}
                  onChange={(event) => {
                    setRemark(event.target.value);
                    change();
                  }}
                />
                {error?.field === "rmrk_desc" && (
                  <span role="alert">{error.message}</span>
                )}
              </label>
            </>
          )}
          <div className={table.actions}>
            <Button
              type="button"
              data-testid="receipt-cancel"
              disabled={write.pending}
              onClick={cancel}
            >
              취소
            </Button>
            {!props.readOnly && !mobile && (
              <Can perm="UI-13.receipt">
                <Button
                  type="submit"
                  variant="primary"
                  data-testid="receipt-save"
                  pending={write.pending}
                  disabled={blocked || write.uncertain}
                >
                  {props.mode === "delete" ? "삭제 확인" : "저장"}
                </Button>
              </Can>
            )}
          </div>
        </form>
      </Modal>
      {leaving && (
        <ConfirmDialog
          title="저장하지 않은 내용이 있습니다."
          confirmLabel="나가기"
          cancelLabel="계속 작성"
          onCancel={() => setLeaving(false)}
          onConfirm={props.onCancel}
        >
          입력한 내용을 버리고 나갈까요?
        </ConfirmDialog>
      )}
    </>
  );
}
