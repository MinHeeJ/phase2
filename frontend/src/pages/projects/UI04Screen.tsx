import { useCallback, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { Badge, Button, Pagination, Skeleton } from "../../shared/Controls";
import { ApiFailure } from "../../shared/api";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { MmGapJudge } from "../../shared/MmGapJudge";
import { getMmGapTolerance } from "../../shared/mmGapParameters";
import { useAdminRead } from "../admin/useAdminResource";
import { ScreenHeading } from "../shared/ScreenHeading";
import { seoulMonth } from "../shared/PeriodBar";
import { useCodes } from "../shared/useCodes";
import { readData } from "../../shared/api";
import RenegotiationPanel from "../renegotiations/UI32Screen";
import { DeveloperDrawer } from "../developers/DeveloperDrawer";
import {
  DeveloperAssignments,
  DeveloperMonthlyCosts,
  DeveloperHistory,
} from "../developersops/DeveloperReadPanels";
import {
  costProjectDefaults,
  listCostProjects,
  getCostProject,
  getCostProjectCauses,
  simulateCostProject,
  downloadCostProjects,
  type CostProjectRow,
  type ProjectSimulation,
  type SimulationInput,
} from "./costClient";
import table from "../developers/Developers.module.css";
import styles from "./CostProjects.module.css";

const money = (v: number | null | undefined) =>
  v == null ? "–" : v.toLocaleString("ko-KR");
const ratio = (v: number | null | undefined) =>
  v == null ? "–" : `${v.toFixed(1)}%`;
const mm = (v: number | null | undefined) => (v == null ? "–" : v.toFixed(2));
const failure = (e: unknown) =>
  e instanceof ApiFailure
    ? e
    : new ApiFailure("조회하지 못했습니다.", null, null);
const causeNames: Record<string, string> = {
  OVER_MM: "투입 초과",
  COST_RATE: "단가 대비 원가",
  FREE_SUPPORT: "무상지원",
};

// SCR-UI-04: annual read-only project cost analysis and nonpersistent simulation.
export default function UI04Screen({
  name = "프로젝트 원가 분석",
  groupName = "원가·수익 분석",
}: {
  name?: string;
  groupName?: string;
  onDirtyChange?: (dirty: boolean) => void;
}) {
  const [filters, setFilters] = useState(costProjectDefaults);
  const [draft, setDraft] = useState(filters);
  const [selected, setSelected] = useState<number | null>(null);
  const [renegotiation, setRenegotiation] = useState<{
    projectId?: number;
  } | null>(null);
  const [exporting, setExporting] = useState(false);
  const [error, setError] = useState<ApiFailure | null>(null);
  const types = useCodes("CONTRACT_TYPE"),
    statuses = useCodes("PROJECT_STATUS");
  const parts = useAdminRead(
    useCallback(
      () =>
        readData<{ id: string; name: string }[]>("/api/sales-parts/options"),
      [],
    ),
  );
  const list = useAdminRead(
    useCallback(() => listCostProjects(filters), [filters]),
  );
  const reset = () => {
    const next = { ...costProjectDefaults(), year: filters.year };
    setDraft(next);
    setFilters(next);
  };
  const shift = (delta: number) => {
    const next = { ...filters, year: filters.year + delta, page: 0 };
    setFilters(next);
    setDraft(next);
    setSelected(null);
    sessionStorage.setItem("UI-04.year", String(next.year));
  };
  const download = async () => {
    setExporting(true);
    setError(null);
    try {
      await downloadCostProjects(filters);
    } catch (e) {
      setError(failure(e));
    } finally {
      setExporting(false);
    }
  };
  if (renegotiation)
    return (
      <RenegotiationPanel
        key={renegotiation.projectId ?? "all"}
        projectId={renegotiation.projectId}
        onClose={() => setRenegotiation(null)}
      />
    );
  const data = list.data;
  return (
    <section data-screen-id="SCR-UI-04" data-testid="cost-project-screen">
      <ScreenHeading
        screenId="UI-04"
        name={name}
        groupName={groupName}
        actions={
          <>
            <Button
              data-testid="cost-project-renegotiations"
              onClick={() => setRenegotiation({})}
            >
              단가 재협상 대상
            </Button>
            <Button
              data-testid="cost-project-download"
              pending={exporting}
              onClick={() => void download()}
            >
              엑셀 다운로드
            </Button>
          </>
        }
      />
      <div className={table.search} aria-label="조회 기간">
        <Button
          data-testid="cost-project-previous-year"
          disabled={filters.year <= 1}
          onClick={() => shift(-1)}
        >
          이전 해
        </Button>
        <strong>{filters.year}년</strong>
        <Button
          data-testid="cost-project-next-year"
          disabled={filters.year >= Number(seoulMonth().slice(0, 4))}
          onClick={() => shift(1)}
        >
          다음 해
        </Button>
        <span>
          1월~{data?.calculation_month.slice(5) ?? "–"}월 누계 · 기준월{" "}
          {data?.reference_month ?? "–"} · 단위: 원
        </span>
        {filters.year < Number(seoulMonth().slice(0, 4)) && (
          <Badge tone="locked">지난 해 · 읽기 전용</Badge>
        )}
      </div>
      {data && !list.error && (
        <div className={styles.summary}>
          {[
            ["누적 매출", data.summary.confirmed_supply_amt],
            ["누적 이익", data.summary.profit_amt],
            ["저마진 프로젝트", data.summary.low_margin_count],
            ["예산 초과", data.summary.budget_over_count],
            ["연구개발비", data.summary.rnd_cost_amt],
          ].map(([label, value]) => (
            <section className={table.card} key={label}>
              <h3>{label}</h3>
              <strong>{money(value as number)}</strong>
            </section>
          ))}
        </div>
      )}
      <form
        className={table.search}
        onSubmit={(e) => {
          e.preventDefault();
          setFilters({ ...draft, page: 0 });
        }}
      >
        <label>
          계약유형
          <select
            data-testid="cost-project-type"
            aria-label="계약유형"
            value={draft.type}
            onChange={(e) => setDraft({ ...draft, type: e.target.value })}
          >
            <option value="">전체</option>
            {types.codes.map((c) => (
              <option key={c.code} value={c.code}>
                {c.name}
              </option>
            ))}
          </select>
        </label>
        <label>
          상태
          <select
            data-testid="cost-project-status"
            aria-label="상태"
            value={draft.status}
            onChange={(e) => setDraft({ ...draft, status: e.target.value })}
          >
            <option value="">전체</option>
            {statuses.codes.map((c) => (
              <option key={c.code} value={c.code}>
                {c.name}
              </option>
            ))}
          </select>
        </label>
        <label>
          영업파트
          <select
            data-testid="cost-project-part"
            aria-label="영업파트"
            value={draft.partId}
            onChange={(e) => setDraft({ ...draft, partId: e.target.value })}
          >
            <option value="">전체</option>
            {parts.data?.map((p) => (
              <option key={p.id} value={p.id}>
                {p.name}
              </option>
            ))}
          </select>
        </label>
        <label>
          검색
          <input
            data-testid="cost-project-search"
            aria-label="검색"
            value={draft.q}
            onChange={(e) => setDraft({ ...draft, q: e.target.value })}
          />
        </label>
        <Button data-testid="cost-project-reset" type="button" onClick={reset}>
          초기화
        </Button>
        <Button
          data-testid="cost-project-query"
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
      <ErrorBanner error={error} onRetry={() => void download()} />
      <ErrorBanner error={types.error} onRetry={types.retry} />
      <ErrorBanner error={statuses.error} onRetry={statuses.retry} />
      <ErrorBanner
        error={parts.error}
        onRetry={() => void parts.reload().catch(() => {})}
      />
      {!list.error &&
        data?.warnings.map((w, i) => (
          <p role="status" key={i}>
            <Badge tone="warning">데이터 결손</Badge> {w}
          </p>
        ))}
      {list.loading ? (
        <Skeleton label="프로젝트 원가 불러오는 중" />
      ) : (
        !list.error &&
        data && (
          <>
            <div className={table.toolbar}>총 {data.total}건</div>
            <div className={table.tableScroll}>
              <table className={table.table} aria-label="프로젝트 원가 목록">
                <thead>
                  <tr>
                    {[
                      ["prj_nm", "프로젝트"],
                      ["", "유형"],
                      ["", "최초/현재 계약금액"],
                      ["", "계획/실제 MM"],
                      ["confirmed_supply_amt", "누적 매출"],
                      ["cumulative_cost_amt", "누적 원가"],
                      ["profit_amt", "누적 이익"],
                      ["margin_rt", "마진율"],
                      ["", "목표/예상 마진"],
                      ["budget_use_rt", "예산 소진"],
                      ["", "MM당 단가/원가/이익"],
                      ["", "무상지원 MM/원가"],
                      ["", "경보/주원인"],
                    ].map(([key, label]) => (
                      <th key={label}>
                        {key ? (
                          <button
                            data-testid={`cost-project-sort-${key.replaceAll("_", "-")}`}
                            onClick={() =>
                              setFilters({
                                ...filters,
                                page: 0,
                                sort: `${key},${filters.sort === `${key},asc` ? "desc" : "asc"}`,
                              })
                            }
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
                  {data.items.map((row) => (
                    <tr
                      key={row.prj_id}
                      data-testid={`cost-project-row-${row.prj_id}`}
                    >
                      <td>
                        <button
                          className={table.textButton}
                          data-testid={`cost-project-open-${row.prj_id}`}
                          onClick={() => setSelected(row.prj_id)}
                        >
                          {row.prj_nm}
                        </button>
                        <div>{row.prj_no}</div>
                      </td>
                      <td>
                        <Badge
                          tone={row.cntrct_ty_cd === "IN" ? "locked" : "info"}
                        >
                          {types.codes.find((c) => c.code === row.cntrct_ty_cd)
                            ?.name ?? row.cntrct_ty_cd}
                        </Badge>
                      </td>
                      <td>
                        {money(row.init_cntrct_amt)} /{" "}
                        {money(row.cur_cntrct_amt)}
                      </td>
                      <td>
                        {mm(row.plan_mm_qty)} / {mm(row.actual_mm_qty)}
                      </td>
                      <td className={table.amount}>
                        {money(row.confirmed_supply_amt)}
                      </td>
                      <td className={table.amount}>
                        {money(row.cumulative_cost_amt)}
                      </td>
                      <td className={table.amount}>{money(row.profit_amt)}</td>
                      <td>{ratio(row.margin_rt)}</td>
                      <td>
                        {ratio(row.trgt_mrgn_rt)} /{" "}
                        {ratio(row.estimated_margin_rt)}
                      </td>
                      <td>
                        {ratio(row.budget_use_rt)}
                        <div>예산 {money(row.trgt_cst_amt)}</div>
                      </td>
                      <td>
                        {money(row.price_per_mm_amt)} /{" "}
                        {money(row.cost_per_mm_amt)} /{" "}
                        {money(row.profit_per_mm_amt)}
                      </td>
                      <td>
                        {mm(row.free_mm_qty)} / {money(row.free_cost_amt)}
                      </td>
                      <td>
                        <CostProjectAlerts row={row} />
                      </td>
                    </tr>
                  ))}
                  {data.items.length === 0 && (
                    <tr>
                      <td colSpan={13} className={table.empty}>
                        조건에 맞는 결과가 없습니다.
                        <Button
                          data-testid="cost-project-empty-reset"
                          onClick={reset}
                        >
                          조건 초기화
                        </Button>
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>
            <Pagination
              page={filters.page + 1}
              size={filters.size}
              total={data.total}
              onPage={(p) => setFilters({ ...filters, page: p - 1 })}
              onSize={(size) => {
                setDraft({ ...draft, size });
                setFilters({ ...filters, size, page: 0 });
              }}
            />
          </>
        )
      )}
      <section className={table.card}>
        <h3>계산 방법</h3>
        <div className={table.tableScroll}>
          <table className={table.table}>
            <thead>
              <tr>
                <th>항목</th>
                <th>산식·출처</th>
              </tr>
            </thead>
            <tbody>
              <tr>
                <td>누적 매출·원가</td>
                <td>
                  확정 청구 공급가액 − 공유 월별 배분 원가·직접 경비 · 무상
                  원가는 이미 포함
                </td>
              </tr>
              <tr>
                <td>예산·저마진</td>
                <td>
                  누적 원가 ÷ 목표 원가 · 80% 주의 / 100% 위험 · 목표 대비 운영
                  기준 초과 하락 시 저마진
                </td>
              </tr>
              <tr>
                <td>턴키 예상</td>
                <td>
                  실제 원가 + 잔여 계획 MM × 최근 인건비 · 참고 매출은 확정 청구
                  매출에 더하지 않음
                </td>
              </tr>
              <tr>
                <td>MO/DP·IN</td>
                <td>
                  MO/DP 기준월 월간 마진 · IN은 매출/계약/마진 없이 연구개발비
                  표시 · 0분모는 –
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>
      {selected != null && (
        <CostProjectDrawer
          key={`${selected}-${filters.year}`}
          id={selected}
          year={filters.year}
          onClose={() => setSelected(null)}
          onRenegotiate={() => {
            setRenegotiation({ projectId: selected });
            setSelected(null);
          }}
        />
      )}
    </section>
  );
}
export function CostProjectAlerts({ row }: { row: CostProjectRow }) {
  return (
    <>
      {row.low_margin && <Badge tone="danger">저마진</Badge>}
      {row.budget_warning && (
        <Badge tone={row.budget_warning === "위험" ? "danger" : "warning"}>
          예산 {row.budget_warning}
        </Badge>
      )}
      {row.cntrct_ty_cd === "IN" && <Badge tone="locked">연구개발비</Badge>}
      <div>{row.primary_cause ? causeNames[row.primary_cause] : "–"}</div>
    </>
  );
}
export function CostProjectDrawer({
  id,
  year,
  onClose,
  onRenegotiate,
}: {
  id: number;
  year: number;
  onClose: () => void;
  onRenegotiate: () => void;
}) {
  const [tab, setTab] = useState("months");
  const [devId, setDevId] = useState<number | null>(null);
  const detail = useAdminRead(
    useCallback(() => getCostProject(id, year), [id, year]),
  );
  const causes = useAdminRead(
    useCallback(
      () =>
        tab === "causes"
          ? getCostProjectCauses(id, year)
          : Promise.resolve(null),
      [id, year, tab],
    ),
  );
  const tolerance = useAdminRead(getMmGapTolerance);
  const navigate = useNavigate();
  const d = detail.data;
  return (
    <div className={table.scrim}>
      <aside
        role="dialog"
        aria-modal="true"
        aria-label="프로젝트 원가 상세"
        className={table.drawer}
        data-testid="cost-project-drawer"
      >
        <div className={table.drawerHeader}>
          <h2>{d?.project.prj_nm ?? "프로젝트 원가 상세"}</h2>
          <Button data-testid="cost-project-close" onClick={onClose}>
            닫기
          </Button>
        </div>
        <Link
          data-testid="cost-project-contract"
          className={table.linkButton}
          to={`/UI-01?open=${id}`}
        >
          프로젝트 상세
        </Link>
        <div role="tablist" className={styles.tabs}>
          {[
            ["months", "월별 원가"],
            ["people", "투입 인력"],
            ["simulation", "시뮬레이션"],
            ["causes", "원가 초과 원인"],
          ].map(([key, label]) => (
            <Button
              key={key}
              role="tab"
              data-testid={`cost-project-tab-${key}`}
              aria-selected={tab === key}
              onClick={() => setTab(key)}
            >
              {label}
            </Button>
          ))}
        </div>
        <ErrorBanner
          error={detail.error}
          onRetry={() => void detail.reload().catch(() => {})}
        />
        {detail.loading ? (
          <Skeleton />
        ) : (
          !detail.error &&
          d && (
            <div role="tabpanel" data-testid={`cost-project-panel-${tab}`}>
              <p>
                기준월 {d.reference_month} ·{" "}
                {d.closed ? "마감 스냅샷" : "미마감"} · 원
              </p>
              {d.warnings.map((w, i) => (
                <p key={i} role="status">
                  <Badge tone="warning">데이터 결손</Badge> {w}
                </p>
              ))}
              {tab === "months" && (
                <div className={table.tableScroll}>
                  <table className={table.table} aria-label="월별 원가">
                    <thead>
                      <tr>
                        {[
                          "월",
                          "확정 청구 매출",
                          "인건비",
                          "경비",
                          "원가",
                          "무상 MM/원가",
                          "마진",
                          "예산 잔여",
                          "턴키 참고매출",
                        ].map((v) => (
                          <th key={v}>{v}</th>
                        ))}
                      </tr>
                    </thead>
                    <tbody>
                      {d.months.map((m) => (
                        <tr
                          key={m.month}
                          data-testid={`cost-project-month-${m.month}`}
                        >
                          <td>
                            {m.month}
                            <Badge tone={m.closed ? "locked" : "info"}>
                              {m.closed ? "마감" : "미마감"}
                            </Badge>
                          </td>
                          <td>{money(m.confirmed_supply_amt)}</td>
                          <td>{money(m.lbr_cst_amt)}</td>
                          <td>{money(m.drct_expns_amt)}</td>
                          <td>{money(m.cumulative_cost_amt)}</td>
                          <td>
                            {mm(m.free_mm_qty)} / {money(m.free_cost_amt)}
                          </td>
                          <td>{ratio(m.margin_rt)}</td>
                          <td>{money(m.budget_remaining_amt)}</td>
                          <td>{money(m.reference_revenue_amt)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
              {tab === "people" && (
                <div className={table.tableScroll}>
                  <table className={table.table} aria-label="투입 인력">
                    <thead>
                      <tr>
                        {[
                          "인력",
                          "월",
                          "MM",
                          "배분 인건비",
                          "경비",
                          "참고 매출",
                          "무상지원",
                        ].map((v) => (
                          <th key={v}>{v}</th>
                        ))}
                      </tr>
                    </thead>
                    <tbody>
                      {d.people.map((p, i) => (
                        <tr
                          key={`${p.allocation.cst_ym}-${p.allocation.asgn_id}-${i}`}
                          data-testid={`cost-project-person-${i}`}
                        >
                          <td>
                            {p.allocation.dev_id == null ? (
                              "프로젝트 전용 경비"
                            ) : (
                              <button
                                data-testid={`cost-project-developer-${i}`}
                                className={table.textButton}
                                onClick={() => setDevId(p.allocation.dev_id)}
                              >
                                {p.dev_nm ?? p.allocation.dev_id}
                              </button>
                            )}
                          </td>
                          <td>{p.allocation.cst_ym}</td>
                          <td>{mm(p.allocation.mm_qty)}</td>
                          <td>{money(p.allocation.lbr_cst_amt)}</td>
                          <td>{money(p.allocation.drct_expns_amt)}</td>
                          <td>{money(p.allocation.sls_amt)}</td>
                          <td>
                            {p.allocation.free_sprt_yn === "Y" && (
                              <Badge tone="warning">무상지원</Badge>
                            )}
                          </td>
                        </tr>
                      ))}
                      {!d.people.length && (
                        <tr>
                          <td colSpan={7} className={table.empty}>
                            투입 인력이 없습니다.
                          </td>
                        </tr>
                      )}
                    </tbody>
                  </table>
                </div>
              )}
              {tab === "simulation" &&
                (year === Number(seoulMonth().slice(0, 4)) ? (
                  <CostProjectSimulation
                    id={id}
                    type={d.project.cntrct_ty_cd}
                  />
                ) : (
                  <p>
                    지난 해는 조회·다운로드만 가능합니다. 시뮬레이션은 현재
                    기준월을 사용합니다.
                  </p>
                ))}
              {tab === "causes" && (
                <>
                  <ErrorBanner
                    error={causes.error}
                    onRetry={() => void causes.reload().catch(() => {})}
                  />
                  <ErrorBanner
                    error={tolerance.error}
                    onRetry={() => void tolerance.reload().catch(() => {})}
                  />
                  {tolerance.data != null && (
                    <p>
                      계획 MM 허용차 {tolerance.data.toFixed(2)} ·{" "}
                      {MmGapJudge(
                        d.project.plan_mm_qty,
                        d.project.actual_mm_qty,
                        tolerance.data,
                      ) === "MATCH"
                        ? "맞음"
                        : "계획 대비 차이 있음"}
                    </p>
                  )}
                  {causes.loading ? (
                    <Skeleton />
                  ) : (
                    !causes.error &&
                    causes.data && (
                      <>
                        <p>
                          TK 진척 대비 초과{" "}
                          {money(causes.data.progress_excess_amt)} · 설명 안
                          되는 나머지 {money(causes.data.unexplained_amt)}
                        </p>
                        <p>
                          원인 금액은 설명 지표이며 총원가에 다시 더하지
                          않습니다.
                        </p>
                        <div className={table.tableScroll}>
                          <table
                            className={table.table}
                            aria-label="원가 초과 원인"
                          >
                            <thead>
                              <tr>
                                {[
                                  "원인",
                                  "금액",
                                  "근거",
                                  "담당",
                                  "권장 조치",
                                ].map((v) => (
                                  <th key={v}>{v}</th>
                                ))}
                              </tr>
                            </thead>
                            <tbody>
                              {causes.data.items.map((c) => (
                                <tr
                                  key={c.cause_code}
                                  data-testid={`cost-project-cause-${c.cause_code.toLowerCase().replaceAll("_", "-")}`}
                                >
                                  <td>
                                    <Badge
                                      tone={
                                        c.amount > 0 ? "warning" : "neutral"
                                      }
                                    >
                                      {causeNames[c.cause_code]}
                                    </Badge>
                                  </td>
                                  <td>{money(c.amount)}</td>
                                  <td>{c.basis}</td>
                                  <td>{c.owner_name ?? "미지정"}</td>
                                  <td>
                                    <Button
                                      data-testid={`cost-project-action-${c.cause_code.toLowerCase().replaceAll("_", "-")}`}
                                      onClick={() => {
                                        if (c.target_screen === "UI-11")
                                          navigate("/UI-11", {
                                            state: { projectId: id, year },
                                          });
                                        else if (c.target_screen === "UI-32")
                                          onRenegotiate();
                                        else setTab("people");
                                      }}
                                    >
                                      {c.recommended_action}
                                    </Button>
                                  </td>
                                </tr>
                              ))}
                            </tbody>
                          </table>
                        </div>
                      </>
                    )
                  )}
                </>
              )}
            </div>
          )
        )}
        {devId != null && (
          <DeveloperDrawer
            id={devId}
            onClose={() => setDevId(null)}
            onSaved={async () => {
              await detail.reload();
            }}
            tabs={[
              {
                key: "assignments",
                label: "투입 이력",
                render: (row) => <DeveloperAssignments id={row.dev_id} />,
              },
              {
                key: "monthly-costs",
                label: "월별 원가",
                render: (row) => <DeveloperMonthlyCosts id={row.dev_id} />,
              },
              {
                key: "history",
                label: "변경 이력",
                render: (row) => <DeveloperHistory id={row.dev_id} />,
              },
            ]}
          />
        )}
      </aside>
    </div>
  );
}
export function CostProjectSimulation({
  id,
  type,
}: {
  id: number;
  type: string;
}) {
  const [input, setInput] = useState({
    headcount_delta: "0",
    monthly_cost_amt: "",
    months: "1",
    monthly_price_amt: "",
  });
  const [error, setError] = useState<ApiFailure | null>(null);
  const [result, setResult] = useState<ProjectSimulation | null>(null);
  const [pending, setPending] = useState(false);
  const monthly = type === "MO" || type === "DP";
  const run = async () => {
    setError(null);
    setResult(null);
    const payload: Record<string, number> = {};
    for (const [key, value] of Object.entries(input)) {
      if (key === "monthly_price_amt" && !monthly) continue;
      const num = Number(value);
      if (
        !value.trim() ||
        !Number.isSafeInteger(num) ||
        (key === "months" ? num <= 0 : key !== "headcount_delta" && num < 0)
      ) {
        setError(
          new ApiFailure(
            "원 단위 정수와 적용 개월을 확인하세요.",
            400,
            null,
            key,
          ),
        );
        return;
      }
      payload[key] = num;
    }
    setPending(true);
    try {
      setResult(
        await simulateCostProject(id, payload as unknown as SimulationInput),
      );
    } catch (e) {
      setError(failure(e));
    } finally {
      setPending(false);
    }
  };
  return (
    <section data-testid="cost-project-simulation">
      <p>
        <Badge tone="info">가상 계산</Badge>현재 기준월 누계를 기준으로 계산하며
        DB에 저장하지 않습니다.
      </p>
      <form
        className={table.search}
        onSubmit={(e) => {
          e.preventDefault();
          void run();
        }}
      >
        {(
          Object.entries({
            headcount_delta: "인원 증감",
            monthly_cost_amt: "1인 월 원가",
            months: "적용 개월",
            ...(monthly ? { monthly_price_amt: "1인 월 단가" } : {}),
          }) as [keyof typeof input, string][]
        ).map(([key, label]) => (
          <label key={key}>
            {label}
            <input
              data-testid={`simulation-${key.replaceAll("_", "-")}`}
              aria-label={label}
              type="number"
              step="1"
              value={input[key]}
              aria-invalid={error?.field === key}
              onChange={(e) => {
                setInput({ ...input, [key]: e.target.value });
                setResult(null);
              }}
            />
            {error?.field === key && <span role="alert">{error.message}</span>}
          </label>
        ))}
        <Button data-testid="simulation-run" type="submit" pending={pending}>
          가상 계산
        </Button>
      </form>
      <ErrorBanner error={error} onRetry={() => void run()} />
      {result && (
        <div data-testid="simulation-result">
          <Badge tone="info">가상 · 저장되지 않음</Badge>
          <p>
            기존 원가 {money(result.baseline_cost_amt)} · 추가 원가{" "}
            {money(result.additional_cost_amt)} · 예상 원가{" "}
            {money(result.simulated_cost_amt)}
          </p>
          <p>
            추가 매출 {money(result.additional_revenue_amt)} · 예상 매출{" "}
            {money(result.simulated_revenue_amt)} · 예상 이익{" "}
            {money(result.simulated_profit_amt)} · 마진{" "}
            {ratio(result.simulated_margin_rt)}
          </p>
          {result.warnings.map((w, i) => (
            <p key={i} role="status">
              {w}
            </p>
          ))}
        </div>
      )}
    </section>
  );
}
