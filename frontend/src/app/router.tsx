import { useCallback, useRef } from "react";
import { Route, Routes } from "react-router-dom";
import UI27Screen from "../pages/admin/UI27Screen";
import UI15Screen from "../pages/partners/UI15Screen";
import { PartnerExportButton } from "../pages/partnersops/PartnerActions";
import { SalesPartsTab } from "../pages/salesparts/SalesPartsTab";
import { SalesPartActions } from "../pages/salespartsops/SalesPartActions";
import { SalesPartExportButton } from "../pages/salespartsops/SalesPartExportButton";
import { UploadButton } from "../pages/uploadsops/UploadButton";
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

export function AppRouter() {
  return (
    <Routes>
      <Route
        path="/*"
        element={
          <AppShell
            renderBusiness={(id, bindings) =>
              id === "UI-27" ? (
                <CommonCodesScreen {...bindings} />
              ) : id === "UI-15" ? (
                <PartnersScreen {...bindings} />
              ) : null
            }
          />
        }
      />
    </Routes>
  );
}

export default AppRouter;

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
      renderUploads={(refresh) => (
        <UploadButton form="B.7" onApplied={refresh} />
      )}
      renderExport={(filters, ids, total, columns) => (
        <PartnerExportButton
          filters={filters}
          ids={ids}
          total={total}
          columns={columns}
        />
      )}
      salesParts={
        <SalesPartsTab
          onDirtyChange={reportDirty}
          renderActions={(part, refresh) => (
            <SalesPartActions
              key={part.bsn_part_id}
              part={part}
              onUpdated={() => {}}
              onListRefresh={refresh}
              onDirtyChange={reportDirty}
            />
          )}
          renderExport={(columns, total, sort) => (
            <SalesPartExportButton
              columns={columns}
              selectedIds={[]}
              total={total}
              sort={sort}
            />
          )}
        />
      }
    />
  );
}
