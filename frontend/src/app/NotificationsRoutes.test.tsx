import {
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
import type { MenuNode } from "../pages/shared/api";
import type { NotificationItem } from "../pages/opportunitiesops/notificationClient";

const menus: MenuNode[] = [
  {
    menuId: 1,
    name: "시스템",
    screenId: null,
    icon: "gear",
    mobile: true,
    children: [
      {
        menuId: 2,
        name: "사용자 관리",
        screenId: "UI-23",
        icon: null,
        mobile: true,
        children: [],
      },
    ],
  },
];
const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }));
let fetcher: ReturnType<typeof vi.fn>;
let items: NotificationItem[], count: number, denied: boolean, failed: boolean;
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
  denied = false;
  failed = false;
  items = [
    {
      notificationId: 82,
      title: "권한 검토 요청",
      message: "확인이 필요합니다",
      createdAt: "2026-10-23T09:00:00",
      read: false,
      targetScreenId: "UI-23",
      target: null,
    },
  ];
  fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (url === "/api/menus") return response(menus);
    if (url === "/api/auth/csrf")
      return response({ headerName: "X-CSRF-TOKEN", csrfToken: "test-proof" });
    if (url === "/api/notifications/unread-count") return response({ count });
    if (url.startsWith("/api/notifications?")) {
      if (failed) throw new Error("offline");
      return response({ items, total: items.length, page: 0, size: 20 });
    }
    if (
      url === "/api/notifications/read-all" ||
      url === "/api/notifications/82/read"
    ) {
      if (denied)
        return new Response(
          JSON.stringify({
            success: false,
            code: "FORBIDDEN",
            message: "대상 접근 권한이 없습니다.",
            requestId: "denied-request",
          }),
          { status: 403 },
        );
      count = 0;
      items = items.map((item) => ({ ...item, read: true }));
      return response(null);
    }
    throw new Error(`Unexpected test request ${url} ${init?.method ?? "GET"}`);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => vi.unstubAllGlobals());

describe("SCR-UI-34 notification route registration", () => {
  it.each([375, 1440])(
    "renders the existing notification screen at width %s without a sidebar leaf",
    async (width) => {
      vi.stubGlobal("innerWidth", width);
      mount();
      const page = await screen.findByTestId("notifications-screen");
      expect(page).toHaveAttribute("data-screen-id", "UI-34");
      expect(
        await within(page).findByText("권한 검토 요청"),
      ).toBeInTheDocument();
      expect(screen.getAllByTestId("notifications-screen")).toHaveLength(1);
      expect(screen.getAllByLabelText("열린 화면")).toHaveLength(1);
      expect(
        within(screen.getByLabelText("화면 탐색")).queryByText("내 알림"),
      ).not.toBeInTheDocument();
      expect(
        within(page).queryByText("화면을 찾을 수 없습니다."),
      ).not.toBeInTheDocument();
      expect(fetcher.mock.calls.every(([, init]) => !init?.method)).toBe(true);
    },
  );

  it("opens the real recent-notification popover and follows all-notifications into the same shell", async () => {
    mount("/UI-23");
    expect(
      await screen.findByTestId("app-shell-notification-count"),
    ).toHaveTextContent("99+");
    fireEvent.click(screen.getByRole("button", { name: "알림" }));
    const popover = await screen.findByTestId("notifications-popover");
    expect(
      await within(popover).findByText("권한 검토 요청"),
    ).toBeInTheDocument();
    expect(fetcher).toHaveBeenCalledWith(
      "/api/notifications?page=0&size=20&unreadOnly=false",
      expect.anything(),
    );
    fireEvent.click(within(popover).getByText("알림 전체 보기"));
    expect(
      await screen.findByTestId("notifications-screen"),
    ).toBeInTheDocument();
    expect(screen.getByLabelText("현재 주소")).toHaveTextContent("/UI-34");
    expect(
      screen.queryByTestId("notifications-popover"),
    ).not.toBeInTheDocument();
    expect(screen.getAllByLabelText("열린 화면")).toHaveLength(1);
  });

  it("refreshes the header count after read-all through the registered screen", async () => {
    mount();
    await screen.findByText("권한 검토 요청");
    await screen.findByTestId("app-shell-notification-count");
    fireEvent.click(screen.getByTestId("notifications-read-all"));
    await screen.findByText("읽음 처리되었습니다.");
    await waitFor(() =>
      expect(
        screen.queryByTestId("app-shell-notification-count"),
      ).not.toBeInTheDocument(),
    );
    expect(fetcher).toHaveBeenCalledWith(
      "/api/notifications/read-all",
      expect.objectContaining({ method: "POST", credentials: "same-origin" }),
    );
    expect(screen.getByLabelText("현재 주소")).toHaveTextContent("/UI-34");
  });

  it("navigates to the registered target only after a successful read command", async () => {
    mount();
    fireEvent.click(await screen.findByText("대상으로 이동"));
    await waitFor(() =>
      expect(screen.getByLabelText("현재 주소")).toHaveTextContent("/UI-23"),
    );
    const call = fetcher.mock.calls.find(
      ([url]) => url === "/api/notifications/82/read",
    );
    expect(call?.[1]?.method).toBe("PATCH");
    expect(new Headers(call?.[1]?.headers).get("X-CSRF-TOKEN")).toBe(
      "test-proof",
    );
  });

  it("keeps a rejected target on the current route with a permission error and no stale content", async () => {
    denied = true;
    mount();
    fireEvent.click(await screen.findByText("대상으로 이동"));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "대상 접근 권한이 없습니다.",
    );
    expect(screen.queryByText("권한 검토 요청")).not.toBeInTheDocument();
    expect(screen.getByLabelText("현재 주소")).toHaveTextContent("/UI-34");
  });

  it("clears open recent content and reloads current-authorized rows and count on permission refresh", async () => {
    mount();
    await screen.findByText("권한 검토 요청");
    fireEvent.click(screen.getByRole("button", { name: "알림" }));
    await screen.findByTestId("notifications-popover");
    items = [];
    count = 2;
    fireEvent(window, new Event("permissions-changed"));
    expect(await screen.findByText("새 알림이 없습니다")).toBeInTheDocument();
    await waitFor(() =>
      expect(
        screen.getByTestId("app-shell-notification-count"),
      ).toHaveTextContent("2"),
    );
    expect(
      screen.queryByTestId("notifications-popover"),
    ).not.toBeInTheDocument();
    expect(screen.queryByText("권한 검토 요청")).not.toBeInTheDocument();
    expect(screen.getByTestId("notifications-read-all")).toBeDisabled();
  });

  it("preserves empty and failed-request recovery through the route", async () => {
    failed = true;
    mount();
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "서버에 연결할 수 없습니다.",
    );
    failed = false;
    items = [];
    fireEvent.click(screen.getByTestId("notifications-refresh"));
    expect(await screen.findByText("새 알림이 없습니다")).toBeInTheDocument();
    expect(screen.getByTestId("notifications-read-all")).toBeDisabled();
  });
});
