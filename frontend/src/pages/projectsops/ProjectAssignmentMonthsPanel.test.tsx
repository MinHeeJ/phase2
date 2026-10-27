import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  ProjectAssignmentMonthsPanel,
  ProjectAssignmentMonthTabs,
} from "./ProjectAssignmentMonthsPanel";
import type { ProjectAssignmentMonthsResult } from "./assignmentMonthsClient";

const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
let fetcher: ReturnType<typeof vi.fn>;
let data: ProjectAssignmentMonthsResult;
const months = () =>
  Array.from({ length: 12 }, (_, i) => ({
    work_ym: `2026-${String(i + 1).padStart(2, "0")}`,
    mm_qty: 0.5,
  }));
beforeEach(() => {
  data = {
    prj_id: 8,
    year: 2026,
    items: [1, 2, 3].map((id) => ({
      asgn_id: id,
      dev_id: id,
      dev_nm: `개발자 ${id}`,
      months: months(),
      total_mm_qty: 6,
    })),
    month_totals: months().map((cell) => ({ ...cell, mm_qty: 1.5 })),
  };
  fetcher = vi.fn(async () => response(data));
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
const tabs = (projectId: number | null = 8, year = 2026) => (
  <ProjectAssignmentMonthTabs projectId={projectId} year={year}>
    <p>기존 투입 목록</p>
  </ProjectAssignmentMonthTabs>
);

describe("SCR-UI-12#project-months", () => {
  it("does not show tabs or request annual MM without a selected project", () => {
    render(tabs(null));
    expect(screen.queryByRole("tablist")).not.toBeInTheDocument();
    expect(screen.getByText("기존 투입 목록")).toBeVisible();
    expect(fetcher).not.toHaveBeenCalled();
  });
  it("opens monthly table only from selected project tab and restores list without a write", async () => {
    render(tabs());
    expect(screen.getByRole("tab", { name: "투입 목록" })).toHaveAttribute(
      "aria-selected",
      "true",
    );
    expect(fetcher).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("tab", { name: "월별 MM" }));
    expect(await screen.findByRole("table")).toBeVisible();
    expect(fetcher.mock.calls[0][0]).toBe(
      "/api/projects/8/assignment-months?year=2026",
    );
    expect(screen.getByRole("tab", { name: "월별 MM" })).toHaveAttribute(
      "aria-selected",
      "true",
    );
    fireEvent.click(screen.getByRole("tab", { name: "투입 목록" }));
    expect(screen.getByText("기존 투입 목록")).toBeVisible();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
    expect(fetcher).toHaveBeenCalledTimes(1);
    expect(
      fetcher.mock.calls.every(
        (call) => !call[1]?.method || call[1].method === "GET",
      ),
    ).toBe(true);
  });
  it("shows three assignment rows, twelve readonly months, row sums and footer totals", async () => {
    render(<ProjectAssignmentMonthsPanel projectId={8} year={2026} />);
    const table = await screen.findByRole("table");
    expect(within(table).getAllByRole("columnheader")).toHaveLength(14);
    for (let i = 1; i <= 12; i++)
      expect(
        within(table).getByRole("columnheader", { name: `${i}월` }),
      ).toBeVisible();
    for (let i = 1; i <= 3; i++) {
      const row = screen.getByTestId(`project-months-row-${i}`);
      expect(within(row).getAllByRole("cell")).toHaveLength(13);
      expect(within(row).getAllByText("0.50")).toHaveLength(12);
      expect(within(row).getByText("6.00")).toBeVisible();
    }
    const totals = screen.getByTestId("project-months-totals");
    expect(within(totals).getAllByText("1.50")).toHaveLength(12);
    expect(within(totals).getByText("18.00")).toBeVisible();
    expect(screen.getByText("읽기 전용")).toBeVisible();
    expect(screen.queryByRole("textbox")).not.toBeInTheDocument();
    expect(screen.queryByRole("spinbutton")).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: /저장|일괄|배치/ }),
    ).not.toBeInTheDocument();
  });
  it("shows a loading state rather than an empty result", () => {
    fetcher.mockImplementation(() => new Promise(() => {}));
    render(<ProjectAssignmentMonthsPanel projectId={8} year={2026} />);
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
    expect(screen.queryByText("조회 결과가 없습니다.")).not.toBeInTheDocument();
    expect(screen.getByTestId("project-months-panel").textContent).toContain(
      "불러오는 중",
    );
  });
  it("shows empty state without fake rows", async () => {
    data.items = [];
    render(<ProjectAssignmentMonthsPanel projectId={8} year={2026} />);
    expect(await screen.findByText("조회 결과가 없습니다.")).toBeVisible();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
  });
  it("shows network failure and retries, never disguising it as an empty result", async () => {
    fetcher.mockRejectedValueOnce(new Error("offline"));
    render(<ProjectAssignmentMonthsPanel projectId={8} year={2026} />);
    expect(await screen.findByText("서버에 연결할 수 없습니다.")).toBeVisible();
    expect(screen.queryByText("조회 결과가 없습니다.")).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    expect(await screen.findByRole("table")).toBeVisible();
  });
  it("keeps API field validation visible rather than showing permission denial", async () => {
    fetcher.mockResolvedValueOnce(
      new Response(
        JSON.stringify({
          success: false,
          meta: {},
          code: "VALIDATION_ERROR",
          field: "year",
          message: "연도를 입력하세요.",
          requestId: "annual-year",
        }),
        { status: 400 },
      ),
    );
    render(<ProjectAssignmentMonthsPanel projectId={8} year={2026} />);
    expect(await screen.findByText("연도를 입력하세요.")).toBeVisible();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
    expect(
      screen.queryByText(/권한이 없습니다|로그인/),
    ).not.toBeInTheDocument();
  });
  it("removes the matrix and tabs immediately when project is cleared", async () => {
    const view = render(tabs());
    fireEvent.click(screen.getByRole("tab", { name: "월별 MM" }));
    await screen.findByRole("table");
    view.rerender(tabs(null));
    expect(screen.queryByRole("tablist")).not.toBeInTheDocument();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
    expect(screen.getByText("기존 투입 목록")).toBeVisible();
  });
  it("resets to the original list when selected project changes", async () => {
    const view = render(tabs());
    fireEvent.click(screen.getByRole("tab", { name: "월별 MM" }));
    await screen.findByRole("table");
    view.rerender(tabs(9));
    expect(screen.getByRole("tab", { name: "투입 목록" })).toHaveAttribute(
      "aria-selected",
      "true",
    );
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("tab", { name: "월별 MM" }));
    await screen.findByRole("table");
    expect(fetcher.mock.calls[1][0]).toBe(
      "/api/projects/9/assignment-months?year=2026",
    );
  });
  it("requeries selected year and never displays the prior year while loading", async () => {
    const view = render(
      <ProjectAssignmentMonthsPanel projectId={8} year={2026} />,
    );
    await screen.findByRole("table");
    fetcher.mockImplementation(() => new Promise(() => {}));
    view.rerender(<ProjectAssignmentMonthsPanel projectId={8} year={2025} />);
    await waitFor(() =>
      expect(fetcher.mock.calls[1][0]).toBe(
        "/api/projects/8/assignment-months?year=2025",
      ),
    );
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
    expect(screen.queryByText("2026년 월별 MM")).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: /저장|일괄|배치/ }),
    ).not.toBeInTheDocument();
  });
});
