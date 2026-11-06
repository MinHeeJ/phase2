import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { afterEach, beforeEach } from "vitest";
import { clearSecurity, setSecurity } from "../src/shared/types";
import { screenIds } from "../src/pages/shared/registry";

// Existing business component fixtures represent an authenticated administrator.
// Role-specific security tests replace this explicit persona in their own setup.
beforeEach(() =>
  setSecurity(
    "합성 시험 관리자",
    [1],
    screenIds.map((screenId) => ({
      screenId,
      read: true,
      create: true,
      update: true,
      delete: true,
      upload: true,
      download: true,
      confirm: true,
    })),
  ),
);
afterEach(() => {
  cleanup();
  clearSecurity();
});
