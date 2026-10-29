import { Fragment, useCallback, useState } from "react";
import { Badge, Button, Skeleton } from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { ApiFailure } from "../../shared/api";
import { useAdminRead } from "../admin/useAdminResource";
import { ScreenHeading } from "../shared/ScreenHeading";
import { seoulMonth } from "../shared/PeriodBar";
import { useCodes } from "../shared/useCodes";
import { listSalesPartOptions } from "../salespartsops/client";
import { listPartnerOptions } from "../partnersops/client";
import type { CostPeopleFilters } from "../costprofit/client";
import {
  peopleDefaults,
  readPeople,
  readPersonBreakdown,
  savePeopleWorkbook,
  type PeopleRow,
} from "./client";
import table from "../developers/Developers.module.css";
import styles from "./People.module.css";

const moneyColumns = [
  ["bill_untprc_amt", "단가"],
  ["personal_revenue_amt", "매출"],
  ["slry_amt", "급여"],
  ["alwnc_amt", "그룹장·팀장 수당"],
  ["sins_cmpny_amt", "4대보험(회사)"],
  ["rtrpay_acml_amt", "퇴직금 적립"],
  ["indv_lbr_cst_amt", "개인별 원가"],
  ["team_expns_amt", "팀비"],
  ["project_expns_amt", "프로젝트경비"],
  ["total_cost_amt", "원가합계"],
  ["profit_amt", "영업이익"],
] as const;
const failure = (error: unknown) =>
  error instanceof ApiFailure
    ? error
    : new ApiFailure("조회에 실패했습니다.", null, null);
async function readOptions<T>(read: () => Promise<T[]>): Promise<T[]> {
  const options = await read();
  if (!Array.isArray(options))
    throw new ApiFailure("선택 항목 응답을 확인할 수 없습니다.", null, null);
  return options;
}
const number = (value: number | null | undefined, unit = 1, digits = 1) =>
  value == null
    ? "–"
    : (value / unit).toLocaleString("ko-KR", { maximumFractionDigits: digits });
const percent = (value: number | null | undefined) =>
  value == null ? "–" : `${number(value)}%`;

function PersonEvidence({
  row,
  filters,
  unit,
}: {
  row: PeopleRow;
  filters: CostPeopleFilters;
  unit: number;
}) {
  const result = useAdminRead(
    useCallback(
      () => readPersonBreakdown(row.dev_id, filters),
      [row.dev_id, filters],
    ),
  );
  return (
    <section
      aria-label={`${row.dev_nm} 배분 근거`}
      data-testid={`people-evidence-${row.dev_id}`}
    >
      {result.loading && <Skeleton label="배분 근거 불러오는 중" />}
      <ErrorBanner
        error={result.error}
        onRetry={() => void result.reload().catch(() => {})}
      />
      {result.data && (
        <>
          <h3>프로젝트·대기 및 월별 근거</h3>
          <div className={table.tableScroll}>
            <table className={table.table}>
              <thead>
                <tr>
                  {[
                    "월",
                    "상태",
                    "프로젝트·분류",
                    "MM",
                    "단가",
                    "배분 인건비",
                    "경비",
                    "개인 참고매출",
                    "이익",
                    "1.0 초과",
                  ].map((label) => (
                    <th key={label}>{label}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {result.data.monthly_rows.flatMap((month) =>
                  month.items.map((item, index) => {
                    const project = row.projects.find(
                      (p) => p.asgn_id === item.asgn_id,
                    );
                    const excluded =
                      filters.excludeTurnkeyPrice &&
                      project?.cntrct_ty_cd === "TK";
                    const revenue = excluded ? 0 : item.sls_amt;
                    const profit =
                      revenue == null || item.lbr_cst_amt == null
                        ? null
                        : revenue - item.lbr_cst_amt - item.drct_expns_amt;
                    return (
                      <tr
                        key={`${month.month}-${index}`}
                        data-testid={`people-evidence-${row.dev_id}-${month.month}-${index}`}
                      >
                        <td>{month.month}</td>
                        <td>
                          <Badge
                            tone={month.status === "CLOSED" ? "locked" : "info"}
                          >
                            {month.status === "CLOSED" ? "마감" : "미마감"}
                          </Badge>
                        </td>
                        <td>
                          {item.aloc_ty_cd === "IDLE" ? (
                            <Badge tone="danger">대기</Badge>
                          ) : item.aloc_ty_cd === "RND" ? (
                            <Badge tone="info">연구개발비</Badge>
                          ) : item.aloc_ty_cd === "INDIRECT" ? (
                            "간접"
                          ) : (
                            (project?.prj_nm ?? item.prj_id)
                          )}
                        </td>
                        <td className={table.amount}>
                          {number(item.mm_qty, 1, 2)}
                        </td>
                        <td className={table.amount}>
                          {number(excluded ? null : item.bill_untprc_amt, unit)}
                        </td>
                        <td className={table.amount}>
                          {number(item.lbr_cst_amt, unit)}
                        </td>
                        <td className={table.amount}>
                          {number(item.drct_expns_amt, unit)}
                        </td>
                        <td className={table.amount}>
                          {number(revenue, unit)}
                        </td>
                        <td className={table.amount}>{number(profit, unit)}</td>
                        <td>
                          {item.mm_ovr_yn === "Y" && (
                            <Badge tone="warning">1.0 초과</Badge>
                          )}
                        </td>
                      </tr>
                    );
                  }),
                )}
              </tbody>
            </table>
          </div>
          {result.data.items.length === 0 && (
            <p className={table.empty}>배분 근거가 없습니다.</p>
          )}
          {result.data.warnings.map((warning, index) => (
            <p key={index}>
              <Badge tone="warning">{warning}</Badge>
            </p>
          ))}
        </>
      )}
    </section>
  );
}

// SCR-UI-03: 개인 원가·수익표. AppShell supplies navigation and tabs.
export default function UI03Screen({
  name = "개인 원가·수익표",
  groupName = "원가·수익",
}: {
  name?: string;
  groupName?: string;
}) {
  const [filters, setFilters] = useState<CostPeopleFilters>(() => {
    const remembered = Number(sessionStorage.getItem("UI-03.year"));
    const current = Number(seoulMonth().slice(0, 4));
    return peopleDefaults(
      remembered >= 1 && remembered <= current ? remembered : current,
    );
  });
  const [draft, setDraft] = useState(filters);
  const [advanced, setAdvanced] = useState(false);
  const [expanded, setExpanded] = useState<number[]>([]);
  const [unit, setUnit] = useState(1000);
  const [downloadError, setDownloadError] = useState<ApiFailure | null>(null);
  const [downloading, setDownloading] = useState(false);
  const list = useAdminRead(useCallback(() => readPeople(filters), [filters]));
  const employees = useCodes("EMP_TYPE"),
    contracts = useCodes("CONTRACT_TYPE"),
    jobs = useCodes("JOB");
  const positions = useCodes("POSITION"),
    grades = useCodes("GRADE");
  const parts = useAdminRead(
    useCallback(() => readOptions(listSalesPartOptions), []),
  );
  const vendors = useAdminRead(
    useCallback(() => readOptions(() => listPartnerOptions("VEND")), []),
  );
  const apply = (next: CostPeopleFilters) => {
    setFilters(next);
    setDraft(next);
    setExpanded([]);
    sessionStorage.setItem("UI-03.year", String(next.year));
  };
  const sort = (key: string) =>
    apply({
      ...filters,
      sort: `${key},${filters.sort === `${key},asc` ? "desc" : "asc"}`,
      page: 0,
    });
  const download = async () => {
    setDownloading(true);
    setDownloadError(null);
    try {
      await savePeopleWorkbook(filters);
    } catch (error) {
      setDownloadError(failure(error));
    } finally {
      setDownloading(false);
    }
  };
  const summary = list.data?.summary;
  const codeName = (
    codes: { code: string; name: string }[],
    code: string | null | undefined,
  ) => codes.find((c) => c.code === code)?.name ?? code ?? "–";
  const currentYear = Number(
    (list.data?.referenceMonth ?? seoulMonth()).slice(0, 4),
  );
  const optionError =
    employees.error ??
    contracts.error ??
    jobs.error ??
    parts.error ??
    vendors.error;
  return (
    <section
      className={styles.screen}
      data-screen-id="SCR-UI-03"
      data-testid="people-screen"
    >
      <ScreenHeading
        screenId="UI-03"
        name={name}
        groupName={groupName}
        actions={
          <Button
            data-testid="people-download"
            pending={downloading}
            disabled={list.loading || !!list.error}
            onClick={() => void download()}
          >
            ↓ 엑셀 다운로드
          </Button>
        }
      />
      <div className={styles.period} aria-label="조회 기간">
        <Button
          data-testid="people-previous-year"
          aria-label="이전 해"
          disabled={filters.year <= 1}
          onClick={() => apply({ ...filters, year: filters.year - 1, page: 0 })}
        >
          ‹
        </Button>
        <strong>{filters.year}년</strong>
        <Button
          data-testid="people-next-year"
          aria-label="다음 해"
          disabled={filters.year >= currentYear}
          onClick={() => apply({ ...filters, year: filters.year + 1, page: 0 })}
        >
          ›
        </Button>
        <Badge tone={filters.year < currentYear ? "locked" : "info"}>
          {filters.year < currentYear ? "지난 해 · 읽기 전용" : "올해"}
        </Badge>
        <span>그 해 1월부터 {list.data?.month ?? "기준월"}까지 누계</span>
      </div>
      <ErrorBanner
        error={list.error}
        onRetry={() => void list.reload().catch(() => {})}
      />
      <ErrorBanner error={downloadError} onRetry={() => void download()} />
      {list.error?.field && (
        <p role="alert">
          {list.error.field}: {list.error.message}
        </p>
      )}
      {list.loading && <Skeleton />}
      <div className={styles.summary}>
        {[
          [
            "총 인원",
            `${number(summary?.people_count)}명`,
            `휴직 포함 · 월평균 가동 ${number(summary?.monthly_active_count)} · 가득 ${number(summary?.monthly_paid_count)}`,
          ],
          [
            "인당 매출",
            `${number(summary?.average_price_amt, unit)} ${unit === 1000 ? "천 원" : "원"}`,
            `단가가 있는 ${number(summary?.priced_people_count)}명 (0 포함)`,
          ],
          [
            "인당 원가",
            number(summary?.average_cost_amt, unit),
            `휴직 제외 ${number(summary?.average_people_count)}명`,
          ],
          [
            "인당 영업이익",
            number(summary?.average_profit_amt, unit),
            `적자 ${number(summary?.loss_people_count)}명`,
          ],
          [
            "가동률 · 가득률",
            `${percent(summary?.utilization_rt)} · ${percent(summary?.paid_rt)}`,
            "월별 비율의 기간 평균",
          ],
        ].map(([label, value, help], index) => (
          <div
            className={table.card}
            key={label}
            data-testid={`people-summary-${index}`}
          >
            <span>{label}</span>
            <strong>{value}</strong>
            <small>{help}</small>
          </div>
        ))}
      </div>
      <details className={table.card}>
        <summary>
          직무·등급별 인당 공헌이익 (인당 평균 영업이익 · 단위{" "}
          {unit === 1000 ? "천 원" : "원"})
        </summary>
        <div className={table.tableScroll}>
          <table className={table.table}>
            <thead>
              <tr>
                <th>직무</th>
                <th>등급</th>
                <th>인원</th>
                <th>인당 공헌이익</th>
              </tr>
            </thead>
            <tbody>
              {summary?.contributions.map((g, i) => (
                <tr key={i} data-testid={`people-contribution-${i}`}>
                  <td>{codeName(jobs.codes, g.job_cd)}</td>
                  <td>{codeName(grades.codes, g.grd_cd)}</td>
                  <td>{g.people_count}</td>
                  <td className={table.amount}>{number(g.profit_amt, unit)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </details>
      <form
        className={table.search}
        onSubmit={(event) => {
          event.preventDefault();
          apply({ ...draft, page: 0 });
        }}
      >
        <label>
          직원 구분
          <select
            data-testid="people-employee"
            value={draft.empType ?? ""}
            onChange={(e) => setDraft({ ...draft, empType: e.target.value })}
          >
            <option value="">전체</option>
            {employees.codes.map((c) => (
              <option key={c.code} value={c.code}>
                {c.name}
              </option>
            ))}
          </select>
        </label>
        <label>
          소속
          <select
            data-testid="people-vendor"
            value={draft.vendorId ?? ""}
            onChange={(e) => setDraft({ ...draft, vendorId: e.target.value })}
          >
            <option value="">전체</option>
            <option value="SELF">자사</option>
            {vendors.data?.map((c) => (
              <option key={c.id} value={c.id}>
                {c.name}
              </option>
            ))}
          </select>
        </label>
        <label>
          영업 파트
          <select
            data-testid="people-part"
            value={draft.partId ?? ""}
            onChange={(e) => setDraft({ ...draft, partId: e.target.value })}
          >
            <option value="">전체</option>
            {parts.data?.map((c) => (
              <option key={c.id} value={c.id}>
                {c.name}
              </option>
            ))}
          </select>
        </label>
        <label>
          계약유형
          <select
            data-testid="people-contract"
            value={draft.contractType ?? ""}
            onChange={(e) =>
              setDraft({ ...draft, contractType: e.target.value })
            }
          >
            <option value="">전체</option>
            {contracts.codes.map((c) => (
              <option key={c.code} value={c.code}>
                {c.name}
              </option>
            ))}
            <option value="IDLE">대기</option>
          </select>
        </label>
        <label>
          직무
          <select
            data-testid="people-job"
            value={draft.job ?? ""}
            onChange={(e) => setDraft({ ...draft, job: e.target.value })}
          >
            <option value="">전체</option>
            {jobs.codes.map((c) => (
              <option key={c.code} value={c.code}>
                {c.name}
              </option>
            ))}
          </select>
        </label>
        <Button
          type="button"
          variant="text"
          data-testid="people-advanced"
          aria-expanded={advanced}
          onClick={() => setAdvanced(!advanced)}
        >
          상세 조건 {advanced ? "▲" : "▼"}
        </Button>
        {advanced && (
          <div data-testid="people-advanced-panel" className={styles.advanced}>
            {(
              [
                ["lossOnly", "적자 인력만"],
                ["overlapOnly", "동시 투입·1.0 초과만"],
                ["excludeTurnkeyPrice", "턴키 단가 미포함"],
              ] as const
            ).map(([key, label]) => (
              <label key={key}>
                <input
                  data-testid={`people-${key.replace(/[A-Z]/g, (c) => `-${c.toLowerCase()}`)}`}
                  type="checkbox"
                  checked={draft[key] ?? false}
                  onChange={(e) =>
                    setDraft({ ...draft, [key]: e.target.checked })
                  }
                />
                {label}
              </label>
            ))}
          </div>
        )}
        <div className={table.actions}>
          <Button
            type="button"
            data-testid="people-reset"
            onClick={() => apply(peopleDefaults(filters.year))}
          >
            초기화
          </Button>
          <Button type="submit" variant="primary" data-testid="people-search">
            조회
          </Button>
        </div>
      </form>
      <ErrorBanner error={optionError} />
      {!!list.data?.warnings?.length && (
        <div
          className={styles.warnings}
          role="status"
          data-testid="people-warnings"
        >
          {list.data.warnings.map((text, i) => (
            <p key={i}>
              <Badge tone="warning">{text}</Badge>
            </p>
          ))}
        </div>
      )}
      <div className={table.toolbar}>
        <div className={table.actions}>
          {(["TOTAL", "AVERAGE"] as const).map((view) => (
            <Button
              key={view}
              data-testid={`people-view-${view.toLowerCase()}`}
              aria-pressed={filters.view === view}
              variant={filters.view === view ? "primary" : "secondary"}
              onClick={() => apply({ ...filters, view, page: 0 })}
            >
              {view === "TOTAL" ? "연 누계" : "월 평균"}
            </Button>
          ))}
          <span>총 {list.data?.total ?? 0}명</span>
          <Button
            data-testid="people-expand-all"
            variant="text"
            onClick={() =>
              setExpanded(
                expanded.length
                  ? []
                  : (list.data?.items.map((r) => r.dev_id) ?? []),
              )
            }
          >
            {expanded.length ? "모두 접기" : "동시 투입 모두 펼치기"}
          </Button>
        </div>
        <label>
          단위{" "}
          <select
            data-testid="people-unit"
            value={unit}
            onChange={(e) => setUnit(Number(e.target.value))}
          >
            <option value={1}>원</option>
            <option value={1000}>천 원</option>
            <option value={1000000}>백만 원</option>
          </select>
        </label>
      </div>
      {!list.loading && !list.error && list.data?.items.length === 0 && (
        <div className={table.empty} data-testid="people-empty">
          <h2>조건에 맞는 결과가 없습니다</h2>
          <Button
            data-testid="people-empty-reset"
            onClick={() => apply(peopleDefaults(filters.year))}
          >
            조건 초기화
          </Button>
        </div>
      )}
      {!list.error && (
        <div className={table.tableScroll}>
          <table className={table.table}>
            <thead>
              <tr>
                <th>
                  <button
                    data-testid="people-sort-name"
                    onClick={() => sort("dev_nm")}
                  >
                    이름
                  </button>
                </th>
                <th>소속</th>
                <th>직위</th>
                <th>구분</th>
                <th>프로젝트</th>
                <th>프로젝트 기간</th>
                <th>계약유형</th>
                <th>
                  <button
                    data-testid="people-sort-mm"
                    onClick={() => sort("mm_qty")}
                  >
                    MM
                  </button>
                </th>
                {moneyColumns.map(([key, label]) => (
                  <th key={key}>
                    {[
                      "bill_untprc_amt",
                      "slry_amt",
                      "total_cost_amt",
                      "personal_revenue_amt",
                      "profit_amt",
                    ].includes(key) ? (
                      <button
                        data-testid={`people-sort-${key.replaceAll("_", "-")}`}
                        onClick={() => sort(key)}
                      >
                        {label}
                      </button>
                    ) : (
                      label
                    )}
                  </th>
                ))}
                <th>
                  <button
                    data-testid="people-sort-margin"
                    onClick={() => sort("margin_rt")}
                  >
                    이익률
                  </button>
                </th>
              </tr>
            </thead>
            <tbody>
              {list.data?.items.map((row) => (
                <Fragment key={row.dev_id}>
                  <tr data-testid={`people-row-${row.dev_id}`}>
                    <td>
                      <button
                        className={table.textButton}
                        data-testid={`people-expand-${row.dev_id}`}
                        aria-expanded={expanded.includes(row.dev_id)}
                        onClick={() =>
                          setExpanded((ids) =>
                            ids.includes(row.dev_id)
                              ? ids.filter((id) => id !== row.dev_id)
                              : [...ids, row.dev_id],
                          )
                        }
                      >
                        {expanded.includes(row.dev_id) ? "▾" : "▸"} {row.dev_nm}
                      </button>
                      <br />
                      <small>{row.mng_no}</small>
                    </td>
                    <td>{row.affiliation_name}</td>
                    <td>{codeName(positions.codes, row.pstn_cd)}</td>
                    <td>
                      {codeName(employees.codes, row.emp_se_cd)}
                      {row.on_leave && <Badge tone="locked">휴직</Badge>}
                    </td>
                    <td>
                      {row.projects.map((p) => p.prj_nm).join(" / ") || "–"}
                    </td>
                    <td>
                      {row.projects
                        .map((p) => `${p.bgn_dt} ~ ${p.end_dt}`)
                        .join(" / ") || "–"}
                    </td>
                    <td>
                      {row.cntrct_ty_cd
                        .split(",")
                        .filter(Boolean)
                        .map((type) =>
                          type === "IDLE" ? (
                            <Badge key={type} tone="danger">
                              대기
                            </Badge>
                          ) : (
                            <span key={type}>
                              {codeName(contracts.codes, type)}{" "}
                            </span>
                          ),
                        )}
                    </td>
                    <td className={table.amount}>
                      {number(row.mm_qty, 1, 2)}
                      {row.overlap && <Badge tone="warning">1.0 초과</Badge>}
                    </td>
                    {moneyColumns.map(([key]) => (
                      <td className={table.amount} key={key}>
                        {number(row[key], unit)}
                      </td>
                    ))}
                    <td className={table.amount}>
                      {row.margin_rt == null
                        ? "–"
                        : `${number(row.margin_rt)}%`}
                    </td>
                  </tr>
                  {expanded.includes(row.dev_id) && (
                    <tr data-testid={`people-detail-row-${row.dev_id}`}>
                      <td colSpan={20}>
                        <PersonEvidence
                          row={row}
                          filters={filters}
                          unit={unit}
                        />
                      </td>
                    </tr>
                  )}
                </Fragment>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <div className={table.actions}>
        <Button
          data-testid="people-page-previous"
          disabled={(filters.page ?? 0) === 0}
          onClick={() => apply({ ...filters, page: (filters.page ?? 0) - 1 })}
        >
          이전
        </Button>
        <span>{(filters.page ?? 0) + 1} 페이지</span>
        <Button
          data-testid="people-page-next"
          disabled={
            ((filters.page ?? 0) + 1) * (filters.size ?? 50) >=
            (list.data?.total ?? 0)
          }
          onClick={() => apply({ ...filters, page: (filters.page ?? 0) + 1 })}
        >
          다음
        </Button>
        <label>
          한 화면 건수
          <select
            data-testid="people-page-size"
            value={filters.size ?? 50}
            onChange={(e) =>
              apply({
                ...filters,
                page: 0,
                size: Number(e.target.value) as 20 | 50 | 100,
              })
            }
          >
            {[20, 50, 100].map((size) => (
              <option key={size} value={size}>
                {size}건씩
              </option>
            ))}
          </select>
        </label>
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
              {[
                [
                  "개인별 원가",
                  "급여 + 수당 + 회사 보험 + 퇴직금 적립",
                  "월별 원가 원본 · 외주 부가세 제외",
                ],
                [
                  "원가합계",
                  "배분 인건비 + 경비 (대기·간접·연구개발비 포함)",
                  "동일 급여·경비 중복 가산 없음",
                ],
                [
                  "영업이익",
                  "개인 참고매출 − 원가합계",
                  "회사 확정 청구매출과 합산하지 않음",
                ],
                [
                  "이익률",
                  "영업이익 ÷ 개인 참고매출 × 100",
                  "분모가 0이거나 결손이면 –",
                ],
                [
                  "월 평균",
                  "같은 연 누계 ÷ 1월~기준월 개월 수",
                  "급여 결손 달도 개월 수에 포함",
                ],
                [
                  "가동률 · 가득률",
                  "월별 직접 인력 대비 가동·MO/DP 유상 인원 비율의 평균",
                  "대기·휴직 포함, 결손 분모는 –",
                ],
                [
                  "인당 공헌이익",
                  "직무·등급별 영업이익 합 ÷ 휴직 제외 인원",
                  "단가 평균은 0 포함·NULL 제외",
                ],
                [
                  "마감",
                  "월별 원가 배분 스냅샷",
                  "마감 배분자료 없으면 오류 · 조회는 원본을 변경하지 않음",
                ],
              ].map(([label, equation, help]) => (
                <tr
                  key={label}
                  data-testid={`people-formula-${label === "마감" ? "closed" : ["개인별 원가", "원가합계", "영업이익", "이익률", "월 평균", "가동률 · 가득률", "인당 공헌이익"].indexOf(label)}`}
                >
                  <td>{label}</td>
                  <td>{equation}</td>
                  <td>{help}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </details>
    </section>
  );
}
