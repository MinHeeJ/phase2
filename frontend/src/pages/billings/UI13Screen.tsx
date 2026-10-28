import { useCallback, useState, type ReactNode } from "react";
import { Link } from "react-router-dom";
import {
  Button,
  Pagination,
  Skeleton,
  SuccessToast,
} from "../../shared/Controls";
import { Can } from "../../shared/Can";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { useAdminRead } from "../admin/useAdminResource";
import { useDeveloperReadOnly } from "../developers/useDeveloperReadOnly";
import { listPartnerOptions } from "../partnersops/client";
import { ScreenHeading } from "../shared/ScreenHeading";
import { useCodes } from "../shared/useCodes";
import { seoulMonth } from "../shared/PeriodBar";
import {
  billingDefaults,
  getBillingSummary,
  listBillings,
  type BillingDetail,
  type BillingFilters,
  type BillingRow,
} from "./client";
import { BillingDrawer, BillingStatus } from "./BillingDrawer";
import { BillingEditor } from "./BillingEditor";
import { BillingGapPanel, BillingOverduePanel } from "./BillingReviewPanels";
import tables from "../developers/Developers.module.css";
import styles from "./Billings.module.css";

const customersRead = () => listPartnerOptions("CUST");
export type BillingTab = "list" | "gaps" | "overdue";
export interface UI13ScreenProps {
  name?: string;
  groupName?: string;
  onDirtyChange?: (dirty: boolean) => void;
  onAttendance?: (projectId?: number, month?: string) => void;
  onExport?: (tab: BillingTab, filters: BillingFilters) => void;
  detailActions?: (
    detail: BillingDetail,
    refresh: () => Promise<unknown>,
  ) => ReactNode;
  dialogs?: ReactNode;
  refreshVersion?: number;
}
export default function UI13Screen({
  name = "청구·입금",
  groupName = "매출",
  onDirtyChange,
  onAttendance,
  onExport,
  detailActions,
  dialogs,
  refreshVersion = 0,
}: UI13ScreenProps) {
  const currentYear = Number(seoulMonth().slice(0, 4));
  const [year, setYear] = useState(currentYear);
  const mobile = useDeveloperReadOnly(),
    readOnly = mobile || year < currentYear;
  const [tab, setTab] = useState<BillingTab>("list");
  const [visited, setVisited] = useState<Set<BillingTab>>(
    () => new Set(["list"]),
  );
  const [draft, setDraft] = useState(billingDefaults(year)),
    [filters, setFilters] = useState(billingDefaults(year));
  const [selected, setSelected] = useState<number | null>(null);
  const [editor, setEditor] = useState<{
    existing?: BillingDetail;
    initial?: { projectId?: number; stageId?: number; month?: string };
  } | null>(null);
  const [message, setMessage] = useState("");
  const list = useAdminRead(
    useCallback(
      () => listBillings({ ...filters, year }),
      [filters, year, refreshVersion],
    ),
  );
  const summary = useAdminRead(
    useCallback(() => getBillingSummary(year), [year, refreshVersion]),
  );
  const customers = useAdminRead(customersRead);
  const statuses = useCodes("BILL_STATUS"),
    types = useCodes("CONTRACT_TYPE");
  const refresh = useCallback(async () => {
    await Promise.all([list.reload(), summary.reload()]);
  }, [list.reload, summary.reload]);
  const open = (row: BillingRow) => {
    if (row.virtual_plan) {
      if (!readOnly)
        setEditor({
          initial: {
            projectId: row.prj_id,
            stageId: row.bill_stg_id ?? undefined,
          },
        });
    } else setSelected(row.bill_id);
  };
  const sort = (field: string) =>
    setFilters((f) => ({
      ...f,
      page: 0,
      sort: `${field},${f.sort === `${field},asc` ? "desc" : "asc"}`,
    }));
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
    "receivable_amt",
    "rcpt_plan_dt",
    "bill_stts_cd",
  ]);
  return (
    <section
      className={styles.workspace}
      data-testid="billing-workspace"
      data-screen-id="SCR-UI-13"
    >
      <ScreenHeading
        screenId="UI-13"
        name={name}
        groupName={groupName}
        actions={
          !readOnly && (
            <div className={tables.writeActions}>
              <Can perm="UI-13.download">
                <a
                  data-testid="billing-template"
                  className={tables.linkButton}
                  href="/api/uploads/templates/B.3"
                >
                  양식 내려받기
                </a>
              </Can>
              <Can perm="UI-13.upload">
                <Button
                  data-testid="billing-upload"
                  disabled={!onAttendance}
                  onClick={() => onAttendance?.()}
                >
                  엑셀 업로드
                </Button>
              </Can>
              <Can perm="UI-13.create">
                <Button
                  data-testid="billing-create"
                  variant="primary"
                  onClick={() => setEditor({})}
                >
                  ＋ 청구 등록
                </Button>
              </Can>
            </div>
          )
        }
      />
      <div className={styles.period}>
        <Button
          data-testid="billing-year-previous"
          aria-label="이전 연도"
          disabled={year <= 1}
          onClick={() => {
            setYear((v) => v - 1);
            setSelected(null);
            setEditor(null);
          }}
        >
          ‹
        </Button>
        <strong>{year}년</strong>
        <Button
          data-testid="billing-year-next"
          aria-label="다음 연도"
          disabled={year >= 9999}
          onClick={() => {
            setYear((v) => v + 1);
            setSelected(null);
            setEditor(null);
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
        <span>
          {year < currentYear
            ? "과거 연도는 조회·다운로드만 가능합니다."
            : "그 해에 청구한 건 · 확정 공급가 기준"}
        </span>
      </div>
      <div role="tablist" aria-label="청구 조회" className={styles.tabs}>
        {(
          [
            ["list", "청구 목록"],
            ["gaps", "청구 누락 점검"],
            ["overdue", "미수 알림"],
          ] as const
        ).map(([value, label]) => (
          <Button
            key={value}
            data-testid={`billing-tab-${value}`}
            role="tab"
            aria-selected={tab === value}
            onClick={() => {
              setTab(value);
              setVisited((v) => new Set([...v, value]));
            }}
          >
            {label}
          </Button>
        ))}
      </div>
      {summary.loading && <Skeleton label="청구 요약 불러오는 중" />}
      <ErrorBanner
        error={summary.error}
        onRetry={() => void summary.reload().catch(() => {})}
      />
      {summary.data && !summary.error && (
        <>
          <div className={styles.summary}>
            {[
              [
                `${year}년 청구`,
                summary.data.annual_confirmed_supply_amt,
                "공급가 · 부가세 제외",
              ],
              [
                "이번 달 청구",
                summary.data.current_month_confirmed_supply_amt,
                "확정 공급가",
              ],
              ["미수금", summary.data.confirmed_receivable_amt, "부가세 포함"],
              ["연체", summary.data.overdue_amt, "입금 예정일 지남"],
            ].map(([label, amount, help], index) => (
              <article data-testid={`billing-summary-${index}`} key={label}>
                <span>{label}</span>
                <strong>{Number(amount).toLocaleString()}원</strong>
                <span>{help}</span>
              </article>
            ))}
          </div>
          <p>
            예정 공급가 {summary.data.planned_supply_amt.toLocaleString()}원 ·
            작성 공급가 {summary.data.draft_supply_amt.toLocaleString()}원
            (매출·미수 제외)
          </p>
        </>
      )}
      <Can perm="UI-13.download">
        <div className={tables.actions}>
          <Button
            data-testid="billing-export"
            disabled={!onExport}
            onClick={() => onExport?.(tab, { ...filters, year })}
          >
            엑셀 다운로드
          </Button>
        </div>
      </Can>
      <div
        role="tabpanel"
        data-testid="billing-list-panel"
        hidden={tab !== "list"}
      >
        <form
          className={tables.search}
          data-testid="billing-list-filters"
          onSubmit={(e) => {
            e.preventDefault();
            setFilters({ ...draft, page: 0 });
          }}
        >
          <label>
            상태
            <select
              data-testid="billing-status-filter"
              value={draft.status}
              onChange={(e) =>
                setDraft((v) => ({ ...v, status: e.target.value }))
              }
            >
              <option value="">전체</option>
              {statuses.codes.map((c) => (
                <option
                  key={c.code}
                  value={c.code}
                  data-testid={`billing-status-option-${c.code.toLowerCase().replace(/[^a-z0-9]+/g, "-")}`}
                >
                  {c.name}
                </option>
              ))}
            </select>
          </label>
          <label>
            유형
            <select
              data-testid="billing-type-filter"
              value={draft.type}
              onChange={(e) =>
                setDraft((v) => ({ ...v, type: e.target.value }))
              }
            >
              <option value="">전체</option>
              {types.codes
                .filter((c) => c.code !== "IN")
                .map((c) => (
                  <option key={c.code} value={c.code}>
                    {c.name}
                  </option>
                ))}
            </select>
          </label>
          <label>
            고객사
            <select
              data-testid="billing-customer-filter"
              value={draft.customerId}
              onChange={(e) =>
                setDraft((v) => ({ ...v, customerId: e.target.value }))
              }
            >
              <option value="">전체</option>
              {customers.data?.map((c) => (
                <option key={c.id} value={c.id}>
                  {c.name}
                </option>
              ))}
            </select>
          </label>
          <label>
            검색
            <input
              data-testid="billing-search"
              value={draft.q}
              maxLength={200}
              onChange={(e) => setDraft((v) => ({ ...v, q: e.target.value }))}
            />
          </label>
          <Button
            data-testid="billing-reset"
            type="button"
            onClick={() => {
              setDraft(billingDefaults(year));
              setFilters(billingDefaults(year));
            }}
          >
            초기화
          </Button>
          <Button data-testid="billing-query" type="submit" variant="primary">
            조회
          </Button>
        </form>
        <ErrorBanner
          error={customers.error}
          onRetry={() => void customers.reload().catch(() => {})}
        />
        <ErrorBanner error={statuses.error} onRetry={statuses.retry} />
        <ErrorBanner error={types.error} onRetry={types.retry} />
        {list.loading && <Skeleton label="청구 목록 불러오는 중" />}
        <ErrorBanner
          error={list.error}
          onRetry={() => void list.reload().catch(() => {})}
        />
        {list.data && !list.error && (
          <>
            <div className={tables.toolbar}>
              <p>
                총 <strong>{list.data.total}</strong>건
              </p>
            </div>
            <div className={tables.tableScroll}>
              <table className={tables.table} aria-label="청구 목록">
                <thead>
                  <tr>
                    {columns.map(([key, label]) => (
                      <th key={key}>
                        {sortable.has(key) ? (
                          <button
                            type="button"
                            data-testid={`billing-sort-${key}`}
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
                  {list.data.items.map((row) => (
                    <tr
                      key={
                        row.bill_id === null
                          ? `stage-${row.bill_stg_id}`
                          : `bill-${row.bill_id}`
                      }
                      data-testid={
                        row.bill_id === null
                          ? `billing-stage-${row.bill_stg_id}`
                          : `billing-row-${row.bill_id}`
                      }
                    >
                      <td>
                        <Button
                          data-testid={`billing-open-${row.bill_id ?? `stage-${row.bill_stg_id}`}`}
                          variant="text"
                          disabled={row.virtual_plan && readOnly}
                          onClick={() => open(row)}
                        >
                          {row.bill_no ?? "청구 예정"}
                        </Button>
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
                      <td>{row.bill_dt ?? "미입력"}</td>
                      {[
                        row.sply_amt,
                        row.vat_amt,
                        row.tot_amt,
                        row.received_amt,
                        row.receivable_amt,
                      ].map((amount, index) => (
                        <td key={index} className={tables.amount}>
                          {amount.toLocaleString()}
                        </td>
                      ))}
                      <td>{row.rcpt_plan_dt ?? "미입력"}</td>
                      <td>
                        <BillingStatus row={row} />
                      </td>
                      <td>{row.txinv_no ?? "미입력"}</td>
                      <td>
                        {row.reminder_stage === null
                          ? "—"
                          : `${row.reminder_stage}단계`}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {list.data.items.length === 0 && (
                <div className={tables.empty}>
                  <h2>청구 내역이 없습니다.</h2>
                  <p>조회 조건을 바꾸거나 청구를 등록하세요.</p>
                </div>
              )}
            </div>
            <Pagination
              page={filters.page + 1}
              size={filters.size}
              total={list.data.total}
              onPage={(page) => setFilters((v) => ({ ...v, page: page - 1 }))}
              onSize={(size) => setFilters((v) => ({ ...v, size, page: 0 }))}
            />
          </>
        )}
      </div>
      {visited.has("gaps") && (
        <div
          role="tabpanel"
          hidden={tab !== "gaps"}
          data-testid="billing-gaps-tabpanel"
        >
          <BillingGapPanel
            readOnly={readOnly}
            onOpen={setSelected}
            onRegister={(projectId, month) =>
              setEditor({ initial: { projectId, month } })
            }
          />
        </div>
      )}
      {visited.has("overdue") && (
        <div
          role="tabpanel"
          hidden={tab !== "overdue"}
          data-testid="billing-overdue-tabpanel"
        >
          <BillingOverduePanel
            year={year}
            onOpen={setSelected}
            customers={customers.data ?? []}
          />
        </div>
      )}
      {selected !== null && (
        <BillingDrawer
          key={selected}
          id={selected}
          readOnly={readOnly}
          onClose={() => setSelected(null)}
          onOpen={setSelected}
          onEdit={(existing) => setEditor({ existing })}
          onChanged={refresh}
          detailActions={detailActions}
        />
      )}
      {editor && !readOnly && (
        <BillingEditor
          {...editor}
          onClose={() => setEditor(null)}
          onDirtyChange={onDirtyChange}
          onAttendance={onAttendance}
          onSaved={async (detail) => {
            setSelected(detail.billing.bill_id);
            await refresh();
            setMessage("저장했습니다.");
          }}
        />
      )}
      {message && (
        <SuccessToast message={message} onDismiss={() => setMessage("")} />
      )}
      {dialogs}
    </section>
  );
}
