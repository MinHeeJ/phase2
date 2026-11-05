import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { MemoryRouter } from "react-router-dom";
import LoginScreen from "./LoginScreen";
import RolePermissionsScreen from "../systemmanagement/RolePermissionsScreen";
import MenuManagementScreen from "../systemmanagement/MenuManagementScreen";

const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    headers: { "Content-Type": "application/json" },
  });
let fetcher: ReturnType<typeof vi.fn>;
beforeEach(() => {
  localStorage.clear();
  sessionStorage.clear();
  fetcher = vi.fn(async (url: string) => {
    if (url === "/api/auth/csrf")
      return response({
        headerName: "X-CSRF-TOKEN",
        csrfToken: crypto.randomUUID(),
      });
    throw new Error(`Unexpected request ${url}`);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
const mount = (entry = "/UI-22") =>
  render(
    <MemoryRouter initialEntries={[entry]}>
      <LoginScreen />
    </MemoryRouter>,
  );
it("renders only the login card before authentication and toggles password visibility", () => {
  mount();
  expect(screen.getByTestId("login-screen")).toHaveAttribute(
    "data-screen-id",
    "UI-22",
  );
  expect(screen.getByTestId("login-password")).toHaveAttribute(
    "type",
    "password",
  );
  fireEvent.click(screen.getByTestId("password-visibility"));
  expect(screen.getByTestId("login-password")).toHaveAttribute("type", "text");
  expect(screen.queryByRole("navigation")).not.toBeInTheDocument();
  expect(fetcher).not.toHaveBeenCalled();
});
it("moves to MFA only after server confirmation without requesting business data", async () => {
  fetcher.mockImplementation(async (url: string) => {
    if (url === "/api/auth/csrf")
      return response({
        headerName: "X-CSRF-TOKEN",
        csrfToken: crypto.randomUUID(),
      });
    if (url === "/api/auth/login")
      return response({
        state: "MFA_REQUIRED",
        expiresAt: new Date(Date.now() + 300000).toISOString(),
      });
    throw new Error(`Unexpected request ${url}`);
  });
  mount();
  fireEvent.change(screen.getByTestId("login-id"), {
    target: { value: " contract.user " },
  });
  fireEvent.change(screen.getByTestId("login-password"), {
    target: { value: crypto.randomUUID() },
  });
  fireEvent.click(screen.getByTestId("authentication-submit"));
  await screen.findByTestId("mfa-code");
  expect(fetcher.mock.calls.some(([url]) => url === "/api/auth/me")).toBe(
    false,
  );
  expect(localStorage.length).toBe(0);
  expect(sessionStorage.length).toBe(0);
});
it("rejects password confirmation mismatch without any request", async () => {
  mount("/UI-22#password");
  fireEvent.change(screen.getByTestId("login-password"), {
    target: { value: crypto.randomUUID() },
  });
  fireEvent.change(screen.getByTestId("new-password"), {
    target: { value: `${crypto.randomUUID()}A1!` },
  });
  fireEvent.change(screen.getByTestId("confirm-password"), {
    target: { value: "different" },
  });
  fireEvent.click(screen.getByTestId("authentication-submit"));
  expect(await screen.findByRole("alert")).toHaveTextContent(
    "비밀번호 확인이 일치하지 않습니다.",
  );
  expect(fetcher).not.toHaveBeenCalled();
});
it("shows common authentication error without exposing account state", async () => {
  fetcher.mockImplementation(async (url: string) =>
    url === "/api/auth/csrf"
      ? response({ headerName: "X-CSRF-TOKEN", csrfToken: crypto.randomUUID() })
      : new Response(
          JSON.stringify({
            success: false,
            meta: {},
            code: "INVALID_CREDENTIALS",
            message: "아이디 또는 비밀번호를 확인하세요.",
            requestId: "contract-request",
          }),
          { status: 401 },
        ),
  );
  mount();
  fireEvent.change(screen.getByTestId("login-id"), {
    target: { value: "contract.user" },
  });
  fireEvent.change(screen.getByTestId("login-password"), {
    target: { value: crypto.randomUUID() },
  });
  fireEvent.click(screen.getByTestId("authentication-submit"));
  expect(await screen.findByRole("alert")).toHaveTextContent(
    "contract-request",
  );
  expect(screen.getByTestId("login-password")).toHaveValue("");
});
it("role copy cancel sends no write and preserves original role", async () => {
  const role = {
    id: 810,
    name: "검증 역할",
    description: "검증",
    useYn: "Y",
    dataScope: "PART",
    sensitiveView: true,
    partStandardChange: true,
    admin: true,
    builtIn: true,
  };
  fetcher.mockImplementation(async (url: string) =>
    url === "/api/admin/roles"
      ? response([role])
      : url === "/api/admin/roles/810/permissions"
        ? response([])
        : Promise.reject(new Error(`Unexpected ${url}`)),
  );
  render(<RolePermissionsScreen />);
  fireEvent.click(await screen.findByTestId("role-select-810"));
  fireEvent.click(screen.getByTestId("role-copy-open"));
  fireEvent.change(screen.getByTestId("role-copy-name"), {
    target: { value: "복사 검증" },
  });
  fireEvent.click(screen.getByTestId("role-copy-cancel"));
  expect(screen.queryByTestId("role-copy-panel")).not.toBeInTheDocument();
  expect(fetcher.mock.calls.every(([, init]) => !init?.method)).toBe(true);
});
it("menu edit keeps screen identity readonly and excludes it from PATCH", async () => {
  const menu = {
    id: 813,
    name: "검증 메뉴",
    screenId: "UI-02",
    parentId: null,
    icon: null,
    sortOrder: 1,
    useYn: "Y",
    mobileVisible: true,
  };
  fetcher.mockImplementation(async (url: string) =>
    url === "/api/admin/menus"
      ? response([menu])
      : url === "/api/auth/csrf"
        ? response({
            headerName: "X-CSRF-TOKEN",
            csrfToken: crypto.randomUUID(),
          })
        : url === "/api/admin/menus/813"
          ? response(null)
          : Promise.reject(new Error(`Unexpected ${url}`)),
  );
  render(<MenuManagementScreen />);
  fireEvent.click(await screen.findByTestId("menu-select-813"));
  expect(screen.getByTestId("menu-screen-id")).toHaveAttribute("readonly");
  fireEvent.change(screen.getByTestId("menu-name"), {
    target: { value: "이름 수정" },
  });
  fireEvent.click(screen.getByTestId("menu-save"));
  await waitFor(() =>
    expect(
      fetcher.mock.calls.some(([url]) => url === "/api/admin/menus/813"),
    ).toBe(true),
  );
  const call = fetcher.mock.calls.find(
    ([url]) => url === "/api/admin/menus/813",
  )!;
  expect(JSON.parse(call[1].body)).not.toHaveProperty("screenId");
  expect(JSON.parse(call[1].body).name).toBe("이름 수정");
});
