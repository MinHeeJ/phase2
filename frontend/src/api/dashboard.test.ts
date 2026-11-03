import { afterEach, describe, expect, it, vi } from "vitest";
import * as client from "./apiClient";
import {
  getDashboard,
  downloadDashboard as screenDownloadDashboard,
} from "../pages/apiops/dashboardClient";
import {
  downloadDashboard,
  dashboardWorkbookType,
} from "../pages/dashboardops/dashboardExportClient";
import { dashboardFixture } from "../../tests/dashboard/DashboardTestSupport";

const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }));
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("dashboard API registration", () => {
  it("reuses the read and binary-export owners without creating wrappers", () => {
    expect(client.getDashboard).toBe(getDashboard);
    expect(client.downloadDashboard).toBe(downloadDashboard);
    expect(screenDownloadDashboard).toBe(downloadDashboard);
  });
  it.each([undefined, "2026-09"])(
    "reads the approved whole dashboard for month %s without authentication or writes",
    async (month) => {
      const data: client.DashboardData = dashboardFixture(month);
      data.kpis.revenue.amount = null;
      data.kpis.revenue.state = "MISSING";
      const signal = new AbortController().signal;
      const fetcher = vi.fn(async () => response(data));
      vi.stubGlobal("fetch", fetcher);
      await expect(client.getDashboard(month, signal)).resolves.toEqual(data);
      expect(fetcher).toHaveBeenCalledTimes(1);
      expect(fetcher).toHaveBeenCalledWith(
        month === undefined
          ? "/api/dashboard"
          : `/api/dashboard?month=${month}`,
        { signal, headers: {} },
      );
    },
  );
  it.each(["INVALID_MONTH", "FUTURE_MONTH"])(
    "preserves %s validation and request tracing through the read registry",
    async (code) => {
      vi.stubGlobal(
        "fetch",
        vi.fn(
          async () =>
            new Response(
              JSON.stringify({
                success: false,
                code,
                field: "month",
                message: "기준월을 확인하세요.",
                requestId: "dashboard-validation",
              }),
              { status: 400 },
            ),
        ),
      );
      await expect(client.getDashboard("2026-13")).rejects.toMatchObject({
        status: 400,
        code,
        field: "month",
        requestId: "dashboard-validation",
      });
    },
  );
  it("preserves read failures instead of returning an empty dashboard", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => {
        throw new TypeError("offline");
      }),
    );
    await expect(client.getDashboard()).rejects.toBeInstanceOf(
      client.ApiFailure,
    );
  });
  it("uses the binary export owner with the selected month and cancellation signal", async () => {
    vi.stubGlobal(
      "URL",
      class extends URL {
        static createObjectURL() {
          return "blob:dashboard-test";
        }
        static revokeObjectURL() {}
      },
    );
    const create = vi.spyOn(URL, "createObjectURL");
    const revoke = vi.spyOn(URL, "revokeObjectURL");
    const click = vi
      .spyOn(HTMLAnchorElement.prototype, "click")
      .mockImplementation(() => {});
    const fetcher = vi.fn(
      async () =>
        new Response(new Uint8Array([0x50, 0x4b, 3, 4]), {
          headers: { "Content-Type": dashboardWorkbookType },
        }),
    );
    vi.stubGlobal("fetch", fetcher);
    const signal = new AbortController().signal;
    await client.downloadDashboard("2026-09", signal);
    expect(fetcher).toHaveBeenCalledTimes(1);
    expect(fetcher).toHaveBeenCalledWith(
      "/api/dashboard/export?month=2026-09",
      {
        method: "GET",
        headers: { Accept: dashboardWorkbookType },
        signal,
      },
    );
    expect(create).toHaveBeenCalledTimes(1);
    expect(click).toHaveBeenCalledTimes(1);
    expect(click.mock.instances[0]).toHaveProperty(
      "download",
      "dashboard_2026-09.xlsx",
    );
    expect(revoke).toHaveBeenCalledWith("blob:dashboard-test");
  });
  it.each(["INVALID_MONTH", "FUTURE_MONTH"])(
    "preserves export %s errors without downloading",
    async (code) => {
      const click = vi
        .spyOn(HTMLAnchorElement.prototype, "click")
        .mockImplementation(() => {});
      vi.stubGlobal(
        "fetch",
        vi.fn(
          async () =>
            new Response(
              JSON.stringify({
                success: false,
                code,
                field: "month",
                message: "기준월을 확인하세요.",
                requestId: "dashboard-export-validation",
              }),
              { status: 400 },
            ),
        ),
      );
      await expect(client.downloadDashboard("2026-13")).rejects.toMatchObject({
        status: 400,
        code,
        field: "month",
        requestId: "dashboard-export-validation",
      });
      expect(click).not.toHaveBeenCalled();
    },
  );
  it("rejects HTML from the registered export instead of treating it as a file", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(
        async () =>
          new Response("<html>unavailable</html>", {
            headers: {
              "Content-Type": "text/html",
              "X-Request-Id": "dashboard-export-error",
            },
          }),
      ),
    );
    await expect(client.downloadDashboard("2026-09")).rejects.toMatchObject({
      message: "엑셀 파일 응답이 아닙니다.",
      requestId: "dashboard-export-error",
    });
  });
});
