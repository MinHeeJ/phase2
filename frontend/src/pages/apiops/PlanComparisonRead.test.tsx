import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter } from "react-router-dom";
import UI11Screen from "../us4/UI11Screen";
import type { PlanComparisonPage } from "../us4/client";

const response = (data: unknown, meta: Record<string, unknown> = {}) =>
  new Response(JSON.stringify({ success: true, data, meta }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
let data: PlanComparisonPage;
let fetcher: ReturnType<typeof vi.fn>;
let failRead: boolean;
let resolveRead: (() => void) | undefined;
const mount = () =>
  render(
    <MemoryRouter>
      <UI11Screen />
    </MemoryRouter>,
  );
const reads = () =>
  fetcher.mock.calls.filter(([url]) =>
    String(url).startsWith("/api/plan-vs-actual?"),
  );
beforeEach(() => {
  sessionStorage.clear();
  vi.useFakeTimers({ toFake: ["Date"] });
  vi.setSystemTime(new Date("2026-10-14T00:00:00Z"));
  failRead = false;
  resolveRead = undefined;
  data = {
    rows: [
      {
        prj_id: 42,
        prj_no: "P-2642",
        prj_nm: "인력 계획",
        job_cd: "BE",
        job_nm: "백엔드 개발",
        planMm: 1998,
        actualMm: 2,
        gapMm: -1996,
        gap: "SHORT",
      },
    ],
    total: 1,
    page: 0,
    size: 50,
    totals: { planMm: 1998, actualMm: 2 },
  };
  fetcher = vi.fn(async (url: string) => {
    if (url === "/api/params")
      return response([{ oprt_std_id: "PLAN_GAP_TOLERANCE", num_val: 0.1 }]);
    if (url === "/api/codes/JOB")
      return response([{ code: "BE", name: "백엔드 개발" }]);
    if (url.startsWith("/api/plan-vs-actual?")) {
      if (resolveRead)
        await new Promise<void>((resolve) => {
          resolveRead = resolve;
        });
      if (failRead)
        return new Response(
          JSON.stringify({
            success: false,
            message: "조회에 실패했습니다.",
            field: "year",
          }),
          { status: 400 },
        );
      return response(data, {
        today: "2026-10-14",
        projects: [{ prj_id: 42, prj_no: "P-2642", prj_nm: "인력 계획" }],
      });
    }
    throw new Error(`Unexpected request ${url}`);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  vi.useRealTimers();
});

describe("existing plan comparison read integration", () => {
  it("shows pending state until the API resolves, then displays uncapped source-plan totals", async () => {
    resolveRead = () => {};
    mount();
    expect(
      screen.getByLabelText("계획 대비 투입 불러오는 중"),
    ).toBeInTheDocument();
    expect(screen.queryByTestId("comparison-totals")).not.toBeInTheDocument();
    await waitFor(() => expect(reads()).toHaveLength(1));
    resolveRead?.();
    resolveRead = undefined;
    await screen.findByText("부족 1996.00");
    expect(
      within(screen.getByTestId("comparison-totals")).getByText("1998.00"),
    ).toBeVisible();
    expect(screen.getByRole("link", { name: "인력 계획" })).toHaveAttribute(
      "href",
      "/UI-01?open=42&tab=staff-plans",
    );
    expect(
      screen.queryByRole("button", { name: "저장" }),
    ).not.toBeInTheDocument();
  });
  it("retains the applied project and job after a failed request and retry", async () => {
    mount();
    await screen.findByText("부족 1996.00");
    fireEvent.change(screen.getByLabelText("프로젝트"), {
      target: { value: "42" },
    });
    fireEvent.change(screen.getByLabelText("직무"), {
      target: { value: "BE" },
    });
    failRead = true;
    fireEvent.click(screen.getByTestId("comparison-query"));
    await screen.findByText("year: 조회에 실패했습니다.");
    expect(screen.queryByTestId("comparison-totals")).not.toBeInTheDocument();
    expect(screen.getByLabelText("직무")).toHaveValue("BE");
    failRead = false;
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    await screen.findByText("부족 1996.00");
    const query = new URLSearchParams(
      String(reads().at(-1)?.[0]).split("?")[1],
    );
    expect(query.get("projectId")).toBe("42");
    expect(query.get("job")).toBe("BE");
    expect(query.get("year")).toBe("2026");
  });
  it("shows a resettable empty list rather than fabricated workforce values", async () => {
    data = { ...data, rows: [], total: 0, totals: { planMm: 0, actualMm: 0 } };
    mount();
    await screen.findByText("조건에 맞는 결과가 없습니다.");
    expect(screen.queryByText("부족 1996.00")).not.toBeInTheDocument();
    fireEvent.click(screen.getByTestId("comparison-empty-reset"));
    await waitFor(() => expect(reads()).toHaveLength(2));
  });
});
