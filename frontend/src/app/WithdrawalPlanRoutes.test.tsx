import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { MemoryRouter, useLocation } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { AppRouter } from "./router";
import { useSharedPreferences } from "../pages/shared/preferences";
import { emptyProject } from "../pages/projects/client";
import { response } from "../pages/developers/DeveloperTestSupport";
import * as registry from "../api/apiClient";
import * as withdrawals from "../pages/us1/client";
import * as annual from "../pages/us4/client";
import * as monthly from "../pages/us5/client";

const menus = [
  {
    menuId: 1,
    name: "프로젝트·투입",
    screenId: null,
    icon: null,
    mobile: true,
    children: ["UI-16", "UI-01", "UI-05", "UI-11", "UI-12"].map(
      (screenId, i) => ({
        menuId: i + 2,
        name: screenId,
        screenId,
        icon: null,
        mobile: true,
        children: [],
      }),
    ),
  },
];

let row: withdrawals.WithdrawalRow;
let fetcher: ReturnType<typeof vi.fn>;
let empty: boolean, failed: boolean, stale: boolean;
const project = {
  ...emptyProject,
  prj_id: 3,
  prj_no: "P-2603",
  prj_nm: "비교 프로젝트",
  cntrct_ty_cd: "MO",
  prj_stts_cd: "RUN",
  use_yn: "Y",
  cntrct_bgn_dt: "2026-01-01",
  cntrct_end_dt: "2026-12-31",
  cur_end_dt: "2026-12-31",
  assignmentCount: 1,
  custName: null,
  partName: null,
  pmName: null,
};
function Location() {
  const location = useLocation();
  return (
    <output aria-label="현재 주소">
      {location.pathname + location.search} {JSON.stringify(location.state)}
    </output>
  );
}
function mount(path = "/UI-05") {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Location />
      <AppRouter />
    </MemoryRouter>,
  );
}
const calls = (prefix: string) =>
  fetcher.mock.calls.filter(([url]) => String(url).startsWith(prefix));
const query = (prefix: string) =>
  new URLSearchParams(String(calls(prefix).at(-1)?.[0]).split("?")[1]);
const writes = () =>
  fetcher.mock.calls.filter(
    ([, init]) => init?.method && init.method !== "GET",
  );
beforeEach(() => {
  vi.stubGlobal("innerWidth", 1280);
  vi.useFakeTimers({ toFake: ["Date"] });
  vi.setSystemTime(new Date("2026-10-14T00:00:00Z"));
  localStorage.clear();
  sessionStorage.clear();
  useSharedPreferences.setState({
    favorites: [],
    recent: [],
    collapsed: false,
    pageSize: 50,
    dense: false,
  });
  empty = false;
  failed = false;
  stale = false;
  row = {
    asgn_id: 7,
    asgn_no: "A-0007",
    dev_id: 2,
    dev_nm: "개발자",
    mng_no: "K-0002",
    prj_id: 3,
    prj_nm: project.prj_nm,
    job_cd: "BE",
    job_nm: "백엔드",
    bgn_dt: "2024-11-20",
    end_dt: "2026-11-19",
    plan_end_dt: "2026-11-19",
    ext_psblty_cd: null,
    ext_psblty_nm: "미정",
    asgn_stts_cd: "ACTIVE",
    prj_stts_cd: "RUN",
    use_yn: "Y",
    cntrct_ty_cd: "DP",
    updated_at: "2026-10-14T00:00:00.123456",
    extend_count: 0,
    early_end: false,
    replacement_name: null,
    remaining_days: 36,
    dispatch_first_dt: "2024-11-20",
    dispatch_expiry_dt: "2026-11-19",
    dispatch_display: "파견 만료 D-36",
    dispatch_tone: "warning",
    dispatch_alert_dates: ["2026-09-20", "2026-10-20"],
    next_asgn_id: null,
    next_prj_nm: null,
    next_bgn_dt: null,
    next_display: "없음",
    chance_locked: false,
    chance_lock_reason: null,
  };
  fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (url === "/api/menus") return response(menus);
    if (url.startsWith("/api/codes/"))
      return response(
        url.endsWith("JOB")
          ? [{ code: "BE", name: "백엔드" }]
          : url.endsWith("EXT_POSSIBILITY")
            ? [
                { code: "H", name: "높음" },
                { code: "M", name: "보통" },
              ]
            : [],
      );
    if (url === "/api/params")
      return response([{ oprt_std_id: "PLAN_GAP_TOLERANCE", num_val: 0.1 }]);
    if (url.startsWith("/api/withdrawals?")) {
      if (failed) throw new Error("offline");
      return new Response(
        JSON.stringify({
          success: true,
          meta: { today: "2026-10-14" },
          data: {
            rows: empty ? [] : [row],
            total: empty ? 0 : 1,
            page: 0,
            size: 50,
            summary: {
              soonPeople: 1,
              noNextPeople: row.ext_psblty_cd === "H" ? 0 : 1,
            },
          },
        }),
        { headers: { "Content-Type": "application/json" } },
      );
    }
    if (url === "/api/assignments/7/changes" && init?.method === "POST") {
      if (stale)
        return new Response(
          JSON.stringify({
            success: false,
            code: "STALE_ASSIGNMENT",
            message: "충돌",
          }),
          { status: 409 },
        );
      const body = JSON.parse(String(init.body));
      row = {
        ...row,
        ext_psblty_cd: body.ext_psblty_cd,
        ext_psblty_nm: "높음",
        updated_at: "2026-10-14T00:00:01.123456",
      };
      return response({
        assignment: row,
        change: { asgn_chg_ty_cd: "EXT_CHANCE" },
      });
    }
    if (url.startsWith("/api/plan-vs-actual/shortages?"))
      return response({
        rows: empty
          ? []
          : [
              {
                prj_id: 3,
                prj_nm: project.prj_nm,
                job_cd: "BE",
                job_nm: "백엔드",
                plan_ym: "2026-10",
                planMm: 3,
                actualMm: 2.5,
                shortageMm: 0.5,
              },
            ],
        total: empty ? 0 : 1,
        page: 0,
        size: 50,
      });
    if (url.startsWith("/api/plan-vs-actual?"))
      return new Response(
        JSON.stringify({
          success: true,
          meta: { today: "2026-10-14", projects: [project] },
          data: {
            rows: empty
              ? []
              : [
                  {
                    prj_id: 3,
                    prj_no: project.prj_no,
                    prj_nm: project.prj_nm,
                    job_cd: "BE",
                    job_nm: "백엔드",
                    planMm: 3,
                    actualMm: 2.5,
                    gapMm: -0.5,
                    gap: "SHORT",
                  },
                ],
            total: empty ? 0 : 1,
            page: 0,
            size: 50,
            totals: { planMm: 3, actualMm: 2.5 },
          },
        }),
        { headers: { "Content-Type": "application/json" } },
      );
    if (url === "/api/projects/3") return response(project);
    if (url.startsWith("/api/projects?"))
      return response({
        items: [project],
        total: 1,
        page: 0,
        size: 50,
        unassignedCount: 0,
      });
    if (url === "/api/projects/3/staff-plans")
      return response({ items: [], actual: [] });
    if (url === "/api/assignments/7")
      return response({
        ...row,
        asgn_rt: 1,
        asgn_se_cd: "NEW",
        bill_untprc_amt: null,
        untprc_base_cd: "MONTH",
        cntrct_dev_id: null,
        cntrct_dev_nm: null,
        rplc_asgn_id: null,
        current_month_mm_qty: 1,
        person_month_mm_qty: 1,
        year_mm_qty: 1,
        warnings: [],
        rates: [],
        months: [],
        rmrk_desc: null,
      });
    if (url.startsWith("/api/assignments?"))
      return response({ items: [], total: 0, page: 0, size: 50 });
    if (url === "/api/assignments/7/months")
      return response({
        asgn_id: 7,
        updated_at: row.updated_at,
        items: [],
        warnings: [],
      });
    if (
      url === "/api/sales-parts/options" ||
      url === "/api/projects/options" ||
      url.startsWith("/api/month-close?") ||
      /^\/api\/projects\/3\/(history|part-changes)$/.test(url)
    )
      return response([]);
    throw new Error(`Unexpected route request ${url}`);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("withdrawal and comparison route composition", () => {
  it("registers the withdrawal list, real chance control and server-owned dispatch tooltip anonymously", async () => {
    mount();
    await screen.findByTestId("withdrawal-row-7");
    expect(await screen.findByTestId("withdrawal-chance-7")).toBeEnabled();
    expect(screen.getByTestId("dispatch-expiry-7")).toHaveAttribute(
      "title",
      expect.stringContaining("2026-09-20, 2026-10-20"),
    );
    expect(calls("/api/auth")).toHaveLength(0);
    expect(screen.getAllByLabelText("열린 화면")).toHaveLength(1);
    expect(screen.queryByText(/추천 n곳|가배정/)).not.toBeInTheDocument();
  });
  it("submits exact change wire then refreshes the applied list and global summary", async () => {
    mount();
    await within(await screen.findByTestId("withdrawal-chance-7")).findByRole(
      "option",
      { name: "높음" },
    );
    await waitFor(() =>
      expect(screen.getByTestId("withdrawal-chance-7")).toBeEnabled(),
    );
    const before = calls("/api/withdrawals?").length;
    fireEvent.change(screen.getByTestId("withdrawal-chance-7"), {
      target: { value: "H" },
    });
    await waitFor(() =>
      expect(calls("/api/withdrawals?").length).toBeGreaterThan(before),
    );
    expect(JSON.parse(writes()[0][1].body)).toEqual({
      asgn_chg_ty_cd: "EXT_CHANCE",
      aply_dt: "2026-10-14",
      ext_psblty_cd: "H",
      updated_at: "2026-10-14T00:00:00.123456",
    });
    expect(screen.getByTestId("withdrawal-summary")).toHaveTextContent(
      "사람 0명",
    );
    expect(screen.getByLabelText("현재 주소")).toHaveTextContent("/UI-05 null");
  });
  it("refreshes a stale chance without retrying the write", async () => {
    stale = true;
    mount();
    await within(await screen.findByTestId("withdrawal-chance-7")).findByRole(
      "option",
      { name: "높음" },
    );
    await waitFor(() =>
      expect(screen.getByTestId("withdrawal-chance-7")).toBeEnabled(),
    );
    const before = calls("/api/withdrawals?").length;
    fireEvent.change(screen.getByTestId("withdrawal-chance-7"), {
      target: { value: "H" },
    });
    await screen.findByText(
      "다른 사람이 먼저 바꿨습니다. 목록을 새로 고칩니다.",
    );
    await waitFor(() =>
      expect(calls("/api/withdrawals?").length).toBeGreaterThan(before),
    );
    expect(writes()).toHaveLength(1);
    expect(screen.getByTestId("withdrawal-chance-7")).toHaveValue("");
  });
  it.each(["historical", "future", "closed"])(
    "preserves the %s chance lock at the registered route",
    async (kind) => {
      if (kind === "historical") sessionStorage.setItem("UI-05.year", "2025");
      if (kind === "future") row.bgn_dt = "2026-11-01";
      if (kind === "closed") {
        row.chance_locked = true;
        row.chance_lock_reason = "종료 프로젝트입니다.";
      }
      mount();
      expect(await screen.findByTestId("withdrawal-chance-7")).toBeDisabled();
      expect(screen.getByTestId("withdrawal-chance-7")).toHaveAttribute(
        "title",
      );
      expect(writes()).toHaveLength(0);
    },
  );
  it("retains the withdrawal empty state", async () => {
    empty = true;
    mount();
    expect(
      await screen.findByText("조건에 맞는 결과가 없습니다."),
    ).toBeVisible();
    expect(writes()).toHaveLength(0);
  });
  it("retains failed-read retry through the shell", async () => {
    failed = true;
    mount();
    await screen.findByText("서버에 연결할 수 없습니다.");
    failed = false;
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    expect(await screen.findByTestId("withdrawal-row-7")).toBeVisible();
  });
  it("opens the existing assignment drawer using the clicked row identity", async () => {
    mount();
    fireEvent.click(await screen.findByTestId("withdrawal-row-7"));
    expect(await screen.findByTestId("assignment-detail")).toHaveTextContent(
      "A-0007",
    );
    expect(screen.getByLabelText("현재 주소")).toHaveTextContent(
      '/UI-12 {"assignmentId":7}',
    );
    expect(writes()).toHaveLength(0);
  });
  it("shares project/job/year with monthly but never annual gap, sort or pagination", async () => {
    mount("/UI-11");
    await screen.findByText("부족 0.50");
    fireEvent.change(screen.getByTestId("comparison-project"), {
      target: { value: "3" },
    });
    fireEvent.change(screen.getByTestId("comparison-job"), {
      target: { value: "BE" },
    });
    fireEvent.change(screen.getByTestId("comparison-gap"), {
      target: { value: "SHORT" },
    });
    fireEvent.click(screen.getByTestId("comparison-query"));
    await waitFor(() =>
      expect(query("/api/plan-vs-actual?").get("gap")).toBe("SHORT"),
    );
    fireEvent.click(screen.getByRole("tab", { name: "미충원 직무" }));
    await screen.findByTestId("shortage-row-3-be-2026-10");
    expect(Object.fromEntries(query("/api/plan-vs-actual/shortages?"))).toEqual(
      { year: "2026", projectId: "3", job: "BE", page: "0", size: "50" },
    );
    expect(screen.getByTestId("shortage-project")).toHaveValue("3");
    expect(screen.queryByLabelText("차이")).not.toBeInTheDocument();
    fireEvent.click(screen.getByTestId("shortage-reset"));
    await waitFor(() =>
      expect(query("/api/plan-vs-actual/shortages?").has("projectId")).toBe(
        false,
      ),
    );
    fireEvent.click(screen.getByRole("tab", { name: "계획 대비 투입" }));
    expect(screen.getByTestId("comparison-project")).toHaveValue("");
    expect(screen.getByTestId("comparison-job")).toHaveValue("");
    expect(screen.getByTestId("comparison-gap")).toHaveValue("SHORT");
  });
  it("shows the unfiltered monthly empty state without a reset action", async () => {
    empty = true;
    mount("/UI-11");
    await screen.findByTestId("comparison-totals");
    fireEvent.click(screen.getByRole("tab", { name: "미충원 직무" }));
    expect(await screen.findByText("미충원 직무가 없습니다.")).toBeVisible();
    expect(
      screen.queryByTestId("shortage-empty-reset"),
    ).not.toBeInTheDocument();
  });
  it.each(["annual", "monthly"])(
    "opens the existing staff-plan tab from %s comparison",
    async (tab) => {
      mount("/UI-11");
      await screen.findByText("부족 0.50");
      if (tab === "monthly") {
        fireEvent.click(screen.getByRole("tab", { name: "미충원 직무" }));
        fireEvent.click(await screen.findByTestId("shortage-row-3-be-2026-10"));
      } else fireEvent.click(screen.getByTestId("comparison-project-3-be"));
      expect(await screen.findByTestId("staff-plans-panel")).toBeVisible();
      expect(screen.getByTestId("project-tab-staff-plans")).toHaveAttribute(
        "aria-selected",
        "true",
      );
      expect(screen.getByLabelText("현재 주소")).toHaveTextContent(
        "/UI-01?open=3&tab=staff-plans",
      );
      expect(writes()).toHaveLength(0);
    },
  );
  it("exports the original feature client functions without a registry import cycle", () => {
    expect(registry.listWithdrawals).toBe(withdrawals.listWithdrawals);
    expect(registry.exportWithdrawals).toBe(withdrawals.exportWithdrawals);
    expect(registry.listPlanComparison).toBe(annual.listPlanComparison);
    expect(registry.downloadPlanComparison).toBe(annual.downloadPlanComparison);
    expect(registry.listMonthlyShortages).toBe(monthly.listMonthlyShortages);
    expect(registry.downloadMonthlyShortages).toBe(
      monthly.downloadMonthlyShortages,
    );
  });
});
