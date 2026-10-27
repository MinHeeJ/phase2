import type { ReactNode } from "react";
import type { ProjectDetailTab } from "../projects/ProjectDrawer";
import type { ProjectDetail } from "../projects/client";
import { ProjectProgressPanel, type PhaseAction } from "./ProjectProgressPanel";
import { ProjectIssuePanel, ProjectIssueTabLabel } from "./ProjectIssuePanel";
import type { ProjectIssueRow } from "./progressClient";
import type { ProgressProject } from "./progressClient";

function progressProject(project: ProjectDetail): ProgressProject {
  const type = project.cntrct_ty_cd;
  if (type !== "TK" && type !== "IN" && type !== "MO" && type !== "DP")
    throw new Error("계약유형을 확인하세요.");
  return { ...project, cntrct_ty_cd: type };
}

export interface ProjectProgressActions {
  phase?: (
    project: ProjectDetail,
    refresh: () => Promise<unknown>,
  ) => PhaseAction;
  createIssue?: (
    project: ProjectDetail,
    reload: () => Promise<unknown>,
  ) => void;
  editIssue?: (
    project: ProjectDetail,
    issue: ProjectIssueRow,
    reload: () => Promise<unknown>,
  ) => void;
  issueLabel?: (project: ProjectDetail) => ReactNode;
}
export function projectProgressTabs(
  actions: ProjectProgressActions = {},
): ProjectDetailTab[] {
  return [
    {
      key: "progress",
      label: "진행 현황",
      render: (project, refresh) => (
        <ProjectProgressPanel
          project={progressProject(project)}
          onChanged={refresh}
          onPhaseAction={actions.phase?.(project, refresh)}
        />
      ),
    },
    {
      key: "issues",
      label: (project) =>
        actions.issueLabel?.(project) ?? (
          <ProjectIssueTabLabel projectId={project.prj_id} />
        ),
      render: (project) => (
        <ProjectIssuePanel
          project={progressProject(project)}
          onCreate={
            actions.createIssue
              ? (reload) => actions.createIssue!(project, reload)
              : undefined
          }
          onEdit={
            actions.editIssue
              ? (issue, reload) => actions.editIssue!(project, issue, reload)
              : undefined
          }
        />
      ),
    },
  ];
}
