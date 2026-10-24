import { useCallback, useEffect, useRef, useState } from "react";
import { Route, Routes } from "react-router-dom";
import UI27Screen from "../pages/admin/UI27Screen";
import UI15Screen from "../pages/partners/UI15Screen";
import SalesPartManagement from "../pages/salespartsops/SalesPartManagement";
import UI02Screen from "../pages/developers/UI02Screen";
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
