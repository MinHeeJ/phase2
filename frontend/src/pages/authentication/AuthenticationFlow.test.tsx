import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { AuthenticationBoundary } from "./AuthenticationBoundary";
import LoginScreen from "./LoginScreen";
import { MenuNavigation } from "../shared/MenuNavigation";
import { useMenus } from "../shared/useMenus";
import { useSharedPreferences } from "../shared/preferences";
import UserManagementScreen from "../systemadmin/UserManagementScreen";
import { setPermissions } from "../../shared/types";

const ok = (data: unknown) =>
  new Response(JSON.stringify({ success: true, data }), {
    headers: { "Content-Type": "application/json" },
  });
function Navigation() {
  const { menus } = useMenus();
  return <MenuNavigation menus={menus} active="UI-01" onNavigate={() => {}} />;
}
beforeEach(() => {
  localStorage.clear();
  useSharedPreferences.setState({ favorites: [], recent: [] });
});
afterEach(() => {
  vi.unstubAllGlobals();
  setPermissions({});
});

it.each(["시스템 관리자", "경영진", "일반 사용자(PM)"])(
  "%s sees only the server-authorized menu tree",
  async (role) => {
    const admin = role === "시스템 관리자";
    const project = {
      menuId: 2,
      name: "프로젝트",
      screenId: "UI-01",
      icon: null,
      mobile: true,
      children: [],
    };
    const management = {
      ...project,
      menuId: 3,
      name: "사용자 관리",
      screenId: "UI-23",
    };
    vi.stubGlobal(
      "fetch",
      vi.fn(async (path: string) => {
        if (path === "/api/auth/me")
          return ok({
            userId: 1,
            name: "시험 사용자",
            roleIds: [admin ? 1 : 2],
            roles: [role],
          });
        if (path === "/api/me/permissions")
          return ok({
            permissions: { "UI-01.read": true, "UI-23.read": admin },
          });
        if (path === "/api/menus")
          return ok([
            {
              ...project,
              menuId: 1,
              screenId: null,
              name: "업무",
              children: admin ? [project, management] : [project],
            },
          ]);
        throw new Error("Unexpected API operation");
      }),
    );
    render(
      <MemoryRouter>
        <AuthenticationBoundary>
          <Navigation />
        </AuthenticationBoundary>
      </MemoryRouter>,
    );
    expect(
      await screen.findByRole("button", { name: "프로젝트" }),
    ).toBeInTheDocument();
    expect(Boolean(screen.queryByRole("button", { name: "사용자 관리" }))).toBe(
      admin,
    );
  },
);

it.each([true, false])(
  "administrator write permission %s controls registration buttons and editable fields",
  async (write) => {
    setPermissions({
      "UI-23.read": true,
      "UI-23.create": write,
      "UI-23.update": write,
    });
    vi.stubGlobal(
      "fetch",
      vi.fn(async (path: string) => {
        if (path.startsWith("/api/admin/users?"))
          return ok({ items: [], total: 0, page: 1, size: 20 });
        return ok([]);
      }),
    );
    render(<UserManagementScreen />);
    await screen.findByText("검색 결과가 없습니다.");
    await waitFor(() =>
      expect(screen.queryByTestId("user-create") !== null).toBe(write),
    );
    if (write) {
      fireEvent.click(screen.getByTestId("user-create"));
      const name = await screen.findByTestId("user-field-name");
      fireEvent.change(name, { target: { value: "변경 이름" } });
      expect(name).toHaveValue("변경 이름");
      expect(screen.getByTestId("user-save")).toBeInTheDocument();
    } else {
      expect(screen.queryByTestId("user-save")).not.toBeInTheDocument();
    }
  },
);

it("renders login inputs, preserves password whitespace and clears it after successful login", async () => {
  const loginId = crypto.randomUUID();
  const password = ` ${crypto.randomUUID()} `;
  let exactInput = false;
  vi.stubGlobal(
    "fetch",
    vi.fn(async (path: string, init?: RequestInit) => {
      if (path === "/api/auth/csrf")
        return ok({ csrfToken: crypto.randomUUID() });
      if (path === "/api/auth/login") {
        const body = JSON.parse(String(init?.body));
        exactInput = body.loginId === loginId && body.password === password;
        return ok({ status: "AUTHENTICATED" });
      }
      if (path === "/api/navigation") return ok([]);
      throw new Error("Unexpected API operation");
    }),
  );
  render(
    <MemoryRouter>
      <LoginScreen />
    </MemoryRouter>,
  );
  fireEvent.change(screen.getByTestId("login-id"), {
    target: { value: ` ${loginId} ` },
  });
  fireEvent.change(screen.getByTestId("login-password"), {
    target: { value: password },
  });
  fireEvent.click(screen.getByRole("button", { name: /^로그인$/ }));
  expect(
    await screen.findByRole("heading", { name: "접근 가능한 메뉴 없음" }),
  ).toBeInTheDocument();
  expect(exactInput).toBe(true);
  expect(screen.queryByTestId("login-password")).not.toBeInTheDocument();
});
