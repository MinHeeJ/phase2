import { Blob as NodeBlob } from "node:buffer";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiFailure } from "../../shared/api";
import { response } from "../developers/DeveloperTestSupport";
import * as people from "../people/client";
import * as projects from "../projects/costClient";
import * as renegotiations from "./client";
import UI03Screen from "../people/UI03Screen";
import UI04Screen from "../projects/UI04Screen";
import RenegotiationPanel from "./UI32Screen";
import { costWorkbookType } from "./costWorkbook";

const cases = [
  {
    resource: "people",
    filename: "개인원가수익.xlsx",
    run: () =>
      people.downloadCostPeople({
        ...people.defaultPeopleFilters(),
        year: 2026,
        partId: "42",
        page: 1,
      }),
  },
  {
    resource: "projects",
    filename: "프로젝트원가분석.xlsx",
    run: () =>
      projects.downloadCostProjects({
        ...projects.costProjectDefaults(),
        year: 2026,
        partId: "42",
        page: 1,
      }),
  },
  {
    resource: "renegotiations",
    filename: "단가재협상.xlsx",
    run: () =>
      renegotiations.downloadCostRenegotiations({
        ...renegotiations.renegotiationDefaults,
        projectId: "42",
        page: 1,
      }),
  },
];
let fetcher: ReturnType<typeof vi.fn>;
let createUrl: ReturnType<typeof vi.fn>;
let revokeUrl: ReturnType<typeof vi.fn>;
let saved: string[];

beforeEach(() => {
  fetcher = vi.fn();
  vi.stubGlobal("fetch", fetcher);
  createUrl = vi.fn(() => "blob:cost-workbook");
  revokeUrl = vi.fn();
  vi.stubGlobal(
    "URL",
    Object.assign(URL, {
      createObjectURL: createUrl,
      revokeObjectURL: revokeUrl,
    }),
  );
  saved = [];
  vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(function (
    this: HTMLAnchorElement,
  ) {
    saved.push(this.download);
  });
});
afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

function workbookResponse(bytes: number[]) {
  return {
    ok: true,
    status: 200,
    headers: new Headers({
      "Content-Type": costWorkbookType,
      "X-Request-Id": "download-check",
    }),
    blob: async () => new NodeBlob([new Uint8Array(bytes)]),
  };
}

describe.each(cases)(
  "$resource download transport",
  ({ resource, filename, run }) => {
    it("requests XLSX with the applied scope and same-origin credentials, then releases the URL", async () => {
      fetcher.mockResolvedValue(workbookResponse([0x50, 0x4b, 3, 4]));
      await run();
      expect(fetcher).toHaveBeenCalledWith(
        expect.stringContaining(`/api/costs/${resource}?`),
        {
          method: "GET",
          credentials: "same-origin",
          cache: "no-store",
          headers: { Accept: costWorkbookType },
        },
      );
      const query = new URLSearchParams(
        String(fetcher.mock.calls[0][0]).split("?")[1],
      );
      expect(
        query.get(resource === "renegotiations" ? "projectId" : "partId"),
      ).toBe("42");
      expect(query.get("page")).toBe("1");
      expect(saved).toEqual([filename]);
      expect(revokeUrl).toHaveBeenCalledWith("blob:cost-workbook");
    });

    it.each([
      { bytes: [] },
      { bytes: [0x50, 0x4b] },
      { bytes: [0x50, 0x4b, 5, 6] },
      { bytes: [60, 104, 116, 109] },
    ])(
      "rejects truncated, empty or renamed non-workbook bytes $bytes",
      async ({ bytes }) => {
        fetcher.mockResolvedValue(workbookResponse(bytes));
        await expect(run()).rejects.toMatchObject({
          message: "엑셀 파일이 손상되었습니다.",
          requestId: "download-check",
        });
        expect(createUrl).not.toHaveBeenCalled();
        expect(saved).toEqual([]);
      },
    );

    it("rejects a JSON success body instead of saving it as xlsx", async () => {
      fetcher.mockResolvedValue(
        new Response('{"success":true}', {
          headers: { "Content-Type": "application/json" },
        }),
      );
      await expect(run()).rejects.toBeInstanceOf(ApiFailure);
      expect(createUrl).not.toHaveBeenCalled();
    });

    it.each([400, 403, 404, 409, 503])(
      "keeps HTTP %i and the singular field and request ID",
      async (status) => {
        fetcher.mockResolvedValue(
          new Response(
            JSON.stringify({
              success: false,
              message: "자료 확인 필요",
              field: "year",
              code: "CHECK",
              requestId: "server-check",
            }),
            { status },
          ),
        );
        await expect(run()).rejects.toMatchObject({
          status,
          field: "year",
          code: "CHECK",
          requestId: "server-check",
        });
        expect(saved).toEqual([]);
      },
    );

    it.each(["<html>proxy failure</html>", "null", "[]"])(
      "handles non-envelope failures without losing HTTP status: %s",
      async (body) => {
        fetcher.mockResolvedValue(
          new Response(body, {
            status: 502,
            headers: { "X-Request-Id": "proxy-check" },
          }),
        );
        await expect(run()).rejects.toMatchObject({
          status: 502,
          requestId: "proxy-check",
          message: "엑셀 다운로드에 실패했습니다.",
        });
        expect(saved).toEqual([]);
      },
    );

    it("reports a network failure without saving anything", async () => {
      fetcher.mockRejectedValue(new TypeError("offline"));
      await expect(run()).rejects.toMatchObject({
        status: null,
        message: "서버에 연결할 수 없습니다.",
      });
      expect(saved).toEqual([]);
    });

    it("releases the object URL even when the browser save fails", async () => {
      fetcher.mockResolvedValue(workbookResponse([0x50, 0x4b, 3, 4]));
      vi.mocked(HTMLAnchorElement.prototype.click).mockImplementation(() => {
        throw new Error("save failed");
      });
      await expect(run()).rejects.toThrow("save failed");
      expect(revokeUrl).toHaveBeenCalledWith("blob:cost-workbook");
    });
  },
);

const metadata = {
  calculation_month: "2026-10",
  reference_month: "2026-10",
  closed: false,
  unit: "원",
  warnings: [],
  items: [],
  total: 0,
  page: 0,
  size: 50 as const,
};
function mountScreen(resource: string) {
  vi.spyOn(people, "listCostPeople").mockResolvedValue({
    ...metadata,
    summary: {
      psn_cnt: 0,
      avg_working_psn_cnt: null,
      avg_earning_psn_cnt: null,
      avg_price_amt: null,
      avg_cost_amt: null,
      avg_profit_amt: null,
      loss_psn_cnt: 0,
      utilization_rt: null,
      earning_rt: null,
      contributions: [],
    },
  });
  vi.spyOn(projects, "listCostProjects").mockResolvedValue({
    ...metadata,
    summary: {
      confirmed_supply_amt: 0,
      profit_amt: 0,
      low_margin_count: 0,
      budget_over_count: 0,
      rnd_cost_amt: 0,
    },
  });
  vi.spyOn(renegotiations, "listCostRenegotiations").mockResolvedValue({
    ...metadata,
    target_margin_rate: 20,
    raise_rate: 5,
    summary: {
      psn_cnt: 0,
      weighted_margin_rt: null,
      assumed_annual_profit_increase_amt: null,
      target_annual_profit_increase_amt: null,
    },
  });
  fetcher.mockImplementation(async () => response([]));
  const download =
    resource === "people"
      ? vi.spyOn(people, "downloadCostPeople")
      : resource === "projects"
        ? vi.spyOn(projects, "downloadCostProjects")
        : vi.spyOn(renegotiations, "downloadCostRenegotiations");
  render(
    <MemoryRouter
      initialEntries={[resource === "people" ? "/UI-03" : "/UI-04"]}
    >
      {resource === "people" ? (
        <UI03Screen />
      ) : resource === "projects" ? (
        <UI04Screen />
      ) : (
        <RenegotiationPanel />
      )}
    </MemoryRouter>,
  );
  return download;
}

describe.each(cases)("$resource existing screen", ({ resource }) => {
  it("shows a failed download and retries without replacing the empty list", async () => {
    const download = mountScreen(resource);
    download.mockRejectedValueOnce(
      new ApiFailure("파일 생성 실패", 503, "screen-check"),
    );
    await screen.findByText(/조건에 맞는 결과가 없습니다/);
    fireEvent.click(
      screen.getByRole("button", { name: /(?:Excel|엑셀) 다운로드/ }),
    );
    await screen.findByText("파일 생성 실패");
    expect(screen.getByText(/screen-check/)).toBeInTheDocument();
    download.mockResolvedValueOnce(undefined);
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    await waitFor(() => expect(download).toHaveBeenCalledTimes(2));
    await waitFor(() =>
      expect(screen.queryByText("파일 생성 실패")).not.toBeInTheDocument(),
    );
    expect(screen.getByText(/조건에 맞는 결과가 없습니다/)).toBeInTheDocument();
  });
});
