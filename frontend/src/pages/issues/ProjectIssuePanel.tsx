import { useCallback, useEffect, useState } from "react";
import {
  Button,
  LockedBanner,
  Skeleton,
  SuccessToast,
} from "../../shared/Controls";
import { Can } from "../../shared/Can";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { useAdminRead } from "../admin/useAdminResource";
import type { ProjectDetailTab } from "../projects/ProjectDrawer";
import { IssueForm } from "./IssueForm";
import { IssueTable } from "./IssueTable";
import {
  listProjectIssues,
  issueWritable,
  type IssueProject,
  type IssueRow,
} from "./client";
import styles from "./Issues.module.css";

// SCR-UI-01#issues — viewport does not determine project write availability.
export function ProjectIssuePanel({
  project,
  onChanged,
}: {
  project: IssueProject;
  onChanged: () => Promise<unknown>;
}) {
  const state = useAdminRead(
    useCallback(() => listProjectIssues(project.prj_id), [project.prj_id]),
  );
  const [editing, setEditing] = useState<IssueRow | "new" | null>(null);
  const [message, setMessage] = useState("");
  const writable = issueWritable(project);
  useEffect(() => {
    setEditing(null);
    setMessage("");
  }, [project.prj_id, writable]);
  const reload = async () => {
    await state.reload();
    await onChanged();
  };
  return (
    <section
      role="tabpanel"
      aria-label="이슈"
      data-testid="project-issue-panel"
      data-screen-id="SCR-UI-01"
    >
      <div className={styles.toolbar}>
        <h3>이슈 ({state.data?.open_count ?? "–"})</h3>
        {writable && (
          <Can perm="UI-01.update">
            <Button data-testid="issue-add" onClick={() => setEditing("new")}>
              + 이슈 등록
            </Button>
          </Can>
        )}
      </div>
      {!writable && <LockedBanner>입력할 수 없는 프로젝트입니다.</LockedBanner>}
      <p className={styles.hint}>
        기한이 지난 이슈는 빨간색으로 보입니다. 줄을 누르면 고칠 수 있습니다.
      </p>
      {message && (
        <SuccessToast message={message} onDismiss={() => setMessage("")} />
      )}
      <ErrorBanner
        error={state.error}
        onRetry={() => void state.reload().catch(() => {})}
      />
      {state.loading ? (
        <Skeleton />
      ) : (
        !state.error &&
        state.data && (
          <IssueTable
            items={state.data.items}
            readOnly={!writable}
            onEdit={setEditing}
          />
        )
      )}
      {writable && editing && (
        <IssueForm
          key={editing === "new" ? "new" : editing.issue_id}
          project={project}
          initial={editing === "new" ? undefined : editing}
          reload={reload}
          onCancel={() => setEditing(null)}
          onSaved={() => {
            setMessage(
              editing === "new"
                ? "이슈를 등록했습니다."
                : "이슈를 저장했습니다.",
            );
            setEditing(null);
          }}
        />
      )}
    </section>
  );
}

function ProjectIssueTabLabel({ project }: { project: IssueProject }) {
  const state = useAdminRead(
    useCallback(() => listProjectIssues(project.prj_id), [project]),
  );
  return <>이슈 ({state.error ? "–" : (state.data?.open_count ?? "–")})</>;
}

export const issueDetailTab: ProjectDetailTab = {
  key: "issues",
  label: (project) => <ProjectIssueTabLabel project={project} />,
  render: (project, refresh) => (
    <ProjectIssuePanel project={project} onChanged={refresh} />
  ),
};
