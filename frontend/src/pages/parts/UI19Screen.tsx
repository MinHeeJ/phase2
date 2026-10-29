import { useEffect, useState } from "react";
import {
  Badge,
  Button,
  LockedBanner,
  Modal,
  Skeleton,
} from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { ApiFailure } from "../../shared/api";
import { ScreenHeading } from "../shared/ScreenHeading";
import { seoulMonth } from "../shared/PeriodBar";
import {
  listSettlements,
  getSettlement,
  saveSettlementCard,
  confirmSettlement,
  cancelSettlement,
  downloadSettlements,
  type PartSettlementDetail,
  type PartSettlementPage,
} from "./client";
import table from "../developers/Developers.module.css";
import shared from "../shared/SharedScreens.module.css";
import styles from "./PartSettlements.module.css";

const labels: Record<string, string> = {
  MM: "M/M",
  PRICE: "단가",
  COST: "비용",
  DIFFERENCE: "차액",
  PROFIT: "이익금",
};
const employee: Record<string, string> = {
  FT: "정규직",
  CT: "계약직",
  FL: "프리랜서",
  OS: "외주",
};
const failure = (error: unknown) =>
  error instanceof ApiFailure
    ? error
    : new ApiFailure("처리하지 못했습니다.", null, null);

// SCR-UI-19
export default function UI19Screen({
  name = "영업 파트 이익금",
  groupName = "원가·수익",
}: {
  name?: string;
  groupName?: string;
}) {
  const [month, setMonth] = useState(
    () => sessionStorage.getItem("part-settlement-month") ?? seoulMonth(),
  );
  const [page, setPage] = useState<PartSettlementPage | null>(null);
  const [selected, setSelected] = useState<number | null>(null);
  const [detail, setDetail] = useState<PartSettlementDetail | null>(null);
  const [view, setView] = useState<"MONTH" | "TREND">("MONTH");
  const [revision, setRevision] = useState(0);
  const [loading, setLoading] = useState(true),
    [detailLoading, setDetailLoading] = useState(false);
  const [error, setError] = useState<ApiFailure | null>(null);
  const [writeError, setWriteError] = useState<ApiFailure | null>(null);
  const [amount, setAmount] = useState("0"),
    [note, setNote] = useState("");
  const [pending, setPending] = useState(false),
    [success, setSuccess] = useState("");
  const [dialog, setDialog] = useState<"confirm" | "cancel" | null>(null),
    [reason, setReason] = useState("");
  const [width, setWidth] = useState(window.innerWidth);
  const [unit, setUnit] = useState(1000);
  useEffect(() => {
    const resize = () => setWidth(window.innerWidth);
    window.addEventListener("resize", resize);
    return () => window.removeEventListener("resize", resize);
  }, []);
  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError(null);
    setPage(null);
    listSettlements(month, controller.signal)
      .then((result) => {
        if (controller.signal.aborted) return;
        setPage(result);
        setSelected((previous) =>
          result.items.some((r) => r.bsn_part_id === previous)
            ? previous
            : (result.items[0]?.bsn_part_id ?? null),
        );
      })
      .catch((e) => {
        if (!controller.signal.aborted) setError(failure(e));
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [month, revision]);
  useEffect(() => {
    if (selected == null) {
      setDetail(null);
      return;
    }
    const controller = new AbortController();
    setDetailLoading(true);
    setDetail(null);
    setWriteError(null);
    getSettlement(selected, month, view, controller.signal)
      .then((result) => {
        if (controller.signal.aborted) return;
        setDetail(result);
        setAmount(String(result.settlement.corp_card_amt));
        setNote(result.note ?? "");
      })
      .catch((e) => {
        if (!controller.signal.aborted) setError(failure(e));
      })
      .finally(() => {
        if (!controller.signal.aborted) setDetailLoading(false);
      });
    return () => controller.abort();
  }, [selected, month, view, revision]);
  const updateMonth = (value: string) => {
    if (!/^(?!0000)[0-9]{4}-(0[1-9]|1[0-2])$/.test(value)) return;
    if (
      amount !== String(detail?.settlement.corp_card_amt ?? 0) ||
      note !== (detail?.note ?? "")
    ) {
      if (
        !window.confirm(
          "저장하지 않은 카드 입력이 있습니다. 기간을 변경할까요?",
        )
      )
        return;
    }
    sessionStorage.setItem("part-settlement-month", value);
    setMonth(value);
    setDialog(null);
    setSuccess("");
  };
  const locked =
    !detail ||
    detail.status === "CLOSED" ||
    month.slice(0, 4) !== (detail.referenceMonth || seoulMonth()).slice(0, 4);
  const confirmed = detail?.settlement.stlm_stts_cd === "CONFIRM";
  const desktop = width > 767;
  const editable = desktop && !locked && !confirmed;
  const won = (value: number | null | undefined) =>
    value == null
      ? "–"
      : (value / unit).toLocaleString("ko-KR", { maximumFractionDigits: 1 });
  const mm = (value: number | null) => (value == null ? "–" : value.toFixed(2));
  const mutate = async (action: "card" | "confirm" | "cancel") => {
    if (selected == null || pending || !desktop || locked) return;
    setWriteError(null);
    setSuccess("");
    if (
      action === "card" &&
      (!/^[0-9]+$/.test(amount) || !Number.isSafeInteger(Number(amount)))
    ) {
      setWriteError(
        new ApiFailure(
          "카드액은 0 이상 원 단위로 입력하세요.",
          400,
          null,
          "corp_card_amt",
          "VALIDATION_ERROR",
        ),
      );
      return;
    }
    if (action === "cancel" && (!reason.trim() || reason.length > 500)) {
      setWriteError(
        new ApiFailure(
          "취소 사유를 입력하세요. (500자 이하)",
          400,
          null,
          "reason",
          "VALIDATION_ERROR",
        ),
      );
      return;
    }
    setPending(true);
    try {
      const result =
        action === "card"
          ? await saveSettlementCard(selected, month, Number(amount), note)
          : action === "confirm"
            ? await confirmSettlement(selected, month)
            : await cancelSettlement(selected, month, reason.trim());
      setDetail(result);
      setAmount(String(result.settlement.corp_card_amt));
      setNote(result.note ?? "");
      setDialog(null);
      setReason("");
      setSuccess(
        action === "card"
          ? "카드 금액을 저장했습니다."
          : action === "confirm"
            ? "정산을 확정했습니다."
            : "확정을 취소했습니다.",
      );
      setRevision((value) => value + 1);
    } catch (e) {
      setWriteError(failure(e));
    } finally {
      setPending(false);
    }
  };
  const download = async () => {
    setWriteError(null);
    setPending(true);
    try {
      const file = await downloadSettlements(month);
      const url = URL.createObjectURL(file.blob);
      const anchor = document.createElement("a");
      anchor.href = url;
      anchor.download = file.filename;
      anchor.click();
      URL.revokeObjectURL(url);
      setSuccess("엑셀 다운로드를 완료했습니다.");
    } catch (e) {
      setWriteError(failure(e));
    } finally {
      setPending(false);
    }
  };
  const h = detail?.settlement;
  return (
    <section
      className={styles.screen}
      data-testid="part-settlements-screen"
      data-screen-id="SCR-UI-19"
    >
      <ScreenHeading
        screenId="UI-19"
        name={name}
        groupName={groupName}
        actions={
          <Button
            data-testid="settlement-download"
            pending={pending}
            onClick={() => void download()}
          >
            엑셀 다운로드
          </Button>
        }
      />
      <section className={shared.periodControls} aria-label="조회 기간">
        <label>
          조회월{" "}
          <input
            data-testid="settlement-month"
            type="month"
            min="0001-01"
            max="9999-12"
            value={month}
            disabled={pending}
            onChange={(e) => updateMonth(e.target.value)}
          />
        </label>
        {page && (
          <Badge tone={page.status === "CLOSED" ? "locked" : "info"}>
            {page.status === "CLOSED" ? "마감" : "작업중"}
          </Badge>
        )}
        <span>해당 월 개인 귀속 파트 기준 · 본사 제외</span>
      </section>
      <ErrorBanner error={error} onRetry={() => setRevision((v) => v + 1)} />
      <ErrorBanner error={writeError} />
      {success && (
        <p role="status" data-testid="settlement-success">
          {success}
        </p>
      )}
      {loading ? (
        <Skeleton />
      ) : page?.items.length === 0 ? (
        <p className={table.empty}>조건에 맞는 결과가 없습니다.</p>
      ) : null}
      {page && page.items.length > 0 && (
        <nav className={table.actions} aria-label="영업 파트">
          {page.items.map((row) => (
            <Button
              data-testid={`settlement-part-${row.bsn_part_id}`}
              key={row.bsn_part_id}
              aria-pressed={selected === row.bsn_part_id}
              disabled={pending}
              onClick={() => {
                setSelected(row.bsn_part_id);
                setDialog(null);
                setError(null);
              }}
            >
              {row.bsn_part_nm}{" "}
              <Badge
                tone={row.stlm_stts_cd === "CONFIRM" ? "success" : "warning"}
              >
                {row.stlm_stts_cd === "CONFIRM" ? "확정" : "잠정"}
              </Badge>
            </Button>
          ))}
        </nav>
      )}
      {detailLoading && <Skeleton label="정산 내역 불러오는 중" />}
      {detail && h && (
        <section data-testid="settlement-detail-panel">
          {detail.provisional && (
            <p className={styles.warning}>
              D-31/D-32 운영 승인 대기 · 현재 계산은 잠정이며 정산 확정은
              보류됩니다.
            </p>
          )}
          {detail.warnings.map((warning, i) => (
            <p className={styles.warning} key={`${i}-${warning}`}>
              {warning}
            </p>
          ))}
          {(locked || confirmed) && (
            <LockedBanner>
              {locked
                ? "지난 연도 또는 마감한 달은 조회·다운로드만 가능합니다."
                : "확정된 정산입니다. 기준·인력별 금액·법인카드가 잠겼습니다."}
            </LockedBanner>
          )}
          <div className={styles.summary}>
            {[
              ["파트장", detail.ldr_nm ?? "–"],
              [
                "배분율",
                h.prft_dstrb_rt == null
                  ? "–"
                  : `${h.prft_dstrb_rt.toFixed(1)}%`,
              ],
              ["인원", `${h.psn_cnt}명`],
              ["차액 합계", won(h.tot_diff_amt)],
              ["파트 이익금", won(h.part_prft_amt)],
            ].map(([label, value]) => (
              <dl key={label}>
                <dt>{label}</dt>
                <dd>{value}</dd>
              </dl>
            ))}
          </div>
          <div className={table.card}>
            <h2>법인카드 사용액</h2>
            <p>
              개인 이익금 합 {won(h.tot_psn_prft_amt)} − 법인카드{" "}
              {won(h.corp_card_amt)} = 파트 이익금 {won(h.part_prft_amt)}
            </p>
            {editable ? (
              <form
                className={styles.cardForm}
                onSubmit={(e) => {
                  e.preventDefault();
                  void mutate("card");
                }}
              >
                <label>
                  법인카드 사용액 (원)
                  <input
                    data-testid="settlement-card-amount"
                    inputMode="numeric"
                    value={amount}
                    disabled={pending}
                    aria-invalid={writeError?.field === "corp_card_amt"}
                    onChange={(e) => setAmount(e.target.value)}
                  />
                </label>
                <label>
                  비고
                  <textarea
                    data-testid="settlement-card-note"
                    value={note}
                    maxLength={500}
                    disabled={pending}
                    onChange={(e) => setNote(e.target.value)}
                  />
                </label>
                <Button
                  data-testid="settlement-card-save"
                  type="submit"
                  pending={pending}
                >
                  카드 금액 저장
                </Button>
              </form>
            ) : (
              <p>비고: {detail.note || "–"}</p>
            )}
            {desktop && !locked && (
              <div className={table.writeActions}>
                {confirmed ? (
                  <Button
                    data-testid="settlement-cancel-open"
                    onClick={() => {
                      setDialog("cancel");
                      setWriteError(null);
                    }}
                  >
                    확정 취소
                  </Button>
                ) : (
                  <Button
                    data-testid="settlement-confirm-open"
                    variant="primary"
                    disabled={pending || !detail.confirmation_allowed}
                    title={
                      !detail.confirmation_allowed
                        ? "승인된 적용 정산 기준이 없어 확정할 수 없습니다."
                        : undefined
                    }
                    onClick={() => setDialog("confirm")}
                  >
                    정산 확정
                  </Button>
                )}
              </div>
            )}
          </div>
          <div className={table.toolbar}>
            <div role="tablist" aria-label="정산 보기">
              <Button
                data-testid="settlement-month-tab"
                role="tab"
                aria-selected={view === "MONTH"}
                onClick={() => {
                  setView("MONTH");
                  setError(null);
                }}
              >
                이번 달
              </Button>
              <Button
                data-testid="settlement-trend-tab"
                role="tab"
                aria-selected={view === "TREND"}
                onClick={() => {
                  setView("TREND");
                  setError(null);
                }}
              >
                기간 추이
              </Button>
            </div>
            <label>
              단위{" "}
              <select
                data-testid="settlement-unit"
                value={unit}
                onChange={(e) => setUnit(Number(e.target.value))}
              >
                <option value={1000}>천 원</option>
                <option value={1}>원</option>
              </select>
            </label>
          </div>
          <div
            role="tabpanel"
            data-testid="settlement-table-panel"
            className={table.tableScroll}
          >
            {view === "MONTH" ? (
              <table className={table.table}>
                <thead>
                  <tr>
                    {[
                      "사람",
                      "구분",
                      "프로젝트",
                      "MM",
                      "단가",
                      "급여",
                      "영업비",
                      "퇴직금",
                      "비용 계",
                      "차액",
                      "이익금",
                      "비고",
                    ].map((label) => (
                      <th key={label}>{label}</th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {detail.items.map((row) => (
                    <tr
                      data-testid={`settlement-person-${row.dev_id}`}
                      key={row.dev_id}
                    >
                      <td>{row.dev_nm}</td>
                      <td>{employee[row.emp_se_cd] ?? row.emp_se_cd}</td>
                      <td>{row.projects ?? "–"}</td>
                      <td className={table.amount}>{mm(row.mm_qty)}</td>
                      {[
                        row.mth_untprc_amt,
                        row.slry_amt,
                        row.bsn_fee_amt,
                        row.rtrpay_amt,
                        row.tot_exp_amt,
                        row.diff_amt,
                        row.prft_amt,
                      ].map((value, i) => (
                        <td className={table.amount} key={i}>
                          {won(value)}
                        </td>
                      ))}
                      <td>{row.rmrk_desc}</td>
                    </tr>
                  ))}
                  {detail.items.length === 0 && (
                    <tr>
                      <td colSpan={12} className={table.empty}>
                        조건에 맞는 결과가 없습니다.
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>
            ) : (
              <table className={table.table}>
                <thead>
                  <tr>
                    <th>사람</th>
                    <th>항목</th>
                    {detail.months.map((m) => (
                      <th key={m.month}>
                        {m.month}
                        <br />
                        <Badge
                          tone={
                            m.stlm_stts_cd === "CONFIRM" ? "success" : "warning"
                          }
                        >
                          {m.stlm_stts_cd === "CONFIRM" ? "확정" : "잠정"}
                        </Badge>
                      </th>
                    ))}
                    <th>합계</th>
                  </tr>
                </thead>
                <tbody>
                  {detail.trend.map((row) => (
                    <tr
                      data-testid={`settlement-trend-${row.dev_id}-${row.metric.toLowerCase()}`}
                      key={`${row.dev_id}-${row.metric}`}
                    >
                      {row.metric === "MM" && <td rowSpan={5}>{row.dev_nm}</td>}
                      <td>{labels[row.metric] ?? row.metric}</td>
                      {row.values.map((value, i) => (
                        <td
                          className={table.amount}
                          key={detail.months[i]?.month ?? i}
                        >
                          {row.metric === "MM" ? mm(value) : won(value)}
                        </td>
                      ))}
                      <td className={table.amount}>
                        {row.metric === "MM" ? mm(row.total) : won(row.total)}
                      </td>
                    </tr>
                  ))}
                  {detail.trend.length === 0 && (
                    <tr>
                      <td
                        colSpan={detail.months.length + 3}
                        className={table.empty}
                      >
                        조건에 맞는 결과가 없습니다.
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>
            )}
          </div>
          <details className={table.card}>
            <summary>계산 방법</summary>
            <div className={table.tableScroll}>
              <table className={table.table}>
                <thead>
                  <tr>
                    <th>항목</th>
                    <th>계산식</th>
                    <th>참고</th>
                  </tr>
                </thead>
                <tbody>
                  <tr>
                    <td>급여</td>
                    <td>월 급여 × 첫 투입일부터 월말까지 달력일 / 월 일수</td>
                    <td>첫 투입월만 일할, 사람별 1회</td>
                  </tr>
                  <tr>
                    <td>퇴직금</td>
                    <td>월 급여 원천의 퇴직금 적립액</td>
                    <td>정규직에만</td>
                  </tr>
                  <tr>
                    <td>비용</td>
                    <td>급여 + 영업비 + 퇴직금</td>
                    <td>외주 영업비 제외</td>
                  </tr>
                  <tr>
                    <td>차액</td>
                    <td>단가 − 비용</td>
                    <td>적자면 음수</td>
                  </tr>
                  <tr>
                    <td>파트 이익금</td>
                    <td>인력별 (차액 × 배분율) 합 − 법인카드</td>
                    <td>음수 상계 · 카드 1회 차감</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </details>
          <details className={table.card}>
            <summary>기준 변경 이력</summary>
            <div className={table.tableScroll}>
              <table className={table.table}>
                <thead>
                  <tr>
                    <th>적용 시작</th>
                    <th>적용 종료</th>
                    <th>파트장</th>
                    <th>배분율</th>
                    <th>영업비</th>
                  </tr>
                </thead>
                <tbody>
                  {detail.standards.map((s) => (
                    <tr
                      key={s.part_std_hist_id}
                      data-testid={`settlement-standard-${s.part_std_hist_id}`}
                    >
                      <td>{s.aply_bgn_ym}</td>
                      <td>{s.aply_end_ym ?? "현재"}</td>
                      <td>{s.ldr_nm ?? "–"}</td>
                      <td>{s.prft_dstrb_rt.toFixed(1)}%</td>
                      <td className={table.amount}>{won(s.bsn_fee_amt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </details>
          <details className={table.card}>
            <summary>확정·취소 이력</summary>
            <div className={table.tableScroll}>
              <table className={table.table}>
                <thead>
                  <tr>
                    <th>처리</th>
                    <th>사유</th>
                    <th>처리자</th>
                    <th>처리 일시</th>
                  </tr>
                </thead>
                <tbody>
                  {detail.history.map((item) => (
                    <tr
                      key={item.stlm_hist_id}
                      data-testid={`settlement-history-${item.stlm_hist_id}`}
                    >
                      <td>
                        {item.stlm_prcs_cd === "CONFIRM" ? "확정" : "취소"}
                      </td>
                      <td>{item.rsn_desc ?? "–"}</td>
                      <td>{item.prcs_user_id ?? "기본 사용자"}</td>
                      <td>{item.prcs_at}</td>
                    </tr>
                  ))}
                  {detail.history.length === 0 && (
                    <tr>
                      <td colSpan={4} className={table.empty}>
                        확정·취소 이력이 없습니다.
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>
          </details>
        </section>
      )}
      {desktop && !locked && dialog && (
        <Modal
          title={dialog === "cancel" ? "정산 확정 취소" : "정산 확정"}
          onCancel={() => {
            if (!pending) setDialog(null);
          }}
        >
          <div data-testid="settlement-confirmation-dialog">
            <p>
              {dialog === "cancel"
                ? "확정을 취소하고 작업중으로 되돌립니다. 기존 이력은 보존됩니다."
                : "원천을 다시 검증하고 기준·인력별 상세·카드 금액을 고정합니다."}
            </p>
            {dialog === "cancel" && (
              <label>
                취소 사유 (필수)
                <textarea
                  data-testid="settlement-cancel-reason"
                  maxLength={500}
                  value={reason}
                  disabled={pending}
                  onChange={(e) => setReason(e.target.value)}
                />
              </label>
            )}
            <Button
              data-testid="settlement-dialog-close"
              disabled={pending}
              onClick={() => setDialog(null)}
            >
              닫기
            </Button>
            <Button
              data-testid="settlement-dialog-submit"
              pending={pending}
              disabled={dialog === "cancel" && !reason.trim()}
              onClick={() => void mutate(dialog)}
            >
              {dialog === "cancel" ? "확정 취소" : "확정"}
            </Button>
          </div>
        </Modal>
      )}
    </section>
  );
}
