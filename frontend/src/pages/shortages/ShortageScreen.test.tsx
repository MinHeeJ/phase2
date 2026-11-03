import { useState } from "react";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter } from "react-router-dom";
import { MonthlyShortagesPanel } from "../us5/MonthlyShortagesPanel";
import type { MonthlyShortageFilters } from "../us5/client";

const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    headers: { "Content-Type": "application/json" },
  });
const row = {
  prj_id: 73,
  prj_nm: "조회 프로젝트",
  job_cd: "BE",
  job_nm: "백엔드 개발",
  plan_ym: "2027-03",
  planMm: 1,
  actualMm: 0.5,
  shortageMm: 0.5,
};
let fetcher: ReturnType<typeof vi.fn>;
let fieldError: boolean;
let total: number;
function Host({ year = 2027 }: { year?: number }) {
  const [filters, setFilters] = useState<MonthlyShortageFilters>({ year });
  return (
    <MonthlyShortagesPanel
      filters={{ ...filters, year }}
      onFiltersChange={setFilters}
      projects={[{ prj_id: 73, prj_nm: "조회 프로젝트" }]}
    />
  );
}
const listUrls = () =>
  fetcher.mock.calls
    .map((call) => String(call[0]))
    .filter((url) => url.startsWith("/api/plan-vs-actual/shortages?"));
beforeEach(() => {
  fieldError = false;
  total = 1;
  fetcher = vi.fn(async (url: string) => {
    if (url === "/api/codes/JOB")
      return response([{ code: "BE", name: "백엔드 개발" }]);
    if (fieldError)
      return new Response(
        JSON.stringify({
          success: false,
          meta: {},
          code: "VALIDATION_ERROR",
          message: "직무 조건을 확인하세요.",
          field: "job",
          requestId: "shortage-inquiry",
        }),
        { status: 400, headers: { "Content-Type": "application/json" } },
      );
    return response({ rows: [row], total, page: 0, size: 50 });
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("shortage screen contract", () => {
  it("shows singular field error and preserves applied filters for retry", async () => {
    render(
      <MemoryRouter>
        <Host />
      </MemoryRouter>,
    );
    await screen.findByText("부족 0.50");
    await waitFor(() =>
      expect(screen.getByLabelText("직무")).not.toBeDisabled(),
    );
    fireEvent.change(screen.getByLabelText("직무"), {
      target: { value: "BE" },
    });
    fieldError = true;
    fireEvent.click(screen.getByTestId("shortage-search"));
    await waitFor(() =>
      expect(screen.getByLabelText("직무")).toHaveAttribute(
        "aria-invalid",
        "true",
      ),
    );
    expect(screen.getByLabelText("직무")).toHaveValue("BE");
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
    expect(screen.getByText(/shortage-inquiry/)).toBeInTheDocument();
    fieldError = false;
    fireEvent.click(screen.getByText("다시 시도"));
    expect(await screen.findByText("부족 0.50")).toBeInTheDocument();
    expect(listUrls().at(-1)).toContain("job=BE");
  });
  it("resets pagination when the shared year changes", async () => {
    total = 51;
    const view = render(
      <MemoryRouter>
        <Host year={2027} />
      </MemoryRouter>,
    );
    await screen.findByText("부족 0.50");
    fireEvent.click(screen.getByTestId("shortage-next"));
    await waitFor(() => expect(listUrls().at(-1)).toContain("page=1"));
    view.rerender(
      <MemoryRouter>
        <Host year={2028} />
      </MemoryRouter>,
    );
    await waitFor(() =>
      expect(listUrls().at(-1)).toBe(
        "/api/plan-vs-actual/shortages?year=2028&page=0&size=50",
      ),
    );
    await waitFor(() =>
      expect(screen.getByTestId("shortage-previous")).toBeDisabled(),
    );
  });
  it("exports applied filters, not unsubmitted edits or the current page", async () => {
    total = 51;
    fetcher.mockImplementation(async (url: string) => {
      if (url === "/api/codes/JOB")
        return response([{ code: "BE", name: "백엔드 개발" }]);
      if (url.includes("/export?"))
        return new Response(JSON.stringify({ message: "내려받기 확인" }), {
          status: 500,
        });
      return response({ rows: [row], total, page: 0, size: 50 });
    });
    render(
      <MemoryRouter>
        <Host />
      </MemoryRouter>,
    );
    await screen.findByText("부족 0.50");
    fireEvent.click(screen.getByTestId("shortage-next"));
    await waitFor(() => expect(listUrls().at(-1)).toContain("page=1"));
    fireEvent.change(screen.getByLabelText("프로젝트"), {
      target: { value: "73" },
    });
    fireEvent.click(screen.getByTestId("shortage-export"));
    expect(await screen.findByText("내려받기 확인")).toBeInTheDocument();
    expect(fetcher.mock.calls.map((call) => call[0])).toContain(
      "/api/plan-vs-actual/shortages/export?year=2027",
    );
    expect(screen.getByLabelText("프로젝트")).toHaveValue("73");
    expect(screen.getByText("부족 0.50")).toBeInTheDocument();
  });
  it("does not offer previous or next on a single page", async () => {
    render(
      <MemoryRouter>
        <Host />
      </MemoryRouter>,
    );
    await screen.findByText("부족 0.50");
    expect(screen.getByTestId("shortage-previous")).toBeDisabled();
    expect(screen.getByTestId("shortage-next")).toBeDisabled();
    expect(
      screen.queryByRole("button", { name: "등록" }),
    ).not.toBeInTheDocument();
  });
});
