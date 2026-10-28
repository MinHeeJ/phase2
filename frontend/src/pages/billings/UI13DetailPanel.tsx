import { useCallback, type ReactNode } from "react";
import { useNavigate } from "react-router-dom";
import { Badge, Button, Skeleton } from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { useAdminRead } from "../admin/useAdminResource";
import { getMonthClose, monthStatus } from "../shared/api";
import { getBilling, type BillingDetail } from "./client";
import tables from "../developers/Developers.module.css";
import styles from "./Billing.module.css";

export function BillingStatus({ status }: { status: string }) {
  const labels: Record<string, string> = {
    PLAN: "예정",
    DRAFT: "작성",
    CONFIRM: "확정",
    CANCEL: "취소",
  };
  return (
    <Badge
      tone={
        status === "CONFIRM"
          ? "success"
          : status === "DRAFT"
            ? "info"
            : status === "CANCEL"
              ? "locked"
              : "neutral"
      }
    >
      {labels[status] ?? status}
    </Badge>
  );
}
export function UI13DetailPanel({
  id,
  readOnly,
  version,
  onClose,
  onEdit,
  onOpen,
  actions,
}: {
  id: number;
  readOnly: boolean;
  version: number;
  onClose: () => void;
  onEdit: (detail: BillingDetail) => void;
  onOpen: (id: number) => void;
  actions?: (
    detail: BillingDetail,
    context: { readOnly: boolean; closed: boolean },
  ) => ReactNode;
}) {
  const detail = useAdminRead(useCallback(() => getBilling(id), [id, version]));
  const row = detail.data?.billing;
  const month =
    row?.contract_type === "TK"
      ? (row.bill_dt?.slice(0, 7) ?? detail.data?.details[0]?.work_ym)
      : row?.atrb_ym;
  const closes = useAdminRead(
    useCallback(
      () => (month ? getMonthClose(month, month) : Promise.resolve([])),
      [month],
    ),
  );
  const closed = !!month && monthStatus(closes.data ?? [], month) === "CLOSED";
  const navigate = useNavigate();
  return (
    <aside
      data-testid="billing-detail"
      className={styles.drawer}
      aria-label="청구 상세"
    >
      <div className={styles.actions}>
        <h2>청구 상세</h2>
        <Button data-testid="billing-detail-close" onClick={onClose}>
          닫기
        </Button>
      </div>
      <ErrorBanner
        error={detail.error}
        onRetry={() => void detail.reload().catch(() => {})}
      />
      {detail.loading && <Skeleton />}
      {detail.data && row && (
        <>
          <h3>
            {row.bill_no} <BillingStatus status={row.bill_stts_cd} />
          </h3>
          <Button
            data-testid="billing-detail-project"
            variant="text"
            onClick={() =>
              navigate(`/UI-01?open=${encodeURIComponent(row.prj_id)}`)
            }
          >
            {row.project_name}
          </Button>
          <p>{row.customer_name ?? "고객사 미입력"}</p>
          <dl className={styles.fields}>
            {[
              ["귀속월", row.atrb_ym ?? detail.data.details[0]?.work_ym ?? "—"],
              ["청구일", row.bill_dt ?? "—"],
              ["공급가액", `${row.sply_amt.toLocaleString("ko-KR")}원`],
              ["부가세", `${row.vat_amt.toLocaleString("ko-KR")}원`],
              ["합계", `${row.tot_amt.toLocaleString("ko-KR")}원`],
              ["입금 예정일", row.rcpt_plan_dt ?? "예정일 미입력"],
              ["세금계산서 번호", row.txinv_no ?? "미입력"],
              ["작성일", row.txinv_dt ?? "—"],
              ["누적 입금", `${row.received_amt.toLocaleString("ko-KR")}원`],
              [
                "조정 청구 총액",
                `${row.adjusted_total_amt.toLocaleString("ko-KR")}원`,
              ],
              ["미수금", `${row.receivable_amt.toLocaleString("ko-KR")}원`],
            ].map(([label, value]) => (
              <div key={label}>
                <dt>{label}</dt>
                <dd>{value}</dd>
              </div>
            ))}
          </dl>
          {detail.data.warnings.map((w, i) => (
            <p key={i}>
              <Badge tone="warning">{w}</Badge>
            </p>
          ))}
          {closed && (
            <p>
              <Badge tone="locked">마감된 달은 변경할 수 없습니다.</Badge>
            </p>
          )}
          <h3>인력별 상세 / TK 청구 단계</h3>
          <div className={tables.tableScroll}>
            <table className={tables.table}>
              <thead>
                <tr>
                  <th>인력 / 단계</th>
                  <th>귀속월</th>
                  <th>MM</th>
                  <th>단가</th>
                  <th>초과근무금액</th>
                  <th>금액</th>
                </tr>
              </thead>
              <tbody>
                {detail.data.details.map((l, i) => (
                  <tr
                    key={l.asgn_id ?? `stage-${i}`}
                    data-testid={`billing-detail-line-${i}`}
                  >
                    <td>{l.display_name}</td>
                    <td>{l.work_ym}</td>
                    <td>{l.mm_qty ?? "—"}</td>
                    <td className={tables.amount}>
                      {l.untprc_amt?.toLocaleString("ko-KR") ?? "—"}
                    </td>
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
          <h3>입금 이력 (부가세 포함)</h3>
          {detail.data.receipts.length === 0 ? (
            <p>입금 이력이 없습니다.</p>
          ) : (
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
                  {detail.data.receipts.map((r) => (
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
                </tbody>
              </table>
            </div>
          )}
          <h3>수정 청구와 원본</h3>
          {detail.data.original_bill_id && (
            <Button
              data-testid="billing-original"
              onClick={() => onOpen(detail.data!.original_bill_id!)}
            >
              원본 청구 열기
            </Button>
          )}
          {detail.data.amendments.map((a) => (
            <Button
              key={a.bill_id}
              data-testid={`billing-amendment-${a.bill_id}`}
              onClick={() => a.bill_id && onOpen(a.bill_id)}
            >
              {a.bill_no} · {a.tot_amt.toLocaleString("ko-KR")}원
            </Button>
          ))}
          <h3>변경 이력</h3>
          {detail.data.change_history.length === 0 ? (
            <p>변경 이력이 없습니다.</p>
          ) : (
            <ul>
              {detail.data.change_history.map((h, i) => (
                <li
                  key={`${h.changed_at}-${i}`}
                  data-testid={`billing-history-${i}`}
                >
                  {h.changed_at} · {h.description}
                </li>
              ))}
            </ul>
          )}
          <div className={styles.actions}>
            {!readOnly &&
              row.bill_stts_cd === "DRAFT" &&
              !closed &&
              !closes.loading &&
              !closes.error && (
                <Button
                  data-testid="billing-edit"
                  onClick={() => onEdit(detail.data!)}
                >
                  수정
                </Button>
              )}
            {actions?.(detail.data, {
              readOnly: readOnly || !!closes.error || closes.loading,
              closed: !!closed,
            })}
          </div>
        </>
      )}
    </aside>
  );
}
