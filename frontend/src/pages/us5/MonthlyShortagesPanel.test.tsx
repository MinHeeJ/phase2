import { useState } from "react";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { MonthlyShortagesPanel } from "./MonthlyShortagesPanel";
import type { MonthlyShortageFilters, MonthlyShortagePage } from "./client";
import { monthlyShortageQuery } from "./client";

const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
let fetcher: ReturnType<typeof vi.fn>, data: MonthlyShortagePage;
let fail: boolean, exportFail: boolean;
const projects = [{ prj_id: 7, prj_nm: "비교 프로젝트" }];
function Host({
  initial = { year: 2026 },
}: {
  initial?: MonthlyShortageFilters;
}) {
  const [filters, setFilters] = useState(initial);
  return (
    <MonthlyShortagesPanel
      filters={filters}
      onFiltersChange={setFilters}
      projects={projects}
    />
  );
}
function panel(initial?: MonthlyShortageFilters) {
  return render(
    <MemoryRouter>
      <Routes>
        <Route path="/" element={<Host initial={initial} />} />
        <Route path="/UI-01" element={<div>인원 계획 상세</div>} />
      </Routes>
    </MemoryRouter>,
  );
}
const listCalls = () =>
  fetcher.mock.calls
    .map((call) => String(call[0]))
    .filter((url) => url.startsWith("/api/plan-vs-actual/shortages?"));
beforeEach(() => {
  fail = false;
  exportFail = false;
  data = {
    rows: [
      {
        prj_id: 7,
        prj_nm: "비교 프로젝트",
        job_cd: "BE",
        job_nm: "백엔드 개발",
        plan_ym: "2026-03",
        planMm: 3,
        actualMm: 2.5,
        shortageMm: 0.5,
      },
    ],
    total: 1,
    page: 0,
    size: 50,
  };
  fetcher = vi.fn(async (url: string) => {
    if (url === "/api/codes/JOB")
      return response([{ code: "BE", name: "백엔드 개발" }]);
    if (url.startsWith("/api/plan-vs-actual/shortages/export?")) {
      if (exportFail)
        return new Response(
          JSON.stringify({ success: false, message: "다운로드 실패" }),
          { status: 500 },
        );
      return new Response("workbook", {
        headers: {
          "Content-Disposition": `attachment; filename*=UTF-8''${encodeURIComponent("미충원직무_2026_20261014.xlsx")}`,
        },
      });
    }
    if (!url.startsWith("/api/plan-vs-actual/shortages?"))
      throw new Error(`Unexpected request ${url}`);
    if (fail) throw new Error("offline");
    return response(data);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
describe("monthly shortages", () => {
  it("renders six columns, danger shortage badge and two-decimal MM without gap control", async () => {
    panel();
    expect(await screen.findByText("부족 0.50")).toBeInTheDocument();
    expect(screen.getByText("3.00")).toBeInTheDocument();
    expect(screen.getByText("2.50")).toBeInTheDocument();
    expect(screen.getAllByRole("columnheader")).toHaveLength(6);
    expect(screen.queryByLabelText("차이")).not.toBeInTheDocument();
    expect(listCalls()[0]).toContain("size=50");
    expect(screen.getByText("부족 0.50").className).toContain("danger");
  });
  it("keeps shared year/project/job but never sends annual gap", () => {
    const filters = { year: 2026, projectId: 7, job: "BE", gap: "MATCH" };
    expect(monthlyShortageQuery(filters).toString()).toBe(
      "year=2026&projectId=7&job=BE",
    );
  });
  it("applies draft project and job only when querying", async () => {
    panel();
    await screen.findByText("부족 0.50");
    const count = listCalls().length;
    fireEvent.change(screen.getByLabelText("프로젝트"), {
      target: { value: "7" },
    });
    await waitFor(() =>
      expect(screen.getByLabelText("직무")).not.toBeDisabled(),
    );
    fireEvent.change(screen.getByLabelText("직무"), {
      target: { value: "BE" },
    });
    expect(listCalls()).toHaveLength(count);
    fireEvent.click(screen.getByText("조회"));
    await waitFor(() =>
      expect(listCalls().at(-1)).toContain("projectId=7&job=BE"),
    );
  });
  it("shows unfiltered empty without reset action", async () => {
    data = { ...data, rows: [], total: 0 };
    panel();
    expect(
      await screen.findByText("미충원 직무가 없습니다."),
    ).toBeInTheDocument();
    expect(screen.queryByText("조건 초기화")).not.toBeInTheDocument();
  });
  it("shows filtered empty and clears shared filters without changing year", async () => {
    data = { ...data, rows: [], total: 0 };
    panel({ year: 2025, projectId: 7, job: "BE" });
    expect(
      await screen.findByText("조건에 맞는 결과가 없습니다."),
    ).toBeInTheDocument();
    fireEvent.click(screen.getByText("조건 초기화"));
    await waitFor(() =>
      expect(listCalls().at(-1)).toBe(
        "/api/plan-vs-actual/shortages?year=2025&page=0&size=50",
      ),
    );
    expect(
      await screen.findByText("미충원 직무가 없습니다."),
    ).toBeInTheDocument();
  });
  it("shows loading and retries failed reads without stale table", async () => {
    fail = true;
    panel();
    expect(
      screen.getByLabelText("미충원 직무 불러오는 중"),
    ).toBeInTheDocument();
    expect(
      await screen.findByText("서버에 연결할 수 없습니다."),
    ).toBeInTheDocument();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
    fail = false;
    fireEvent.click(screen.getByText("다시 시도"));
    expect(await screen.findByText("부족 0.50")).toBeInTheDocument();
  });
  it("pages on server while preserving shared filters", async () => {
    data.total = 51;
    panel({ year: 2026, projectId: 7 });
    await screen.findByText("부족 0.50");
    fireEvent.click(screen.getByTestId("shortage-next"));
    await waitFor(() =>
      expect(listCalls().at(-1)).toContain("projectId=7&page=1&size=50"),
    );
  });
  it("row opens project staff-plans detail", async () => {
    panel();
    fireEvent.click(await screen.findByTestId("shortage-row-7-be-2026-03"));
    expect(await screen.findByText("인원 계획 상세")).toBeInTheDocument();
  });
  it("project link targets staff-plans with selected identity", async () => {
    panel();
    const link = await screen.findByTestId("shortage-project-7-be-2026-03");
    expect(link).toHaveAttribute("href", "/UI-01?open=7&tab=staff-plans");
  });
  it("downloads all matching rows without paging or gap and uses server filename", async () => {
    const create = vi.fn(() => "blob:download");
    const revoke = vi.fn();
    Object.defineProperty(URL, "createObjectURL", {
      configurable: true,
      value: create,
    });
    Object.defineProperty(URL, "revokeObjectURL", {
      configurable: true,
      value: revoke,
    });
    const click = vi
      .spyOn(HTMLAnchorElement.prototype, "click")
      .mockImplementation(function (this: HTMLAnchorElement) {
        expect(this.download).toBe("미충원직무_2026_20261014.xlsx");
      });
    panel({ year: 2026, projectId: 7, job: "BE" });
    await screen.findByText("부족 0.50");
    fireEvent.click(screen.getByTestId("shortage-export"));
    await waitFor(() => expect(click).toHaveBeenCalled());
    expect(fetcher.mock.calls.map((call) => call[0])).toContain(
      "/api/plan-vs-actual/shortages/export?year=2026&projectId=7&job=BE",
    );
    expect(revoke).toHaveBeenCalledWith("blob:download");
  });
  it("shows download errors without discarding list", async () => {
    exportFail = true;
    panel();
    await screen.findByText("부족 0.50");
    fireEvent.click(screen.getByTestId("shortage-export"));
    expect(await screen.findByText("다운로드 실패")).toBeInTheDocument();
    expect(screen.getByText("부족 0.50")).toBeInTheDocument();
  });
});
