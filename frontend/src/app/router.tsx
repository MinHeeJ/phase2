import { useCallback, useRef } from "react";
import { BrowserRouter, Route, Routes } from "react-router-dom";
import { Ui27Screen } from "../pages/admin/Ui27Screen";
import { SharedWorkspace } from "../pages/shared/SharedWorkspace";
import { screenIds } from "../pages/shared/registry";

function Ui27Route({
  onDirtyChange,
}: {
  onDirtyChange: (dirty: boolean) => void;
}) {
  const notify = useRef(onDirtyChange);
  notify.current = onDirtyChange;
  const reportDirty = useCallback(
    (dirty: boolean) => notify.current(dirty),
    [],
  );
  return <Ui27Screen embedded onDirtyChange={reportDirty} />;
}

export function ApplicationRoutes() {
  return (
    <Routes>
      <Route
        element={
          <SharedWorkspace
            renderScreen={(screenId, context) =>
              screenId === "UI-27" ? (
                <Ui27Route onDirtyChange={context.onDirtyChange} />
              ) : null
            }
          />
        }
      >
        <Route index element={null} />
        {screenIds.map((screenId) => (
          <Route key={screenId} path={`/${screenId}`} element={null} />
        ))}
        <Route path="*" element={null} />
      </Route>
    </Routes>
  );
}

export function ApplicationRouter() {
  return (
    <BrowserRouter>
      <ApplicationRoutes />
    </BrowserRouter>
  );
}
