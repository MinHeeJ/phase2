import { useCallback, useRef, useState } from "react";
import { Badge, Button, Skeleton } from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { useAdminRead } from "../admin/useAdminResource";
import { useCodes } from "../shared/useCodes";
import {
  getBoardForecast,
  getBoardOpportunities,
  getBoardScenarioRows,
  type BoardFilters,
} from "./client";
import {
  boardScenarioDefaults,
  compareBoardScenario,
  type BoardScenario,
} from "./scenarios";
import controls from "../../shared/Controls.module.css";
import styles from "./WorkforceBoard.module.css";

export function BoardForecastPanel({ filters }: { filters: BoardFilters }) {
  const forecast = useAdminRead(
    useCallback(() => getBoardForecast(filters), [filters]),
  );
  const [editing, setEditing] = useState<BoardScenario | null>(null);
  const [scenarios, setScenarios] = useState<BoardScenario[]>([]);
  const [showSources, setShowSources] = useState(false);
  const nextId = useRef(1);
  const jobs = useCodes("JOB"),
    grades = useCodes("GRADE");
  const sources = useAdminRead(
    useCallback(
      async () =>
        showSources
          ? {
              opportunities: await getBoardOpportunities(),
              rows: await getBoardScenarioRows(filters),
            }
          : null,
      [showSources, filters],
    ),
  );
  const change = <K extends keyof BoardScenario>(
    key: K,
    value: BoardScenario[K],
  ) => setEditing((s) => (s ? { ...s, [key]: value } : s));
  return (
    <div role="tabpanel" data-testid="board-forecast-panel">
      <p className={styles.info}>
        공통 예측 · 선택 연도의 미래 최대 6개월 · 추정 · 가동률 목표 90%
      </p>
      {(filters.skill || filters.emp_se_cd || filters.free_only) && (
        <p role="status" className={styles.warning}>
          수요·공급 API는 연도·직무·등급 기준입니다. 스킬·직원구분·비는 인원
          조건은 달력에만 적용됩니다.
        </p>
      )}
      <ErrorBanner
        error={forecast.error}
        onRetry={() => void forecast.reload().catch(() => {})}
      />
      {forecast.loading ? (
        <Skeleton />
      ) : (
        !forecast.error &&
        forecast.data && (
          <>
            {!forecast.data.length ? (
              <p className={controls.empty}>예측할 미래월이 없습니다.</p>
            ) : (
              <div className={controls.tableScroll}>
                <table>
                  <thead>
                    <tr>
                      {[
                        "월",
                        "직무",
                        "등급",
                        "공급 MM",
                        "수요 MM",
                        "차이 MM",
                        "가동률 / 목표 90%",
                        "대기 비용",
                        "상태",
                      ].map((label) => (
                        <th
                          key={label}
                          data-testid={`board-forecast-column-${["월", "직무", "등급", "공급 MM", "수요 MM", "차이 MM", "가동률 / 목표 90%", "대기 비용", "상태"].indexOf(label)}`}
                        >
                          {label}
                        </th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {forecast.data.map((row) => (
                      <tr
                        key={`${row.month}-${row.job_cd}-${row.grd_cd}`}
                        data-testid={`board-forecast-${row.month}-${row.job_cd}-${row.grd_cd || "none"}`}
                      >
                        <td>{row.month}</td>
                        <td>
                          {jobs.codes.find((c) => c.code === row.job_cd)
                            ?.name ?? row.job_cd}
                        </td>
                        <td>{row.grd_cd || "미분류"}</td>
                        <td>{row.supply_mm.toFixed(2)}</td>
                        <td>{row.demand_mm.toFixed(2)}</td>
                        <td>
                          <Badge
                            tone={row.shortage_mm > 0.4 ? "danger" : "neutral"}
                          >
                            {row.remaining_mm.toFixed(2)}
                          </Badge>
                        </td>
                        <td>
                          {row.utilization_rate == null
                            ? "–"
                            : `${row.utilization_rate.toFixed(2)}%`}
                          <meter
                            min={0}
                            max={100}
                            low={90}
                            optimum={90}
                            value={row.utilization_rate ?? 0}
                            aria-label={`${row.month} 가동률`}
                          />
                          목표 90%
                        </td>
                        <td>
                          {row.idle_cost.toLocaleString("ko-KR")}원{" "}
                          <Badge tone="warning">추정</Badge>
                        </td>
                        <td>
                          {row.warnings.map((w) => (
                            <p
                              key={w}
                              data-testid={`board-forecast-warning-${row.month}-${row.job_cd}-${row.grd_cd || "none"}-${row.warnings.indexOf(w)}`
                                .toLowerCase()
                                .replace(/[^a-z0-9]+/g, "-")}
                            >
                              {w}
                            </p>
                          ))}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
            <section className={styles.scenarios} data-testid="board-scenarios">
              <h2>시나리오 비교</h2>
              <p>
                브라우저 메모리에서 최대 3개 · 원본 데이터는 변경하지 않습니다.
              </p>
              <Button
                data-testid="board-scenario-add"
                disabled={
                  scenarios.length >= 3 ||
                  editing !== null ||
                  !forecast.data.length
                }
                onClick={() => {
                  setShowSources(true);
                  setEditing(boardScenarioDefaults(nextId.current++));
                }}
              >
                시나리오 추가
              </Button>
              <Button
                data-testid="board-scenario-reset"
                onClick={() => {
                  setScenarios([]);
                  setEditing(null);
                }}
              >
                시나리오 초기화
              </Button>
              <ErrorBanner
                error={sources.error}
                onRetry={() => void sources.reload().catch(() => {})}
              />
              {editing && (
                <form
                  data-testid="board-scenario-editor"
                  className={styles.search}
                  onSubmit={(e) => {
                    e.preventDefault();
                    if (
                      sources.loading ||
                      sources.error ||
                      !sources.data ||
                      scenarios.length >= 3
                    )
                      return;
                    setScenarios((s) => [...s, editing]);
                    setEditing(null);
                  }}
                >
                  <label>
                    시나리오 이름
                    <input
                      data-testid="board-scenario-name"
                      required
                      maxLength={50}
                      value={editing.name}
                      onChange={(e) => change("name", e.target.value)}
                    />
                  </label>
                  <fieldset>
                    <legend>기회 수주 가정 · 선택 OPEN 기회 100%</legend>
                    {sources.loading ? (
                      <Skeleton />
                    ) : sources.data?.opportunities.length === 0 ? (
                      <p>진행 중인 기회가 없습니다.</p>
                    ) : (
                      sources.data?.opportunities.map((o) => (
                        <label
                          key={o.oppt_id}
                          data-testid={`board-scenario-opportunity-label-${o.oppt_id}`}
                        >
                          <input
                            data-testid={`board-scenario-opportunity-${o.oppt_id}`}
                            type="checkbox"
                            checked={editing.opportunityIds.includes(o.oppt_id)}
                            onChange={(e) =>
                              change(
                                "opportunityIds",
                                e.target.checked
                                  ? [...editing.opportunityIds, o.oppt_id]
                                  : editing.opportunityIds.filter(
                                      (id) => id !== o.oppt_id,
                                    ),
                              )
                            }
                          />
                          {o.oppt_nm} · 기준 {o.win_prblt_rt}%
                        </label>
                      ))
                    )}
                  </fieldset>
                  <fieldset>
                    <legend>
                      종료 후 연장 가정 · 기준 H 80% / M 50% / L 0%
                    </legend>
                    {(
                      [
                        ["high", "H"],
                        ["medium", "M"],
                        ["low", "L"],
                      ] as const
                    ).map(([key, label]) => (
                      <label
                        key={key}
                        data-testid={`board-scenario-${key}-label`}
                      >
                        {label} 확률(%)
                        <input
                          data-testid={`board-scenario-${key}`}
                          type="number"
                          min={0}
                          max={100}
                          step={1}
                          required
                          value={editing[key]}
                          onChange={(e) => change(key, e.target.valueAsNumber)}
                        />
                      </label>
                    ))}
                  </fieldset>
                  <fieldset>
                    <legend>인력 증감 가정</legend>
                    <label>
                      직무
                      <select
                        data-testid="board-scenario-job"
                        required
                        value={editing.job}
                        onChange={(e) => change("job", e.target.value)}
                      >
                        <option value="">선택</option>
                        {jobs.codes.map((c) => (
                          <option
                            key={c.code}
                            value={c.code}
                            data-testid={`board-scenario-job-option-${c.code.toLowerCase().replaceAll("_", "-")}`}
                          >
                            {c.name}
                          </option>
                        ))}
                      </select>
                    </label>
                    <label>
                      등급
                      <select
                        data-testid="board-scenario-grade"
                        value={editing.grade}
                        onChange={(e) => change("grade", e.target.value)}
                      >
                        <option value="">미분류</option>
                        {grades.codes.map((c) => (
                          <option
                            key={c.code}
                            value={c.code}
                            data-testid={`board-scenario-grade-option-${c.code.toLowerCase().replaceAll("_", "-")}`}
                          >
                            {c.name}
                          </option>
                        ))}
                      </select>
                    </label>
                    <label>
                      증감 인원
                      <input
                        data-testid="board-scenario-people"
                        type="number"
                        min={-100}
                        max={100}
                        step={1}
                        required
                        value={editing.people}
                        onChange={(e) =>
                          change("people", e.target.valueAsNumber)
                        }
                      />
                    </label>
                  </fieldset>
                  <Button
                    data-testid="board-scenario-cancel"
                    type="button"
                    onClick={() => setEditing(null)}
                  >
                    취소
                  </Button>
                  <Button
                    data-testid="board-scenario-save"
                    type="submit"
                    disabled={
                      sources.loading || !!sources.error || !sources.data
                    }
                  >
                    비교에 추가
                  </Button>
                </form>
              )}
              {!!scenarios.length && sources.data && (
                <div className={controls.tableScroll}>
                  <table>
                    <thead>
                      <tr>
                        {[
                          "기준 월·직무·등급",
                          "기준 공급 / 수요 / 차이",
                          ...scenarios.map((s) => s.name),
                        ].map((label, i) => (
                          <th
                            key={i}
                            data-testid={`board-scenario-column-${i}`}
                          >
                            {label}
                          </th>
                        ))}
                      </tr>
                    </thead>
                    <tbody>
                      {forecast.data.map((row) => {
                        const values = scenarios.map((s) =>
                          compareBoardScenario(
                            row,
                            s,
                            sources.data!.opportunities,
                            sources.data!.rows,
                          ),
                        );
                        return (
                          <tr
                            key={`${row.month}-${row.job_cd}-${row.grd_cd}`}
                            data-testid={`board-scenario-row-${row.month}-${row.job_cd}-${row.grd_cd || "none"}`}
                          >
                            <td>
                              {row.month} · {row.job_cd} ·{" "}
                              {row.grd_cd || "미분류"}
                            </td>
                            <td>
                              {row.supply_mm.toFixed(2)} /{" "}
                              {row.demand_mm.toFixed(2)} /{" "}
                              {row.remaining_mm.toFixed(2)} MM
                              <p>
                                기준 대기 비용{" "}
                                {row.idle_cost.toLocaleString("ko-KR")}원
                              </p>
                              <p>
                                기준 예상 영업이익{" "}
                                {row.baseline_operating_profit == null
                                  ? "– (원천 미확인)"
                                  : `${row.baseline_operating_profit.toLocaleString("ko-KR")}원`}
                              </p>
                            </td>
                            {values.map((value, i) => (
                              <td
                                key={scenarios[i].id}
                                data-testid={`board-scenario-value-${row.month}-${row.job_cd}-${row.grd_cd || "none"}-${scenarios[i].id}`
                                  .toLowerCase()
                                  .replace(/[^a-z0-9]+/g, "-")}
                              >
                                <p>
                                  공급 {value.supply.toFixed(2)} / 수요{" "}
                                  {value.demand.toFixed(2)} / 남는 MM{" "}
                                  {value.remaining.toFixed(2)}
                                </p>
                                <p>
                                  대기 비용{" "}
                                  {value.idleCost.toLocaleString("ko-KR")}원{" "}
                                  <Badge tone="warning">추정</Badge>
                                </p>
                                <p>
                                  가정 매출 증감{" "}
                                  {value.revenueDelta.toLocaleString("ko-KR")}원
                                  · 인건비 증감{" "}
                                  {value.laborDelta.toLocaleString("ko-KR")}원
                                </p>
                                <p>
                                  예상 영업이익{" "}
                                  {value.operatingProfit == null
                                    ? "–"
                                    : `${value.operatingProfit.toLocaleString("ko-KR")}원`}{" "}
                                  · 기준 대비{" "}
                                  {value.profitDelta.toLocaleString("ko-KR")}원
                                </p>
                                {i > 0 && (
                                  <p>
                                    첫 시나리오 대비 남는 MM{" "}
                                    {(
                                      value.remaining - values[0].remaining
                                    ).toFixed(2)}{" "}
                                    · 영업이익 증감{" "}
                                    {(
                                      value.profitDelta - values[0].profitDelta
                                    ).toLocaleString("ko-KR")}
                                    원
                                  </p>
                                )}
                                {value.warnings.map((w) => (
                                  <p
                                    key={w}
                                    role="status"
                                    data-testid={`board-scenario-warning-${row.month}-${row.job_cd}-${row.grd_cd || "none"}-${scenarios[i].id}-${value.warnings.indexOf(w)}`
                                      .toLowerCase()
                                      .replace(/[^a-z0-9]+/g, "-")}
                                  >
                                    {w}
                                  </p>
                                ))}
                              </td>
                            ))}
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                </div>
              )}
              {scenarios.map((s) => (
                <Button
                  data-testid={`board-scenario-remove-${s.id}`}
                  key={s.id}
                  onClick={() =>
                    setScenarios((rows) => rows.filter((r) => r.id !== s.id))
                  }
                >
                  {s.name} 삭제
                </Button>
              ))}
            </section>
          </>
        )
      )}
    </div>
  );
}
