import { useCallback, useRef } from "react";
import { Route, Routes } from "react-router-dom";
import UI27Screen from "../pages/admin/UI27Screen";
import UI15Screen from "../pages/partners/UI15Screen";
import SalesPartManagement from "../pages/salespartsops/SalesPartManagement";
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
