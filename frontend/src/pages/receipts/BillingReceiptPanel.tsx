import { useCallback, useEffect, useRef, useState } from "react";
import {
  Badge,
  Button,
  LockedBanner,
  Modal,
  Skeleton,
  SuccessToast,
} from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { Can } from "../../shared/Can";
import { useAdminRead, useAdminWrite } from "../admin/useAdminResource";
import { useDeveloperReadOnly } from "../developers/useDeveloperReadOnly";
import { getMonthClose, monthStatus } from "../shared/api";
import * as client from "./client";
import type { BillingReceipt, ReceiptBillingDetail } from "./client";
import table from "../developers/Developers.module.css";
import styles from "./BillingReceipts.module.css";

const money = (amount: number) => `${amount.toLocaleString("ko-KR")}원`;
const currentYear = () =>
  Number(
    new Intl.DateTimeFormat("en", {
      timeZone: "Asia/Seoul",
      year: "numeric",
    }).format(new Date()),
  );

export function BillingReceiptPanel({
  billId,
  year,
  onChanged,
}: {
  billId: number;
  year: number;
  onChanged: () => Promise<unknown>;
}) {
  const read = useCallback(async () => {
    const detail = await client.listBillingReceipts(billId);
    const months = detail.receipts.map((r) => r.rcpt_dt.slice(0, 7)).sort();
    return {
      detail,
      months: months.length
        ? await getMonthClose(months[0], months[months.length - 1])
        : [],
    };
  }, [billId]);
  const state = useAdminRead(read);
  const mobile = useDeveloperReadOnly();
  const readOnly = mobile || year < currentYear();
  const [editing, setEditing] = useState<BillingReceipt | "new" | null>(null);
  const [deleting, setDeleting] = useState<BillingReceipt | null>(null);
  const [message, setMessage] = useState("");
  const detail = state.data?.detail;
  const writable =
    !readOnly &&
    detail?.billing.bill_stts_cd === "CONFIRM" &&
    detail.original_bill_id == null;
  useEffect(() => {
    setEditing(null);
    setDeleting(null);
    setMessage("");
  }, [billId, readOnly]);
  const refresh = async () => {
    await state.reload();
    await onChanged();
  };
  const closed = (row: BillingReceipt) =>
    monthStatus(state.data?.months ?? [], row.rcpt_dt.slice(0, 7)) === "CLOSED";
  return (
    <section
      role="tabpanel"
      aria-label="입금 내역"
      data-testid="billing-receipt-panel"
    >
      <div className={table.toolbar}>
        <h3>입금 내역</h3>
        {writable && !state.error && (
          <Can perm="UI-13.receipt">
            <Button data-testid="receipt-add" onClick={() => setEditing("new")}>
              입금 등록
            </Button>
          </Can>
        )}
      </div>
      {readOnly && (
        <LockedBanner>
          {mobile
            ? "휴대폰에서는 조회만 가능합니다."
            : "과거 연도는 조회만 가능합니다."}
        </LockedBanner>
      )}
      {detail && !readOnly && !writable && (
        <LockedBanner>
          확정 원본 청구만 입금을 등록·수정·삭제할 수 있습니다.
        </LockedBanner>
      )}
      {message && (
        <SuccessToast message={message} onDismiss={() => setMessage("")} />
      )}
      <ErrorBanner
        error={state.error}
        onRetry={() => void state.reload().catch(() => {})}
      />
      {state.loading ? (
        <Skeleton />
      ) : (
        !state.error &&
        detail && (
          <>
            <div className={styles.summary} data-testid="receipt-balance">
              <p>
                청구번호 <strong>{detail.billing.bill_no ?? "–"}</strong>
              </p>
              <p>
                조정 청구 총액{" "}
                <strong>{money(detail.billing.adjusted_total_amt)}</strong>
              </p>
              <p>
                입금액 <strong>{money(detail.billing.received_amt)}</strong>
              </p>
              <p>
                미수금 <strong>{money(detail.billing.receivable_amt)}</strong>
              </p>
              <Badge
                tone={
                  detail.billing.receivable_amt === 0 ? "success" : "warning"
                }
              >
                {detail.billing.receivable_amt === 0 ? "입금 완료" : "미수"}
              </Badge>
            </div>
            <div className={table.tableScroll}>
              <table className={table.table}>
                <thead>
                  <tr>
                    <th>입금일</th>
                    <th className={table.amount}>입금액</th>
                    <th>비고</th>
                    <th>작업</th>
                  </tr>
                </thead>
                <tbody>
                  {detail.receipts.map((row) => (
                    <tr
                      key={row.receipt_id}
                      data-testid={`receipt-row-${row.receipt_id}`}
                    >
                      <td>{row.rcpt_dt}</td>
                      <td className={table.amount}>{money(row.rcpt_amt)}</td>
                      <td>{row.rmrk_desc ?? "–"}</td>
                      <td>
                        {closed(row) ? (
                          <Badge tone="locked">마감</Badge>
                        ) : (
                          writable && (
                            <Can perm="UI-13.receipt">
                              <div className={table.actions}>
                                <Button
                                  small
                                  data-testid={`receipt-edit-${row.receipt_id}`}
                                  onClick={() => setEditing(row)}
                                >
                                  수정
                                </Button>
                                <Button
                                  small
                                  data-testid={`receipt-delete-${row.receipt_id}`}
                                  onClick={() => setDeleting(row)}
                                >
                                  삭제
                                </Button>
                              </div>
                            </Can>
                          )
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {detail.receipts.length === 0 && (
                <div className={table.empty}>등록된 입금 내역이 없습니다.</div>
              )}
            </div>
          </>
        )
      )}
      {writable && editing && detail && (
        <BillingReceiptForm
          key={editing === "new" ? "new" : editing.receipt_id}
          billId={billId}
          detail={detail}
          initial={editing === "new" ? undefined : editing}
          reload={refresh}
          onCancel={() => setEditing(null)}
          onSaved={() => {
            setEditing(null);
            setMessage("입금을 저장했습니다.");
          }}
        />
      )}
      {writable && deleting && detail && (
        <ReceiptDeletion
          billId={billId}
          row={deleting}
          detail={detail}
          reload={refresh}
          onCancel={() => setDeleting(null)}
          onSaved={() => {
            setDeleting(null);
            setMessage("입금을 삭제했습니다.");
          }}
        />
      )}
    </section>
  );
}

function ReceiptIdentity({ detail }: { detail: ReceiptBillingDetail }) {
  return (
    <div className={styles.summary}>
      <p>청구번호 {detail.billing.bill_no ?? "–"}</p>
      <p>조정 청구 총액 {money(detail.billing.adjusted_total_amt)}</p>
      <p>미수금 {money(detail.billing.receivable_amt)}</p>
    </div>
  );
}

export function BillingReceiptForm({
  billId,
  detail,
  initial,
  reload,
  onCancel,
  onSaved,
}: {
  billId: number;
  detail: ReceiptBillingDetail;
  initial?: BillingReceipt;
  reload: () => Promise<unknown>;
  onCancel: () => void;
  onSaved: () => void;
}) {
  const [date, setDate] = useState(initial?.rcpt_dt ?? "");
  const [amount, setAmount] = useState(initial?.rcpt_amt.toString() ?? "");
  const [remark, setRemark] = useState(initial?.rmrk_desc ?? "");
  const [dirty, setDirty] = useState(false),
    [leaving, setLeaving] = useState(false);
  const [localError, setLocalError] = useState<{
    field: string;
    message: string;
  } | null>(null);
  const form = useRef<HTMLFormElement>(null);
  const write = useAdminWrite(reload);
  const error = localError ?? write.error;
  const monthRead = useCallback(async () => {
    const months = [date.slice(0, 7), initial?.rcpt_dt.slice(0, 7)]
      .filter((v): v is string => !!v && /^\d{4}-\d{2}$/.test(v))
      .sort();
    return months.length
      ? getMonthClose(months[0], months[months.length - 1])
      : [];
  }, [date, initial?.rcpt_dt]);
  const months = useAdminRead(monthRead);
  const locked = [date, initial?.rcpt_dt].some(
    (v) => !!v && monthStatus(months.data ?? [], v.slice(0, 7)) === "CLOSED",
  );
  const blocked = write.pending || write.uncertain;
  useEffect(() => {
    if (
      error?.field &&
      ["rcpt_dt", "rcpt_amt", "rmrk_desc"].includes(error.field)
    )
      form.current
        ?.querySelector<HTMLElement>(`[name="${error.field}"]`)
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
      else onCancel();
    }
  };
  const fieldError = (key: string) =>
    error?.field === key ? error.message : null;
  const save = () => {
    let field = "";
    const number = Number(amount.replaceAll(",", ""));
    if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) field = "rcpt_dt";
    else if (
      !/^\d+$/.test(amount.replaceAll(",", "")) ||
      !Number.isSafeInteger(number) ||
      number < 1
    )
      field = "rcpt_amt";
    else if (
      number >
      detail.billing.receivable_amt + (initial?.rcpt_amt ?? 0)
    ) {
      setLocalError({
        field: "rcpt_amt",
        message: "입금액이 미수 잔액을 초과합니다.",
      });
      return;
    } else if ([...remark].length > 200) field = "rmrk_desc";
    if (field) {
      setLocalError({ field, message: "필수 항목과 입력 범위를 확인하세요." });
      return;
    }
    if (locked || months.loading || months.error) return;
    const input = {
      rcpt_dt: date,
      rcpt_amt: number,
      rmrk_desc: remark || null,
    };
    void write.save(
      () =>
        initial
          ? client.updateBillingReceipt(billId, initial.receipt_id, input)
          : client.createBillingReceipt(billId, input),
      onSaved,
    );
  };
  return (
    <Modal title={initial ? "입금 수정" : "입금 등록"} onCancel={cancel}>
      <form
        ref={form}
        className={styles.form}
        noValidate
        data-testid="receipt-form"
        data-screen-id="SCR-UI-13-M5"
        onSubmit={(e) => {
          e.preventDefault();
          save();
        }}
      >
        <ReceiptIdentity detail={detail} />
        <ErrorBanner
          error={write.error}
          pending={write.pending}
          onRetry={write.uncertain ? () => void write.recover() : undefined}
        />
        <ErrorBanner
          error={months.error}
          onRetry={() => void months.reload().catch(() => {})}
        />
        {locked && (
          <LockedBanner>
            마감된 입금월은 수정할 수 없습니다. 기존·새 입금월을 확인하세요.
          </LockedBanner>
        )}
        <label>
          입금일 *
          <input
            name="rcpt_dt"
            data-testid="receipt-date"
            type="date"
            value={date}
            disabled={blocked}
            aria-invalid={!!fieldError("rcpt_dt")}
            onChange={(e) => {
              setDate(e.target.value);
              change();
            }}
          />
          {fieldError("rcpt_dt") && (
            <span className={styles.error}>{fieldError("rcpt_dt")}</span>
          )}
        </label>
        <label>
          입금액 *
          <input
            name="rcpt_amt"
            data-testid="receipt-amount"
            inputMode="numeric"
            value={amount}
            disabled={blocked}
            aria-invalid={!!fieldError("rcpt_amt")}
            onChange={(e) => {
              setAmount(e.target.value);
              change();
            }}
          />
          {fieldError("rcpt_amt") && (
            <span className={styles.error}>{fieldError("rcpt_amt")}</span>
          )}
        </label>
        <p>
          부가세 포함 금액을 입력하세요. 입금일은 매출 귀속월을 변경하지
          않습니다.
        </p>
        <label>
          비고
          <textarea
            name="rmrk_desc"
            data-testid="receipt-remark"
            maxLength={200}
            value={remark}
            disabled={blocked}
            aria-invalid={!!fieldError("rmrk_desc")}
            onChange={(e) => {
              setRemark(e.target.value);
              change();
            }}
          />
          {fieldError("rmrk_desc") && (
            <span className={styles.error}>{fieldError("rmrk_desc")}</span>
          )}
        </label>
        <div className={table.actions}>
          <Button
            type="button"
            data-testid="receipt-cancel"
            disabled={write.pending}
            onClick={cancel}
          >
            취소
          </Button>
          <Button
            type="submit"
            variant="primary"
            data-testid="receipt-save"
            pending={write.pending}
            disabled={
              write.uncertain || locked || months.loading || !!months.error
            }
          >
            저장
          </Button>
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

function ReceiptDeletion({
  billId,
  row,
  detail,
  reload,
  onCancel,
  onSaved,
}: {
  billId: number;
  row: BillingReceipt;
  detail: ReceiptBillingDetail;
  reload: () => Promise<unknown>;
  onCancel: () => void;
  onSaved: () => void;
}) {
  const write = useAdminWrite(reload);
  return (
    <Modal
      title="이 입금을 삭제할까요?"
      confirmation
      onCancel={() => {
        if (!write.pending) onCancel();
      }}
    >
      <div data-testid="receipt-delete-dialog">
        <ReceiptIdentity detail={detail} />
        <p>
          {row.rcpt_dt} · {money(row.rcpt_amt)} 입금이 삭제되고 미수금이
          늘어납니다.
        </p>
        <ErrorBanner
          error={write.error}
          pending={write.pending}
          onRetry={write.uncertain ? () => void write.recover() : undefined}
        />
        <div className={table.actions}>
          <Button
            data-testid="receipt-delete-cancel"
            disabled={write.pending}
            onClick={onCancel}
          >
            취소
          </Button>
          <Button
            data-testid="receipt-delete-confirm"
            variant="primary"
            pending={write.pending}
            disabled={write.uncertain}
            onClick={() =>
              void write.save(
                () => client.deleteBillingReceipt(billId, row.receipt_id),
                onSaved,
              )
            }
          >
            삭제 확인
          </Button>
        </div>
      </div>
    </Modal>
  );
}
