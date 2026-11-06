import { fireEvent, render, screen } from "@testing-library/react";
import { MemoryRouter, useNavigate } from "react-router-dom";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { AppRouter } from "../../app/router";
import { clearSecurity } from "../../shared/types";
import { AuthenticationBoundary } from "./AuthenticationBoundary";

const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    headers: { "Content-Type": "application/json" },
  });
beforeEach(() => {
  Object.defineProperty(window, "innerWidth", {
    configurable: true,
    value: 375,
  });
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string) => {
      if (url === "/api/auth/me")
        return response({
          name: "권한 시험 관리자",
          roles: ["1"],
          passwordAgeWarning: false,
        });
      if (url === "/api/me/permissions")
        return response({
          permissions: ["UI-15", "UI-23", "UI-24", "UI-25"].map((screenId) => ({
            screenId,
            read: true,
            create: true,
            update: true,
            delete: true,
            download: true,
          })),
        });
      if (url === "/api/navigation")
        return response({ firstAllowedScreenId: "UI-23" });
      if (url === "/api/menus")
        return response([
          {
            menuId: 8,
            name: "시스템",
            screenId: null,
            icon: null,
            mobile: true,
            children: ["UI-15", "UI-23", "UI-24", "UI-25"].map(
              (screenId, index) => ({
                menuId: index + 9,
                name: screenId,
                screenId,
                icon: null,
                mobile: false,
                children: [],
              }),
            ),
          },
        ]);
      if (url === "/api/notifications/unread-count")
        return response({ unreadCount: 0 });
      if (url.startsWith("/api/admin/"))
        return response({ items: [], total: 0, page: 0, size: 100 });
      if (url.startsWith("/api/partners?"))
        return response({ items: [], total: 0, page: 1, size: 50 });
      if (url.startsWith("/api/codes/")) return response([]);
      if (url.endsWith("/options")) return response([]);
      throw new Error(`Unexpected ${url}`);
    }),
  );
});
afterEach(() => {
  clearSecurity();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
it.each([
  ["/UI-23", "user-accounts-screen", "사용자 관리"],
  ["/UI-24", "role-permissions-screen", "권한 관리"],
  ["/UI-25", "menu-management-screen", "메뉴 관리"],
])(
  "binds %s through the real router and AppShell even on mobile",
  async (path, testId, title) => {
    render(
      <MemoryRouter initialEntries={[path]}>
        <AppRouter />
      </MemoryRouter>,
    );
    expect(await screen.findByTestId(testId)).toBeVisible();
    expect(screen.getByRole("heading", { name: title })).toBeVisible();
    expect(screen.queryByText("PC에서 이용해 주세요")).not.toBeInTheDocument();
    expect(
      screen.queryByText("이 화면은 다음 단계에서 만듭니다."),
    ).not.toBeInTheDocument();
  },
);
it("binds UI-15 to the existing partner screen through authenticated navigation", async () => {
  Object.defineProperty(window, "innerWidth", {
    configurable: true,
    value: 1440,
  });
  render(
    <MemoryRouter initialEntries={["/UI-15"]}>
      <AppRouter />
    </MemoryRouter>,
  );
  expect(await screen.findByTestId("partners-screen")).toHaveAttribute(
    "data-screen-id",
    "SCR-UI-15",
  );
  expect(await screen.findByRole("button", { name: /^조회$/ })).toBeVisible();
  expect(
    screen.queryByText("이 화면은 다음 단계에서 만듭니다."),
  ).not.toBeInTheDocument();
});

it.each([
  [undefined, "UI-01", true],
  [undefined, "UI-33", false],
  [{ workspace: "candidates" }, "UI-33", true],
  [{ workspace: "candidates" }, "UI-01", false],
] as const)(
  "checks the existing UI-33 workspace %j against %s (allowed: %s)",
  async (state, allowedScreen, allowed) => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async (url: string) => {
        if (url === "/api/auth/me")
          return response({ name: "시험 사용자", roles: ["5"] });
        if (url === "/api/me/permissions")
          return response({
            permissions: [{ screenId: allowedScreen, read: true }],
          });
        if (url === "/api/navigation")
          return response({ firstAllowedScreenId: allowedScreen });
        throw new Error(`Unexpected ${url}`);
      }),
    );
    function WorkspaceSwitch() {
      const navigate = useNavigate();
      return (
        <button
          data-testid="candidate-workspace-button"
          onClick={() =>
            navigate("/UI-33", { state: { workspace: "candidates" } })
          }
        >
          후보로 이동
        </button>
      );
    }
    render(
      <MemoryRouter initialEntries={[{ pathname: "/UI-33", state }]}>
        <WorkspaceSwitch />
        <AuthenticationBoundary>
          <main data-testid="authorized-workspace">허용된 업무</main>
        </AuthenticationBoundary>
      </MemoryRouter>,
    );
    if (allowed) {
      expect(await screen.findByTestId("authorized-workspace")).toBeVisible();
      if (!state) {
        fireEvent.click(screen.getByTestId("candidate-workspace-button"));
        expect(await screen.findByRole("alert")).toHaveTextContent(
          "접근 권한이 없습니다.",
        );
        expect(
          screen.queryByTestId("authorized-workspace"),
        ).not.toBeInTheDocument();
      }
    } else {
      expect(await screen.findByRole("alert")).toHaveTextContent(
        "접근 권한이 없습니다.",
      );
      expect(
        screen.queryByTestId("authorized-workspace"),
      ).not.toBeInTheDocument();
    }
  },
);
