import { useCallback, useState } from "react";
import { Badge, Button, Skeleton } from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { useAdminRead } from "../admin/useAdminResource";
import { useCodes } from "../shared/useCodes";
import { listProjectOptions } from "../projects/client";
import { DeveloperDrawer } from "../developers/DeveloperDrawer";
import { getAssignment } from "../assignments/client";
import { AssignmentChangeForm } from "../changes/AssignmentChangeForms";
import { useDeveloperReadOnly } from "../developers/useDeveloperReadOnly";
import {
  listCostRenegotiations,
  renegotiationDefaults,
  type RenegotiationRow,
} from "./client";
import styles from "../developers/Developers.module.css";

const money = (value: number | null | undefined) =>
  value == null ? "–" : value.toLocaleString("ko-KR");
const percent = (value: number | null | undefined) =>
  value == null ? "–" : `${value.toFixed(1)}%`;

function RateChange({
  row,
  onClose,
  onChanged,
}: {
  row: RenegotiationRow;
  onClose: () => void;
  onChanged: () => Promise<unknown>;
}) {
  const state = useAdminRead(
    useCallback(() => getAssignment(row.asgn_id), [row.asgn_id]),
  );
  return (
    <section data-testid="renegotiation-change-panel">
      <ErrorBanner error={state.error} />
      {state.error && (
        <Button
          data-testid="renegotiation-change-retry"
          onClick={() => void state.reload().catch(() => {})}
        >
          다시 시도
        </Button>
      )}
      {state.loading && <Skeleton />}
      {!state.data && (
        <Button data-testid="renegotiation-change-close" onClick={onClose}>
          닫기
        </Button>
      )}
      {state.data && (
        <AssignmentChangeForm
          assignment={state.data}
          contractType={row.cntrct_ty_cd}
          reload={async () => {
            await state.reload();
            await onChanged();
          }}
          onCancel={onClose}
          onSaved={onClose}
        />
      )}
    </section>
  );
}

// SCR-UI-32: feature component only. /UI-32 remains developer registration.
export default function UI32Screen() {
  const [draft, setDraft] = useState(renegotiationDefaults);
  const [filters, setFilters] = useState(renegotiationDefaults);
  const [detail, setDetail] = useState<number | null>(null);
  const [change, setChange] = useState<RenegotiationRow | null>(null);
  const [method, setMethod] = useState(false);
  const [validation, setValidation] = useState("");
  const employees = useCodes("EMP_TYPE");
  const projects = useAdminRead(useCallback(listProjectOptions, []));
  const state = useAdminRead(
    useCallback(() => listCostRenegotiations(filters), [filters]),
  );
  const readOnly = useDeveloperReadOnly();
  const data = state.data;
  const apply = (raise = draft.raiseRate) => {
    if (!Number.isFinite(raise) || raise < 0 || raise > 50) {
      setValidation("인상률은 0~50%로 입력하세요.");
      return;
    }
    setValidation("");
    setFilters({ ...draft, raiseRate: raise, page: 0 });
  };
  const sort = (field: string) =>
    setFilters((current) => ({
      ...current,
      page: 0,
      sort: `${field},${current.sort === `${field},asc` ? "desc" : "asc"}`,
    }));
  return (
    <section data-testid="renegotiation-screen" data-screen-id="SCR-UI-32">
      <header className={styles.toolbar}>
        <h1>단가 재협상 대상</h1>
        <Button
          data-testid="renegotiation-download"
          disabled
          title="다운로드 HTTP 연결 및 열 매핑 승인 대기"
        >
          엑셀 다운로드
        </Button>
      </header>
      <p role="note">
        재협상 화면 주소 승인 대기: /UI-32는 기존 개발자 등록입니다. 원가 초과
        원인에서 이 화면으로의 이동은 제공하지 않습니다.
      </p>
      <p>
        기준월 {data?.month ?? "조회 중"}{" "}
        {data?.closed && <Badge tone="locked">마감 스냅샷</Badge>}
      </p>
      <div className={styles.fields} aria-label="재협상 요약">
        <div className={styles.card}>
          <h2>대상 인원</h2>
          {money(
            state.error || state.loading ? null : data?.summary.people_count,
          )}
          명
        </div>
        <div className={styles.card}>
          <h2>매출가중 평균 이익률</h2>
          {percent(
            state.error || state.loading
              ? null
              : data?.summary.weighted_margin_rt,
          )}
        </div>
        <div className={styles.card}>
          <h2>가정 연 증가</h2>
          {money(
            state.error || state.loading
              ? null
              : data?.summary.annual_increase_amt,
          )}
          원
        </div>
        <div className={styles.card}>
          <h2>목표 연 증가</h2>
          {money(
            state.error || state.loading
              ? null
              : data?.summary.target_annual_increase_amt,
          )}
          원
        </div>
      </div>
      <p>
        12개월 유지 가정이며 실제 향후 계약을 보장하지 않습니다. 가정 계산은
        저장되지 않습니다.
      </p>
      <form
        data-testid="renegotiation-search-form"
        className={styles.search}
        onSubmit={(event) => {
          event.preventDefault();
          apply();
        }}
      >
        <label>
          가정 인상률 (%)
          <input
            data-testid="renegotiation-raise"
            aria-invalid={!!validation}
            type="number"
            min="0"
            max="50"
            step="any"
            value={Number.isNaN(draft.raiseRate) ? "" : draft.raiseRate}
            onChange={(event) => {
              const value =
                event.target.value === "" ? NaN : Number(event.target.value);
              setDraft((current) => ({ ...current, raiseRate: value }));
              if (Number.isFinite(value) && value >= 0 && value <= 50) {
                setValidation("");
                setFilters((current) => ({
                  ...current,
                  raiseRate: value,
                  page: 0,
                }));
              } else setValidation("인상률은 0~50%로 입력하세요.");
            }}
          />
        </label>
        <label>
          갱신 기간
          <select
            data-testid="renegotiation-window"
            value={draft.windowMonths}
            onChange={(event) =>
              setDraft({ ...draft, windowMonths: Number(event.target.value) })
            }
          >
            <option value="3">3개월 안</option>
            <option value="6">6개월 안</option>
            <option value="12">12개월 안</option>
          </select>
        </label>
        <label>
          이익률
          <select
            data-testid="renegotiation-margin"
            value={draft.marginBelow}
            onChange={(event) =>
              setDraft({ ...draft, marginBelow: Number(event.target.value) })
            }
          >
            <option value="10">10% 미만</option>
            <option value="15">15% 미만</option>
            <option value="20">20% 미만</option>
          </select>
        </label>
        <label>
          직원구분
          <select
            data-testid="renegotiation-employee"
            value={draft.empType}
            onChange={(event) =>
              setDraft({ ...draft, empType: event.target.value })
            }
          >
            <option value="">전체</option>
            {employees.codes.map((option) => (
              <option
                key={option.code}
                value={option.code}
                data-testid={`renegotiation-employee-option-${option.code.toLowerCase().replaceAll("_", "-")}`}
              >
                {option.name}
              </option>
            ))}
          </select>
        </label>
        <label>
          프로젝트
          <select
            data-testid="renegotiation-project"
            value={draft.projectId}
            onChange={(event) =>
              setDraft({ ...draft, projectId: event.target.value })
            }
          >
            <option value="">전체</option>
            {projects.data
              ?.filter((p) => ["MO", "DP"].includes(p.type))
              .map((p) => (
                <option
                  key={p.id}
                  value={p.id}
                  data-testid={`renegotiation-project-option-${p.id}`}
                >
                  {p.name}
                </option>
              ))}
          </select>
        </label>
        <Button
          data-testid="renegotiation-reset"
          type="button"
          onClick={() => {
            setDraft(renegotiationDefaults);
            setFilters({ ...renegotiationDefaults });
            setValidation("");
          }}
        >
          초기화
        </Button>
        <Button
          data-testid="renegotiation-search"
          type="submit"
          variant="primary"
        >
          조회
        </Button>
      </form>
      {validation && <p role="alert">{validation}</p>}
      <ErrorBanner error={employees.error ?? projects.error} />
      {(employees.error || projects.error) && (
        <Button
          data-testid="renegotiation-options-retry"
          onClick={() => {
            employees.retry();
            void projects.reload().catch(() => {});
          }}
        >
          선택 목록 다시 시도
        </Button>
      )}
      <ErrorBanner error={state.error} />
      {state.error && (
        <>
          <p>{state.error.field && `입력 항목: ${state.error.field}`}</p>
          <Button
            data-testid="renegotiation-retry"
            onClick={() => void state.reload().catch(() => {})}
          >
            다시 시도
          </Button>
        </>
      )}
      {state.loading ? (
        <Skeleton />
      ) : (
        !state.error &&
        data && (
          <>
            {data.warnings.length > 0 && (
              <section data-testid="renegotiation-warning" role="status">
                <Badge tone="warning">결손 점검</Badge>
                {data.warnings.map((warning) => (
                  <p
                    key={warning}
                    data-testid={`renegotiation-warning-${data.warnings.indexOf(warning)}`}
                  >
                    {warning}
                  </p>
                ))}
              </section>
            )}
            <div className={styles.toolbar}>
              <span>총 {data.total}건</span>
              <select
                aria-label="페이지 크기"
                data-testid="renegotiation-size"
                value={filters.size}
                onChange={(event) =>
                  setFilters({
                    ...filters,
                    size: Number(event.target.value),
                    page: 0,
                  })
                }
              >
                <option value="20">20건</option>
                <option value="50">50건</option>
                <option value="100">100건</option>
              </select>
            </div>
            <div className={styles.tableScroll}>
              <table className={styles.table}>
                <thead>
                  <tr>
                    <th>
                      <button
                        data-testid="renegotiation-sort-name"
                        onClick={() => sort("dev_nm")}
                      >
                        사람
                      </button>
                    </th>
                    <th>구분</th>
                    <th>프로젝트</th>
                    <th>
                      <button
                        data-testid="renegotiation-sort-date"
                        onClick={() => sort("end_dt")}
                      >
                        갱신일(D-)
                      </button>
                    </th>
                    <th>MM</th>
                    <th>현재 단가</th>
                    <th>월 배분원가</th>
                    <th>
                      <button
                        data-testid="renegotiation-sort-margin"
                        onClick={() => sort("margin_rt")}
                      >
                        이익률
                      </button>
                    </th>
                    <th>목표 단가</th>
                    <th>필요 인상률</th>
                    <th>가정 연 증가</th>
                    <th>후속 조치</th>
                  </tr>
                </thead>
                <tbody>
                  {data.items.map((row) => (
                    <tr
                      key={row.asgn_id}
                      data-testid={`renegotiation-row-${row.asgn_id}`}
                      onClick={() => setDetail(row.dev_id)}
                    >
                      <td>
                        <button
                          className={styles.textButton}
                          data-testid={`renegotiation-person-${row.asgn_id}`}
                          onClick={() => setDetail(row.dev_id)}
                        >
                          {row.dev_nm}
                        </button>
                      </td>
                      <td>
                        {employees.codes.find((e) => e.code === row.emp_se_cd)
                          ?.name ?? row.emp_se_cd}
                      </td>
                      <td>
                        {row.prj_nm}{" "}
                        <Badge tone="info">{row.cntrct_ty_cd}</Badge>
                      </td>
                      <td>
                        {row.end_dt}{" "}
                        <Badge
                          tone={
                            row.days_remaining <= 30 ? "warning" : "neutral"
                          }
                        >
                          D-{row.days_remaining}
                        </Badge>
                      </td>
                      <td className={styles.amount}>{row.mm_qty.toFixed(2)}</td>
                      <td className={styles.amount}>
                        {money(row.bill_untprc_amt)}
                      </td>
                      <td className={styles.amount}>
                        {money(row.allocated_cost_amt)}
                      </td>
                      <td className={styles.amount}>
                        {row.margin_rt == null ? (
                          "–"
                        ) : (
                          <Badge
                            tone={row.margin_rt < 0 ? "danger" : "warning"}
                          >
                            {percent(row.margin_rt)}
                          </Badge>
                        )}
                      </td>
                      <td className={styles.amount}>
                        {money(row.target_untprc_amt)}
                      </td>
                      <td className={styles.amount}>
                        {percent(row.required_raise_rt)}
                      </td>
                      <td className={styles.amount}>
                        {money(row.annual_increase_amt)}
                        {row.warnings.map((w) => (
                          <p
                            key={w}
                            data-testid={`renegotiation-row-${row.asgn_id}-warning-${row.warnings.indexOf(w)}`}
                          >
                            {w}
                          </p>
                        ))}
                      </td>
                      <td>
                        {!readOnly && !data.closed && (
                          <Button
                            data-testid={`renegotiation-rate-${row.asgn_id}`}
                            onClick={(event) => {
                              event.stopPropagation();
                              setChange(row);
                            }}
                          >
                            단가 변경
                          </Button>
                        )}
                        {data.closed && (
                          <Badge tone="locked">마감 조회 전용</Badge>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {data.items.length === 0 && (
                <p className={styles.empty}>조회 결과가 없습니다.</p>
              )}
            </div>
            <div className={styles.actions}>
              <Button
                data-testid="renegotiation-prev"
                disabled={filters.page === 0}
                onClick={() =>
                  setFilters({ ...filters, page: filters.page - 1 })
                }
              >
                이전
              </Button>
              <span>{filters.page + 1} 페이지</span>
              <Button
                data-testid="renegotiation-next"
                disabled={(filters.page + 1) * filters.size >= data.total}
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
      <Button
        data-testid="renegotiation-method"
        aria-expanded={method}
        onClick={() => setMethod(!method)}
      >
        계산 방법
      </Button>
      {method && (
        <section
          data-testid="renegotiation-method-panel"
          className={styles.tableScroll}
        >
          <table className={styles.table}>
            <thead>
              <tr>
                <th>항목</th>
                <th>계산 방법·출처</th>
              </tr>
            </thead>
            <tbody>
              <tr data-testid="renegotiation-rule-margin">
                <td>이익률</td>
                <td>
                  (단가×MM−배분 원가) / (단가×MM). MM·단가는 ⑦, 배분 원가는 공통
                  배분 엔진.
                </td>
              </tr>
              <tr data-testid="renegotiation-rule-target">
                <td>목표 단가</td>
                <td>
                  (배분 원가/MM) / (1−적용월 목표 이익률). 표시만 만원 단위
                  올림, 0분모는 –.
                </td>
              </tr>
              <tr data-testid="renegotiation-rule-annual">
                <td>연 이익 증가</td>
                <td>
                  현재 단가×가정 인상률×MM×12. 목표 시 max(목표 단가−현재
                  단가,0)×MM×12.
                </td>
              </tr>
            </tbody>
          </table>
        </section>
      )}
      {detail !== null && (
        <DeveloperDrawer
          id={detail}
          onClose={() => setDetail(null)}
          onSaved={state.reload}
        />
      )}
      {change && (
        <RateChange
          row={change}
          onClose={() => setChange(null)}
          onChanged={state.reload}
        />
      )}
    </section>
  );
}
