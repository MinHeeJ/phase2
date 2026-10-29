import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import UI04Screen, {
  CostProjectDrawer,
  CostProjectSimulation,
} from "./UI04Screen";
import UI11Screen from "../us4/UI11Screen";
import { response, failure } from "../developers/DeveloperTestSupport";
import type {
  CostProjectDetail,
  CostProjectList,
  CostProjectRow,
} from "./costClient";
import { MmGapJudge } from "../../shared/MmGapJudge";
import table from "../developers/Developers.module.css";

const row: CostProjectRow = {
  prj_id: 7,
  prj_no: "P-2607",
  prj_nm: "원가 프로젝트",
  cntrct_ty_cd: "TK",
  cur_cntrct_amt: 500,
  init_cntrct_amt: 500,
  trgt_cst_amt: 400,
  trgt_mrgn_rt: 20,
  plan_mm_qty: 1,
  actual_mm_qty: 1.1,
  confirmed_supply_amt: 200,
  lbr_cst_amt: 300,
  drct_expns_amt: 20,
  cumulative_cost_amt: 320,
  profit_amt: -120,
  margin_rt: -60,
  budget_use_rt: 80,
  budget_remaining_amt: 80,
  estimated_cost_amt: null,
  estimated_margin_rt: null,
  reference_revenue_amt: 400,
  free_mm_qty: 0.1,
  free_cost_amt: 20,
  price_per_mm_amt: 100,
  cost_per_mm_amt: 200,
  profit_per_mm_amt: -100,
  low_margin: false,
  budget_warning: "주의",
  primary_cause: "OVER_MM",
};
const metadata = {
  calculation_month: "2026-10",
  reference_month: "2026-10",
  closed: false,
  unit: "원",
  warnings: [] as string[],
};
const causes = {
  items: [
    {
      cause_code: "OVER_MM",
      amount: 100,
      basis: "실제 MM−계획 MM",
      owner_name: "담당자",
      recommended_action: "투입 계획 확인",
      target_screen: "UI-11",
      project_id: 7,
    },
    {
      cause_code: "COST_RATE",
      amount: 30,
      basis: "배분 원가",
      owner_name: "담당자",
      recommended_action: "단가 재협상 검토",
      target_screen: "UI-32",
      project_id: 7,
    },
    {
      cause_code: "FREE_SUPPORT",
      amount: 0,
      basis: "무상지원",
      owner_name: null,
      recommended_action: "무상지원 범위 확인",
      target_screen: "UI-04",
      project_id: 7,
    },
  ],
  progress_excess_amt: 120,
  unexplained_amt: -10,
  primary_cause: "OVER_MM",
};
const initialDetail: CostProjectDetail = {
  ...metadata,
  project: row,
  causes,
  months: [
    {
      month: "2026-10",
      closed: false,
      confirmed_supply_amt: 200,
      lbr_cst_amt: 300,
      drct_expns_amt: 20,
      cumulative_cost_amt: 320,
      profit_amt: -120,
      margin_rt: -60,
      free_mm_qty: 0.1,
      free_cost_amt: 20,
      budget_remaining_amt: 80,
      reference_revenue_amt: 400,
    },
  ],
  people: [],
};
let detail: CostProjectDetail;
let empty: boolean;
let fail: boolean;
let simulationFail: boolean;
let fetcher: ReturnType<typeof vi.fn>;
beforeEach(() => {
  sessionStorage.clear();
  localStorage.clear();
  vi.stubGlobal("innerWidth", 1280);
  vi.setSystemTime(new Date("2026-10-29T00:00:00Z"));
  detail = structuredClone(initialDetail);
  empty = false;
  fail = false;
  simulationFail = false;
  fetcher = vi.fn(async (path: string, init?: RequestInit) => {
    if (path.startsWith("/api/codes/"))
      return response([{ code: "TK", name: "턴키" }]);
    if (path.startsWith("/api/sales-parts/options")) return response([]);
    if (path.startsWith("/api/params/PLAN_GAP_TOLERANCE"))
      return response({ num_val: 0.1 });
    if (path.startsWith("/api/costs/projects/7/simulate")) {
      if (simulationFail)
        return new Response(
          JSON.stringify({
            success: false,
            message: "원가를 확인하세요.",
            code: "VALIDATION_ERROR",
            field: "monthly_cost_amt",
            requestId: "test",
          }),
          { status: 400, headers: { "Content-Type": "application/json" } },
        );
      return response({
        simulated: true,
        baseline_cost_amt: 320,
        additional_cost_amt: 40,
        simulated_cost_amt: 360,
        additional_revenue_amt: null,
        simulated_revenue_amt: null,
        simulated_profit_amt: null,
        simulated_margin_rt: null,
        warnings: [],
      });
    }
    if (path.startsWith("/api/costs/projects/7/causes"))
      return fail ? failure() : response(causes);
    if (path.startsWith("/api/costs/projects/7?"))
      return fail ? failure() : response(detail);
    if (path.startsWith("/api/costs/projects?")) {
      if (fail) return failure();
      const data: CostProjectList = {
        ...metadata,
        items: empty ? [] : [detail.project],
        total: empty ? 0 : 1,
        page: 0,
        size: 50,
        summary: {
          confirmed_supply_amt: 200,
          profit_amt: -120,
          low_margin_count: 0,
          budget_over_count: 0,
          rnd_cost_amt: 0,
        },
      };
      return response(data);
    }
    if (path.startsWith("/api/costs/renegotiations?"))
      return response({
        ...metadata,
        items: [],
        total: 0,
        page: 0,
        size: 50,
        raise_rate: 5,
        summary: {
          psn_cnt: 0,
          weighted_margin_rt: null,
          assumed_annual_profit_increase_amt: 0,
          target_annual_profit_increase_amt: 0,
        },
      });
    if (path.startsWith("/api/projects/options")) return response([]);
    if (path.startsWith("/api/plan-vs-actual?"))
      return new Response(
        JSON.stringify({
          success: true,
          meta: {
            today: "2026-10-29",
            projects: [
              { prj_id: 7, prj_no: "P-2607", prj_nm: "원가 프로젝트" },
            ],
          },
          data: {
            rows: [],
            total: 0,
            page: 0,
            size: 50,
            totals: { planMm: 0, actualMm: 0 },
          },
        }),
        { headers: { "Content-Type": "application/json" } },
      );
    throw new Error(`Unexpected request ${path} ${init?.method}`);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
const mount = () =>
  render(
    <MemoryRouter initialEntries={["/UI-04"]}>
      <UI04Screen />
    </MemoryRouter>,
  );
const drawer = (year = 2026) =>
  render(
    <MemoryRouter>
      <CostProjectDrawer
        id={7}
        year={year}
        onClose={vi.fn()}
        onRenegotiate={vi.fn()}
      />
    </MemoryRouter>,
  );

describe("project cost list and states", () => {
  it("uses shared table styles, real API filters and warning states", async () => {
    mount();
    const t = await screen.findByRole("table", { name: "프로젝트 원가 목록" });
    expect(t).toHaveClass(table.table);
    expect(within(t).getByText("예산 주의")).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("검색"), {
      target: { value: "조건" },
    });
    fireEvent.click(screen.getByRole("button", { name: /^조회$/ }));
    await waitFor(() =>
      expect(
        fetcher.mock.calls.some(([p]) =>
          String(p).includes("q=%EC%A1%B0%EA%B1%B4"),
        ),
      ).toBe(true),
    );
  });
  it("shows danger for over-budget and low margin separately", async () => {
    detail.project = { ...row, low_margin: true, budget_warning: "위험" };
    mount();
    expect(await screen.findByText("예산 위험")).toBeInTheDocument();
    expect(screen.getByText("저마진", { exact: true })).toBeInTheDocument();
  });
  it("suppresses IN financial values and labels research cost", async () => {
    detail.project = {
      ...row,
      cntrct_ty_cd: "IN",
      cur_cntrct_amt: null,
      init_cntrct_amt: null,
      margin_rt: null,
      confirmed_supply_amt: null,
      profit_amt: null,
    };
    mount();
    expect(
      await screen.findByText("연구개발비", { selector: "span" }),
    ).toBeInTheDocument();
    expect(
      within(screen.getByTestId("cost-project-row-7")).getAllByText("–").length,
    ).toBeGreaterThan(0);
  });
  it("renders loading without fabricated rows", async () => {
    let finish!: (value: Response) => void;
    fetcher.mockImplementation(
      () =>
        new Promise<Response>((resolve) => {
          finish = resolve;
        }),
    );
    mount();
    expect(
      screen.getByLabelText("프로젝트 원가 불러오는 중"),
    ).toBeInTheDocument();
    expect(screen.queryByTestId("cost-project-row-7")).not.toBeInTheDocument();
    finish(response([]));
  });
  it("shows empty result and reset without a sample row", async () => {
    empty = true;
    mount();
    expect(
      await screen.findByText(/조건에 맞는 결과가 없습니다/),
    ).toBeInTheDocument();
    expect(screen.queryByTestId("cost-project-row-7")).not.toBeInTheDocument();
  });
  it("failed request retains search and offers retry", async () => {
    fail = true;
    mount();
    await screen.findByRole("button", { name: "다시 시도" });
    fireEvent.change(screen.getByLabelText("검색"), {
      target: { value: "유지" },
    });
    expect(screen.getByLabelText("검색")).toHaveValue("유지");
    fail = false;
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    expect(await screen.findByTestId("cost-project-row-7")).toBeInTheDocument();
  });
  it("embeds merged renegotiation panel without changing the legacy URL", async () => {
    mount();
    fireEvent.click(screen.getByRole("button", { name: "단가 재협상 대상" }));
    expect(
      await screen.findByTestId("renegotiation-panel"),
    ).toBeInTheDocument();
  });
});

describe("detail tabs and navigation", () => {
  it("shows monthly actual amounts and a project detail link", async () => {
    drawer();
    const t = await screen.findByRole("table", { name: "월별 원가" });
    expect(t).toHaveClass(table.table);
    expect(within(t).getByText("320")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "프로젝트 상세" })).toHaveAttribute(
      "href",
      "/UI-01?open=7",
    );
    fireEvent.click(screen.getByRole("tab", { name: "투입 인력" }));
    expect(screen.getByText("투입 인력이 없습니다.")).toBeInTheDocument();
  });
  it("shows signed residual and zero cause without dropping it", async () => {
    drawer();
    await screen.findByRole("table", { name: "월별 원가" });
    fireEvent.click(screen.getByRole("tab", { name: "원가 초과 원인" }));
    expect(
      await screen.findByText(/설명 안 되는 나머지 -10/),
    ).toBeInTheDocument();
    expect(
      screen.getByTestId("cost-project-cause-free-support"),
    ).toHaveTextContent("0");
  });
  it("deep-links into real UI11 with selected project and year", async () => {
    render(
      <MemoryRouter initialEntries={["/UI-04"]}>
        <Routes>
          <Route
            path="/UI-04"
            element={
              <CostProjectDrawer
                id={7}
                year={2026}
                onClose={vi.fn()}
                onRenegotiate={vi.fn()}
              />
            }
          />
          <Route path="/UI-11" element={<UI11Screen />} />
        </Routes>
      </MemoryRouter>,
    );
    await screen.findByRole("table", { name: "월별 원가" });
    fireEvent.click(screen.getByRole("tab", { name: "원가 초과 원인" }));
    fireEvent.click(
      await screen.findByRole("button", { name: "투입 계획 확인" }),
    );
    await waitFor(() =>
      expect(screen.getByLabelText("프로젝트")).toHaveValue("7"),
    );
    expect(
      fetcher.mock.calls.some(
        ([p]) =>
          String(p).startsWith("/api/plan-vs-actual?") &&
          String(p).includes("projectId=7") &&
          String(p).includes("year=2026"),
      ),
    ).toBe(true);
  });
  it("ignores invalid UI11 navigation state and keeps session fallback", async () => {
    sessionStorage.setItem("UI-11.year", "2025");
    render(
      <MemoryRouter
        initialEntries={[
          { pathname: "/UI-11", state: { projectId: -1, year: 2099 } },
        ]}
      >
        <UI11Screen />
      </MemoryRouter>,
    );
    await waitFor(() =>
      expect(
        fetcher.mock.calls.some(([p]) => String(p).includes("year=2025")),
      ).toBe(true),
    );
    expect(screen.getByLabelText("프로젝트")).toHaveValue("");
  });
  it("past year has no scenario form", async () => {
    drawer(2025);
    await screen.findByRole("table", { name: "월별 원가" });
    fireEvent.click(screen.getByRole("tab", { name: "시뮬레이션" }));
    expect(screen.queryByLabelText("인원 증감")).not.toBeInTheDocument();
    expect(screen.getByText(/지난 해는 조회/)).toBeInTheDocument();
  });
});

describe("nonpersistent simulation", () => {
  it.each(["TK", "IN"])("hides monthly price input for %s", (type) => {
    render(<CostProjectSimulation id={7} type={type} />);
    expect(screen.queryByLabelText("1인 월 단가")).not.toBeInTheDocument();
  });
  it("requires integral won without an API call", () => {
    render(<CostProjectSimulation id={7} type="MO" />);
    fireEvent.change(screen.getByLabelText("1인 월 원가"), {
      target: { value: "1.5" },
    });
    fireEvent.submit(screen.getByTestId("simulation-run").closest("form")!);
    expect(fetcher).not.toHaveBeenCalled();
  });
  it("keeps inputs after a field error and marks virtual success on retry", async () => {
    simulationFail = true;
    render(<CostProjectSimulation id={7} type="MO" />);
    for (const [label, value] of [
      ["인원 증감", "1"],
      ["1인 월 원가", "20"],
      ["적용 개월", "2"],
      ["1인 월 단가", "30"],
    ])
      fireEvent.change(screen.getByLabelText(label), { target: { value } });
    fireEvent.submit(screen.getByTestId("simulation-run").closest("form")!);
    await screen.findByRole("button", { name: "다시 시도" });
    expect(screen.getByLabelText("1인 월 원가")).toHaveValue(20);
    simulationFail = false;
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    expect(await screen.findByTestId("simulation-result")).toHaveTextContent(
      "가상 · 저장되지 않음",
    );
    const writes = fetcher.mock.calls.filter(
      ([, init]) => init?.method === "POST",
    );
    expect(writes).toHaveLength(2);
    expect(JSON.parse(writes[0][1].body)).toEqual({
      headcount_delta: 1,
      monthly_cost_amt: 20,
      months: 2,
      monthly_price_amt: 30,
    });
  });
  it("MM tolerance includes exactly 0.10MM and excludes 0.11MM", () => {
    expect(MmGapJudge(1, 1.1, 0.1)).toBe("MATCH");
    expect(MmGapJudge(1, 1.11, 0.1)).toBe("OVER");
    expect(MmGapJudge(1, 0.9, 0.1)).toBe("MATCH");
    expect(MmGapJudge(1, 0.89, 0.1)).toBe("SHORT");
  });
});
