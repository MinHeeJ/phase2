import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { MemoryRouter, useLocation } from "react-router-dom";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { AppRouter } from "./router";
import { useSharedPreferences } from "../pages/shared/preferences";
import { isScreenId, sharedRoutes, screenIds } from "../pages/shared/registry";
import type { NotificationItem } from "../pages/opportunitiesops/notificationClient";

const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }));
let fetcher: ReturnType<typeof vi.fn>;
let rows: NotificationItem[], count: number, listStatus: number;
function Location() {
  return <output aria-label="현재 주소">{useLocation().pathname}</output>;
}
function mount(path = "/UI-34") {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Location />
      <AppRouter />
    </MemoryRouter>,
  );
}
beforeEach(() => {
  vi.stubGlobal("innerWidth", 1280);
  localStorage.clear();
  sessionStorage.clear();
  useSharedPreferences.setState({
    favorites: [],
    recent: [],
    collapsed: false,
    pageSize: 50,
    dense: false,
  });
  count = 100;
  listStatus = 0;
  rows = [
    {
      id: 71,
      type: "QUOTE_APPROVAL",
      title: "견적 승인 요청",
      message: "허용된 기회 검토",
      createdAt: "2026-10-23T09:00:00",
      read: false,
      readAt: null,
      screenId: "UI-29",
      linkParams: { open: 7 },
      resolved: false,
    },
  ];
  fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (url === "/api/auth/me")
      return response({
        userId: 417,
        name: "알림 검증 사용자",
        roleIds: [4],
        roles: ["영업 파트장"],
      });
    if (url === "/api/me/permissions")
      return response({
        permissions: [{ menuId: 29, screenId: "UI-29", inq: true }],
      });
    if (url === "/api/navigation")
      return response([
        {
          menuId: 29,
          name: "영업 기회",
          screenId: "UI-29",
          icon: null,
          mobile: true,
          children: [],
        },
      ]);
    if (url === "/api/menus") return response([]);
    if (url === "/api/auth/csrf")
      return response({ headerName: "X-CSRF-TOKEN", csrfToken: "test-csrf" });
    if (url === "/api/notifications/unread-count") return response({ count });
    if (url.startsWith("/api/notifications?")) {
      if (listStatus)
        return new Response(
          JSON.stringify({
            success: false,
            code: "FORBIDDEN",
            message: "알림 접근 불가",
            requestId: "notification-test",
          }),
          { status: listStatus },
        );
      return response({ items: rows, total: rows.length, page: 0, size: 20 });
    }
    if (
      url === "/api/notifications/read-all" ||
      url === "/api/notifications/71/read"
    ) {
      expect(init?.body).toBeUndefined();
      rows = rows.map((row) => ({ ...row, read: true }));
      count = 0;
      return response({});
    }
    throw new Error(`Unexpected request ${url}`);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  vi.useRealTimers();
});

it("registers the header-only full screen without requiring a sidebar leaf", async () => {
  mount();
  expect(await screen.findByTestId("notifications-screen")).toHaveAttribute(
    "data-screen-id",
    "SCR-UI-34",
  );
  expect(await screen.findByText("견적 승인 요청")).toBeVisible();
  expect(sharedRoutes).toContain("/UI-34");
  expect(isScreenId("UI-34")).toBe(true);
  expect(screenIds).not.toContain("UI-34");
  expect(
    within(screen.getByLabelText("화면 탐색")).queryByText("내 알림"),
  ).not.toBeInTheDocument();
  expect(screen.getAllByLabelText("열린 화면")).toHaveLength(1);
});
it("opens the real nonmodal popover and its full-view link in the same shell", async () => {
  mount();
  expect(await screen.findByTestId("app-shell-unread-count")).toHaveTextContent(
    "99+",
  );
  fireEvent.click(screen.getByRole("button", { name: "알림" }));
  const popover = await screen.findByTestId("notifications-popover");
  expect(await within(popover).findByText("견적 승인 요청")).toBeVisible();
  expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  fireEvent.click(
    within(popover).getByRole("button", { name: "알림 전체 보기" }),
  );
  expect(await screen.findByTestId("notifications-screen")).toBeVisible();
  expect(screen.getByLabelText("현재 주소")).toHaveTextContent("/UI-34");
  expect(screen.queryByTestId("notifications-popover")).not.toBeInTheDocument();
});
it("returns focus to the bell when the popover is closed", async () => {
  mount();
  await screen.findByText("견적 승인 요청");
  const bell = screen.getByRole("button", { name: "알림" });
  fireEvent.click(bell);
  fireEvent.click(
    await within(await screen.findByTestId("notifications-popover")).findByRole(
      "button",
      { name: "닫기" },
    ),
  );
  expect(bell).toHaveFocus();
  expect(screen.getByLabelText("현재 주소")).toHaveTextContent("/UI-34");
});
it("refreshes the header count after a successful read-all command", async () => {
  mount();
  await screen.findByText("견적 승인 요청");
  fireEvent.click(screen.getByTestId("notifications-read-all"));
  await waitFor(() =>
    expect(screen.getByTestId("app-shell-unread-count")).toHaveTextContent("0"),
  );
  expect(fetcher).toHaveBeenCalledWith(
    "/api/notifications/read-all",
    expect.objectContaining({
      method: "POST",
      credentials: "same-origin",
      headers: expect.objectContaining({ "X-CSRF-TOKEN": "test-csrf" }),
    }),
  );
});
it("clears revoked rows and count then restores the current server view", async () => {
  mount();
  await screen.findByText("견적 승인 요청");
  rows = [];
  count = 2;
  act(() => window.dispatchEvent(new Event("sicost:permissions-changed")));
  await screen.findByText("알림이 없습니다.");
  expect(screen.queryByText("견적 승인 요청")).not.toBeInTheDocument();
  await waitFor(() =>
    expect(screen.getByTestId("app-shell-unread-count")).toHaveTextContent("2"),
  );
});
it("clears notification content and the header count on session termination", async () => {
  mount();
  await screen.findByText("견적 승인 요청");
  act(() => window.dispatchEvent(new Event("sicost:session-ended")));
  expect(screen.queryByText("견적 승인 요청")).not.toBeInTheDocument();
  expect(
    screen.queryByTestId("app-shell-unread-count"),
  ).not.toBeInTheDocument();
  expect(await screen.findByTestId("login-screen")).toBeVisible();
  expect(screen.getByLabelText("현재 주소")).toHaveTextContent("/UI-22");
});
it("preserves permission errors instead of showing a successful empty list", async () => {
  listStatus = 403;
  mount();
  expect(await screen.findByRole("alert")).toHaveTextContent("알림 접근 불가");
  expect(screen.queryByText("알림이 없습니다.")).not.toBeInTheDocument();
  expect(screen.queryByText("견적 승인 요청")).not.toBeInTheDocument();
});
it("keeps the full notification controls available on mobile", async () => {
  vi.stubGlobal("innerWidth", 375);
  mount();
  await screen.findByText("견적 승인 요청");
  expect(screen.getByTestId("notifications-unread-filter")).toBeVisible();
  expect(screen.queryByText("PC에서 이용해 주세요")).not.toBeInTheDocument();
  expect(
    fetcher.mock.calls.some(([url]) =>
      String(url).includes("/api/auth/activity"),
    ),
  ).toBe(false);
});
