import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter, useLocation } from "react-router-dom";
import NotificationsScreen, {
  NotificationBell,
  NotificationsPopover,
} from "./NotificationsScreen";
import type { SecurityNotification } from "./notificationClient";
import { updateOpportunityStage } from "./client";

const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    headers: { "Content-Type": "application/json" },
  });
const rejected = (status: number) =>
  new Response(
    JSON.stringify({
      success: false,
      message: status === 403 ? "대상 접근 불가" : "조회 실패",
      requestId: "notification-test",
    }),
    { status, headers: { "Content-Type": "application/json" } },
  );
let rows: SecurityNotification[];
let fetcher: ReturnType<typeof vi.fn>;
let unreadCount: number,
  total: number,
  failRead: number,
  failWrite: number,
  failDetail: number;
const Location = () => (
  <output data-testid="location">
    {useLocation().pathname}
    {useLocation().search}
  </output>
);
const mount = (child: React.ReactNode) =>
  render(
    <MemoryRouter initialEntries={["/UI-34"]}>
      {child}
      <Location />
    </MemoryRouter>,
  );
beforeEach(() => {
  unreadCount = 1;
  total = 1;
  failRead = 0;
  failWrite = 0;
  failDetail = 0;
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
  fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (url === "/api/auth/csrf")
      return response({
        headerName: "X-CSRF-TOKEN",
        csrfToken: "test-notification-proof",
      });
    if (url === "/api/notifications/unread-count")
      return failRead ? rejected(failRead) : response({ unreadCount });
    if (url.startsWith("/api/notifications?"))
      return failRead
        ? rejected(failRead)
        : response({
            items:
              new URL(url, "https://example.test").searchParams.get(
                "unreadOnly",
              ) === "true"
                ? rows.filter((row) => !row.read)
                : rows,
            total,
            page: 0,
            size: 20,
          });
    if (url === "/api/notifications/73")
      return failDetail ? rejected(failDetail) : response(rows[0]);
    if (url === "/api/notifications/73/read") {
      if (failWrite) return rejected(failWrite);
      rows = rows.map((row) => ({ ...row, read: true }));
      unreadCount = 0;
      return response({
        id: 73,
        read: true,
        unreadCount,
        targetScreenId: rows[0].targetScreenId,
        targetId: rows[0].targetId,
      });
    }
    if (url === "/api/notifications/read-all") {
      if (failWrite) return rejected(failWrite);
      rows = rows.map((row) => ({ ...row, read: true }));
      unreadCount = 0;
      return response({ updatedCount: rows.length, unreadCount });
    }
    if (url === "/api/opportunities/731/stage")
      return response({ bsn_stg_cd: "PROP" });
    throw new Error(`Unexpected request ${url} ${init?.method}`);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
const writes = () =>
  fetcher.mock.calls.filter(([, init]) =>
    ["POST", "PATCH"].includes(init?.method ?? "GET"),
  );

describe("내 알림", () => {
  it("renders current server rows, badges and full unread count without marking anything read", async () => {
    unreadCount = 143;
    mount(<NotificationsScreen />);
    await screen.findByText("견적 확인 요청");
    expect(screen.getByText("총 1건 · 미읽음 143건")).toBeVisible();
    expect(screen.getByText("미읽음")).toBeVisible();
    expect(screen.getByTestId("notification-row-73").className).toContain(
      "unread",
    );
    expect(writes()).toHaveLength(0);
  });
  it("GET detail never marks read or navigates", async () => {
    mount(<NotificationsScreen />);
    await screen.findByText("견적 확인 요청");
    fireEvent.click(screen.getByRole("button", { name: "견적 확인 요청" }));
    await screen.findByTestId("notification-detail-panel");
    expect(writes()).toHaveLength(0);
    expect(screen.getByTestId("location")).toHaveTextContent("/UI-34");
  });
  it("requires successful read and refresh before target navigation and sends session CSRF", async () => {
    mount(<NotificationsScreen />);
    await screen.findByText("견적 확인 요청");
    fireEvent.click(screen.getByRole("button", { name: "대상으로 이동" }));
    await waitFor(() =>
      expect(screen.getByTestId("location")).toHaveTextContent(
        "/UI-29?open=731",
      ),
    );
    const mutation = writes()[0];
    expect(mutation[0]).toBe("/api/notifications/73/read");
    expect(mutation[1]).toMatchObject({
      method: "PATCH",
      credentials: "same-origin",
      headers: { "X-CSRF-TOKEN": "test-notification-proof" },
    });
    const index = fetcher.mock.calls.findIndex(
      ([url]) => url === "/api/notifications/73/read",
    );
    expect(
      fetcher.mock.calls
        .slice(index + 1)
        .some(([url]) => url.startsWith("/api/notifications?")),
    ).toBe(true);
  });
  it("does not navigate or report success on rejected read", async () => {
    failWrite = 409;
    mount(<NotificationsScreen />);
    await screen.findByText("견적 확인 요청");
    fireEvent.click(screen.getByRole("button", { name: "대상으로 이동" }));
    await screen.findByRole("alert");
    expect(screen.getByTestId("location")).toHaveTextContent("/UI-34");
    expect(screen.queryByText("읽음 처리했습니다.")).not.toBeInTheDocument();
  });
  it("read-all refreshes list and unread count without confirmation or optimistic success", async () => {
    mount(<NotificationsScreen />);
    await screen.findByText("견적 확인 요청");
    fireEvent.click(screen.getByRole("button", { name: "모두 읽음" }));
    await screen.findByText("현재 열람 가능한 알림을 모두 읽음 처리했습니다.");
    expect(screen.getByText("읽음")).toBeVisible();
    expect(screen.getByText("총 1건 · 미읽음 0건")).toBeVisible();
    expect(writes()[0][0]).toBe("/api/notifications/read-all");
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });
  it("read-all failure preserves unread state", async () => {
    failWrite = 409;
    mount(<NotificationsScreen />);
    await screen.findByText("견적 확인 요청");
    fireEvent.click(screen.getByRole("button", { name: "모두 읽음" }));
    await screen.findByRole("alert");
    expect(screen.getByText("미읽음")).toBeVisible();
    expect(
      screen.queryByText("현재 열람 가능한 알림을 모두 읽음 처리했습니다."),
    ).not.toBeInTheDocument();
  });
  it("filters unread notifications and resets pagination", async () => {
    mount(<NotificationsScreen />);
    await screen.findByText("견적 확인 요청");
    fireEvent.click(screen.getByLabelText("읽지 않은 알림만"));
    fireEvent.click(screen.getByRole("button", { name: "조회" }));
    await waitFor(() =>
      expect(fetcher).toHaveBeenCalledWith(
        expect.stringContaining("unreadOnly=true&page=0&size=20"),
        expect.anything(),
      ),
    );
  });
  it("paginates using the server total rather than the visible row count", async () => {
    total = 41;
    mount(<NotificationsScreen />);
    await screen.findByText("견적 확인 요청");
    expect(screen.getByRole("button", { name: "이전" })).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: "다음" }));
    await waitFor(() =>
      expect(fetcher).toHaveBeenCalledWith(
        expect.stringContaining("page=1&size=20"),
        expect.anything(),
      ),
    );
    expect(screen.getByText("2 페이지")).toBeVisible();
  });
  it("keeps resolved history and hides its target action", async () => {
    rows[0] = { ...rows[0], resolved: true, read: true };
    unreadCount = 0;
    mount(<NotificationsScreen />);
    await screen.findByText("견적 확인 요청");
    expect(screen.getByText("해소")).toBeVisible();
    expect(
      screen.queryByRole("button", { name: "대상으로 이동" }),
    ).not.toBeInTheDocument();
    expect(screen.getByTestId("notification-row-73").className).toContain(
      "read",
    );
  });
  it("shows an empty list and disables read-all", async () => {
    rows = [];
    unreadCount = 0;
    total = 0;
    mount(<NotificationsScreen />);
    await screen.findByText("알림이 없습니다.");
    expect(screen.getByRole("button", { name: "모두 읽음" })).toBeDisabled();
    fetcher.mockClear();
    fireEvent.click(screen.getByRole("button", { name: "새로고침" }));
    await screen.findByText("알림이 없습니다.");
    expect(fetcher).toHaveBeenCalledWith(
      "/api/notifications?unreadOnly=false&page=0&size=20",
      expect.anything(),
    );
    expect(fetcher).toHaveBeenCalledWith(
      "/api/notifications/unread-count",
      expect.anything(),
    );
    expect(writes()).toHaveLength(0);
  });
  it("removes old content when current authorization is lost and can recover", async () => {
    mount(<NotificationsScreen />);
    await screen.findByText("견적 확인 요청");
    failRead = 403;
    window.dispatchEvent(new Event("sicost:permissions-changed"));
    await screen.findByRole("alert");
    expect(screen.queryByText("견적 확인 요청")).not.toBeInTheDocument();
    failRead = 0;
    rows = [];
    total = 0;
    unreadCount = 0;
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    await screen.findByText("알림이 없습니다.");
  });
  it("clears selected detail and protected row after denied direct detail", async () => {
    mount(<NotificationsScreen />);
    await screen.findByText("견적 확인 요청");
    failDetail = 403;
    rows = [];
    total = 0;
    fireEvent.click(screen.getByRole("button", { name: "견적 확인 요청" }));
    await screen.findByRole("alert");
    expect(
      screen.queryByTestId("notification-detail-panel"),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByText("담당 기회를 검토하세요."),
    ).not.toBeInTheDocument();
    expect(writes()).toHaveLength(0);
  });
  it("fails closed for an unregistered or malicious target path", async () => {
    rows[0].targetScreenId = "https://outside.test";
    mount(<NotificationsScreen />);
    await screen.findByText("견적 확인 요청");
    fireEvent.click(screen.getByRole("button", { name: "대상으로 이동" }));
    await screen.findByRole("alert");
    expect(screen.getByTestId("location")).toHaveTextContent("/UI-34");
  });
  it("renders loading state while API responses are outstanding", () => {
    fetcher.mockImplementation(() => new Promise(() => {}));
    mount(<NotificationsScreen />);
    expect(screen.getByLabelText("알림 불러오는 중")).toBeInTheDocument();
    expect(screen.queryByTestId("notification-row-73")).not.toBeInTheDocument();
  });
  it("popover requests recent 20 and navigates to the single full view", async () => {
    const close = vi.fn();
    mount(<NotificationsPopover onClose={close} />);
    await within(screen.getByTestId("notifications-popover")).findByText(
      "견적 확인 요청",
    );
    expect(fetcher).toHaveBeenCalledWith(
      "/api/notifications?page=0&size=20",
      expect.anything(),
    );
    fireEvent.click(screen.getByRole("button", { name: "알림 전체 보기" }));
    expect(close).toHaveBeenCalledOnce();
    expect(screen.getByTestId("location")).toHaveTextContent("/UI-34");
  });
  it("header alone caps count at 99+ and returns focus to the bell", async () => {
    unreadCount = 143;
    mount(<NotificationBell />);
    await screen.findByText("99+");
    expect(screen.getByLabelText("미읽음 143건")).toBeVisible();
    fireEvent.click(screen.getByRole("button", { name: "알림" }));
    await screen.findByTestId("notifications-popover");
    fireEvent.click(screen.getByRole("button", { name: "닫기" }));
    expect(screen.getByRole("button", { name: "알림" })).toHaveFocus();
    expect(
      screen.queryByTestId("notifications-popover"),
    ).not.toBeInTheDocument();
  });
  it("opportunity lifecycle writes also bootstrap CSRF without changing their payload", async () => {
    await updateOpportunityStage(731, "PROP");
    expect(fetcher).toHaveBeenLastCalledWith(
      "/api/opportunities/731/stage",
      expect.objectContaining({
        method: "POST",
        body: JSON.stringify({ bsn_stg_cd: "PROP" }),
        headers: {
          "X-CSRF-TOKEN": "test-notification-proof",
          "Content-Type": "application/json",
        },
      }),
    );
  });
});
