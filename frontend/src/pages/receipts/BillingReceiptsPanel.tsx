import { useCallback, useEffect, useRef, useState } from "react";
import {
  Badge,
  Button,
  ConfirmDialog,
  LockedBanner,
  Modal,
  Skeleton,
} from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { can } from "../../shared/types";
import { formatMoney, isDate } from "../../lib/format";
import { useAdminRead, useAdminWrite } from "../admin/useAdminResource";
import { useDeveloperReadOnly } from "../developers/useDeveloperReadOnly";
import { getMonthClose, monthStatus } from "../shared/api";
import { seoulMonth } from "../shared/PeriodBar";
import {
  createBillingReceipt,
  deleteBillingReceipt,
  listBillingReceipts,
  updateBillingReceipt,
  type BillingReceipt,
  type BillingReceiptDetail,
} from "./client";
import tables from "../developers/Developers.module.css";
import styles from "./BillingReceipts.module.css";

type ReceiptMode =
  | { kind: "create" }
  | { kind: "update" | "delete"; row: BillingReceipt };
const money = (value: number) => `${formatMoney(BigInt(value))}원`;

export interface BillingReceiptsPanelProps {
  billId: number;
  selectedYear?: number;
  refreshVersion?: number;
  onChanged?: () => Promise<unknown>;
}

// UI-13 detail's local 입금 내역 region; no additional route or router.
export function BillingReceiptsPanel({
  billId,
  selectedYear,
  refreshVersion = 0,
  onChanged,
}: BillingReceiptsPanelProps) {
  const mobile = useDeveloperReadOnly();
  const currentYear = Number(seoulMonth().slice(0, 4));
  const readonly =
    mobile || (selectedYear != null && selectedYear < currentYear);
  const read = useCallback(async () => {
    const detail = await listBillingReceipts(billId);
    const months = [
      ...detail.receipts.map((r) => r.rcpt_dt.slice(0, 7)),
      seoulMonth(),
    ].sort();
    const closures = await getMonthClose(months[0], months[months.length - 1]);
    return { detail, closures };
  }, [billId, refreshVersion]);
  const state = useAdminRead(read);
  const [mode, setMode] = useState<ReceiptMode | null>(null);
  const [message, setMessage] = useState("");
  useEffect(() => {
    setMode(null);
    setMessage("");
  }, [billId, readonly]);
  const detail = state.data?.detail;
  const eligible =
    !!detail &&
    detail.billing.bill_stts_cd === "CONFIRM" &&
    detail.original_bill_id == null;
  const permitted = can("UI-13.receipt");
  const writable =
    !readonly && permitted && eligible && !state.loading && !state.error;
  const refresh = async () => {
    await state.reload();
    await onChanged?.();
  };
  return (
    <section
      role="tabpanel"
      aria-label="입금 내역"
      data-testid="billing-receipts-panel"
      className={styles.panel}
    >
      <div className={tables.toolbar}>
        <h3>입금 내역</h3>
        {writable && (
          <Button
            data-testid="billing-receipt-add"
            onClick={() => {
              setMode({ kind: "create" });
              setMessage("");
            }}
          >
            입금 등록
          </Button>
        )}
      </div>
      {readonly && (
        <LockedBanner>
          조회만 가능합니다. 과거 연도와 휴대폰에서는 입금을 변경할 수 없습니다.
        </LockedBanner>
      )}
      {!permitted && <LockedBanner>입금 권한이 없습니다.</LockedBanner>}
      {detail && !eligible && (
        <LockedBanner>
          확정된 원본 청구만 입금을 등록·수정·삭제할 수 있습니다.
        </LockedBanner>
      )}
      {message && (
        <p role="status" data-testid="billing-receipt-success">
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
        detail && (
          <>
            <ReceiptSummary detail={detail} />
            {!detail.receipts.length ? (
              <div className={styles.empty}>등록된 입금 내역이 없습니다.</div>
            ) : (
              <div className={tables.tableScroll}>
                <table className={tables.table} aria-label="입금 내역">
                  <thead>
                    <tr>
                      <th>입금일</th>
                      <th>입금액</th>
                      <th>비고</th>
                      <th>작업</th>
                    </tr>
                  </thead>
                  <tbody>
                    {detail.receipts.map((row) => {
                      const closed =
                        monthStatus(
                          state.data!.closures,
                          row.rcpt_dt.slice(0, 7),
                        ) === "CLOSED";
                      const past =
                        Number(row.rcpt_dt.slice(0, 4)) < currentYear;
                      return (
                        <tr
                          key={row.receipt_id}
                          data-testid={`billing-receipt-row-${row.receipt_id}`}
                        >
                          <td>
                            {row.rcpt_dt}{" "}
                            {closed && <Badge tone="locked">마감</Badge>}
                          </td>
                          <td className={tables.amount}>
                            {money(row.rcpt_amt)}
                          </td>
                          <td>{row.rmrk_desc ?? "–"}</td>
                          <td>
                            {writable && !closed && !past && (
                              <div className={tables.actions}>
                                <Button
                                  small
                                  data-testid={`billing-receipt-edit-${row.receipt_id}`}
                                  onClick={() =>
                                    setMode({ kind: "update", row })
                                  }
                                >
                                  수정
                                </Button>
                                <Button
                                  small
                                  data-testid={`billing-receipt-delete-${row.receipt_id}`}
                                  onClick={() =>
                                    setMode({ kind: "delete", row })
                                  }
                                >
                                  삭제
                                </Button>
                              </div>
                            )}
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            )}
            <details>
              <summary>변경 이력</summary>
              {detail.change_history.map((h, i) => (
                <p
                  className={styles.history}
                  key={`${h.changed_at}-${i}`}
                  data-testid={`billing-receipt-history-${i}`}
                >
                  {h.changed_at} · {h.user_id ?? "기본 사용자"} ·{" "}
                  {h.description}
                </p>
              ))}
            </details>
          </>
        )
      )}
      {!readonly && permitted && eligible && mode && detail && (
        <BillingReceiptForm
          key={
            mode.kind === "create"
              ? "new"
              : `${mode.kind}-${mode.row.receipt_id}`
          }
          billId={billId}
          detail={detail}
          mode={mode}
          reload={refresh}
          onCancel={() => setMode(null)}
          onSaved={() => {
            setMode(null);
            setMessage(
              mode.kind === "delete" ? "삭제했습니다." : "저장했습니다.",
            );
          }}
        />
      )}
    </section>
  );
}
function ReceiptSummary({ detail }: { detail: BillingReceiptDetail }) {
  const b = detail.billing;
  return (
    <div className={styles.summary} data-testid="billing-receipt-summary">
      <p>
        청구번호<strong>{b.bill_no}</strong>
      </p>
      <p>
        조정 청구 총액<strong>{money(b.adjusted_total_amt)}</strong>
      </p>
      <p>
        입금 합<strong>{money(b.received_amt)}</strong>
      </p>
      <p>
        미수금<strong>{money(b.receivable_amt)}</strong>
      </p>
      {b.bill_stts_cd === "CONFIRM" && (
        <Badge tone={b.receivable_amt === 0 ? "success" : "warning"}>
          {b.receivable_amt === 0 ? "입금 완료" : "미수"}
        </Badge>
      )}
    </div>
  );
}

function BillingReceiptForm({
  billId,
  detail,
  mode,
  reload,
  onCancel,
  onSaved,
}: {
  billId: number;
  detail: BillingReceiptDetail;
  mode: ReceiptMode;
  reload: () => Promise<unknown>;
  onCancel: () => void;
  onSaved: () => void;
}) {
  const initial = mode.kind === "create" ? null : mode.row;
  const [date, setDate] = useState(initial?.rcpt_dt ?? "");
  const [amount, setAmount] = useState(initial ? String(initial.rcpt_amt) : "");
  const [remark, setRemark] = useState(initial?.rmrk_desc ?? "");
  const [dirty, setDirty] = useState(false),
    [leaving, setLeaving] = useState(false);
  const [localError, setLocalError] = useState<{
    field: string;
    message: string;
  } | null>(null);
  const form = useRef<HTMLFormElement>(null);
  const write = useAdminWrite(reload);
  const removal = mode.kind === "delete";
  const closure = useAdminRead(
    useCallback(async () => {
      const months = [date, initial?.rcpt_dt]
        .filter((d): d is string => !!d && isDate(d))
        .map((d) => d.slice(0, 7))
        .sort();
      return months.length
        ? getMonthClose(months[0], months[months.length - 1])
        : [];
    }, [date, initial?.rcpt_dt]),
  );
  const closed =
    closure.data?.some(
      (c) =>
        c.status === "CLOSED" &&
        [date.slice(0, 7), initial?.rcpt_dt.slice(0, 7)].includes(c.month),
    ) ?? false;
  const past = [date, initial?.rcpt_dt].some(
    (d) => d && Number(d.slice(0, 4)) < Number(seoulMonth().slice(0, 4)),
  );
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
      else onCancel();
    }
  };
  const save = () => {
    let invalid = null;
    if (!removal) {
      if (!isDate(date))
        invalid = { field: "rcpt_dt", message: "입금일을 입력하세요." };
      else if (
        !/^\d+$/.test(amount) ||
        !Number.isSafeInteger(Number(amount)) ||
        Number(amount) < 1
      )
        invalid = {
          field: "rcpt_amt",
          message: "입금액은 원 단위 양수로 입력하세요.",
        };
      else if (remark.length > 200)
        invalid = {
          field: "rmrk_desc",
          message: "비고는 200자 이하로 입력하세요.",
        };
    }
    if (closed || past)
      invalid = {
        field: "rcpt_dt",
        message: closed
          ? "마감된 달은 변경할 수 없습니다."
          : "과거 연도는 조회만 가능합니다.",
      };
    setLocalError(invalid);
    if (invalid || closure.loading || closure.error) return;
    const input = {
      rcpt_dt: date,
      rcpt_amt: Number(amount),
      rmrk_desc: remark || null,
    };
    void write.save(
      () =>
        mode.kind === "create"
          ? createBillingReceipt(billId, input)
          : mode.kind === "update"
            ? updateBillingReceipt(billId, mode.row.receipt_id, input)
            : deleteBillingReceipt(billId, mode.row.receipt_id),
      onSaved,
    );
  };
  const blocked = write.pending || write.uncertain;
  const fieldError = (field: string) =>
    error?.field === field ? error.message : null;
  return (
    <>
      <Modal
        title={
          mode.kind === "create"
            ? "입금 등록"
            : removal
              ? "입금 삭제"
              : "입금 수정"
        }
        onCancel={cancel}
      >
        <form
          ref={form}
          noValidate
          data-testid="billing-receipt-dialog"
          data-screen-id="SCR-UI-13-M5"
          className={styles.form}
          onSubmit={(e) => {
            e.preventDefault();
            save();
          }}
        >
          <ReceiptSummary detail={detail} />
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
            <LockedBanner>마감된 달은 변경할 수 없습니다.</LockedBanner>
          )}
          {past && <LockedBanner>과거 연도는 조회만 가능합니다.</LockedBanner>}
          {removal ? (
            <p>
              선택한 {initial!.rcpt_dt} 입금 {money(initial!.rcpt_amt)}을
              삭제하시겠습니까? 삭제 전 값은 변경 이력에 보존됩니다.
            </p>
          ) : (
            <>
              <div className={styles.fields}>
                <label className={styles.field}>
                  입금일 *
                  <input
                    name="rcpt_dt"
                    aria-label="입금일"
                    type="date"
                    required
                    data-testid="billing-receipt-date"
                    disabled={blocked}
                    value={date}
                    aria-invalid={!!fieldError("rcpt_dt")}
                    onChange={(e) => {
                      setDate(e.target.value);
                      change();
                    }}
                  />
                  {fieldError("rcpt_dt") && (
                    <span className={styles.error}>
                      {fieldError("rcpt_dt")}
                    </span>
                  )}
                </label>
                <label className={styles.field}>
                  입금액 *
                  <input
                    name="rcpt_amt"
                    aria-label="입금액"
                    inputMode="numeric"
                    required
                    data-testid="billing-receipt-amount"
                    disabled={blocked}
                    value={amount}
                    aria-invalid={!!fieldError("rcpt_amt")}
                    onChange={(e) => {
                      setAmount(e.target.value);
                      change();
                    }}
                  />
                  {fieldError("rcpt_amt") && (
                    <span className={styles.error}>
                      {fieldError("rcpt_amt")}
                    </span>
                  )}
                </label>
              </div>
              <p>
                입금액은 부가세 포함 금액입니다. 입금일은 매출 귀속월을 바꾸지
                않습니다.
              </p>
              <label className={styles.field}>
                비고
                <textarea
                  name="rmrk_desc"
                  aria-label="비고"
                  maxLength={200}
                  data-testid="billing-receipt-remark"
                  disabled={blocked}
                  value={remark}
                  aria-invalid={!!fieldError("rmrk_desc")}
                  onChange={(e) => {
                    setRemark(e.target.value);
                    change();
                  }}
                />
                {fieldError("rmrk_desc") && (
                  <span className={styles.error}>
                    {fieldError("rmrk_desc")}
                  </span>
                )}
              </label>
            </>
          )}
          <div className={tables.actions}>
            <Button
              type="button"
              data-testid="billing-receipt-cancel"
              disabled={write.pending}
              onClick={cancel}
            >
              취소
            </Button>
            <Button
              type="submit"
              variant="primary"
              data-testid="billing-receipt-save"
              pending={write.pending}
              disabled={
                write.uncertain ||
                closure.loading ||
                !!closure.error ||
                closed ||
                past
              }
            >
              {removal ? "삭제 확인" : "저장"}
            </Button>
          </div>
        </form>
      </Modal>
      {leaving && (
        <ConfirmDialog
          title="저장하지 않은 내용이 있습니다."
          confirmLabel="나가기"
          cancelLabel="계속 작성"
          onCancel={() => setLeaving(false)}
          onConfirm={onCancel}
        >
          입력한 내용을 버리고 나갈까요?
        </ConfirmDialog>
      )}
    </>
  );
}
