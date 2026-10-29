import { useCallback, useState } from "react";
import { useNavigate } from "react-router-dom";
import { Badge, Button, Pagination, Skeleton } from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { ApiFailure } from "../../shared/api";
import { useAdminRead } from "../admin/useAdminResource";
import { useCodes } from "../shared/useCodes";
import { listProjectOptions } from "../projects/client";
import { DeveloperDrawer } from "../developers/DeveloperDrawer";
import {
  DeveloperAssignments,
  DeveloperMonthlyCosts,
  DeveloperHistory,
} from "../developersops/DeveloperReadPanels";
import {
  listCostRenegotiations,
  downloadCostRenegotiations,
  renegotiationDefaults,
  type RenegotiationFilters,
} from "./client";
import { AllocationPanel } from "./AllocationPanel";
import table from "../developers/Developers.module.css";
import styles from "./Renegotiation.module.css";

const money = (value: number | null | undefined) =>
  value == null ? "–" : value.toLocaleString("ko-KR");
const ratio = (value: number | null | undefined) =>
  value == null ? "–" : `${value.toFixed(1)}%`;

// SCR-UI-32 logical panel, embedded in SCR-UI-04; never registered at /UI-32.
export default function UI32Screen({
  projectId,
  onClose,
}: {
  projectId?: number;
  onClose?: () => void;
}) {
  const navigate = useNavigate();
  const initial = {
    ...renegotiationDefaults,
    projectId: projectId == null ? "" : String(projectId),
  };
  const [draft, setDraft] = useState<RenegotiationFilters>(initial);
  const [filters, setFilters] = useState<RenegotiationFilters>(initial);
  const [detailId, setDetailId] = useState<number | null>(null);
  const [basis, setBasis] = useState(false);
  const [validation, setValidation] = useState("");
  const [exportError, setExportError] = useState<ApiFailure | null>(null);
  const [exporting, setExporting] = useState(false);
  const employee = useCodes("EMP_TYPE");
  const projects = useAdminRead(useCallback(() => listProjectOptions(), []));
  const list = useAdminRead(
    useCallback(() => listCostRenegotiations(filters), [filters]),
  );
  const data = list.data;
  const apply = (next: RenegotiationFilters) => {
    if (
      next.raiseRate !== "" &&
      (!Number.isFinite(next.raiseRate) ||
        next.raiseRate < 0 ||
        next.raiseRate > 50)
    ) {
      setValidation("가정 인상률은 0~50%로 입력하세요.");
      return;
    }
    setValidation("");
    setFilters({ ...next, page: 0 });
  };
  const reset = () => {
    setDraft({ ...initial });
    setFilters({ ...initial });
    setValidation("");
  };
  const sort = (field: string) =>
    setFilters((f) => ({
      ...f,
      page: 0,
      sort: `${field},${f.sort === `${field},asc` ? "desc" : "asc"}`,
    }));
  const download = async () => {
    setExporting(true);
    setExportError(null);
    try {
      await downloadCostRenegotiations(filters);
    } catch (error) {
      setExportError(
        error instanceof ApiFailure
          ? error
          : new ApiFailure("다운로드하지 못했습니다.", null, null),
      );
    } finally {
      setExporting(false);
    }
  };
  const columns = [
    ["dev_nm", "이름"],
    ["emp_se_cd", "구분"],
    ["prj_nm", "프로젝트"],
    ["end_dt", "갱신일 (D-)"],
    ["mm_qty", "MM"],
    ["bill_untprc_amt", "현재 단가"],
    ["allocated_cost_amt", "월 배분원가"],
    ["margin_rt", "이익률"],
    ["target_price_amt", "목표 단가"],
    ["required_raise_rt", "필요 인상률"],
    ["assumed_annual_profit_increase_amt", "가정 연 증가"],
  ];
  return (
    <section
      className={styles.panel}
      data-testid="renegotiation-panel"
      data-screen-id="SCR-UI-32"
      aria-label="단가 재협상 대상"
    >
      <header className={table.toolbar}>
        <h2>단가 재협상 대상</h2>
        <div className={table.actions}>
          <Button
            data-testid="renegotiation-download"
            pending={exporting}
            disabled={list.loading || !!list.error}
            onClick={() => void download()}
          >
            Excel 다운로드
          </Button>
          {onClose && (
            <Button data-testid="renegotiation-close" onClick={onClose}>
              프로젝트 원가 분석으로
            </Button>
          )}
        </div>
      </header>
      <p className={table.help}>
        기준월 {data?.calculation_month ?? "–"} · 단위: 원 ·{" "}
        <Badge tone={data?.closed ? "locked" : "info"}>
          {data?.closed ? "마감 스냅샷" : "미마감"}
        </Badge>
      </p>
      <div className={styles.summary}>
        {[
          ["대상 인원", money(data?.summary.psn_cnt)],
          ["매출 가중 평균 이익률", ratio(data?.summary.weighted_margin_rt)],
          [
            "가정 인상 연 증가",
            money(data?.summary.assumed_annual_profit_increase_amt),
          ],
          [
            "목표 단가 연 증가",
            money(data?.summary.target_annual_profit_increase_amt),
          ],
        ].map(([label, value]) => (
          <section
            className={table.card}
            key={label}
            data-testid={`renegotiation-summary-${["대상 인원", "매출 가중 평균 이익률", "가정 인상 연 증가", "목표 단가 연 증가"].indexOf(label)}`}
          >
            <h3>{label}</h3>
            <strong>{value}</strong>
          </section>
        ))}
      </div>
      <form
        className={table.search}
        data-testid="renegotiation-search-form"
        onSubmit={(event) => {
          event.preventDefault();
          apply(draft);
        }}
      >
        <label>
          가정 인상률 (%)
          <input
            data-testid="renegotiation-raise"
            aria-label="가정 인상률 (%)"
            type="number"
            min="0"
            max="50"
            step="0.1"
            value={
              draft.raiseRate === ""
                ? (data?.raise_rate ?? "")
                : draft.raiseRate
            }
            aria-invalid={!!validation || list.error?.field === "raiseRate"}
            onChange={(event) => {
              const value =
                event.target.value === "" ? "" : Number(event.target.value);
              const next: RenegotiationFilters = { ...draft, raiseRate: value };
              setDraft(next);
              apply(next);
            }}
          />
        </label>
        <label>
          갱신 기간
          <select
            data-testid="renegotiation-window"
            aria-label="갱신 기간"
            value={draft.windowMonths}
            onChange={(event) =>
              setDraft({ ...draft, windowMonths: Number(event.target.value) })
            }
          >
            {[3, 6, 12].map((value) => (
              <option
                key={value}
                value={value}
                data-testid={`renegotiation-window-option-${value}`}
              >
                {value}개월 안
              </option>
            ))}
          </select>
        </label>
        <label>
          이익률
          <select
            data-testid="renegotiation-margin"
            aria-label="이익률"
            value={draft.marginBelow}
            onChange={(event) =>
              setDraft({ ...draft, marginBelow: Number(event.target.value) })
            }
          >
            {[10, 15, 20].map((value) => (
              <option
                key={value}
                value={value}
                data-testid={`renegotiation-margin-option-${value}`}
              >
                {value}% 미만
              </option>
            ))}
          </select>
        </label>
        <label>
          직원구분
          <select
            data-testid="renegotiation-employee"
            aria-label="직원구분"
            value={draft.empType}
            onChange={(event) =>
              setDraft({ ...draft, empType: event.target.value })
            }
          >
            <option value="">전체</option>
            {employee.codes.map((code) => (
              <option
                key={code.code}
                value={code.code}
                data-testid={`renegotiation-employee-option-${code.code.toLowerCase().replaceAll("_", "-")}`}
              >
                {code.name}
              </option>
            ))}
          </select>
        </label>
        <label>
          프로젝트
          <select
            data-testid="renegotiation-project"
            aria-label="프로젝트"
            value={draft.projectId}
            onChange={(event) =>
              setDraft({ ...draft, projectId: event.target.value })
            }
          >
            <option value="">전체</option>
            {projects.data
              ?.filter((row) => row.type === "MO" || row.type === "DP")
              .map((row) => (
                <option
                  key={row.id}
                  value={row.id}
                  data-testid={`renegotiation-project-option-${row.id}`}
                >
                  {row.name}
                </option>
              ))}
          </select>
        </label>
        <Button data-testid="renegotiation-reset" type="button" onClick={reset}>
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
      <ErrorBanner error={employee.error} onRetry={employee.retry} />
      <ErrorBanner
        error={projects.error}
        onRetry={() => void projects.reload().catch(() => {})}
      />
      <ErrorBanner
        error={list.error}
        pending={list.loading}
        onRetry={() => void list.reload().catch(() => {})}
      />
      <ErrorBanner error={exportError} onRetry={() => void download()} />
      {!list.error &&
        data?.warnings.map((warning, index) => (
          <p
            role="status"
            key={`${index}-${warning}`}
            data-testid={`renegotiation-warning-${index}`}
          >
            <Badge tone="warning">데이터 결손</Badge> {warning}
          </p>
        ))}
      {list.loading ? (
        <Skeleton />
      ) : (
        !list.error &&
        data && (
          <>
            <div className={table.toolbar}>
              <span>총 {data.total}건</span>
            </div>
            <div className={table.tableScroll}>
              <table className={table.table} aria-label="재협상 대상 목록">
                <thead>
                  <tr>
                    {columns.map(([field, label]) => (
                      <th key={field}>
                        <button
                          data-testid={`renegotiation-sort-${field.replaceAll("_", "-")}`}
                          type="button"
                          onClick={() => sort(field)}
                        >
                          {label}
                        </button>
                      </th>
                    ))}
                    <th>후속 조치</th>
                  </tr>
                </thead>
                <tbody>
                  {data.items.length === 0 && (
                    <tr>
                      <td colSpan={12} className={table.empty}>
                        조건에 맞는 결과가 없습니다
                      </td>
                    </tr>
                  )}
                  {data.items.map((row) => (
                    <tr
                      key={row.asgn_id}
                      data-testid={`renegotiation-row-${row.asgn_id}`}
                    >
                      <td>
                        <button
                          data-testid={`renegotiation-person-${row.asgn_id}`}
                          className={table.textButton}
                          onClick={() => setDetailId(row.dev_id)}
                        >
                          {row.dev_nm}
                        </button>
                      </td>
                      <td>
                        {employee.codes.find(
                          (code) => code.code === row.emp_se_cd,
                        )?.name ?? row.emp_se_cd}
                      </td>
                      <td>{row.prj_nm}</td>
                      <td>
                        {row.end_dt}{" "}
                        <Badge
                          tone={row.days_remaining <= 30 ? "warning" : "info"}
                        >
                          D-{row.days_remaining}
                        </Badge>
                      </td>
                      <td className={table.amount}>
                        {row.mm_qty == null ? "–" : row.mm_qty.toFixed(2)}
                      </td>
                      <td className={table.amount}>
                        {money(row.bill_untprc_amt)}
                      </td>
                      <td className={table.amount}>
                        {money(row.allocated_cost_amt)}
                      </td>
                      <td>
                        <Badge
                          tone={
                            row.margin_rt == null
                              ? "neutral"
                              : row.margin_rt < 0
                                ? "danger"
                                : "warning"
                          }
                        >
                          {ratio(row.margin_rt)}
                        </Badge>
                      </td>
                      <td className={table.amount}>
                        {money(row.target_price_amt)}
                      </td>
                      <td className={table.amount}>
                        {ratio(row.required_raise_rt)}
                      </td>
                      <td className={table.amount}>
                        {money(row.assumed_annual_profit_increase_amt)}
                      </td>
                      <td>
                        <Button
                          data-testid={`renegotiation-rate-${row.asgn_id}`}
                          small
                          onClick={() =>
                            navigate("/UI-12", {
                              state: { assignmentId: row.asgn_id },
                            })
                          }
                        >
                          단가 변경
                        </Button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <Pagination
              page={filters.page + 1}
              size={filters.size}
              total={data.total}
              onPage={(page) => setFilters({ ...filters, page: page - 1 })}
              onSize={(size) => {
                setDraft({ ...draft, size });
                setFilters({ ...filters, size, page: 0 });
              }}
            />
            <Button
              data-testid="renegotiation-basis-toggle"
              onClick={() => setBasis(!basis)}
            >
              월별 배분 근거 {basis ? "접기" : "보기"}
            </Button>
            {basis && (
              <AllocationPanel
                month={data.calculation_month}
                projectId={
                  filters.projectId ? Number(filters.projectId) : undefined
                }
              />
            )}
          </>
        )
      )}
      <section className={table.card}>
        <h3>계산 방법</h3>
        <p>
          현재 기준월 자료를 12개월 유지하는 가정이며 실제 향후 계약을 보장하지
          않습니다. 가정 변경은 저장되지 않습니다.
        </p>
        <div className={table.tableScroll}>
          <table className={table.table} aria-label="재협상 계산 방법">
            <thead>
              <tr>
                <th>항목</th>
                <th>산식</th>
              </tr>
            </thead>
            <tbody>
              <tr>
                <td>이익률</td>
                <td>(단가 × MM − 배분 원가) ÷ (단가 × MM)</td>
              </tr>
              <tr>
                <td>목표 단가</td>
                <td>
                  (배분 원가 ÷ MM) ÷ (1 − 목표 이익률{" "}
                  {ratio(data?.target_margin_rate)}) · 표시만 만원 올림
                </td>
              </tr>
              <tr>
                <td>필요 인상률</td>
                <td>(올림 전 목표 단가 − 현재 단가) ÷ 현재 단가</td>
              </tr>
              <tr>
                <td>가정 연 증가</td>
                <td>현재 단가 × 가정 인상률 × MM × 12</td>
              </tr>
              <tr>
                <td>매출 가중 평균 이익률</td>
                <td>
                  (대상 매출 합 − 배분 원가 합) ÷ 대상 매출 합 · 0분모는 –
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>
      {detailId != null && (
        <DeveloperDrawer
          id={detailId}
          onClose={() => setDetailId(null)}
          onSaved={async () => {
            await list.reload();
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
    </section>
  );
}

export { UI32Screen as RenegotiationPanel };
