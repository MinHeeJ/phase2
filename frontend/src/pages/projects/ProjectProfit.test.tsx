import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { Link, MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import UI04Screen from "./UI04Screen";
import UI11Screen from "../us4/UI11Screen";
import { response, failure } from "../developers/DeveloperTestSupport";
import { MmGapJudge } from "../../shared/MmGapJudge";
import type { CostProjectDetail, CostProjectPage } from "./profitClient";
import table from "../developers/Developers.module.css";

let detail: CostProjectDetail;
let page: CostProjectPage;
let fetcher: ReturnType<typeof vi.fn>;
let failed: string;
beforeEach(() => {
  vi.useFakeTimers({ toFake: ["Date"] });
  vi.setSystemTime(new Date("2026-10-23T00:00:00Z"));
  failed = "";
  const summary: CostProjectDetail["summary"] = {
    project: {
      prj_id: 7,
      prj_no: "P-2607",
      prj_nm: "사업 원가",
      cntrct_ty_cd: "TK",
      prj_stts_cd: "RUN",
      part_name: "본사",
      pm_name: "담당 PM",
      init_cntrct_amt: 500,
      cur_cntrct_amt: 500,
      trgt_cst_amt: 400,
      actl_prgs_rt: 50,
    },
    plan_mm: 2,
    actual_mm: 3,
    revenue: 200,
    cost: 320,
    profit: -120,
    margin: -60,
    forecast_cost: null,
    estimated_margin: null,
    target_margin: 20,
    target_difference: null,
    budget_rate: 80,
    budget_alert: "WARN",
    low_margin: true,
    revenue_per_mm: 67,
    cost_per_mm: 107,
    profit_per_mm: -40,
    free_mm: 0.5,
    free_cost: 40,
    rnd_cost: 0,
    primary_cause: "STAFFING",
    warnings: ["예상 최종 원가 근거가 없습니다."],
  };
  detail = {
    summary,
    through: "2026-10",
    period_note: "연초부터 기준월까지. 기존 진행 현황은 전체 누적입니다.",
    months: [
      {
        month: "2026-10",
        revenue: 200,
        labor: 300,
        expenses: 20,
        cost: 320,
        mm: 3,
        free_mm: 0.5,
        free_cost: 40,
        margin: -60,
        budget_remaining: 80,
        reference_revenue: 400,
        closed: false,
        warnings: [],
      },
    ],
    people: [
      {
        month: "2026-10",
        dev_id: 11,
        dev_nm: "개발자",
        asgn_id: 12,
        mm_qty: 0.5,
        lbr_cst_amt: 40,
        drct_expns_amt: 0,
        reference_revenue: 0,
        free_sprt: true,
        pre_cntrct: true,
      },
    ],
    causes: {
      prj_id: 7,
      year: 2026,
      primary_cause: "STAFFING",
      progress_excess: 120,
      residual: -26,
      warnings: [],
      items: [
        {
          code: "STAFFING",
          label: "투입 초과",
          amount: 106,
          basis: "실제−계획 MM × MM당 원가",
          owner: "담당 PM",
          recommendation: "계획 확인",
          route: "/UI-11?projectId=7&year=2026",
          unavailable: null,
        },
        {
          code: "PRICING",
          label: "단가 대비 원가",
          amount: 0,
          basis: "사람·월별 비교",
          owner: "담당 PM",
          recommendation: "단가 확인",
          route: null,
          unavailable:
            "단가 재협상 화면은 기존 /UI-32 개발자 등록과 주소가 충돌하여 이동할 수 없습니다.",
        },
        {
          code: "FREE",
          label: "무상지원",
          amount: 40,
          basis: "이미 총원가에 포함",
          owner: "담당 PM",
          recommendation: "계약 확인",
          route: "/UI-04?open=7",
          unavailable: null,
        },
      ],
    },
  };
  page = {
    items: [summary],
    total: 1,
    page: 0,
    size: 50,
    through: "2026-10",
    summary: {
      revenue: 200,
      profit: -120,
      low_margin_count: 1,
      budget_risk_count: 0,
      rnd_cost: 0,
    },
  };
  fetcher = vi.fn(async (path: string) => {
    if (failed && path.includes(failed)) return failure("year");
    if (path.startsWith("/api/codes/"))
      return response([{ code: "TK", name: "턴키" }]);
    if (path === "/api/sales-parts/options")
      return response([{ id: "1", name: "본사" }]);
    if (path === "/api/params")
      return response([{ oprt_std_id: "PLAN_GAP_TOLERANCE", num_val: 0.1 }]);
    if (path.startsWith("/api/costs/projects/7/causes"))
      return response(detail.causes);
    if (path.startsWith("/api/costs/projects/7?")) return response(detail);
    if (path.startsWith("/api/costs/projects?")) return response(page);
    if (path.startsWith("/api/plan-vs-actual?"))
      return new Response(
        JSON.stringify({
          success: true,
          meta: { today: "2026-10-23", projects: [] },
          data: {
            rows: [],
            total: 0,
            page: 0,
            size: 50,
            totals: { planMm: 0, actualMm: 0 },
          },
        }),
      );
    throw new Error(path);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});
const mount = () =>
  render(
    <MemoryRouter initialEntries={["/UI-04"]}>
      <UI04Screen />
    </MemoryRouter>,
  );
async function open() {
  mount();
  fireEvent.click(await screen.findByTestId("cost-projects-open-7"));
  return screen.findByTestId("cost-project-detail");
}

describe("SCR-UI-04", () => {
  it("shows API-backed summary, warning colours and original developer table style", async () => {
    mount();
    const row = await screen.findByTestId("cost-projects-row-7");
    expect(within(row).getByText("예산 주의").className).toContain("warning");
    expect(within(row).getByText("저마진").className).toContain("danger");
    expect(row.closest("table")).toHaveClass(table.table);
    expect(screen.getByTestId("cost-projects-summary")).toHaveTextContent(
      "누적 매출 200",
    );
    expect(screen.getByTestId("cost-projects-download")).toBeDisabled();
  });
  it("shows risk separately from warning", async () => {
    page.items[0].budget_alert = "RISK";
    mount();
    const row = await screen.findByTestId("cost-projects-row-7");
    expect(within(row).getByText("예산 초과").className).toContain("danger");
    expect(screen.queryByText("예산 주의")).not.toBeInTheDocument();
  });
  it("hides internal contract revenue margins and displays research cost", async () => {
    page.items[0].project.cntrct_ty_cd = "IN";
    page.items[0].revenue = null;
    page.items[0].profit = null;
    page.items[0].margin = null;
    page.items[0].low_margin = false;
    page.items[0].budget_alert = "UNAVAILABLE";
    mount();
    const row = await screen.findByTestId("cost-projects-row-7");
    expect(row).toHaveTextContent("연구개발비");
    expect(within(row).queryByText("500")).not.toBeInTheDocument();
    expect(within(row).queryByText("저마진")).not.toBeInTheDocument();
  });
  it("keeps query year synchronized with the one shared period bar", async () => {
    mount();
    await screen.findByTestId("cost-projects-row-7");
    fireEvent.click(screen.getByRole("button", { name: "이전 연도" }));
    await waitFor(() =>
      expect(
        fetcher.mock.calls.some(([path]) => String(path).includes("year=2025")),
      ).toBe(true),
    );
    expect(screen.getAllByRole("region", { name: "조회 기간" })).toHaveLength(
      1,
    );
    fireEvent.click(screen.getByRole("button", { name: "다음 연도" }));
    await waitFor(() => expect(screen.getByText("2026년")).toBeVisible());
  });
  it("sends filters using approved query names", async () => {
    mount();
    await screen.findByTestId("cost-projects-row-7");
    fireEvent.change(screen.getByTestId("cost-projects-part"), {
      target: { value: "1" },
    });
    fireEvent.change(screen.getByTestId("cost-projects-search"), {
      target: { value: "사업" },
    });
    fireEvent.click(screen.getByTestId("cost-projects-query"));
    await waitFor(() =>
      expect(
        fetcher.mock.calls.some(
          ([path]) =>
            String(path).includes("partId=1") &&
            String(path).includes("q=%EC%82%AC%EC%97%85"),
        ),
      ).toBe(true),
    );
  });
  it("empty list is a real empty state", async () => {
    page.items = [];
    page.total = 0;
    mount();
    expect(
      await screen.findByTestId("cost-projects-empty-row"),
    ).toHaveTextContent("조회 결과가 없습니다");
  });
  it("failed request renders singular field and retry preserves search", async () => {
    failed = "/api/costs/projects?";
    mount();
    await screen.findByText("year: 입력 오류");
    fireEvent.change(screen.getByTestId("cost-projects-search"), {
      target: { value: "보존" },
    });
    failed = "";
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    await screen.findByTestId("cost-projects-row-7");
    expect(screen.getByTestId("cost-projects-search")).toHaveValue("보존");
  });
  it("drawer shows month cost, reference revenue separately and selected contract link", async () => {
    const drawer = await open();
    await within(drawer).findByText("2026-10");
    expect(
      within(drawer).getByTestId("cost-project-contract-link"),
    ).toHaveAttribute("href", "/UI-01?open=7");
    expect(
      within(drawer).getByTestId("cost-project-month-2026-10"),
    ).toHaveTextContent("320");
    fireEvent.click(screen.getByTestId("cost-project-tab-people"));
    expect(screen.getByTestId("cost-project-person-0")).toHaveTextContent(
      "무상지원",
    );
    expect(screen.getByTestId("cost-project-person-0")).toHaveTextContent(
      "미계약 투입",
    );
  });
  it("empty personnel tab", async () => {
    detail.people = [];
    await open();
    await screen.findByTestId("cost-project-tab-people");
    fireEvent.click(screen.getByTestId("cost-project-tab-people"));
    expect(screen.getByTestId("cost-project-people-empty")).toHaveTextContent(
      "없습니다",
    );
  });
  it("blocked simulation preserves input and performs no POST", async () => {
    await open();
    fireEvent.click(await screen.findByTestId("cost-project-tab-simulate"));
    fireEvent.change(screen.getByTestId("cost-project-scenario-cost"), {
      target: { value: "100" },
    });
    expect(screen.getByTestId("cost-project-simulate")).toBeDisabled();
    fireEvent.click(screen.getByTestId("cost-project-tab-months"));
    await screen.findByTestId("cost-project-panel-months");
    fireEvent.click(screen.getByTestId("cost-project-tab-simulate"));
    expect(screen.getByTestId("cost-project-scenario-cost")).toHaveValue(100);
    expect(
      fetcher.mock.calls.some(([path]) => String(path).includes("simulate")),
    ).toBe(false);
  });
  it("only MO DP simulation shows a monthly price input", async () => {
    detail.summary.project.cntrct_ty_cd = "MO";
    await open();
    fireEvent.click(await screen.findByTestId("cost-project-tab-simulate"));
    expect(screen.getByTestId("cost-project-scenario-price")).toBeVisible();
  });
  it("causes show residual, owner, zero supplement and no wrong renegotiation route", async () => {
    await open();
    fireEvent.click(await screen.findByTestId("cost-project-tab-causes"));
    const row = await screen.findByTestId("cost-project-cause-staffing");
    expect(row).toHaveTextContent("담당 PM");
    expect(row).toHaveTextContent("주원인");
    expect(screen.getByTestId("cost-project-panel-causes")).toHaveTextContent(
      "-26",
    );
    expect(screen.getByTestId("cost-project-cause-pricing")).toHaveTextContent(
      "보조 정보",
    );
    expect(screen.getByTestId("cost-project-plan-link")).toHaveAttribute(
      "href",
      "/UI-11?projectId=7&year=2026",
    );
    expect(
      screen.queryByRole("link", { name: "단가 재협상" }),
    ).not.toBeInTheDocument();
    expect(screen.getByText(/주소가 충돌/)).toBeVisible();
  });
  it("cause errors can be retried independently", async () => {
    failed = "/causes";
    await open();
    fireEvent.click(await screen.findByTestId("cost-project-tab-causes"));
    await screen.findByText("year: 입력 오류");
    failed = "";
    fireEvent.click(
      within(screen.getByTestId("cost-project-panel-causes")).getByRole(
        "button",
        { name: "다시 시도" },
      ),
    );
    expect(
      await screen.findByTestId("cost-project-cause-staffing"),
    ).toBeVisible();
  });
  it("kept-alive cost analysis only consumes open on its own route", async () => {
    render(
      <MemoryRouter initialEntries={["/UI-01?open=7"]}>
        <Link data-testid="visit-cost-analysis" to="/UI-04?open=7">
          원가 분석으로 이동
        </Link>
        <UI04Screen />
      </MemoryRouter>,
    );
    await screen.findByTestId("cost-projects-row-7");
    expect(screen.queryByTestId("cost-project-detail")).not.toBeInTheDocument();
    expect(
      fetcher.mock.calls.some(([path]) =>
        String(path).startsWith("/api/costs/projects/7?"),
      ),
    ).toBe(false);
    fireEvent.click(screen.getByTestId("visit-cost-analysis"));
    await screen.findByTestId("cost-project-month-2026-10");
    expect(screen.getByTestId("cost-project-detail")).toBeVisible();
  });
  it("kept-alive UI-11 ignores another route's project and applies it on entry", async () => {
    render(
      <MemoryRouter initialEntries={["/UI-12?projectId=7&year=2025"]}>
        <Link
          data-testid="visit-plan-comparison"
          to="/UI-11?projectId=7&year=2025"
        >
          계획 대비 투입으로 이동
        </Link>
        <UI11Screen />
      </MemoryRouter>,
    );
    await screen.findByTestId("comparison-totals");
    expect(
      fetcher.mock.calls.some(
        ([path]) =>
          String(path).startsWith("/api/plan-vs-actual?") &&
          String(path).includes("projectId=7"),
      ),
    ).toBe(false);
    fireEvent.click(screen.getByTestId("visit-plan-comparison"));
    await waitFor(() =>
      expect(
        fetcher.mock.calls.some(
          ([path]) =>
            String(path).startsWith("/api/plan-vs-actual?") &&
            String(path).includes("projectId=7") &&
            String(path).includes("year=2025"),
        ),
      ).toBe(true),
    );
  });
  it("UI-11 consumes selected project and year", async () => {
    render(
      <MemoryRouter initialEntries={["/UI-11?projectId=7&year=2025"]}>
        <UI11Screen />
      </MemoryRouter>,
    );
    await waitFor(() =>
      expect(
        fetcher.mock.calls.some(
          ([path]) =>
            String(path).startsWith("/api/plan-vs-actual?") &&
            String(path).includes("projectId=7") &&
            String(path).includes("year=2025"),
        ),
      ).toBe(true),
    );
  });
  it.each([
    [2, 2.1, "MATCH"],
    [2, 2.11, "OVER"],
    [2, 1.9, "MATCH"],
    [2, 1.89, "SHORT"],
  ])("separate 0.10MM tolerance %s %s", (plan, actual, result) => {
    expect(MmGapJudge(Number(plan), Number(actual), 0.1)).toBe(result);
  });
});
