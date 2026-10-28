import { useCallback, useEffect } from "react";
import { Badge, Button, Skeleton } from "../../shared/Controls";
import { Can } from "../../shared/Can";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { useAdminRead } from "../admin/useAdminResource";
import {
  listProjectIssues,
  type ProgressProject,
  type ProjectIssueRow,
} from "./progressClient";
import {
  projectAllowsProgressInput,
  ProjectProgressReadOnly,
} from "./ProjectProgressPanel";
import styles from "./ProjectProgress.module.css";
import table from "./ProjectLifecycle.module.css";

// SCR-UI-01#issues: includes closed records; tab count includes OPEN/DOING only.
export function ProjectIssuePanel({
  project,
  onCreate,
  onEdit,
  onCountChange,
}: {
  project: ProgressProject;
  onCreate?: (reload: () => Promise<unknown>) => void;
  onEdit?: (issue: ProjectIssueRow, reload: () => Promise<unknown>) => void;
  onCountChange?: (count: number) => void;
}) {
  const state = useAdminRead(
    useCallback(() => listProjectIssues(project.prj_id), [project.prj_id]),
  );
  const allowed = projectAllowsProgressInput(project);
  useEffect(() => {
    if (state.data && !state.error) onCountChange?.(state.data.open_count);
  }, [state.data, state.error, onCountChange]);
  const parts = new Intl.DateTimeFormat("en", {
    timeZone: "Asia/Seoul",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).formatToParts(new Date());
  const today = ["year", "month", "day"]
    .map((key) => parts.find((p) => p.type === key)!.value)
    .join("-");
  return (
    <section
      data-testid="project-issue-panel"
      data-screen-id="SCR-UI-01"
      aria-label="프로젝트 이슈"
    >
      <div className={styles.toolbar}>
        <h3>이슈 ({state.data?.open_count ?? "–"})</h3>
        {allowed && (
          <Can perm="UI-01.update">
            <Button
              data-testid="project-issue-create"
              disabled={!onCreate || state.loading || !!state.error}
              onClick={() => onCreate?.(state.reload)}
            >
              + 이슈 등록
            </Button>
          </Can>
        )}
      </div>
      {!allowed && <ProjectProgressReadOnly project={project} />}
      <p className={styles.help}>
        기한이 지난 이슈는 빨간색으로 보입니다.
        {allowed && " 줄을 누르면 고칠 수 있습니다."}
      </p>
      <ErrorBanner
        error={state.error}
        onRetry={() => void state.reload().catch(() => {})}
      />
      {state.loading ? (
        <Skeleton />
      ) : (
        !state.error &&
        state.data &&
        (state.data.items.length === 0 ? (
          <p className={table.empty}>등록된 이슈가 없습니다.</p>
        ) : (
          <div className={table.tableScroll}>
            <table className={table.table}>
              <thead>
                <tr>
                  {[
                    "중요도",
                    "제목 · 조치 내용",
                    "담당자",
                    "기한",
                    "상태",
                    "등록일",
                  ].map((h) => (
                    <th
                      key={h}
                      data-testid={`project-issue-column-${["중요도", "제목 · 조치 내용", "담당자", "기한", "상태", "등록일"].indexOf(h)}`}
                    >
                      {h}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {state.data.items.map((issue) => {
                  const overdue =
                    issue.issue_stts_cd !== "CLOSED" &&
                    issue.ddln_dt != null &&
                    issue.ddln_dt < today;
                  const days =
                    issue.ddln_dt == null
                      ? null
                      : Math.floor(
                          (Date.parse(`${today}T00:00:00Z`) -
                            Date.parse(`${issue.ddln_dt}T00:00:00Z`)) /
                            86400000,
                        );
                  return (
                    <tr
                      key={issue.issue_id}
                      data-testid={`project-issue-${issue.issue_id}`}
                      onClick={() => {
                        if (allowed) onEdit?.(issue, state.reload);
                      }}
                    >
                      <td>
                        <Badge
                          tone={
                            issue.svrt_cd === "HIGH"
                              ? "danger"
                              : issue.svrt_cd === "MID"
                                ? "warning"
                                : "neutral"
                          }
                        >
                          {issue.svrt_cd === "HIGH"
                            ? "높음"
                            : issue.svrt_cd === "MID"
                              ? "중간"
                              : "낮음"}
                        </Badge>
                      </td>
                      <td className={styles.issueText}>
                        {allowed && onEdit ? (
                          <Button
                            variant="text"
                            data-testid={`project-issue-edit-${issue.issue_id}`}
                            onClick={(e) => {
                              e.stopPropagation();
                              onEdit(issue, state.reload);
                            }}
                          >
                            {issue.ttl_nm}
                          </Button>
                        ) : (
                          issue.ttl_nm
                        )}
                        {issue.actn_desc && <p>{issue.actn_desc}</p>}
                      </td>
                      <td>{issue.chrg_nm ?? "–"}</td>
                      <td
                        className={overdue ? styles.overdue : ""}
                        data-testid={`project-issue-deadline-${issue.issue_id}`}
                      >
                        {issue.ddln_dt?.slice(5) ?? "–"}
                        {overdue && <small> · {days}일 지남</small>}
                      </td>
                      <td>
                        <Badge
                          tone={
                            issue.issue_stts_cd === "CLOSED"
                              ? "locked"
                              : issue.issue_stts_cd === "DOING"
                                ? "warning"
                                : "info"
                          }
                        >
                          {issue.issue_stts_cd === "CLOSED"
                            ? "종료"
                            : issue.issue_stts_cd === "DOING"
                              ? "조치 중"
                              : "열림"}
                        </Badge>
                      </td>
                      <td>{issue.reg_dt}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        ))
      )}
    </section>
  );
}
export function ProjectIssueTabLabel({
  projectId,
  revision = 0,
}: {
  projectId: number;
  revision?: number;
}) {
  const state = useAdminRead(
    useCallback(() => listProjectIssues(projectId), [projectId, revision]),
  );
  return <>이슈 ({state.error ? "–" : (state.data?.open_count ?? "–")})</>;
}
