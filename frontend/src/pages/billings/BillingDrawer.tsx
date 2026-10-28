import { useCallback, type ReactNode } from "react";
import { Link } from "react-router-dom";
import { Badge, Button, Skeleton } from "../../shared/Controls";
import { Can } from "../../shared/Can";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { useAdminRead, useAdminWrite } from "../admin/useAdminResource";
import { seoulMonth } from "../shared/PeriodBar";
import {
  getBilling,
  updateBilling,
  type BillingDetail,
  type BillingRow,
} from "./client";
import { BillingLines } from "./BillingLines";
import tables from "../developers/Developers.module.css";
import styles from "./Billings.module.css";

const statusNames = {
  PLAN: "예정",
  DRAFT: "작성",
  CONFIRM: "확정",
  CANCEL: "취소",
};
export function BillingStatus({ row }: { row: BillingRow }) {
  const stateTone =
    row.bill_stts_cd === "CONFIRM"
      ? "success"
      : row.bill_stts_cd === "CANCEL"
        ? "locked"
        : "neutral";
  return (
    <>
      <Badge tone={stateTone}>{statusNames[row.bill_stts_cd]}</Badge>
      {row.bill_stts_cd === "CONFIRM" && (
        <Badge
          tone={
            row.receivable_amt === 0
              ? "success"
              : (row.reminder_stage ?? 0) > 0
                ? "danger"
                : "warning"
          }
        >
          {row.receivable_amt === 0
            ? "입금 완료"
            : (row.reminder_stage ?? 0) > 0
              ? "연체"
              : "미수"}
        </Badge>
      )}
    </>
  );
}
export interface BillingDrawerProps {
  id: number;
  readOnly: boolean;
  onClose: () => void;
  onOpen: (id: number) => void;
  onEdit: (detail: BillingDetail) => void;
  onChanged: () => Promise<unknown>;
  detailActions?: (
    detail: BillingDetail,
    refresh: () => Promise<unknown>,
  ) => ReactNode;
}
export function BillingDrawer({
  id,
  readOnly,
  onClose,
  onOpen,
  onEdit,
  onChanged,
  detailActions,
}: BillingDrawerProps) {
  const read = useAdminRead(useCallback(() => getBilling(id), [id]));
  const writer = useAdminWrite(async () => {
    await read.reload();
    await onChanged();
  });
  const detail = read.data,
    row = detail?.billing;
  const past =
    Number(
      (row?.contract_type === "TK" ? row.bill_dt : row?.atrb_ym)?.slice(0, 4),
    ) < Number(seoulMonth().slice(0, 4));
  const editable = !readOnly && !past;
  return (
    <div className={tables.scrim} data-testid="billing-detail-scrim">
      <aside
        className={tables.drawer}
        role="dialog"
        aria-modal="true"
        aria-label="청구 상세"
        data-testid="billing-detail"
      >
        <header className={tables.drawerHeader}>
          <h2>청구 상세</h2>
          <Button data-testid="billing-detail-close" onClick={onClose}>
            닫기
          </Button>
        </header>
        {read.loading && <Skeleton label="청구 상세 불러오는 중" />}
        <ErrorBanner
          error={read.error}
          onRetry={() => void read.reload().catch(() => {})}
        />
        {row && detail && (
          <>
            <h3>
              {row.bill_no} <BillingStatus row={row} />
            </h3>
            <dl className={tables.details}>
              <dt>프로젝트</dt>
              <dd>
                <Link
                  data-testid="billing-detail-project"
                  to={`/UI-01?open=${row.prj_id}`}
                >
                  {row.project_name}
                </Link>
              </dd>
              <dt>고객사</dt>
              <dd>{row.customer_name ?? "—"}</dd>
              <dt>귀속월 / 청구일</dt>
              <dd>
                {row.atrb_ym ?? "—"} / {row.bill_dt ?? "미입력"}
              </dd>
              <dt>공급가액 / 부가세</dt>
              <dd>
                {row.sply_amt.toLocaleString()} / {row.vat_amt.toLocaleString()}
                원
              </dd>
              <dt>합계</dt>
              <dd>{row.tot_amt.toLocaleString()}원</dd>
              <dt>세금계산서 번호 / 작성일</dt>
              <dd>
                {row.txinv_no ?? "미입력"} / {row.txinv_dt ?? "미입력"} (기록만)
              </dd>
              <dt>입금 예정일</dt>
              <dd>{row.rcpt_plan_dt ?? "예정일 미입력"}</dd>
              <dt>누적 입금</dt>
              <dd>{row.received_amt.toLocaleString()}원 (부가세 포함)</dd>
              <dt>조정 청구 총액</dt>
              <dd>{row.adjusted_total_amt.toLocaleString()}원</dd>
              <dt>미수금</dt>
              <dd>{row.receivable_amt.toLocaleString()}원</dd>
            </dl>
            {detail.warnings.map((warning, i) => (
              <p
                className={styles.warning}
                key={i}
                data-testid={`billing-detail-warning-${i}`}
              >
                {warning}
              </p>
            ))}
            {detail.original_bill_id !== null && (
              <Button
                data-testid="billing-original"
                onClick={() => onOpen(detail.original_bill_id!)}
              >
                원본 청구 열기
              </Button>
            )}
            <h3>인력별 상세 / TK 청구 단계</h3>
            <BillingLines lines={detail.details} />
            <h3>수정 청구</h3>
            {detail.amendments.length === 0 ? (
              <p>수정 청구 없음</p>
            ) : (
              detail.amendments.map((amendment) => (
                <div
                  key={amendment.bill_id}
                  data-testid={`billing-amendment-${amendment.bill_id}`}
                >
                  <Button
                    data-testid={`billing-open-amendment-${amendment.bill_id}`}
                    onClick={() => onOpen(amendment.bill_id!)}
                  >
                    {amendment.bill_no}
                  </Button>
                  <BillingStatus row={amendment} />{" "}
                  {amendment.tot_amt.toLocaleString()}원
                </div>
              ))
            )}
            <h3>입금 이력</h3>
            <div className={tables.tableScroll}>
              <table className={tables.table} aria-label="입금 이력">
                <thead>
                  <tr>
                    <th>입금일</th>
                    <th>입금액(부가세 포함)</th>
                    <th>비고</th>
                  </tr>
                </thead>
                <tbody>
                  {detail.receipts.map((receipt) => (
                    <tr
                      key={receipt.receipt_id}
                      data-testid={`billing-receipt-${receipt.receipt_id}`}
                    >
                      <td>{receipt.rcpt_dt}</td>
                      <td className={tables.amount}>
                        {receipt.rcpt_amt.toLocaleString()}
                      </td>
                      <td>{receipt.rmrk_desc ?? "—"}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {detail.receipts.length === 0 && (
                <p className={tables.empty}>입금 내역이 없습니다.</p>
              )}
            </div>
            <h3>변경 이력</h3>
            {detail.change_history.map((history, i) => (
              <p
                key={`${history.changed_at}-${i}`}
                data-testid={`billing-history-${i}`}
              >
                {history.changed_at} · {history.user_id ?? "기본 사용자"} ·{" "}
                {history.description}
              </p>
            ))}
            <ErrorBanner error={writer.error} />
            {!editable && (
              <p className={styles.readonly}>조회·다운로드만 가능합니다.</p>
            )}
            {editable && (
              <div className={tables.writeActions}>
                {row.bill_stts_cd === "DRAFT" && !detail.original_bill_id && (
                  <Can perm="UI-13.update">
                    <Button
                      data-testid="billing-edit"
                      onClick={() => onEdit(detail)}
                    >
                      수정
                    </Button>
                    <Button
                      data-testid="billing-recalculate-now"
                      pending={writer.pending}
                      disabled={writer.uncertain}
                      onClick={() => {
                        if (
                          window.confirm(
                            "현재 원천으로 상세를 다시 계산할까요?",
                          )
                        )
                          void writer.save(
                            () => updateBilling(id, { recalculate: true }),
                            () => {},
                          );
                      }}
                    >
                      다시 계산
                    </Button>
                  </Can>
                )}
                {detailActions?.(detail, async () => {
                  await read.reload();
                  await onChanged();
                })}
              </div>
            )}
          </>
        )}
      </aside>
    </div>
  );
}
