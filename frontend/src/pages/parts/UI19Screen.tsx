import { useCallback, useEffect, useState } from "react";
import {
  Badge,
  Button,
  LockedBanner,
  Modal,
  Skeleton,
  SuccessToast,
} from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { ApiFailure } from "../../shared/api";
import { useAdminRead, useAdminWrite } from "../admin/useAdminResource";
import { ScreenHeading } from "../shared/ScreenHeading";
import { seoulMonth } from "../shared/PeriodBar";
import { useSharedMobile } from "../shared/PlaceholderScreen";
import tables from "../developers/Developers.module.css";
import {
  listPartSettlements,
  getPartSettlement,
  updatePartSettlementCard,
  confirmPartSettlement,
  cancelPartSettlement,
  downloadPartSettlements,
  type SettlementDetail,
  type SettlementItem,
} from "./client";

const money = (value: number | null | undefined) =>
  value == null ? "–" : value.toLocaleString("ko-KR");
const metrics: Record<string, string> = {
  mm_qty: "M/M",
  mth_untprc_amt: "단가",
  tot_exp_amt: "비용",
  diff_amt: "차액",
  prft_amt: "이익금",
};
const columns: [keyof SettlementItem, string][] = [
  ["dev_nm", "이름"],
  ["emp_se_cd", "구분"],
  ["project_names", "프로젝트"],
  ["mm_qty", "M/M"],
  ["mth_untprc_amt", "월 단가"],
  ["slry_amt", "급여"],
  ["bsn_fee_amt", "영업비"],
  ["rtrpay_amt", "퇴직금"],
  ["tot_exp_amt", "비용 계"],
  ["diff_amt", "차액"],
  ["prft_amt", "이익금"],
  ["rmrk_desc", "비고"],
];

// SCR-UI-19 — /UI-19. Other established business routes remain owned by their existing screens.
export default function UI19Screen({
  name = "영업 파트 이익금",
  groupName = "원가·수익 분석",
  onDirtyChange,
}: {
  name?: string;
  groupName?: string;
  onDirtyChange?: (dirty: boolean) => void;
}) {
  const [month, setMonth] = useState(
    () => sessionStorage.getItem("part-settlement-month") ?? seoulMonth(),
  );
  const [selected, setSelected] = useState<number | null>(null);
  const [exportError, setExportError] = useState<ApiFailure | null>(null);
  const [exporting, setExporting] = useState(false);
  const read = useCallback(() => listPartSettlements(month), [month]);
  const list = useAdminRead(read);
  const rows =
    list.error || list.data?.calculation_month !== month ? [] : list.data.items;
  const partId = rows.some((r) => r.bsn_part_id === selected)
    ? selected
    : (rows[0]?.bsn_part_id ?? null);
  const selectMonth = (value: string) => {
    if (!value) return;
    sessionStorage.setItem("part-settlement-month", value);
    setMonth(value);
  };
  const download = async () => {
    setExporting(true);
    setExportError(null);
    try {
      await downloadPartSettlements(month);
    } catch (error) {
      setExportError(error as ApiFailure);
    } finally {
      setExporting(false);
    }
  };
  return (
    <section
      data-testid="part-settlement-screen"
      aria-label="영업 파트 이익금 화면"
    >
      <ScreenHeading
        screenId="UI-19"
        name={name}
        groupName={groupName}
        actions={
          <Button
            data-testid="part-download-button"
            pending={exporting}
            onClick={() => void download()}
          >
            엑셀 다운로드
          </Button>
        }
      />
      <div className={tables.search}>
        <label>
          조회월{" "}
          <input
            data-testid="part-month-input"
            aria-label="조회월"
            type="month"
            max={seoulMonth()}
            value={month}
            onChange={(e) => selectMonth(e.target.value)}
          />
        </label>
        {!list.loading && list.data && (
          <Badge tone={list.data.closed ? "locked" : "info"}>
            {list.data.closed ? "마감" : "작업중"}
          </Badge>
        )}
        <Button
          data-testid="part-query-button"
          onClick={() => void list.reload().catch(() => {})}
        >
          조회
        </Button>
      </div>
      <ErrorBanner error={exportError} />
      <ErrorBanner
        error={list.error}
        onRetry={() => void list.reload().catch(() => {})}
      />
      {list.loading && <Skeleton />}
      {!list.loading && !list.error && rows.length === 0 && (
        <p className={tables.empty}>조건에 맞는 결과가 없습니다</p>
      )}
      <div role="tablist" aria-label="영업 파트" className={tables.actions}>
        {rows.map((row) => (
          <Button
            key={row.bsn_part_id}
            role="tab"
            aria-selected={partId === row.bsn_part_id}
            data-testid={`part-tab-${row.bsn_part_id}`}
            onClick={() => setSelected(row.bsn_part_id)}
          >
            {row.bsn_part_nm}{" "}
            <Badge tone={row.provisional ? "warning" : "success"}>
              {row.provisional ? "잠정" : "확정"}
            </Badge>
          </Button>
        ))}
      </div>
      {partId != null && (
        <PartSettlementPanel
          key={`${partId}:${month}`}
          partId={partId}
          month={month}
          onDirtyChange={onDirtyChange}
          onRefresh={list.reload}
        />
      )}
    </section>
  );
}

export function PartSettlementPanel({
  partId,
  month,
  onDirtyChange,
  onRefresh,
}: {
  partId: number;
  month: string;
  onDirtyChange?: (dirty: boolean) => void;
  onRefresh: () => Promise<unknown>;
}) {
  const [view, setView] = useState("MONTH");
  const read = useCallback(
    () => getPartSettlement(partId, month, view),
    [partId, month, view],
  );
  const detail = useAdminRead(read);
  const [card, setCard] = useState("");
  const [remark, setRemark] = useState("");
  const [reason, setReason] = useState("");
  const [dirty, setDirty] = useState(false);
  const [message, setMessage] = useState("");
  const [validation, setValidation] = useState<string | null>(null);
  const [dialog, setDialog] = useState<"confirm" | "cancel" | null>(null);
  const mobile = useSharedMobile();
  useEffect(() => {
    if (detail.data && !dirty) {
      setCard(String(detail.data.settlement.corp_card_amt));
      setRemark(detail.data.settlement.rmrk_desc ?? "");
    }
  }, [detail.data, dirty]);
  useEffect(() => {
    onDirtyChange?.(dirty);
  }, [dirty, onDirtyChange]);
  useEffect(() => () => onDirtyChange?.(false), [onDirtyChange]);
  const refresh = async () => {
    await detail.reload();
    await onRefresh();
  };
  const write = useAdminWrite(refresh);
  const data = detail.loading || detail.error ? null : detail.data;
  const confirmed = data?.settlement.stlm_stts_cd === "CONFIRM";
  const writable =
    !!data &&
    !data.closed &&
    month.slice(0, 4) === seoulMonth().slice(0, 4) &&
    !mobile;
  const editable = writable && !confirmed;
  const saved = (text: string) => {
    setDirty(false);
    setDialog(null);
    setReason("");
    setMessage(text);
  };
  const saveCard = () => {
    if (
      !/^\d+$/.test(card) ||
      !Number.isSafeInteger(Number(card)) ||
      Number(card) < 0
    ) {
      setValidation("법인카드 사용액은 0 이상의 정수로 입력하세요.");
      return;
    }
    setValidation(null);
    void write.save(
      () =>
        updatePartSettlementCard(partId, month, {
          corp_card_amt: Number(card),
          rmrk_desc: remark,
        }),
      () => saved("법인카드 금액과 비고를 저장했습니다."),
    );
  };
  const submit = () => {
    if (dialog === "cancel" && (!reason.trim() || reason.trim().length > 500)) {
      setValidation("취소 사유를 1~500자로 입력하세요.");
      return;
    }
    setValidation(null);
    void write.save(
      () =>
        dialog === "cancel"
          ? cancelPartSettlement(partId, month, reason.trim())
          : confirmPartSettlement(partId, month),
      () =>
        saved(
          dialog === "cancel" ? "확정을 취소했습니다." : "정산을 확정했습니다.",
        ),
    );
  };
  return (
    <section
      data-testid="part-detail-panel"
      role="tabpanel"
      aria-label="파트 정산 상세"
    >
      {detail.loading && <Skeleton />}
      <ErrorBanner
        error={detail.error}
        onRetry={() => void detail.reload().catch(() => {})}
      />
      <ErrorBanner
        error={write.error}
        pending={write.pending}
        onRetry={() => void write.recover()}
      />
      {validation && <p role="alert">{validation}</p>}
      {message && (
        <SuccessToast message={message} onDismiss={() => setMessage("")} />
      )}
      {data && (
        <>
          {(data.closed || month.slice(0, 4) !== seoulMonth().slice(0, 4)) && (
            <LockedBanner>
              마감 월 또는 지난 연도는 조회·다운로드만 가능합니다.
            </LockedBanner>
          )}
          {data.provisional && (
            <LockedBanner>
              잠정 계산 · D-31/D-32 운영 기준 승인 전 정산 확정 보류
            </LockedBanner>
          )}
          {data.warnings.map((warning, index) => (
            <p key={warning} data-testid={`part-warning-${index}`}>
              <Badge tone="warning">{warning}</Badge>
            </p>
          ))}
          <div className={tables.card}>
            <dl className={tables.details}>
              <dt>파트장</dt>
              <dd>{data.settlement.ldr_nm ?? "–"}</dd>
              <dt>배분율</dt>
              <dd>
                {data.settlement.prft_dstrb_rt == null
                  ? "–"
                  : `${data.settlement.prft_dstrb_rt.toFixed(1)}%`}
              </dd>
              <dt>인원</dt>
              <dd>{data.settlement.psn_cnt}명</dd>
              <dt>차액</dt>
              <dd>{money(data.settlement.tot_diff_amt)}원</dd>
              <dt>파트 이익금</dt>
              <dd>
                {money(data.settlement.part_prft_amt)}원{" "}
                <Badge tone={confirmed ? "success" : "warning"}>
                  {confirmed ? "확정" : "잠정"}
                </Badge>
              </dd>
              <dt>법인카드 사용액</dt>
              <dd>{money(data.settlement.corp_card_amt)}원</dd>
              <dt>비고</dt>
              <dd>{data.settlement.rmrk_desc ?? "–"}</dd>
            </dl>
            {editable && (
              <div
                className={tables.writeActions}
                data-testid="part-card-controls"
              >
                <label>
                  법인카드 사용액{" "}
                  <input
                    data-testid="part-card-input"
                    aria-label="법인카드 사용액"
                    inputMode="numeric"
                    value={card}
                    onChange={(e) => {
                      setCard(e.target.value);
                      setDirty(true);
                    }}
                  />
                </label>
                <label>
                  비고{" "}
                  <input
                    data-testid="part-remark-input"
                    aria-label="비고"
                    maxLength={500}
                    value={remark}
                    onChange={(e) => {
                      setRemark(e.target.value);
                      setDirty(true);
                    }}
                  />
                </label>
                <Button
                  data-testid="part-card-save-button"
                  pending={write.pending}
                  disabled={write.uncertain}
                  onClick={saveCard}
                >
                  카드액 저장
                </Button>
                <Button
                  data-testid="part-confirm-button"
                  disabled={
                    !data.confirmation_available ||
                    dirty ||
                    write.pending ||
                    write.uncertain
                  }
                  onClick={() => setDialog("confirm")}
                >
                  정산 확정
                </Button>
              </div>
            )}
            {writable && confirmed && (
              <div className={tables.writeActions}>
                <Button
                  data-testid="part-cancel-button"
                  disabled={write.pending || write.uncertain}
                  onClick={() => setDialog("cancel")}
                >
                  확정 취소
                </Button>
              </div>
            )}
          </div>
          <div role="tablist" aria-label="정산 보기" className={tables.actions}>
            <Button
              role="tab"
              data-testid="part-month-tab"
              aria-selected={view === "MONTH"}
              onClick={() => setView("MONTH")}
            >
              이번 달
            </Button>
            <Button
              role="tab"
              data-testid="part-trend-tab"
              aria-selected={view === "TREND"}
              onClick={() => setView("TREND")}
            >
              기간 추이
            </Button>
          </div>
          {view === "MONTH" ? (
            <div className={tables.tableScroll}>
              <table className={tables.table} aria-label="월별 인력 정산">
                <thead>
                  <tr>
                    {columns.map(([key, label]) => (
                      <th key={key}>{label}</th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {data.items.length === 0 && (
                    <tr>
                      <td colSpan={columns.length} className={tables.empty}>
                        조건에 맞는 결과가 없습니다
                      </td>
                    </tr>
                  )}
                  {data.items.map((row) => (
                    <tr
                      key={row.dev_id}
                      data-testid={`part-person-${row.dev_id}`}
                    >
                      {columns.map(([key]) => (
                        <td
                          key={key}
                          className={
                            typeof row[key] === "number"
                              ? tables.amount
                              : undefined
                          }
                        >
                          {key === "mm_qty"
                            ? row.mm_qty.toFixed(2)
                            : typeof row[key] === "number"
                              ? money(row[key] as number)
                              : (row[key] ?? "–")}
                        </td>
                      ))}
                    </tr>
                  ))}
                  {data.items.length > 0 && (
                    <tr>
                      <th colSpan={4}>합계</th>
                      <td className={tables.amount}>
                        {money(data.settlement.tot_sls_amt)}
                      </td>
                      <td colSpan={3} />
                      <td className={tables.amount}>
                        {money(data.settlement.tot_exp_amt)}
                      </td>
                      <td className={tables.amount}>
                        {money(data.settlement.tot_diff_amt)}
                      </td>
                      <td className={tables.amount}>
                        {money(data.settlement.tot_psn_prft_amt)}
                      </td>
                      <td />
                    </tr>
                  )}
                </tbody>
              </table>
            </div>
          ) : (
            <SettlementTrend data={data} />
          )}
          <details className={tables.card} open>
            <summary>계산 방법</summary>
            <div className={tables.tableScroll}>
              <table className={tables.table}>
                <tbody>
                  <tr>
                    <th>월 비용</th>
                    <td>
                      실제 지급 급여 + 영업비(OS 제외, FL 포함) + 퇴직금(FT만)
                    </td>
                  </tr>
                  <tr>
                    <th>첫 달 급여</th>
                    <td>
                      최초 유효 투입 시작일부터 월말까지 달력일 일할 · 1회 적용
                    </td>
                  </tr>
                  <tr>
                    <th>인력별 이익금</th>
                    <td>
                      (월 단가 − 비용) × 배분율 · 음수 상계 · 원 단위 절사
                    </td>
                  </tr>
                  <tr>
                    <th>파트 이익금</th>
                    <td>인력별 이익금 합계 − 법인카드 사용액 1회</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </details>
          <details className={tables.card}>
            <summary>기준 이력</summary>
            <div className={tables.tableScroll}>
              <table className={tables.table}>
                <thead>
                  <tr>
                    <th>시작월</th>
                    <th>종료월</th>
                    <th>파트장</th>
                    <th>배분율</th>
                    <th>영업비</th>
                  </tr>
                </thead>
                <tbody>
                  {data.standard_history.map((row) => (
                    <tr
                      key={row.part_std_hist_id}
                      data-testid={`part-standard-${row.part_std_hist_id}`}
                    >
                      <td>{row.aply_bgn_ym}</td>
                      <td>{row.aply_end_ym ?? "현재"}</td>
                      <td>{row.ldr_nm ?? "–"}</td>
                      <td>{row.prft_dstrb_rt.toFixed(1)}%</td>
                      <td>{money(row.bsn_fee_amt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </details>
          <details className={tables.card}>
            <summary>확정·취소 이력</summary>
            {data.history.length === 0 && (
              <p className={tables.empty}>확정·취소 이력이 없습니다.</p>
            )}
            {data.history.map((row) => (
              <p
                key={row.stlm_hist_id}
                data-testid={`part-history-${row.stlm_hist_id}`}
              >
                {row.prcs_at} ·{" "}
                {row.stlm_prcs_cd === "CONFIRM" ? "확정" : "확정 취소"} ·{" "}
                {row.rsn_desc ?? "–"} · {row.prcs_user_id ?? "기본 사용자"}
              </p>
            ))}
          </details>
        </>
      )}
      {!mobile && dialog && (
        <Modal
          title={dialog === "cancel" ? "정산 확정 취소" : "정산 확정"}
          onCancel={() => !write.pending && setDialog(null)}
        >
          <div data-testid="part-confirmation-dialog">
            <p>
              {dialog === "cancel"
                ? "확정을 취소해도 상세 스냅샷과 이력은 보존됩니다."
                : "원천을 다시 검증하고 기준·상세를 확정합니다. 확정 후에는 수정할 수 없습니다."}
            </p>
            {dialog === "cancel" && (
              <label>
                취소 사유{" "}
                <textarea
                  data-testid="part-cancel-reason"
                  aria-label="취소 사유"
                  maxLength={500}
                  value={reason}
                  onChange={(e) => setReason(e.target.value)}
                />
              </label>
            )}
            <ErrorBanner error={write.error} />
            {validation && <p role="alert">{validation}</p>}
            <Button
              data-testid="part-dialog-close"
              disabled={write.pending}
              onClick={() => setDialog(null)}
            >
              닫기
            </Button>
            <Button
              data-testid="part-dialog-submit"
              pending={write.pending}
              disabled={write.uncertain}
              onClick={submit}
            >
              확인
            </Button>
          </div>
        </Modal>
      )}
    </section>
  );
}

function SettlementTrend({ data }: { data: SettlementDetail }) {
  const months = data.trend[0]?.months ?? [];
  return (
    <div className={tables.tableScroll}>
      <table className={tables.table} aria-label="인력별 기간 추이">
        <thead>
          <tr>
            <th>이름</th>
            <th>항목</th>
            {months.map((m) => (
              <th key={m.month}>
                {m.month}{" "}
                <Badge tone={m.confirmed ? "success" : "warning"}>
                  {m.confirmed ? "확정" : "잠정"}
                </Badge>
              </th>
            ))}
            <th>합계</th>
          </tr>
        </thead>
        <tbody>
          {data.trend.length === 0 && (
            <tr>
              <td colSpan={3} className={tables.empty}>
                조건에 맞는 결과가 없습니다
              </td>
            </tr>
          )}
          {data.trend.map((row) => (
            <tr
              key={`${row.dev_id}:${row.metric}`}
              data-testid={`part-trend-${row.dev_id}-${row.metric.replaceAll("_", "-")}`}
            >
              <td>{row.dev_nm}</td>
              <th>{metrics[row.metric] ?? row.metric}</th>
              {row.months.map((m) => (
                <td key={m.month} className={tables.amount}>
                  {row.metric === "mm_qty"
                    ? m.value.toFixed(2)
                    : money(m.value)}
                </td>
              ))}
              <td className={tables.amount}>
                {row.metric === "mm_qty"
                  ? row.months.reduce((s, m) => s + m.value, 0).toFixed(2)
                  : money(row.months.reduce((s, m) => s + m.value, 0))}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
