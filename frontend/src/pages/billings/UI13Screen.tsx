import { useEffect, useState, type ReactNode } from "react";
import { Link } from "react-router-dom";
import { Badge, Button, SuccessToast } from "../../shared/Controls";
import { ApiFailure } from "../../shared/api";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { ScreenHeading } from "../shared/ScreenHeading";
import { seoulMonth } from "../shared/PeriodBar";
import { BillingEditor } from "./BillingEditor";
import { BillingDrawer, BillingStatus } from "./BillingDrawer";
import {
  billingDefaults,
  downloadBillings,
  getBillingSummary,
  listBillings,
  type BillingDetail,
  type BillingFilters,
  type BillingPage,
  type BillingRow,
  type BillingSummary,
} from "./client";
import tables from "../developers/Developers.module.css";
import styles from "./Billings.module.css";

export interface BillingScreenContext {
  year: number;
  readOnly: boolean;
  refresh: () => void;
  openBilling: (id: number) => void;
}
export interface BillingScreenProps {
  name?: string;
  groupName?: string;
  onDirtyChange?: (dirty: boolean) => void;
  onUpload?: (context: BillingScreenContext) => void;
  onAttendance?: (
    projectId: number,
    month: string,
    context: BillingScreenContext,
  ) => void;
  renderActions?: (
    detail: BillingDetail,
    refresh: () => void,
    context: BillingScreenContext,
  ) => ReactNode;
  renderReceipts?: (
    detail: BillingDetail,
    refresh: () => void,
    context: BillingScreenContext,
  ) => ReactNode;
  renderTab?: (
    tab: "gaps" | "overdue",
    context: {
      year: number;
      readOnly: boolean;
      refreshVersion: number;
      openBilling: (id: number) => void;
      register: (row: Partial<BillingRow>) => void;
    },
  ) => ReactNode;
}
export default function UI13Screen({
  name = "청구·입금",
  groupName = "매출",
  onDirtyChange,
  onUpload,
  onAttendance,
  renderActions,
  renderReceipts,
  renderTab,
}: BillingScreenProps) {
  const currentYear = Number(seoulMonth().slice(0, 4));
  const [year, setYear] = useState(currentYear),
    [tab, setTab] = useState<"list" | "gaps" | "overdue">("list");
  const [draft, setDraft] = useState(() => billingDefaults(currentYear)),
    [filters, setFilters] = useState(() => billingDefaults(currentYear));
  const [page, setPage] = useState<BillingPage | null>(null),
    [summary, setSummary] = useState<BillingSummary | null>(null);
  const [error, setError] = useState<ApiFailure | null>(null),
    [summaryError, setSummaryError] = useState<ApiFailure | null>(null);
  const [loading, setLoading] = useState(true),
    [revision, setRevision] = useState(0),
    [message, setMessage] = useState("");
  const [detailId, setDetailId] = useState<number | null>(null);
  const [editor, setEditor] = useState<{
    detail?: BillingDetail;
    initial?: Partial<BillingRow>;
  } | null>(null);
  const [mobile, setMobile] = useState(() => window.innerWidth <= 767);
  useEffect(() => {
    const resize = () => setMobile(window.innerWidth <= 767);
    window.addEventListener("resize", resize);
    return () => window.removeEventListener("resize", resize);
  }, []);
  const readOnly = year < currentYear || mobile;
  useEffect(() => {
    onDirtyChange?.(!!editor);
    return () => onDirtyChange?.(false);
  }, [!!editor, onDirtyChange]);
  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError(null);
    listBillings({ ...filters, year }, controller.signal)
      .then((data) => {
        if (!controller.signal.aborted) setPage(data);
      })
      .catch((e: ApiFailure) => {
        if (!controller.signal.aborted) setError(e);
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [filters, year, revision]);
  useEffect(() => {
    const controller = new AbortController();
    setSummary(null);
    setSummaryError(null);
    getBillingSummary(year, controller.signal)
      .then((data) => {
        if (!controller.signal.aborted) setSummary(data);
      })
      .catch((e: ApiFailure) => {
        if (!controller.signal.aborted) setSummaryError(e);
      });
    return () => controller.abort();
  }, [year, revision]);
  const context = {
    year,
    readOnly,
    refresh: () => setRevision((v) => v + 1),
    openBilling: setDetailId,
  };
  const register = (row?: Partial<BillingRow>) => {
    if (!readOnly) setEditor({ initial: row });
  };
  const shift = (amount: number) => {
    if (editor && !window.confirm("입력 중인 내용을 버리고 이동할까요?"))
      return;
    setEditor(null);
    setDetailId(null);
    setYear((y) => y + amount);
    setFilters((f) => ({ ...f, page: 0 }));
  };
  const search = () => setFilters({ ...draft, page: 0 });
  const reset = () => {
    setDraft(billingDefaults(year));
    setFilters(billingDefaults(year));
  };
  const sort = (key: string) =>
    setFilters((f) => ({
      ...f,
      sort: `${key},${f.sort === `${key},asc` ? "desc" : "asc"}`,
      page: 0,
    }));
  const saved = (detail: BillingDetail) => {
    setEditor(null);
    setDetailId(detail.billing.bill_id);
    setRevision((v) => v + 1);
    setMessage("저장했습니다.");
  };
  const download = async () => {
    try {
      await downloadBillings({ ...filters, year });
      setMessage("엑셀 파일을 내려받았습니다.");
    } catch (e) {
      setError(e as ApiFailure);
    }
  };
  const patch = (key: keyof BillingFilters, value: string) =>
    setDraft((old) => ({ ...old, [key]: value }));
  const columns = [
    ["bill_no", "청구번호"],
    ["project_name", "프로젝트"],
    ["customer_name", "고객사"],
    ["contract_type", "계약유형"],
    ["atrb_ym", "귀속월"],
    ["bill_dt", "청구일"],
    ["sply_amt", "공급가"],
    ["vat_amt", "부가세"],
    ["tot_amt", "합계"],
    ["received_amt", "입금액"],
    ["receivable_amt", "미수금"],
    ["rcpt_plan_dt", "입금 예정일"],
    ["bill_stts_cd", "상태"],
    ["txinv_no", "세금계산서 번호"],
    ["reminder_stage", "미수 알림"],
  ];
  const sortable = new Set([
    "bill_no",
    "project_name",
    "atrb_ym",
    "bill_dt",
    "sply_amt",
    "vat_amt",
    "tot_amt",
    "received_amt",
    "receivable_amt",
    "rcpt_plan_dt",
    "bill_stts_cd",
  ]);
  return (
    <section data-testid="billing-workspace" data-screen-id="SCR-UI-13">
      <ScreenHeading
        screenId="UI-13"
        name={name}
        groupName={groupName}
        actions={
          !readOnly && (
            <div className={tables.writeActions}>
              <a
                data-testid="billing-template"
                className={tables.linkButton}
                href="/api/uploads/templates/B.3"
              >
                양식 내려받기
              </a>
              {onUpload && (
                <Button
                  data-testid="billing-upload"
                  onClick={() => onUpload(context)}
                >
                  엑셀 업로드
                </Button>
              )}
              <Button
                data-testid="billing-register"
                variant="primary"
                onClick={() => register()}
              >
                ＋ 청구 등록
              </Button>
            </div>
          )
        }
      />
      <div className={styles.period}>
        <Button
          data-testid="billing-year-previous"
          disabled={year <= 1}
          onClick={() => shift(-1)}
        >
          ‹ 이전 연도
        </Button>
        <strong>{year}년</strong>
        <Button
          data-testid="billing-year-next"
          disabled={year >= 9998}
          onClick={() => shift(1)}
        >
          다음 연도 ›
        </Button>
        {year === currentYear && <Badge tone="info">올해</Badge>}
        {year < currentYear && (
          <Badge tone="locked">과거 연도 · 읽기 전용</Badge>
        )}
      </div>
      <div className={styles.tabs} role="tablist">
        {(
          [
            ["list", "청구 목록"],
            ["gaps", "청구 누락 점검"],
            ["overdue", "미수 알림"],
          ] as const
        ).map(([key, label]) => (
          <button
            data-testid={`billing-tab-${key}`}
            key={key}
            role="tab"
            aria-selected={tab === key}
            onClick={() => setTab(key)}
          >
            {label}
          </button>
        ))}
      </div>
      <div className={styles.cards}>
        {[
          [`${year}년 청구 (공급가)`, summary?.annual_confirmed_supply_amt],
          [
            "이번 달 청구 (공급가)",
            summary?.current_month_confirmed_supply_amt,
          ],
          ["미수금 (부가세 포함)", summary?.confirmed_receivable_amt],
          ["연체 (부가세 포함)", summary?.overdue_amt],
        ].map(([label, value]) => (
          <article
            className={styles.card}
            key={label}
            data-testid={`billing-summary-${String(label).includes("이번") ? "month" : String(label).startsWith("미수") ? "receivable" : String(label).startsWith("연체") ? "overdue" : "year"}`}
          >
            <span>{label}</span>
            <strong>
              {typeof value === "number"
                ? `${value.toLocaleString("ko-KR")} 원`
                : "—"}
            </strong>
          </article>
        ))}
      </div>
      <ErrorBanner error={summaryError} />
      {summary && (
        <p className={tables.help}>
          예정 공급가 {summary.planned_supply_amt.toLocaleString("ko-KR")}원 ·
          작성 공급가 {summary.draft_supply_amt.toLocaleString("ko-KR")}원 (확정
          매출·미수 제외)
        </p>
      )}
      <div
        role="tabpanel"
        data-testid="billing-list-panel"
        hidden={tab !== "list"}
      >
        <form
          className={tables.search}
          onSubmit={(e) => {
            e.preventDefault();
            search();
          }}
        >
          <label>
            상태
            <select
              data-testid="billing-filter-status"
              value={draft.status}
              onChange={(e) => patch("status", e.target.value)}
            >
              <option value="">전체</option>
              {[
                ["PLAN", "예정"],
                ["DRAFT", "작성"],
                ["CONFIRM", "확정"],
                ["CANCEL", "취소"],
                ["PAID", "입금 완료"],
                ["UNPAID", "미수"],
                ["OVERDUE", "연체"],
              ].map(([value, label]) => (
                <option key={value} value={value}>
                  {label}
                </option>
              ))}
            </select>
          </label>
          <label>
            유형
            <select
              data-testid="billing-filter-type"
              value={draft.type}
              onChange={(e) => patch("type", e.target.value)}
            >
              <option value="">전체</option>
              <option value="TK">턴키</option>
              <option value="MO">월단위</option>
              <option value="DP">파견</option>
            </select>
          </label>
          <label>
            고객사 식별자
            <input
              data-testid="billing-filter-customer"
              type="number"
              min="1"
              value={draft.customerId}
              onChange={(e) => patch("customerId", e.target.value)}
            />
          </label>
          <label>
            검색
            <input
              data-testid="billing-filter-search"
              placeholder="프로젝트·고객사·청구번호"
              value={draft.q}
              onChange={(e) => patch("q", e.target.value)}
            />
          </label>
          <Button data-testid="billing-reset" type="button" onClick={reset}>
            초기화
          </Button>
          <Button data-testid="billing-search" type="submit" variant="primary">
            조회
          </Button>
        </form>
        <ErrorBanner
          error={error}
          onRetry={() => setRevision((v) => v + 1)}
          pending={loading}
        />
        <div className={tables.toolbar}>
          <span>총 {page?.total ?? "—"}건</span>
          <Button
            data-testid="billing-download"
            onClick={() => void download()}
          >
            엑셀 다운로드
          </Button>
        </div>
        {loading ? (
          <p role="status">청구 목록 불러오는 중</p>
        ) : (
          !error && (
            <div className={tables.tableScroll}>
              <table className={tables.table}>
                <thead>
                  <tr>
                    {columns.map(([key, label]) => (
                      <th key={key}>
                        {sortable.has(key) ? (
                          <button
                            data-testid={`billing-sort-${key.replaceAll("_", "-")}`}
                            onClick={() => sort(key)}
                          >
                            {label}
                          </button>
                        ) : (
                          label
                        )}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {page?.items.map((row) => (
                    <tr
                      key={row.bill_id ?? `stage-${row.bill_stg_id}`}
                      data-testid={`billing-row-${row.bill_id ?? `stage-${row.bill_stg_id}`}`}
                      onClick={() => {
                        if (row.bill_id) setDetailId(row.bill_id);
                        else register(row);
                      }}
                    >
                      {columns.map(([key]) => (
                        <td
                          key={key}
                          className={
                            key.endsWith("_amt") ? tables.amount : undefined
                          }
                        >
                          {key === "bill_no" ? (
                            <button
                              data-testid={`billing-open-${row.bill_id ?? `stage-${row.bill_stg_id}`}`}
                              className={tables.textButton}
                              disabled={row.virtual_plan && readOnly}
                            >
                              {row.bill_no ?? "청구 예정"}
                            </button>
                          ) : key === "project_name" ? (
                            <Link
                              data-testid={`billing-project-link-${row.bill_id ?? `stage-${row.bill_stg_id}`}`}
                              to={`/UI-01?open=${row.prj_id}`}
                              onClick={(e) => e.stopPropagation()}
                            >
                              {row.project_name}
                            </Link>
                          ) : key === "bill_stts_cd" ? (
                            <BillingStatus row={row} />
                          ) : key === "reminder_stage" ? (
                            row.reminder_stage == null ? (
                              "—"
                            ) : (
                              <Badge
                                tone={
                                  row.reminder_stage >= 2 ? "danger" : "warning"
                                }
                              >
                                {row.reminder_stage}단계
                              </Badge>
                            )
                          ) : key.endsWith("_amt") ? (
                            Number(row[key as keyof BillingRow]).toLocaleString(
                              "ko-KR",
                            )
                          ) : (
                            String(row[key as keyof BillingRow] ?? "—")
                          )}
                        </td>
                      ))}
                    </tr>
                  ))}
                  {!page?.items.length && (
                    <tr>
                      <td className={tables.empty} colSpan={columns.length}>
                        조건에 맞는 청구가 없습니다. 조회 조건을 변경해 보세요.
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>
          )
        )}
        <nav className={styles.period} aria-label="청구 페이지">
          <Button
            data-testid="billing-page-previous"
            disabled={filters.page === 0}
            onClick={() => setFilters((f) => ({ ...f, page: f.page - 1 }))}
          >
            이전 쪽
          </Button>
          <span>{filters.page + 1}쪽</span>
          <Button
            data-testid="billing-page-next"
            disabled={!page || (filters.page + 1) * filters.size >= page.total}
            onClick={() => setFilters((f) => ({ ...f, page: f.page + 1 }))}
          >
            다음 쪽
          </Button>
          <select
            data-testid="billing-page-size"
            aria-label="한 화면 건수"
            value={filters.size}
            onChange={(e) =>
              setFilters((f) => ({
                ...f,
                page: 0,
                size: Number(e.target.value) as 20 | 50 | 100,
              }))
            }
          >
            {[20, 50, 100].map((n) => (
              <option key={n} value={n}>
                {n}건씩
              </option>
            ))}
          </select>
        </nav>
      </div>
      {(["gaps", "overdue"] as const).map((key) => (
        <div
          data-testid={`billing-${key}-panel`}
          role="tabpanel"
          key={key}
          hidden={tab !== key}
        >
          {renderTab?.(key, {
            year,
            readOnly,
            refreshVersion: revision,
            openBilling: setDetailId,
            register,
          })}
        </div>
      ))}
      {detailId != null && (
        <BillingDrawer
          id={detailId}
          readOnly={readOnly}
          refreshVersion={revision}
          onClose={() => setDetailId(null)}
          onOpen={setDetailId}
          onEdit={(detail) => setEditor({ detail })}
          onRefresh={context.refresh}
          renderActions={
            renderActions
              ? (detail, refresh) => renderActions(detail, refresh, context)
              : undefined
          }
          renderReceipts={
            renderReceipts
              ? (detail, refresh) => renderReceipts(detail, refresh, context)
              : undefined
          }
        />
      )}
      {editor && !readOnly && (
        <BillingEditor
          {...editor}
          year={year}
          onClose={() => setEditor(null)}
          onSaved={saved}
          onAttendance={
            onAttendance
              ? (projectId, month) => onAttendance(projectId, month, context)
              : undefined
          }
        />
      )}
      {message && (
        <SuccessToast message={message} onDismiss={() => setMessage("")} />
      )}
    </section>
  );
}
