import { useCallback, useEffect, useState } from "react";
import {
  Badge,
  Button,
  LockedBanner,
  Skeleton,
  SuccessToast,
} from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { Can } from "../../shared/Can";
import { useAdminRead } from "../admin/useAdminResource";
import { getMonthClose, monthStatus } from "../shared/api";
import { seoulMonth } from "../shared/PeriodBar";
import { useDeveloperReadOnly } from "../developers/useDeveloperReadOnly";
import { UI13M5 } from "./UI13M5";
import { listBillingReceipts, type BillingReceipt } from "./client";
import styles from "../developers/Developers.module.css";

export function UI13ReceiptPanel({
  billId,
  year,
  onChanged,
}: {
  billId: number;
  year: number;
  onChanged: () => Promise<unknown>;
}) {
  const read = useCallback(() => listBillingReceipts(billId), [billId]);
  const state = useAdminRead(read);
  const calendar = useAdminRead(
    useCallback(() => getMonthClose("0001-01", "9999-12"), []),
  );
  const mobile = useDeveloperReadOnly();
  const readonly = mobile || year < Number(seoulMonth().slice(0, 4));
  const [action, setAction] = useState<{
    mode: "create" | "update" | "delete";
    receipt?: BillingReceipt;
  } | null>(null);
  const [message, setMessage] = useState("");
  useEffect(() => {
    setAction(null);
    setMessage("");
  }, [billId, readonly]);
  const refresh = async () => {
    await state.reload();
    await calendar.reload();
    await onChanged();
  };
  const detail = state.data;
  const eligible =
    !readonly &&
    detail?.billing.bill_stts_cd === "CONFIRM" &&
    detail.original_bill_id == null;
  const canWrite =
    eligible && !calendar.loading && !calendar.error && !!calendar.data;
  return (
    <section aria-label="입금 내역" data-testid="receipt-panel">
      <div className={styles.toolbar}>
        <h3>입금 내역</h3>
        {canWrite && (
          <Can perm="UI-13.receipt">
            <Button
              data-testid="receipt-add"
              onClick={() => setAction({ mode: "create" })}
            >
              입금 등록
            </Button>
          </Can>
        )}
      </div>
      {readonly && <LockedBanner>조회 전용입니다.</LockedBanner>}
      {message && (
        <SuccessToast message={message} onDismiss={() => setMessage("")} />
      )}
      <ErrorBanner
        error={state.error}
        onRetry={() => void state.reload().catch(() => {})}
      />
      <ErrorBanner
        error={calendar.error}
        onRetry={() => void calendar.reload().catch(() => {})}
      />
      {state.loading ? (
        <Skeleton />
      ) : (
        !state.error &&
        detail && (
          <>
            <p>
              조정 청구 총액{" "}
              {detail.billing.adjusted_total_amt.toLocaleString("ko-KR")}원 ·
              입금 합 {detail.billing.received_amt.toLocaleString("ko-KR")}원 ·
              미수금 {detail.billing.receivable_amt.toLocaleString("ko-KR")}원
            </p>
            {detail.billing.bill_stts_cd === "CONFIRM" && (
              <Badge
                tone={
                  detail.billing.receivable_amt === 0 ? "success" : "warning"
                }
              >
                {detail.billing.receivable_amt === 0 ? "입금 완료" : "미수"}
              </Badge>
            )}
            <div className={styles.tableScroll}>
              <table className={styles.table}>
                <thead>
                  <tr>
                    <th>입금일</th>
                    <th>입금액 (부가세 포함)</th>
                    <th>비고</th>
                    <th>작업</th>
                  </tr>
                </thead>
                <tbody>
                  {detail.receipts.map((row) => {
                    const closed =
                      !!calendar.data &&
                      monthStatus(calendar.data, row.rcpt_dt.slice(0, 7)) ===
                        "CLOSED";
                    return (
                      <tr
                        key={row.receipt_id}
                        data-testid={`receipt-row-${row.receipt_id}`}
                      >
                        <td>{row.rcpt_dt}</td>
                        <td className={styles.amount}>
                          {row.rcpt_amt.toLocaleString("ko-KR")}원
                        </td>
                        <td>{row.rmrk_desc ?? "–"}</td>
                        <td>
                          {closed ? (
                            <Badge tone="locked">마감</Badge>
                          ) : (
                            canWrite && (
                              <Can perm="UI-13.receipt">
                                <div className={styles.actions}>
                                  <Button
                                    small
                                    data-testid={`receipt-edit-${row.receipt_id}`}
                                    onClick={() =>
                                      setAction({
                                        mode: "update",
                                        receipt: row,
                                      })
                                    }
                                  >
                                    수정
                                  </Button>
                                  <Button
                                    small
                                    data-testid={`receipt-delete-${row.receipt_id}`}
                                    onClick={() =>
                                      setAction({
                                        mode: "delete",
                                        receipt: row,
                                      })
                                    }
                                  >
                                    삭제
                                  </Button>
                                </div>
                              </Can>
                            )
                          )}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
            {detail.receipts.length === 0 && <p>등록된 입금이 없습니다.</p>}
          </>
        )
      )}
      {canWrite && detail && action && calendar.data && (
        <UI13M5
          key={`${billId}-${action.mode}-${action.receipt?.receipt_id ?? "new"}`}
          detail={detail}
          year={year}
          months={calendar.data}
          mode={action.mode}
          receipt={action.receipt}
          reload={refresh}
          onCancel={() => setAction(null)}
          onSaved={() => {
            setAction(null);
            setMessage(
              action.mode === "delete" ? "삭제했습니다." : "저장했습니다.",
            );
          }}
        />
      )}
    </section>
  );
}
