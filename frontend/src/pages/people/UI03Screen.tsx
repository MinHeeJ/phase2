import { Fragment, useEffect, useState } from "react";
import { ApiFailure } from "../../shared/api";
import { Badge, Button } from "../../shared/Controls";
import { PeriodBar } from "../shared/PeriodBar";
import { useScreenPeriod } from "../shared/useScreenPeriod";
import { ScreenHeading } from "../shared/ScreenHeading";
import { useCodes } from "../shared/useCodes";
import {
  listSalesPartOptions,
  type SalesPartOption,
} from "../salespartsops/client";
import { listPartnerOptions } from "../partnersops/client";
import {
  defaultPeopleFilters,
  getCostPersonBreakdown,
  listCostPeople,
  type PeopleData,
  type PeopleFilters,
  type PersonBreakdown,
} from "./client";
import styles from "../developers/Developers.module.css";

const amount = (value: number | null | undefined) =>
  value == null ? "–" : value.toLocaleString("ko-KR");
const ratio = (value: number | null | undefined) =>
  value == null ? "–" : `${value.toFixed(1)}%`;
function Failure({ error, retry }: { error: ApiFailure; retry: () => void }) {
  return (
    <div role="alert" data-testid="people-error">
      <p>
        {error.message}
        {error.field ? ` (${error.field})` : ""}
      </p>
      <Button data-testid="people-retry" onClick={retry}>
        다시 시도
      </Button>
    </div>
  );
}
function Breakdown({
  id,
  year,
  view,
}: {
  id: number;
  year: number;
  view: string;
}) {
  const [data, setData] = useState<PersonBreakdown | null>(null);
  const [error, setError] = useState<ApiFailure | null>(null);
  const [revision, setRevision] = useState(0);
  useEffect(() => {
    const controller = new AbortController();
    setData(null);
    setError(null);
    getCostPersonBreakdown(id, year, view, controller.signal)
      .then((value) => {
        if (!controller.signal.aborted) setData(value);
      })
      .catch((failure: ApiFailure) => {
        if (!controller.signal.aborted) setError(failure);
      });
    return () => controller.abort();
  }, [id, year, view, revision]);
  return (
    <section
      data-testid={`people-breakdown-${id}`}
      aria-label="프로젝트·대기 및 월별 근거"
    >
      <h3>프로젝트·대기 및 월별 근거</h3>
      <p>
        월별 근거는 실제 월 금액입니다. 월 평균 표시는 연 누계를 조회 기간 개월
        수로 나눕니다.
      </p>
      {error && (
        <Failure error={error} retry={() => setRevision((v) => v + 1)} />
      )}
      {!data && !error && <p role="status">근거 불러오는 중…</p>}
      {data && (
        <>
          <p>{data.warnings.join(" · ")}</p>
          <div className={styles.tableScroll}>
            <table className={styles.table}>
              <thead>
                <tr>
                  {[
                    "월",
                    "프로젝트·대기",
                    "유형",
                    "MM",
                    "단가",
                    "배분 인건비",
                    "경비",
                    "개인 참고매출",
                    "이익",
                    "경고",
                  ].map((title) => (
                    <th key={title}>{title}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {data.items.map((row, index) => (
                  <tr
                    key={`${row.month}-${row.asgn_id}-${index}`}
                    data-testid={`people-evidence-${id}-${index}`}
                  >
                    <td>{row.month}</td>
                    <td>{row.project}</td>
                    <td>{row.contract_type ?? row.classification}</td>
                    <td className={styles.amount}>{row.mm.toFixed(2)}</td>
                    {[
                      row.price,
                      row.labor,
                      row.expenses,
                      row.revenue,
                      row.profit,
                    ].map((value, i) => (
                      <td key={i} className={styles.amount}>
                        {amount(value)}
                      </td>
                    ))}
                    <td>
                      {row.overlap && <Badge tone="warning">1.0 초과</Badge>}
                      {row.free && <Badge tone="info">무상지원</Badge>}
                      {row.pre_contract && (
                        <Badge tone="warning">미계약 투입</Badge>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {data.items.length === 0 && (
            <p className={styles.empty}>월별 근거가 없습니다.</p>
          )}
        </>
      )}
    </section>
  );
}

// SCR-UI-03
export default function UI03Screen({
  name = "개인 원가·수익표",
  groupName = "원가·수익 분석",
}: {
  name?: string;
  groupName?: string;
  onDirtyChange?: (dirty: boolean) => void;
}) {
  const [period] = useScreenPeriod("UI-03");
  const year = Number(period.slice(0, 4));
  const [draft, setDraft] = useState(defaultPeopleFilters);
  const [filters, setFilters] = useState(defaultPeopleFilters);
  const [data, setData] = useState<PeopleData | null>(null);
  const [error, setError] = useState<ApiFailure | null>(null);
  const [revision, setRevision] = useState(0);
  const [expanded, setExpanded] = useState<number | null>(null);
  const [contribution, setContribution] = useState(false);
  const [parts, setParts] = useState<SalesPartOption[]>([]);
  const [vendors, setVendors] = useState<{ id: string; name: string }[]>([]);
  const [optionError, setOptionError] = useState(false);
  const employees = useCodes("EMP_TYPE"),
    jobs = useCodes("JOB"),
    contracts = useCodes("CONTRACT_TYPE");
  useEffect(() => {
    let active = true;
    setOptionError(false);
    Promise.all([listSalesPartOptions(), listPartnerOptions("VEND")])
      .then(([p, v]) => {
        if (active) {
          setParts(p);
          setVendors(v);
        }
      })
      .catch(() => {
        if (active) setOptionError(true);
      });
    return () => {
      active = false;
    };
  }, [revision]);
  useEffect(() => {
    const controller = new AbortController();
    setData(null);
    setError(null);
    setExpanded(null);
    listCostPeople(year, filters, controller.signal)
      .then((value) => {
        if (!controller.signal.aborted) setData(value);
      })
      .catch((failure: ApiFailure) => {
        if (!controller.signal.aborted) setError(failure);
      });
    return () => controller.abort();
  }, [year, filters, revision]);
  const reset = () => {
    setDraft(defaultPeopleFilters);
    setFilters(defaultPeopleFilters);
  };
  const update = <K extends keyof PeopleFilters>(
    key: K,
    value: PeopleFilters[K],
  ) => setDraft((current) => ({ ...current, [key]: value }));
  const select = (
    key: "empType" | "partId" | "vendorId" | "contractType" | "job",
    label: string,
    options: { value: string; label: string }[],
  ) => (
    <label>
      {label}
      <select
        data-testid={`people-filter-${key.replace(/[A-Z]/g, (c) => `-${c.toLowerCase()}`)}`}
        aria-label={label}
        value={draft[key]}
        onChange={(e) => update(key, e.target.value)}
      >
        <option value="">전체</option>
        {options.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </select>
    </label>
  );
  const summary = data?.summary;
  return (
    <main data-testid="people-screen" data-screen-id="SCR-UI-03">
      <ScreenHeading
        screenId="UI-03"
        name={name}
        groupName={groupName}
        actions={
          <Button
            data-testid="people-download"
            disabled
            title="승인된 다운로드 연결 및 B~W 열 매핑 대기"
          >
            엑셀 다운로드
          </Button>
        }
      />
      <PeriodBar screenId="UI-03" />
      <p className={styles.help}>
        다운로드는 승인된 HTTP 연결과 B~W 열 매핑이 제공되기 전까지 사용할 수
        없습니다.
      </p>
      {summary && (
        <section
          className={styles.search}
          data-testid="people-summary"
          aria-label="개인 원가 요약"
        >
          <span>
            총 인원 <strong>{summary.people}명</strong>
          </span>
          <span>월평균 가동 인원 {amount(summary.active_people)}</span>
          <span>월평균 가득 인원 {amount(summary.paid_people)}</span>
          <span>인당 매출(평균 단가) {amount(summary.average_price)}</span>
          <span>평균 원가 {amount(summary.average_cost)}</span>
          <span>평균 이익 {amount(summary.average_profit)}</span>
          <span>적자 인원 {summary.loss_people}명</span>
          <span>가동률 {ratio(summary.utilization)}</span>
          <span>가득률 {ratio(summary.earning_rate)}</span>
        </section>
      )}
      <Button
        data-testid="people-contribution-toggle"
        aria-expanded={contribution}
        onClick={() => setContribution((v) => !v)}
      >
        직무·등급별 인당 공헌이익 {contribution ? "접기" : "펼치기"}
      </Button>
      {contribution && (
        <section data-testid="people-contribution-panel">
          <div className={styles.tableScroll}>
            <table className={styles.table}>
              <thead>
                <tr>
                  <th>구분</th>
                  <th>직무·등급</th>
                  <th>인원</th>
                  <th>인당 공헌이익</th>
                </tr>
              </thead>
              <tbody>
                {data?.contributions.map((row) => (
                  <tr
                    key={`${row.dimension}-${row.name}`}
                    data-testid={`people-contribution-${row.dimension}-${encodeURIComponent(row.name).replace(/%/g, "").toLowerCase()}`}
                  >
                    <td>{row.dimension === "job" ? "직무" : "등급"}</td>
                    <td>{row.name}</td>
                    <td>{row.people}</td>
                    <td className={styles.amount}>
                      {amount(row.per_person_profit)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {!data?.contributions.length && (
            <p className={styles.empty}>공헌이익 자료가 없습니다.</p>
          )}
        </section>
      )}
      <form
        className={styles.search}
        onSubmit={(e) => {
          e.preventDefault();
          setFilters({ ...draft, page: 0 });
        }}
      >
        {select(
          "empType",
          "직원구분",
          employees.codes.map((c) => ({ value: c.code, label: c.name })),
        )}
        {select(
          "vendorId",
          "소속",
          vendors.map((c) => ({ value: c.id, label: c.name })),
        )}
        {select(
          "partId",
          "영업파트",
          parts.map((c) => ({ value: c.id, label: c.name })),
        )}
        {select(
          "contractType",
          "계약유형",
          contracts.codes.map((c) => ({ value: c.code, label: c.name })),
        )}
        {select(
          "job",
          "직무",
          jobs.codes.map((c) => ({ value: c.code, label: c.name })),
        )}
        <label>
          <input
            data-testid="people-loss-only"
            type="checkbox"
            checked={draft.lossOnly}
            onChange={(e) => update("lossOnly", e.target.checked)}
          />
          적자 인력만
        </label>
        <label>
          <input
            data-testid="people-overlap-only"
            type="checkbox"
            checked={draft.overlapOnly}
            onChange={(e) => update("overlapOnly", e.target.checked)}
          />
          동시 투입 1.0 초과만
        </label>
        <label>
          <input
            data-testid="people-exclude-turnkey"
            type="checkbox"
            checked={draft.excludeTurnkeyPrice}
            onChange={(e) => update("excludeTurnkeyPrice", e.target.checked)}
          />
          턴키 단가 제외
        </label>
        <Button data-testid="people-reset" type="button" onClick={reset}>
          초기화
        </Button>
        <Button data-testid="people-query" type="submit" variant="primary">
          조회
        </Button>
      </form>
      {(optionError || employees.error || jobs.error || contracts.error) && (
        <div role="alert">
          <p>필터 선택 목록을 불러오지 못했습니다.</p>
          <Button
            data-testid="people-options-retry"
            onClick={() => {
              employees.retry();
              jobs.retry();
              contracts.retry();
              setRevision((v) => v + 1);
            }}
          >
            선택 목록 다시 시도
          </Button>
        </div>
      )}
      <div className={styles.toolbar}>
        <div role="tablist" aria-label="집계 방식">
          {(["TOTAL", "AVERAGE"] as const).map((view) => (
            <Button
              key={view}
              role="tab"
              data-testid={`people-view-${view.toLowerCase()}`}
              aria-selected={filters.view === view}
              onClick={() => {
                update("view", view);
                setFilters((v) => ({ ...v, view, page: 0 }));
              }}
            >
              {view === "TOTAL" ? "연 누계" : "월 평균"}
            </Button>
          ))}
        </div>
        <label>
          정렬
          <select
            data-testid="people-sort"
            value={filters.sort}
            onChange={(e) =>
              setFilters((v) => ({ ...v, sort: e.target.value, page: 0 }))
            }
          >
            <option value="name,asc">이름 오름차순</option>
            <option value="name,desc">이름 내림차순</option>
            <option value="profit,asc">이익 낮은순</option>
            <option value="profit,desc">이익 높은순</option>
            <option value="cost,desc">원가 높은순</option>
          </select>
        </label>
      </div>
      {error && (
        <Failure error={error} retry={() => setRevision((v) => v + 1)} />
      )}
      {!data && !error && <p role="status">개인 원가 불러오는 중…</p>}
      {data && (
        <>
          <p>
            총 {data.total}명 · {year}-01 ~ {data.through}
          </p>
          {data.warnings.length > 0 && (
            <div role="alert" data-testid="people-warnings">
              <Badge tone="warning">결손·배분 점검</Badge>
              {[...new Set(data.warnings)].map((w, i) => (
                <p key={w} data-testid={`people-warning-${i}`}>
                  {w}
                </p>
              ))}
            </div>
          )}
          <div className={styles.tableScroll}>
            <table className={styles.table}>
              <thead>
                <tr>
                  {[
                    "이름",
                    "소속",
                    "직위",
                    "구분",
                    "프로젝트",
                    "기간",
                    "유형",
                    "MM",
                    "단가",
                    "급여",
                    "수당",
                    "보험 회사분",
                    "퇴직금",
                    "개인원가",
                    "직접경비",
                    "총원가",
                    "개인 참고매출",
                    "이익",
                    "이익률",
                  ].map((title) => (
                    <th key={title}>{title}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {data.items.map((row) => (
                  <Fragment key={row.dev_id}>
                    <tr data-testid={`people-row-${row.dev_id}`}>
                      <td>
                        <button
                          type="button"
                          className={styles.textButton}
                          data-testid={`people-expand-${row.dev_id}`}
                          aria-expanded={expanded === row.dev_id}
                          onClick={() =>
                            setExpanded((v) =>
                              v === row.dev_id ? null : row.dev_id,
                            )
                          }
                        >
                          {row.name} {expanded === row.dev_id ? "▾" : "▸"}
                        </button>
                        {row.overlap && <Badge tone="warning">1.0 초과</Badge>}
                        {row.on_leave && <Badge>휴직</Badge>}
                      </td>
                      <td>{row.affiliation}</td>
                      <td>{row.position ?? "–"}</td>
                      <td>
                        {employees.codes.find((c) => c.code === row.emp_type)
                          ?.name ?? row.emp_type}
                      </td>
                      <td>{row.projects || "–"}</td>
                      <td>{row.period}</td>
                      <td>{row.contract_types || "–"}</td>
                      <td className={styles.amount}>{row.mm.toFixed(2)}</td>
                      {[
                        row.price,
                        row.salary,
                        row.allowance,
                        row.insurance,
                        row.retirement,
                        row.labor,
                        row.expenses,
                        row.cost,
                        row.revenue,
                        row.profit,
                      ].map((value, i) => (
                        <td key={i} className={styles.amount}>
                          {amount(value)}
                        </td>
                      ))}
                      <td className={styles.amount}>{ratio(row.margin)}</td>
                    </tr>
                    {expanded === row.dev_id && (
                      <tr data-testid={`people-expanded-row-${row.dev_id}`}>
                        <td colSpan={19}>
                          <Breakdown
                            id={row.dev_id}
                            year={year}
                            view={filters.view}
                          />
                        </td>
                      </tr>
                    )}
                  </Fragment>
                ))}
              </tbody>
              <tfoot>
                <tr>
                  <th colSpan={15}>필터 전체 합계</th>
                  <td className={styles.amount}>{amount(summary?.cost)}</td>
                  <td className={styles.amount}>{amount(summary?.revenue)}</td>
                  <td className={styles.amount}>{amount(summary?.profit)}</td>
                  <td>–</td>
                </tr>
              </tfoot>
            </table>
          </div>
          {data.total === 0 && (
            <div className={styles.empty}>
              조건에 맞는 결과가 없습니다.
              <Button data-testid="people-empty-reset" onClick={reset}>
                조건 초기화
              </Button>
            </div>
          )}
          <div className={styles.actions}>
            <Button
              data-testid="people-previous"
              disabled={data.page === 0}
              onClick={() => setFilters((v) => ({ ...v, page: v.page - 1 }))}
            >
              이전
            </Button>
            <span>{data.page + 1}페이지</span>
            <Button
              data-testid="people-next"
              disabled={(data.page + 1) * data.size >= data.total}
              onClick={() => setFilters((v) => ({ ...v, page: v.page + 1 }))}
            >
              다음
            </Button>
            <label>
              목록 건수
              <select
                data-testid="people-page-size"
                value={filters.size}
                onChange={(e) =>
                  setFilters((v) => ({
                    ...v,
                    size: Number(e.target.value),
                    page: 0,
                  }))
                }
              >
                {[20, 50, 100].map((size) => (
                  <option key={size} value={size}>
                    {size}명
                  </option>
                ))}
              </select>
            </label>
          </div>
        </>
      )}
      <section data-testid="people-calculation-panel">
        <h2>계산 방법</h2>
        <div className={styles.tableScroll}>
          <table className={styles.table}>
            <thead>
              <tr>
                <th>항목</th>
                <th>계산 방법·출처</th>
              </tr>
            </thead>
            <tbody>
              <tr data-testid="people-rule-period">
                <th>연 누계 / 월 평균</th>
                <td>
                  1월~기준월(과거 연도 12월) 누계 / 같은 누계 ÷ 조회 개월 수.
                  급여 결손 달도 분모에 포함합니다.
                </td>
              </tr>
              <tr data-testid="people-rule-allocation">
                <th>원가 배분</th>
                <td>
                  월별 급여·경비와 투입 MM을 공통 엔진으로 배분. 정규·계약
                  잔여는 대기, 프리·외주는 실제 MM 분모. 마감 월은 스냅샷만
                  사용합니다.
                </td>
              </tr>
              <tr data-testid="people-rule-ratios">
                <th>가동률 / 가득률</th>
                <td>
                  월별 MM&gt;0 직접 인력 / 전체 직접 인력(대기·휴직 포함), MO/DP
                  유상 인력 / 전체 직접 인력의 월평균.
                </td>
              </tr>
              <tr data-testid="people-rule-averages">
                <th>평균·공헌이익</th>
                <td>
                  평균 단가: 단가 칸이 있는 사람(0 포함). 원가·이익 평균: 휴직
                  제외 인원. 턴키 제외 시 턴키 전용 인력의 이익은 −원가입니다. 0
                  분모는 –.
                </td>
              </tr>
              <tr data-testid="people-rule-revenue">
                <th>참고매출·경비</th>
                <td>
                  개인 참고매출은 확정 청구 회사 매출과 다릅니다. 프로젝트 전용
                  경비는 개인에게 중복 배분하지 않습니다. 원 단위 버림, MM 소수
                  둘째 자리, 비율 소수 첫째 자리.
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>
    </main>
  );
}
