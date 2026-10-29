import { useCallback, type ReactNode } from "react";
import { Badge, Button, Skeleton } from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { useAdminRead, useAdminWrite } from "../admin/useAdminResource";
import {
  getBilling,
  updateBilling,
  type BillingDetail,
  type BillingDetailLine,
  type BillingRow,
} from "./client";
import tables from "../developers/Developers.module.css";
import styles from "./Billings.module.css";

export function BillingStatus({ row }: { row: BillingRow }) {
  const labels = {
    PLAN: "예정",
    DRAFT: "작성",
    CONFIRM: "확정",
    CANCEL: "취소",
  };
  return (
    <Badge
      tone={
        row.bill_stts_cd === "CONFIRM"
          ? "success"
          : row.bill_stts_cd === "DRAFT"
            ? "info"
            : "neutral"
      }
    >
      {labels[row.bill_stts_cd]}
    </Badge>
  );
}
export function BillingLines({ lines }: { lines: BillingDetailLine[] }) {
  return (
    <div className={tables.tableScroll} data-testid="billing-lines-root">
      <table className={tables.table}>
        <thead>
          <tr>
            <th>인력</th>
            <th>귀속월</th>
            <th>MM</th>
            <th>투입 MM</th>
            <th>단가</th>
            <th>근무일수</th>
            <th>정규시간</th>
            <th>초과근무금액</th>
            <th>상세 금액</th>
          </tr>
        </thead>
        <tbody>
          {lines.map((l) => (
            <tr data-testid={`billing-line-${l.asgn_id}`} key={l.asgn_id}>
              <td>{l.display_name}</td>
              <td>{l.work_ym}</td>
              <td>{l.mm_qty ?? "–"}</td>
              <td>{l.source_mm_qty ?? "–"}</td>
              <td className={tables.amount}>
                {l.untprc_amt?.toLocaleString("ko-KR") ?? "–"}
              </td>
              <td>{l.work_day_qty ?? "–"}</td>
              <td>{l.work_tm_qty ?? "–"}</td>
              <td className={tables.amount}>
                {l.ovtm_amt.toLocaleString("ko-KR")}
              </td>
              <td className={tables.amount}>
                {l.bill_amt.toLocaleString("ko-KR")}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
export function UI13DetailPanel({
  id,
  readOnly,
  version = 0,
  onClose,
  onEdit,
  onOpen,
  onProject,
  refresh,
  actions,
}: {
  id: number;
  readOnly: boolean;
  version?: number;
  onClose: () => void;
  onEdit: (detail: BillingDetail) => void;
  onOpen: (id: number) => void;
  onProject: (id: number) => void;
  refresh: () => Promise<unknown>;
  actions?: (
    detail: BillingDetail,
    reload: () => Promise<unknown>,
  ) => ReactNode;
}) {
  const detail = useAdminRead(useCallback(() => getBilling(id), [id, version]));
  const reload = async () => {
    await detail.reload();
    await refresh();
  };
  const write = useAdminWrite(reload);
  const d = detail.data,
    row = d?.billing;
  return (
    <div className={styles.scrim} data-testid="billing-detail-root">
      <aside
        className={styles.drawer}
        role="dialog"
        aria-modal="true"
        aria-label="청구 상세"
        data-testid="billing-detail"
      >
        <header>
          <h2>청구 상세</h2>
          <Button data-testid="billing-detail-close" onClick={onClose}>
            닫기
          </Button>
        </header>
        <ErrorBanner
          error={detail.error}
          onRetry={() => void detail.reload().catch(() => {})}
        />
        <ErrorBanner error={write.error} onRetry={() => void write.recover()} />
        {detail.loading ? (
          <Skeleton />
        ) : (
          d &&
          row && (
            <>
              <h3>
                {row.bill_no} <BillingStatus row={row} />
              </h3>
              <Button
                variant="text"
                data-testid="billing-detail-project"
                onClick={() => onProject(row.prj_id)}
              >
                {row.project_name}
              </Button>
              <p>
                {row.customer_name} · {row.contract_type} · {row.bill_odr_no}차
              </p>
              <dl className={styles.fields}>
                {[
                  ["귀속월", row.atrb_ym],
                  ["청구일", row.bill_dt],
                  ["공급가액", row.sply_amt.toLocaleString("ko-KR")],
                  ["부가세", row.vat_amt.toLocaleString("ko-KR")],
                  ["합계", row.tot_amt.toLocaleString("ko-KR")],
                  ["세금계산서 번호", row.txinv_no],
                  ["작성일", row.txinv_dt],
                  ["입금 예정일", row.rcpt_plan_dt],
                  ["누적 입금", row.received_amt.toLocaleString("ko-KR")],
                  [
                    "조정 청구 총액",
                    row.adjusted_total_amt.toLocaleString("ko-KR"),
                  ],
                  ["미수금", row.receivable_amt.toLocaleString("ko-KR")],
                ].map(([label, value]) => (
                  <div
                    key={label}
                    data-testid={`billing-detail-field-${["귀속월", "청구일", "공급가액", "부가세", "합계", "세금계산서 번호", "작성일", "입금 예정일", "누적 입금", "조정 청구 총액", "미수금"].indexOf(label as string)}`}
                  >
                    <dt>{label}</dt>
                    <dd>{value ?? "–"}</dd>
                  </div>
                ))}
              </dl>
              {d.warnings.map((w, i) => (
                <p
                  className={styles.warning}
                  key={i}
                  data-testid={`billing-detail-warning-${i}`}
                >
                  {w}
                </p>
              ))}
              {row.contract_type === "TK" ? (
                <p>TK 청구 단계: {row.bill_stg_id}</p>
              ) : (
                <BillingLines lines={d.details} />
              )}
              <h3>입금 이력 (부가세 포함)</h3>
              <div className={tables.tableScroll}>
                <table className={tables.table}>
                  <thead>
                    <tr>
                      <th>입금일</th>
                      <th>입금액</th>
                      <th>비고</th>
                    </tr>
                  </thead>
                  <tbody>
                    {d.receipts.map((r) => (
                      <tr
                        key={r.receipt_id}
                        data-testid={`billing-receipt-${r.receipt_id}`}
                      >
                        <td>{r.rcpt_dt}</td>
                        <td className={tables.amount}>
                          {r.rcpt_amt.toLocaleString("ko-KR")}
                        </td>
                        <td>{r.rmrk_desc}</td>
                      </tr>
                    ))}
                    {!d.receipts.length && (
                      <tr>
                        <td colSpan={3}>입금 이력이 없습니다.</td>
                      </tr>
                    )}
                  </tbody>
                </table>
              </div>
              {d.original_bill_id && (
                <Button
                  data-testid="billing-original-open"
                  onClick={() => onOpen(d.original_bill_id!)}
                >
                  원본 청구 열기
                </Button>
              )}
              {d.amendments.map((a) => (
                <Button
                  data-testid={`billing-amendment-${a.bill_id}`}
                  key={a.bill_id}
                  onClick={() => onOpen(a.bill_id!)}
                >
                  {a.bill_no} 수정 청구
                </Button>
              ))}
              <h3>변경 이력</h3>
              {d.change_history.map((h, i) => (
                <article
                  key={`${h.changed_at}-${i}`}
                  data-testid={`billing-history-${i}`}
                >
                  <time>{h.changed_at}</time> · {h.user_id ?? "기본 사용자"}
                  <p>{h.description}</p>
                </article>
              ))}
              {!readOnly && (
                <div className={styles.actions}>
                  {row.bill_stts_cd === "DRAFT" && (
                    <>
                      <Button
                        data-testid="billing-edit"
                        onClick={() => onEdit(d)}
                      >
                        수정
                      </Button>
                      <Button
                        data-testid="billing-recalculate-save"
                        pending={write.pending}
                        disabled={write.uncertain}
                        onClick={() =>
                          void write.save(
                            () => updateBilling(id, { recalculate: true }),
                            () => {},
                          )
                        }
                      >
                        다시 계산
                      </Button>
                    </>
                  )}
                  {actions?.(d, reload)}
                </div>
              )}
            </>
          )
        )}
      </aside>
    </div>
  );
}
