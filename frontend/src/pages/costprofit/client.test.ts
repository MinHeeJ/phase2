import { afterEach, describe, expect, it, vi } from "vitest";
import * as client from "./client";
import { ApiFailure } from "../../shared/api";

const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }));
afterEach(() => vi.unstubAllGlobals());
describe("cost analysis shared API boundary", () => {
  it("preserves false and zero filters and drops only absent filters", () => {
    expect(
      client.costQuery({
        page: 0,
        size: 50,
        lossOnly: false,
        q: "a & b",
        partId: "",
        view: null,
      }),
    ).toBe("page=0&size=50&lossOnly=false&q=a+%26+b");
  });
  it("queries allocation by the caller's month and selected project with cancellation", async () => {
    const data = { month: "2026-10", status: "OPEN", items: [], warnings: [] };
    const fetcher = vi.fn().mockResolvedValue(response(data));
    vi.stubGlobal("fetch", fetcher);
    const signal = new AbortController().signal;
    expect(await client.getCostAllocation("2026-10", "42", signal)).toEqual(
      data,
    );
    expect(fetcher).toHaveBeenCalledWith(
      "/api/costs/allocation?month=2026-10&projectId=42",
      expect.objectContaining({ signal }),
    );
  });
  it("uses approved paths and carries selected years into detail and causes reads", async () => {
    const fetcher = vi
      .fn()
      .mockImplementation(() => Promise.resolve(response({ items: [] })));
    vi.stubGlobal("fetch", fetcher);
    await client.listCostPeople({
      year: 2026,
      view: "AVERAGE",
      page: 0,
      size: 20,
    });
    await client.getCostPersonBreakdown("42", 2026, "AVERAGE");
    await client.listCostProjects({ year: 2026, type: "TK", q: "검색" });
    await client.getCostProject("81", 2025);
    await client.getCostProjectCauses("81", 2025);
    await client.listPartSettlements("2026-10");
    await client.getPartSettlement("7", "2026-10", "TREND");
    await client.listCostRenegotiations({
      windowMonths: 6,
      marginBelow: 15,
      raiseRate: 0,
    });
    expect(fetcher.mock.calls.map(([url]) => url)).toEqual([
      "/api/costs/people?year=2026&view=AVERAGE&page=0&size=20",
      "/api/costs/people/42/breakdown?year=2026&view=AVERAGE",
      "/api/costs/projects?year=2026&type=TK&q=%EA%B2%80%EC%83%89",
      "/api/costs/projects/81?year=2025",
      "/api/costs/projects/81/causes?year=2025",
      "/api/settlements/parts?month=2026-10",
      "/api/settlements/parts/7?month=2026-10&view=TREND",
      "/api/costs/renegotiations?windowMonths=6&marginBelow=15&raiseRate=0",
    ]);
  });
  it("keeps card natural identity out of the request body and preserves omitted versus blank note", async () => {
    const fetcher = vi
      .fn()
      .mockImplementation(() => Promise.resolve(response({ note: "" })));
    vi.stubGlobal("fetch", fetcher);
    await client.savePartSettlementCard("7", "2026-10", { corp_card_amt: 0 });
    await client.savePartSettlementCard("7", "2026-10", {
      corp_card_amt: 0,
      note: "",
    });
    expect(fetcher.mock.calls[0][0]).toBe(
      "/api/settlements/parts/7/card?month=2026-10",
    );
    expect(fetcher.mock.calls[0][1]).toMatchObject({
      method: "PUT",
      body: '{"corp_card_amt":0}',
    });
    expect(fetcher.mock.calls[1][1]).toMatchObject({
      body: '{"corp_card_amt":0,"note":""}',
    });
  });
  it("confirmation has no client approval override and cancellation carries only reason", async () => {
    const fetcher = vi
      .fn()
      .mockImplementation(() => Promise.resolve(response({})));
    vi.stubGlobal("fetch", fetcher);
    await client.confirmPartSettlement("7", "2026-10");
    await client.cancelPartSettlement("7", "2026-10", "취소 사유");
    expect(fetcher.mock.calls[0][1]).toMatchObject({ method: "POST" });
    expect(fetcher.mock.calls[0][1].body).toBeUndefined();
    expect(JSON.parse(fetcher.mock.calls[1][1].body)).toEqual({
      reason: "취소 사유",
    });
  });
  it("simulation sends the canonical inputs without adding year or writing actual project amounts", async () => {
    const fetcher = vi.fn().mockResolvedValue(response({ simulated: true }));
    vi.stubGlobal("fetch", fetcher);
    const input = { headcount_delta: -1, monthly_cost_amt: 100, months: 2 };
    await client.simulateCostProject("81", input);
    expect(fetcher.mock.calls[0][0]).toBe("/api/costs/projects/81/simulate");
    expect(JSON.parse(fetcher.mock.calls[0][1].body)).toEqual(input);
  });
  it.each(["0", "-1", "1/2", "abc", "01", "", Number.MAX_SAFE_INTEGER + 1])(
    "rejects malformed selected identifiers: %s",
    (id) => {
      const fetcher = vi.fn();
      vi.stubGlobal("fetch", fetcher);
      expect(() => client.getCostProject(id, 2026)).toThrow(ApiFailure);
      expect(fetcher).not.toHaveBeenCalled();
    },
  );
  it("preserves feature errors, singular field and request id", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            success: false,
            code: "CLOSED_MONTH",
            message: "마감 배분자료 없음",
            field: "month",
            requestId: "receipt",
          }),
          { status: 409 },
        ),
      ),
    );
    await expect(client.getCostAllocation("2026-09")).rejects.toMatchObject({
      status: 409,
      code: "CLOSED_MONTH",
      field: "month",
      requestId: "receipt",
    });
  });
  it("downloads the full filtered population on the same GET with XLSX Accept", async () => {
    const filename = "개인원가.xlsx";
    const fetcher = vi.fn().mockResolvedValue(
      new Response(new Blob(["content"]), {
        headers: {
          "Content-Type":
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
          "Content-Disposition": `attachment; filename*=UTF-8''${encodeURIComponent(filename)}`,
        },
      }),
    );
    vi.stubGlobal("fetch", fetcher);
    const result = await client.downloadCostPeople({
      year: 2026,
      empType: "FT",
      page: 2,
      size: 20,
      sort: "dev_id,asc",
    });
    expect(fetcher.mock.calls[0][0]).toBe(
      "/api/costs/people?year=2026&empType=FT&sort=dev_id%2Casc",
    );
    expect(fetcher.mock.calls[0][1]).toMatchObject({
      headers: {
        Accept:
          "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
      },
    });
    expect(result.filename).toBe(filename);
    expect(result.blob.size).toBeGreaterThan(0);
  });
  it("does not treat JSON success as a workbook", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(response({})));
    await expect(client.downloadCostProjects({ year: 2026 })).rejects.toThrow(
      "엑셀 응답",
    );
  });
  it("reports download validation errors rather than saving an error document", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            message: "월 확인",
            field: "month",
            code: "VALIDATION_ERROR",
          }),
          { status: 400, headers: { "X-Request-Id": "receipt" } },
        ),
      ),
    );
    await expect(client.downloadPartSettlements("bad")).rejects.toMatchObject({
      status: 400,
      field: "month",
      code: "VALIDATION_ERROR",
      requestId: "receipt",
    });
  });
});
