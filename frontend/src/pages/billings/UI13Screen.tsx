import { useCallback, useEffect, useState, type ReactNode } from "react";
import { useNavigate } from "react-router-dom";
import {
  Button,
  Badge,
  Pagination,
  Skeleton,
  SuccessToast,
} from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { readData } from "../../shared/api";
import { useAdminRead } from "../admin/useAdminResource";
import { ScreenHeading } from "../shared/ScreenHeading";
import { seoulMonth } from "../shared/PeriodBar";
import { getMonthClose } from "../shared/api";
import { listPartnerOptions } from "../partnersops/client";
import { useDeveloperReadOnly } from "../developers/useDeveloperReadOnly";
import {
  listBillings,
  getBillingSummary,
  billingQuery,
  type BillingDetail,
  type BillingRow,
  type BillingFilters,
} from "./client";
import { UI13M1 } from "./UI13M1";
import { UI13DetailPanel, BillingStatus } from "./UI13DetailPanel";
import tables from "../developers/Developers.module.css";
import styles from "./Billing.module.css";

export interface BillingScreenContext {
  year: number;
  readOnly: boolean;
  refresh: () => Promise<unknown>;
  openBilling: (id: number) => void;
  createBilling: (row?: BillingRow) => void;
}
export interface BillingScreenProps {
  name?: string;
  groupName?: string;
  onDirtyChange?: (dirty: boolean) => void;
  toolbar?: ReactNode;
  dialogs?: ReactNode;
  gapPanel?: (context: BillingScreenContext) => ReactNode;
  overduePanel?: (context: BillingScreenContext) => ReactNode;
  detailActions?: (
    detail: BillingDetail,
    context: BillingScreenContext & { closed: boolean },
  ) => ReactNode;
  attendance?: (projectId: number, month: string) => ReactNode;
}
const defaults = (year: number): BillingFilters => ({
  year,
  status: "",
  type: "",
  customerId: "",
  q: "",
  page: 0,
  size: 50,
  sort: "",
});
const customersRead = () => listPartnerOptions("CUST");
const money = (n: number) => n.toLocaleString("ko-KR");
const columns = [
  ["bill_no", "청구번호"],
  ["project_name", "프로젝트"],
  ["customer_name", "고객사"],
  ["contract_type", "계약유형"],
  ["atrb_ym", "귀속월"],
  ["bill_dt", "청구일"],
  ["sply_amt", "공급가액"],
  ["vat_amt", "부가세"],
  ["tot_amt", "합계"],
  ["bill_stts_cd", "상태"],
  ["txinv_no", "세금계산서 번호"],
  ["rcpt_plan_dt", "입금 예정일"],
  ["received_amt", "누적 입금"],
  ["receivable_amt", "미수금"],
  ["reminder_stage", "미수 알림 단계"],
] as const;
export default function UI13Screen({
  name = "청구·입금",
  groupName = "매출",
  onDirtyChange,
  toolbar,
  dialogs,
  gapPanel,
  overduePanel,
  detailActions,
  attendance,
}: BillingScreenProps) {
  const currentYear = Number(seoulMonth().slice(0, 4));
  const [filters, setFilters] = useState<BillingFilters>(() => {
    const saved = Number(sessionStorage.getItem("UI-13.year"));
    return defaults(saved >= 1 && saved <= 9999 ? saved : currentYear);
  });
  const [draft, setDraft] = useState(filters),
    [tab, setTab] = useState("list");
  const [selected, setSelected] = useState<number | null>(null),
    [version, setVersion] = useState(0);
  const [editor, setEditor] = useState<{
    initial?: BillingRow;
    detail?: BillingDetail;
  } | null>(null);
  const [message, setMessage] = useState("");
  const mobile = useDeveloperReadOnly(),
    readOnly = mobile || filters.year < currentYear;
  const list = useAdminRead(
    useCallback(() => listBillings(filters), [filters]),
  );
  const summary = useAdminRead(
    useCallback(() => getBillingSummary(filters.year), [filters.year]),
  );
  const customers = useAdminRead(customersRead),
    navigate = useNavigate();
  useEffect(() => {
    if (readOnly) {
      setEditor(null);
      onDirtyChange?.(false);
    }
  }, [readOnly, onDirtyChange]);
  const refresh = async () => {
    await Promise.all([list.reload(), summary.reload()]);
    setVersion((v) => v + 1);
  };
  const context: BillingScreenContext = {
    year: filters.year,
    readOnly,
    refresh,
    openBilling: setSelected,
    createBilling: (initial) => {
      if (!readOnly) setEditor({ initial });
    },
  };
  const changeYear = (year: number) => {
    if (year < 1 || year > 9999) return;
    sessionStorage.setItem("UI-13.year", String(year));
    setFilters((f) => ({ ...f, year, page: 0 }));
    setDraft((d) => ({ ...d, year }));
    setEditor(null);
  };
  const open = (row: BillingRow) =>
    row.virtual_plan
      ? context.createBilling(row)
      : row.bill_id && setSelected(row.bill_id);
  return (
    <section data-testid="billing-screen">
      <ScreenHeading
        screenId="UI-13"
        name={name}
        groupName={groupName}
        actions={
          <>
            {!readOnly && (
              <Button
                data-testid="billing-create"
                variant="primary"
                onClick={() => setEditor({})}
              >
                청구 등록
              </Button>
            )}
            {toolbar}
          </>
        }
      />
      <div className={styles.actions}>
        <Button
          data-testid="billing-year-previous"
          onClick={() => changeYear(filters.year - 1)}
        >
          이전 연도
        </Button>
        <strong>{filters.year}년</strong>
        <Button
          data-testid="billing-year-next"
          onClick={() => changeYear(filters.year + 1)}
        >
          다음 연도
        </Button>
      </div>
      {readOnly && (
        <p>
          <Badge tone="locked">
            {mobile
              ? "휴대폰에서는 조회와 다운로드만 가능합니다."
              : "과거 연도는 조회와 다운로드만 가능합니다."}
          </Badge>
        </p>
      )}
      <nav role="tablist" className={styles.tabs} aria-label="청구·입금 탭">
        {[
          ["list", "청구 목록"],
          ["gaps", "청구 누락 점검"],
          ["overdue", "미수 알림"],
        ].map(([key, label]) => (
          <Button
            key={key}
            data-testid={`billing-tab-${key}`}
            role="tab"
            aria-selected={tab === key}
            onClick={() => setTab(key)}
          >
            {label}
          </Button>
        ))}
      </nav>
      <ErrorBanner
        error={summary.error}
        onRetry={() => void summary.reload().catch(() => {})}
      />
      {summary.loading && <Skeleton label="합계 불러오는 중" />}
      {summary.data && (
        <div className={styles.summary}>
          {[
            ["연간 청구(공급가)", summary.data.annual_confirmed_supply_amt],
            [
              "이번 달 청구(공급가)",
              summary.data.current_month_confirmed_supply_amt,
            ],
            ["미수", summary.data.confirmed_receivable_amt],
            ["연체", summary.data.overdue_amt],
            ["예정(공급가)", summary.data.planned_supply_amt],
            ["작성(공급가)", summary.data.draft_supply_amt],
          ].map(([label, amount]) => (
            <div key={label}>
              <span>{label}</span>
              <strong>{money(Number(amount))}원</strong>
            </div>
          ))}
        </div>
      )}
      <section
        role="tabpanel"
        hidden={tab !== "list"}
        data-testid="billing-list-panel"
      >
        <form
          className={tables.search}
          onSubmit={(e) => {
            e.preventDefault();
            setFilters({ ...draft, page: 0 });
          }}
        >
          <label>
            상태
            <select
              data-testid="billing-filter-status"
              aria-label="상태"
              value={draft.status}
              onChange={(e) =>
                setDraft((d) => ({ ...d, status: e.target.value }))
              }
            >
              <option value="">전체</option>
              <option value="PLAN">예정</option>
              <option value="DRAFT">작성</option>
              <option value="CONFIRM">확정</option>
              <option value="CANCEL">취소</option>
            </select>
          </label>
          <label>
            유형
            <select
              data-testid="billing-filter-type"
              aria-label="유형"
              value={draft.type}
              onChange={(e) =>
                setDraft((d) => ({ ...d, type: e.target.value }))
              }
            >
              <option value="">전체</option>
              <option value="TK">턴키</option>
              <option value="MO">월단위</option>
              <option value="DP">파견</option>
            </select>
          </label>
          <label>
            고객사
            <select
              data-testid="billing-filter-customer"
              aria-label="고객사"
              value={draft.customerId}
              onChange={(e) =>
                setDraft((d) => ({ ...d, customerId: e.target.value }))
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
              data-testid="billing-filter-query"
              value={draft.q}
              onChange={(e) => setDraft((d) => ({ ...d, q: e.target.value }))}
            />
          </label>
          <Button data-testid="billing-search" type="submit" variant="primary">
            조회
          </Button>
          <Button
            data-testid="billing-reset"
            type="button"
            onClick={() => {
              const f = defaults(filters.year);
              setDraft(f);
              setFilters(f);
            }}
          >
            초기화
          </Button>
        </form>
        <ErrorBanner
          error={list.error ?? customers.error}
          onRetry={() => void list.reload().catch(() => {})}
        />
        <div className={styles.actions}>
          <span>총 {list.data?.total ?? "—"}건</span>
          <a
            data-testid="billing-export"
            className={tables.linkButton}
            href={`/api/billings/export?${billingQuery(filters, true)}`}
          >
            엑셀 다운로드
          </a>
        </div>
        {list.loading && <Skeleton />}
        {!list.loading && !list.error && list.data && (
          <>
            <div className={tables.tableScroll}>
              <table className={tables.table}>
                <thead>
                  <tr>
                    {columns.map(([key, label]) => (
                      <th key={key}>
                        <button
                          data-testid={`billing-sort-${key}`}
                          type="button"
                          onClick={() =>
                            setFilters((f) => ({
                              ...f,
                              page: 0,
                              sort: `${key},${f.sort === `${key},asc` ? "desc" : "asc"}`,
                            }))
                          }
                        >
                          {label}
                        </button>
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {list.data.items.length === 0 && (
                    <tr>
                      <td colSpan={columns.length}>
                        조건에 맞는 결과가 없습니다
                      </td>
                    </tr>
                  )}
                  {list.data.items.map((row) => (
                    <tr
                      key={
                        row.bill_id
                          ? `bill-${row.bill_id}`
                          : `stage-${row.bill_stg_id}`
                      }
                      className={
                        row.bill_id === selected ? tables.highlight : ""
                      }
                      data-testid={`billing-row-${row.bill_id ? `bill-${row.bill_id}` : `stage-${row.bill_stg_id}`}`}
                      onClick={() => open(row)}
                    >
                      {columns.map(([key]) => (
                        <td
                          key={key}
                          className={key.endsWith("amt") ? tables.amount : ""}
                        >
                          {key === "bill_no" ? (
                            <Button
                              data-testid={`billing-open-${row.bill_id ?? `stage-${row.bill_stg_id}`}`}
                              variant="text"
                              disabled={row.virtual_plan && readOnly}
                              onClick={(e) => {
                                e.stopPropagation();
                                open(row);
                              }}
                            >
                              {row.bill_no ?? "청구 예정"}
                            </Button>
                          ) : key === "project_name" ? (
                            <Button
                              data-testid={`billing-project-${row.bill_id ?? `stage-${row.bill_stg_id}`}`}
                              variant="text"
                              onClick={(e) => {
                                e.stopPropagation();
                                navigate(`/UI-01?open=${row.prj_id}`);
                              }}
                            >
                              {row.project_name}
                            </Button>
                          ) : key === "bill_stts_cd" ? (
                            <BillingStatus status={row.bill_stts_cd} />
                          ) : key === "reminder_stage" ? (
                            row.reminder_stage === null ? (
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
                          ) : typeof row[key] === "number" ? (
                            money(row[key] as number)
                          ) : (
                            (row[key] ?? "—")
                          )}
                        </td>
                      ))}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <Pagination
              page={filters.page + 1}
              size={filters.size}
              total={list.data.total}
              onPage={(page) => setFilters((f) => ({ ...f, page: page - 1 }))}
              onSize={(size) => setFilters((f) => ({ ...f, size, page: 0 }))}
            />
          </>
        )}
      </section>
      <section
        role="tabpanel"
        hidden={tab !== "gaps"}
        data-testid="billing-gaps-panel"
      >
        {gapPanel ? (
          gapPanel(context)
        ) : (
          <BillingRelatedPanel
            kind="gaps"
            active={tab === "gaps"}
            context={context}
          />
        )}
      </section>
      <section
        role="tabpanel"
        hidden={tab !== "overdue"}
        data-testid="billing-overdue-panel"
      >
        {overduePanel ? (
          overduePanel(context)
        ) : (
          <BillingRelatedPanel
            kind="overdue"
            active={tab === "overdue"}
            context={context}
          />
        )}
      </section>
      {selected !== null && (
        <UI13DetailPanel
          key={selected}
          id={selected}
          version={version}
          readOnly={readOnly}
          onClose={() => setSelected(null)}
          onOpen={setSelected}
          onEdit={(detail) => setEditor({ detail })}
          actions={
            detailActions
              ? (detail, guard) =>
                  detailActions(detail, {
                    ...context,
                    readOnly: guard.readOnly,
                    closed: guard.closed,
                  })
              : undefined
          }
        />
      )}
      {editor && !readOnly && (
        <UI13M1
          initial={editor.initial}
          detail={editor.detail}
          attendance={attendance}
          onCancel={() => {
            setEditor(null);
            onDirtyChange?.(false);
          }}
          onDirtyChange={onDirtyChange}
          onSaved={async (saved) => {
            await refresh();
            setSelected(saved.billing.bill_id);
            setMessage("청구를 저장했습니다.");
          }}
        />
      )}
      {dialogs}
      {message && (
        <SuccessToast message={message} onDismiss={() => setMessage("")} />
      )}
    </section>
  );
}

interface RelatedRow {
  project_id?: number;
  project_name?: string;
  month?: string;
  basis_supply_amt?: number;
  confirmed_supply_amt?: number;
  difference_amt?: number;
  judgment?: string;
  explanation?: string;
  attendance_missing?: boolean;
  billing_ids?: number[];
  billing?: BillingRow;
  overdue_days?: number | null;
  stage?: number | null;
  recipient_roles?: string[];
  sent_history?: { sent_at: string }[];
  next_notification_date?: string | null;
  due_status?: string;
}
function BillingRelatedPanel({
  kind,
  active,
  context,
}: {
  kind: "gaps" | "overdue";
  active: boolean;
  context: BillingScreenContext;
}) {
  const [filter, setFilter] = useState({
    month: seoulMonth(),
    judgment: "",
    stage: "",
    overDays: "",
    customerId: "",
    q: "",
    page: 0,
    size: 50 as 20 | 50 | 100,
    sort: "",
  });
  const closes = useAdminRead(
    useCallback(
      () =>
        kind === "gaps"
          ? getMonthClose("0001-01", "9999-12")
          : Promise.resolve([]),
      [kind],
    ),
  );
  const [initialized, setInitialized] = useState(false);
  useEffect(() => {
    if (!initialized && closes.data) {
      const last = closes.data
        .filter((c) => c.status === "CLOSED")
        .map((c) => c.month)
        .sort()
        .at(-1);
      if (last) setFilter((f) => ({ ...f, month: last }));
      setInitialized(true);
    }
  }, [closes.data, initialized]);
  const query =
    kind === "gaps"
      ? {
          month: filter.month,
          judgment: filter.judgment,
          q: filter.q,
          page: filter.page,
          size: filter.size,
          sort: filter.sort,
        }
      : {
          year: context.year,
          stage: filter.stage,
          overDays: filter.overDays,
          customerId: filter.customerId,
          q: filter.q,
          page: filter.page,
          size: filter.size,
          sort: filter.sort,
        };
  const queryString = billingQuery(query);
  const read = useAdminRead(
    useCallback(
      () =>
        active
          ? readData<{
              items: RelatedRow[];
              total: number;
              total_difference_amt?: number;
            }>(`/api/billings/${kind}?${queryString}`)
          : Promise.resolve(null),
      [active, kind, queryString],
    ),
  );
  const names =
    kind === "gaps"
      ? [
          "프로젝트",
          "귀속월",
          "투입 기준 금액",
          "청구 공급가",
          "차이",
          "판정",
          "설명",
          "청구 고치기",
        ]
      : [
          "고객사",
          "프로젝트",
          "청구번호",
          "미수금",
          "입금 예정일",
          "지난 일수",
          "단계",
          "수신 역할",
          "보낸 알림",
          "다음 알림",
        ];
  return (
    <>
      <div className={tables.search}>
        {kind === "gaps" ? (
          <>
            <label>
              월
              <input
                data-testid="billing-gaps-month"
                type="month"
                value={filter.month}
                onChange={(e) =>
                  setFilter((f) => ({ ...f, month: e.target.value, page: 0 }))
                }
              />
            </label>
            <label>
              판정
              <select
                data-testid="billing-gaps-judgment"
                value={filter.judgment}
                onChange={(e) =>
                  setFilter((f) => ({
                    ...f,
                    judgment: e.target.value,
                    page: 0,
                  }))
                }
              >
                <option value="">전체</option>
                {[
                  "맞음",
                  "적게 청구",
                  "많이 청구",
                  "청구 없음",
                  "근태 미입력",
                ].map((v) => (
                  <option key={v}>{v}</option>
                ))}
              </select>
            </label>
          </>
        ) : (
          <>
            <label>
              단계
              <select
                data-testid="billing-overdue-stage"
                value={filter.stage}
                onChange={(e) =>
                  setFilter((f) => ({ ...f, stage: e.target.value, page: 0 }))
                }
              >
                <option value="">전체</option>
                {[0, 1, 2, 3].map((n) => (
                  <option key={n} value={n}>
                    {n}단계
                  </option>
                ))}
              </select>
            </label>
            <label>
              초과 일수
              <select
                data-testid="billing-overdue-days"
                value={filter.overDays}
                onChange={(e) =>
                  setFilter((f) => ({
                    ...f,
                    overDays: e.target.value,
                    page: 0,
                  }))
                }
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
        )}
        <label>
          검색
          <input
            data-testid={`billing-${kind}-query`}
            value={filter.q}
            onChange={(e) =>
              setFilter((f) => ({ ...f, q: e.target.value, page: 0 }))
            }
          />
        </label>
        <Button
          data-testid={`billing-${kind}-search`}
          onClick={() => void read.reload().catch(() => {})}
        >
          조회
        </Button>
        <a
          data-testid={`billing-${kind}-export`}
          className={tables.linkButton}
          href={`/api/billings/${kind}/export?${billingQuery(query, true)}`}
        >
          엑셀 다운로드
        </a>
      </div>
      <ErrorBanner
        error={read.error ?? closes.error}
        onRetry={() => void read.reload().catch(() => {})}
      />
      {read.loading && <Skeleton />}
      {read.data && (
        <>
          {read.data.total_difference_amt !== undefined && (
            <p>총 차이 {money(read.data.total_difference_amt)}원</p>
          )}
          <div className={tables.tableScroll}>
            <table className={tables.table}>
              <thead>
                <tr>
                  {names.map((n) => (
                    <th key={n}>{n}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {read.data.items.length === 0 && (
                  <tr>
                    <td colSpan={names.length}>조건에 맞는 결과가 없습니다</td>
                  </tr>
                )}
                {read.data.items.map((r, i) => (
                  <tr
                    key={r.billing?.bill_id ?? `${r.project_id}-${r.month}`}
                    data-testid={`billing-${kind}-row-${i}`}
                  >
                    {kind === "gaps" ? (
                      <>
                        <td>{r.project_name}</td>
                        <td>{r.month}</td>
                        {[
                          r.basis_supply_amt,
                          r.confirmed_supply_amt,
                          r.difference_amt,
                        ].map((n, j) => (
                          <td key={j} className={tables.amount}>
                            {n == null ? "—" : money(n)}
                          </td>
                        ))}
                        <td>
                          <Badge
                            tone={
                              r.attendance_missing || r.judgment !== "맞음"
                                ? "warning"
                                : "success"
                            }
                          >
                            {r.judgment}
                          </Badge>
                        </td>
                        <td>{r.explanation}</td>
                        <td>
                          {r.billing_ids?.map((id) => (
                            <Button
                              key={id}
                              data-testid={`billing-gap-open-${id}`}
                              onClick={() => context.openBilling(id)}
                            >
                              청구 고치기
                            </Button>
                          ))}
                        </td>
                      </>
                    ) : (
                      <>
                        <td>{r.billing?.customer_name}</td>
                        <td>{r.billing?.project_name}</td>
                        <td>
                          <Button
                            data-testid={`billing-overdue-open-${r.billing?.bill_id}`}
                            onClick={() =>
                              r.billing?.bill_id &&
                              context.openBilling(r.billing.bill_id)
                            }
                          >
                            {r.billing?.bill_no}
                          </Button>
                        </td>
                        <td className={tables.amount}>
                          {money(r.billing?.receivable_amt ?? 0)}
                        </td>
                        <td>{r.billing?.rcpt_plan_dt ?? "예정일 미입력"}</td>
                        <td>{r.overdue_days ?? "—"}</td>
                        <td>
                          {r.stage == null ? (
                            "—"
                          ) : (
                            <Badge tone={r.stage >= 2 ? "danger" : "warning"}>
                              {r.stage}단계
                            </Badge>
                          )}
                        </td>
                        <td>{r.recipient_roles?.join(", ")}</td>
                        <td>
                          {r.sent_history?.length
                            ? r.sent_history.map((s) => s.sent_at).join(", ")
                            : "보낸 알림 없음"}
                        </td>
                        <td>{r.next_notification_date ?? "—"}</td>
                      </>
                    )}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <Pagination
            page={filter.page + 1}
            size={filter.size}
            total={read.data.total}
            onPage={(page) => setFilter((f) => ({ ...f, page: page - 1 }))}
            onSize={(size) => setFilter((f) => ({ ...f, size, page: 0 }))}
          />
        </>
      )}
      {kind === "overdue" && (
        <p>알림 발송은 사용자·권한 기능 구축 후 사용할 수 있습니다.</p>
      )}
    </>
  );
}
