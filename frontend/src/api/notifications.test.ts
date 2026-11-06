import { afterEach, describe, expect, it, vi } from "vitest";
import * as client from "./apiClient";
import * as notifications from "../pages/opportunitiesops/notificationClient";

const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }));
afterEach(() => vi.unstubAllGlobals());
describe("notification API registration", () => {
  it.each([
    "listNotifications",
    "getNotificationUnreadCount",
    "getNotification",
    "updateNotificationRead",
    "saveNotificationsReadAll",
  ] as const)("reuses the feature's %s without a second transport", (name) => {
    expect(client[name]).toBe(notifications[name]);
  });
  it("passes filters and selected identifiers to the existing read clients", async () => {
    const fetcher = vi.fn(async () =>
      response({ items: [], total: 0, page: 0, size: 20 }),
    );
    vi.stubGlobal("fetch", fetcher);
    await client.listNotifications({ unreadOnly: true, page: 0, size: 20 });
    await client.getNotificationUnreadCount();
    await client.getNotification(73);
    expect(fetcher.mock.calls.map((call) => (call as unknown[])[0])).toEqual([
      "/api/notifications?unreadOnly=true&page=0&size=20",
      "/api/notifications/unread-count",
      "/api/notifications/73",
    ]);
  });
  it.each([
    ["updateNotificationRead", "/api/notifications/73/read", "PATCH"],
    ["saveNotificationsReadAll", "/api/notifications/read-all", "POST"],
  ] as const)("preserves CSRF and bodyless %s", async (name, path, method) => {
    const fetcher = vi.fn(async (url: string) =>
      response(
        url === "/api/auth/csrf"
          ? {
              headerName: "X-CSRF-TOKEN",
              csrfToken: "notification-client-proof",
            }
          : { unreadCount: 0 },
      ),
    );
    vi.stubGlobal("fetch", fetcher);
    if (name === "updateNotificationRead")
      await client.updateNotificationRead(73);
    else await client.saveNotificationsReadAll();
    expect(fetcher).toHaveBeenLastCalledWith(
      path,
      expect.objectContaining({
        method,
        credentials: "same-origin",
        headers: { "X-CSRF-TOKEN": "notification-client-proof" },
      }),
    );
    expect((fetcher.mock.calls.at(-1) as unknown[])[1]).not.toHaveProperty(
      "body",
    );
  });
  it.each([401, 403, 404, 409, 429, 503])(
    "does not turn status %s into success",
    async (status) => {
      vi.stubGlobal(
        "fetch",
        vi.fn(
          async () =>
            new Response(
              JSON.stringify({
                success: false,
                message: "알림 조회 실패",
                requestId: "notification-client",
              }),
              { status },
            ),
        ),
      );
      await expect(client.getNotification(73)).rejects.toMatchObject({
        status,
        requestId: "notification-client",
      });
    },
  );
});
