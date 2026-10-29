import { useCallback } from "react";
import { Link } from "react-router-dom";
import { Badge, Button, LockedBanner, Skeleton } from "../../shared/Controls";
import { Can } from "../../shared/Can";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { useAdminRead } from "../admin/useAdminResource";
import { useDeveloperReadOnly } from "../developers/useDeveloperReadOnly";
import { ProjectMemoPanel } from "../memos/ProjectMemoPanel";
import { ProjectProgressBars } from "../apiops/ProjectProgressWidgets";
import { getProgressParameters } from "../apiops/boardClient";
import {
  getProjectProgress,
  type ProgressProject,
  type ProgressPhase,
} from "./progressClient";
import styles from "./ProjectProgress.module.css";
import table from "./ProjectLifecycle.module.css";

export const projectAllowsProgressInput = (project: ProgressProject) =>
  project.use_yn === "Y" &&
  ["PLAN", "RUN", "HOLD"].includes(project.prj_stts_cd);
export function ProjectProgressReadOnly({
  project,
}: {
  project: ProgressProject;
}) {
  return (
    <LockedBanner>
      {project.use_yn === "N"
        ? "사용 안 함"
        : project.prj_stts_cd === "END"
          ? "종료"
          : "실주"}{" "}
      프로젝트입니다. 기록은 읽기만 할 수 있습니다.
    </LockedBanner>
  );
}
export type PhaseAction = (
  phase: ProgressPhase,
  action: "complete" | "uncomplete" | "period",
  reload: () => Promise<unknown>,
) => void;

// SCR-UI-01#progress: summary, stage table and existing weekly memo component.
export function ProjectProgressPanel({
  project,
  onChanged,
  onPhaseAction,
}: {
  project: ProgressProject;
  onChanged: () => Promise<unknown>;
  onPhaseAction?: PhaseAction;
}) {
  const state = useAdminRead(
    useCallback(() => getProjectProgress(project.prj_id), [project.prj_id]),
  );
  const parameters = useAdminRead(getProgressParameters);
  const mobile = useDeveloperReadOnly();
  const allowed = projectAllowsProgressInput(project);
  const row = state.data;
  const refresh = async () => {
    await state.reload();
    await onChanged();
  };
  const staleParam = parameters.data?.find(
    (p) => p.oprt_std_id === "SIG_STALE_DAYS",
  );
  const staleDays =
    staleParam?.num_val ??
    (staleParam?.txt_val != null ? Number(staleParam.txt_val) : null);
  const stale =
    row?.memo_elapsed_days != null &&
    staleDays != null &&
    row.memo_elapsed_days > staleDays;
  const today = new Intl.DateTimeFormat("en-CA", {
    timeZone: "Asia/Seoul",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).formatToParts(new Date());
  const date = ["year", "month", "day"]
    .map((part) => today.find((p) => p.type === part)?.value)
    .join("-");
  const short = (value: string | null) => (value ? value.slice(2, 7) : "–");
  const operating =
    project.cntrct_ty_cd === "MO" || project.cntrct_ty_cd === "DP";
  return (
    <section
      data-testid="project-progress-panel"
      data-screen-id="SCR-UI-01"
      aria-label="진행 현황"
    >
      <h3>진행 현황</h3>
      <Link
        data-testid="project-progress-cost-analysis"
        to={`/UI-04?open=${project.prj_id}`}
      >
        원가 분석
      </Link>
      <ErrorBanner
        error={state.error}
        onRetry={() => void state.reload().catch(() => {})}
      />
      <ErrorBanner
        error={parameters.error}
        onRetry={() => void parameters.reload().catch(() => {})}
      />
      {state.loading ? (
        <Skeleton />
      ) : (
        !state.error &&
        row && (
          <>
            {!allowed ? (
              <ProjectProgressReadOnly project={project} />
            ) : (
              row.light && (
                <div
                  role="status"
                  data-testid="project-progress-light"
                  className={`${styles.banner} ${row.light === "위험" ? styles.risk : row.light === "주의" ? styles.warn : styles.normal}`}
                >
                  <strong>{row.light}</strong>
                  <span>{row.reasons.join(" · ")}</span>
                </div>
              )
            )}
            <div className={styles.summary}>
              <p>
                PM <strong>{row.pmName}</strong> / 현재 단계{" "}
                <strong>{row.currentPhase?.phs_nm ?? "–"}</strong>
              </p>
              <ProjectProgressBars
                plan={row.plan_prgs_rt}
                actual={row.actl_prgs_rt}
              />
              <p>
                계획 대비{" "}
                <strong>
                  {row.schedule_diff_rt > 0 ? "+" : ""}
                  {row.schedule_diff_rt.toFixed(1)}%p
                </strong>
              </p>
              <p className={styles.help}>
                {operating
                  ? "운영형(월단위) 프로젝트는 진척률을 기간 기준으로 자동 계산합니다."
                  : "실적 진척률은 가장 최근 주간 메모를 기준으로 표시합니다."}
              </p>
              {project.cntrct_ty_cd === "TK" && row.cost_use_rt != null && (
                <div data-testid="project-progress-cost">
                  원가 사용률 <strong>{row.cost_use_rt.toFixed(1)}%</strong> ·
                  진척률보다 {row.cost_progress_diff_rt?.toFixed(1)}%p
                  <span
                    tabIndex={0}
                    data-testid="project-cost-info"
                    className={styles.amountInfo}
                    aria-label={`예산 ${row.budget_amt?.toLocaleString("ko-KR")}원, 누적 원가 ${row.cumulative_cost_amt?.toLocaleString("ko-KR")}원`}
                  >
                    ⓘ 금액
                    <span role="tooltip" className={styles.amountTip}>
                      예산 {row.budget_amt?.toLocaleString("ko-KR")}원 · 누적
                      원가 {row.cumulative_cost_amt?.toLocaleString("ko-KR")}원
                    </span>
                  </span>
                </div>
              )}
              <p
                data-testid="project-memo-elapsed"
                className={stale ? styles.stale : ""}
              >
                마지막 업데이트 {row.last_memo_dt ?? "메모없음"} ·{" "}
                {row.memo_elapsed_days == null
                  ? "– (업데이트 지연 판단 제외)"
                  : `${row.memo_elapsed_days}일 경과`}
              </p>
              {row.missing_salary_months.length > 0 && (
                <p className={table.warning}>
                  급여가 올라가지 않은 달이 있습니다(
                  {row.missing_salary_months.join(", ")}).
                </p>
              )}
            </div>
            <h3>단계</h3>
            {row.phases.length === 0 ? (
              <p className={table.empty}>단계 표가 없습니다.</p>
            ) : (
              <div className={table.tableScroll}>
                <table className={table.table}>
                  <thead>
                    <tr>
                      {[
                        "단계",
                        "비중",
                        "예정 기간",
                        "완료일",
                        "상태",
                        "동작",
                      ].map((h) => (
                        <th
                          key={h}
                          data-testid={`project-phase-column-${["단계", "비중", "예정 기간", "완료일", "상태", "동작"].indexOf(h)}`}
                        >
                          {h}
                        </th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {row.phases.map((phase) => (
                      <tr
                        key={phase.phs_id}
                        data-testid={`project-phase-${phase.phs_id}`}
                      >
                        <td>{phase.phs_nm}</td>
                        <td>{phase.wght_rt}%</td>
                        <td>
                          {short(phase.plan_bgn_dt)} ~{" "}
                          {short(phase.plan_end_dt)}
                        </td>
                        <td>{phase.cmpl_dt ?? "–"}</td>
                        <td>
                          <Badge
                            tone={
                              phase.status === "완료"
                                ? "success"
                                : phase.status === "지연"
                                  ? "danger"
                                  : phase.status === "진행 중"
                                    ? "info"
                                    : "neutral"
                            }
                          >
                            {phase.status}
                          </Badge>
                        </td>
                        <td>
                          {allowed && (
                            <Can perm="UI-01.update">
                              <div className={styles.phaseActions}>
                                {phase.cmpl_dt ? (
                                  <Button
                                    small
                                    data-testid={`project-phase-uncomplete-${phase.phs_id}`}
                                    disabled={!onPhaseAction}
                                    onClick={() =>
                                      onPhaseAction?.(
                                        phase,
                                        "uncomplete",
                                        refresh,
                                      )
                                    }
                                  >
                                    완료 취소
                                  </Button>
                                ) : (
                                  <>
                                    {phase.plan_bgn_dt &&
                                      phase.plan_bgn_dt <= date && (
                                        <Button
                                          small
                                          data-testid={`project-phase-complete-${phase.phs_id}`}
                                          disabled={!onPhaseAction}
                                          onClick={() =>
                                            onPhaseAction?.(
                                              phase,
                                              "complete",
                                              refresh,
                                            )
                                          }
                                        >
                                          완료
                                        </Button>
                                      )}
                                    {!mobile && (
                                      <Button
                                        small
                                        data-testid={`project-phase-period-${phase.phs_id}`}
                                        disabled={!onPhaseAction}
                                        onClick={() =>
                                          onPhaseAction?.(
                                            phase,
                                            "period",
                                            refresh,
                                          )
                                        }
                                      >
                                        기간 수정
                                      </Button>
                                    )}
                                  </>
                                )}
                              </div>
                            </Can>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
            <ProjectMemoPanel project={project} onChanged={refresh} />
          </>
        )
      )}
    </section>
  );
}
