import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { MemoryRouter, useLocation } from "react-router-dom";
import NotificationsScreen, {
  NotificationPopover,
  notificationPermissionEvent,
  notificationSessionEvent,
} from "./NotificationsScreen";
import type { NotificationItem } from "./notificationClient";

const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    headers: { "Content-Type": "application/json" },
  });
const denied = (status: number) =>
  new Response(
    JSON.stringify({
      success: false,
      code: status === 401 ? "UNAUTHENTICATED" : "FORBIDDEN",
      message: status === 401 ? "로그인이 필요합니다." : "대상 접근 불가",
      requestId: "notification-test",
    }),
    { status, headers: { "Content-Type": "application/json" } },
  );
let rows: NotificationItem[],
  fetcher: ReturnType<typeof vi.fn>,
  total: number,
  readFailure: number,
  detailFailure: number,
  listFailure: number;
function Location() {
  const location = useLocation();
  return (
    <output data-testid="location">
      {location.pathname}
      {location.search}
      {JSON.stringify(location.state)}
    </output>
  );
}
function mount(child = <NotificationsScreen />) {
  return render(
    <MemoryRouter initialEntries={["/UI-34"]}>
      {child}
      <Location />
    </MemoryRouter>,
  );
}
beforeEach(() => {
  total = 1;
  readFailure = 0;
  detailFailure = 0;
  listFailure = 0;
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
    if (url === "/api/auth/csrf")
      return response({ headerName: "X-CSRF-TOKEN", csrfToken: "test-csrf" });
    if (url.startsWith("/api/notifications?")) {
      if (listFailure) return denied(listFailure);
      const params = new URLSearchParams(url.split("?")[1]);
      return response({
        items:
          params.get("page") === "0"
            ? rows.filter(
                (row) => params.get("unreadOnly") !== "true" || !row.read,
              )
            : [],
        total,
        page: Number(params.get("page")),
        size: 20,
      });
    }
    if (url === "/api/notifications/unread-count")
      return response({ count: rows.filter((row) => !row.read).length });
    if (
      url === "/api/notifications/71/read" ||
      url === "/api/notifications/read-all"
    ) {
      if (readFailure) return denied(readFailure);
      expect(init?.headers).toEqual(
        expect.objectContaining({ "X-CSRF-TOKEN": "test-csrf" }),
      );
      expect(init?.body).toBeUndefined();
      rows = rows.map((row) => ({ ...row, read: true }));
      return response({});
    }
    if (url === "/api/notifications/71")
      return detailFailure ? denied(detailFailure) : response(rows[0]);
    throw new Error(`Unexpected request ${url}`);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  vi.useRealTimers();
});

it("renders current API title, message, timestamp, unread count and target", async () => {
  mount();
  await screen.findByText("견적 승인 요청");
  expect(screen.getByText("허용된 기회 검토")).toBeVisible();
  expect(screen.getByText("2026-10-23 09:00:00")).toBeVisible();
  expect(screen.getByText("미읽음 1")).toBeVisible();
  expect(screen.getByTestId("notification-target-71")).toBeVisible();
  expect(fetcher).toHaveBeenCalledWith(
    "/api/notifications?page=0&size=20&unreadOnly=false",
    expect.anything(),
  );
});
it("marks only the selected id read then refreshes list and count", async () => {
  mount();
  await screen.findByTestId("notification-read-71");
  fireEvent.click(screen.getByTestId("notification-read-71"));
  await screen.findByText("읽음 처리했습니다.");
  expect(screen.getByText("미읽음 0")).toBeVisible();
  expect(screen.queryByTestId("notification-read-71")).not.toBeInTheDocument();
  expect(
    fetcher.mock.calls.filter(
      ([url]) => url === "/api/notifications/unread-count",
    ),
  ).toHaveLength(2);
});
it("marks all read without an identifier body or extra confirmation", async () => {
  mount();
  await screen.findByText("견적 승인 요청");
  fireEvent.click(screen.getByTestId("notifications-read-all"));
  await screen.findByText("읽음 처리했습니다.");
  expect(fetcher).toHaveBeenCalledWith(
    "/api/notifications/read-all",
    expect.objectContaining({ method: "POST" }),
  );
  expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
});
it("filters unread only on query and starts from the first page", async () => {
  mount();
  await screen.findByText("견적 승인 요청");
  fireEvent.click(screen.getByTestId("notifications-unread-filter"));
  expect(
    fetcher.mock.calls.some(([url]) => String(url).includes("unreadOnly=true")),
  ).toBe(false);
  fireEvent.click(screen.getByTestId("notifications-query"));
  await waitFor(() =>
    expect(fetcher).toHaveBeenCalledWith(
      "/api/notifications?page=0&size=20&unreadOnly=true",
      expect.anything(),
    ),
  );
});
it("uses server total for next and previous page controls", async () => {
  total = 21;
  mount();
  await screen.findByText("견적 승인 요청");
  expect(screen.getByTestId("notifications-previous")).toBeDisabled();
  fireEvent.click(screen.getByTestId("notifications-next"));
  await waitFor(() =>
    expect(fetcher).toHaveBeenCalledWith(
      "/api/notifications?page=1&size=20&unreadOnly=false",
      expect.anything(),
    ),
  );
  await screen.findByText("알림이 없습니다.");
  expect(screen.getByTestId("notifications-next")).toBeDisabled();
});
it("shows empty state without inventing rows", async () => {
  rows = [];
  total = 0;
  mount();
  await screen.findByText("알림이 없습니다.");
  expect(screen.queryByTestId("notification-row-71")).not.toBeInTheDocument();
  expect(screen.getByTestId("notifications-read-all")).toBeDisabled();
});
it("refreshes the list and unread count from the empty state", async () => {
  const notification = rows[0];
  rows = [];
  total = 0;
  mount();
  await screen.findByText("알림이 없습니다.");
  rows = [notification];
  total = 1;
  fireEvent.click(screen.getByRole("button", { name: "새로고침" }));
  await screen.findByText("견적 승인 요청");
  expect(screen.getByText("미읽음 1")).toBeVisible();
  expect(screen.queryByText("알림이 없습니다.")).not.toBeInTheDocument();
  expect(
    fetcher.mock.calls.filter(
      ([url]) => url === "/api/notifications/unread-count",
    ),
  ).toHaveLength(2);
});
it.each([401, 403, 500])(
  "removes content on failed %s list and supports retry",
  async (status) => {
    listFailure = status;
    mount();
    await screen.findByRole("alert");
    expect(screen.queryByText("견적 승인 요청")).not.toBeInTheDocument();
    listFailure = 0;
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    await screen.findByText("견적 승인 요청");
  },
);
it.each([403, 404])(
  "does not navigate when read fails with %s",
  async (status) => {
    readFailure = status;
    mount();
    await screen.findByText("견적 승인 요청");
    fireEvent.click(screen.getByTestId("notification-target-71"));
    await screen.findByRole("alert");
    expect(screen.getByTestId("location")).toHaveTextContent("/UI-34");
    expect(screen.queryByText("견적 승인 요청")).not.toBeInTheDocument();
    expect(
      fetcher.mock.calls.some(([url]) => url === "/api/notifications/71"),
    ).toBe(false);
  },
);
it("revalidates target after marking read and uses only internal routes", async () => {
  mount();
  await screen.findByText("견적 승인 요청");
  fireEvent.click(screen.getByTestId("notification-target-71"));
  await waitFor(() =>
    expect(screen.getByTestId("location")).toHaveTextContent("/UI-29?open=7"),
  );
  const calls = fetcher.mock.calls.map(([url]) => url);
  expect(calls.indexOf("/api/notifications/71/read")).toBeLessThan(
    calls.indexOf("/api/notifications/71"),
  );
});
it("does not navigate when current target authorization was lost", async () => {
  detailFailure = 403;
  mount();
  await screen.findByText("견적 승인 요청");
  fireEvent.click(screen.getByTestId("notification-target-71"));
  await screen.findByRole("alert");
  expect(screen.getByTestId("location")).toHaveTextContent("/UI-34");
  expect(screen.queryByText("허용된 기회 검토")).not.toBeInTheDocument();
});
it("keeps resolved history but offers no target action", async () => {
  rows[0].resolved = true;
  mount();
  await screen.findByText("해소됨");
  expect(screen.getByText("견적 승인 요청")).toBeVisible();
  expect(
    screen.queryByTestId("notification-target-71"),
  ).not.toBeInTheDocument();
});
it("clears revoked content immediately and restores from the server when permission returns", async () => {
  mount();
  await screen.findByText("견적 승인 요청");
  listFailure = 403;
  window.dispatchEvent(new Event(notificationPermissionEvent));
  await screen.findByRole("alert");
  expect(screen.queryByText("견적 승인 요청")).not.toBeInTheDocument();
  listFailure = 0;
  window.dispatchEvent(new Event(notificationPermissionEvent));
  await screen.findByText("견적 승인 요청");
});
it("clears displayed rows and unread count on session termination", async () => {
  mount();
  await screen.findByText("견적 승인 요청");
  window.dispatchEvent(new Event(notificationSessionEvent));
  await screen.findByText("로그인이 필요합니다.");
  expect(screen.queryByText("견적 승인 요청")).not.toBeInTheDocument();
  expect(screen.queryByText("미읽음 1")).not.toBeInTheDocument();
});
it("renders a nonmodal latest-20 popover and navigates to full view", async () => {
  const close = vi.fn();
  mount(<NotificationPopover onClose={close} />);
  await screen.findByText("견적 승인 요청");
  expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  expect(
    screen.queryByTestId("notifications-unread-filter"),
  ).not.toBeInTheDocument();
  fireEvent.click(screen.getByTestId("notifications-view-all"));
  expect(close).toHaveBeenCalledOnce();
  expect(screen.getByTestId("location")).toHaveTextContent("/UI-34");
});
it("hides stale rows during loading rather than presenting previous data as current", async () => {
  let finish!: (response: Response) => void;
  const original = fetcher.getMockImplementation()!;
  fetcher.mockImplementation((url: string, init?: RequestInit) =>
    url.startsWith("/api/notifications?")
      ? new Promise<Response>((resolve) => {
          finish = resolve;
        })
      : original(url, init),
  );
  mount();
  expect(screen.queryByText("견적 승인 요청")).not.toBeInTheDocument();
  expect(screen.getByTestId("notifications-read-all")).toBeDisabled();
  finish(response({ items: rows, total: 1, page: 0, size: 20 }));
  await screen.findByText("견적 승인 요청");
});
