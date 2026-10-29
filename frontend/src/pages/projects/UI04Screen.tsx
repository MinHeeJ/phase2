import { useCallback, useEffect, useState } from "react";
import { Link, useLocation } from "react-router-dom";
import { Badge, Button, Skeleton } from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { MmGapJudge } from "../../shared/MmGapJudge";
import { getMmGapTolerance } from "../../shared/mmGapParameters";
import { useAdminRead } from "../admin/useAdminResource";
import { ScreenHeading } from "../shared/ScreenHeading";
import { PeriodBar } from "../shared/PeriodBar";
import { useScreenPeriod } from "../shared/useScreenPeriod";
import { useCodes } from "../shared/useCodes";
import { listSalesPartOptions } from "../salespartsops/client";
import {
  costProjectDefaults,
  getCostProject,
  getCostProjectCauses,
  listCostProjects,
  type CostProjectRow,
} from "./profitClient";
import table from "../developers/Developers.module.css";
import styles from "./Projects.module.css";

const money = (value: number | null | undefined) =>
  value == null ? "–" : value.toLocaleString("ko-KR");
const percent = (value: number | null) =>
  value == null ? "–" : `${value.toFixed(1)}%`;
const causeNames: Record<string, string> = {
  STAFFING: "투입 초과",
  PRICING: "단가 대비 원가",
  FREE: "무상지원",
};

// SCR-UI-04: annual project profitability, read-only detail and shared cause API.
export default function UI04Screen({
  name = "프로젝트 원가 분석",
  groupName = "원가·수익 분석",
}: {
  name?: string;
  groupName?: string;
}) {
  const [period] = useScreenPeriod("UI-04");
  const year = Number(period.slice(0, 4));
  const location = useLocation();
  const [filters, setFilters] = useState(() => costProjectDefaults(year));
  const [draft, setDraft] = useState(filters);
  const [selected, setSelected] = useState<number | null>(null);
  useEffect(() => {
    setFilters((old) => ({ ...old, year, page: 0 }));
    setSelected(null);
  }, [year]);
  useEffect(() => {
    if (location.pathname !== "/UI-04") return;
    const value = new URLSearchParams(location.search).get("open");
    if (
      value &&
      /^[1-9][0-9]*$/.test(value) &&
      Number.isSafeInteger(Number(value))
    )
      setSelected(Number(value));
  }, [location.pathname, location.search]);
  const list = useAdminRead(
    useCallback(() => listCostProjects({ ...filters, year }), [filters, year]),
  );
  const types = useCodes("CONTRACT_TYPE"),
    statuses = useCodes("PROJECT_STATUS");
  const parts = useAdminRead(listSalesPartOptions);
  const tolerance = useAdminRead(getMmGapTolerance);
  const apply = () => setFilters({ ...draft, year, page: 0 });
  const sort = (key: string) =>
    setFilters((old) => ({
      ...old,
      page: 0,
      sort: `${key},${old.sort === `${key},asc` ? "desc" : "asc"}`,
    }));
  return (
    <section data-testid="cost-projects-screen" data-screen-id="SCR-UI-04">
      <ScreenHeading
        screenId="UI-04"
        name={name}
        groupName={groupName}
        actions={
          <Button
            data-testid="cost-projects-download"
            disabled
            title="다운로드 API 바인딩과 열 매핑 승인 대기"
          >
            엑셀 다운로드
          </Button>
        }
      />
      <PeriodBar screenId="UI-04" />
      <p className={table.help}>
        다운로드 API 바인딩과 열 매핑 승인 전에는 다운로드할 수 없습니다.
      </p>
      {list.data && (
        <div className={table.fields} data-testid="cost-projects-summary">
          {Object.entries({
            "누적 매출": money(list.data.summary.revenue),
            "누적 이익": money(list.data.summary.profit),
            저마진: `${list.data.summary.low_margin_count}건`,
            "예산 초과": `${list.data.summary.budget_risk_count}건`,
            연구개발비: money(list.data.summary.rnd_cost),
          }).map(([label, value], index) => (
            <div
              className={table.card}
              key={label}
              data-testid={`cost-projects-summary-${index}`}
            >
              <span>{label}</span>
              <strong> {value}</strong>
            </div>
          ))}
        </div>
      )}
      <form
        className={table.search}
        onSubmit={(event) => {
          event.preventDefault();
          apply();
        }}
      >
        <label>
          계약유형
          <select
            aria-label="계약유형"
            data-testid="cost-projects-type"
            value={draft.type}
            onChange={(event) =>
              setDraft({ ...draft, type: event.target.value })
            }
          >
            <option value="">전체</option>
            {types.codes.map((code) => (
              <option key={code.code} value={code.code}>
                {code.name}
              </option>
            ))}
          </select>
        </label>
        <label>
          상태
          <select
            aria-label="상태"
            data-testid="cost-projects-status"
            value={draft.status}
            onChange={(event) =>
              setDraft({ ...draft, status: event.target.value })
            }
          >
            <option value="">전체</option>
            {statuses.codes.map((code) => (
              <option key={code.code} value={code.code}>
                {code.name}
              </option>
            ))}
          </select>
        </label>
        <label>
          영업파트
          <select
            aria-label="영업파트"
            data-testid="cost-projects-part"
            value={draft.partId}
            onChange={(event) =>
              setDraft({ ...draft, partId: event.target.value })
            }
          >
            <option value="">전체</option>
            {parts.data?.map((part) => (
              <option key={part.id} value={part.id}>
                {part.name}
              </option>
            ))}
          </select>
        </label>
        <label>
          검색
          <input
            data-testid="cost-projects-search"
            value={draft.q}
            maxLength={200}
            placeholder="프로젝트명·번호"
            onChange={(event) => setDraft({ ...draft, q: event.target.value })}
          />
        </label>
        <Button
          data-testid="cost-projects-reset"
          type="button"
          onClick={() => {
            const defaults = costProjectDefaults(year);
            setDraft(defaults);
            setFilters(defaults);
          }}
        >
          초기화
        </Button>
        <Button
          data-testid="cost-projects-query"
          type="submit"
          variant="primary"
        >
          조회
        </Button>
      </form>
      {[types, statuses].map((state, index) => (
        <ErrorBanner key={index} error={state.error} onRetry={state.retry} />
      ))}
      {[parts, tolerance].map((state, index) => (
        <ErrorBanner
          key={index}
          error={state.error}
          onRetry={() => void state.reload().catch(() => {})}
        />
      ))}
      <ErrorBanner
        error={list.error}
        onRetry={() => void list.reload().catch(() => {})}
      />
      {list.error?.field && (
        <p role="alert">
          {list.error.field}: {list.error.message}
        </p>
      )}
      {list.loading ? (
        <Skeleton label="프로젝트 원가 불러오는 중" />
      ) : (
        !list.error &&
        list.data && (
          <>
            <p>
              {list.data.through} 기준 · 총 {list.data.total}건
            </p>
            <div className={table.tableScroll}>
              <table className={table.table}>
                <thead>
                  <tr>
                    {[
                      "프로젝트",
                      "유형",
                      "원 계약금액",
                      "현재 계약금액",
                      "계획 / 실제 MM",
                      "누적 매출",
                      "누적 원가",
                      "이익",
                      "누적 마진",
                      "예상 최종 / 월간 마진",
                      "목표 대비",
                      "예산 소진",
                      "MM당 단가 / 원가 / 이익",
                      "무상지원 MM / 원가",
                      "경보 / 주원인",
                    ].map((label, index) => (
                      <th key={label}>
                        {[0, 6, 8, 11].includes(index) ? (
                          <button
                            data-testid={`cost-projects-sort-${index}`}
                            onClick={() =>
                              sort(
                                (
                                  {
                                    0: "prj_nm",
                                    6: "cost",
                                    8: "margin",
                                    11: "budget_rate",
                                  } as Record<number, string>
                                )[index],
                              )
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
                  {list.data.items.map((row) => (
                    <ProjectProfitRow
                      key={row.project.prj_id}
                      row={row}
                      tolerance={tolerance.data}
                      onSelect={() => setSelected(row.project.prj_id)}
                    />
                  ))}
                  {list.data.items.length === 0 && (
                    <tr data-testid="cost-projects-empty-row">
                      <td colSpan={15} className={table.empty}>
                        조회 결과가 없습니다. 조건을 바꿔 다시 조회하세요.
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>
            <div className={table.actions}>
              <Button
                data-testid="cost-projects-previous"
                disabled={filters.page === 0}
                onClick={() =>
                  setFilters({ ...filters, page: filters.page - 1 })
                }
              >
                이전
              </Button>
              <span>{filters.page + 1} 페이지</span>
              <Button
                data-testid="cost-projects-next"
                disabled={(filters.page + 1) * filters.size >= list.data.total}
                onClick={() =>
                  setFilters({ ...filters, page: filters.page + 1 })
                }
              >
                다음
              </Button>
            </div>
          </>
        )
      )}
      <h2>계산 방법</h2>
      <div className={table.tableScroll}>
        <table className={table.table}>
          <thead>
            <tr>
              <th>지표</th>
              <th>계산 / 출처</th>
            </tr>
          </thead>
          <tbody>
            <tr data-testid="cost-projects-method-revenue">
              <td>누적 매출</td>
              <td>
                확정 청구 공급가액 (음수 수정 청구 포함), 입금·VAT·개인 참고매출
                제외
              </td>
            </tr>
            <tr data-testid="cost-projects-method-cost">
              <td>총원가</td>
              <td>
                공통 배분 인건비 + 경비 1회. 무상지원 포함, 마감 월은 스냅샷만
                조회
              </td>
            </tr>
            <tr data-testid="cost-projects-method-budget">
              <td>예산 / 저마진</td>
              <td>
                저장된 목표 원가, 80% 주의·100% 위험. 마진이 목표보다 5%p 초과
                낮을 때 저마진
              </td>
            </tr>
            <tr data-testid="cost-projects-method-period">
              <td>기간 / 예상</td>
              <td>
                연초~기준월 누적. TK 남은 계획 인력×MM×최근 월 원가, 근거 없으면
                계산 불가
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      {selected != null && (
        <ProjectProfitDrawer
          key={`${selected}-${year}`}
          id={selected}
          year={year}
          onClose={() => setSelected(null)}
        />
      )}
    </section>
  );
}
function ProjectProfitRow({
  row,
  tolerance,
  onSelect,
}: {
  row: CostProjectRow;
  tolerance: number | null;
  onSelect: () => void;
}) {
  const internal = row.project.cntrct_ty_cd === "IN";
  return (
    <tr data-testid={`cost-projects-row-${row.project.prj_id}`}>
      <td>
        <Button
          variant="text"
          data-testid={`cost-projects-open-${row.project.prj_id}`}
          onClick={onSelect}
        >
          {row.project.prj_nm}
        </Button>
      </td>
      <td>
        <Badge tone="info">{row.project.cntrct_ty_cd}</Badge>
      </td>
      <td className={table.amount}>
        {money(internal ? null : row.project.init_cntrct_amt)}
      </td>
      <td className={table.amount}>
        {money(internal ? null : row.project.cur_cntrct_amt)}
      </td>
      <td>
        {row.plan_mm.toFixed(2)} / {row.actual_mm.toFixed(2)}{" "}
        {tolerance != null && (
          <Badge
            tone={
              MmGapJudge(row.plan_mm, row.actual_mm, tolerance) === "OVER"
                ? "warning"
                : "neutral"
            }
          >
            {MmGapJudge(row.plan_mm, row.actual_mm, tolerance)}
          </Badge>
        )}
      </td>
      <td className={table.amount}>{money(row.revenue)}</td>
      <td className={table.amount}>
        {money(row.cost)}
        {internal && " 연구개발비"}
      </td>
      <td className={table.amount}>{money(row.profit)}</td>
      <td>{percent(row.margin)}</td>
      <td>{percent(row.estimated_margin)}</td>
      <td>
        {row.target_difference == null
          ? "–"
          : `${row.target_difference.toFixed(1)}%p`}
      </td>
      <td>{percent(row.budget_rate)}</td>
      <td>
        {money(row.revenue_per_mm)} / {money(row.cost_per_mm)} /{" "}
        {money(row.profit_per_mm)}
      </td>
      <td>
        {row.free_mm.toFixed(2)} / {money(row.free_cost)}
      </td>
      <td>
        {row.low_margin && <Badge tone="danger">저마진</Badge>}
        {row.budget_alert === "WARN" && <Badge tone="warning">예산 주의</Badge>}
        {row.budget_alert === "RISK" && <Badge tone="danger">예산 초과</Badge>}
        {row.warnings.map((warning, index) => (
          <p
            key={warning}
            data-testid={`cost-projects-warning-${row.project.prj_id}-${index}`}
          >
            {warning}
          </p>
        ))}
        {row.primary_cause && causeNames[row.primary_cause]}
      </td>
    </tr>
  );
}
function ProjectProfitDrawer({
  id,
  year,
  onClose,
}: {
  id: number;
  year: number;
  onClose: () => void;
}) {
  const detail = useAdminRead(
    useCallback(() => getCostProject(id, year), [id, year]),
  );
  const [tab, setTab] = useState("months");
  const [scenario, setScenario] = useState({
    people: "",
    cost: "",
    months: "",
    price: "",
  });
  const causes = useAdminRead(
    useCallback(
      () =>
        tab === "causes"
          ? getCostProjectCauses(id, year)
          : Promise.resolve(null),
      [id, year, tab],
    ),
  );
  const row = detail.data;
  const internal = row?.summary.project.cntrct_ty_cd === "IN";
  return (
    <div className={table.scrim}>
      <aside
        role="dialog"
        aria-modal="true"
        aria-label="프로젝트 원가 상세"
        className={styles.drawer}
        data-testid="cost-project-detail"
      >
        <div className={table.drawerHeader}>
          <h2>{row?.summary.project.prj_nm ?? "프로젝트 원가 상세"}</h2>
          <Button data-testid="cost-project-detail-close" onClick={onClose}>
            닫기
          </Button>
        </div>
        <ErrorBanner
          error={detail.error}
          onRetry={() => void detail.reload().catch(() => {})}
        />
        {detail.error?.field && (
          <p>
            {detail.error.field}: {detail.error.message}
          </p>
        )}
        {detail.loading ? (
          <Skeleton />
        ) : (
          !detail.error &&
          row && (
            <>
              <p>{row.period_note}</p>
              <p>
                계약금액{" "}
                {money(internal ? null : row.summary.project.cur_cntrct_amt)} ·
                실적 진척 {percent(row.summary.project.actl_prgs_rt)}
              </p>
              <Link
                data-testid="cost-project-contract-link"
                className={table.linkButton}
                to={`/UI-01?open=${id}`}
              >
                프로젝트 상세
              </Link>
              {row.summary.warnings.map((warning, index) => (
                <p
                  className={styles.warning}
                  key={warning}
                  data-testid={`cost-project-detail-warning-${index}`}
                >
                  {warning}
                </p>
              ))}
              <div role="tablist" className={styles.tabs}>
                {[
                  { key: "months", label: "월별 원가" },
                  { key: "people", label: "투입 인력" },
                  { key: "simulate", label: "시뮬레이션" },
                  { key: "causes", label: "원가 초과 원인" },
                ].map((item) => (
                  <Button
                    key={item.key}
                    role="tab"
                    aria-selected={tab === item.key}
                    data-testid={`cost-project-tab-${item.key}`}
                    onClick={() => setTab(item.key)}
                  >
                    {item.label}
                  </Button>
                ))}
              </div>
              <section
                role="tabpanel"
                data-testid={`cost-project-panel-${tab}`}
              >
                {tab === "months" && (
                  <div className={table.tableScroll}>
                    <table className={table.table}>
                      <thead>
                        <tr>
                          {[
                            "월",
                            "확정 청구 매출",
                            "배분 인건비",
                            "경비",
                            "무상지원",
                            "총원가",
                            "마진",
                            "예산 잔여",
                            "턴키 참고매출",
                            "상태",
                          ].map((label) => (
                            <th key={label}>{label}</th>
                          ))}
                        </tr>
                      </thead>
                      <tbody>
                        {row.months.map((month) => (
                          <tr
                            key={month.month}
                            data-testid={`cost-project-month-${month.month}`}
                          >
                            <td>{month.month}</td>
                            {[
                              month.revenue,
                              month.labor,
                              month.expenses,
                              month.free_cost,
                              month.cost,
                            ].map((value, index) => (
                              <td key={index} className={table.amount}>
                                {money(value)}
                              </td>
                            ))}
                            <td>{percent(month.margin)}</td>
                            <td className={table.amount}>
                              {money(month.budget_remaining)}
                            </td>
                            <td className={table.amount}>
                              {row.summary.project.cntrct_ty_cd === "TK"
                                ? money(month.reference_revenue)
                                : "–"}
                            </td>
                            <td>
                              <Badge tone={month.closed ? "locked" : "info"}>
                                {month.closed ? "마감" : "잠정"}
                              </Badge>
                              {month.warnings.join(" · ")}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
                {tab === "people" && (
                  <div className={table.tableScroll}>
                    <table className={table.table}>
                      <thead>
                        <tr>
                          {[
                            "사람",
                            "월",
                            "MM",
                            "배분 인건비",
                            "경비",
                            "개인 참고매출",
                            "구분",
                          ].map((label) => (
                            <th key={label}>{label}</th>
                          ))}
                        </tr>
                      </thead>
                      <tbody>
                        {row.people.map((person, index) => (
                          <tr
                            key={`${person.month}-${person.asgn_id}-${index}`}
                            data-testid={`cost-project-person-${index}`}
                          >
                            <td>{person.dev_nm ?? person.dev_id}</td>
                            <td>{person.month}</td>
                            <td>{person.mm_qty.toFixed(2)}</td>
                            <td className={table.amount}>
                              {money(person.lbr_cst_amt)}
                            </td>
                            <td className={table.amount}>
                              {money(person.drct_expns_amt)}
                            </td>
                            <td className={table.amount}>
                              {money(person.reference_revenue)}
                            </td>
                            <td>
                              {person.free_sprt && (
                                <Badge tone="warning">무상지원</Badge>
                              )}
                              {person.pre_cntrct && (
                                <Badge tone="warning">미계약 투입</Badge>
                              )}
                            </td>
                          </tr>
                        ))}
                        {row.people.length === 0 && (
                          <tr data-testid="cost-project-people-empty">
                            <td colSpan={7} className={table.empty}>
                              투입 인력이 없습니다.
                            </td>
                          </tr>
                        )}
                      </tbody>
                    </table>
                  </div>
                )}
                {tab === "simulate" && (
                  <>
                    <p className={styles.warning}>
                      시뮬레이션 요청·응답 필드 승인 대기: 가상 계산을 실행할 수
                      없습니다. 입력값은 저장하지 않으며 탭 이동 시 유지됩니다.
                    </p>
                    <div className={table.search}>
                      {[
                        { key: "people", label: "인원 증감" },
                        { key: "cost", label: "1인 월 원가" },
                        { key: "months", label: "적용 개월" },
                        ...(["MO", "DP"].includes(
                          row.summary.project.cntrct_ty_cd,
                        )
                          ? [{ key: "price", label: "1인 단가" }]
                          : []),
                      ].map((field) => (
                        <label key={field.key}>
                          {field.label}
                          <input
                            type="number"
                            data-testid={`cost-project-scenario-${field.key}`}
                            value={scenario[field.key as keyof typeof scenario]}
                            onChange={(event) =>
                              setScenario({
                                ...scenario,
                                [field.key]: event.target.value,
                              })
                            }
                          />
                        </label>
                      ))}
                      <Button disabled data-testid="cost-project-simulate">
                        가상 계산
                      </Button>
                    </div>
                  </>
                )}
                {tab === "causes" && (
                  <>
                    <ErrorBanner
                      error={causes.error}
                      onRetry={() => void causes.reload().catch(() => {})}
                    />
                    {causes.error?.field && (
                      <p>
                        {causes.error.field}: {causes.error.message}
                      </p>
                    )}
                    {causes.loading ? (
                      <Skeleton />
                    ) : (
                      !causes.error &&
                      causes.data && (
                        <>
                          <p>
                            진척 대비 초과 {money(causes.data.progress_excess)}{" "}
                            · 설명 안 되는 나머지 {money(causes.data.residual)}
                          </p>
                          <p>
                            원인 금액은 설명 지표이며 총원가에 다시 더하지
                            않습니다.
                          </p>
                          <div className={table.tableScroll}>
                            <table className={table.table}>
                              <thead>
                                <tr>
                                  {[
                                    "원인",
                                    "금액",
                                    "산출 근거",
                                    "담당",
                                    "권장 조치",
                                  ].map((label) => (
                                    <th key={label}>{label}</th>
                                  ))}
                                </tr>
                              </thead>
                              <tbody>
                                {causes.data.items.map((cause) => (
                                  <tr
                                    key={cause.code}
                                    data-testid={`cost-project-cause-${cause.code.toLowerCase()}`}
                                  >
                                    <td>
                                      {cause.label}
                                      {cause.code ===
                                        causes.data?.primary_cause && (
                                        <Badge tone="danger">주원인</Badge>
                                      )}
                                      {cause.amount === 0 && (
                                        <Badge tone="neutral">보조 정보</Badge>
                                      )}
                                    </td>
                                    <td className={table.amount}>
                                      {money(cause.amount)}
                                    </td>
                                    <td>{cause.basis}</td>
                                    <td>{cause.owner}</td>
                                    <td>
                                      {cause.recommendation}
                                      {cause.code === "STAFFING" && (
                                        <Link
                                          className={table.linkButton}
                                          data-testid="cost-project-plan-link"
                                          to={`/UI-11?projectId=${id}&year=${year}`}
                                        >
                                          인원 계획 대비 투입
                                        </Link>
                                      )}
                                      {cause.code === "FREE" && (
                                        <Button
                                          data-testid="cost-project-analysis-link"
                                          onClick={() => setTab("months")}
                                        >
                                          프로젝트 원가 분석
                                        </Button>
                                      )}
                                      {cause.unavailable && (
                                        <p>{cause.unavailable}</p>
                                      )}
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
              </section>
            </>
          )
        )}
      </aside>
    </div>
  );
}
