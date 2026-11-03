import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type ReactNode,
} from "react";
import { Link } from "react-router-dom";
import {
  Badge,
  Button,
  DataTable,
  EmptyState,
  Pagination,
  Skeleton,
  Tabs,
} from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { ScreenHeading } from "../shared/ScreenHeading";
import { useAdminRead } from "../admin/useAdminResource";
import { useDeveloperReadOnly } from "../developers/useDeveloperReadOnly";
import { useCodes } from "../shared/useCodes";
import { listPartnerOptions } from "../partnersops/client";
import { listSalesPartOptions } from "../salespartsops/client";
import {
  getOpportunities,
  getClosedOpportunities,
  getOpportunity,
  getOpportunityForecast,
  opportunityFilters,
  opportunityStages,
  type OpportunityDetail,
  type OpportunityFilters,
} from "./client";
import { OpportunityForm } from "./OpportunityForm";
import styles from "./Opportunities.module.css";

const customersRead = () => listPartnerOptions("CUST");
const money = (value: number | null | undefined) =>
  value == null ? "N/A" : `${value.toLocaleString("ko-KR")}원`;
export interface OpportunityDetailPanel {
  key: string;
  label: string;
  render: (
    row: OpportunityDetail,
    refresh: () => Promise<unknown>,
  ) => ReactNode;
}
export interface OpportunitiesProps {
  name?: string;
  onDirtyChange?: (dirty: boolean) => void;
  detailActions?: (
    row: OpportunityDetail,
    refresh: () => Promise<unknown>,
  ) => ReactNode;
  detailPanels?: OpportunityDetailPanel[];
  listActions?: (filters: OpportunityFilters) => ReactNode;
  forecastPanel?: ReactNode;
  refreshVersion?: number;
}
export default function UI29Screen({
  name = "영업 파이프라인",
  onDirtyChange,
  detailActions,
  detailPanels = [],
  listActions,
  forecastPanel,
  refreshVersion = 0,
}: OpportunitiesProps) {
  const [tab, setTab] = useState("open"),
    [filters, setFilters] = useState<OpportunityFilters>(opportunityFilters),
    [draft, setDraft] = useState<OpportunityFilters>(opportunityFilters);
  const [selected, setSelected] = useState<number | null>(null),
    [editing, setEditing] = useState<OpportunityDetail | "new" | null>(null),
    [notice, setNotice] = useState<string | null>(null);
  const readOnly = useDeveloperReadOnly();
  const read = useCallback(async () => {
    if (tab !== "closed") return getOpportunities(filters);
    return getClosedOpportunities(filters);
  }, [filters, tab, refreshVersion]);
  const list = useAdminRead(read),
    customers = useAdminRead(customersRead),
    parts = useAdminRead(listSalesPartOptions);
  const customerName = (id: number | null) =>
    customers.data?.find((c) => Number(c.id) === id)?.name ??
    "고객 정보 확인 중";
  const partName = (id: number | null) =>
    parts.data?.find((p) => Number(p.id) === id)?.name ?? "미지정";
  const refresh = async () => {
    await list.reload();
  };
  if (editing)
    return (
      <OpportunityForm
        initial={editing === "new" ? undefined : editing}
        onDirtyChange={onDirtyChange}
        onCancel={() => setEditing(null)}
        onSaved={(row) => {
          setEditing(null);
          setSelected(row.oppt_id);
          setNotice("저장했습니다.");
          void list.reload().catch(() => {});
        }}
      />
    );
  return (
    <section data-testid="opportunities-screen" data-screen-id="SCR-UI-29">
      <ScreenHeading
        screenId="UI-29"
        name={name}
        groupName="인력 계획"
        actions={
          <>
            {listActions?.(filters)}
            {!readOnly && (
              <Button
                data-testid="opportunity-create"
                variant="primary"
                onClick={() => {
                  setSelected(null);
                  setEditing("new");
                }}
              >
                + 기회 등록
              </Button>
            )}
          </>
        }
      />
      {notice && (
        <p role="status">
          {notice}
          <Button
            data-testid="opportunity-notice-dismiss"
            variant="text"
            onClick={() => setNotice(null)}
          >
            닫기
          </Button>
        </p>
      )}
      {list.data && (
        <div className={styles.kpis} data-testid="opportunities-kpi">
          <article>
            <p>진행 기회</p>
            <strong>{list.data.kpi.open_count}건</strong>
          </article>
          <article>
            <p>예상 금액</p>
            <strong>{money(list.data.kpi.expected_amount)}</strong>
          </article>
          <article>
            <p>확률 반영 금액</p>
            <strong>{money(list.data.kpi.weighted_amount)}</strong>
          </article>
          <article>
            <p>필요 인력</p>
            <strong>{list.data.kpi.needed_people}명</strong>
          </article>
        </div>
      )}
      <Tabs
        selected={tab}
        onChange={(key) => {
          setTab(key);
          setFilters((f) => ({ ...f, page: 0, bsn_stg_cd: "" }));
        }}
        items={[
          { key: "open", label: "진행 중" },
          { key: "closed", label: "종료 · 수주·실주" },
          { key: "forecast", label: "매출·인력 예측" },
        ]}
      />
      {tab === "forecast" ? (
        (forecastPanel ?? <OpportunityForecastTab />)
      ) : (
        <div role="tabpanel" data-testid={`opportunities-${tab}-panel`}>
          <form
            className={styles.search}
            onSubmit={(e) => {
              e.preventDefault();
              setFilters({ ...draft, page: 0 });
            }}
          >
            <label>
              검색
              <input
                data-testid="opportunity-search"
                placeholder="기회명·고객 검색"
                value={draft.q}
                onChange={(e) => setDraft((d) => ({ ...d, q: e.target.value }))}
              />
            </label>
            <label>
              고객
              <select
                data-testid="opportunity-filter-customer"
                value={draft.cust_ptnr_id}
                onChange={(e) =>
                  setDraft((d) => ({ ...d, cust_ptnr_id: e.target.value }))
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
              영업 파트
              <select
                data-testid="opportunity-filter-part"
                value={draft.bsn_part_id}
                onChange={(e) =>
                  setDraft((d) => ({ ...d, bsn_part_id: e.target.value }))
                }
              >
                <option value="">전체</option>
                {parts.data?.map((p) => (
                  <option key={p.id} value={p.id}>
                    {p.name}
                  </option>
                ))}
              </select>
            </label>
            {tab === "open" && (
              <label>
                단계
                <select
                  data-testid="opportunity-filter-stage"
                  value={draft.bsn_stg_cd}
                  onChange={(e) =>
                    setDraft((d) => ({ ...d, bsn_stg_cd: e.target.value }))
                  }
                >
                  <option value="">전체</option>
                  {opportunityStages.map((s) => (
                    <option key={s.code} value={s.code}>
                      {s.name}
                    </option>
                  ))}
                </select>
              </label>
            )}
            <label>
              시작월
              <input
                data-testid="opportunity-filter-from"
                type="month"
                value={draft.from}
                onChange={(e) =>
                  setDraft((d) => ({ ...d, from: e.target.value }))
                }
              />
            </label>
            <label>
              종료월
              <input
                data-testid="opportunity-filter-to"
                type="month"
                value={draft.to}
                onChange={(e) =>
                  setDraft((d) => ({ ...d, to: e.target.value }))
                }
              />
            </label>
            <label>
              정렬
              <select
                data-testid="opportunity-sort"
                value={draft.sort}
                onChange={(e) =>
                  setDraft((d) => ({ ...d, sort: e.target.value }))
                }
              >
                <option value="oppt_id,asc">기회 번호순</option>
                <option value="expc_amt,desc">예상 금액 높은순</option>
                <option value="updated_at,desc">최근 수정순</option>
                <option value="oppt_nm,asc">기회명순</option>
              </select>
            </label>
            <Button
              data-testid="opportunity-filter-reset"
              type="button"
              onClick={() => {
                setDraft(opportunityFilters);
                setFilters(opportunityFilters);
              }}
            >
              초기화
            </Button>
            <Button
              data-testid="opportunity-filter-submit"
              type="submit"
              variant="primary"
            >
              조회
            </Button>
          </form>
          <ErrorBanner
            error={list.error}
            onRetry={() => void list.reload().catch(() => {})}
          />
          {list.loading ? (
            <Skeleton />
          ) : list.error ? null : (
            list.data && (
              <>
                {list.data.kpi.stale_count > 0 && (
                  <p className={styles.warning}>
                    {list.data.kpi.stale_count}건은 14일 초과 갱신이 없습니다.
                  </p>
                )}
                {tab === "open" ? (
                  <>
                    <div className={styles.kanban}>
                      {opportunityStages.map((stage) => (
                        <section
                          key={stage.code}
                          className={styles.column}
                          data-testid={`opportunity-column-${stage.code.toLowerCase()}`}
                        >
                          <h2>
                            {stage.name}
                            <span>기본 {stage.probability}%</span>
                          </h2>
                          {list
                            .data!.items.filter(
                              (r) => r.bsn_stg_cd === stage.code,
                            )
                            .map((row) => (
                              <button
                                type="button"
                                key={row.oppt_id}
                                data-testid={`opportunity-card-${row.oppt_id}`}
                                className={styles.opportunity}
                                onClick={() => setSelected(row.oppt_id)}
                              >
                                <small>
                                  {row.oppt_no} ·{" "}
                                  {customerName(row.cust_ptnr_id)}
                                </small>
                                <strong>{row.oppt_nm}</strong>
                                <span>
                                  {money(row.expc_amt)} · {row.win_prblt_rt}%
                                </span>
                                <small>
                                  {partName(row.bsn_part_id)} · 담당자{" "}
                                  {row.owner_user_id ?? "미지정"}
                                </small>
                                <small>
                                  {row.bgn_ym} · {row.mth_cnt}개월 ·{" "}
                                  {row.needs.reduce(
                                    (sum, n) => sum + n.psn_cnt,
                                    0,
                                  )}
                                  명
                                </small>
                                {row.next_action && (
                                  <small>
                                    다음 할 일: {row.next_action}{" "}
                                    {row.next_action_dt}
                                  </small>
                                )}
                                <span className={styles.marks}>
                                  <OpportunityApprovalBadge row={row} />
                                  {row.stale_warning && (
                                    <Badge tone="warning">장기 미갱신</Badge>
                                  )}
                                </span>
                              </button>
                            ))}
                          {!list.data!.items.some(
                            (r) => r.bsn_stg_cd === stage.code,
                          ) && (
                            <p className={styles.columnEmpty}>
                              진행 중인 기회가 없습니다.
                            </p>
                          )}
                        </section>
                      ))}
                    </div>
                    {list.data.total === 0 && (
                      <EmptyState
                        reason="조건에 맞는 기회가 없습니다"
                        actionLabel="필터 초기화"
                        onAction={() => {
                          setDraft(opportunityFilters);
                          setFilters(opportunityFilters);
                        }}
                      />
                    )}
                  </>
                ) : (
                  <DataTable
                    rows={list.data.items}
                    rowKey={(r) => String(r.oppt_id)}
                    total={list.data.total}
                    columns={[
                      { key: "no", label: "번호", render: (r) => r.oppt_no },
                      {
                        key: "name",
                        label: "기회명",
                        render: (r) => (
                          <Button
                            data-testid={`opportunity-row-${r.oppt_id}`}
                            variant="text"
                            onClick={() => setSelected(r.oppt_id)}
                          >
                            {r.oppt_nm}
                          </Button>
                        ),
                      },
                      {
                        key: "customer",
                        label: "고객",
                        render: (r) => customerName(r.cust_ptnr_id),
                      },
                      {
                        key: "status",
                        label: "결과",
                        render: (r) => (
                          <Badge
                            tone={
                              r.oppt_stts_cd === "WON" ? "success" : "danger"
                            }
                          >
                            {r.oppt_stts_cd === "WON" ? "수주" : "실주"}
                          </Badge>
                        ),
                      },
                      {
                        key: "amount",
                        label: "예상 금액",
                        kind: "number",
                        render: (r) => money(r.expc_amt),
                      },
                      {
                        key: "project",
                        label: "프로젝트",
                        render: (r) =>
                          r.win_prj_id ? (
                            <Link
                              data-testid={`opportunity-project-${r.oppt_id}`}
                              to="/UI-01"
                              state={{ projectId: r.win_prj_id }}
                            >
                              프로젝트 보기
                            </Link>
                          ) : (
                            "—"
                          ),
                      },
                    ]}
                  />
                )}
                <Pagination
                  page={filters.page + 1}
                  size={filters.size}
                  total={list.data.total}
                  onPage={(p) => setFilters((f) => ({ ...f, page: p - 1 }))}
                  onSize={(size) =>
                    setFilters((f) => ({ ...f, size, page: 0 }))
                  }
                />
              </>
            )
          )}
        </div>
      )}
      {selected != null && (
        <OpportunityDrawer
          key={selected}
          id={selected}
          onClose={() => setSelected(null)}
          onEdit={(row) => {
            setSelected(null);
            setEditing(row);
          }}
          onRefresh={refresh}
          actions={detailActions}
          panels={detailPanels}
        />
      )}
    </section>
  );
}
export function OpportunityApprovalBadge({ row }: { row: OpportunityDetail }) {
  const state = row.quote.approval_state;
  return (
    <Badge
      tone={
        state === "APPROVED"
          ? "success"
          : state === "REJECTED"
            ? "danger"
            : state === "PENDING"
              ? "warning"
              : "neutral"
      }
    >
      {state === "APPROVED"
        ? "승인 완료"
        : state === "REJECTED"
          ? "반려"
          : state === "PENDING"
            ? "승인 대기"
            : "승인 불필요"}
    </Badge>
  );
}
export function OpportunityQuoteSummary({ row }: { row: OpportunityDetail }) {
  const q = row.quote;
  return (
    <>
      <div className={styles.marks}>
        <OpportunityApprovalBadge row={row} />
        <Badge tone={q.cost_status === "UNKNOWN" ? "warning" : "success"}>
          {q.cost_status === "UNKNOWN"
            ? "원가 미확인"
            : q.cost_status === "PROVEN_ZERO"
              ? "실제 0원 검증 완료"
              : "원가 확인"}
        </Badge>
      </div>
      <dl className={styles.quote}>
        {[
          ["예상 매출", money(q.expc_sls_amt)],
          ["예상 원가", money(q.expc_cst_amt)],
          [
            "이익률",
            q.expc_mrgn_rt == null ? "N/A" : `${q.expc_mrgn_rt.toFixed(2)}%`,
          ],
          ["손익분기액", money(q.minimum_amount)],
          ["금액 차이", money(q.amount_difference)],
          [
            "차이율",
            q.amount_difference_rate == null
              ? "N/A"
              : `${q.amount_difference_rate.toFixed(2)}%`,
          ],
        ].map(([label, value]) => (
          <div key={label}>
            <dt>{label}</dt>
            <dd>{value}</dd>
          </div>
        ))}
      </dl>
      {q.warnings.map((w, i) => (
        <p key={i} className={styles.warning}>
          {w}
        </p>
      ))}
      {row.amount_difference_reason && (
        <p>불일치 사유: {row.amount_difference_reason}</p>
      )}
    </>
  );
}
export function OpportunityDrawer({
  id,
  onClose,
  onEdit,
  onRefresh,
  actions,
  panels = [],
}: {
  id: number;
  onClose: () => void;
  onEdit: (row: OpportunityDetail) => void;
  onRefresh: () => Promise<unknown>;
  actions?: OpportunitiesProps["detailActions"];
  panels?: OpportunityDetailPanel[];
}) {
  const detail = useAdminRead(useCallback(() => getOpportunity(id), [id])),
    [tab, setTab] = useState("basic");
  const dialog = useRef<HTMLElement>(null);
  const readOnly = useDeveloperReadOnly();
  const jobs = useCodes("JOB"),
    grades = useCodes("GRADE");
  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null;
    dialog.current?.focus();
    return () => previous?.focus();
  }, []);
  const refresh = async () => {
    await detail.reload();
    await onRefresh();
  };
  const row = detail.data;
  return (
    <div className={styles.scrim}>
      <aside
        role="dialog"
        aria-modal="true"
        aria-label="기회 상세"
        tabIndex={-1}
        ref={dialog}
        data-testid="opportunity-detail"
        className={styles.drawer}
        onKeyDown={(e) => {
          if (e.key === "Escape") onClose();
          if (e.key === "Tab") {
            const focusable = Array.from(
              dialog.current?.querySelectorAll<HTMLElement>(
                "button:not(:disabled),a[href],input:not(:disabled),select:not(:disabled)",
              ) ?? [],
            );
            const first = focusable[0],
              last = focusable.at(-1);
            if (
              e.shiftKey &&
              (document.activeElement === first ||
                document.activeElement === dialog.current)
            ) {
              e.preventDefault();
              last?.focus();
            } else if (!e.shiftKey && document.activeElement === last) {
              e.preventDefault();
              first?.focus();
            }
          }
        }}
      >
        <header className={styles.actions}>
          <h2>기회 상세</h2>
          <Button data-testid="opportunity-detail-close" onClick={onClose}>
            닫기
          </Button>
        </header>
        <ErrorBanner
          error={detail.error}
          onRetry={() => void detail.reload().catch(() => {})}
        />
        {detail.loading ? (
          <Skeleton />
        ) : (
          row && (
            <>
              <h3>{row.oppt_nm}</h3>
              <p>
                {row.oppt_no} ·{" "}
                {row.oppt_stts_cd === "OPEN"
                  ? "진행 중"
                  : row.oppt_stts_cd === "WON"
                    ? "수주"
                    : row.oppt_stts_cd === "LOST"
                      ? "실주"
                      : "삭제"}
              </p>
              <Tabs
                selected={tab}
                onChange={setTab}
                items={[
                  { key: "basic", label: "기본 정보" },
                  { key: "needs", label: "필요 인력" },
                  { key: "quote", label: "견적 점검" },
                  { key: "approvals", label: "승인 이력" },
                  ...panels.map((p) => ({ key: p.key, label: p.label })),
                ].slice(0, 5)}
              />
              <div role="tabpanel" data-testid={`opportunity-detail-${tab}`}>
                {tab === "basic" && (
                  <dl>
                    <dt>예상 금액</dt>
                    <dd>{money(row.expc_amt)}</dd>
                    <dt>단계·확률</dt>
                    <dd>
                      {opportunityStages.find((s) => s.code === row.bsn_stg_cd)
                        ?.name ?? row.bsn_stg_cd}{" "}
                      · {row.win_prblt_rt}%
                    </dd>
                    <dt>계약유형·기간</dt>
                    <dd>
                      {row.cntrct_ty_cd} · {row.bgn_ym}부터 {row.mth_cnt}개월
                    </dd>
                    <dt>담당자</dt>
                    <dd>{row.owner_user_id ?? "미지정"}</dd>
                    <dt>다음 할 일</dt>
                    <dd>
                      {row.next_action ?? "없음"} {row.next_action_dt}
                    </dd>
                    <dt>비고</dt>
                    <dd>{row.rmrk_desc ?? "없음"}</dd>
                  </dl>
                )}
                {tab === "needs" && (
                  <>
                    <DataTable
                      rows={row.needs}
                      rowKey={(n) => String(n.need_id)}
                      total={row.needs.length}
                      columns={[
                        {
                          key: "job",
                          label: "직무·등급",
                          render: (n) =>
                            `${jobs.codes.find((j) => j.code === n.job_cd)?.name ?? n.job_cd} / ${grades.codes.find((g) => g.code === n.grd_cd)?.name ?? n.grd_cd ?? "미지정"}`,
                        },
                        {
                          key: "people",
                          label: "인원",
                          render: (n) => n.psn_cnt,
                        },
                        {
                          key: "period",
                          label: "기간·MM",
                          render: (n) =>
                            `${n.bgn_ym} · ${n.mth_cnt}개월 · ${n.mth_mm_qty}MM`,
                        },
                        {
                          key: "price",
                          label: "예상 단가",
                          render: (n) => money(n.expc_untprc_amt),
                        },
                        {
                          key: "min",
                          label: "최소단가",
                          render: (n) => money(n.min_untprc_amt),
                        },
                        {
                          key: "target",
                          label: "목표단가",
                          render: (n) => money(n.trgt_untprc_amt),
                        },
                      ]}
                    />
                    <OpportunityQuoteSummary row={row} />
                  </>
                )}
                {tab === "quote" && <OpportunityQuoteSummary row={row} />}
                {tab === "approvals" && (
                  <>
                    <OpportunityApprovalBadge row={row} />
                    <DataTable
                      rows={row.approval_history}
                      rowKey={(h) => `${h.created_at}-${h.action}-${h.user_id}`}
                      total={row.approval_history.length}
                      columns={[
                        {
                          key: "date",
                          label: "일시",
                          render: (h) => h.created_at,
                        },
                        {
                          key: "action",
                          label: "처리",
                          render: (h) =>
                            h.action === "REQUEST"
                              ? "승인 요청"
                              : h.action === "APPROVE"
                                ? "승인"
                                : "반려",
                        },
                        {
                          key: "opinion",
                          label: "의견",
                          render: (h) => h.opinion ?? "—",
                        },
                        {
                          key: "actor",
                          label: "처리자",
                          render: (h) => h.user_id ?? "기본 사용자",
                        },
                      ]}
                    />
                  </>
                )}
                {panels.find((p) => p.key === tab)?.render(row, refresh)}
              </div>
              <footer className={styles.actions}>
                {row.oppt_stts_cd === "OPEN" && !readOnly && (
                  <Button
                    data-testid="opportunity-edit"
                    onClick={() => onEdit(row)}
                  >
                    수정
                  </Button>
                )}
                {actions?.(row, refresh)}
              </footer>
            </>
          )
        )}
      </aside>
    </div>
  );
}
export function OpportunityForecastTab() {
  const forecast = useAdminRead(getOpportunityForecast),
    data = forecast.data;
  return (
    <section role="tabpanel" data-testid="opportunity-forecast">
      <ErrorBanner
        error={forecast.error}
        onRetry={() => void forecast.reload().catch(() => {})}
      />
      {forecast.loading ? (
        <Skeleton />
      ) : (
        data && (
          <>
            <h2>앞으로 6개월 예상 매출</h2>
            <Badge tone="info">예측 값</Badge>
            {!data.items.length ? (
              <p>{data.workforce.message ?? "예측할 미래월이 없습니다."}</p>
            ) : (
              <>
                <div className={styles.chart} aria-label="월별 확률 반영 매출">
                  {data.items.map((m) => (
                    <div
                      key={m.month}
                      data-testid={`forecast-month-${m.month}`}
                    >
                      <span>{money(m.weighted_total)}</span>
                      <i
                        style={{
                          height: `${Math.max(2, (m.weighted_total / Math.max(1, ...data.items.map((x) => x.weighted_total))) * 140)}px`,
                        }}
                      />
                      <span>{m.month}</span>
                    </div>
                  ))}
                </div>
                <DataTable
                  rows={data.items}
                  rowKey={(m) => m.month}
                  total={data.items.length}
                  columns={[
                    { key: "month", label: "월", render: (m) => m.month },
                    {
                      key: "project",
                      label: "진행 중 프로젝트",
                      render: (m) => money(m.project_revenue),
                    },
                    {
                      key: "opportunity",
                      label: "기회 예상 매출",
                      render: (m) => money(m.opportunity_revenue),
                    },
                    {
                      key: "weighted",
                      label: "확률 반영",
                      render: (m) => money(m.weighted_total),
                    },
                    {
                      key: "all",
                      label: "전부 수주 시",
                      render: (m) => money(m.all_won_total),
                    },
                  ]}
                />
              </>
            )}
            <h2>인력 수요와 공급</h2>
            <DataTable
              rows={data.workforce.items}
              rowKey={(m) => `${m.month}-${m.job_cd}-${m.grd_cd}`}
              total={data.workforce.items.length}
              columns={[
                { key: "month", label: "월", render: (m) => m.month },
                {
                  key: "job",
                  label: "직무·등급",
                  render: (m) => `${m.job_cd} / ${m.grd_cd ?? "미분류"}`,
                },
                { key: "supply", label: "공급 MM", render: (m) => m.supply_mm },
                { key: "demand", label: "수요 MM", render: (m) => m.demand_mm },
              ]}
            />
            {[...new Set(data.workforce.items.flatMap((m) => m.warnings))].map(
              (w) => (
                <p key={w} className={styles.warning}>
                  {w}
                </p>
              ),
            )}
          </>
        )
      )}
    </section>
  );
}
