import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { MemoryRouter, useLocation } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { AppRouter } from "./router";
import { useSharedPreferences } from "../pages/shared/preferences";
import { isScreenId, sharedRoutes } from "../pages/shared/registry";
import type { SecurityNotification } from "../pages/opportunitiesops/notificationClient";

const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }));
const rejected = (status: number) =>
  new Response(
    JSON.stringify({
      success: false,
      message: "현재 접근 권한이 없습니다.",
      requestId: "notification-route",
    }),
    { status },
  );
let fetcher: ReturnType<typeof vi.fn>;
let rows: SecurityNotification[];
let count: number;
let menuStatus: number;
let listStatus: number;
let writeStatus: number;
const Location = () => (
  <output aria-label="현재 주소">
    {useLocation().pathname}
    {useLocation().search}
  </output>
);
function mount(path = "/UI-34") {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Location />
      <AppRouter />
    </MemoryRouter>,
  );
}
beforeEach(() => {
  sessionStorage.clear();
  localStorage.clear();
  useSharedPreferences.setState({
    favorites: [],
    recent: [],
    collapsed: false,
    pageSize: 50,
    dense: false,
  });
  rows = [
    {
      id: 73,
      type: "QUOTE_APPROVAL",
      title: "견적 확인 요청",
      content: "담당 기회를 검토하세요.",
      createdAt: "2026-11-06T09:00:00+09:00",
      read: false,
      resolved: false,
      targetScreenId: "UI-29",
      targetId: 731,
      basisMonth: "2026-11",
    },
  ];
  count = 100;
  menuStatus = 0;
  listStatus = 0;
  writeStatus = 0;
  fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (url === "/api/auth/me")
      return response({
        name: "합성 시험 관리자",
        roles: ["1"],
        passwordAgeWarning: false,
      });
    if (url === "/api/me/permissions")
      return response({
        permissions: ["UI-23", "UI-29"].map((screenId) => ({
          screenId,
          read: true,
        })),
      });
    if (url === "/api/navigation")
      return response({ firstAllowedScreenId: "UI-23" });
    if (url === "/api/menus")
      return menuStatus ? rejected(menuStatus) : response([]);
    if (url === "/api/notifications/unread-count")
      return response({ unreadCount: count });
    if (url.startsWith("/api/notifications?"))
      return listStatus
        ? rejected(listStatus)
        : response({ items: rows, total: rows.length, page: 0, size: 20 });
    if (url === "/api/auth/csrf")
      return response({
        headerName: "X-CSRF-TOKEN",
        csrfToken: "notification-route-proof",
      });
    if (url === "/api/notifications/73/read") {
      if (writeStatus) return rejected(writeStatus);
      rows = rows.map((row) => ({ ...row, read: true }));
      count = 0;
      return response({
        id: 73,
        read: true,
        unreadCount: count,
        targetScreenId: "UI-29",
        targetId: 731,
      });
    }
    throw new Error(`Unexpected request ${url} ${init?.method}`);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("notification route registration", () => {
  it("renders the real header-only screen without a database menu leaf", async () => {
    mount();
    expect(await screen.findByTestId("notifications-screen")).toHaveAttribute(
      "data-screen-id",
      "SCR-UI-34",
    );
    await screen.findByText("견적 확인 요청");
    expect(sharedRoutes).toContain("/UI-34");
    expect(isScreenId("UI-34")).toBe(true);
    expect(screen.getAllByLabelText("열린 화면")).toHaveLength(1);
    expect(screen.getByTestId("app-shell-tab-ui-34")).toHaveTextContent(
      "내 알림",
    );
    expect(
      screen.queryByText("화면을 찾을 수 없습니다."),
    ).not.toBeInTheDocument();
    expect(
      fetcher.mock.calls.every(
        ([, init]) => !init?.method || init.method === "GET",
      ),
    ).toBe(true);
  });
  it("opens the recent-20 popover from the shell and navigates to one full view", async () => {
    mount("/UI-23");
    await screen.findByText("99+");
    fireEvent.click(screen.getByRole("button", { name: "알림" }));
    await within(screen.getByTestId("notifications-popover")).findByText(
      "견적 확인 요청",
    );
    expect(fetcher).toHaveBeenCalledWith(
      "/api/notifications?page=0&size=20",
      expect.anything(),
    );
    fireEvent.click(screen.getByRole("button", { name: "알림 전체 보기" }));
    await screen.findByRole("heading", { name: "내 알림" });
    expect(screen.getByLabelText("현재 주소")).toHaveTextContent("/UI-34");
    expect(screen.getAllByTestId("notifications-screen")).toHaveLength(1);
    expect(
      screen.queryByTestId("notifications-popover"),
    ).not.toBeInTheDocument();
  });
  it("preserves successful read-before-target navigation through the real router", async () => {
    mount();
    await screen.findByText("견적 확인 요청");
    fireEvent.click(screen.getByRole("button", { name: "대상으로 이동" }));
    await waitFor(() =>
      expect(screen.getByLabelText("현재 주소")).toHaveTextContent(
        "/UI-29?open=731",
      ),
    );
    expect(fetcher).toHaveBeenCalledWith(
      "/api/notifications/73/read",
      expect.objectContaining({
        method: "PATCH",
        credentials: "same-origin",
        headers: { "X-CSRF-TOKEN": "notification-route-proof" },
      }),
    );
  });
  it.each([403, 404])(
    "keeps rejected target %s on the current notification route",
    async (status) => {
      writeStatus = status;
      mount();
      await screen.findByText("견적 확인 요청");
      fireEvent.click(screen.getByRole("button", { name: "대상으로 이동" }));
      await screen.findByRole("alert");
      expect(screen.getByLabelText("현재 주소")).toHaveTextContent("/UI-34");
      expect(rows[0].read).toBe(false);
    },
  );
  it("clears protected rows on a permission-change event and recovers an empty view", async () => {
    mount();
    await screen.findByText("견적 확인 요청");
    listStatus = 403;
    act(() => window.dispatchEvent(new Event("sicost:permissions-changed")));
    await screen.findByRole("alert");
    expect(screen.queryByText("견적 확인 요청")).not.toBeInTheDocument();
    listStatus = 0;
    rows = [];
    count = 0;
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    await screen.findByText("알림이 없습니다.");
    expect(screen.getByRole("button", { name: "모두 읽음" })).toBeDisabled();
  });
  it.each([401, 403])(
    "does not mount notification content or polling after menu denial %s",
    async (status) => {
      menuStatus = status;
      mount();
      if (status === 401) {
        await screen.findByTestId("authentication-screen");
        expect(screen.getByLabelText("현재 주소")).toHaveTextContent("/UI-22");
      } else await screen.findByRole("alert");
      expect(
        screen.queryByTestId("notifications-screen"),
      ).not.toBeInTheDocument();
      expect(screen.queryByTestId("notification-bell")).not.toBeInTheDocument();
      expect(
        fetcher.mock.calls.some(([url]) =>
          url.startsWith("/api/notifications"),
        ),
      ).toBe(false);
    },
  );
});
