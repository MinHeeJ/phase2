import { afterEach, describe, expect, it, vi } from "vitest";
import * as client from "./apiClient";
import * as notifications from "../pages/opportunitiesops/notificationClient";

const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }));
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("notification API registration", () => {
  it.each([
    "listNotifications",
    "notificationCount",
    "readNotification",
    "readAllNotifications",
  ] as const)(
    "reexports %s from its owning client without a second transport",
    (name) => {
      expect(client[name]).toBe(notifications[name]);
    },
  );

  it("preserves the authorized page and uncapped server count without issuing writes", async () => {
    const page: client.NotificationPage = {
      items: [],
      total: 0,
      page: 2,
      size: 20,
    };
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(response(page))
      .mockResolvedValueOnce(response({ count: 100 }));
    vi.stubGlobal("fetch", fetcher);
    expect(await client.listNotifications(2, true)).toEqual(page);
    expect(await client.notificationCount()).toEqual({ count: 100 });
    expect(fetcher.mock.calls.map(([url]) => url)).toEqual([
      "/api/notifications?page=2&size=20&unreadOnly=true",
      "/api/notifications/unread-count",
    ]);
    expect(fetcher.mock.calls.every(([, init]) => !init.method)).toBe(true);
  });

  it.each([
    ["single", "/api/notifications/82/read", "PATCH"],
    ["all", "/api/notifications/read-all", "POST"],
  ])(
    "sends %s read with a session and fixed CSRF header and announces only success",
    async (kind, path, method) => {
      const changed = vi.fn();
      window.addEventListener(client.NOTIFICATIONS_CHANGED, changed);
      try {
        const fetcher = vi
          .fn()
          .mockResolvedValueOnce(
            response({
              headerName: "untrusted-header",
              csrfToken: "test-proof",
            }),
          )
          .mockResolvedValueOnce(response(null));
        vi.stubGlobal("fetch", fetcher);
        if (kind === "single") await client.readNotification(82);
        else await client.readAllNotifications();
        expect(fetcher.mock.calls.map(([url]) => url)).toEqual([
          "/api/auth/csrf",
          path,
        ]);
        const init = fetcher.mock.calls[1][1] as RequestInit;
        expect(init).toMatchObject({ method, credentials: "same-origin" });
        expect(init.body).toBeUndefined();
        expect(new Headers(init.headers).get("X-CSRF-TOKEN")).toBe(
          "test-proof",
        );
        expect(new Headers(init.headers).has("untrusted-header")).toBe(false);
        expect(changed).toHaveBeenCalledOnce();
      } finally {
        window.removeEventListener(client.NOTIFICATIONS_CHANGED, changed);
      }
    },
  );

  it.each([401, 403, 404, 409, 429, 503])(
    "propagates HTTP %s without announcing success or replaying a command",
    async (status) => {
      const changed = vi.fn();
      window.addEventListener(client.NOTIFICATIONS_CHANGED, changed);
      try {
        const fetcher = vi
          .fn()
          .mockResolvedValueOnce(response({ csrfToken: "test-proof" }))
          .mockResolvedValueOnce(
            new Response(
              JSON.stringify({
                success: false,
                code: "REJECTED",
                message: "요청을 처리할 수 없습니다.",
                field: null,
                requestId: "server-request",
              }),
              { status },
            ),
          );
        vi.stubGlobal("fetch", fetcher);
        await expect(client.readNotification(82)).rejects.toMatchObject({
          status,
          code: "REJECTED",
          requestId: "server-request",
        });
        expect(changed).not.toHaveBeenCalled();
        expect(fetcher).toHaveBeenCalledTimes(2);
      } finally {
        window.removeEventListener(client.NOTIFICATIONS_CHANGED, changed);
      }
    },
  );

  it("does not send a write when CSRF retrieval fails", async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValue(
        new Response(
          JSON.stringify({ success: false, message: "CSRF 요청 실패" }),
          { status: 403 },
        ),
      );
    vi.stubGlobal("fetch", fetcher);
    await expect(client.readAllNotifications()).rejects.toMatchObject({
      status: 403,
    });
    expect(fetcher).toHaveBeenCalledOnce();
    expect(fetcher.mock.calls[0][0]).toBe("/api/auth/csrf");
  });
});
