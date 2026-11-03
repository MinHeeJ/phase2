import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter } from "react-router-dom";
import UI28Screen from "./UI28Screen";
import {
  boardDefaults,
  boardQuery,
  type BoardCell,
  type WorkforceMonth,
} from "./client";
import { boardScenarioDefaults, compareBoardScenario } from "./scenarios";
import controls from "../../shared/Controls.module.css";

const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
let fetcher: ReturnType<typeof vi.fn>,
  empty: boolean,
  failRead: boolean,
  cell: BoardCell;
const forecast: WorkforceMonth = {
  month: "2026-11",
  job_cd: "BE",
  grd_cd: "S",
  supply_mm: 1,
  demand_mm: 1.41,
  remaining_mm: -0.41,
  shortage_mm: 0.41,
  available_mm: 2,
  confirmed_mm: 1,
  extension_mm: 0,
  temporary_mm: 0.5,
  utilization_rate: 50,
  idle_cost: 3000000,
  estimated: true,
  baseline_operating_profit: null,
  average_labor_cost: 3000000,
  warnings: ["미래 실제 손익 없음"],
};
const mount = () =>
  render(
    <MemoryRouter>
      <UI28Screen />
    </MemoryRouter>,
  );
beforeEach(() => {
  sessionStorage.clear();
  empty = false;
  failRead = false;
  vi.useFakeTimers({ toFake: ["Date"] });
  vi.setSystemTime(new Date("2026-10-14T00:00:00Z"));
  cell = {
    month: "2026-01",
    confirmed_mm: 0.5,
    temporary_mm: 0.75,
    assignment_count: 2,
    status: "투입중",
    withdrawal_date: "2026-01-31",
    fill_ratio: 50,
    assignments: [],
    temp_assignments: [],
    warnings: [],
  };
  fetcher = vi.fn(async (url: string) => {
    if (url.startsWith("/api/codes/"))
      return response([
        {
          code: url.endsWith("GRADE") ? "S" : "BE",
          name: url.endsWith("GRADE") ? "고급" : "백엔드 개발",
        },
      ]);
    if (failRead) throw new Error("offline");
    if (url.startsWith("/api/workforce/forecast"))
      return response({ items: [forecast], total: 1, page: 0, size: 100 });
    if (url.startsWith("/api/opportunities"))
      return response({ items: [], total: 0 });
    return response({
      items: empty
        ? []
        : [
            {
              dev_id: 2,
              dev_nm: "개발자",
              mng_no: "K-0002",
              job_cd: "BE",
              grd_cd: "S",
              months: Array.from({ length: 12 }, (_, i) => ({
                ...cell,
                month: `2026-${String(i + 1).padStart(2, "0")}`,
              })),
            },
          ],
      total: empty ? 0 : url.includes("size=100") ? 1 : 61,
      page: 0,
      size: 50,
      monthly_totals: Array.from({ length: 12 }, (_, i) => ({
        month: `2026-${String(i + 1).padStart(2, "0")}`,
        free_people: 5,
        remaining_mm: 8.5,
      })),
    });
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});
describe("UI-28 인력 운영 보드", () => {
  it("renders twelve months, identities, MM, global totals and keyboard details", async () => {
    mount();
    await screen.findByTestId("board-row-2");
    expect(
      screen.getByRole("heading", { name: "인력 운영 보드" }),
    ).toBeInTheDocument();
    expect(screen.getByText("K-0002")).toBeInTheDocument();
    expect(screen.getByText("12월")).toBeInTheDocument();
    const button = screen.getByTestId("board-cell-2-2026-01");
    expect(button).toHaveTextContent("확정 0.50");
    expect(button).toHaveTextContent("가배정 0.75");
    expect(button).toHaveTextContent("2건");
    expect(button).toHaveTextContent("철수 01-31");
    fireEvent.focus(button);
    expect(screen.getByTestId("board-cell-details")).toHaveTextContent(
      "개발자 · 2026-01",
    );
    fireEvent.keyDown(screen.getByTestId("board-cell-details"), {
      key: "Escape",
    });
    expect(screen.queryByTestId("board-cell-details")).not.toBeInTheDocument();
    expect(
      screen.getByText("총 61명 · 1쪽 · 월 합계는 전체 필터 기준"),
    ).toBeInTheDocument();
    expect(screen.queryByText(/AI 추천/)).not.toBeInTheDocument();
  });
  it.each([
    ["휴직", "locked"],
    ["퇴사", "locked"],
    ["투입중", "success"],
    ["철수예정", "warning"],
    ["가배정", "info"],
    ["대기", "danger"],
  ])("renders %s with its own semantic tone", async (status, tone) => {
    cell.status = status;
    mount();
    const button = await screen.findByTestId("board-cell-2-2026-01");
    expect(button.querySelector(`.${controls[tone]}`)).toHaveTextContent(
      status,
    );
  });
  it("preserves actual over-cap MM and shows warning", async () => {
    cell.confirmed_mm = 1.25;
    cell.fill_ratio = 100;
    mount();
    const button = await screen.findByTestId("board-cell-2-2026-01");
    expect(button).toHaveTextContent("확정 1.25");
    expect(button).toHaveTextContent("MM 초과");
  });
  it("applies AND filters on submit and pages without changing the filters", async () => {
    mount();
    await screen.findByTestId("board-row-2");
    fireEvent.change(screen.getByTestId("board-filter-job"), {
      target: { value: "BE" },
    });
    fireEvent.change(screen.getByTestId("board-filter-grade"), {
      target: { value: "S" },
    });
    fireEvent.click(screen.getByTestId("board-free-only"));
    fireEvent.click(screen.getByTestId("board-query"));
    await waitFor(() =>
      expect(fetcher).toHaveBeenCalledWith(
        expect.stringContaining("free_only=true"),
        expect.anything(),
      ),
    );
    await screen.findByTestId("board-row-2");
    fireEvent.click(screen.getByTestId("board-page-next"));
    await waitFor(() =>
      expect(fetcher).toHaveBeenCalledWith(
        expect.stringContaining("page=1"),
        expect.anything(),
      ),
    );
  });
  it("shows empty and resets filters", async () => {
    empty = true;
    mount();
    expect(
      await screen.findByText("조건에 맞는 결과가 없습니다."),
    ).toBeInTheDocument();
    fireEvent.click(screen.getByTestId("board-empty-reset"));
    expect(screen.getByTestId("board-free-only")).not.toBeChecked();
  });
  it("shows request failure and retries", async () => {
    failRead = true;
    mount();
    await screen.findByText("서버에 연결할 수 없습니다.");
    failRead = false;
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    expect(await screen.findByTestId("board-row-2")).toBeInTheDocument();
  });
  it("export excludes sort and pagination while preserving every approved filter", () => {
    const query = boardQuery(
      {
        ...boardDefaults(2026),
        page: 4,
        job: "BE",
        grade: "S",
        skill: "JAVA",
        emp_se_cd: "FT",
        free_only: true,
      },
      true,
    );
    expect(Object.fromEntries(query)).toEqual({
      year: "2026",
      job: "BE",
      grade: "S",
      skill: "JAVA",
      emp_se_cd: "FT",
      free_only: "true",
    });
  });
  it("renders shared forecast warnings, 90 percent target and read-only scenario controls", async () => {
    mount();
    await screen.findByTestId("board-row-2");
    fireEvent.click(screen.getByTestId("board-forecast-tab"));
    await screen.findByTestId("board-forecast-2026-11-BE-S");
    expect(screen.getByText("미래 실제 손익 없음")).toBeInTheDocument();
    expect(screen.getByText("-0.41")).toHaveClass(controls.danger);
    expect(screen.getByLabelText("2026-11 가동률")).toHaveAttribute(
      "optimum",
      "90",
    );
    fireEvent.click(screen.getByTestId("board-scenario-add"));
    await screen.findByText("진행 중인 기회가 없습니다.");
    fireEvent.click(screen.getByTestId("board-scenario-cancel"));
    expect(
      screen.queryByTestId("board-scenario-editor"),
    ).not.toBeInTheDocument();
    expect(
      fetcher.mock.calls.every(
        ([, init]) =>
          !(init as RequestInit)?.method ||
          (init as RequestInit).method === "GET",
      ),
    ).toBe(true);
  });
  it("keeps missing profit null and calculates labor deltas without changing inputs", () => {
    const scenario = {
      ...boardScenarioDefaults(1),
      job: "BE",
      grade: "S",
      people: 2,
    };
    const before = JSON.stringify(forecast);
    const value = compareBoardScenario(forecast, scenario, [], []);
    expect(value.supply).toBe(3);
    expect(value.laborDelta).toBe(6000000);
    expect(value.operatingProfit).toBeNull();
    expect(value.profitDelta).toBe(-6000000);
    expect(JSON.stringify(forecast)).toBe(before);
  });
  it("limits scenarios to three, removes and resets without any write API", async () => {
    mount();
    await screen.findByTestId("board-row-2");
    fireEvent.click(screen.getByTestId("board-forecast-tab"));
    await screen.findByTestId("board-scenario-add");
    for (let i = 0; i < 3; i++) {
      fireEvent.click(screen.getByTestId("board-scenario-add"));
      await waitFor(() =>
        expect(screen.getByTestId("board-scenario-save")).toBeEnabled(),
      );
      fireEvent.change(screen.getByTestId("board-scenario-job"), {
        target: { value: "BE" },
      });
      fireEvent.submit(screen.getByTestId("board-scenario-editor"));
      await waitFor(() =>
        expect(
          screen.queryByTestId("board-scenario-editor"),
        ).not.toBeInTheDocument(),
      );
    }
    expect(screen.getByTestId("board-scenario-add")).toBeDisabled();
    expect(
      screen.getByTestId("board-scenario-row-2026-11-BE-S"),
    ).toHaveTextContent("기존 예상 영업이익 원천 미확인");
    fireEvent.click(screen.getByTestId("board-scenario-remove-2"));
    expect(screen.getByTestId("board-scenario-add")).toBeEnabled();
    fireEvent.click(screen.getByTestId("board-scenario-reset"));
    expect(
      screen.queryByTestId("board-scenario-remove-1"),
    ).not.toBeInTheDocument();
    expect(
      fetcher.mock.calls.every(([, init]) => !(init as RequestInit)?.method),
    ).toBe(true);
  });
  it("opportunity assumption uses stored probability and actual need dates without mutating them", () => {
    const opportunity = {
      oppt_id: 7,
      oppt_nm: "기회",
      oppt_stts_cd: "OPEN",
      win_prblt_rt: 60,
      expc_amt: 10000000,
      bgn_ym: "2026-11",
      mth_cnt: 2,
      needs: [
        {
          job_cd: "BE",
          grd_cd: "S",
          psn_cnt: 2,
          bgn_ym: "2026-11",
          mth_cnt: 1,
          mth_mm_qty: 0.5,
        },
      ],
    };
    const before = JSON.stringify(opportunity);
    const scenario = { ...boardScenarioDefaults(1), opportunityIds: [7] };
    const value = compareBoardScenario(forecast, scenario, [opportunity], []);
    expect(value.demand).toBeCloseTo(1.81);
    expect(value.revenueDelta).toBe(2000000);
    expect(
      compareBoardScenario(
        { ...forecast, month: "2026-12" },
        scenario,
        [opportunity],
        [],
      ).demand,
    ).toBe(forecast.demand_mm);
    expect(JSON.stringify(opportunity)).toBe(before);
  });
  it("zero missing labor cost is explicit and never invents a profit baseline", () => {
    const value = compareBoardScenario(
      { ...forecast, average_labor_cost: 0 },
      { ...boardScenarioDefaults(1), job: "BE", grade: "S", people: 1 },
      [],
      [],
    );
    expect(value.laborDelta).toBe(0);
    expect(value.warnings.join(" ")).toContain("원가 미확인");
    expect(value.operatingProfit).toBeNull();
  });
});
