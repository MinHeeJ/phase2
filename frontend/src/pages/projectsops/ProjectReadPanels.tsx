import { useCallback, type ReactNode } from "react";
import { Badge, Skeleton } from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { useAdminRead } from "../admin/useAdminResource";
import { useCodes } from "../shared/useCodes";
import { listProjectAssignments, listProjectHistory } from "./client";
import styles from "./ProjectLifecycle.module.css";

// SCR-UI-01: internal drawer panels at /UI-01, never separate routes.
function ProjectCollection<T>({
  title,
  read,
  empty,
  headings,
  cells,
}: {
  title: string;
  read: () => Promise<T[]>;
  empty: string;
  headings: string[];
  cells: (row: T) => ReactNode[];
}) {
  const state = useAdminRead(read);
  return (
    <section
      role="tabpanel"
      aria-label={title}
      data-testid={`project-${title === "변경 이력" ? "history" : "assignments"}`}
    >
      <h3>
        {title}
        {state.data && title === "투입 인력" ? ` (${state.data.length})` : ""}
      </h3>
      <ErrorBanner
        error={state.error}
        onRetry={() => void state.reload().catch(() => {})}
      />
      {state.loading ? (
        <Skeleton />
      ) : (
        !state.error &&
        (state.data?.length ? (
          <div className={styles.tableScroll}>
            <table className={styles.table}>
              <thead>
                <tr>
                  {headings.map((h) => (
                    <th key={h}>{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {state.data.map((row, index) => (
                  <tr
                    key={index}
                    data-testid={`project-${title === "변경 이력" ? "history" : "assignment"}-row-${index}`}
                  >
                    {cells(row).map((cell, n) => (
                      <td key={n}>{cell}</td>
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <p className={styles.empty}>{empty}</p>
        ))
      )}
    </section>
  );
}
export function ProjectAssignments({ projectId }: { projectId: number }) {
  const read = useCallback(
    () => listProjectAssignments(projectId),
    [projectId],
  );
  const codes = useCodes("ASSIGN_STATUS");
  return (
    <ProjectCollection
      title="투입 인력"
      read={read}
      empty="투입된 인력이 없습니다."
      headings={["이름", "직무", "투입 ~ 철수", "투입률", "상태"]}
      cells={(r) => [
        r.name,
        r.job,
        `${r.beginDate} ~ ${r.endDate ?? "–"}`,
        `${(r.assignmentRate * 100).toLocaleString("ko-KR")}%`,
        <Badge tone={r.status === "ACTIVE" ? "info" : "locked"}>
          {codes.codes.find((c) => c.code === r.status)?.name ?? r.status}
        </Badge>,
      ]}
    />
  );
}
export function ProjectHistory({
  projectId,
  revision = 0,
}: {
  projectId: number;
  revision?: number;
}) {
  const read = useCallback(
    () => listProjectHistory(projectId),
    [projectId, revision],
  );
  return (
    <ProjectCollection
      title="변경 이력"
      read={read}
      empty="아직 바꾼 기록이 없습니다."
      headings={["일시", "항목", "바꾸기 전", "바꾼 뒤", "처리자"]}
      cells={(r) => [
        r.changedAt.replace("T", " "),
        r.field === "use_yn" ? "사용 여부" : r.field,
        r.before ?? "–",
        r.after ?? "–",
        r.actor ?? "기본 사용자",
      ]}
    />
  );
}
