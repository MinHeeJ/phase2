import { useEffect, useState } from "react";
import { ApiFailure } from "../../shared/api";
import { Badge, Button } from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { ScreenHeading } from "../shared/ScreenHeading";
import { useScreenPeriod } from "../shared/useScreenPeriod";
import styles from "../developers/Developers.module.css";
import {
  getPartSettlement,
  listPartSettlements,
  type SettlementDetail,
  type SettlementMonth,
  type SettlementSummary,
} from "./client";

const amount = (value: number | null | undefined) =>
  value == null ? "–" : value.toLocaleString("ko-KR");
const failure = (error: unknown) =>
  error instanceof ApiFailure
    ? error
    : new ApiFailure("정산 자료를 조회할 수 없습니다.", null, null);

// SCR-UI-19 — /UI-19. The shell's PeriodBar owns the visible shared period.
export default function UI19Screen({
  name = "영업 파트 이익금",
  groupName,
  onDirtyChange,
}: {
  name?: string;
  groupName?: string;
  onDirtyChange?: (dirty: boolean) => void;
}) {
  const [month] = useScreenPeriod("UI-19");
  const [parts, setParts] = useState<SettlementSummary[]>([]);
  const [selected, setSelected] = useState<number | null>(null);
  const [view, setView] = useState<"MONTH" | "TREND">("MONTH");
  const [detail, setDetail] = useState<SettlementDetail | null>(null);
  const [error, setError] = useState<ApiFailure | null>(null);
  const [loading, setLoading] = useState(true);
  const [reload, setReload] = useState(0);
  const [card, setCard] = useState("");
  const [remark, setRemark] = useState("");
  const [dirty, setDirty] = useState(false);
  useEffect(() => {
    onDirtyChange?.(dirty);
  }, [dirty, onDirtyChange]);
  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError(null);
    setDetail(null);
    setParts([]);
    listPartSettlements(month, controller.signal)
      .then((rows) => {
        if (controller.signal.aborted) return;
        setParts(rows);
        setSelected((previous) =>
          rows.some((r) => r.part.bsn_part_id === previous)
            ? previous
            : (rows[0]?.part.bsn_part_id ?? null),
        );
        setLoading(false);
      })
      .catch((e) => {
        if (!controller.signal.aborted) {
          setError(failure(e));
          setLoading(false);
        }
      });
    return () => controller.abort();
  }, [month, reload]);
  useEffect(() => {
    if (selected == null || !parts.some((p) => p.part.bsn_part_id === selected))
      return;
    const controller = new AbortController();
    setLoading(true);
    setError(null);
    setDetail(null);
    getPartSettlement(selected, month, view, controller.signal)
      .then((data) => {
        if (controller.signal.aborted) return;
        setDetail(data);
        setLoading(false);
        if (!dirty) {
          const current = data.months.find((m) => m.month === month);
          setCard(String(current?.corp_card_amt ?? ""));
          setRemark(current?.card_remark ?? "");
        }
      })
      .catch((e) => {
        if (!controller.signal.aborted) {
          setError(failure(e));
          setLoading(false);
        }
      });
    return () => controller.abort();
  }, [selected, month, view, parts]);
  const current = detail?.months.find((m) => m.month === month);
  const locked = !current || current.closed || current.status === "CONFIRM";
  const cardInvalid = card !== "" && !/^\d+$/.test(card);
  const choose = (id: number) => {
    if (
      dirty &&
      !window.confirm("저장하지 않은 입력을 버리고 파트를 바꾸시겠습니까?")
    )
      return;
    setDirty(false);
    setSelected(id);
  };
  return (
    <section data-testid="part-settlement-screen" data-screen-id="SCR-UI-19">
      <ScreenHeading
        screenId="UI-19"
        name={name}
        groupName={groupName}
        actions={
          <Button
            data-testid="part-download-button"
            disabled
            title="다운로드 HTTP 연결과 열 매핑 승인 대기"
          >
            엑셀 다운로드
          </Button>
        }
      />
      <p role="status" data-testid="part-approval-message">
        D-31/D-32 운영 승인 대기 — 잠정 계산이며 정산 확정은 보류됩니다.
      </p>
      <p>
        카드·확정·취소 요청 본문과 결과 스키마가 승인되지 않아 저장 작업을
        사용할 수 없습니다. 입력값은 유지됩니다.
      </p>
      <p>다운로드 HTTP 연결과 열 매핑 승인 대기</p>
      <ErrorBanner error={error} />
      {error?.field && (
        <p data-testid="part-error-field">입력 항목: {error.field}</p>
      )}
      {error && (
        <Button
          data-testid="part-retry-button"
          onClick={() => setReload((n) => n + 1)}
        >
          다시 시도
        </Button>
      )}
      <nav aria-label="영업 파트" className={styles.actions}>
        {parts.map((p) => (
          <Button
            key={p.part.bsn_part_id}
            data-testid={`part-tab-${p.part.bsn_part_id}`}
            aria-pressed={p.part.bsn_part_id === selected}
            onClick={() => choose(p.part.bsn_part_id)}
          >
            {p.part.bsn_part_nm} ·{" "}
            {p.settlement.status === "CONFIRM" ? "확정" : "잠정"}
          </Button>
        ))}
      </nav>
      {loading && (
        <p data-testid="part-loading" role="status">
          정산 자료를 불러오는 중입니다.
        </p>
      )}
      {!loading && !error && parts.length === 0 && (
        <p className={styles.empty}>조회할 영업 파트가 없습니다.</p>
      )}
      {current && (
        <>
          <section className={styles.card} data-testid="part-summary-panel">
            <Badge
              tone={
                current.closed
                  ? "locked"
                  : current.status === "CONFIRM"
                    ? "success"
                    : "warning"
              }
            >
              {current.month} · {current.closed ? "마감" : "작업중"} ·{" "}
              {current.status === "CONFIRM" ? "확정" : "잠정"}
            </Badge>
            <p>
              파트장 {current.standard?.ldr_nm ?? "–"} · 배분율{" "}
              {current.standard?.prft_dstrb_rt ?? "–"}% · 인원{" "}
              {current.people.length}명
            </p>
            <p>
              차액 합계{" "}
              {amount(
                current.people.some((p) => p.diff_amt == null)
                  ? null
                  : current.people.reduce((s, p) => s + (p.diff_amt ?? 0), 0),
              )}
              원 · 파트 이익금 {amount(current.part_prft_amt)}원
            </p>
          </section>
          {current.warnings.map((warning, i) => (
            <p
              role="status"
              key={`${i}-${warning}`}
              data-testid={`part-warning-${i}`}
            >
              {warning}
            </p>
          ))}
          <section
            className={styles.writeActions}
            data-testid="part-write-panel"
            aria-label="법인카드 입력"
          >
            <label>
              법인카드 사용액{" "}
              <input
                data-testid="part-card-input"
                inputMode="numeric"
                value={card}
                readOnly={locked}
                aria-invalid={cardInvalid}
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
                value={remark}
                maxLength={500}
                readOnly={locked}
                onChange={(e) => {
                  setRemark(e.target.value);
                  setDirty(true);
                }}
              />
            </label>
            {cardInvalid && (
              <p role="alert">카드액은 0 이상 원 단위 정수여야 합니다.</p>
            )}
            <Button data-testid="part-card-save-button" disabled>
              카드 금액 저장
            </Button>
            {current.status === "CONFIRM" ? (
              <Button data-testid="part-cancel-button" disabled>
                확정 취소
              </Button>
            ) : (
              <Button data-testid="part-confirm-button" disabled>
                정산 확정
              </Button>
            )}
          </section>
          <nav aria-label="정산 조회 구분" className={styles.actions}>
            <Button
              data-testid="part-month-tab"
              aria-pressed={view === "MONTH"}
              onClick={() => setView("MONTH")}
            >
              이번 달
            </Button>
            <Button
              data-testid="part-trend-tab"
              aria-pressed={view === "TREND"}
              onClick={() => setView("TREND")}
            >
              기간 추이
            </Button>
          </nav>
          <section data-testid="part-detail-panel" role="tabpanel">
            {view === "MONTH" ? (
              <MonthTable month={current} />
            ) : (
              <TrendTable months={detail?.months ?? []} />
            )}
          </section>
          <section data-testid="part-history-panel">
            <h2>변경·확정 이력</h2>
            {current.history.length === 0 ? (
              <p className={styles.empty}>정산 이력이 없습니다.</p>
            ) : (
              current.history.map((h, i) => (
                <p key={`${h.prcs_at}-${i}`} data-testid={`part-history-${i}`}>
                  {h.prcs_at} ·{" "}
                  {h.stlm_prcs_cd === "CONFIRM" ? "확정" : "확정 취소"} ·{" "}
                  {h.rsn_desc} · {h.prcs_user_id ?? "기본 사용자"}
                </p>
              ))
            )}
          </section>
        </>
      )}
      <details data-testid="part-calculation-panel">
        <summary data-testid="part-calculation-toggle">계산 방법</summary>
        <p>
          월별 급여 귀속 파트 기준, 본사 제외. 급여는 실제 지급액, 외주는 부가세
          제외 공급가액을 사용합니다. 첫 달 급여는 최초 유효 투입 시작일부터
          월말까지 달력일로 한 번만 일할 계산합니다.
        </p>
        <p>
          월 비용 = 급여 + 영업비(외주 제외) + 퇴직금 적립(정규직만). 사람별
          차액 × 배분율은 음수를 포함해 원 단위 내림합니다. 사람별 이익금을 합한
          뒤 법인카드를 한 번 차감합니다.
        </p>
        <p>
          확정 월은 저장 상세와 기준 스냅샷을 사용하며 소급 수정하지 않습니다.
          취소는 미마감 확정 건에 사유가 필요합니다.
        </p>
      </details>
    </section>
  );
}
function MonthTable({ month }: { month: SettlementMonth }) {
  return (
    <div className={styles.tableScroll}>
      <table className={styles.table}>
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
          {month.people.length === 0 && (
            <tr>
              <td colSpan={12} className={styles.empty}>
                해당 월 귀속 인력이 없습니다.
              </td>
            </tr>
          )}
          {month.people.map((p) => (
            <tr key={p.dev_id} data-testid={`part-person-${p.dev_id}`}>
              <td>{p.dev_nm}</td>
              <td>{p.emp_se_cd}</td>
              <td>인력별 합산</td>
              <td className={styles.amount}>{p.mm_qty.toFixed(2)}</td>
              {[
                p.mth_untprc_amt,
                p.slry_amt,
                p.bsn_fee_amt,
                p.rtrpay_amt,
                p.tot_exp_amt,
                p.diff_amt,
                p.prft_amt,
              ].map((n, i) => (
                <td key={i} className={styles.amount}>
                  {amount(n)}
                </td>
              ))}
              <td>{p.rmrk_desc}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
function TrendTable({ months }: { months: SettlementMonth[] }) {
  const people = new Map(
    months.flatMap((m) => m.people.map((p) => [p.dev_id, p.dev_nm] as const)),
  );
  const metrics = [
    ["mm_qty", "M/M"],
    ["mth_untprc_amt", "단가"],
    ["tot_exp_amt", "비용"],
    ["diff_amt", "차액"],
    ["prft_amt", "이익금"],
  ] as const;
  return (
    <div className={styles.tableScroll}>
      <table className={styles.table}>
        <thead>
          <tr>
            <th>사람</th>
            <th>항목</th>
            {months.map((m) => (
              <th key={m.month}>
                {m.month}{" "}
                <Badge tone={m.status === "CONFIRM" ? "success" : "warning"}>
                  {m.status === "CONFIRM" ? "확정" : "잠정"}
                </Badge>
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {people.size === 0 && (
            <tr>
              <td className={styles.empty} colSpan={months.length + 2}>
                기간 내 귀속 인력이 없습니다.
              </td>
            </tr>
          )}
          {[...people].flatMap(([id, name]) =>
            metrics.map(([key, label]) => (
              <tr
                key={`${id}-${key}`}
                data-testid={`part-trend-${id}-${key.replaceAll("_", "-")}`}
              >
                <td>{name}</td>
                <th>{label}</th>
                {months.map((m) => {
                  const p = m.people.find((row) => row.dev_id === id);
                  return (
                    <td className={styles.amount} key={m.month}>
                      {p
                        ? key === "mm_qty"
                          ? p.mm_qty.toFixed(2)
                          : amount(p[key])
                        : "–"}
                    </td>
                  );
                })}
              </tr>
            )),
          )}
          <tr data-testid="part-trend-total">
            <th colSpan={2}>파트 이익금 (카드 1회 차감)</th>
            {months.map((m) => (
              <td className={styles.amount} key={m.month}>
                {amount(m.part_prft_amt)}
              </td>
            ))}
          </tr>
        </tbody>
      </table>
    </div>
  );
}
