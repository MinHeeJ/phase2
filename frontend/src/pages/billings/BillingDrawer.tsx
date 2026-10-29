import { useEffect, useState, type ReactNode } from "react";
import { Link } from "react-router-dom";
import { Badge, Button } from "../../shared/Controls";
import { ApiFailure } from "../../shared/api";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { getBilling, type BillingDetail, type BillingRow } from "./client";
import tables from "../developers/Developers.module.css";
import styles from "./Billings.module.css";

export function BillingStatus({ row }: { row: BillingRow }) {
  if (row.bill_stts_cd !== "CONFIRM")
    return (
      <Badge tone={row.bill_stts_cd === "DRAFT" ? "info" : "neutral"}>
        {
          (
            { PLAN: "예정", DRAFT: "작성", CANCEL: "취소" } as Record<
              string,
              string
            >
          )[row.bill_stts_cd]
        }
      </Badge>
    );
  const late =
    !!row.rcpt_plan_dt &&
    row.receivable_amt > 0 &&
    row.rcpt_plan_dt <
      new Intl.DateTimeFormat("sv-SE", { timeZone: "Asia/Seoul" }).format(
        new Date(),
      );
  return (
    <>
      <Badge tone="info">확정</Badge>{" "}
      <Badge
        tone={
          row.receivable_amt === 0 ? "success" : late ? "danger" : "warning"
        }
      >
        {row.receivable_amt === 0 ? "입금 완료" : late ? "연체" : "미수"}
      </Badge>
    </>
  );
}
export function BillingDrawer({
  id,
  refreshVersion,
  readOnly,
  onClose,
  onOpen,
  onEdit,
  onRefresh,
  renderActions,
  renderReceipts,
}: {
  id: number;
  refreshVersion: number;
  readOnly: boolean;
  onClose: () => void;
  onOpen: (id: number) => void;
  onEdit: (detail: BillingDetail) => void;
  onRefresh?: () => void;
  renderActions?: (detail: BillingDetail, refresh: () => void) => ReactNode;
  renderReceipts?: (detail: BillingDetail, refresh: () => void) => ReactNode;
}) {
  const [detail, setDetail] = useState<BillingDetail | null>(null),
    [error, setError] = useState<ApiFailure | null>(null);
  const [loading, setLoading] = useState(true),
    [revision, setRevision] = useState(0);
  useEffect(() => {
    let live = true;
    setLoading(true);
    setError(null);
    setDetail(null);
    getBilling(id)
      .then((data) => {
        if (live) setDetail(data);
      })
      .catch((e: ApiFailure) => {
        if (live) setError(e);
      })
      .finally(() => {
        if (live) setLoading(false);
      });
    return () => {
      live = false;
    };
  }, [id, refreshVersion, revision]);
  const refresh = () => {
    setRevision((v) => v + 1);
    onRefresh?.();
  };
  return (
    <div className={tables.scrim}>
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
        {loading && <p role="status">청구 상세 불러오는 중</p>}
        <ErrorBanner error={error} onRetry={() => setRevision((v) => v + 1)} />
        {detail && (
          <>
            <h3>
              {detail.billing.bill_no} · {detail.billing.project_name}
            </h3>
            <BillingStatus row={detail.billing} />
            <dl className={tables.details}>
              <dt>프로젝트</dt>
              <dd>
                <Link
                  data-testid="billing-detail-project"
                  to={`/UI-01?open=${detail.billing.prj_id}`}
                >
                  {detail.billing.project_name}
                </Link>
              </dd>
              <dt>고객사</dt>
              <dd>{detail.billing.customer_name ?? "—"}</dd>
              {Object.entries({
                atrb_ym: "귀속월",
                bill_dt: "청구일",
                sply_amt: "공급가액",
                vat_amt: "부가세",
                tot_amt: "합계",
                rcpt_plan_dt: "입금 예정일",
                txinv_no: "세금계산서 번호",
                txinv_dt: "작성일",
                received_amt: "누적 입금",
                adjusted_total_amt: "조정 청구 총액",
                receivable_amt: "미수금",
              }).map(([key, label]) => (
                <div className={styles.detailValue} key={key}>
                  <dt>{label}</dt>
                  <dd>
                    {typeof detail.billing[key as keyof BillingRow] === "number"
                      ? Number(
                          detail.billing[key as keyof BillingRow],
                        ).toLocaleString("ko-KR")
                      : String(detail.billing[key as keyof BillingRow] ?? "—")}
                  </dd>
                </div>
              ))}
            </dl>
            {detail.warnings.map((warning, i) => (
              <p key={i} className={styles.warning}>
                {warning}
              </p>
            ))}
            <h3>
              {detail.billing.contract_type === "TK"
                ? "TK 청구 단계"
                : "인력별 상세"}
            </h3>
            {detail.billing.contract_type === "TK" ? (
              <p>
                단계 #{detail.billing.bill_stg_id ?? "원본 참조"} · 공급가{" "}
                {detail.billing.sply_amt.toLocaleString("ko-KR")}원
              </p>
            ) : (
              <div className={tables.tableScroll}>
                <table className={tables.table}>
                  <thead>
                    <tr>
                      <th>계약상 인력</th>
                      <th>귀속월</th>
                      <th>MM</th>
                      <th>단가</th>
                      <th>초과근무금액</th>
                      <th>공급가</th>
                    </tr>
                  </thead>
                  <tbody>
                    {detail.details.map((line) => (
                      <tr
                        data-testid={`billing-detail-line-${line.asgn_id}`}
                        key={line.asgn_id}
                      >
                        <td>{line.display_name}</td>
                        <td>{line.work_ym}</td>
                        <td>{line.mm_qty ?? "—"}</td>
                        <td>{line.untprc_amt?.toLocaleString("ko-KR")}</td>
                        <td>{line.ovtm_amt.toLocaleString("ko-KR")}</td>
                        <td>{line.bill_amt.toLocaleString("ko-KR")}</td>
                      </tr>
                    ))}
                    {!detail.details.length && (
                      <tr>
                        <td colSpan={6} className={tables.empty}>
                          저장된 인력 상세가 없습니다.
                        </td>
                      </tr>
                    )}
                  </tbody>
                </table>
              </div>
            )}
            {renderReceipts ? (
              renderReceipts(detail, refresh)
            ) : (
              <>
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
                      {detail.receipts.map((receipt) => (
                        <tr
                          data-testid={`billing-receipt-row-${receipt.receipt_id}`}
                          key={receipt.receipt_id}
                        >
                          <td>{receipt.rcpt_dt}</td>
                          <td>{receipt.rcpt_amt.toLocaleString("ko-KR")}</td>
                          <td>{receipt.rmrk_desc}</td>
                        </tr>
                      ))}
                      {!detail.receipts.length && (
                        <tr>
                          <td colSpan={3} className={tables.empty}>
                            입금 이력이 없습니다.
                          </td>
                        </tr>
                      )}
                    </tbody>
                  </table>
                </div>
              </>
            )}
            <h3>수정 청구와 원본</h3>
            {detail.original_bill_id && (
              <Button
                data-testid="billing-original"
                onClick={() => onOpen(detail.original_bill_id!)}
              >
                원본 청구 #{detail.original_bill_id}
              </Button>
            )}
            {detail.amendments.map((row) => (
              <p
                key={row.bill_id}
                data-testid={`billing-amendment-${row.bill_id}`}
              >
                <Button
                  data-testid={`billing-amendment-open-${row.bill_id}`}
                  onClick={() => onOpen(row.bill_id!)}
                >
                  {row.bill_no}
                </Button>{" "}
                {row.sply_amt.toLocaleString("ko-KR")}원 · {row.bill_stts_cd}
              </p>
            ))}
            <h3>변경 이력</h3>
            {detail.change_history.length ? (
              detail.change_history.map((history, i) => (
                <p data-testid={`billing-history-${i}`} key={i}>
                  {history.changed_at} · {history.user_id ?? "기본 사용자"} ·{" "}
                  {history.description}
                </p>
              ))
            ) : (
              <p>변경 이력이 없습니다.</p>
            )}
            {!readOnly && (
              <div className={tables.writeActions}>
                {detail.billing.bill_stts_cd === "DRAFT" && (
                  <Button
                    data-testid="billing-edit"
                    onClick={() => onEdit(detail)}
                  >
                    수정
                  </Button>
                )}
                {renderActions?.(detail, refresh)}
              </div>
            )}
          </>
        )}
      </aside>
    </div>
  );
}
