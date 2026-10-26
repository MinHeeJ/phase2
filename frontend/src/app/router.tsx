import { Route, Routes } from "react-router-dom";
import { renderAdminScreen } from "../pages/admin/UI27";
import { SharedWorkspace } from "../pages/shared/SharedWorkspace";

export function ApplicationRouter() {
  return (
    <Routes>
      <Route
        path="/*"
        element={<SharedWorkspace renderScreen={renderAdminScreen} />}
      />
    </Routes>
  );
}
