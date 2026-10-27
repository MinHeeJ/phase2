import type { ReactNode } from "react";
import { Badge } from "../../shared/Controls";
import type { BoardLight, BoardSummary } from "./boardClient";
import styles from "./ProjectBoard.module.css";

export function ProjectSummaryCard({
  label,
  value,
  detail,
  tone,
}: {
  label: string;
  value: number;
  detail: string;
  tone?: "danger" | "warning";
}) {
  return (
    <div
      className={`${styles.summaryCard} ${tone ? styles[tone] : ""}`}
      data-testid={`board-summary-${label === "위험" ? "risk" : label === "열린 이슈" ? "issues" : label === "기한 지난 이슈" ? "overdue" : label === "업데이트 없음" ? "stale" : "projects"}`}
    >
      <span className={styles.summaryLabel}>{label}</span>
      <strong className={styles.summaryValue}>
        {value.toLocaleString("ko-KR")}
      </strong>
      <small className={styles.summaryDetail}>{detail}</small>
    </div>
  );
}
export function ProjectBoardSummary({ summary: s }: { summary: BoardSummary }) {
  return (
    <div
      data-testid="board-summary"
      className={styles.summary}
      aria-label="전체 프로젝트 요약"
    >
      <ProjectSummaryCard
        label="진행 프로젝트"
        value={s.project_count}
        detail={`정상 ${s.normal_count} · 주의 ${s.warn_count} · 위험 ${s.risk_count}`}
      />
      <ProjectSummaryCard
        label="위험"
        value={s.risk_count}
        detail="확인이 필요한 프로젝트"
        tone="danger"
      />
      <ProjectSummaryCard
        label="열린 이슈"
        value={s.open_issue_count}
        detail={`높음 ${s.high_open_issue_count}건`}
      />
      <ProjectSummaryCard
        label="기한 지난 이슈"
        value={s.overdue_issue_count}
        detail="기한이 지난 미종료 이슈"
        tone="danger"
      />
      <ProjectSummaryCard
        label="업데이트 없음"
        value={s.stale_project_count}
        detail="2주 넘게 상태 업데이트 없음"
        tone="warning"
      />
    </div>
  );
}
export function ProjectTrafficLight({
  light,
  reasons,
}: {
  light: BoardLight;
  reasons: string[];
}) {
  return (
    <span
      tabIndex={0}
      title={reasons.join("\n")}
      aria-label={`${light}: ${reasons.join(" ")}`}
    >
      <Badge
        tone={
          light === "위험" ? "danger" : light === "주의" ? "warning" : "info"
        }
      >
        {light}
      </Badge>
    </span>
  );
}
export function ProjectProgressBars({
  plan,
  actual,
}: {
  plan: number;
  actual: number;
}) {
  const danger = plan - actual >= 10;
  return (
    <div
      data-testid={`board-progress-${crypto.randomUUID()}`}
      className={styles.progress}
    >
      <div>
        <span>계획 {plan.toFixed(1)}%</span>
        <div
          className={styles.track}
          role="meter"
          aria-label="계획 진척률"
          aria-valuemin={0}
          aria-valuemax={100}
          aria-valuenow={plan}
        >
          <i className={styles.planBar} style={{ width: `${plan}%` }} />
        </div>
      </div>
      <div>
        <span>실적 {actual.toFixed(1)}%</span>
        <div
          className={styles.track}
          role="meter"
          aria-label="실적 진척률"
          aria-valuemin={0}
          aria-valuemax={100}
          aria-valuenow={actual}
        >
          <i
            className={danger ? styles.riskBar : styles.actualBar}
            style={{ width: `${actual}%` }}
          />
        </div>
      </div>
    </div>
  );
}
export function ProjectWarningStrip({ children }: { children: ReactNode }) {
  return (
    <div
      data-testid="board-warning-strip"
      className={styles.warning}
      role="status"
    >
      {children}
    </div>
  );
}
