import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { MemoryRouter, useLocation } from "react-router-dom";
import NotificationsScreen from "./NotificationsScreen";
const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, data, meta: {} }), {
    headers: { "Content-Type": "application/json" },
  });
let fetcher: ReturnType<typeof vi.fn>;
let empty: boolean, denied: boolean;
function Location() {
  return <span data-testid="location">{useLocation().pathname}</span>;
}
function mount(popover = false) {
  return render(
    <MemoryRouter>
      <NotificationsScreen popover={popover} />
      <Location />
    </MemoryRouter>,
  );
}
beforeEach(() => {
  empty = false;
  denied = false;
  fetcher = vi.fn(async (path: string, init?: RequestInit) => {
    if (path === "/api/auth/csrf")
      return response({ headerName: "X-CSRF-TOKEN", csrfToken: "test-proof" });
    if (path.includes("/read") || init?.method === "POST") {
      if (denied)
        return new Response(
          JSON.stringify({
            success: false,
            message: "대상 접근 권한이 없습니다.",
            code: "FORBIDDEN",
          }),
          { status: 403 },
        );
      return response(null);
    }
    if (path.includes("unread-count")) return response({ count: 100 });
    return response({
      items: empty
        ? []
        : [
            {
              notificationId: 82,
              title: "견적 검토",
              message: "확인이 필요합니다",
              createdAt: "2026-10-23T09:00:00",
              read: false,
              targetScreenId: "UI-29",
              target: { id: 7 },
            },
          ],
      total: 1,
      page: 0,
      size: 20,
    });
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => vi.unstubAllGlobals());
it("renders authorized data and caps only the displayed badge", async () => {
  mount();
  expect(await screen.findByText("견적 검토")).toBeInTheDocument();
  expect(screen.getByLabelText("미읽음 수")).toHaveTextContent("99+");
  expect(fetcher.mock.calls.every(([, init]) => !init?.method)).toBe(true);
});
it("renders an empty list", async () => {
  empty = true;
  mount();
  expect(await screen.findByText("새 알림이 없습니다")).toBeInTheDocument();
  expect(screen.getByTestId("notifications-read-all")).toBeDisabled();
});
it("sends the read command with CSRF before target navigation", async () => {
  mount();
  fireEvent.click(await screen.findByText("대상으로 이동"));
  await waitFor(() =>
    expect(screen.getByTestId("location")).toHaveTextContent("/UI-29"),
  );
  const call = fetcher.mock.calls.find(
    ([path]) => path === "/api/notifications/82/read",
  );
  expect(call?.[1]).toEqual(
    expect.objectContaining({ method: "PATCH", credentials: "same-origin" }),
  );
  expect(new Headers(call?.[1]?.headers).get("X-CSRF-TOKEN")).toBe(
    "test-proof",
  );
});
it("does not navigate and clears revoked content on read rejection", async () => {
  denied = true;
  mount();
  fireEvent.click(await screen.findByText("대상으로 이동"));
  expect(await screen.findByRole("alert")).toHaveTextContent(
    "대상 접근 권한이 없습니다.",
  );
  expect(screen.queryByText("견적 검토")).not.toBeInTheDocument();
  expect(screen.getByTestId("location")).not.toHaveTextContent("UI-29");
});
it("read-all has no recipient supplied by the client", async () => {
  mount();
  await screen.findByText("견적 검토");
  fireEvent.click(screen.getByTestId("notifications-read-all"));
  expect(await screen.findByText("읽음 처리되었습니다.")).toBeInTheDocument();
  expect(fetcher).toHaveBeenCalledWith(
    "/api/notifications/read-all",
    expect.objectContaining({ method: "POST" }),
  );
});
it("filters unread notifications at the API", async () => {
  mount();
  await screen.findByText("견적 검토");
  fireEvent.click(screen.getByLabelText("읽지 않은 알림만"));
  await waitFor(() =>
    expect(fetcher).toHaveBeenCalledWith(
      "/api/notifications?page=0&size=20&unreadOnly=true",
      expect.anything(),
    ),
  );
});
it("popover offers all-notifications without a sidebar item", async () => {
  mount(true);
  await screen.findByText("견적 검토");
  fireEvent.click(screen.getByText("알림 전체 보기"));
  expect(screen.getByTestId("location")).toHaveTextContent("/UI-34");
});
it("shows request failures without stale rows", async () => {
  fetcher.mockImplementation(
    async () =>
      new Response(
        JSON.stringify({ success: false, message: "조회 권한이 없습니다." }),
        { status: 403 },
      ),
  );
  mount();
  expect(await screen.findByRole("alert")).toHaveTextContent(
    "조회 권한이 없습니다.",
  );
  expect(screen.queryByText("견적 검토")).not.toBeInTheDocument();
});
