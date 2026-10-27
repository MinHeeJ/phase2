import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import { UI12ProjectMonthsPanel } from "./UI12ProjectMonthsPanel";
import type { ProjectAssignmentMonthsResult } from "./client";

const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
let data: ProjectAssignmentMonthsResult;
let fetcher: ReturnType<typeof vi.fn>;
beforeEach(() => {
  data = {
    prj_id: 31,
    year: 2026,
    items: Array.from({ length: 3 }, (_, index) => ({
      asgn_id: index + 71,
      dev_id: index + 81,
      dev_nm: `개발자 ${index + 1}`,
      months: Array.from({ length: 12 }, (_, month) => ({
        work_ym: `2026-${String(month + 1).padStart(2, "0")}`,
        mm_qty: 0.5,
      })),
      total_mm_qty: 6,
    })),
    month_totals: Array.from({ length: 12 }, (_, month) => ({
      work_ym: `2026-${String(month + 1).padStart(2, "0")}`,
      mm_qty: 1.5,
    })),
  };
  fetcher = vi.fn(async () => response(data));
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
const panel = (projectId: number | null = 31) =>
  render(
    <UI12ProjectMonthsPanel
      projectId={projectId}
      year={2026}
      list={<p>투입 목록 내용</p>}
    />,
  );
const open = () =>
  fireEvent.click(screen.getByRole("tab", { name: "월별 MM" }));

describe("project annual assignment months", () => {
  it("does not show tabs or fetch project months until one project is selected", () => {
    panel(null);
    expect(screen.queryByRole("tab")).not.toBeInTheDocument();
    expect(screen.getByText("투입 목록 내용")).toBeVisible();
    expect(fetcher).not.toHaveBeenCalled();
  });
  it("shows three assignment rows with twelve readonly months plus API row/month totals", async () => {
    panel();
    open();
    const rows = await screen.findAllByTestId(/^project-months-row-/);
    expect(rows).toHaveLength(3);
    for (const row of rows) {
      expect(within(row).getAllByRole("cell")).toHaveLength(14);
      expect(within(row).getAllByText("0.50")).toHaveLength(12);
      expect(within(row).getByText("6.00")).toBeVisible();
    }
    expect(
      within(screen.getByTestId("project-months-total-row")).getAllByText(
        "1.50",
      ),
    ).toHaveLength(12);
    expect(screen.queryByRole("textbox")).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: /저장|일괄|배치/ }),
    ).not.toBeInTheDocument();
    expect(fetcher).toHaveBeenCalledWith(
      "/api/projects/31/assignment-months?year=2026",
      expect.anything(),
    );
  });
  it("returns to same selected project list without mutations", async () => {
    panel();
    open();
    await screen.findByText("읽기 전용");
    fireEvent.click(screen.getByRole("tab", { name: "투입 목록" }));
    expect(screen.getByText("투입 목록 내용")).toBeVisible();
    expect(
      fetcher.mock.calls.every(
        (call) => !call[1]?.method || call[1].method === "GET",
      ),
    ).toBe(true);
  });
  it("renders empty state", async () => {
    data.items = [];
    panel();
    open();
    await screen.findByText("조건에 맞는 결과가 없습니다");
    expect(
      screen.queryByTestId(/^project-months-row-/),
    ).not.toBeInTheDocument();
  });
  it("renders loading without fabricated rows or totals", () => {
    fetcher.mockImplementation(() => new Promise(() => {}));
    panel();
    open();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
    expect(screen.queryByText("1.50")).not.toBeInTheDocument();
  });
  it("shows failed request and supports read-only retry without permission denial", async () => {
    fetcher.mockRejectedValueOnce(new Error("offline"));
    panel();
    open();
    await screen.findByRole("alert");
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    await screen.findByText("읽기 전용");
    expect(screen.queryByText(/권한이 없습니다/)).not.toBeInTheDocument();
  });
  it("changing project resets to list and never keeps a previous project's month rows", async () => {
    const view = panel();
    open();
    await screen.findAllByTestId(/^project-months-row-/);
    view.rerender(
      <UI12ProjectMonthsPanel
        projectId={32}
        year={2026}
        list={<p>다음 프로젝트 목록</p>}
      />,
    );
    expect(screen.getByText("다음 프로젝트 목록")).toBeVisible();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(1));
  });
});
