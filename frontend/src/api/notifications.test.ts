import { afterEach, expect, it, vi } from "vitest";
import * as client from "./apiClient";
import * as notifications from "../pages/opportunitiesops/notificationClient";

const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }));
afterEach(() => vi.unstubAllGlobals());
it.each([
  "listNotifications",
  "getNotification",
  "getNotificationUnreadCount",
  "markNotificationRead",
  "markAllNotificationsRead",
] as const)(
  "registers %s without a second transport or import cycle",
  (name) => {
    expect(client[name]).toBe(notifications[name]);
  },
);
it("keeps notification paging, selected identifiers and body-less CSRF writes", async () => {
  const fetcher = vi.fn(async (path: string) =>
    response(
      path === "/api/auth/csrf"
        ? { headerName: "X-CSRF-TOKEN", csrfToken: "test-csrf" }
        : {},
    ),
  );
  vi.stubGlobal("fetch", fetcher);
  await client.listNotifications(2, true);
  await client.getNotification(71);
  await client.getNotificationUnreadCount();
  await client.markNotificationRead(71);
  await client.markAllNotificationsRead();
  expect(fetcher.mock.calls.map(([path]) => path)).toEqual([
    "/api/notifications?page=2&size=20&unreadOnly=true",
    "/api/notifications/71",
    "/api/notifications/unread-count",
    "/api/auth/csrf",
    "/api/notifications/71/read",
    "/api/auth/csrf",
    "/api/notifications/read-all",
  ]);
  expect(fetcher).toHaveBeenCalledWith(
    "/api/notifications/71/read",
    expect.objectContaining({
      method: "PATCH",
      credentials: "same-origin",
      headers: { "X-CSRF-TOKEN": "test-csrf" },
    }),
  );
  expect(fetcher).toHaveBeenCalledWith(
    "/api/notifications/read-all",
    expect.objectContaining({ method: "POST" }),
  );
});
it.each([401, 403, 404])(
  "preserves %s rather than returning a successful notification",
  async (status) => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            success: false,
            code: "DENIED",
            message: "접근 불가",
            requestId: "notification-test",
          }),
          { status },
        ),
      ),
    );
    await expect(client.getNotification(71)).rejects.toMatchObject({
      status,
      code: "DENIED",
      requestId: "notification-test",
    });
  },
);
