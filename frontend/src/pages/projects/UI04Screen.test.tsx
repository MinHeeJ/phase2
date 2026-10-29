import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import {
  Link,
  MemoryRouter,
  Route,
  Routes,
  useLocation,
} from "react-router-dom";
import UI04Screen from "./UI04Screen";
import UI11Screen from "../us4/UI11Screen";
import { MmGapJudge } from "../../shared/MmGapJudge";
import type {
  CostProjectRow,
  CostProjectDetail,
  CostProjectCauses,
} from "./costProjectClient";
import table from "../developers/Developers.module.css";
const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
let row: CostProjectRow,
  detail: CostProjectDetail,
  causes: CostProjectCauses,
  fetcher: ReturnType<typeof vi.fn>,
  empty: boolean,
  failed: boolean,
  simulationError: boolean;
const metadata = {
  month: "2026-10",
  status: "OPEN" as const,
  referenceMonth: "2026-10",
  unit: "KRW" as const,
  warnings: [],
};
function Location() {
  const location = useLocation();
  return (
    <output data-testid="location">
      {location.pathname}
      {location.search}
    </output>
  );
}
function mount(path = "/UI-04") {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/UI-04" element={<UI04Screen />} />
        <Route path="/UI-11" element={<UI11Screen />} />
      </Routes>
      <Location />
    </MemoryRouter>,
  );
}
beforeEach(() => {
  sessionStorage.clear();
  vi.useFakeTimers({ toFake: ["Date"] });
  vi.setSystemTime(new Date("2026-10-29T00:00:00Z"));
  empty = false;
  failed = false;
  simulationError = false;
  row = {
    prj_id: 17,
    prj_no: "P-2617",
    prj_nm: "분석 프로젝트",
    cntrct_ty_cd: "TK",
    prj_stts_cd: "RUN",
    bsn_part_id: 1,
    part_name: "영업",
    init_cntrct_amt: 500,
    cur_cntrct_amt: 500,
    trgt_cst_amt: 400,
    trgt_mrgn_rt: 20,
    plan_mm_qty: 2,
    actual_mm_qty: 3,
    confirmed_supply_amt: 200,
    lbr_cst_amt: 300,
    drct_expns_amt: 20,
    total_cost_amt: 320,
    profit_amt: -120,
    margin_rt: -60,
    expected_margin_rt: 10,
    monthly_margin_rt: null,
    budget_use_rt: 80,
    target_diff_rt: -10,
    per_mm_price_amt: 66.7,
    per_mm_cost_amt: 106.7,
    per_mm_profit_amt: -40,
    free_mm_qty: 0.5,
    free_cost_amt: 40,
    rnd_cost_amt: null,
    budget_warning: "WARNING",
    low_margin: true,
    main_cause: "EXCESS_MM",
    warnings: [],
  };
  causes = {
    ...metadata,
    prj_id: 17,
    year: 2026,
    main_cause: "EXCESS_MM",
    progress_excess_amt: 50,
    unexplained_amt: -10,
    items: [
      {
        code: "EXCESS_MM",
        label: "투입 초과",
        amount: 100,
        basis: "초과 MM × MM당 원가",
        owner: "PM 미지정",
        action: "인원 계획 대비 투입 확인",
        navigation: "/UI-11?projectId=17&year=2026",
        available: true,
      },
      {
        code: "COST_PRICE",
        label: "단가 대비 원가",
        amount: 0,
        basis: "배분 원가 − 개인 매출 × 기준율",
        owner: "PM 미지정",
        action: "승인 대기",
        navigation: null,
        available: false,
      },
      {
        code: "FREE_SUPPORT",
        label: "무상지원",
        amount: 40,
        basis: "총원가에 이미 포함",
        owner: "PM 미지정",
        action: "프로젝트 원가 분석 확인",
        navigation: "/UI-04?projectId=17&year=2026",
        available: true,
      },
    ],
  };
  detail = {
    ...metadata,
    project: row,
    monthly_rows: [
      {
        month: "2026-10",
        status: "OPEN",
        confirmed_supply_amt: 200,
        lbr_cst_amt: 300,
        drct_expns_amt: 20,
        total_cost_amt: 320,
        profit_amt: -120,
        margin_rt: -60,
        mm_qty: 3,
        free_mm_qty: 0.5,
        free_cost_amt: 40,
        budget_remaining_amt: 80,
        reference_revenue_amt: 100,
      },
    ],
    people_rows: [
      {
        month: "2026-10",
        dev_id: 7,
        dev_nm: "개발자",
        asgn_id: 9,
        mm_qty: 0.5,
        lbr_cst_amt: 40,
        drct_expns_amt: 0,
        personal_revenue_amt: 0,
        free_sprt_yn: "Y",
      },
    ],
    reference_revenue_amt: 100,
    causes,
  };
  fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (url === "/api/codes/CONTRACT_TYPE")
      return response([
        { code: "TK", name: "턴키" },
        { code: "MO", name: "월단위" },
        { code: "IN", name: "내부" },
      ]);
    if (url === "/api/codes/PROJECT_STATUS")
      return response([
        { code: "RUN", name: "진행" },
        { code: "END", name: "종료" },
      ]);
    if (url === "/api/codes/JOB") return response([]);
    if (url === "/api/params")
      return response([{ oprt_std_id: "PLAN_GAP_TOLERANCE", num_val: 0.1 }]);
    if (url === "/api/sales-parts/options")
      return response([{ id: "1", name: "영업", hq: false }]);
    if (url.startsWith("/api/plan-vs-actual?"))
      return new Response(
        JSON.stringify({
          success: true,
          meta: {
            today: "2026-10-29",
            projects: [
              { prj_id: 17, prj_no: "P-2617", prj_nm: "분석 프로젝트" },
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
        { status: 200, headers: { "Content-Type": "application/json" } },
      );
    if (url.endsWith("/simulate")) {
      if (simulationError)
        return new Response(
          JSON.stringify({
            success: false,
            code: "VALIDATION_ERROR",
            field: "headcount_delta",
            message: "현재 인원을 확인하세요.",
            requestId: "project-request",
          }),
          { status: 400 },
        );
      return response({
        ...metadata,
        simulated: true,
        cost_delta_amt: 30,
        revenue_delta_amt: 0,
        profit_delta_amt: -30,
      });
    }
    if (failed) throw new Error("offline");
    if (url.includes("/causes")) return response(causes);
    if (url.includes("/projects/17")) return response(detail);
    if (url.startsWith("/api/costs/projects?"))
      return response({
        ...metadata,
        items: empty ? [] : [row],
        total: empty ? 0 : 1,
        page: 0,
        size: 50,
        summary: {
          confirmed_supply_amt: 200,
          profit_amt: -120,
          low_margin_count: 1,
          budget_exceeded_count: 0,
          rnd_cost_amt: 0,
        },
      });
    throw new Error(`Unexpected request ${url} ${init?.method}`);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  vi.useRealTimers();
});
async function open() {
  await screen.findByTestId("project-costs-open-17");
  fireEvent.click(screen.getByTestId("project-costs-open-17"));
  await screen.findByTestId("project-costs-detail-panel");
}
async function simulation() {
  await open();
  fireEvent.click(screen.getByTestId("project-costs-tab-2"));
}
function fill() {
  for (const [name, value] of [
    ["headcount-delta", "1"],
    ["monthly-cost-amt", "10"],
    ["months", "3"],
  ])
    fireEvent.change(screen.getByTestId(`project-simulation-${name}`), {
      target: { value },
    });
}
describe("project costs", () => {
  it("renders API-backed summary, warnings, same developer table classes and one-decimal budget", async () => {
    mount();
    const item = await screen.findByTestId("project-costs-row-17");
    expect(within(item).getByText("320")).toBeVisible();
    expect(within(item).getByText("80.0")).toBeVisible();
    expect(within(item).getByText("예산 80% 이상").className).toContain(
      "warning",
    );
    expect(within(item).getByText("저마진").className).toContain("danger");
    expect(item.closest("table")).toHaveClass(table.table);
    expect(screen.getByTestId("project-costs-summary")).toHaveTextContent(
      "연구개발비",
    );
  });
  it("supports empty results without fictitious data", async () => {
    empty = true;
    mount();
    expect(await screen.findByText(/조건에 맞는 결과/)).toBeVisible();
    expect(
      screen.queryByTestId("project-costs-row-17"),
    ).not.toBeInTheDocument();
  });
  it("shows loading and request failure then retries", async () => {
    failed = true;
    mount();
    expect(screen.getByLabelText("프로젝트 원가 불러오는 중")).toBeVisible();
    await screen.findByText("서버에 연결할 수 없습니다.");
    failed = false;
    fireEvent.click(screen.getByText("다시 시도"));
    await screen.findByTestId("project-costs-row-17");
  });
  it("sends selected filters and zero-based paging to relative API", async () => {
    mount();
    await screen.findByText("턴키", { selector: "option" });
    fireEvent.change(screen.getByTestId("project-costs-type"), {
      target: { value: "MO" },
    });
    fireEvent.change(screen.getByTestId("project-costs-search"), {
      target: { value: "업무" },
    });
    fireEvent.click(screen.getByTestId("project-costs-query"));
    await waitFor(() =>
      expect(
        fetcher.mock.calls.some(
          (c) => c[0].includes("type=MO") && c[0].includes("q="),
        ),
      ).toBe(true),
    );
  });
  it("shows unavailable values as dash and internal project cost without sales", async () => {
    Object.assign(row, {
      cntrct_ty_cd: "IN",
      init_cntrct_amt: null,
      cur_cntrct_amt: null,
      confirmed_supply_amt: null,
      profit_amt: null,
      margin_rt: null,
      expected_margin_rt: null,
      budget_use_rt: null,
      target_diff_rt: null,
      budget_warning: null,
      low_margin: false,
      rnd_cost_amt: 320,
    });
    mount();
    const item = await screen.findByTestId("project-costs-row-17");
    expect(within(item).getAllByText("–").length).toBeGreaterThan(5);
    expect(within(item).queryByText("저마진")).not.toBeInTheDocument();
  });
  it("distinguishes danger budget from warning budget", async () => {
    row.budget_warning = "DANGER";
    row.budget_use_rt = 100;
    mount();
    const item = await screen.findByTestId("project-costs-row-17");
    expect(within(item).getByText("예산 100% 이상").className).toContain(
      "danger",
    );
  });
  it("opens monthly and people evidence and existing project detail link", async () => {
    mount();
    await open();
    expect(screen.getByTestId("project-costs-contract-link")).toHaveAttribute(
      "href",
      "/UI-01?open=P-2617",
    );
    const month = screen.getByTestId("project-cost-month-2026-10");
    expect(month.closest("table")).toHaveClass(table.table);
    expect(within(month).getByText("미마감")).toBeVisible();
    fireEvent.click(screen.getByTestId("project-costs-tab-1"));
    expect(screen.getByTestId("project-cost-person-0")).toHaveTextContent(
      "개발자",
    );
    fireEvent.click(screen.getByTestId("project-costs-close"));
    expect(
      screen.queryByTestId("project-costs-drawer"),
    ).not.toBeInTheDocument();
  });
  it("simulates TK with canonical inputs only and visibly labels nonpersistent results", async () => {
    mount();
    await simulation();
    expect(screen.queryByLabelText("1인 월 단가")).not.toBeInTheDocument();
    fill();
    fireEvent.click(screen.getByTestId("project-simulation-calculate"));
    expect(
      await screen.findByTestId("project-simulation-result"),
    ).toHaveTextContent("가상 · DB 미저장");
    const call = fetcher.mock.calls.find((c) => c[0].endsWith("/simulate"));
    expect(JSON.parse(call![1]!.body as string)).toEqual({
      headcount_delta: 1,
      monthly_cost_amt: 10,
      months: 3,
    });
  });
  it("monthly/dispatch simulation includes price", async () => {
    row.cntrct_ty_cd = "MO";
    mount();
    await simulation();
    fill();
    fireEvent.change(screen.getByLabelText("1인 월 단가"), {
      target: { value: "15" },
    });
    fireEvent.click(screen.getByTestId("project-simulation-calculate"));
    await screen.findByTestId("project-simulation-result");
    expect(
      JSON.parse(
        fetcher.mock.calls.find((c) => c[0].endsWith("/simulate"))![1]!
          .body as string,
      ).monthly_price_amt,
    ).toBe(15);
  });
  it("retains simulation inputs and singular server field on error", async () => {
    simulationError = true;
    mount();
    await simulation();
    fill();
    fireEvent.click(screen.getByTestId("project-simulation-calculate"));
    await screen.findAllByText("현재 인원을 확인하세요.");
    expect(
      screen.getByTestId("project-simulation-headcount-delta"),
    ).toHaveValue("1");
    expect(screen.getByText("문의 번호 project-request")).toBeVisible();
  });
  it("validates required whole-won input before making a request", async () => {
    mount();
    await simulation();
    fireEvent.click(screen.getByTestId("project-simulation-calculate"));
    await screen.findAllByText("정수 입력값과 범위를 확인하세요.");
    expect(fetcher.mock.calls.some((c) => c[0].endsWith("/simulate"))).toBe(
      false,
    );
  });
  it("closed month hides simulation controls", async () => {
    detail.monthly_rows[0].status = "CLOSED";
    mount();
    await simulation();
    expect(screen.getByTestId("project-simulation-locked")).toBeVisible();
    expect(
      screen.queryByTestId("project-simulation-calculate"),
    ).not.toBeInTheDocument();
  });
  it("past year hides simulation controls", async () => {
    mount("/UI-04?year=2025");
    await simulation();
    expect(screen.getByTestId("project-simulation-locked")).toBeVisible();
  });
  it("causes show negative residual, PM, primary/secondary and approval-pending destination", async () => {
    mount();
    await open();
    fireEvent.click(screen.getByTestId("project-costs-tab-3"));
    await screen.findByTestId("project-cause-excess-mm");
    expect(screen.getByTestId("project-causes-panel")).toHaveTextContent("-10");
    expect(screen.getByText("주원인").className).toContain("danger");
    expect(screen.getByText("보조 정보")).toBeVisible();
    expect(screen.getByText("단가 재협상 · 승인 대기")).toBeVisible();
    expect(
      screen.queryByRole("link", { name: "단가 재협상" }),
    ).not.toBeInTheDocument();
  });
  it("updates an already mounted cost screen from a new project/year deep link", async () => {
    render(
      <MemoryRouter initialEntries={["/UI-04"]}>
        <UI04Screen />
        <Link to="/UI-04?projectId=17&year=2025">다른 원가 분석 열기</Link>
      </MemoryRouter>,
    );
    await screen.findByTestId("project-costs-row-17");
    fireEvent.click(screen.getByRole("link", { name: "다른 원가 분석 열기" }));
    await screen.findByTestId("project-costs-detail-panel");
    expect(screen.getByText("2025년")).toBeVisible();
    expect(
      fetcher.mock.calls.some(
        ([url]) => url === "/api/costs/projects/17?year=2025",
      ),
    ).toBe(true);
    expect(sessionStorage.getItem("UI-04.year")).toBe("2025");
  });
  it("updates an already mounted comparison screen from a cost-cause deep link", async () => {
    render(
      <MemoryRouter initialEntries={["/UI-11"]}>
        <UI11Screen />
        <Link to="/UI-11?projectId=17&year=2025">원인 프로젝트 비교</Link>
      </MemoryRouter>,
    );
    await screen.findByTestId("comparison-project");
    fireEvent.click(screen.getByRole("link", { name: "원인 프로젝트 비교" }));
    await waitFor(() => {
      expect(screen.getByTestId("comparison-project")).toHaveValue("17");
      expect(
        fetcher.mock.calls.some(
          ([url]) =>
            url.startsWith("/api/plan-vs-actual?") &&
            url.includes("projectId=17") &&
            url.includes("year=2025"),
        ),
      ).toBe(true);
    });
    expect(sessionStorage.getItem("UI-11.year")).toBe("2025");
  });
  it("UI11 actually initializes both project and year filters from causes navigation", async () => {
    mount();
    await open();
    fireEvent.click(screen.getByTestId("project-costs-tab-3"));
    fireEvent.click(await screen.findByTestId("project-cause-link-excess-mm"));
    await screen.findByTestId("comparison-project");
    expect(screen.getByTestId("location")).toHaveTextContent(
      "/UI-11?projectId=17&year=2026",
    );
    await waitFor(() =>
      expect(
        fetcher.mock.calls.some(
          (c) =>
            c[0].includes("projectId=17") &&
            c[0].includes("year=2026") &&
            !c[0].includes("costs"),
        ),
      ).toBe(true),
    );
    await waitFor(() =>
      expect(screen.getByTestId("comparison-project")).toHaveValue("17"),
    );
  });
  it("inclusive 0.10MM tolerance is separate from cost and margin warnings", () => {
    expect(MmGapJudge(2, 2.1, 0.1)).toBe("MATCH");
    expect(MmGapJudge(2, 1.9, 0.1)).toBe("MATCH");
    expect(MmGapJudge(2, 2.11, 0.1)).toBe("OVER");
    expect(MmGapJudge(2, 1.89, 0.1)).toBe("SHORT");
  });
});
