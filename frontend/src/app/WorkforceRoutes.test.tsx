import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { MemoryRouter, useLocation, useNavigate } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { AppRouter } from "./router";
import { useSharedPreferences } from "../pages/shared/preferences";
import {
  response,
  failure,
  codeFixtures,
} from "../pages/developers/DeveloperTestSupport";
import type { OpportunityDetail } from "../pages/opportunities/client";
import * as client from "../api/apiClient";

const menus = [
  {
    menuId: 1,
    name: "인력 운영",
    screenId: null,
    icon: null,
    mobile: true,
    children: [
      ["UI-16", "대시보드"],
      ["UI-01", "프로젝트"],
      ["UI-17", "인력 수요 예측"],
      ["UI-28", "인력 운영 보드"],
      ["UI-29", "영업 파이프라인"],
      ["UI-33", "외부 인력 후보"],
      ["UI-05", "철수 리스트"],
    ].map(([screenId, name], i) => ({
      menuId: i + 2,
      screenId,
      name,
      mobile: true,
      icon: null,
      children: [],
    })),
  },
];
const workforce = {
  items: [],
  total: 0,
  page: 0,
  size: 100,
  months: ["2026-12"],
  shortages: [
    {
      job_cd: "BE",
      grd_cd: "SR",
      maximum_shortage_mm: 1,
      maximum_shortage_people: 1,
      first_shortage_month: "2026-12",
      recommended_hiring_month: "2026-10",
      review_immediately: true,
      candidate_count: 1,
    },
  ],
  target_utilization_rate: 90,
  message: null,
};
let row: OpportunityDetail;
let fetcher: ReturnType<typeof vi.fn>;
let failBoard: boolean;
function Navigation() {
  const navigate = useNavigate(),
    location = useLocation();
  return (
    <>
      <output aria-label="route">{location.pathname}</output>
      <button onClick={() => navigate("/UI-33")}>직접 프로젝트 등록</button>
      <button onClick={() => navigate("/UI-01")}>프로젝트 목록 이동</button>
      <button
        onClick={() => {
          const target = client.candidateNavigation(workforce.shortages[0]);
          navigate(target.pathname, { state: target.state });
        }}
      >
        부족 후보 이동
      </button>
    </>
  );
}
const mount = (pathname: string, state?: unknown) =>
  render(
    <MemoryRouter initialEntries={[{ pathname, state }]}>
      <Navigation />
      <AppRouter />
    </MemoryRouter>,
  );
const candidateState = () =>
  client.candidateWorkspaceState({
    job: "BE",
    grade: "SR",
    shortage_month: "2026-12",
  });
const reads = (path: string) =>
  fetcher.mock.calls.filter(([url]) => String(url).startsWith(path)).length;
beforeEach(() => {
  vi.stubGlobal("innerWidth", 1280);
  vi.useFakeTimers({ toFake: ["Date"] });
  vi.setSystemTime(new Date("2026-11-03T00:00:00Z"));
  localStorage.clear();
  sessionStorage.clear();
  failBoard = false;
  useSharedPreferences.setState({
    favorites: [],
    recent: [],
    collapsed: false,
    pageSize: 50,
    dense: false,
  });
  row = {
    oppt_id: 101,
    oppt_no: "O-101",
    oppt_nm: "연결 기회",
    cust_ptnr_id: 1,
    bsn_part_id: 1,
    owner_user_id: null,
    bsn_stg_cd: "DISC",
    win_prblt_rt: 10,
    expc_amt: 3000000,
    cntrct_ty_cd: "MO",
    bgn_ym: "2026-12",
    mth_cnt: 2,
    next_action: null,
    next_action_dt: null,
    rmrk_desc: null,
    oppt_stts_cd: "OPEN",
    win_prj_id: null,
    updated_at: null,
    created_at: "2026-11-01T00:00:00",
    stale_warning: false,
    warnings: [],
    needs: [
      {
        need_id: 303,
        job_cd: "BE",
        grd_cd: "SR",
        psn_cnt: 1,
        bgn_ym: "2026-12",
        mth_cnt: 2,
        mth_mm_qty: 0.5,
        expc_untprc_amt: 3000000,
        rmrk_desc: null,
        psn_mth_cst_amt: 1800000,
        min_untprc_amt: 1800000,
        trgt_untprc_amt: 2250000,
        warnings: [],
      },
    ],
    quote: {
      expc_sls_amt: 3000000,
      expc_cst_amt: 1800000,
      expc_mrgn_rt: 40,
      minimum_amount: 1800000,
      amount_difference: 0,
      amount_difference_rate: 0,
      cost_status: "VERIFIED",
      approval_state: "PENDING",
      approved_quote_amount: null,
      warnings: [],
    },
    approval_history: [],
  };
  fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (url === "/api/menus") return response(menus);
    if (url.startsWith("/api/codes/"))
      return response(codeFixtures[url.split("/").at(-1)!] ?? []);
    if (url.startsWith("/api/partners/options") || url.includes("/options"))
      return response([]);
    if (url.startsWith("/api/params/")) return response({ num_val: 80 });
    if (url.startsWith("/api/month-close")) return response([]);
    if (url.startsWith("/api/projects?"))
      return response({
        items: [],
        total: 0,
        page: 0,
        size: 50,
        unassignedCount: 0,
      });
    if (url.startsWith("/api/candidates/shortages"))
      return response({ items: [], total: 0, page: 0, size: 50 });
    if (url.startsWith("/api/candidates?"))
      return response({
        items: [],
        total: 0,
        page: 0,
        size: 50,
        kpi: {
          candidate_count: 0,
          interview_count: 0,
          available_by_next_month_count: 0,
          uncontacted_over_30_days_count: 0,
        },
      });
    if (url.startsWith("/api/workforce/board?"))
      return failBoard
        ? failure(null, 500)
        : response({
            items: [],
            total: 0,
            page: 0,
            size: 50,
            monthly_totals: Array.from({ length: 12 }, (_, i) => ({
              month: `2026-${String(i + 1).padStart(2, "0")}`,
              free_people: 0,
              remaining_mm: 0,
            })),
          });
    if (url.startsWith("/api/workforce/forecast"))
      return response({
        ...workforce,
        items: [
          {
            month: "2026-12",
            job_cd: "BE",
            grd_cd: "SR",
            supply_mm: 0,
            demand_mm: 1,
            remaining_mm: -1,
            shortage_mm: 1,
            available_mm: 1,
            confirmed_mm: 0,
            extension_mm: 0,
            temporary_mm: 0,
            utilization_rate: 0,
            idle_cost: 0,
            estimated: true,
            baseline_operating_profit: null,
            average_labor_cost: 0,
            warnings: [],
          },
        ],
        total: 1,
      });
    if (url.startsWith("/api/opportunities/forecast"))
      return response({
        items: [],
        total: 0,
        page: 0,
        size: 50,
        workforce,
        kpi: {
          weighted_total: 0,
          opportunity_total: 0,
          all_won_total: 0,
          average_demand_mm: 1,
          average_supply_mm: 0,
          hiring_job_count: 1,
        },
      });
    if (url.startsWith("/api/opportunities?"))
      return response({
        items: [row],
        total: 1,
        page: 0,
        size: 50,
        kpi: {
          open_count: 1,
          expected_amount: row.expc_amt,
          weighted_amount: 300000,
          needed_people: 1,
          stale_count: 0,
        },
      });
    if (url === "/api/opportunities/101/stage") {
      row.bsn_stg_cd = JSON.parse(String(init?.body)).bsn_stg_cd;
      return response(row);
    }
    if (url === "/api/opportunities/101/approve") {
      row.quote.approval_state = "APPROVED";
      return response(row);
    }
    if (url === "/api/opportunities/101") return response(row);
    if (url.startsWith("/api/temp-assignments?"))
      return response({ items: [], total: 0, page: 0, size: 50 });
    throw new Error(`Unexpected request ${url}`);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("workforce route registration", () => {
  it("direct UI-33 retains project registration and its draft across candidate menu navigation", async () => {
    mount("/UI-33");
    const input = await screen.findByLabelText("프로젝트명", { exact: true });
    fireEvent.change(input, { target: { value: "보존 프로젝트" } });
    fireEvent.click(
      within(screen.getByRole("navigation", { name: "화면 탐색" })).getByRole(
        "button",
        { name: "외부 인력 후보" },
      ),
    );
    expect(await screen.findByTestId("candidate-workspace")).toBeVisible();
    expect(screen.getByLabelText("프로젝트명", { exact: true })).toHaveValue(
      "보존 프로젝트",
    );
    fireEvent.change(screen.getByTestId("candidate-filter-name"), {
      target: { value: "후보 검색" },
    });
    fireEvent.click(
      within(screen.getByLabelText("열린 화면")).getByRole("button", {
        name: /프로젝트$/,
      }),
    );
    expect(screen.getByLabelText("프로젝트명", { exact: true })).toBeVisible();
    fireEvent.click(
      within(screen.getByLabelText("열린 화면")).getByRole("button", {
        name: "외부 인력 후보",
      }),
    );
    expect(screen.getByTestId("candidate-filter-name")).toHaveValue(
      "후보 검색",
    );
    expect(screen.getByLabelText("route")).toHaveTextContent("/UI-33");
  });
  it("refresh-shaped candidate history state restores shortage filters", async () => {
    mount("/UI-33", candidateState());
    expect(await screen.findByTestId("candidate-workspace")).toBeVisible();
    expect(
      screen.queryByLabelText("프로젝트명", { exact: true }),
    ).not.toBeInTheDocument();
    await waitFor(() =>
      expect(screen.getByLabelText("조회 직무")).toHaveValue("BE"),
    );
    expect(screen.getByLabelText("조회 등급")).toHaveValue("SR");
    expect(screen.getByTestId("candidate-filter-month")).toHaveValue("2026-12");
  });
  it("candidate navigation does not turn an already mounted project list into registration", async () => {
    mount("/UI-01");
    await screen.findByTestId("project-workspace");
    fireEvent.click(screen.getByText("부족 후보 이동"));
    expect(await screen.findByTestId("candidate-workspace")).toBeVisible();
    expect(
      screen.queryByLabelText("프로젝트명", { exact: true }),
    ).not.toBeInTheDocument();
  });
  it("UI-17 shortage links open the candidate context with job grade and shortage month", async () => {
    mount("/UI-17");
    fireEvent.click(await screen.findByTestId("demand-candidates-be-sr"));
    expect(await screen.findByTestId("candidate-workspace")).toBeVisible();
    await waitFor(() =>
      expect(screen.getByLabelText("조회 직무")).toHaveValue("BE"),
    );
    expect(screen.getByTestId("candidate-filter-month")).toHaveValue("2026-12");
  });
  it("UI-28 renders the real calendar and requeries temporary assignment changes", async () => {
    mount("/UI-28");
    await screen.findByText("12월");
    expect(screen.getByTestId("board-calendar-panel")).toBeVisible();
    const before = reads("/api/workforce/board?");
    act(() => window.dispatchEvent(new Event(client.TEMP_ASSIGNMENTS_CHANGED)));
    await waitFor(() =>
      expect(reads("/api/workforce/board?")).toBeGreaterThan(before),
    );
  });
  it("UI-28 preserves API failure and retry rather than a placeholder", async () => {
    failBoard = true;
    mount("/UI-28");
    await screen.findByText(/문의 번호 developer-test/);
    failBoard = false;
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    await screen.findByText("12월");
  });
  it("UI-29 mounts the full-screen editor from its existing primary action", async () => {
    mount("/UI-29");
    fireEvent.click(await screen.findByTestId("opportunity-create"));
    expect(await screen.findByTestId("opportunity-editor")).toBeVisible();
  });
  it("UI-29 connects pending-only approval and refreshes the selected detail", async () => {
    mount("/UI-29");
    fireEvent.click(await screen.findByTestId("opportunity-card-101"));
    fireEvent.click(await screen.findByTestId("opportunity-approve"));
    fireEvent.click(screen.getByTestId("opportunity-action-save"));
    await waitFor(() =>
      expect(
        screen.queryByTestId("opportunity-action-dialog"),
      ).not.toBeInTheDocument(),
    );
    expect(screen.queryByTestId("opportunity-approve")).not.toBeInTheDocument();
    expect(
      fetcher.mock.calls.some(
        ([url, init]) =>
          url === "/api/opportunities/101/approve" && init?.method === "POST",
      ),
    ).toBe(true);
    expect(reads("/api/opportunities/101")).toBeGreaterThan(1);
  });
  it("UI-29 binds need identity and inclusive period to temporary assignments", async () => {
    mount("/UI-29");
    fireEvent.click(await screen.findByTestId("opportunity-card-101"));
    fireEvent.click(await screen.findByRole("tab", { name: "가배정" }));
    expect(await screen.findByTestId("temp-assignment-panel")).toBeVisible();
    await waitFor(() =>
      expect(
        fetcher.mock.calls.some(([url]) => String(url).includes("need_id=303")),
      ).toBe(true),
    );
  });
  it("UI-29 forecast links retain the same candidate filters", async () => {
    mount("/UI-29");
    fireEvent.click(await screen.findByRole("tab", { name: "매출·인력 예측" }));
    fireEvent.click(await screen.findByTestId("forecast-candidates-be-sr"));
    expect(await screen.findByTestId("candidate-workspace")).toBeVisible();
    expect(screen.getByTestId("candidate-filter-month")).toHaveValue("2026-12");
  });
  it("the shared registry exposes feature clients without reverse import cycles", async () => {
    await client.getWorkforceBoard({
      year: 2026,
      sort: "dev_id,asc",
      page: 0,
      size: 50,
    });
    await client.listTempAssignments({ need_id: 303 });
    await client.getCandidateShortages({ job: "BE" });
    expect(reads("/api/workforce/board?")).toBe(1);
    expect(reads("/api/temp-assignments?")).toBe(1);
    expect(
      fetcher.mock.calls.some(([url]) =>
        String(url).includes("/api/candidates/shortages?job=BE"),
      ),
    ).toBe(true);
  });
});
