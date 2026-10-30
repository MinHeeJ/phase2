import { afterEach, describe, expect, it, vi } from "vitest";
import * as client from "./apiClient";
import * as people from "../pages/people/client";
import * as projects from "../pages/projects/costClient";
import * as parts from "../pages/parts/client";
import * as costs from "../pages/costsops/client";

const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }));
const peopleFilters = () => ({ ...people.defaultPeopleFilters(), year: 2026 });
const projectFilters = () => ({
  ...projects.costProjectDefaults(),
  year: 2026,
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
describe("cost analysis API registration", () => {
  it.each([
    ["listCostPeople", people.listCostPeople],
    ["getCostPersonBreakdown", people.getCostPersonBreakdown],
    ["downloadCostPeople", people.downloadCostPeople],
    ["listCostProjects", projects.listCostProjects],
    ["getCostProject", projects.getCostProject],
    ["getCostProjectCauses", projects.getCostProjectCauses],
    ["simulateCostProject", projects.simulateCostProject],
    ["downloadCostProjects", projects.downloadCostProjects],
    ["listPartSettlements", parts.listPartSettlements],
    ["getPartSettlement", parts.getPartSettlement],
    ["updatePartSettlementCard", parts.updatePartSettlementCard],
    ["confirmPartSettlement", parts.confirmPartSettlement],
    ["cancelPartSettlement", parts.cancelPartSettlement],
    ["downloadPartSettlements", parts.downloadPartSettlements],
    ["listCostRenegotiations", costs.listCostRenegotiations],
    ["getCostAllocation", costs.getCostAllocation],
    ["downloadCostRenegotiations", costs.downloadCostRenegotiations],
  ] as const)(
    "exposes %s directly from its owner without a wrapper",
    (name, implementation) => {
      expect(client[name]).toBe(implementation);
    },
  );
  it.each([
    [
      "listCostPeople",
      () => client.listCostPeople(peopleFilters()),
      "/api/costs/people",
      { year: "2026", view: "TOTAL", page: "0", size: "50" },
    ],
    [
      "getCostPersonBreakdown",
      () => client.getCostPersonBreakdown(37, { year: 2026, view: "AVERAGE" }),
      "/api/costs/people/37/breakdown",
      { year: "2026", view: "AVERAGE" },
    ],
    [
      "listCostProjects",
      () => client.listCostProjects(projectFilters()),
      "/api/costs/projects",
      { year: "2026", page: "0", size: "50" },
    ],
    [
      "getCostProject",
      () => client.getCostProject(73, 2026),
      "/api/costs/projects/73",
      { year: "2026" },
    ],
    [
      "getCostProjectCauses",
      () => client.getCostProjectCauses(73, 2026),
      "/api/costs/projects/73/causes",
      { year: "2026" },
    ],
    [
      "listPartSettlements",
      () => client.listPartSettlements("2026-10"),
      "/api/settlements/parts",
      { month: "2026-10" },
    ],
    [
      "getPartSettlement",
      () => client.getPartSettlement(7, "2026-10", "TREND"),
      "/api/settlements/parts/7",
      { month: "2026-10", view: "TREND" },
    ],
    [
      "listCostRenegotiations",
      () =>
        client.listCostRenegotiations({
          ...costs.renegotiationDefaults,
          projectId: "73",
        }),
      "/api/costs/renegotiations",
      { projectId: "73", windowMonths: "6", marginBelow: "15" },
    ],
    [
      "getCostAllocation",
      () => client.getCostAllocation("2026-10", 73),
      "/api/costs/allocation",
      { month: "2026-10", projectId: "73" },
    ],
  ] as const)(
    "calls the approved read path for %s",
    async (_name, call, path, query) => {
      const payload = {
        items: [],
        warnings: ["마감 배분자료 없음"],
        closed: true,
      };
      const fetcher = vi.fn().mockResolvedValue(response(payload));
      vi.stubGlobal("fetch", fetcher);
      expect(await call()).toEqual(payload);
      expect(fetcher).toHaveBeenCalledOnce();
      const [url, init] = fetcher.mock.calls[0];
      expect(url.split("?")[0]).toBe(path);
      expect(
        Object.fromEntries(new URLSearchParams(url.split("?")[1])),
      ).toMatchObject(query);
      expect(init.method ?? "GET").toBe("GET");
      expect(init.body).toBeUndefined();
      if (
        [
          "/api/costs/people",
          "/api/costs/projects",
          "/api/settlements/parts",
          "/api/costs/renegotiations",
        ].includes(path)
      ) {
        expect(init.headers.Accept).toBe("application/json");
      }
    },
  );
  it.each([
    [
      "simulateCostProject",
      () =>
        client.simulateCostProject(73, {
          headcount_delta: -1,
          monthly_cost_amt: 100,
          months: 2,
          monthly_price_amt: 200,
        }),
      "/api/costs/projects/73/simulate",
      "POST",
      {
        headcount_delta: -1,
        monthly_cost_amt: 100,
        months: 2,
        monthly_price_amt: 200,
      },
    ],
    [
      "updatePartSettlementCard",
      () =>
        client.updatePartSettlementCard(7, "2026-10", {
          corp_card_amt: 0,
          rmrk_desc: "카드 비고",
        }),
      "/api/settlements/parts/7/card?month=2026-10",
      "PUT",
      { corp_card_amt: 0, rmrk_desc: "카드 비고" },
    ],
    [
      "confirmPartSettlement",
      () => client.confirmPartSettlement(7, "2026-10"),
      "/api/settlements/parts/7/confirm?month=2026-10",
      "POST",
      undefined,
    ],
    [
      "cancelPartSettlement",
      () => client.cancelPartSettlement(7, "2026-10", "재검토"),
      "/api/settlements/parts/7/cancel?month=2026-10",
      "POST",
      { reason: "재검토" },
    ],
  ] as const)(
    "preserves the resource identity and request body for %s",
    async (_name, call, path, method, body) => {
      const payload = { simulated: true, warnings: [] };
      const fetcher = vi.fn().mockResolvedValue(response(payload));
      vi.stubGlobal("fetch", fetcher);
      expect(await call()).toEqual(payload);
      expect(fetcher).toHaveBeenCalledOnce();
      expect(fetcher.mock.calls[0][0]).toBe(path);
      expect(fetcher.mock.calls[0][1]).toMatchObject({ method });
      expect(fetcher.mock.calls[0][1].body).toBe(
        body === undefined ? undefined : JSON.stringify(body),
      );
    },
  );
  it.each([
    [400, "VALIDATION_ERROR", "corp_card_amt"],
    [404, "NOT_FOUND", null],
    [409, "CLOSED_MONTH", "month"],
    [409, "PART_ALREADY_CONFIRMED", null],
    [409, "STALE_SETTLEMENT", null],
    [409, "PART_STANDARD_MISSING", null],
  ] as const)(
    "preserves %s %s without retrying a command",
    async (status, code, field) => {
      const fetcher = vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            success: false,
            meta: {},
            message: "정산 처리 불가",
            code,
            field,
            requestId: "settlement-request",
          }),
          { status },
        ),
      );
      vi.stubGlobal("fetch", fetcher);
      await expect(
        client.confirmPartSettlement(7, "2026-10"),
      ).rejects.toMatchObject({
        status,
        code,
        field,
        requestId: "settlement-request",
      });
      expect(fetcher).toHaveBeenCalledOnce();
    },
  );
  it.each([
    [() => client.downloadCostPeople(peopleFilters()), "/api/costs/people"],
    [
      () => client.downloadCostProjects(projectFilters()),
      "/api/costs/projects",
    ],
    [() => client.downloadPartSettlements("2026-10"), "/api/settlements/parts"],
    [
      () => client.downloadCostRenegotiations(costs.renegotiationDefaults),
      "/api/costs/renegotiations",
    ],
  ] as const)(
    "negotiates XLSX on the existing list path",
    async (call, path) => {
      const fetcher = vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            success: false,
            meta: {},
            code: "EXPORT_UNAVAILABLE",
            message: "다운로드 실패",
            field: null,
            requestId: "download-request",
          }),
          { status: 500 },
        ),
      );
      vi.stubGlobal("fetch", fetcher);
      await expect(call()).rejects.toMatchObject({
        code: "EXPORT_UNAVAILABLE",
        requestId: "download-request",
      });
      expect(fetcher.mock.calls[0][0].split("?")[0]).toBe(path);
      expect(fetcher.mock.calls[0][1].headers.Accept).toBe(
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
      );
      expect(fetcher).toHaveBeenCalledOnce();
    },
  );
});
