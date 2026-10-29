import { useEffect, useState } from "react";
import { Badge, Button, Modal } from "../../shared/Controls";
import { ApiFailure } from "../../shared/api";
import * as client from "./client";
import type { BillingDetail, BillingReceipt } from "./client";
import table from "../developers/Developers.module.css";
import styles from "./BillingReview.module.css";

export const billingMoney = (value: number | null | undefined) =>
  value == null ? "–" : value.toLocaleString("ko-KR");
export function BillingDetailPanel({
  detail,
  readOnly,
  onClose,
  onChanged,
  onEdit,
}: {
  detail: BillingDetail;
  readOnly: boolean;
  onClose: () => void;
  onChanged: (detail: BillingDetail) => Promise<void>;
  onEdit: () => void;
}) {
  const [action, setAction] = useState<
    "confirm" | "cancel" | "amendments" | null
  >(null);
  const [receipt, setReceipt] = useState<BillingReceipt | "new" | null>(null);
  const [error, setError] = useState("");
  const b = detail.billing;
  useEffect(() => {
    if (readOnly) {
      setAction(null);
      setReceipt(null);
    }
  }, [readOnly]);
  return (
    <div className={table.scrim}>
      <aside
        className={table.drawer}
        aria-label="청구 상세"
        data-testid="billing-detail"
      >
        <div className={table.drawerHeader}>
          <h2>청구 상세</h2>
          <Button data-testid="billing-detail-close" onClick={onClose}>
            닫기
          </Button>
        </div>
        <h3>
          {b.bill_no} · {b.project_name}
        </h3>
        <Badge
          tone={
            b.bill_stts_cd === "CONFIRM"
              ? "success"
              : b.bill_stts_cd === "DRAFT"
                ? "warning"
                : "neutral"
          }
        >
          {b.bill_stts_cd}
        </Badge>
        <dl className={table.fields}>
          {[
            ["귀속월", b.atrb_ym],
            ["청구일", b.bill_dt],
            ["공급가액", billingMoney(b.sply_amt)],
            ["부가세", billingMoney(b.vat_amt)],
            ["합계", billingMoney(b.tot_amt)],
            ["입금 예정일", b.rcpt_plan_dt],
            ["세금계산서 번호", b.txinv_no],
            ["작성일", b.txinv_dt],
            ["누적 입금", billingMoney(b.received_amt)],
            ["조정 청구 총액", billingMoney(b.adjusted_total_amt)],
            ["미수금", billingMoney(b.receivable_amt)],
          ].map(([label, value]) => (
            <div key={label}>
              <dt>{label}</dt>
              <dd>{value ?? "–"}</dd>
            </div>
          ))}
        </dl>
        {detail.warnings.map((w, i) => (
          <p className={styles.message} key={i}>
            {w}
          </p>
        ))}
        {detail.original_bill_id !== null && (
          <p>원본 청구 #{detail.original_bill_id}</p>
        )}
        <div className={table.tableScroll}>
          <table className={table.table}>
            <thead>
              <tr>
                {["표기 인력", "귀속월", "MM", "단가", "금액"].map((c) => (
                  <th key={c}>{c}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {detail.details.map((l, i) => (
                <tr key={`${l.asgn_id}-${i}`} data-testid={`billing-line-${i}`}>
                  <td>{l.display_name ?? "수정 청구"}</td>
                  <td>{l.work_ym}</td>
                  <td>{l.mm_qty ?? "–"}</td>
                  <td className={table.amount}>{billingMoney(l.untprc_amt)}</td>
                  <td className={table.amount}>{billingMoney(l.bill_amt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <h3>입금 이력</h3>
        {detail.receipts.length === 0 && <p>등록된 입금이 없습니다.</p>}
        {detail.receipts.map((r) => (
          <div
            key={r.receipt_id}
            data-testid={`billing-receipt-${r.receipt_id}`}
          >
            {r.rcpt_dt} · {billingMoney(r.rcpt_amt)}원 · {r.rmrk_desc}
            {!readOnly && (
              <Button
                data-testid={`billing-receipt-edit-${r.receipt_id}`}
                onClick={() => setReceipt(r)}
              >
                입금 수정
              </Button>
            )}
          </div>
        ))}
        <h3>수정 청구</h3>
        {detail.amendments.map((a) => (
          <p key={a.bill_id}>
            {a.bill_no} · {billingMoney(a.sply_amt)} · {a.bill_stts_cd}
          </p>
        ))}
        <h3>변경 이력</h3>
        {detail.change_history.map((h, i) => (
          <p key={i}>
            {h.changed_at} · {h.description} · {h.user_id ?? "기본 사용자"}
          </p>
        ))}
        {error && <p role="alert">{error}</p>}
        {!readOnly && (
          <div className={table.actions}>
            {b.bill_stts_cd === "DRAFT" && (
              <>
                <Button data-testid="billing-edit" onClick={onEdit}>
                  수정
                </Button>
                <Button
                  data-testid="billing-recalculate"
                  onClick={async () => {
                    try {
                      await onChanged(
                        await client.saveBilling(b.bill_id!, {
                          recalculate: true,
                        }),
                      );
                    } catch (e) {
                      setError((e as Error).message);
                    }
                  }}
                >
                  다시 계산
                </Button>
                <Button
                  data-testid="billing-confirm"
                  onClick={() => setAction("confirm")}
                >
                  확정
                </Button>
              </>
            )}
            {["DRAFT", "CONFIRM"].includes(b.bill_stts_cd) &&
              b.received_amt === 0 && (
                <Button
                  data-testid="billing-cancel"
                  onClick={() => setAction("cancel")}
                >
                  청구 취소
                </Button>
              )}
            {b.bill_stts_cd === "CONFIRM" &&
              detail.original_bill_id === null && (
                <>
                  <Button
                    data-testid="billing-amend"
                    onClick={() => setAction("amendments")}
                  >
                    수정 청구
                  </Button>
                  <Button
                    data-testid="billing-receipt-add"
                    onClick={() => setReceipt("new")}
                  >
                    입금 등록
                  </Button>
                </>
              )}
          </div>
        )}
        {action && (
          <BillingActionDialog
            detail={detail}
            action={action}
            onClose={() => setAction(null)}
            onSaved={async (next) => {
              await onChanged(next);
              setAction(null);
            }}
          />
        )}
        {receipt && (
          <BillingReceiptDialog
            detail={detail}
            receipt={receipt}
            onClose={() => setReceipt(null)}
            onSaved={async () => {
              await onChanged(await client.getBilling(b.bill_id!));
              setReceipt(null);
            }}
          />
        )}
      </aside>
    </div>
  );
}
export function BillingActionDialog({
  detail,
  action,
  onClose,
  onSaved,
}: {
  detail: BillingDetail;
  action: "confirm" | "cancel" | "amendments";
  onClose: () => void;
  onSaved: (detail: BillingDetail) => Promise<void>;
}) {
  const [reason, setReason] = useState("");
  const [supply, setSupply] = useState("");
  const [vat, setVat] = useState("");
  const [date, setDate] = useState("");
  const [due, setDue] = useState("");
  const [invoice, setInvoice] = useState("");
  const [invoiceDate, setInvoiceDate] = useState("");
  const [error, setError] = useState("");
  const [field, setField] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const title =
    action === "confirm"
      ? "청구 확정"
      : action === "cancel"
        ? "청구 취소"
        : "수정 청구";
  async function submit() {
    if (pending) return;
    if (action !== "confirm" && !reason.trim()) {
      setError("사유를 입력하세요.");
      setField("reason");
      return;
    }
    if (
      action === "amendments" &&
      (!Number.isSafeInteger(Number(supply)) ||
        Number(supply) >= 0 ||
        vat === "" ||
        !Number.isSafeInteger(Number(vat)) ||
        Number(vat) > 0)
    ) {
      setError("공급가는 음수, 부가세는 0 이하의 정수로 입력하세요.");
      return;
    }
    setPending(true);
    setError("");
    setField(null);
    try {
      await onSaved(
        await client.billingWrite(
          detail.billing.bill_id!,
          action,
          action === "confirm"
            ? undefined
            : action === "cancel"
              ? { reason: reason.trim() }
              : {
                  reason: reason.trim(),
                  sply_amt: Number(supply),
                  vat_amt: Number(vat),
                  bill_dt: date || null,
                  rcpt_plan_dt: due || null,
                  txinv_no: invoice,
                  txinv_dt: invoiceDate || null,
                },
        ),
      );
    } catch (e) {
      setError((e as Error).message);
      setField(e instanceof ApiFailure ? e.field : null);
    } finally {
      setPending(false);
    }
  }
  return (
    <Modal
      title={title}
      onCancel={onClose}
      confirmation={action !== "amendments"}
    >
      <section data-testid={`billing-${action}-dialog`}>
        <p>
          원본 청구: {detail.billing.bill_no} · {detail.billing.project_name}
        </p>
        {action === "confirm" && (
          <p>
            확정 뒤 공급가액과 원천 상세는 변경할 수 없습니다. 세금계산서는
            기록만 합니다.
          </p>
        )}
        {action !== "confirm" && (
          <label>
            사유
            <textarea
              data-testid="billing-action-reason"
              aria-label="사유"
              aria-invalid={field === "reason"}
              maxLength={500}
              value={reason}
              onChange={(e) => setReason(e.target.value)}
            />
          </label>
        )}
        {action === "amendments" && (
          <>
            <p>
              조정 총액 {billingMoney(detail.billing.adjusted_total_amt)}원 ·
              입금 합 {billingMoney(detail.billing.received_amt)}원
            </p>
            <div className={styles.fields}>
              {[
                ["공급가액", supply, setSupply, "number"],
                ["부가세", vat, setVat, "number"],
                ["청구일", date, setDate, "date"],
                ["입금 예정일", due, setDue, "date"],
                ["세금계산서 번호", invoice, setInvoice, "text"],
                ["작성일", invoiceDate, setInvoiceDate, "date"],
              ].map(([label, value, set, type], i) => (
                <label key={i}>
                  {label as string}
                  <input
                    data-testid={`billing-amendment-field-${i}`}
                    aria-label={label as string}
                    type={type as string}
                    value={value as string}
                    onChange={(e) =>
                      (set as (s: string) => void)(e.target.value)
                    }
                  />
                </label>
              ))}
            </div>
          </>
        )}
        {error && (
          <p role="alert">
            {error}
            {field ? ` (${field})` : ""}
          </p>
        )}
        <div className={table.actions}>
          <Button
            data-testid="billing-action-close"
            disabled={pending}
            onClick={onClose}
          >
            닫기
          </Button>
          <Button
            data-testid="billing-action-save"
            variant="primary"
            pending={pending}
            onClick={() => void submit()}
          >
            {action === "cancel"
              ? "청구 취소 확인"
              : action === "confirm"
                ? "확정 확인"
                : "저장"}
          </Button>
        </div>
      </section>
    </Modal>
  );
}
function BillingReceiptDialog({
  detail,
  receipt,
  onClose,
  onSaved,
}: {
  detail: BillingDetail;
  receipt: BillingReceipt | "new";
  onClose: () => void;
  onSaved: () => Promise<void>;
}) {
  const [date, setDate] = useState(receipt === "new" ? "" : receipt.rcpt_dt);
  const [amount, setAmount] = useState(
    receipt === "new" ? "" : String(receipt.rcpt_amt),
  );
  const [remark, setRemark] = useState(
    receipt === "new" ? "" : (receipt.rmrk_desc ?? ""),
  );
  const [remove, setRemove] = useState(false),
    [pending, setPending] = useState(false),
    [error, setError] = useState("");
  async function save() {
    if (
      !remove &&
      (!date || !Number.isSafeInteger(Number(amount)) || Number(amount) <= 0)
    ) {
      setError("입금일과 양수 정수 금액을 입력하세요.");
      return;
    }
    setPending(true);
    try {
      await client.saveBillingReceipt(
        detail.billing.bill_id!,
        receipt === "new" ? null : receipt.receipt_id,
        { rcpt_dt: date, rcpt_amt: Number(amount), rmrk_desc: remark },
        remove,
      );
      await onSaved();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setPending(false);
    }
  }
  return (
    <Modal
      title={
        remove
          ? "입금 삭제 확인"
          : receipt === "new"
            ? "입금 등록"
            : "입금 수정"
      }
      onCancel={onClose}
    >
      <section data-testid="billing-receipt-dialog">
        <p>
          미수금 {billingMoney(detail.billing.receivable_amt)}원 (부가세 포함)
        </p>
        {!remove && (
          <div className={styles.fields}>
            <label>
              입금일
              <input
                data-testid="billing-receipt-date"
                type="date"
                value={date}
                onChange={(e) => setDate(e.target.value)}
              />
            </label>
            <label>
              입금액
              <input
                data-testid="billing-receipt-amount"
                type="number"
                value={amount}
                onChange={(e) => setAmount(e.target.value)}
              />
            </label>
            <label>
              비고
              <input
                data-testid="billing-receipt-remark"
                maxLength={200}
                value={remark}
                onChange={(e) => setRemark(e.target.value)}
              />
            </label>
          </div>
        )}
        {error && <p role="alert">{error}</p>}
        <div className={table.actions}>
          <Button data-testid="billing-receipt-close" onClick={onClose}>
            취소
          </Button>
          {receipt !== "new" && !remove && (
            <Button
              data-testid="billing-receipt-delete"
              onClick={() => setRemove(true)}
            >
              삭제
            </Button>
          )}
          <Button
            data-testid="billing-receipt-save"
            pending={pending}
            onClick={() => void save()}
          >
            {remove ? "삭제 확인" : "저장"}
          </Button>
        </div>
      </section>
    </Modal>
  );
}
