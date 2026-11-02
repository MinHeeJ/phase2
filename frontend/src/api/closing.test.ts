import { afterEach, describe, expect, it, vi } from "vitest";
import * as client from "./apiClient";
import { getClosing } from "../pages/apiops/closingClient";
import * as commands from "../pages/closingops/client";
import * as adjustments from "../pages/adjustments/client";

const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }));
afterEach(() => vi.unstubAllGlobals());
describe("monthly closing API registration", () => {
  it.each([
    ["getClosing", getClosing],
    ["previewClosing", commands.previewClosing],
    ["listClosingHistory", commands.listClosingHistory],
    ["runClosingCheck", commands.runClosingCheck],
    ["confirmClosing", commands.confirmClosing],
    ["reopenClosing", commands.reopenClosing],
    ["listClosingAdjustments", adjustments.listClosingAdjustments],
    ["createClosingAdjustment", adjustments.createClosingAdjustment],
    ["cancelClosingAdjustment", adjustments.cancelClosingAdjustment],
  ] as const)("reuses the feature-owned %s export", (name, operation) => {
    expect(client[name]).toBe(operation);
  });
  it("preserves read paths and zero-based pagination without issuing POSTs", async () => {
    const fetcher = vi.fn(async (_url: string, _init: RequestInit) =>
      response({ items: [], page: 0, size: 20, total: 0 }),
    );
    vi.stubGlobal("fetch", fetcher);
    await client.getClosing("2026-10");
    await client.previewClosing("2026-10");
    await client.listClosingHistory(2026, 0, 20);
    await client.listClosingAdjustments({
      year: 2026,
      month: "2026-10",
      page: 0,
      size: 20,
    });
    expect(fetcher.mock.calls.map(([url]) => url)).toEqual([
      "/api/closing?month=2026-10",
      "/api/closing/preview?month=2026-10",
      "/api/closing/history?year=2026&page=0&size=20",
      "/api/closing/adjustments?year=2026&page=0&size=20&month=2026-10",
    ]);
    expect(
      fetcher.mock.calls.every(
        ([, init]) => !init.method || init.method === "GET",
      ),
    ).toBe(true);
  });
  it("preserves command methods, request fields and selected adjustment identity", async () => {
    const fetcher = vi.fn(async (_url: string, _init?: RequestInit) =>
      response({}),
    );
    vi.stubGlobal("fetch", fetcher);
    const input: client.AdjustmentInput = {
      originalMonth: "2026-09",
      applyMonth: "2026-10",
      type: "COST",
      amount: -100,
      reason: "비용 정정",
      originalAdjustmentId: 72,
    };
    await client.runClosingCheck("2026-10");
    await client.confirmClosing("2026-10", true);
    await client.reopenClosing("2026-10", "원천 정정");
    await client.createClosingAdjustment(input);
    const selected: Pick<client.AdjustmentRow, "id"> = { id: 73 };
    await client.cancelClosingAdjustment(selected.id, "중복 입력");
    expect(
      fetcher.mock.calls.map(([url, init]) => [url, init?.method, init?.body]),
    ).toEqual([
      ["/api/closing/check?month=2026-10", "POST", undefined],
      [
        "/api/closing/confirm",
        "POST",
        JSON.stringify({ month: "2026-10", ackWarnings: true }),
      ],
      [
        "/api/closing/reopen",
        "POST",
        JSON.stringify({ month: "2026-10", reason: "원천 정정" }),
      ],
      ["/api/closing/adjustments", "POST", JSON.stringify(input)],
      [
        `/api/closing/adjustments/${selected.id}/cancel`,
        "POST",
        JSON.stringify({ reason: "중복 입력" }),
      ],
    ]);
  });
  it("preserves business conflict errors through the registry", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(
        async () =>
          new Response(
            JSON.stringify({
              success: false,
              code: "CLOSED_MONTH",
              message: "마감된 월입니다.",
              requestId: "closing-conflict",
            }),
            { status: 409 },
          ),
      ),
    );
    await expect(client.confirmClosing("2026-10", false)).rejects.toMatchObject(
      { status: 409, code: "CLOSED_MONTH", requestId: "closing-conflict" },
    );
  });
});
