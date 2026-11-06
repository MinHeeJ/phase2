import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter } from "react-router-dom";
import AuthenticationScreen from "./AuthenticationScreen";
import UserAccountsScreen from "./UserAccountsScreen";
import RolePermissionsScreen from "./RolePermissionsScreen";
import MenuManagementScreen from "./MenuManagementScreen";
import { BusinessContact } from "../shared/BusinessContact";
import { clearSecurity, setSecurity } from "../../shared/types";
import type { SecurityMenu, SecurityRole, SecurityUser } from "./client";

const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    headers: { "Content-Type": "application/json" },
  });
let fetcher: ReturnType<typeof vi.fn>;
const role: SecurityRole = {
  id: 71,
  name: "시험 운영",
  description: "설명",
  useYn: "Y",
  dataScope: "PART",
  admin: false,
  builtIn: false,
  sensitiveInquiry: false,
  partStandardChange: false,
};
const menu: SecurityMenu = {
  id: 81,
  name: "시험 화면",
  screenId: "UI-12",
  parentId: null,
  icon: null,
  sortOrder: 1,
  useYn: "Y",
  mobileVisible: true,
};
const user: SecurityUser = {
  id: 91,
  loginId: "trial.person",
  name: "시험 계정",
  email: "trial@example.invalid",
  phone: null,
  basePartId: null,
  developerId: null,
  roleIds: [71],
  partIds: [],
  projectIds: [],
  status: "ACTIVE",
  resignationDate: null,
  lastLoginAt: null,
};
beforeEach(() => {
  setSecurity(
    "시험 관리자",
    [1],
    ["UI-23", "UI-24", "UI-25"].map((screenId) => ({
      screenId,
      read: true,
      create: true,
      update: true,
      delete: true,
      download: true,
    })),
  );
  fetcher = vi.fn(async (url: string) => {
    if (url === "/api/auth/csrf")
      return response({
        headerName: "X-CSRF-TOKEN",
        csrfToken: "unit-test-csrf",
      });
    if (url.startsWith("/api/admin/roles?"))
      return response({ items: [role], total: 1, page: 0, size: 100 });
    if (url === "/api/admin/roles/71") return response(role);
    if (url === "/api/admin/roles/71/permissions")
      return response({
        roleId: 71,
        permissions: [
          {
            menuId: 81,
            screenId: "UI-12",
            name: "시험 화면",
            inq: true,
            reg: false,
            mdfcn: false,
            del: false,
            upld: false,
            dwnld: false,
            cnfrm: false,
            supportedActions: [
              "inq",
              "reg",
              "mdfcn",
              "del",
              "upld",
              "dwnld",
              "cnfrm",
            ],
          },
        ],
      });
    if (url.startsWith("/api/admin/menus?"))
      return response({ items: [menu], total: 1, page: 0, size: 100 });
    if (url.startsWith("/api/admin/users?"))
      return response({ items: [user], total: 1, page: 0, size: 20 });
    if (url === "/api/admin/users/91") return response(user);
    if (url === "/api/sales-parts/options" || url === "/api/projects/options")
      return response([]);
    throw new Error(`Unexpected request ${url}`);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  clearSecurity();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
const mount = (child: React.ReactNode) =>
  render(<MemoryRouter initialEntries={["/UI-22"]}>{child}</MemoryRouter>);

describe("보안 관리 화면 실제 렌더링", () => {
  it("keeps password whitespace and enters MFA without business navigation", async () => {
    fetcher.mockImplementation(async (url: string) =>
      url === "/api/auth/csrf"
        ? response({ headerName: "X-CSRF-TOKEN", csrfToken: "unit-test-csrf" })
        : response({ state: "MFA_REQUIRED", expiresInSeconds: 300 }),
    );
    mount(<AuthenticationScreen />);
    fireEvent.change(screen.getByLabelText("아이디"), {
      target: { value: "  trial.person  " },
    });
    fireEvent.change(screen.getByLabelText("비밀번호"), {
      target: { value: " Trial!1234 " },
    });
    fireEvent.click(screen.getByRole("button", { name: "로그인" }));
    await screen.findByLabelText("인증 코드");
    const login = fetcher.mock.calls.find(([url]) => url === "/api/auth/login");
    expect(JSON.parse(login?.[1]?.body as string)).toEqual({
      loginId: "trial.person",
      password: " Trial!1234 ",
    });
    expect(screen.queryByText("사용자 관리")).not.toBeInTheDocument();
  });
  it("hides administrator create controls without that action", async () => {
    setSecurity(
      "조회 관리자",
      [1],
      [{ screenId: "UI-23", read: true, create: false, download: false }],
    );
    mount(<UserAccountsScreen />);
    await screen.findByText("trial.person");
    expect(
      screen.queryByRole("button", { name: "사용자 등록" }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "엑셀 내려받기" }),
    ).not.toBeInTheDocument();
  });
  it("selected user id binds the immutable login identity", async () => {
    mount(<UserAccountsScreen />);
    fireEvent.click(
      await screen.findByRole("button", { name: "trial.person" }),
    );
    await screen.findByDisplayValue("trial.person");
    expect(screen.getByTestId("user-login-id-input")).toHaveAttribute(
      "readonly",
    );
    expect(
      fetcher.mock.calls.some(([url]) => url === "/api/admin/users/91"),
    ).toBe(true);
  });
  it("matrix changes remain local until a reasoned explicit save", async () => {
    mount(<RolePermissionsScreen />);
    fireEvent.click(await screen.findByRole("button", { name: /시험 운영/ }));
    await screen.findByTestId("permission-81-reg");
    fireEvent.click(screen.getByTestId("permission-81-reg"));
    expect(screen.getByTestId("permission-81-reg")).toBeChecked();
    expect(screen.getByRole("button", { name: "권한 저장" })).toBeDisabled();
    expect(
      fetcher.mock.calls.filter(([, init]) => init?.method === "PUT"),
    ).toHaveLength(0);
  });
  it("existing screen id is readonly and preview cancel sends no write", async () => {
    mount(<MenuManagementScreen />);
    fireEvent.click(await screen.findByTestId("menu-select-81"));
    expect(screen.getByTestId("menu-screen-readonly")).toHaveValue("UI-12");
    expect(screen.getByTestId("menu-screen-readonly")).toHaveAttribute(
      "readonly",
    );
    fireEvent.change(screen.getByTestId("menu-use-select"), {
      target: { value: "N" },
    });
    expect(screen.getByRole("button", { name: "저장" })).toBeDisabled();
    expect(screen.getByTestId("menu-disable-confirmation")).toBeVisible();
  });
  it("contact uses selected domain id, audits server-side and discards raw contact on close", async () => {
    fetcher.mockImplementation(async () =>
      response({
        phone: "010-0000-0000",
        email: "trial@example.invalid",
        masked: false,
      }),
    );
    mount(<BusinessContact kind="partners" id={91} />);
    fireEvent.click(screen.getByRole("button", { name: "원문 보기" }));
    await screen.findByText(/010-0000-0000/);
    expect(fetcher).toHaveBeenCalledWith(
      "/api/partners/91/contact?purpose=CONTRACT_SALES",
      expect.anything(),
    );
    fireEvent.click(screen.getByRole("button", { name: "원문 닫기" }));
    expect(screen.queryByText(/010-0000-0000/)).not.toBeInTheDocument();
  });
  it("role copies are bound to the selected role instead of a sample identity", async () => {
    mount(<RolePermissionsScreen />);
    fireEvent.click(await screen.findByRole("button", { name: /시험 운영/ }));
    await screen.findByRole("button", { name: "복사" });
    fireEvent.change(screen.getByTestId("role-copy-name-input"), {
      target: { value: "독립 역할" },
    });
    fetcher.mockImplementation(async (url: string, init?: RequestInit) => {
      if (url === "/api/auth/csrf")
        return response({
          headerName: "X-CSRF-TOKEN",
          csrfToken: "unit-test-csrf",
        });
      if (init?.method === "POST") return response({ ...role, id: 72 });
      if (url === "/api/admin/roles/72") return response({ ...role, id: 72 });
      if (url.endsWith("/permissions"))
        return response({ roleId: 72, permissions: [] });
      return response({ items: [role], total: 1 });
    });
    fireEvent.click(screen.getByRole("button", { name: "복사" }));
    await waitFor(() =>
      expect(
        fetcher.mock.calls.some(
          ([url, init]) =>
            url === "/api/admin/roles/71/copy" && init?.method === "POST",
        ),
      ).toBe(true),
    );
  });
});
