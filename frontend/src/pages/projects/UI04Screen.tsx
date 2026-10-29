import { useCallback, useEffect, useState } from "react";
import { Link, useLocation } from "react-router-dom";
import { Badge, Button, Skeleton } from "../../shared/Controls";
import { ApiFailure } from "../../shared/api";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { MmGapBadge } from "../../shared/MmGapBadge";
import { getMmGapTolerance } from "../../shared/mmGapParameters";
import { useAdminRead } from "../admin/useAdminResource";
import { ScreenHeading } from "../shared/ScreenHeading";
import { seoulMonth } from "../shared/PeriodBar";
import { useCodes } from "../shared/useCodes";
import { listSalesPartOptions } from "../salespartsops/client";
import type {
  CostProjectFilters,
  CostSimulationRequest,
  CostSimulationResult,
} from "../costprofit/client";
import {
  listCostProjects,
  getCostProject,
  simulateCostProject,
  downloadCostProjects,
  projectNumber as fmt,
  causeLabel,
  type CostProjectRow,
  type CostProjectDetail,
} from "./costProjectClient";
import { UI04CausesPanel } from "./UI04CausesPanel";
import table from "../developers/Developers.module.css";
import styles from "./ProjectCosts.module.css";

// SCR-UI-04: project profitability, read-only monthly evidence and arithmetic simulation.
export default function UI04Screen({
  name = "프로젝트 원가 분석",
  groupName = "원가·수익",
}: {
  name?: string;
  groupName?: string;
}) {
  const location = useLocation();
  const query = new URLSearchParams(location.search);
  const [filters, setFilters] = useState<CostProjectFilters>(() => ({
    year:
      Number(query.get("year")) ||
      Number(sessionStorage.getItem("UI-04.year")) ||
      Number(seoulMonth().slice(0, 4)),
    page: 0,
    size: 50,
    sort: "prj_nm,asc",
  }));
  const [draft, setDraft] = useState(filters);
  const [selected, setSelected] = useState<number | null>(() =>
    /^[1-9][0-9]*$/.test(query.get("projectId") ?? "")
      ? Number(query.get("projectId"))
      : null,
  );
  useEffect(() => {
    if (location.pathname !== "/UI-04") return;
    const params = new URLSearchParams(location.search);
    const year = Number(params.get("year"));
    if (
      Number.isInteger(year) &&
      year >= 1 &&
      year <= Number(seoulMonth().slice(0, 4))
    ) {
      setFilters((current) =>
        current.year === year ? current : { ...current, year, page: 0 },
      );
      setDraft((current) =>
        current.year === year ? current : { ...current, year, page: 0 },
      );
      sessionStorage.setItem("UI-04.year", String(year));
    }
    const projectId = params.get("projectId");
    if (
      /^[1-9][0-9]*$/.test(projectId ?? "") &&
      Number.isSafeInteger(Number(projectId))
    )
      setSelected(Number(projectId));
  }, [location.pathname, location.search]);
  const [unit, setUnit] = useState(1);
  const [warningOnly, setWarningOnly] = useState(false);
  const [downloadError, setDownloadError] = useState<ApiFailure | null>(null);
  const [downloading, setDownloading] = useState(false);
  const list = useAdminRead(
    useCallback(() => listCostProjects<CostProjectRow>(filters), [filters]),
  );
  const types = useCodes("CONTRACT_TYPE"),
    statuses = useCodes("PROJECT_STATUS");
  const parts = useAdminRead(listSalesPartOptions);
  const tolerance = useAdminRead(getMmGapTolerance);
  const apply = (next: CostProjectFilters) => {
    setFilters(next);
    setDraft(next);
    setSelected(null);
    sessionStorage.setItem("UI-04.year", String(next.year));
  };
  const reset = () => {
    apply({ year: filters.year, page: 0, size: 50, sort: "prj_nm,asc" });
    setWarningOnly(false);
  };
  const download = async () => {
    setDownloading(true);
    setDownloadError(null);
    try {
      const file = await downloadCostProjects(filters),
        url = URL.createObjectURL(file.blob);
      const link = document.createElement("a");
      link.href = url;
      link.download = file.filename;
      link.click();
      URL.revokeObjectURL(url);
    } catch (error) {
      setDownloadError(
        error instanceof ApiFailure
          ? error
          : new ApiFailure("다운로드에 실패했습니다.", null, null),
      );
    } finally {
      setDownloading(false);
    }
  };
  const rows =
    list.data?.items.filter(
      (r) =>
        !warningOnly ||
        r.low_margin ||
        r.budget_warning === "WARNING" ||
        r.budget_warning === "DANGER" ||
        r.warnings.length,
    ) ?? [];
  const money = (value: number | null | undefined) => fmt(value, 0, unit);
  const columns: [keyof CostProjectRow, string][] = [
    ["init_cntrct_amt", "원 계약금액"],
    ["cur_cntrct_amt", "현재 계약금액"],
    ["plan_mm_qty", "계획 MM"],
    ["actual_mm_qty", "실제 MM"],
    ["confirmed_supply_amt", "누적 매출"],
    ["total_cost_amt", "누적 원가"],
    ["profit_amt", "이익"],
    ["margin_rt", "누적 마진율"],
    ["expected_margin_rt", "예상 최종·월간 마진율"],
    ["target_diff_rt", "목표 대비"],
    ["budget_use_rt", "예산 소진율"],
    ["per_mm_price_amt", "MM당 단가"],
    ["per_mm_cost_amt", "MM당 원가"],
    ["per_mm_profit_amt", "MM당 이익"],
  ];
  return (
    <section
      className={styles.screen}
      data-screen-id="SCR-UI-04"
      data-testid="project-costs-screen"
    >
      <ScreenHeading
        screenId="UI-04"
        name={name}
        groupName={groupName}
        actions={
          <Button
            data-testid="project-costs-download"
            pending={downloading}
            onClick={() => void download()}
          >
            엑셀 다운로드
          </Button>
        }
      />
      <div className={styles.period}>
        <Button
          data-testid="project-costs-previous-year"
          aria-label="이전 해"
          disabled={filters.year <= 1}
          onClick={() => apply({ ...filters, year: filters.year - 1, page: 0 })}
        >
          ‹
        </Button>
        <strong>{filters.year}년</strong>
        <Button
          data-testid="project-costs-next-year"
          aria-label="다음 해"
          disabled={filters.year >= Number(seoulMonth().slice(0, 4))}
          onClick={() => apply({ ...filters, year: filters.year + 1, page: 0 })}
        >
          ›
        </Button>
        <span>그 해 1월부터 기준월까지 누계</span>
        {filters.year < Number(seoulMonth().slice(0, 4)) && (
          <Badge tone="locked">지난 해 · 읽기 전용</Badge>
        )}
      </div>
      {list.data && (
        <div className={styles.cards} data-testid="project-costs-summary">
          {[
            ["누적 매출", money(list.data.summary.confirmed_supply_amt)],
            ["누적 이익", money(list.data.summary.profit_amt)],
            ["저마진 프로젝트", fmt(list.data.summary.low_margin_count)],
            ["예산 초과", fmt(list.data.summary.budget_exceeded_count)],
            ["연구개발비", money(list.data.summary.rnd_cost_amt)],
          ].map(([label, value]) => (
            <article key={label}>
              <span>{label}</span>
              <strong>{value}</strong>
            </article>
          ))}
        </div>
      )}
      <form
        className={table.search}
        onSubmit={(event) => {
          event.preventDefault();
          apply({ ...draft, page: 0 });
        }}
      >
        <label>
          계약유형{" "}
          <select
            data-testid="project-costs-type"
            value={draft.type ?? ""}
            onChange={(e) =>
              setDraft({ ...draft, type: e.target.value || undefined })
            }
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
          상태{" "}
          <select
            data-testid="project-costs-status"
            value={draft.status ?? ""}
            onChange={(e) =>
              setDraft({ ...draft, status: e.target.value || undefined })
            }
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
          영업 파트{" "}
          <select
            data-testid="project-costs-part"
            value={draft.partId ?? ""}
            onChange={(e) =>
              setDraft({ ...draft, partId: e.target.value || undefined })
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
        <label>
          검색{" "}
          <input
            data-testid="project-costs-search"
            value={draft.q ?? ""}
            maxLength={200}
            onChange={(e) => setDraft({ ...draft, q: e.target.value })}
          />
        </label>
        <Button data-testid="project-costs-reset" type="button" onClick={reset}>
          초기화
        </Button>
        <Button
          data-testid="project-costs-query"
          variant="primary"
          type="submit"
        >
          조회
        </Button>
      </form>
      <ErrorBanner
        error={list.error}
        onRetry={() => void list.reload().catch(() => {})}
      />
      <ErrorBanner error={downloadError} onRetry={() => void download()} />
      {[types, statuses].map((source, i) => (
        <ErrorBanner key={i} error={source.error} onRetry={source.retry} />
      ))}
      <ErrorBanner
        error={parts.error}
        onRetry={() => void parts.reload().catch(() => {})}
      />
      <ErrorBanner
        error={tolerance.error}
        onRetry={() => void tolerance.reload().catch(() => {})}
      />
      {list.data?.warnings.map((w) => (
        <p key={w} className={styles.warning}>
          {w}
        </p>
      ))}
      <div className={table.toolbar}>
        <span>
          총 {list.data?.total ?? 0}건 · {list.data?.month} 기준 · 단위 KRW
        </span>
        <label>
          보기{" "}
          <select
            data-testid="project-costs-warning-only"
            value={String(warningOnly)}
            onChange={(e) => setWarningOnly(e.target.value === "true")}
          >
            <option value="false">전체</option>
            <option value="true">경보 있는 것만 (현재 페이지)</option>
          </select>
        </label>
        <label>
          단위{" "}
          <select
            data-testid="project-costs-unit"
            value={unit}
            onChange={(e) => setUnit(Number(e.target.value))}
          >
            <option value={1}>원</option>
            <option value={1000}>천 원</option>
            <option value={1000000}>백만 원</option>
          </select>
        </label>
      </div>
      {list.loading ? (
        <Skeleton label="프로젝트 원가 불러오는 중" />
      ) : (
        !list.error && (
          <div className={table.tableScroll}>
            <table className={table.table}>
              <thead>
                <tr>
                  <th>
                    <button
                      data-testid="project-costs-sort-name"
                      onClick={() =>
                        apply({
                          ...filters,
                          sort:
                            filters.sort === "prj_nm,asc"
                              ? "prj_nm,desc"
                              : "prj_nm,asc",
                          page: 0,
                        })
                      }
                    >
                      프로젝트
                    </button>
                  </th>
                  <th>유형</th>
                  <th>상태</th>
                  {columns.map(([key, label]) => (
                    <th key={key}>{label}</th>
                  ))}
                  <th>무상지원 MM·원가</th>
                  <th>경보 · 주원인</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((row) => (
                  <tr
                    key={row.prj_id}
                    data-testid={`project-costs-row-${row.prj_id}`}
                    onClick={() => setSelected(row.prj_id)}
                  >
                    <td>
                      <button
                        className={table.textButton}
                        data-testid={`project-costs-open-${row.prj_id}`}
                        onClick={() => setSelected(row.prj_id)}
                      >
                        {row.prj_nm}
                      </button>
                      <br />
                      {row.prj_no}
                    </td>
                    <td>
                      <Badge>
                        {types.codes.find((t) => t.code === row.cntrct_ty_cd)
                          ?.name ?? row.cntrct_ty_cd}
                      </Badge>
                    </td>
                    <td>
                      <Badge
                        tone={row.prj_stts_cd === "END" ? "locked" : "info"}
                      >
                        {statuses.codes.find((t) => t.code === row.prj_stts_cd)
                          ?.name ?? row.prj_stts_cd}
                      </Badge>
                    </td>
                    {columns.map(([key]) => (
                      <td key={key} className={table.amount}>
                        {key === "plan_mm_qty" || key === "actual_mm_qty" ? (
                          <>
                            {fmt(row[key], 2)}
                            {key === "actual_mm_qty" &&
                              row.plan_mm_qty != null &&
                              tolerance.data != null && (
                                <MmGapBadge
                                  planMm={row.plan_mm_qty}
                                  actualMm={row.actual_mm_qty}
                                  tolerance={tolerance.data}
                                />
                              )}
                          </>
                        ) : key.endsWith("_rt") ? (
                          fmt(
                            key === "expected_margin_rt" &&
                              row.cntrct_ty_cd !== "TK"
                              ? row.monthly_margin_rt
                              : (row[key] as number | null),
                            1,
                          )
                        ) : (
                          money(row[key] as number | null)
                        )}
                      </td>
                    ))}
                    <td>
                      {fmt(row.free_mm_qty, 2)} / {money(row.free_cost_amt)}
                    </td>
                    <td>
                      {row.low_margin && <Badge tone="danger">저마진</Badge>}{" "}
                      {row.budget_warning === "DANGER" ? (
                        <Badge tone="danger">예산 100% 이상</Badge>
                      ) : row.budget_warning === "WARNING" ? (
                        <Badge tone="warning">예산 80% 이상</Badge>
                      ) : row.budget_warning === "UNAVAILABLE" ? (
                        <Badge tone="warning">예산 확인</Badge>
                      ) : null}
                      <br />
                      {causeLabel(row.main_cause)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            {rows.length === 0 && (
              <div className={table.empty}>
                조건에 맞는 결과가 없습니다.{" "}
                <Button data-testid="project-costs-empty-reset" onClick={reset}>
                  조건 초기화
                </Button>
              </div>
            )}
          </div>
        )
      )}
      <div className={table.actions}>
        <Button
          data-testid="project-costs-previous-page"
          disabled={!list.data || list.data.page <= 0}
          onClick={() => apply({ ...filters, page: (filters.page ?? 0) - 1 })}
        >
          이전
        </Button>
        <span>{(list.data?.page ?? 0) + 1}</span>
        <Button
          data-testid="project-costs-next-page"
          disabled={
            !list.data ||
            (list.data.page + 1) * list.data.size >= list.data.total
          }
          onClick={() => apply({ ...filters, page: (filters.page ?? 0) + 1 })}
        >
          다음
        </Button>
        <select
          aria-label="한 화면 건수"
          data-testid="project-costs-page-size"
          value={filters.size}
          onChange={(e) =>
            apply({
              ...filters,
              size: Number(e.target.value) as 20 | 50 | 100,
              page: 0,
            })
          }
        >
          {[20, 50, 100].map((size) => (
            <option key={size} value={size}>
              {size}건씩
            </option>
          ))}
        </select>
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
                <td>누적 매출</td>
                <td>확정 청구 공급가액 합</td>
                <td>개인 참고매출·VAT·입금 제외</td>
              </tr>
              <tr>
                <td>누적 원가</td>
                <td>배분 인건비 + 직접 경비</td>
                <td>무상지원 원가 이미 포함</td>
              </tr>
              <tr>
                <td>예산 소진율</td>
                <td>누적 원가 ÷ 저장된 목표 원가 × 100</td>
                <td>80% 주의 · 100% 위험</td>
              </tr>
              <tr>
                <td>저마진</td>
                <td>목표 마진율보다 5%p 초과 낮음</td>
                <td>분모·원천 누락 시 –</td>
              </tr>
            </tbody>
          </table>
        </div>
      </details>
      {selected != null && (
        <ProjectCostDrawer
          key={`${selected}-${filters.year}`}
          id={selected}
          year={filters.year}
          onClose={() => setSelected(null)}
        />
      )}
    </section>
  );
}

function ProjectCostDrawer({
  id,
  year,
  onClose,
}: {
  id: number;
  year: number;
  onClose: () => void;
}) {
  const read = useAdminRead(
    useCallback(() => getCostProject<CostProjectDetail>(id, year), [id, year]),
  );
  const [tab, setTab] = useState("월별 원가");
  return (
    <div className={table.scrim}>
      <aside
        className={table.drawer}
        role="dialog"
        aria-modal="true"
        aria-label="프로젝트 원가 상세"
        data-testid="project-costs-drawer"
        onKeyDown={(e) => {
          if (e.key === "Escape") onClose();
        }}
      >
        <header className={table.drawerHeader}>
          <h2>{read.data?.project.prj_nm ?? "프로젝트 원가 상세"}</h2>
          <Button data-testid="project-costs-close" onClick={onClose}>
            닫기
          </Button>
        </header>
        <Link
          data-testid="project-costs-contract-link"
          to={`/UI-01?open=${encodeURIComponent(read.data?.project.prj_no ?? "")}`}
        >
          프로젝트 상세로 이동
        </Link>
        <div className={styles.tabs} role="tablist">
          {["월별 원가", "투입 인력", "시뮬레이션", "원가 초과 원인"].map(
            (name, index) => (
              <Button
                key={name}
                role="tab"
                aria-selected={tab === name}
                data-testid={`project-costs-tab-${index}`}
                onClick={() => setTab(name)}
              >
                {name}
              </Button>
            ),
          )}
        </div>
        <ErrorBanner
          error={read.error}
          onRetry={() => void read.reload().catch(() => {})}
        />
        {read.loading ? (
          <Skeleton />
        ) : (
          !read.error &&
          read.data && (
            <div role="tabpanel" data-testid="project-costs-detail-panel">
              {read.data.warnings.map((w) => (
                <p className={styles.warning} key={w}>
                  {w}
                </p>
              ))}
              {tab === "원가 초과 원인" ? (
                <UI04CausesPanel projectId={id} year={year} />
              ) : tab === "시뮬레이션" ? (
                <ProjectCostSimulation
                  id={id}
                  type={read.data.project.cntrct_ty_cd}
                  locked={
                    year < Number(read.data.referenceMonth.slice(0, 4)) ||
                    read.data.monthly_rows.at(-1)?.status === "CLOSED"
                  }
                />
              ) : tab === "월별 원가" ? (
                <>
                  <p>
                    턴키 배분 기준 참고매출:{" "}
                    {fmt(read.data.reference_revenue_amt)}원 (확정 청구 매출에
                    합산하지 않음)
                  </p>
                  <div className={table.tableScroll}>
                    <table className={table.table}>
                      <thead>
                        <tr>
                          {[
                            "월",
                            "마감",
                            "확정 청구",
                            "인건비",
                            "경비",
                            "무상지원 원가",
                            "원가",
                            "마진율",
                            "예산 잔여",
                          ].map((label) => (
                            <th key={label}>{label}</th>
                          ))}
                        </tr>
                      </thead>
                      <tbody>
                        {read.data.monthly_rows.map((m) => (
                          <tr
                            key={m.month}
                            data-testid={`project-cost-month-${m.month}`}
                          >
                            <td>{m.month}</td>
                            <td>
                              <Badge
                                tone={m.status === "CLOSED" ? "locked" : "info"}
                              >
                                {m.status === "CLOSED" ? "마감" : "미마감"}
                              </Badge>
                            </td>
                            {[
                              m.confirmed_supply_amt,
                              m.lbr_cst_amt,
                              m.drct_expns_amt,
                              m.free_cost_amt,
                              m.total_cost_amt,
                              m.margin_rt,
                              m.budget_remaining_amt,
                            ].map((v, i) => (
                              <td className={table.amount} key={i}>
                                {fmt(v, i === 5 ? 1 : 0)}
                              </td>
                            ))}
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                </>
              ) : (
                <div className={table.tableScroll}>
                  <table className={table.table}>
                    <thead>
                      <tr>
                        {[
                          "월",
                          "이름",
                          "MM",
                          "배분 인건비",
                          "경비",
                          "개인 참고매출",
                          "무상지원",
                        ].map((label) => (
                          <th key={label}>{label}</th>
                        ))}
                      </tr>
                    </thead>
                    <tbody>
                      {read.data.people_rows.map((p, i) => (
                        <tr
                          key={`${p.month}-${p.dev_id}-${p.asgn_id ?? i}`}
                          data-testid={`project-cost-person-${i}`}
                        >
                          <td>{p.month}</td>
                          <td>{p.dev_nm}</td>
                          <td>{fmt(p.mm_qty, 2)}</td>
                          <td>{fmt(p.lbr_cst_amt)}</td>
                          <td>{fmt(p.drct_expns_amt)}</td>
                          <td>{fmt(p.personal_revenue_amt)}</td>
                          <td>{p.free_sprt_yn === "Y" ? "무상" : "–"}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                  {read.data.people_rows.length === 0 && (
                    <p className={table.empty}>투입 인력이 없습니다.</p>
                  )}
                </div>
              )}
            </div>
          )
        )}
      </aside>
    </div>
  );
}
function ProjectCostSimulation({
  id,
  type,
  locked,
}: {
  id: number;
  type: string;
  locked: boolean;
}) {
  const [values, setValues] = useState({
    headcount_delta: "",
    monthly_cost_amt: "",
    months: "",
    monthly_price_amt: "",
  });
  const [error, setError] = useState<ApiFailure | null>(null),
    [result, setResult] = useState<CostSimulationResult | null>(null),
    [pending, setPending] = useState(false);
  const paid = type === "MO" || type === "DP";
  const fields = [
    ["headcount_delta", "인원 증감"],
    ["monthly_cost_amt", "1인 월 원가"],
    ["months", "적용 개월"],
    ...(paid ? [["monthly_price_amt", "1인 월 단가"]] : []),
  ] as [keyof typeof values, string][];
  if (locked)
    return (
      <p data-testid="project-simulation-locked">
        <Badge tone="locked">지난 해·마감 월은 조회만 가능합니다.</Badge>
      </p>
    );
  return (
    <form
      className={styles.simulation}
      data-testid="project-simulation-panel"
      onSubmit={async (event) => {
        event.preventDefault();
        setError(null);
        setResult(null);
        for (const [field] of fields)
          if (
            !/^-?\d+$/.test(values[field]) ||
            !Number.isSafeInteger(Number(values[field])) ||
            (field !== "headcount_delta" &&
              Number(values[field]) < (field === "months" ? 1 : 0))
          ) {
            setError(
              new ApiFailure(
                "정수 입력값과 범위를 확인하세요.",
                400,
                null,
                field,
                "VALIDATION_ERROR",
              ),
            );
            return;
          }
        const input: CostSimulationRequest = {
          headcount_delta: Number(values.headcount_delta),
          monthly_cost_amt: Number(values.monthly_cost_amt),
          months: Number(values.months),
        };
        if (paid) input.monthly_price_amt = Number(values.monthly_price_amt);
        setPending(true);
        try {
          setResult(await simulateCostProject(id, input));
        } catch (failure) {
          setError(
            failure instanceof ApiFailure
              ? failure
              : new ApiFailure("가상 계산에 실패했습니다.", null, null),
          );
        } finally {
          setPending(false);
        }
      }}
    >
      <p>가상 계산 · 실제 원가·계약·청구를 변경하거나 저장하지 않습니다.</p>
      {fields.map(([field, label]) => (
        <label key={field}>
          {label}
          <input
            data-testid={`project-simulation-${field.replaceAll("_", "-")}`}
            inputMode="numeric"
            value={values[field]}
            aria-invalid={error?.field === field}
            onChange={(e) => {
              setValues({ ...values, [field]: e.target.value });
              setResult(null);
            }}
          />
          {error?.field === field && <span role="alert">{error.message}</span>}
        </label>
      ))}
      <ErrorBanner error={error} />
      <Button
        data-testid="project-simulation-calculate"
        type="submit"
        pending={pending}
        variant="primary"
      >
        가상 계산
      </Button>
      {result && (
        <output data-testid="project-simulation-result">
          <Badge tone="info">가상 · DB 미저장</Badge>
          <p>
            원가 증감 {fmt(result.cost_delta_amt)}원 · 매출 증감{" "}
            {fmt(result.revenue_delta_amt)}원 · 이익 증감{" "}
            {fmt(result.profit_delta_amt)}원
          </p>
        </output>
      )}
    </form>
  );
}
