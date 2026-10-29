import { Fragment, useCallback, useState } from "react";
import { Badge, Button, Pagination, Skeleton } from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { ApiFailure } from "../../shared/api";
import { failure } from "../admin/client";
import { useAdminRead } from "../admin/useAdminResource";
import { ScreenHeading } from "../shared/ScreenHeading";
import { seoulMonth } from "../shared/PeriodBar";
import { useCodes } from "../shared/useCodes";
import { listSalesPartOptions } from "../salespartsops/client";
import { listPartnerOptions } from "../partnersops/client";
import {
  defaultPeopleFilters,
  downloadCostPeople,
  getCostPersonBreakdown,
  listCostPeople,
  type PeopleFilters,
  type PeopleRow,
} from "./client";
import styles from "../developers/Developers.module.css";

const columns = [
  ["dev_nm", "이름"],
  ["vendor_name", "소속"],
  ["pstn_cd", "직위"],
  ["emp_se_cd", "구분"],
  ["project_names", "프로젝트"],
  ["period", "기간"],
  ["contract_types", "유형"],
  ["mm_qty", "MM"],
  ["bill_untprc_amt", "단가"],
  ["slry_amt", "급여"],
  ["alwnc_amt", "수당"],
  ["sins_cmpny_amt", "보험 회사분"],
  ["rtrpay_acml_amt", "퇴직금"],
  ["indv_lbr_cst_amt", "개인원가"],
  ["team_expns_amt", "팀비"],
  ["project_expns_amt", "프로젝트경비"],
  ["tot_cst_amt", "총원가"],
  ["prft_amt", "이익"],
  ["mrgn_rt", "이익률"],
] as const;
const referenceColumns = [
  ["sins_emp_amt", "보험 본인분"],
  ["whtax_amt", "원천세"],
  ["real_pay_amt", "실지급액"],
] as const;
const sortable = new Set([
  "dev_nm",
  "mm_qty",
  "bill_untprc_amt",
  "slry_amt",
  "tot_cst_amt",
  "prft_amt",
  "mrgn_rt",
]);
export const peopleNumber = (value: number | null | undefined, digits = 0) =>
  value == null
    ? "–"
    : value.toLocaleString("ko-KR", {
        minimumFractionDigits: digits,
        maximumFractionDigits: digits,
      });

function Breakdown({ id, filters }: { id: number; filters: PeopleFilters }) {
  const read = useAdminRead(
    useCallback(
      () => getCostPersonBreakdown(id, filters),
      [id, filters.year, filters.view],
    ),
  );
  return (
    <section data-testid={`people-breakdown-${id}`} aria-label="월별 계산 근거">
      <ErrorBanner
        error={read.error}
        onRetry={() => void read.reload().catch(() => {})}
      />
      {read.loading ? (
        <Skeleton label="계산 근거 불러오는 중" />
      ) : (
        !read.error &&
        read.data && (
          <>
            <p>
              월별 원천 금액 · 단위: 원 · 평균 보기에서도 월별 근거는 실제 월
              금액입니다.
            </p>
            <div className={styles.tableScroll}>
              <table className={styles.table}>
                <thead>
                  <tr>
                    {[
                      "월",
                      "프로젝트 / 대기",
                      "MM",
                      "단가",
                      "배분 인건비",
                      "경비",
                      "참고매출",
                      "이익",
                      "대기 원가",
                      "초과",
                    ].map((c) => (
                      <th key={c}>{c}</th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {read.data.items.map((r, index) => (
                    <tr
                      key={`${r.cst_ym}-${r.asgn_id}-${r.aloc_ty_cd}-${index}`}
                      data-testid={`people-basis-${id}-${index}`}
                    >
                      <td>{r.cst_ym}</td>
                      <td>{r.prj_nm ?? r.aloc_ty_cd}</td>
                      <td>{peopleNumber(r.mm_qty, 2)}</td>
                      <td>{peopleNumber(r.bill_untprc_amt)}</td>
                      <td data-sensitive>{peopleNumber(r.lbr_cst_amt)}</td>
                      <td>{peopleNumber(r.drct_expns_amt)}</td>
                      <td>{peopleNumber(r.sls_amt)}</td>
                      <td data-sensitive>{peopleNumber(r.prft_amt)}</td>
                      <td>{peopleNumber(r.idle_cst_amt)}</td>
                      <td>
                        {r.mm_ovr_yn === "Y" && (
                          <Badge tone="warning">1.0 초과</Badge>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {read.data.items.length === 0 && (
                <p className={styles.empty}>조건에 맞는 결과가 없습니다</p>
              )}
            </div>
          </>
        )
      )}
    </section>
  );
}
// SCR-UI-03: annual people profitability, read-only source data.
export default function UI03Screen({
  name = "개인 원가·수익표",
  groupName = "원가·수익 분석",
}: {
  name?: string;
  groupName?: string;
  onDirtyChange?: (dirty: boolean) => void;
}) {
  const [draft, setDraft] = useState(defaultPeopleFilters);
  const [filters, setFilters] = useState(defaultPeopleFilters);
  const [expanded, setExpanded] = useState<number[]>([]);
  const [references, setReferences] = useState(false);
  const [exporting, setExporting] = useState(false);
  const [exportError, setExportError] = useState<ApiFailure | null>(null);
  const list = useAdminRead(
    useCallback(() => listCostPeople(filters), [filters]),
  );
  const options = useAdminRead(
    useCallback(
      async () => ({
        parts: await listSalesPartOptions(),
        vendors: await listPartnerOptions("VEND"),
      }),
      [],
    ),
  );
  const employee = useCodes("EMP_TYPE"),
    jobs = useCodes("JOB"),
    types = useCodes("CONTRACT_TYPE"),
    positions = useCodes("POSITION");
  const lookup = (
    codes: { code: string; name: string }[],
    value: string | null,
  ) => codes.find((c) => c.code === value)?.name ?? value ?? "–";
  const change = (values: Partial<PeopleFilters>) => {
    setFilters((f) => ({ ...f, ...values }));
    setExpanded([]);
  };
  const search = () => {
    setFilters({ ...draft, view: filters.view, page: 0 });
    setExpanded([]);
  };
  const reset = () => {
    const next = defaultPeopleFilters();
    setDraft(next);
    setFilters(next);
    setExpanded([]);
  };
  const download = async () => {
    if (exporting) return;
    setExporting(true);
    setExportError(null);
    try {
      await downloadCostPeople(filters);
    } catch (e) {
      setExportError(failure(e));
    } finally {
      setExporting(false);
    }
  };
  const allColumns = references
    ? [...columns, ...referenceColumns]
    : [...columns];
  const value = (r: PeopleRow, key: (typeof allColumns)[number][0]) => {
    if (key === "vendor_name") return r.vendor_name ?? "자사";
    if (key === "emp_se_cd") return lookup(employee.codes, r.emp_se_cd);
    if (key === "pstn_cd") return lookup(positions.codes, r.pstn_cd);
    if (key === "contract_types")
      return r.contract_types.map((c) => lookup(types.codes, c)).join(" · ");
    const v = r[key];
    if (Array.isArray(v)) return v.join(" · ");
    if (key === "mrgn_rt")
      return v == null ? "–" : `${peopleNumber(Number(v), 1)}%`;
    if (typeof v === "number" || v == null)
      return peopleNumber(v as number | null, key === "mm_qty" ? 2 : 0);
    return v;
  };
  const summary = list.data?.summary;
  const summaryCards = summary
    ? [
        ["총 인원", peopleNumber(summary.psn_cnt)],
        ["월평균 가동 인원", peopleNumber(summary.avg_working_psn_cnt, 1)],
        ["월평균 가득 인원", peopleNumber(summary.avg_earning_psn_cnt, 1)],
        ["인당 매출(참고 단가)", peopleNumber(summary.avg_price_amt)],
        ["평균 원가", peopleNumber(summary.avg_cost_amt)],
        ["평균 이익", peopleNumber(summary.avg_profit_amt)],
        ["적자 인원", peopleNumber(summary.loss_psn_cnt)],
        [
          "가동률",
          summary.utilization_rt == null
            ? "–"
            : `${peopleNumber(summary.utilization_rt, 1)}%`,
        ],
        [
          "가득률",
          summary.earning_rt == null
            ? "–"
            : `${peopleNumber(summary.earning_rt, 1)}%`,
        ],
      ]
    : [];
  return (
    <section data-testid="people-workspace" data-screen-id="SCR-UI-03">
      <ScreenHeading
        screenId="UI-03"
        name={name}
        groupName={groupName}
        actions={
          <Button
            data-testid="people-download"
            pending={exporting}
            onClick={() => void download()}
          >
            엑셀 다운로드
          </Button>
        }
      />
      <div className={styles.search}>
        <label>
          연도{" "}
          <input
            data-testid="people-year"
            type="number"
            min={1}
            max={Number(seoulMonth().slice(0, 4))}
            value={draft.year}
            onChange={(e) =>
              setDraft((d) => ({ ...d, year: Number(e.target.value) }))
            }
          />
        </label>
        <Button
          data-testid="people-this-year"
          onClick={() => {
            const year = Number(seoulMonth().slice(0, 4));
            setDraft((d) => ({ ...d, year }));
            change({ year, page: 0 });
          }}
        >
          올해
        </Button>
        {list.data && (
          <>
            <span>
              {list.data.items[0]?.period ??
                `${filters.year}년 1월 ~ ${list.data.calculation_month}`}
            </span>
            <Badge tone={list.data.closed ? "locked" : "info"}>
              {list.data.closed ? "마감" : "잠정"}
            </Badge>
            <span>단위: {list.data.unit}</span>
          </>
        )}
      </div>
      {!list.error && !list.loading && (
        <div className={styles.fields}>
          {summaryCards.map(([label, number]) => (
            <div className={styles.card} key={label}>
              <span>{label}</span>
              <h2>{number}</h2>
            </div>
          ))}
        </div>
      )}
      {summary && !list.error && (
        <details data-testid="people-contributions">
          <summary>직무·등급별 인당 공헌이익</summary>
          <div className={styles.tableScroll}>
            <table className={styles.table}>
              <thead>
                <tr>
                  <th>직무</th>
                  <th>등급</th>
                  <th>인원</th>
                  <th>인당 공헌이익</th>
                </tr>
              </thead>
              <tbody>
                {summary.contributions.map((r, i) => (
                  <tr
                    key={`${r.job_cd}-${r.grd_cd}`}
                    data-testid={`people-contribution-${i}`}
                  >
                    <td>{lookup(jobs.codes, r.job_cd)}</td>
                    <td>{r.grd_cd || "–"}</td>
                    <td>{r.psn_cnt}</td>
                    <td>{peopleNumber(r.contribution_amt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </details>
      )}
      <form
        data-testid="people-filters"
        className={styles.search}
        onSubmit={(e) => {
          e.preventDefault();
          search();
        }}
      >
        {(
          [
            [
              "empType",
              "직원구분",
              employee.codes.map((c) => ({ value: c.code, label: c.name })),
            ],
            [
              "vendorId",
              "소속",
              (options.data?.vendors ?? []).map((c) => ({
                value: c.id,
                label: c.name,
              })),
            ],
            [
              "partId",
              "영업파트",
              (options.data?.parts ?? []).map((c) => ({
                value: c.id,
                label: c.name,
              })),
            ],
            [
              "contractType",
              "계약유형",
              types.codes.map((c) => ({ value: c.code, label: c.name })),
            ],
            [
              "job",
              "직무",
              jobs.codes.map((c) => ({ value: c.code, label: c.name })),
            ],
          ] as const
        ).map(([key, label, values]) => (
          <label key={key}>
            {label}
            <select
              data-testid={`people-filter-${key.replace(/[A-Z]/g, (c) => `-${c.toLowerCase()}`)}`}
              value={draft[key]}
              onChange={(e) =>
                setDraft((d) => ({ ...d, [key]: e.target.value }))
              }
            >
              <option value="">전체</option>
              {values.map((v) => (
                <option key={v.value} value={v.value}>
                  {v.label}
                </option>
              ))}
            </select>
          </label>
        ))}
        {(
          [
            ["lossOnly", "적자 인력만"],
            ["overlapOnly", "동시투입 1.0 초과만"],
            ["excludeTurnkeyPrice", "턴키 단가 제외"],
          ] as const
        ).map(([key, label]) => (
          <label key={key}>
            <input
              type="checkbox"
              data-testid={`people-${key.replace(/[A-Z]/g, (c) => `-${c.toLowerCase()}`)}`}
              checked={draft[key]}
              onChange={(e) =>
                setDraft((d) => ({ ...d, [key]: e.target.checked }))
              }
            />
            {label}
          </label>
        ))}
        <Button type="button" data-testid="people-reset" onClick={reset}>
          초기화
        </Button>
        <Button type="submit" data-testid="people-search" variant="primary">
          조회
        </Button>
      </form>
      {[employee, jobs, types, positions].map((codes, i) => (
        <ErrorBanner key={i} error={codes.error} onRetry={codes.retry} />
      ))}
      <ErrorBanner
        error={options.error}
        onRetry={() => void options.reload().catch(() => {})}
      />
      <ErrorBanner error={exportError} onRetry={() => void download()} />
      <ErrorBanner
        error={list.error}
        onRetry={() => void list.reload().catch(() => {})}
      />
      <div className={styles.toolbar}>
        <div role="tablist" aria-label="표시 기준">
          {(
            [
              ["TOTAL", "연 누계"],
              ["AVERAGE", "월 평균"],
            ] as const
          ).map(([view, label]) => (
            <Button
              data-testid={`people-view-${view.toLowerCase()}`}
              role="tab"
              key={view}
              aria-selected={filters.view === view}
              onClick={() => change({ view, page: 0 })}
            >
              {label}
            </Button>
          ))}
        </div>
        <label>
          <input
            data-testid="people-payroll-reference"
            type="checkbox"
            checked={references}
            onChange={(e) => setReferences(e.target.checked)}
          />
          급여 참고 열 보기
        </label>
      </div>
      {list.loading ? (
        <Skeleton label="개인 원가·수익 불러오는 중" />
      ) : (
        !list.error &&
        list.data && (
          <>
            {list.data.warnings.length > 0 && (
              <div
                role="status"
                data-testid="people-warnings"
                className={styles.card}
              >
                <Badge tone="warning">자료 점검</Badge>
                <ul>
                  {list.data.warnings.map((w) => (
                    <li key={w}>{w}</li>
                  ))}
                </ul>
              </div>
            )}
            <div className={styles.tableScroll}>
              <table className={styles.table} aria-label="개인 원가·수익표">
                <thead>
                  <tr>
                    <th>펼침</th>
                    {allColumns.map(([key, label]) => (
                      <th key={key}>
                        {sortable.has(key) ? (
                          <button
                            data-testid={`people-sort-${key.replaceAll("_", "-")}`}
                            onClick={() =>
                              change({
                                sort: `${key},${filters.sort === `${key},asc` ? "desc" : "asc"}`,
                                page: 0,
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
                  {list.data.items.map((r) => (
                    <Fragment key={r.dev_id}>
                      <tr data-testid={`people-row-${r.dev_id}`}>
                        <td>
                          {r.expandable && (
                            <Button
                              data-testid={`people-expand-${r.dev_id}`}
                              aria-label={`${r.dev_nm} 계산 근거`}
                              aria-expanded={expanded.includes(r.dev_id)}
                              onClick={() =>
                                setExpanded((ids) =>
                                  ids.includes(r.dev_id)
                                    ? ids.filter((id) => id !== r.dev_id)
                                    : [...ids, r.dev_id],
                                )
                              }
                            >
                              {expanded.includes(r.dev_id) ? "접기" : "펼침"}
                            </Button>
                          )}
                        </td>
                        {allColumns.map(([key]) => (
                          <td
                            key={key}
                            className={
                              typeof r[key] === "number"
                                ? styles.amount
                                : undefined
                            }
                            data-sensitive={key.endsWith("_amt") || undefined}
                          >
                            {key === "prft_amt" ? (
                              <Badge
                                tone={r.prft_amt < 0 ? "danger" : "success"}
                              >
                                {value(r, key)}
                              </Badge>
                            ) : (
                              value(r, key)
                            )}
                            {key === "mm_qty" && r.mm_ovr_yn === "Y" && (
                              <Badge tone="warning">1.0 초과</Badge>
                            )}
                          </td>
                        ))}
                      </tr>
                      {expanded.includes(r.dev_id) && (
                        <tr>
                          <td colSpan={allColumns.length + 1}>
                            <Breakdown id={r.dev_id} filters={filters} />
                          </td>
                        </tr>
                      )}
                    </Fragment>
                  ))}
                </tbody>
              </table>
              {list.data.items.length === 0 && (
                <p className={styles.empty}>조건에 맞는 결과가 없습니다</p>
              )}
            </div>
            <Pagination
              page={filters.page + 1}
              size={filters.size}
              total={list.data.total}
              onPage={(page) => change({ page: page - 1 })}
              onSize={(size) => change({ size, page: 0 })}
            />
          </>
        )
      )}
      <details data-testid="people-calculation">
        <summary>계산 방법</summary>
        <div className={styles.tableScroll}>
          <table className={styles.table}>
            <thead>
              <tr>
                <th>항목</th>
                <th>계산 방법</th>
                <th>출처</th>
              </tr>
            </thead>
            <tbody>
              <tr>
                <td>연 누계 / 월 평균</td>
                <td>
                  1월~기준월 합계 / 포함 개월 수. 결손 급여 달도 분모에 포함
                </td>
                <td>월별 급여·투입 MM</td>
              </tr>
              <tr>
                <td>배분 원가</td>
                <td>
                  정규·계약 MM/max(합계 MM,1), 프리·외주 MM/합계 MM. 잔여 원은
                  소수 부분 큰 순·투입 ID 순
                </td>
                <td>공통 원가 배분 엔진</td>
              </tr>
              <tr>
                <td>개인 이익</td>
                <td>
                  개인 참고매출 − 총원가. 회사 확정 청구 매출과 별개. 턴키 단가
                  제외 시 턴키 참고매출 제외
                </td>
                <td>단가 이력·턴키 PERSON 배분·급여·직접 경비</td>
              </tr>
              <tr>
                <td>가동률 / 가득률</td>
                <td>
                  각 월 MM 양수 인원 / 전체 직접 인원, 유상 MO/DP 인원 / 전체
                  직접 인원의 월별 비율 평균. 대기·휴직 포함
                </td>
                <td>월별 투입·근무 상태</td>
              </tr>
              <tr>
                <td>인당 평균 / 결손</td>
                <td>
                  단가는 0 포함·단가 없는 사람 제외, 원가·이익은 휴직 제외. 분모
                  0은 –. 마감은 스냅샷만 조회
                </td>
                <td>월별 원가·마감 배분자료</td>
              </tr>
            </tbody>
          </table>
        </div>
      </details>
    </section>
  );
}
