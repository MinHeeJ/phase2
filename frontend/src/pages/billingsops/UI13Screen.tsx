import { useCallback, useEffect, useRef, useState } from "react";
import { Badge, Button } from "../../shared/Controls";
import { ScreenHeading } from "../shared/ScreenHeading";
import { useDeveloperReadOnly } from "../developers/useDeveloperReadOnly";
import { saveProjectWorkbook } from "../projectsops/client";
import { readData } from "../../shared/api";
import { BillingDetailPanel, billingMoney } from "./BillingDetailPanel";
import { BillingEditor } from "./BillingEditor";
import { BillingAttendanceDialog } from "./BillingAttendanceDialog";
import * as client from "./client";
import type {
  BillingDetail,
  BillingFilters,
  BillingGapResult,
  BillingOverdueResult,
  BillingPage,
  BillingSummary,
} from "./client";
import table from "../developers/Developers.module.css";
import styles from "./BillingReview.module.css";

type Tab = "list" | "gaps" | "overdue";
const tabNames = {
  list: "청구 목록",
  gaps: "청구 누락 점검",
  overdue: "미수 알림",
};
export default function UI13Screen({
  name = "청구·입금",
  groupName = "매출",
  onUpload,
  embedded,
}: {
  name?: string;
  groupName?: string;
  onUpload?: () => void;
  embedded?: {
    tab: "gaps" | "overdue";
    year: number;
    readOnly: boolean;
    refreshVersion: number;
    openBilling: (id: number) => void;
    register: (identity: { prj_id: number; atrb_ym: string }) => void;
  };
}) {
  const currentYear = Number(
    new Intl.DateTimeFormat("en", {
      timeZone: "Asia/Seoul",
      year: "numeric",
    }).format(new Date()),
  );
  const [ownYear, setYear] = useState(currentYear),
    [ownTab, setTab] = useState<Tab>("list");
  const year = embedded?.year ?? ownYear,
    tab = embedded?.tab ?? ownTab;
  const [filters, setFilters] = useState<Record<Tab, BillingFilters>>({
    list: { page: 0, size: 50 },
    gaps: { page: 0, size: 50 },
    overdue: { page: 0, size: 50 },
  });
  const [draft, setDraft] = useState<Record<Tab, BillingFilters>>({
    list: {},
    gaps: {},
    overdue: {},
  });
  const [summary, setSummary] = useState<BillingSummary | null>(null);
  const [list, setList] = useState<BillingPage | null>(null),
    [gaps, setGaps] = useState<BillingGapResult | null>(null),
    [overdue, setOverdue] = useState<BillingOverdueResult | null>(null);
  const [detail, setDetail] = useState<BillingDetail | null>(null),
    [editing, setEditing] = useState<"new" | "edit" | null>(null);
  const [initial, setInitial] = useState<
    { projectId: number; month: string } | undefined
  >();
  const [attendance, setAttendance] = useState(false);
  const [loading, setLoading] = useState(false),
    [error, setError] = useState(""),
    [message, setMessage] = useState("");
  const [refresh, setRefresh] = useState(0),
    [exporting, setExporting] = useState(false);
  const previousYear = useRef(year);
  useEffect(() => {
    if (!embedded || previousYear.current === year) return;
    previousYear.current = year;
    const month = `${year}${String(filters.gaps.month ?? gaps?.month ?? `${year}-01`).slice(4)}`;
    setFilters((previous) => ({
      ...previous,
      gaps: { ...previous.gaps, month, page: 0 },
      overdue: { ...previous.overdue, page: 0 },
    }));
    setDraft((previous) => ({
      ...previous,
      gaps: { ...previous.gaps, month },
    }));
  }, [year, embedded, filters.gaps.month, gaps?.month]);
  const mobile = useDeveloperReadOnly(),
    readOnly =
      embedded?.readOnly ||
      mobile ||
      year < currentYear ||
      (tab === "gaps" &&
        Number(String(filters.gaps.month ?? gaps?.month ?? year).slice(0, 4)) <
          currentYear);
  useEffect(() => {
    if (readOnly) {
      setEditing(null);
      setAttendance(false);
    }
  }, [readOnly]);
  const query = useCallback(
    (): BillingFilters => ({
      ...filters[tab],
      ...(tab === "gaps" ? {} : { year }),
    }),
    [filters, tab, year],
  );
  useEffect(() => {
    let active = true;
    setLoading(true);
    setError("");
    const request =
      tab === "list"
        ? client.listBillings(query())
        : tab === "gaps"
          ? client.listBillingGaps(query())
          : client.listBillingOverdue(query());
    request
      .then((data) => {
        if (!active) return;
        if (tab === "list") setList(data as BillingPage);
        else if (tab === "gaps") setGaps(data as BillingGapResult);
        else setOverdue(data as BillingOverdueResult);
      })
      .catch((e) => {
        if (active) setError(e.message);
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [query, refresh, tab, embedded?.refreshVersion]);
  useEffect(() => {
    if (embedded) return;
    let active = true;
    client
      .getBillingSummary(year)
      .then((s) => {
        if (active) setSummary(s);
      })
      .catch((e) => {
        if (active) setError(e.message);
      });
    return () => {
      active = false;
    };
  }, [year, refresh]);
  async function open(id: number | null) {
    if (id === null) return;
    if (embedded) {
      embedded.openBilling(id);
      return;
    }
    try {
      setDetail(await client.getBilling(id));
    } catch (e) {
      setError((e as Error).message);
    }
  }
  async function changed(next: BillingDetail) {
    setDetail(next);
    setEditing(null);
    setMessage("저장했습니다.");
    setRefresh((v) => v + 1);
  }
  function update(key: string, value: string) {
    setDraft({ ...draft, [tab]: { ...draft[tab], [key]: value } });
  }
  function apply() {
    setFilters({
      ...filters,
      [tab]: { ...draft[tab], page: 0, size: filters[tab].size ?? 50 },
    });
  }
  function register(projectId: number, month: string) {
    if (embedded) embedded.register({ prj_id: projectId, atrb_ym: month });
    else {
      setInitial({ projectId, month });
      setEditing("new");
    }
  }
  function page(page: number) {
    setFilters({ ...filters, [tab]: { ...filters[tab], page } });
  }
  function changeYear(next: number) {
    setYear(next);
    const suffix = String(
      filters.gaps.month ?? gaps?.month ?? `${year}-01`,
    ).slice(4);
    const month = `${next}${suffix}`;
    setFilters((previous) => ({
      ...previous,
      gaps: { ...previous.gaps, month, page: 0 },
      list: { ...previous.list, page: 0 },
      overdue: { ...previous.overdue, page: 0 },
    }));
    setDraft((previous) => ({
      ...previous,
      gaps: { ...previous.gaps, month },
    }));
  }
  function sort(field: string) {
    const previous = filters[tab].sort;
    setFilters({
      ...filters,
      [tab]: {
        ...filters[tab],
        sort: `${field},${previous === `${field},asc` ? "desc" : "asc"}`,
        page: 0,
      },
    });
  }
  const result = tab === "list" ? list : tab === "gaps" ? gaps : overdue;
  const currentPage = Number(filters[tab].page ?? 0),
    size = Number(filters[tab].size ?? 50);
  async function download() {
    setExporting(true);
    try {
      const suffix = tab === "list" ? "" : `/${tab}`;
      const { page: _page, size: _size, ...conditions } = query();
      saveProjectWorkbook(
        await readData(
          `/api/billings${suffix}/export?${client.billingQuery(conditions)}`,
        ),
      );
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setExporting(false);
    }
  }
  const th = (label: string, field: string) => (
    <th key={field}>
      <button data-testid={`billing-sort-${field}`} onClick={() => sort(field)}>
        {label}
      </button>
    </th>
  );
  return (
    <section
      className={styles.page}
      data-testid={embedded ? `billing-review-${tab}` : "billing-screen"}
      data-screen-id="SCR-UI-13"
    >
      {!embedded && (
        <>
          <ScreenHeading
            name={name}
            groupName={groupName}
            screenId="UI-13"
            actions={
              !readOnly && (
                <>
                  <Button
                    data-testid="billing-upload"
                    onClick={() =>
                      onUpload ? onUpload() : setAttendance(true)
                    }
                  >
                    엑셀 업로드
                  </Button>
                  <Button
                    data-testid="billing-create"
                    variant="primary"
                    onClick={() => {
                      setInitial(undefined);
                      setEditing("new");
                    }}
                  >
                    ＋ 청구 등록
                  </Button>
                </>
              )
            }
          />
          <div className={styles.period}>
            <Button
              data-testid="billing-year-previous"
              aria-label="이전 해"
              onClick={() => changeYear(year - 1)}
            >
              ‹
            </Button>
            <strong>{year}년</strong>
            <Button
              data-testid="billing-year-next"
              aria-label="다음 해"
              onClick={() => changeYear(year + 1)}
            >
              ›
            </Button>
            <Button
              data-testid="billing-year-current"
              onClick={() => changeYear(currentYear)}
            >
              올해
            </Button>
            {readOnly && <span>조회·다운로드만 가능합니다.</span>}
          </div>
          <div role="tablist" className={styles.tabs}>
            {(Object.keys(tabNames) as Tab[]).map((t) => (
              <button
                key={t}
                data-testid={`billing-tab-${t}`}
                role="tab"
                aria-selected={t === tab}
                onClick={() => setTab(t)}
              >
                {tabNames[t]}
              </button>
            ))}
          </div>
          {summary && (
            <div className={styles.cards} data-testid="billing-summary">
              {[
                [
                  `${year}년 청구`,
                  summary.annual_confirmed_supply_amt,
                  "공급가 · 확정",
                ],
                [
                  "이번 달 청구",
                  summary.current_month_confirmed_supply_amt,
                  "공급가 · 확정",
                ],
                ["미수금", summary.confirmed_receivable_amt, "부가세 포함"],
                ["연체", summary.overdue_amt, "입금 예정일 지남"],
              ].map(([label, amount, caption], i) => (
                <article key={i} data-testid={`billing-summary-${i}`}>
                  <span>{label}</span>
                  <strong>{billingMoney(amount as number)}원</strong>
                  <span>{caption}</span>
                </article>
              ))}
              <p>
                예정 {billingMoney(summary.planned_supply_amt)}원 · 작성{" "}
                {billingMoney(summary.draft_supply_amt)}원 (매출·미수와 별도)
              </p>
            </div>
          )}
        </>
      )}
      <form
        className={table.search}
        data-testid="billing-filters"
        onSubmit={(e) => {
          e.preventDefault();
          apply();
        }}
      >
        {tab === "gaps" ? (
          <>
            <label>
              월
              <input
                data-testid="billing-gap-month"
                type="month"
                value={draft.gaps.month ?? ""}
                placeholder={gaps?.month}
                onChange={(e) => update("month", e.target.value)}
              />
            </label>
            <label>
              판정
              <select
                data-testid="billing-judgment"
                value={draft.gaps.judgment ?? ""}
                onChange={(e) => update("judgment", e.target.value)}
              >
                <option value="">전체</option>
                {[
                  "맞음",
                  "맞음(무상)",
                  "적게 청구",
                  "많이 청구",
                  "청구 없음",
                  "근태 미입력",
                ].map((s) => (
                  <option key={s}>{s}</option>
                ))}
              </select>
            </label>
          </>
        ) : tab === "overdue" ? (
          <>
            <label>
              단계
              <select
                data-testid="billing-stage-filter"
                value={draft.overdue.stage ?? ""}
                onChange={(e) => update("stage", e.target.value)}
              >
                <option value="">전체</option>
                {[0, 1, 2, 3].map((s) => (
                  <option key={s} value={s}>
                    {s}단계
                  </option>
                ))}
              </select>
            </label>
            <label>
              지난 일수
              <select
                data-testid="billing-aging"
                value={draft.overdue.overDays ?? ""}
                onChange={(e) => update("overDays", e.target.value)}
              >
                <option value="">전체</option>
                {[30, 60, 90].map((n) => (
                  <option key={n} value={n}>
                    {n}일 초과
                  </option>
                ))}
              </select>
            </label>
          </>
        ) : (
          <>
            <label>
              상태
              <select
                data-testid="billing-status"
                value={draft.list.status ?? ""}
                onChange={(e) => update("status", e.target.value)}
              >
                <option value="">전체</option>
                {["PLAN", "DRAFT", "CONFIRM", "CANCEL"].map((s) => (
                  <option key={s}>{s}</option>
                ))}
              </select>
            </label>
            <label>
              유형
              <select
                data-testid="billing-type"
                value={draft.list.type ?? ""}
                onChange={(e) => update("type", e.target.value)}
              >
                <option value="">전체</option>
                {["TK", "MO", "DP"].map((s) => (
                  <option key={s}>{s}</option>
                ))}
              </select>
            </label>
          </>
        )}
        <label>
          검색
          <input
            data-testid="billing-search"
            value={draft[tab].q ?? ""}
            onChange={(e) => update("q", e.target.value)}
          />
        </label>
        <Button
          data-testid="billing-reset"
          type="button"
          onClick={() => {
            setDraft({ ...draft, [tab]: {} });
            setFilters({ ...filters, [tab]: { page: 0, size: 50 } });
          }}
        >
          초기화
        </Button>
        <Button data-testid="billing-query" type="submit" variant="primary">
          조회
        </Button>
      </form>
      {error && (
        <div role="alert">
          {error}
          <Button
            data-testid="billing-retry"
            onClick={() => setRefresh((v) => v + 1)}
          >
            다시 시도
          </Button>
        </div>
      )}
      {message && (
        <p role="status" data-testid="billing-success">
          {message}
        </p>
      )}
      {tab === "gaps" && gaps && (
        <p>
          점검 월 {gaps.month} · 총 차이{" "}
          {billingMoney(gaps.total_difference_amt)}원
        </p>
      )}
      {tab === "overdue" && (
        <>
          <p className={styles.message}>
            알림 발송은 사용자·권한 기능 구축 후 사용할 수 있습니다.
          </p>
          {overdue && (
            <div className={styles.cards}>
              {overdue.stage_counts.map((s) => (
                <article
                  key={s.stage}
                  data-testid={`billing-stage-count-${s.stage}`}
                >
                  {s.stage}단계 · {s.count}건
                </article>
              ))}
            </div>
          )}
          {overdue?.customer_totals.map((c, i) => (
            <p key={c.customer_id ?? i}>
              {c.customer_name ?? "고객사 미지정"}:{" "}
              {billingMoney(c.receivable_amt)}원
            </p>
          ))}
        </>
      )}
      <div className={table.toolbar}>
        <span>총 {result?.total ?? "–"}건</span>
        <Button
          data-testid="billing-download"
          pending={exporting}
          onClick={() => void download()}
        >
          엑셀 다운로드
        </Button>
      </div>
      {loading ? (
        <p role="status" data-testid="billing-loading">
          불러오는 중…
        </p>
      ) : (
        !error &&
        result && (
          <div className={table.tableScroll} data-testid="billing-table-scroll">
            <table className={table.table} data-testid="billing-table">
              <thead>
                <tr>
                  {tab === "gaps" ? (
                    <>
                      {th("프로젝트", "project_name")}
                      {th("귀속월", "month")}
                      {th("투입 기준 금액", "basis_supply_amt")}
                      {th("청구 공급가", "confirmed_supply_amt")}
                      {th("차이", "difference_amt")}
                      {th("판정", "judgment")}
                      <th>설명</th>
                      <th>작업</th>
                    </>
                  ) : tab === "overdue" ? (
                    <>
                      {th("고객사", "customer_name")}
                      {th("프로젝트", "project_name")}
                      {th("청구번호", "bill_no")}
                      {th("미수금", "receivable_amt")}
                      {th("입금 예정일", "rcpt_plan_dt")}
                      {th("지난 일수", "overdue_days")}
                      {th("단계", "stage")}
                      <th>수신 역할</th>
                      <th>보낸 알림</th>
                      {th("다음 알림", "next_notification_date")}
                      <th>작업</th>
                    </>
                  ) : (
                    <>
                      {th("청구번호", "bill_no")}
                      {th("프로젝트", "project_name")}
                      <th>고객사</th>
                      <th>유형</th>
                      {th("귀속월", "atrb_ym")}
                      {th("청구일", "bill_dt")}
                      {th("공급가", "sply_amt")}
                      <th>부가세</th>
                      <th>합계</th>
                      <th>입금액</th>
                      {th("미수금", "receivable_amt")}
                      <th>입금 예정일</th>
                      <th>상태</th>
                      <th>세금계산서 번호</th>
                      <th>미수 알림</th>
                    </>
                  )}
                </tr>
              </thead>
              <tbody>
                {tab === "gaps"
                  ? gaps?.items.map((g) => (
                      <tr
                        key={g.project_id}
                        data-testid={`billing-gap-${g.project_id}`}
                      >
                        <td>
                          <a
                            data-testid={`billing-gap-project-${g.project_id}`}
                            href={`/UI-01?open=${g.project_id}`}
                          >
                            {g.project_name}
                          </a>
                        </td>
                        <td>{g.month}</td>
                        <td className={table.amount}>
                          {billingMoney(g.basis_supply_amt)}
                        </td>
                        <td className={table.amount}>
                          {billingMoney(g.confirmed_supply_amt)}
                        </td>
                        <td className={table.amount}>
                          {billingMoney(g.difference_amt)}
                        </td>
                        <td>
                          <Badge
                            tone={
                              g.judgment.startsWith("맞음")
                                ? "success"
                                : g.judgment === "많이 청구"
                                  ? "danger"
                                  : "warning"
                            }
                          >
                            {g.judgment}
                          </Badge>
                        </td>
                        <td>
                          {g.explanation}
                          {g.suspected_people
                            .map((p) => `${p.name} (${p.management_number})`)
                            .join(", ")}
                        </td>
                        <td>
                          {!readOnly && (
                            <Button
                              data-testid={`billing-gap-create-${g.project_id}`}
                              onClick={() => register(g.project_id, g.month)}
                            >
                              청구 등록
                            </Button>
                          )}
                          {g.billing_ids.map((id) => (
                            <Button
                              key={id}
                              data-testid={`billing-gap-open-${id}`}
                              onClick={() => void open(id)}
                            >
                              청구 고치기
                            </Button>
                          ))}
                        </td>
                      </tr>
                    ))
                  : tab === "overdue"
                    ? overdue?.items.map((o) => (
                        <tr
                          key={o.billing.bill_id}
                          data-testid={`billing-overdue-${o.billing.bill_id}`}
                        >
                          <td>{o.billing.customer_name ?? "–"}</td>
                          <td>{o.billing.project_name}</td>
                          <td>
                            <button
                              className={table.textButton}
                              data-testid={`billing-overdue-open-${o.billing.bill_id}`}
                              onClick={() => void open(o.billing.bill_id)}
                            >
                              {o.billing.bill_no}
                            </button>
                          </td>
                          <td className={table.amount}>
                            {billingMoney(o.billing.receivable_amt)}
                          </td>
                          <td>{o.billing.rcpt_plan_dt ?? "예정일 미입력"}</td>
                          <td>{o.overdue_days ?? "–"}</td>
                          <td>
                            <Badge
                              tone={
                                o.stage !== null && o.stage >= 2
                                  ? "danger"
                                  : o.stage === 1
                                    ? "warning"
                                    : "neutral"
                              }
                            >
                              {o.stage === null ? "–" : `${o.stage}단계`}
                            </Badge>
                          </td>
                          <td>{o.recipient_roles.join(" / ")}</td>
                          <td>
                            {o.sent_history.length
                              ? o.sent_history.map((h) => h.sent_at).join(", ")
                              : "보낸 알림 없음"}
                          </td>
                          <td>{o.next_notification_date ?? "–"}</td>
                          <td>
                            <Button
                              data-testid={`billing-remind-${o.billing.bill_id}`}
                              disabled
                              title="알림 발송은 사용자·권한 기능 구축 후 사용할 수 있습니다."
                            >
                              다시 알림
                            </Button>
                          </td>
                        </tr>
                      ))
                    : list?.items.map((b, i) => (
                        <tr
                          key={b.bill_id ?? `stage-${b.bill_stg_id}`}
                          data-testid={`billing-row-${b.bill_id ?? `stage-${b.bill_stg_id}`}`}
                        >
                          <td>
                            <button
                              data-testid={`billing-open-${i}`}
                              className={table.textButton}
                              onClick={() => void open(b.bill_id)}
                              disabled={b.virtual_plan}
                            >
                              {b.bill_no ?? "청구 예정"}
                            </button>
                          </td>
                          <td>
                            <a
                              data-testid={`billing-project-${i}`}
                              href={`/UI-01?open=${b.prj_id}`}
                            >
                              {b.project_name}
                            </a>
                          </td>
                          <td>{b.customer_name}</td>
                          <td>{b.contract_type}</td>
                          <td>{b.atrb_ym ?? "–"}</td>
                          <td>{b.bill_dt ?? "청구일 미입력"}</td>
                          <td className={table.amount}>
                            {billingMoney(b.sply_amt)}
                          </td>
                          <td className={table.amount}>
                            {billingMoney(b.vat_amt)}
                          </td>
                          <td className={table.amount}>
                            {billingMoney(b.tot_amt)}
                          </td>
                          <td className={table.amount}>
                            {billingMoney(b.received_amt)}
                          </td>
                          <td className={table.amount}>
                            {billingMoney(b.receivable_amt)}
                          </td>
                          <td>{b.rcpt_plan_dt ?? "–"}</td>
                          <td>
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
                          </td>
                          <td>{b.txinv_no ?? "번호 미입력"}</td>
                          <td>{b.reminder_stage ?? "–"}</td>
                        </tr>
                      ))}
                {result.items.length === 0 && (
                  <tr>
                    <td colSpan={15}>
                      <div className={styles.empty}>
                        조회 결과가 없습니다. 조회 조건을 확인하세요.
                      </div>
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
        )
      )}
      <nav aria-label="청구 목록 페이지" className={table.actions}>
        <Button
          data-testid="billing-page-previous"
          disabled={currentPage === 0 || loading}
          onClick={() => page(currentPage - 1)}
        >
          이전 쪽
        </Button>
        <span>{currentPage + 1}쪽</span>
        <Button
          data-testid="billing-page-next"
          disabled={
            !result || (currentPage + 1) * size >= result.total || loading
          }
          onClick={() => page(currentPage + 1)}
        >
          다음 쪽
        </Button>
        <select
          aria-label="한 화면 건수"
          data-testid="billing-page-size"
          value={size}
          onChange={(e) =>
            setFilters({
              ...filters,
              [tab]: { ...filters[tab], size: Number(e.target.value), page: 0 },
            })
          }
        >
          {[20, 50, 100].map((n) => (
            <option key={n} value={n}>
              {n}건씩
            </option>
          ))}
        </select>
      </nav>
      {detail && (
        <BillingDetailPanel
          detail={detail}
          readOnly={readOnly}
          onClose={() => setDetail(null)}
          onChanged={changed}
          onEdit={() => setEditing("edit")}
        />
      )}
      {editing && !readOnly && (
        <BillingEditor
          detail={editing === "edit" ? (detail ?? undefined) : undefined}
          initial={initial}
          onClose={() => setEditing(null)}
          onSaved={changed}
        />
      )}
      {attendance && !readOnly && (
        <BillingAttendanceDialog
          initial={initial}
          onClose={() => setAttendance(false)}
          onChanged={() => setRefresh((v) => v + 1)}
        />
      )}
    </section>
  );
}
