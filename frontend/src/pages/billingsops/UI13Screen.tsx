import { useCallback, useEffect, useState, type ReactNode } from "react";
import { Link } from "react-router-dom";
import { Badge, Button } from "../../shared/Controls";
import { Can } from "../../shared/Can";
import { readData } from "../../shared/api";
import { ScreenHeading } from "../shared/ScreenHeading";
import { seoulMonth } from "../shared/PeriodBar";
import { useDeveloperReadOnly } from "../developers/useDeveloperReadOnly";
import { listPartnerOptions } from "../partnersops/client";
import {
  BillingDialog,
  BillingLines,
  type BillingDialogProps,
  type BillingDialogMode,
} from "./BillingDialog";
import {
  getBillingSummary,
  listBillingGaps,
  listBillingOverdue,
  getBillingDetail,
  downloadBilling,
  billingQuery,
  writeBilling,
  type BillingRow,
  type BillingDetail,
  type BillingPage,
  type BillingFilters,
  type BillingGapPage,
  type BillingOverduePage,
  type BillingSummary,
} from "./client";
import table from "../developers/Developers.module.css";
import styles from "./Billing.module.css";

type Tab = "list" | "gaps" | "overdue";
const names = {
  list: "청구 목록",
  gaps: "청구 누락 점검",
  overdue: "미수 알림",
};
const money = (value: number | undefined | null) =>
  value == null ? "—" : `${value.toLocaleString("ko-KR")}원`;
const statusName: Record<string, string> = {
  PLAN: "예정",
  DRAFT: "작성",
  CONFIRM: "확정",
  CANCEL: "취소",
};
export default function UI13Screen({
  name = "청구·입금",
  groupName = "매출",
  attendanceDialog,
  onAttendance,
  renderAttendance,
  renderReceipts,
}: {
  name?: string;
  groupName?: string;
  attendanceDialog?: ReactNode;
  onAttendance?: () => void;
  renderAttendance?: (
    refresh: () => Promise<void>,
    readOnly: boolean,
  ) => ReactNode;
  renderReceipts?: (
    detail: BillingDetail,
    refresh: () => Promise<void>,
    year: number,
  ) => ReactNode;
}) {
  const currentYear = Number(seoulMonth().slice(0, 4));
  const [year, setYear] = useState(
    () => Number(sessionStorage.getItem("UI-13.year")) || currentYear,
  );
  const mobile = useDeveloperReadOnly(),
    readOnly = mobile || year < currentYear;
  const [tab, setTab] = useState<Tab>("list");
  const defaults = (): Record<Tab, BillingFilters> => ({
    list: { page: 0, size: 50, sort: "bill_no,asc" },
    gaps: { page: 0, size: 50, sort: "project_name,asc" },
    overdue: { page: 0, size: 50, sort: "bill_no,asc" },
  });
  const [filters, setFilters] = useState(defaults),
    [draft, setDraft] = useState(defaults);
  const [summary, setSummary] = useState<BillingSummary | null>(null);
  const [list, setList] = useState<BillingPage<BillingRow> | null>(null);
  const [gaps, setGaps] = useState<BillingGapPage | null>(null),
    [overdue, setOverdue] = useState<BillingOverduePage | null>(null);
  const [loading, setLoading] = useState(false),
    [error, setError] = useState(""),
    [message, setMessage] = useState("");
  const [detail, setDetail] = useState<BillingDetail | null>(null),
    [detailLoading, setDetailLoading] = useState(false);
  const [dialog, setDialog] = useState<Omit<
    BillingDialogProps,
    "onClose" | "onSaved"
  > | null>(null);
  const [customers, setCustomers] = useState<{ id: string; name: string }[]>(
    [],
  );
  const [refresh, setRefresh] = useState(0);
  useEffect(() => {
    void listPartnerOptions("CUST")
      .then(setCustomers)
      .catch(() => {});
  }, []);
  useEffect(() => {
    if (readOnly) setDialog(null);
  }, [readOnly]);
  const reload = useCallback(async () => {
    setLoading(true);
    setError("");
    try {
      const [summary, data] = await Promise.all([
        getBillingSummary(year),
        tab === "list"
          ? readData<BillingPage<BillingRow>>(
              `/api/billings?${billingQuery({ ...filters.list, year })}`,
            )
          : tab === "gaps"
            ? listBillingGaps(filters.gaps)
            : listBillingOverdue({ ...filters.overdue, year }),
      ]);
      setSummary(summary);
      if (tab === "list") setList(data as BillingPage<BillingRow>);
      if (tab === "gaps") setGaps(data as BillingGapPage);
      if (tab === "overdue") setOverdue(data as BillingOverduePage);
    } catch (e) {
      setError(e instanceof Error ? e.message : "조회하지 못했습니다.");
    } finally {
      setLoading(false);
    }
  }, [tab, year, filters, refresh]);
  useEffect(() => {
    void reload();
  }, [reload]);
  async function openDetail(id: number) {
    setDetailLoading(true);
    setError("");
    try {
      setDetail(await getBillingDetail(id));
    } catch (e) {
      setError(e instanceof Error ? e.message : "상세 조회 실패");
    } finally {
      setDetailLoading(false);
    }
  }
  async function saved(next?: BillingDetail) {
    if (next) setDetail(next);
    else if (detail?.billing.bill_id)
      setDetail(await getBillingDetail(detail.billing.bill_id));
    setMessage("저장했습니다.");
    setRefresh((v) => v + 1);
  }
  const changeFilter = (key: string, value: string) =>
    setDraft((f) => ({ ...f, [tab]: { ...f[tab], [key]: value } }));
  const apply = () =>
    setFilters((f) => ({ ...f, [tab]: { ...draft[tab], page: 0 } }));
  const pageData = tab === "list" ? list : tab === "gaps" ? gaps : overdue;
  const sort = (key: string) =>
    setFilters((f) => ({
      ...f,
      [tab]: {
        ...f[tab],
        page: 0,
        sort: `${key},${f[tab].sort === `${key},asc` ? "desc" : "asc"}`,
      },
    }));
  const column = (key: string, label: string) => (
    <th key={key}>
      <button data-testid={`billing-sort-${key}`} onClick={() => sort(key)}>
        {label}
      </button>
    </th>
  );
  const select = (
    key: string,
    label: string,
    options: { value: string; label: string }[],
  ) => (
    <label>
      {label}
      <select
        data-testid={`billing-filter-${key}`}
        value={draft[tab][key] ?? ""}
        onChange={(e) => changeFilter(key, e.target.value)}
      >
        <option value="">전체</option>
        {options.map((o) => (
          <option key={o.value} value={o.value}>
            {o.label}
          </option>
        ))}
      </select>
    </label>
  );
  const action = (mode: BillingDialogMode) => {
    if (!readOnly && detail) setDialog({ mode, detail });
  };
  async function recalculate() {
    if (!detail?.billing.bill_id || readOnly) return;
    try {
      await saved(
        await writeBilling(
          `/api/billings/${detail.billing.bill_id}`,
          { recalculate: true },
          "PUT",
        ),
      );
    } catch (e) {
      setError(e instanceof Error ? e.message : "다시 계산 실패");
    }
  }
  async function download() {
    try {
      await downloadBilling(tab, {
        ...filters[tab],
        ...(tab === "gaps" ? {} : { year }),
      });
    } catch (e) {
      setError(e instanceof Error ? e.message : "다운로드 실패");
    }
  }
  return (
    <section data-testid="billing-screen">
      <ScreenHeading
        screenId="UI-13"
        name={name}
        groupName={groupName}
        actions={
          !readOnly && (
            <div className={table.actions}>
              {onAttendance && (
                <Can perm="UI-13.upload">
                  <Button
                    data-testid="billing-attendance"
                    onClick={onAttendance}
                  >
                    엑셀 업로드
                  </Button>
                </Can>
              )}
              <Can perm="UI-13.create">
                <Button
                  data-testid="billing-create"
                  variant="primary"
                  onClick={() => setDialog({ mode: "create" })}
                >
                  ＋ 청구 등록
                </Button>
              </Can>
            </div>
          )
        }
      />
      <div className={styles.period} data-testid="billing-period">
        <Button
          data-testid="billing-year-previous"
          aria-label="이전 연도"
          onClick={() => {
            setYear(year - 1);
            sessionStorage.setItem("UI-13.year", String(year - 1));
            setDetail(null);
          }}
        >
          ‹
        </Button>
        <strong>{year}년</strong>
        <Button
          data-testid="billing-year-next"
          aria-label="다음 연도"
          onClick={() => {
            setYear(year + 1);
            sessionStorage.setItem("UI-13.year", String(year + 1));
            setDetail(null);
          }}
        >
          ›
        </Button>
        <Button
          data-testid="billing-year-current"
          variant="text"
          onClick={() => setYear(currentYear)}
        >
          올해
        </Button>
        <span>그 해에 청구한 건</span>
      </div>
      {readOnly && (
        <p className={styles.readOnly}>
          조회 전용입니다. 과거 연도와 휴대폰에서는 등록·변경할 수 없습니다.
        </p>
      )}
      <div role="tablist" aria-label="청구 업무" className={styles.tabs}>
        {(Object.keys(names) as Tab[]).map((key) => (
          <button
            data-testid={`billing-tab-${key}`}
            role="tab"
            key={key}
            aria-selected={tab === key}
            onClick={() => setTab(key)}
          >
            {names[key]}
          </button>
        ))}
      </div>
      {summary && (
        <div className={styles.cards} data-testid="billing-summary">
          {[
            [
              `${year}년 청구`,
              summary.annual_confirmed_supply_amt,
              "공급가 · CONFIRM만",
            ],
            [
              "이번 달 청구",
              summary.current_month_confirmed_supply_amt,
              "공급가",
            ],
            ["미수금", summary.confirmed_receivable_amt, "부가세 포함"],
            ["연체", summary.overdue_amt, "입금 예정일 지남"],
          ].map(([label, value, hint]) => (
            <div className={styles.card} key={String(label)}>
              <span>{label}</span>
              <strong>{money(value as number)}</strong>
              <small>{hint}</small>
            </div>
          ))}
          <p className={styles.separate}>
            예정 {money(summary.planned_supply_amt)} · 작성{" "}
            {money(summary.draft_supply_amt)} — 확정 매출·미수와 별도
          </p>
        </div>
      )}
      <form
        className={table.search}
        onSubmit={(e) => {
          e.preventDefault();
          apply();
        }}
      >
        {tab === "list" && (
          <>
            {select(
              "status",
              "상태",
              Object.entries(statusName).map(([value, label]) => ({
                value,
                label,
              })),
            )}
            {select("type", "계약유형", [
              { value: "TK", label: "턴키" },
              { value: "MO", label: "월단위" },
              { value: "DP", label: "파견" },
            ])}
          </>
        )}
        {tab === "gaps" && (
          <>
            <label>
              월
              <input
                data-testid="billing-filter-month"
                type="month"
                value={draft.gaps.month ?? gaps?.month ?? ""}
                onChange={(e) => changeFilter("month", e.target.value)}
              />
            </label>
            {select(
              "judgment",
              "판정",
              [
                "맞음",
                "맞음(무상)",
                "적게 청구",
                "많이 청구",
                "청구 없음",
                "근태 미입력",
              ].map((v) => ({ value: v, label: v })),
            )}
          </>
        )}
        {tab === "overdue" && (
          <>
            {select(
              "stage",
              "단계",
              [0, 1, 2, 3].map((v) => ({
                value: String(v),
                label: `${v}단계`,
              })),
            )}
            {select(
              "overDays",
              "지난 일수",
              [30, 60, 90].map((v) => ({
                value: String(v),
                label: `${v}일 초과`,
              })),
            )}
          </>
        )}
        {tab !== "gaps" &&
          select(
            "customerId",
            "고객사",
            customers.map((c) => ({ value: c.id, label: c.name })),
          )}
        <label>
          검색
          <input
            data-testid="billing-filter-q"
            value={draft[tab].q ?? ""}
            onChange={(e) => changeFilter("q", e.target.value)}
          />
        </label>
        <Button
          data-testid="billing-reset"
          type="button"
          onClick={() => {
            const f = defaults()[tab];
            setDraft((d) => ({ ...d, [tab]: f }));
            setFilters((d) => ({ ...d, [tab]: f }));
          }}
        >
          초기화
        </Button>
        <Button data-testid="billing-search" variant="primary" type="submit">
          조회
        </Button>
      </form>
      {message && (
        <p role="status" data-testid="billing-success">
          {message}
        </p>
      )}
      {error && (
        <div role="alert" className={styles.error}>
          <span>{error}</span>
          <Button data-testid="billing-retry" onClick={() => void reload()}>
            다시 시도
          </Button>
        </div>
      )}
      {loading || detailLoading ? (
        <p aria-label="불러오는 중">불러오는 중…</p>
      ) : (
        <>
          <div className={table.toolbar}>
            <span>
              총 {pageData?.total ?? 0}건
              {tab === "gaps" &&
                ` · 총 차이 ${money(gaps?.total_difference_amt)}`}
            </span>
            <Can perm="UI-13.download">
              <Button
                data-testid="billing-export"
                onClick={() => void download()}
              >
                ↓ 엑셀 다운로드
              </Button>
            </Can>
          </div>
          {tab === "overdue" && (
            <>
              <div className={styles.cards}>
                {overdue?.stage_counts.map((s) => (
                  <div className={styles.card} key={s.stage}>
                    {s.stage}단계 <strong>{s.count}건</strong>
                  </div>
                ))}
              </div>
              <p>
                고객사별 미수 합계:{" "}
                {overdue?.customer_totals
                  .map(
                    (c) =>
                      `${c.customer_name ?? "미지정"} ${money(c.receivable_amt)}`,
                  )
                  .join(" · ")}
              </p>
              <p>알림 발송은 사용자·권한 기능 구축 후 사용할 수 있습니다.</p>
            </>
          )}
          <div
            className={table.tableScroll}
            role="tabpanel"
            data-testid={`billing-panel-${tab}`}
          >
            <table className={table.table}>
              <thead>
                <tr>
                  {tab === "list" ? (
                    <>
                      {column("bill_no", "청구번호")}
                      {column("project_name", "프로젝트")}
                      <th>고객사</th>
                      <th>계약유형</th>
                      <th>귀속월</th>
                      {column("bill_dt", "청구일")}
                      {column("sply_amt", "공급가")}
                      <th>부가세</th>
                      <th>합계</th>
                      <th>입금액</th>
                      {column("receivable_amt", "미수금")}
                      <th>입금 예정일</th>
                      <th>상태</th>
                      <th>세금계산서 번호</th>
                      <th>미수 알림</th>
                    </>
                  ) : tab === "gaps" ? (
                    <>
                      {column("project_name", "프로젝트")}
                      <th>귀속월</th>
                      {column("basis_supply_amt", "투입 기준 금액")}
                      {column("confirmed_supply_amt", "청구 공급가")}
                      {column("difference_amt", "차이")}
                      {column("judgment", "판정")}
                      <th>설명</th>
                      <th>작업</th>
                    </>
                  ) : (
                    <>
                      {column("customer_name", "고객사")}
                      {column("project_name", "프로젝트")}
                      {column("bill_no", "청구번호")}
                      {column("receivable_amt", "미수금")}
                      {column("rcpt_plan_dt", "입금 예정일")}
                      {column("overdue_days", "지난 일수")}
                      {column("stage", "단계")}
                      <th>수신 역할</th>
                      <th>보낸 알림</th>
                      <th>다음 알림</th>
                      <th>작업</th>
                    </>
                  )}
                </tr>
              </thead>
              <tbody>
                {!pageData?.items.length && (
                  <tr>
                    <td
                      colSpan={tab === "list" ? 15 : tab === "gaps" ? 8 : 11}
                      className={styles.empty}
                    >
                      조건에 맞는 결과가 없습니다
                    </td>
                  </tr>
                )}
                {tab === "list" &&
                  list?.items.map((row) => (
                    <tr
                      key={
                        row.bill_id == null
                          ? `stage-${row.bill_stg_id}`
                          : `bill-${row.bill_id}`
                      }
                      data-testid={`billing-row-${row.bill_id ?? `stage-${row.bill_stg_id}`}`}
                    >
                      <td>
                        <button
                          data-testid={`billing-open-${row.bill_id ?? `stage-${row.bill_stg_id}`}`}
                          className={table.textButton}
                          onClick={() =>
                            row.bill_id
                              ? void openDetail(row.bill_id)
                              : !readOnly &&
                                setDialog({
                                  mode: "create",
                                  projectId: row.prj_id,
                                  stageId: row.bill_stg_id ?? undefined,
                                })
                          }
                        >
                          {row.bill_no ?? "청구 예정"}
                        </button>
                      </td>
                      <td>
                        <Link
                          data-testid={`billing-project-${row.bill_id ?? `stage-${row.bill_stg_id}`}`}
                          to={`/UI-01?open=${row.prj_id}`}
                        >
                          {row.project_name}
                        </Link>
                      </td>
                      <td>{row.customer_name ?? "—"}</td>
                      <td>{row.contract_type}</td>
                      <td>{row.atrb_ym ?? "—"}</td>
                      <td>{row.bill_dt ?? "—"}</td>
                      {[
                        row.sply_amt,
                        row.vat_amt,
                        row.tot_amt,
                        row.received_amt,
                        row.receivable_amt,
                      ].map((n, i) => (
                        <td key={i} className={table.amount}>
                          {money(n)}
                        </td>
                      ))}
                      <td>{row.rcpt_plan_dt ?? "예정일 미입력"}</td>
                      <td>
                        <BillingStatus row={row} />
                      </td>
                      <td>{row.txinv_no ?? "번호 없음"}</td>
                      <td>
                        <ReminderStage stage={row.reminder_stage} />
                      </td>
                    </tr>
                  ))}
                {tab === "gaps" &&
                  gaps?.items.map((row) => (
                    <tr
                      key={`${row.project_id}-${row.month}`}
                      data-testid={`billing-gap-${row.project_id}`}
                    >
                      <td>
                        <Link
                          data-testid={`billing-gap-project-${row.project_id}`}
                          to={`/UI-01?open=${row.project_id}`}
                        >
                          {row.project_name}
                        </Link>
                      </td>
                      <td>{row.month}</td>
                      {[
                        row.basis_supply_amt,
                        row.confirmed_supply_amt,
                        row.difference_amt,
                      ].map((n, i) => (
                        <td key={i} className={table.amount}>
                          {money(n)}
                        </td>
                      ))}
                      <td>
                        <Badge
                          tone={
                            row.judgment.startsWith("맞음")
                              ? "success"
                              : row.attendance_missing
                                ? "warning"
                                : "danger"
                          }
                        >
                          {row.judgment}
                        </Badge>
                      </td>
                      <td>
                        {row.explanation}
                        {row.suspected_people
                          .map((p) => `${p.name} (${p.management_number})`)
                          .join(" · ")}
                      </td>
                      <td>
                        {!readOnly && (
                          <Can perm="UI-13.create">
                            <Button
                              data-testid={`billing-gap-create-${row.project_id}`}
                              onClick={() =>
                                setDialog({
                                  mode: "create",
                                  projectId: row.project_id,
                                  month: row.month,
                                })
                              }
                            >
                              청구 등록
                            </Button>
                          </Can>
                        )}
                        {row.billing_ids.map((id) => (
                          <Button
                            key={id}
                            data-testid={`billing-gap-open-${id}`}
                            onClick={() => void openDetail(id)}
                          >
                            청구 고치기
                          </Button>
                        ))}
                      </td>
                    </tr>
                  ))}
                {tab === "overdue" &&
                  overdue?.items.map((row) => (
                    <tr
                      key={row.billing.bill_id}
                      data-testid={`billing-overdue-${row.billing.bill_id}`}
                    >
                      <td>{row.billing.customer_name ?? "—"}</td>
                      <td>{row.billing.project_name}</td>
                      <td>
                        <Button
                          data-testid={`billing-overdue-open-${row.billing.bill_id}`}
                          variant="text"
                          onClick={() =>
                            row.billing.bill_id &&
                            void openDetail(row.billing.bill_id)
                          }
                        >
                          {row.billing.bill_no}
                        </Button>
                      </td>
                      <td className={table.amount}>
                        {money(row.billing.receivable_amt)}
                      </td>
                      <td>{row.billing.rcpt_plan_dt ?? "예정일 미입력"}</td>
                      <td>{row.overdue_days ?? "—"}</td>
                      <td>
                        <ReminderStage stage={row.stage} />
                      </td>
                      <td>{row.recipient_roles.join(", ") || "—"}</td>
                      <td>
                        {row.sent_history.length
                          ? row.sent_history.map((s) => s.sent_at).join(", ")
                          : "보낸 알림 없음"}
                      </td>
                      <td>{row.next_notification_date ?? "—"}</td>
                      <td>
                        <Button
                          data-testid={`billing-remind-${row.billing.bill_id}`}
                          disabled
                          title="알림 발송은 사용자·권한 기능 구축 후 사용할 수 있습니다."
                        >
                          다시 알림
                        </Button>
                      </td>
                    </tr>
                  ))}
              </tbody>
            </table>
          </div>
          <div className={table.actions}>
            <Button
              data-testid="billing-page-previous"
              disabled={Number(filters[tab].page) === 0}
              onClick={() =>
                setFilters((f) => ({
                  ...f,
                  [tab]: { ...f[tab], page: Number(f[tab].page) - 1 },
                }))
              }
            >
              이전 쪽
            </Button>
            <span>{Number(filters[tab].page) + 1}쪽</span>
            <Button
              data-testid="billing-page-next"
              disabled={
                (Number(filters[tab].page) + 1) * Number(filters[tab].size) >=
                (pageData?.total ?? 0)
              }
              onClick={() =>
                setFilters((f) => ({
                  ...f,
                  [tab]: { ...f[tab], page: Number(f[tab].page) + 1 },
                }))
              }
            >
              다음 쪽
            </Button>
            <select
              data-testid="billing-page-size"
              aria-label="목록 건수"
              value={filters[tab].size}
              onChange={(e) =>
                setFilters((f) => ({
                  ...f,
                  [tab]: { ...f[tab], size: Number(e.target.value), page: 0 },
                }))
              }
            >
              {[20, 50, 100].map((n) => (
                <option key={n} value={n}>
                  {n}건
                </option>
              ))}
            </select>
          </div>
        </>
      )}
      {detail && (
        <aside
          className={styles.drawer}
          data-testid="billing-detail"
          aria-label="청구 상세"
        >
          <header className={table.drawerHeader}>
            <h2>청구 상세 · {detail.billing.bill_no}</h2>
            <Button
              data-testid="billing-detail-close"
              onClick={() => setDetail(null)}
            >
              닫기
            </Button>
          </header>
          <p>
            {detail.billing.project_name} · {detail.billing.customer_name}
            <BillingStatus row={detail.billing} />
          </p>
          <p>
            귀속월 {detail.billing.atrb_ym ?? "—"} · 청구일{" "}
            {detail.billing.bill_dt ?? "—"}
          </p>
          <p>
            공급가 {money(detail.billing.sply_amt)} · 부가세{" "}
            {money(detail.billing.vat_amt)} · 합계{" "}
            {money(detail.billing.tot_amt)}
          </p>
          <p>
            세금계산서 {detail.billing.txinv_no ?? "번호 없음"} · 작성일{" "}
            {detail.billing.txinv_dt ?? "—"} (기록만)
          </p>
          <p>입금 예정일 {detail.billing.rcpt_plan_dt ?? "예정일 미입력"}</p>
          <BillingLines lines={detail.details} />
          {detail.warnings.map((w) => (
            <p key={w}>
              <Badge tone="warning">{w}</Badge>
            </p>
          ))}
          {detail.original_bill_id && (
            <Button
              data-testid="billing-original"
              onClick={() => void openDetail(detail.original_bill_id!)}
            >
              원본 청구 열기
            </Button>
          )}
          {detail.amendments.map((a) => (
            <Button
              key={a.bill_id}
              data-testid={`billing-amendment-${a.bill_id}`}
              onClick={() => a.bill_id && void openDetail(a.bill_id)}
            >
              {a.bill_no} · {statusName[a.bill_stts_cd]}
            </Button>
          ))}
          {renderReceipts ? (
            renderReceipts(detail, () => saved(), year)
          ) : (
            <>
              <h3>입금 이력</h3>
              {detail.receipts.length === 0 && <p>입금 이력이 없습니다.</p>}
              {detail.receipts.map((r) => (
                <div
                  key={r.receipt_id}
                  data-testid={`billing-receipt-${r.receipt_id}`}
                >
                  {r.rcpt_dt} · {money(r.rcpt_amt)} · {r.rmrk_desc}
                  {!readOnly && (
                    <Can perm="UI-13.receipt">
                      <Button
                        data-testid={`billing-receipt-edit-${r.receipt_id}`}
                        onClick={() =>
                          setDialog({
                            mode: "receipt-edit",
                            detail,
                            receipt: r,
                          })
                        }
                      >
                        입금 수정
                      </Button>
                      <Button
                        data-testid={`billing-receipt-delete-${r.receipt_id}`}
                        onClick={() =>
                          setDialog({
                            mode: "receipt-delete",
                            detail,
                            receipt: r,
                          })
                        }
                      >
                        입금 삭제
                      </Button>
                    </Can>
                  )}
                </div>
              ))}
              <p>
                누적 입금 {money(detail.billing.received_amt)} · 조정 청구 총액{" "}
                {money(detail.billing.adjusted_total_amt)} · 미수금{" "}
                {money(detail.billing.receivable_amt)}
              </p>
            </>
          )}
          <h3>변경 이력</h3>
          {detail.change_history.map((h, i) => (
            <p key={i}>
              {h.changed_at} · {h.description} · {h.user_id ?? "기본 사용자"}
            </p>
          ))}
          {!readOnly && (
            <div className={table.actions}>
              {detail.billing.bill_stts_cd === "DRAFT" && (
                <>
                  <Can perm="UI-13.update">
                    <Button
                      data-testid="billing-edit"
                      onClick={() => action("edit")}
                    >
                      수정
                    </Button>
                    <Button
                      data-testid="billing-recalculate"
                      onClick={() => void recalculate()}
                    >
                      다시 계산
                    </Button>
                  </Can>
                  <Can perm="UI-13.confirm">
                    <Button
                      data-testid="billing-confirm"
                      onClick={() => action("confirm")}
                    >
                      확정
                    </Button>
                  </Can>
                </>
              )}
              {["DRAFT", "CONFIRM"].includes(detail.billing.bill_stts_cd) &&
                detail.receipts.length === 0 && (
                  <Can perm="UI-13.cancel">
                    <Button
                      data-testid="billing-cancel"
                      onClick={() => action("cancel")}
                    >
                      청구 취소
                    </Button>
                  </Can>
                )}
              {detail.billing.bill_stts_cd === "CONFIRM" &&
                !detail.original_bill_id && (
                  <>
                    <Can perm="UI-13.create">
                      <Button
                        data-testid="billing-amend"
                        onClick={() => action("amend")}
                      >
                        수정 청구
                      </Button>
                    </Can>
                    {!renderReceipts && (
                      <Can perm="UI-13.receipt">
                        <Button
                          data-testid="billing-receipt-add"
                          onClick={() => action("receipt")}
                        >
                          입금 등록
                        </Button>
                      </Can>
                    )}
                  </>
                )}
            </div>
          )}
        </aside>
      )}
      {dialog && !readOnly && (
        <BillingDialog
          key={`${dialog.mode}-${dialog.detail?.billing.bill_id ?? dialog.projectId ?? "new"}`}
          {...dialog}
          onClose={() => setDialog(null)}
          onSaved={saved}
        />
      )}
      {attendanceDialog}
      {renderAttendance?.(() => saved(), readOnly)}
    </section>
  );
}
function ReminderStage({ stage }: { stage: number | null }) {
  return stage == null ? (
    <span>—</span>
  ) : (
    <Badge tone={stage >= 2 ? "danger" : stage === 1 ? "warning" : "neutral"}>
      {stage}단계
    </Badge>
  );
}
function BillingStatus({ row }: { row: BillingRow }) {
  return (
    <Badge
      tone={
        row.bill_stts_cd === "CANCEL"
          ? "neutral"
          : row.bill_stts_cd !== "CONFIRM"
            ? "info"
            : row.receivable_amt === 0
              ? "success"
              : "warning"
      }
    >
      {row.bill_stts_cd === "CONFIRM"
        ? row.receivable_amt === 0
          ? "입금 완료"
          : "미수"
        : statusName[row.bill_stts_cd]}
    </Badge>
  );
}
