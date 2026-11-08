import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { setPermissions } from "../../shared/types";
import UserManagementScreen from "./UserManagementScreen";
import RolePermissionsScreen from "./RolePermissionsScreen";
import MenuManagementScreen from "./MenuManagementScreen";

const ok = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    headers: { "Content-Type": "application/json" },
  });
let fetcher: ReturnType<typeof vi.fn>;
let empty = false;
const role = {
  roleId: 41,
  name: "시험 역할",
  description: "설명",
  dataScope: "PART",
  useYn: "Y",
  admin: false,
  builtin: false,
  sensitiveInquiry: false,
  partStandardChange: false,
};
const user = {
  userId: 57,
  loginId: "test-user",
  name: "시험 사용자",
  email: "test@example.invalid",
  phone: null,
  partId: 71,
  developerId: null,
  roleIds: [41],
  partIds: [71],
  projectIds: [83],
  status: "ACTIVE",
  resignationDate: null,
  lastLoginAt: null,
};
const menu = {
  menuId: 91,
  name: "개발자 풀",
  screenId: "UI-02",
  parentId: null,
  icon: null,
  sortOrder: 1,
  useYn: "Y",
  mobileVisible: true,
};
beforeEach(() => {
  empty = false;
  setPermissions(
    Object.fromEntries(
      ["UI-23", "UI-24", "UI-25"].flatMap((s) =>
        ["read", "create", "update", "delete", "download"].map((a) => [
          `${s}.${a}`,
          true,
        ]),
      ),
    ),
  );
  fetcher = vi.fn(async (path: string, init?: RequestInit) => {
    if (path === "/api/auth/csrf") return ok({ csrfToken: "test-csrf" });
    if (init?.method && init.method !== "GET")
      return ok(
        path.includes("/roles/") && !path.endsWith("permissions") ? role : user,
      );
    if (path === "/api/admin/roles") return ok([role]);
    if (path === "/api/admin/roles/41") return ok(role);
    if (path.endsWith("/permissions"))
      return ok([
        {
          ...menu,
          inq: "Y",
          reg: "N",
          mdfcn: "N",
          del: "N",
          upld: "N",
          dwnld: "N",
          cnfrm: "N",
        },
      ]);
    if (path === "/api/admin/menus") return ok([menu]);
    if (path === "/api/admin/notification-channels")
      return ok({ businessEmailEnabled: false });
    if (path.startsWith("/api/admin/users?"))
      return ok({
        items: empty ? [] : [user],
        total: empty ? 0 : 1,
        page: 1,
        size: 20,
      });
    if (path === "/api/admin/users/57") return ok(user);
    if (path.startsWith("/api/admin/access-history"))
      return ok({ items: [], total: 0, page: 1, size: 20 });
    if (path.startsWith("/api/sales-parts/options"))
      return ok([{ id: "71", name: "담당 파트", hq: false }]);
    if (path.startsWith("/api/projects/options"))
      return ok([
        { id: 83, name: "담당 프로젝트", no: "project-test", type: "TK" },
      ]);
    if (path.startsWith("/api/developers/options")) return ok([]);
    throw new Error(`Unexpected request: ${path}`);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.unstubAllGlobals();
  setPermissions({});
});
describe("관리 화면 실제 바인딩", () => {
  it("registration opens its form without submitting the search form", async () => {
    render(<UserManagementScreen />);
    await screen.findByRole("button", { name: "test-user" });
    const searches = fetcher.mock.calls.filter(([p]) =>
      p.startsWith("/api/admin/users?"),
    ).length;
    fireEvent.click(screen.getByTestId("user-create"));
    expect(
      await screen.findByRole("heading", { name: "사용자 등록" }),
    ).toBeInTheDocument();
    expect(screen.getByTestId("user-field-loginid")).not.toHaveAttribute(
      "readonly",
    );
    expect(
      fetcher.mock.calls.filter(([p]) => p.startsWith("/api/admin/users?")),
    ).toHaveLength(searches);
  });
  it("loads selected account and PATCH excludes immutable identity and assignments", async () => {
    render(<UserManagementScreen />);
    fireEvent.click(await screen.findByRole("button", { name: "test-user" }));
    const name = await screen.findByTestId("user-field-name");
    fireEvent.change(name, { target: { value: "변경된 사용자" } });
    expect(screen.getByTestId("user-field-loginid")).toHaveAttribute(
      "readonly",
    );
    fireEvent.click(screen.getByTestId("user-save"));
    await waitFor(() =>
      expect(
        fetcher.mock.calls.some(
          ([p, i]) => p === "/api/admin/users/57" && i?.method === "PATCH",
        ),
      ).toBe(true),
    );
    const [, init] = fetcher.mock.calls.find(
      ([p, i]) => p === "/api/admin/users/57" && i?.method === "PATCH",
    )!;
    const payload = JSON.parse(String(init?.body));
    expect(payload.name).toBe("변경된 사용자");
    expect(payload).not.toHaveProperty("loginId");
    expect(payload).not.toHaveProperty("roleIds");
    expect(payload).not.toHaveProperty("projectIds");
    expect(new Headers(init?.headers).get("X-CSRF-TOKEN")).toBe("test-csrf");
  });
  it("search with zero rows clears the previously selected account", async () => {
    render(<UserManagementScreen />);
    fireEvent.click(await screen.findByRole("button", { name: "test-user" }));
    await screen.findByTestId("user-field-name");
    empty = true;
    fireEvent.click(screen.getByTestId("user-search-submit"));
    expect(
      await screen.findByText("검색 결과가 없습니다."),
    ).toBeInTheDocument();
    expect(screen.queryByTestId("user-field-name")).not.toBeInTheDocument();
  });
  it("read-only actor sees no write or export controls", async () => {
    setPermissions({ "UI-23.read": true });
    render(<UserManagementScreen />);
    await screen.findByRole("button", { name: "test-user" });
    expect(screen.queryByTestId("user-create")).not.toBeInTheDocument();
    expect(screen.queryByTestId("user-export")).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "test-user" }));
    await screen.findByTestId("user-field-name");
    expect(screen.queryByTestId("user-save")).not.toBeInTheDocument();
  });
  it("editing permission checkbox is local until compare and explicit save", async () => {
    render(<RolePermissionsScreen />);
    fireEvent.click(await screen.findByTestId("role-select-41"));
    const checkbox = await screen.findByTestId("permission-91-reg");
    fireEvent.click(checkbox);
    expect(fetcher.mock.calls.some(([, i]) => i?.method === "PUT")).toBe(false);
    fireEvent.change(screen.getByTestId("role-permission-reason"), {
      target: { value: "운영 담당 업무 추가" },
    });
    fireEvent.click(screen.getByTestId("role-permission-compare"));
    fireEvent.click(screen.getByTestId("role-permission-save"));
    await waitFor(() =>
      expect(
        fetcher.mock.calls.some(
          ([p, i]) =>
            p === "/api/admin/roles/41/permissions" && i?.method === "PUT",
        ),
      ).toBe(true),
    );
    const [, init] = fetcher.mock.calls.find(
      ([p, i]) => p.endsWith("permissions") && i?.method === "PUT",
    )!;
    expect(JSON.parse(String(init?.body))).toEqual({
      reason: "운영 담당 업무 추가",
      permissions: [
        {
          menuId: 91,
          inq: "Y",
          reg: "Y",
          mdfcn: "N",
          del: "N",
          upld: "N",
          dwnld: "N",
          cnfrm: "N",
        },
      ],
    });
  });
  it("menu screen id is readonly and disabling requires inline confirmation", async () => {
    const interaction = userEvent.setup();
    render(<MenuManagementScreen />);
    await interaction.click(await screen.findByTestId("menu-select-91"));
    expect(screen.getByTestId("menu-screen-id")).toHaveAttribute("readonly");
    fireEvent.change(screen.getByTestId("menu-use"), {
      target: { value: "N" },
    });
    await interaction.click(screen.getByTestId("menu-save"));
    expect(screen.getByTestId("menu-disable-impact")).toBeInTheDocument();
    expect(fetcher.mock.calls.some(([, i]) => i?.method === "PATCH")).toBe(
      false,
    );
    await interaction.click(screen.getByTestId("menu-save"));
    await waitFor(() =>
      expect(
        fetcher.mock.calls.some(
          ([p, i]) => p === "/api/admin/menus/91" && i?.method === "PATCH",
        ),
      ).toBe(true),
    );
    const [, init] = fetcher.mock.calls.find(
      ([p, i]) => p === "/api/admin/menus/91" && i?.method === "PATCH",
    )!;
    const payload = JSON.parse(String(init?.body));
    expect(payload).not.toHaveProperty("screenId");
    expect(payload.riskConfirmed).toBe(true);
  });
});
