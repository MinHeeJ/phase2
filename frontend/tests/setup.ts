import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { afterEach, beforeEach } from "vitest";
import { setPermissions } from "../src/shared/types";
import { screenIds } from "../src/pages/shared/registry";

// Existing calculation/form tests run as an explicit test administrator. Security tests replace this fixture per role.
beforeEach(() =>
  setPermissions(
    Object.fromEntries(
      screenIds.flatMap((screen) =>
        [
          "read",
          "create",
          "update",
          "delete",
          "upload",
          "download",
          "confirm",
        ].map((action) => [`${screen}.${action}`, true]),
      ),
    ),
  ),
);

afterEach(() => cleanup());
