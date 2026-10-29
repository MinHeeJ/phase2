import { useCallback, useState, type ReactNode } from "react";
import { useNavigate } from "react-router-dom";
import {
  Badge,
  Button,
  Pagination,
  Skeleton,
  SuccessToast,
} from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { useAdminRead } from "../admin/useAdminResource";
import { failure } from "../admin/client";
import { ScreenHeading } from "../shared/ScreenHeading";
import { seoulMonth } from "../shared/PeriodBar";
import { useDeveloperReadOnly } from "../developers/useDeveloperReadOnly";
import { downloadUploadFile } from "../uploadsops/client";
import { listPartnerOptions } from "../partnersops/client";
import {
  billingDefaults,
  billingQuery,
  getBillingSummary,
  listBillings,
  type BillingDetail,
  type BillingRow,
} from "./client";
import { BillingStatus, UI13DetailPanel } from "./UI13DetailPanel";
import { UI13M1 } from "./UI13M1";
import tables from "../developers/Developers.module.css";
import styles from "./Billings.module.css";

export interface BillingScreenContext {
  year: number;
  readOnly: boolean;
  refresh: () => Promise<unknown>;
  openBilling: (id: number) => void;
  createBilling: (projectId?: number, month?: string, stageId?: number) => void;
}
export interface BillingScreenProps {
  name?: string;
  groupName?: string;
  onDirtyChange?: (dirty: boolean) => void;
  initialBillingId?: number;
  toolbar?: ReactNode;
  dialogs?: ReactNode;
  gapsPanel?: (context: BillingScreenContext) => ReactNode;
  overduePanel?: (context: BillingScreenContext) => ReactNode;
  detailActions?: (
    detail: BillingDetail,
    reload: () => Promise<unknown>,
  ) => ReactNode;
  onAttendance?: (projectId: number, month: string) => void;
}
const customers = () => listPartnerOptions("CUST");
export default function UI13Screen({
  name = "청구·입금",
  groupName = "매출",
  onDirtyChange,
  initialBillingId,
  toolbar,
  dialogs,
  gapsPanel,
  overduePanel,
  detailActions,
  onAttendance,
}: BillingScreenProps) {
  const navigate = useNavigate();
  const currentYear = Number(seoulMonth().slice(0, 4));
  const [filters, setFilters] = useState(() =>
    billingDefaults(
      Number(sessionStorage.getItem("UI-13.year")) || currentYear,
    ),
  );
  const [draft, setDraft] = useState(filters),
    [tab, setTab] = useState("list");
  const [selected, setSelected] = useState<number | null>(
    initialBillingId ?? null,
  );
  const [editor, setEditor] = useState<{
    existing?: BillingDetail;
    projectId?: number;
    month?: string;
    stageId?: number;
  } | null>(null);
  const [version, setVersion] = useState(0),
    [message, setMessage] = useState("");
  const [downloadError, setDownloadError] = useState<ReturnType<
    typeof failure
  > | null>(null);
  const mobile = useDeveloperReadOnly(),
    readOnly = mobile || filters.year < currentYear;
  const list = useAdminRead(
    useCallback(() => listBillings(filters), [filters]),
  );
  const summary = useAdminRead(
    useCallback(() => getBillingSummary(filters.year), [filters.year]),
  );
  const customerOptions = useAdminRead(customers);
  const refresh = async () => {
    await Promise.all([list.reload(), summary.reload()]);
    setVersion((v) => v + 1);
  };
  const context: BillingScreenContext = {
    year: filters.year,
    readOnly,
    refresh,
    openBilling: setSelected,
    createBilling: (projectId, month, stageId) => {
      if (!readOnly) setEditor({ projectId, month, stageId });
    },
  };
  const year = (next: number) => {
    if (next < 1 || next > 9999) return;
    sessionStorage.setItem("UI-13.year", String(next));
    setFilters((f) => ({ ...f, year: next, page: 0 }));
    setDraft((d) => ({ ...d, year: next }));
  };
  const reset = () => {
    const f = billingDefaults(filters.year);
    setFilters(f);
    setDraft(f);
  };
  const open = (row: BillingRow) =>
    row.bill_id == null
      ? context.createBilling(
          row.prj_id,
          undefined,
          row.bill_stg_id ?? undefined,
        )
      : setSelected(row.bill_id);
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
  ];
  return (
    <section data-testid="billings-screen" data-screen-id="SCR-UI-13">
      <ScreenHeading
        screenId="UI-13"
        name={name}
        groupName={groupName}
        actions={
          !readOnly && (
            <>
              {toolbar}
              <Button
                data-testid="billing-add"
                variant="primary"
                onClick={() => setEditor({})}
              >
                + 청구 등록
              </Button>
            </>
          )
        }
      />
      <div className={styles.period}>
        <Button
          data-testid="billing-year-prev"
          onClick={() => year(filters.year - 1)}
        >
          이전 해
        </Button>
        <strong>{filters.year}년</strong>
        <Button
          data-testid="billing-year-next"
          onClick={() => year(filters.year + 1)}
        >
          다음 해
        </Button>
        {readOnly && <Badge tone="locked">읽기 전용</Badge>}
      </div>
      <div role="tablist" className={styles.tabs}>
        <Button
          role="tab"
          data-testid="billing-list-tab"
          aria-selected={tab === "list"}
          onClick={() => setTab("list")}
        >
          청구 목록
        </Button>
        {gapsPanel && (
          <Button
            role="tab"
            data-testid="billing-gaps-tab"
            aria-selected={tab === "gaps"}
            onClick={() => setTab("gaps")}
          >
            청구 누락 점검
          </Button>
        )}
        {overduePanel && (
          <Button
            role="tab"
            data-testid="billing-overdue-tab"
            aria-selected={tab === "overdue"}
            onClick={() => setTab("overdue")}
          >
            미수 알림
          </Button>
        )}
      </div>
      {message && (
        <SuccessToast message={message} onDismiss={() => setMessage("")} />
      )}
      <ErrorBanner
        error={summary.error}
        onRetry={() => void summary.reload().catch(() => {})}
      />
      {summary.data && (
        <div className={styles.summary}>
          {[
            [
              `${filters.year}년 청구`,
              summary.data.annual_confirmed_supply_amt,
              "확정 공급가",
            ],
            [
              "이번 달 청구",
              summary.data.current_month_confirmed_supply_amt,
              "확정 공급가",
            ],
            ["미수금", summary.data.confirmed_receivable_amt, "부가세 포함"],
            ["연체", summary.data.overdue_amt, "입금 예정일 지남"],
          ].map(([label, amount, hint]) => (
            <article
              key={label}
              data-testid={`billing-summary-card-${[`${filters.year}년 청구`, "이번 달 청구", "미수금", "연체"].indexOf(label as string)}`}
            >
              <span>{label}</span>
              <strong>
                {typeof amount === "number"
                  ? amount.toLocaleString("ko-KR")
                  : "–"}{" "}
                원
              </strong>
              <small>{hint}</small>
            </article>
          ))}
        </div>
      )}
      {summary.data && (
        <p>
          예정 공급가 {summary.data.planned_supply_amt?.toLocaleString("ko-KR")}
          원 · 작성 공급가{" "}
          {summary.data.draft_supply_amt?.toLocaleString("ko-KR")}원 (매출·미수
          제외)
        </p>
      )}
      <div
        role="tabpanel"
        data-testid={`billing-${tab}-panel`}
        hidden={tab !== "list"}
      >
        <form
          className={styles.search}
          data-testid="billing-search-form"
          onSubmit={(e) => {
            e.preventDefault();
            setFilters({ ...draft, page: 0 });
          }}
        >
          <label>
            상태
            <select
              data-testid="billing-filter-status"
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
              value={draft.customerId ?? ""}
              onChange={(e) =>
                setDraft((d) => ({
                  ...d,
                  customerId: e.target.value
                    ? Number(e.target.value)
                    : undefined,
                }))
              }
            >
              <option value="">전체</option>
              {customerOptions.data?.map((c) => (
                <option
                  key={c.id}
                  value={c.id}
                  data-testid={`billing-filter-customer-option-${c.id}`}
                >
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
              placeholder="프로젝트·청구번호·고객사"
            />
          </label>
          <Button type="button" data-testid="billing-reset" onClick={reset}>
            초기화
          </Button>
          <Button type="submit" variant="primary" data-testid="billing-search">
            조회
          </Button>
        </form>
        <ErrorBanner
          error={list.error}
          onRetry={() => void list.reload().catch(() => {})}
        />
        <ErrorBanner error={downloadError} />
        <ErrorBanner error={customerOptions.error} />
        <div className={styles.toolbar}>
          <span>총 {list.data?.total ?? 0}건</span>
          <Button
            data-testid="billing-export"
            onClick={() => {
              const { page: _page, size: _size, ...scope } = filters;
              void downloadUploadFile(
                `/api/billings/export?${billingQuery(scope)}`,
                `청구_${filters.year}.xlsx`,
              ).catch((e) => setDownloadError(failure(e)));
            }}
          >
            엑셀 다운로드
          </Button>
        </div>
        {list.loading ? (
          <Skeleton />
        ) : (
          !list.error && (
            <div className={tables.tableScroll}>
              <table className={tables.table}>
                <thead>
                  <tr>
                    {columns.map(([key, label]) => (
                      <th
                        key={key}
                        data-testid={`billing-column-${key.replaceAll("_", "-")}`}
                      >
                        <Button
                          data-testid={`billing-sort-${key.replaceAll("_", "-")}`}
                          small
                          variant="text"
                          onClick={() =>
                            setFilters((f) => ({
                              ...f,
                              page: 0,
                              sort: `${key},${f.sort === `${key},asc` ? "desc" : "asc"}`,
                            }))
                          }
                        >
                          {label}
                        </Button>
                      </th>
                    ))}
                    <th>세금계산서 번호</th>
                    <th>미수 알림</th>
                  </tr>
                </thead>
                <tbody>
                  {list.data?.items.map((r) => (
                    <tr
                      key={
                        r.bill_id == null ? `stage-${r.bill_stg_id}` : r.bill_id
                      }
                      data-testid={`billing-row-${r.bill_id ?? `stage-${r.bill_stg_id}`}`}
                    >
                      <td>
                        <Button
                          data-testid={`billing-open-${r.bill_id ?? `stage-${r.bill_stg_id}`}`}
                          variant="text"
                          disabled={r.virtual_plan && readOnly}
                          onClick={() => open(r)}
                        >
                          {r.bill_no ?? "청구 예정"}
                        </Button>
                      </td>
                      <td>
                        <Button
                          data-testid={`billing-project-${r.bill_id ?? `stage-${r.bill_stg_id}`}`}
                          variant="text"
                          onClick={() => navigate(`/UI-01?open=${r.prj_id}`)}
                        >
                          {r.project_name}
                        </Button>
                      </td>
                      <td>{r.customer_name}</td>
                      <td>{r.contract_type}</td>
                      <td>{r.atrb_ym ?? "–"}</td>
                      <td>{r.bill_dt ?? "–"}</td>
                      {[
                        r.sply_amt,
                        r.vat_amt,
                        r.tot_amt,
                        r.received_amt,
                        r.receivable_amt,
                      ].map((v, i) => (
                        <td
                          key={i}
                          className={tables.amount}
                          data-testid={`billing-amount-${r.bill_id ?? `stage-${r.bill_stg_id}`}-${i}`}
                        >
                          {v.toLocaleString("ko-KR")}
                        </td>
                      ))}
                      <td>{r.rcpt_plan_dt ?? "–"}</td>
                      <td>
                        <BillingStatus row={r} />
                        {r.bill_stts_cd === "CONFIRM" && (
                          <Badge
                            tone={
                              r.receivable_amt === 0
                                ? "success"
                                : (r.reminder_stage ?? 0) >= 2
                                  ? "danger"
                                  : "warning"
                            }
                          >
                            {r.receivable_amt === 0
                              ? "입금 완료"
                              : (r.reminder_stage ?? 0) >= 2
                                ? "연체"
                                : "미수"}
                          </Badge>
                        )}
                      </td>
                      <td>{r.txinv_no ?? "–"}</td>
                      <td>
                        {r.reminder_stage == null
                          ? "–"
                          : `${r.reminder_stage}단계`}
                      </td>
                    </tr>
                  ))}
                  {list.data?.items.length === 0 && (
                    <tr>
                      <td colSpan={15}>
                        <div className={tables.empty}>
                          <p>조건에 맞는 결과가 없습니다</p>
                          <Button
                            data-testid="billing-empty-reset"
                            onClick={reset}
                          >
                            조건 초기화
                          </Button>
                        </div>
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>
          )
        )}
        <Pagination
          page={filters.page + 1}
          size={filters.size}
          total={list.data?.total ?? 0}
          onPage={(p) => setFilters((f) => ({ ...f, page: p - 1 }))}
          onSize={(size) => setFilters((f) => ({ ...f, page: 0, size }))}
        />
      </div>
      {gapsPanel && (
        <div hidden={tab !== "gaps"} data-testid="billing-gaps-content">
          {gapsPanel(context)}
        </div>
      )}
      {overduePanel && (
        <div hidden={tab !== "overdue"} data-testid="billing-overdue-content">
          {overduePanel(context)}
        </div>
      )}
      {selected != null && (
        <UI13DetailPanel
          id={selected}
          readOnly={readOnly}
          version={version}
          onClose={() => setSelected(null)}
          onOpen={setSelected}
          onProject={(id) => navigate(`/UI-01?open=${id}`)}
          onEdit={(existing) => setEditor({ existing })}
          refresh={refresh}
          actions={detailActions}
        />
      )}
      {editor && !readOnly && (
        <UI13M1
          {...editor}
          onCancel={() => setEditor(null)}
          onSaved={(d) => {
            setEditor(null);
            setSelected(d.billing.bill_id);
            setMessage("청구를 저장했습니다.");
          }}
          reload={refresh}
          onDirtyChange={onDirtyChange}
          onAttendance={onAttendance}
        />
      )}
      {!readOnly && dialogs}
    </section>
  );
}
