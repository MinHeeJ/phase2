import { useCallback, useEffect, useRef, useState } from "react";
import { Route, Routes } from "react-router-dom";
import UI27Screen from "../pages/admin/UI27Screen";
import UI15Screen from "../pages/partners/UI15Screen";
import SalesPartManagement from "../pages/salespartsops/SalesPartManagement";
import UI02Screen from "../pages/developers/UI02Screen";
import UI01Screen from "../pages/projects/UI01Screen";
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
          partChange && (
            <ProjectPartChangeDialog
              project={partChange.project}
              reload={partChange.refresh}
              onCancel={() => setPartChange(null)}
              onSaved={(text) => {
                setPartChange(null);
                setMessage(text);
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
