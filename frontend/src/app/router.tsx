import { useCallback, useEffect, useRef, useState } from "react";
import { Route, Routes } from "react-router-dom";
import UI27Screen from "../pages/admin/UI27Screen";
import UI15Screen from "../pages/partners/UI15Screen";
import SalesPartManagement from "../pages/salespartsops/SalesPartManagement";
import UI02Screen from "../pages/developers/UI02Screen";
import UI01Screen from "../pages/projects/UI01Screen";
import UI05Screen from "../pages/us1/UI05Screen";
import { WithdrawalChanceSelect } from "../pages/us2/WithdrawalChanceSelect";
import { DispatchExpiryCell } from "../pages/us3/DispatchExpiry";
import UI11Screen from "../pages/us4/UI11Screen";
import UI13Screen from "../pages/billingsops/UI13Screen";
import { MonthlyShortagesPanel } from "../pages/us5/MonthlyShortagesPanel";
import UI12Screen, {
  type AssignmentScreenContext,
} from "../pages/assignments/UI12Screen";
import type { AssignmentDetail } from "../pages/assignments/client";
import { AssignmentActions } from "../pages/assignmentsops/AssignmentActions";
import { UI12MonthsPanel } from "../pages/months/UI12MonthsPanel";
import { UI12ChangesPanel } from "../pages/changes/UI12ChangesPanel";
import { AssignmentChangeForm } from "../pages/changes/AssignmentChangeForms";
import { changeNames } from "../pages/changes/client";
import { ProjectAssignmentMonthsPanel } from "../pages/projectsops/ProjectAssignmentMonthsPanel";
import { getProject } from "../pages/projects/client";
import { useAdminRead } from "../pages/admin/useAdminResource";
import { Button, Skeleton } from "../shared/Controls";
import { Can } from "../shared/Can";
import UI31Screen from "../pages/apiops/UI31Screen";
import { IssueListPanel } from "../pages/issues/UI31Screen";
import { issueDetailTab } from "../pages/issues/ProjectIssuePanel";
import { downloadProjectBoard } from "../pages/projectboardops/client";
import { projectProgressTabs } from "../pages/projectsops/ProjectProgressTabs";
import { ProjectPhaseDialog } from "../pages/phases/ProjectPhasePanel";
import type { ProjectDetail } from "../pages/projects/client";
import { ProjectBillStagePanel } from "../pages/billstages/ProjectBillStagePanel";
import { ProjectAllocationPanel } from "../pages/allocations/ProjectAllocationPanel";
import { ProjectExpensePanel } from "../pages/expenses/ProjectExpensePanel";
import { ProjectContractChangePanel } from "../pages/contractchanges/ProjectContractChangePanel";
import { ProjectPartChangeDialog } from "../pages/partchanges/ProjectPartChangePanel";
import { ProjectStaffPlanPanel } from "../pages/staffplans/ProjectStaffPlanPanel";
import { ProjectLifecycleActions } from "../pages/projectsops/ProjectActions";
import type {
  DeveloperFilters,
  DeveloperListItem,
} from "../pages/developers/client";
import { DeveloperDisableDialog } from "../pages/developersops/DeveloperActions";
import {
  DeveloperAssignments,
  DeveloperMonthlyCosts,
  DeveloperHistory,
} from "../pages/developersops/DeveloperReadPanels";
import { downloadDevelopers } from "../pages/developersops/client";
import { WorkStatusPanel } from "../pages/workstatus/WorkStatusPanel";
import { UploadDialogs } from "../pages/uploadsops/UploadDialogs";
import { uploadFailure } from "../pages/uploadsops/client";
import { useDeveloperReadOnly } from "../pages/developers/useDeveloperReadOnly";
import { ErrorBanner } from "../shared/ErrorBanner";
import { SuccessToast } from "../shared/Controls";
import type { ApiFailure } from "../shared/api";
import {
  AppShell,
  type BusinessScreenBindings,
} from "../pages/shared/AppShell";

function CommonCodesScreen({
  onDirtyChange,
  name,
  groupName,
}: BusinessScreenBindings) {
  const notify = useRef(onDirtyChange);
  notify.current = onDirtyChange;
  const reportDirty = useCallback(
    (dirty: boolean) => notify.current(dirty),
    [],
  );
  return (
    <UI27Screen onDirtyChange={reportDirty} name={name} groupName={groupName} />
  );
}

function PartnersScreen({
  onDirtyChange,
  ...bindings
}: BusinessScreenBindings) {
  const notify = useRef(onDirtyChange);
  notify.current = onDirtyChange;
  const reportDirty = useCallback(
    (dirty: boolean) => notify.current(dirty),
    [],
  );
  return (
    <UI15Screen
      {...bindings}
      onDirtyChange={reportDirty}
      salesParts={<SalesPartManagement onDirtyChange={reportDirty} />}
    />
  );
}

function DevelopersScreen({
  onDirtyChange,
  ...bindings
}: BusinessScreenBindings) {
  const notify = useRef(onDirtyChange);
  notify.current = onDirtyChange;
  const reportDirty = useCallback(
    (dirty: boolean) => notify.current(dirty),
    [],
  );
  const [version, setVersion] = useState(0);
  const [disabled, setDisabled] = useState<DeveloperListItem[] | null>(null);
  const [upload, setUpload] = useState<"B.8" | "B.1" | null>(null);
  const [message, setMessage] = useState("");
  const [error, setError] = useState<ApiFailure | null>(null);
  const exporting = useRef(false);
  const readOnly = useDeveloperReadOnly();
  useEffect(() => {
    if (readOnly) {
      setDisabled(null);
      setUpload(null);
    }
  }, [readOnly]);
  const reload = useCallback(async () => {
    setVersion((v) => v + 1);
  }, []);
  const exportList = async ({
    empType,
    job,
    status,
    q,
    use_yn,
  }: DeveloperFilters) => {
    if (exporting.current) return;
    exporting.current = true;
    setError(null);
    try {
      await downloadDevelopers({ empType, job, status, q, use_yn });
    } catch (e) {
      setError(uploadFailure(e));
    } finally {
      exporting.current = false;
    }
  };
  return (
    <>
      <UI02Screen
        {...bindings}
        onDirtyChange={reportDirty}
        refreshVersion={version}
        onDisable={setDisabled}
        onUpload={setUpload}
        onExport={(filters) => {
          void exportList(filters);
        }}
        detailTabs={[
          {
            key: "assignments",
            label: "투입 이력",
            render: (row) => <DeveloperAssignments id={row.dev_id} />,
          },
          {
            key: "work-status",
            label: "근무 상태 이력",
            render: (row, refresh) => (
              <WorkStatusPanel developerId={row.dev_id} onChanged={refresh} />
            ),
          },
          {
            key: "monthly-costs",
            label: "월별 원가",
            render: (row) => (
              <DeveloperMonthlyCosts key={version} id={row.dev_id} />
            ),
          },
          {
            key: "history",
            label: "변경 이력",
            render: (row) => <DeveloperHistory key={version} id={row.dev_id} />,
          },
        ]}
        dialogs={
          <>
            <ErrorBanner error={error} onRetry={() => setError(null)} />
            {!readOnly && disabled && (
              <DeveloperDisableDialog
                rows={disabled}
                onClose={() => setDisabled(null)}
                reload={reload}
                onComplete={setMessage}
              />
            )}
            {!readOnly && upload && (
              <UploadDialogs
                key={upload}
                form={upload}
                onClose={() => setUpload(null)}
                onApplied={async (result) => {
                  await reload();
                  setMessage(
                    upload === "B.1"
                      ? `급여 ${result.appliedRows}명분을 반영했습니다.`
                      : `${result.appliedRows}명을 반영했습니다.`,
                  );
                }}
              />
            )}
          </>
        }
      />
      {message && (
        <SuccessToast message={message} onDismiss={() => setMessage("")} />
      )}
    </>
  );
}

function ProjectsScreen({
  onDirtyChange,
  ...bindings
}: BusinessScreenBindings) {
  const notify = useRef(onDirtyChange);
  notify.current = onDirtyChange;
  const reportDirty = useCallback(
    (dirty: boolean) => notify.current(dirty),
    [],
  );
  const [partChange, setPartChange] = useState<{
    project: ProjectDetail;
    refresh: () => Promise<unknown>;
  } | null>(null);
  const [message, setMessage] = useState("");
  const [phaseDialog, setPhaseDialog] = useState<Omit<
    Parameters<typeof ProjectPhaseDialog>[0],
    "onCancel" | "onSaved"
  > | null>(null);
  const mobile = useDeveloperReadOnly();
  useEffect(() => {
    if (mobile)
      setPhaseDialog((current) =>
        current?.action.kind === "period" ? null : current,
      );
  }, [mobile]);
  return (
    <>
      <UI01Screen
        {...bindings}
        onDirtyChange={reportDirty}
        onPartChange={(project, refresh) => setPartChange({ project, refresh })}
        detailActions={(project, refresh) => (
          <ProjectLifecycleActions
            projectId={project.prj_id}
            useYn={project.use_yn}
            reload={refresh}
            onComplete={setMessage}
          />
        )}
        detailTabs={[
          ...projectProgressTabs({
            phase: (project) => (row, kind, reload) =>
              setPhaseDialog({ project, action: { row, kind }, reload }),
          }).filter((tab) => tab.key === "progress"),
          issueDetailTab,
          {
            key: "bill-stages",
            label: "청구 단계",
            types: ["TK"],
            render: (project, refresh, _, controls) => (
              <ProjectBillStagePanel
                projectId={project.prj_id}
                contractType={project.cntrct_ty_cd}
                useYn={project.use_yn}
                onChanged={refresh}
                onDirtyChange={controls.onDirtyChange}
              />
            ),
          },
          {
            key: "allocations",
            label: "배분표",
            types: ["TK"],
            render: (project, refresh, _, controls) => (
              <ProjectAllocationPanel
                key={project.prj_id}
                projectId={project.prj_id}
                useYn={project.use_yn}
                performanceStart={project.prfm_bgn_dt ?? project.cntrct_bgn_dt}
                performanceEnd={project.cur_end_dt ?? project.cntrct_end_dt}
                onChanged={refresh}
                onDirtyChange={controls.onDirtyChange}
              />
            ),
          },
          {
            key: "expenses",
            label: "직접 경비",
            render: (project, refresh) => (
              <ProjectExpensePanel
                key={project.prj_id}
                projectId={project.prj_id}
                useYn={project.use_yn}
                onChanged={refresh}
              />
            ),
          },
          {
            key: "contract-changes",
            label: "계약 변경 이력",
            render: (project, refresh) => (
              <ProjectContractChangePanel
                key={project.prj_id}
                project={{
                  ...project,
                  cntrct_ty_cd: project.cntrct_ty_cd as
                    | "TK"
                    | "MO"
                    | "DP"
                    | "IN",
                }}
                onChanged={refresh}
              />
            ),
          },
          {
            key: "staff-plans",
            label: "인원 계획",
            render: (project, refresh, _, controls) => (
              <ProjectStaffPlanPanel
                projectId={project.prj_id}
                useYn={project.use_yn}
                performanceStart={project.prfm_bgn_dt ?? project.cntrct_bgn_dt}
                performanceEnd={project.cur_end_dt ?? project.cntrct_end_dt}
                onChanged={refresh}
                onDirtyChange={controls.onDirtyChange}
                onBasicInfo={controls.onBasicInfo}
              />
            ),
          },
        ]}
        dialogs={
          <>
            {phaseDialog && (
              <ProjectPhaseDialog
                {...phaseDialog}
                onCancel={() => setPhaseDialog(null)}
                onSaved={() => {
                  setPhaseDialog(null);
                  setMessage("단계 변경을 저장했습니다.");
                }}
              />
            )}
            {partChange && (
              <ProjectPartChangeDialog
                project={partChange.project}
                reload={partChange.refresh}
                onCancel={() => setPartChange(null)}
                onSaved={(text) => {
                  setPartChange(null);
                  setMessage(text);
                }}
              />
            )}
          </>
        }
      />
      {message && (
        <SuccessToast message={message} onDismiss={() => setMessage("")} />
      )}
    </>
  );
}

function AssignmentDetailSections({
  row,
  context,
  onChange,
}: {
  row: AssignmentDetail;
  context: AssignmentScreenContext;
  onChange: (
    row: AssignmentDetail,
    project: ProjectDetail,
    context: AssignmentScreenContext,
  ) => void;
}) {
  const project = useAdminRead(
    useCallback(() => getProject(row.prj_id), [row.prj_id]),
  );
  const [tab, setTab] = useState("info");
  const [dirty, setDirty] = useState(false);
  const mobile = useDeveloperReadOnly();
  const readOnly =
    context.readOnly ||
    mobile ||
    ["CANCELLED", "ENDED"].includes(row.asgn_stts_cd);
  const writable =
    !!project.data &&
    project.data.use_yn === "Y" &&
    ["PLAN", "RUN", "HOLD"].includes(project.data.prj_stts_cd);
  const reportDirty = (value: boolean) => {
    setDirty(value);
    context.onDirtyChange(value);
  };
  return (
    <section data-testid="assignment-detail-sections">
      <ErrorBanner
        error={project.error}
        onRetry={() => void project.reload().catch(() => {})}
      />
      {project.loading ? (
        <Skeleton />
      ) : (
        project.data &&
        !project.error && (
          <>
            {!readOnly &&
              writable &&
              (row.asgn_stts_cd !== "REPLACED" ||
                ["MO", "DP"].includes(project.data.cntrct_ty_cd)) && (
                <Can perm="UI-12.update">
                  <Button
                    data-testid="assignment-record-change"
                    onClick={() => onChange(row, project.data!, context)}
                  >
                    + 변경 기록
                  </Button>
                </Can>
              )}
            <div role="tablist" aria-label="투입 상세 영역">
              <Button
                role="tab"
                data-testid="assignment-info-tab"
                aria-selected={tab === "info"}
                disabled={dirty}
                onClick={() => setTab("info")}
              >
                투입 정보
              </Button>
              <Button
                role="tab"
                data-testid="assignment-changes-tab"
                aria-selected={tab === "changes"}
                disabled={dirty}
                onClick={() => setTab("changes")}
              >
                변경 이력
              </Button>
            </div>
            {tab === "info" ? (
              <UI12MonthsPanel
                assignmentId={row.asgn_id}
                assignmentStatus={row.asgn_stts_cd}
                projectStatus={project.data.prj_stts_cd}
                useYn={project.data.use_yn}
                readOnly={readOnly}
                onChanged={context.refresh}
                onDirtyChange={reportDirty}
              />
            ) : (
              <UI12ChangesPanel
                assignment={row}
                contractType={project.data.cntrct_ty_cd}
                readOnly={readOnly}
                projectWritable={writable}
                onChanged={context.refresh}
              />
            )}
          </>
        )
      )}
    </section>
  );
}

function AssignmentsScreen({
  onDirtyChange,
  ...bindings
}: BusinessScreenBindings) {
  const notify = useRef(onDirtyChange);
  notify.current = onDirtyChange;
  const reportDirty = useCallback(
    (dirty: boolean) => notify.current(dirty),
    [],
  );
  const [change, setChange] = useState<{
    row: AssignmentDetail;
    project: ProjectDetail;
    context: AssignmentScreenContext;
  } | null>(null);
  const [message, setMessage] = useState("");
  const today = new Intl.DateTimeFormat("sv-SE", {
    timeZone: "Asia/Seoul",
  }).format(new Date());
  return (
    <>
      <UI12Screen
        {...bindings}
        onDirtyChange={reportDirty}
        selectionActions={(rows, reload, context) => (
          <AssignmentActions
            selected={rows}
            filter={context.filters}
            today={today}
            readonly={context.readOnly}
            reload={reload}
            onOpenAssignment={context.openAssignment}
            onDirty={reportDirty}
          />
        )}
        detailActions={(row, context) => (
          <AssignmentActions
            selected={[]}
            assignment={row}
            filter={context.filters}
            today={today}
            readonly={context.readOnly}
            reload={context.refresh}
            onOpenAssignment={context.openAssignment}
            onDirty={context.onDirtyChange}
          />
        )}
        detailPanels={(row, context) => (
          <AssignmentDetailSections
            key={row.asgn_id}
            row={row}
            context={context}
            onChange={(assignment, project, controls) =>
              setChange({ row: assignment, project, context: controls })
            }
          />
        )}
        projectPanel={(projectId, year) => (
          <ProjectAssignmentMonthsPanel projectId={projectId} year={year} />
        )}
        dialogs={
          change && (
            <AssignmentChangeForm
              assignment={change.row}
              contractType={change.project.cntrct_ty_cd}
              readOnly={change.context.readOnly}
              projectWritable={
                change.project.use_yn === "Y" &&
                ["PLAN", "RUN", "HOLD"].includes(change.project.prj_stts_cd)
              }
              reload={change.context.refresh}
              today={today}
              onCancel={() => setChange(null)}
              onSaved={(result) => {
                setChange(null);
                setMessage(
                  `변경 이력에 '${changeNames[result.change.asgn_chg_ty_cd]}' 기록을 남겼습니다.`,
                );
              }}
            />
          )
        }
      />
      {message && (
        <SuccessToast message={message} onDismiss={() => setMessage("")} />
      )}
    </>
  );
}

function ProjectBoardScreen(bindings: BusinessScreenBindings) {
  const [version, setVersion] = useState(0);
  const [openCount, setOpenCount] = useState<number>();
  const refresh = useCallback(async () => {
    setVersion((value) => value + 1);
  }, []);
  return (
    <UI31Screen
      {...bindings}
      refreshVersion={version}
      onDownload={downloadProjectBoard}
      issueOpenCount={openCount}
      issuesPanel={
        <IssueListPanel onChanged={refresh} onOpenCount={setOpenCount} />
      }
    />
  );
}

export function AppRouter() {
  return (
    <Routes>
      <Route
        path="/*"
        element={
          <AppShell
            renderBusiness={(id, bindings) =>
              id === "UI-15" ? (
                <PartnersScreen {...bindings} />
              ) : id === "UI-02" ? (
                <DevelopersScreen {...bindings} />
              ) : id === "UI-01" ? (
                <ProjectsScreen {...bindings} />
              ) : id === "UI-05" ? (
                <UI05Screen
                  {...bindings}
                  renderChance={(row, context) => (
                    <WithdrawalChanceSelect
                      key={row.asgn_id}
                      row={row}
                      today={context.today}
                      year={context.year}
                      onChanged={context.refresh}
                    />
                  )}
                  renderDispatch={(row) => (
                    <DispatchExpiryCell
                      assignmentId={row.asgn_id}
                      value={row}
                    />
                  )}
                />
              ) : id === "UI-11" ? (
                <UI11Screen
                  {...bindings}
                  renderShortages={({ filters, onChange, projects }) => (
                    <MonthlyShortagesPanel
                      filters={{
                        year: filters.year,
                        projectId: filters.projectId
                          ? Number(filters.projectId)
                          : undefined,
                        job: filters.job || undefined,
                      }}
                      projects={projects}
                      onFiltersChange={(next) =>
                        onChange({
                          ...filters,
                          year: next.year,
                          projectId:
                            next.projectId === undefined
                              ? ""
                              : String(next.projectId),
                          job: next.job ?? "",
                          page: 0,
                        })
                      }
                    />
                  )}
                />
              ) : id === "UI-12" ? (
                <AssignmentsScreen {...bindings} />
              ) : id === "UI-13" ? (
                <UI13Screen {...bindings} />
              ) : id === "UI-31" ? (
                <ProjectBoardScreen {...bindings} />
              ) : id === "UI-27" ? (
                <CommonCodesScreen {...bindings} />
              ) : null
            }
          />
        }
      />
    </Routes>
  );
}

export default AppRouter;
