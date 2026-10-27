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
import { emptyProject, type ProjectDetail } from "../pages/projects/client";
import type { AssignmentDetail } from "../pages/assignments/client";
import * as client from "../api/apiClient";
import * as assignments from "../pages/assignments/client";
import * as changes from "../pages/changes/client";
import * as months from "../pages/months/client";
import * as projectMonths from "../pages/projectsops/assignmentMonthsClient";
import { response } from "../pages/developers/DeveloperTestSupport";

const menus = [
  {
    menuId: 1,
    name: "프로젝트·투입",
    screenId: null,
    icon: null,
    mobile: true,
    children: ["UI-16", "UI-01", "UI-12"].map((id, i) => ({
      menuId: i + 2,
      name: id,
      screenId: id,
      icon: null,
      mobile: true,
      children: [],
    })),
  },
];
let row: AssignmentDetail;
let project: ProjectDetail;
let fetcher: ReturnType<typeof vi.fn>;
let empty: boolean, failed: boolean;
function Location() {
  const location = useLocation();
  return (
    <output aria-label="현재 주소">
      {location.pathname + location.search}
    </output>
  );
}
function mount(path = "/UI-12", state?: object) {
  return render(
    <MemoryRouter initialEntries={[{ pathname: path, state }]}>
      <Location />
      <AppRouter />
    </MemoryRouter>,
  );
}
const writes = () =>
  fetcher.mock.calls.filter(
    ([, init]) => init?.method && init.method !== "GET",
  );
async function openDetail() {
  fireEvent.click(await screen.findByTestId("assignment-open-7"));
  await screen.findByTestId("assignment-months-panel");
}
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
  project = {
    ...emptyProject,
    prj_id: 3,
    prj_no: "P-2603",
    prj_nm: "계약 업무",
    cntrct_ty_cd: "MO",
    prj_stts_cd: "RUN",
    use_yn: "Y",
    cntrct_bgn_dt: "2026-10-01",
    cntrct_end_dt: "2026-12-31",
    cur_end_dt: "2026-12-31",
    cur_cntrct_amt: 1000000,
    init_cntrct_amt: 1000000,
    assignmentCount: 1,
    custName: null,
    partName: null,
    pmName: null,
    cntrct_mm_qty: 1,
  };
  row = {
    asgn_id: 7,
    asgn_no: "A-0007",
    prj_id: 3,
    prj_nm: "계약 업무",
    dev_id: 2,
    dev_nm: "개발자",
    mng_no: "K-0002",
    job_cd: "BE",
    bgn_dt: "2026-10-16",
    end_dt: "2026-12-31",
    plan_end_dt: "2026-12-31",
    asgn_rt: 1,
    asgn_se_cd: "NEW",
    bill_untprc_amt: 7000000,
    untprc_base_cd: "MONTH",
    cntrct_dev_id: null,
    cntrct_dev_nm: null,
    ext_psblty_cd: "H",
    asgn_stts_cd: "ACTIVE",
    rplc_asgn_id: null,
    current_month_mm_qty: 0.52,
    person_month_mm_qty: 0.52,
    year_mm_qty: 2.52,
    warnings: [],
    updated_at: "2026-10-14T00:00:00.123456",
    extend_count: 0,
    rates: [],
    rmrk_desc: null,
    months: [
      {
        work_ym: "2026-10",
        auto_mm_qty: 0.52,
        mm_qty: 0.52,
        mnl_yn: "N",
        person_month_mm_qty: 0.52,
        closed_yn: "N",
      },
    ],
  };
  fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (url === "/api/menus") return response(menus);
    if (url.startsWith("/api/codes/"))
      return response(
        url.endsWith("JOB") ? [{ code: "BE", name: "백엔드" }] : [],
      );
    if (url.startsWith("/api/month-close?")) return response([]);
    if (url.startsWith("/api/params/")) return response({ num_val: 80 });
    if (url === "/api/projects/options")
      return response([{ id: 3, no: "P-2603", name: "계약 업무", type: "MO" }]);
    if (
      url.startsWith("/api/developers/options") ||
      url.startsWith("/api/partners/options") ||
      url === "/api/sales-parts/options"
    )
      return response([]);
    if (url === "/api/projects/3") return response(project);
    if (url.startsWith("/api/projects?"))
      return response({
        items: [project],
        total: 1,
        page: 0,
        size: 50,
        unassignedCount: 0,
      });
    if (url === "/api/projects/3/assignments")
      return response([
        {
          asgn_id: 7,
          name: row.dev_nm,
          job: "백엔드",
          beginDate: row.bgn_dt,
          endDate: row.end_dt,
          assignmentRate: 1,
          status: "ACTIVE",
        },
      ]);
    if (/^\/api\/projects\/3\/(part-changes|history)$/.test(url))
      return response([]);
    if (url.startsWith("/api/projects/3/assignment-months?"))
      return response({
        prj_id: 3,
        year: 2026,
        items: [
          {
            asgn_id: 7,
            dev_id: 2,
            dev_nm: row.dev_nm,
            months: [{ work_ym: "2026-10", mm_qty: 0.52 }],
            total_mm_qty: 0.52,
          },
        ],
        month_totals: [{ work_ym: "2026-10", mm_qty: 0.52 }],
      });
    if (url.startsWith("/api/assignments?")) {
      if (failed) throw new Error("offline");
      return response({
        items: empty ? [] : [row],
        total: empty ? 0 : 1,
        page: 0,
        size: 50,
      });
    }
    if (url === "/api/assignments/7") return response(row);
    if (url === "/api/assignments/7/months") {
      if (init?.method === "PUT")
        row = { ...row, updated_at: "2026-10-14T00:00:01.123456" };
      return response({
        asgn_id: 7,
        updated_at: row.updated_at,
        items: row.months,
        warnings: [],
      });
    }
    if (url.startsWith("/api/assignments/7/changes"))
      return response({ items: [], total: 0 });
    throw new Error(`Unexpected assignment route request ${url}`);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("assignment route composition", () => {
  it("registers the real list anonymously in the existing shell", async () => {
    mount();
    expect(await screen.findByTestId("assignment-row-7")).toHaveTextContent(
      "A-0007",
    );
    expect(screen.getAllByLabelText("열린 화면")).toHaveLength(1);
    expect(
      fetcher.mock.calls.some(([url]) => String(url).includes("/auth/")),
    ).toBe(false);
    expect(
      screen.queryByText("화면을 찾을 수 없습니다."),
    ).not.toBeInTheDocument();
  });
  it("keeps the registered feature's empty state", async () => {
    empty = true;
    mount();
    expect(
      await screen.findByText("조건에 맞는 결과가 없습니다"),
    ).toBeVisible();
    expect(writes()).toHaveLength(0);
  });
  it("keeps the registered feature's error state", async () => {
    failed = true;
    mount();
    expect(await screen.findByText("서버에 연결할 수 없습니다.")).toBeVisible();
    expect(screen.queryByTestId("assignment-row-7")).not.toBeInTheDocument();
  });
  it("passes applied filters and selected rows to batch actions without submitting on cancel", async () => {
    mount();
    await screen.findByTestId("assignment-row-7");
    fireEvent.change(screen.getByTestId("assignment-filter-project"), {
      target: { value: "3" },
    });
    fireEvent.click(screen.getByTestId("assignment-search"));
    await waitFor(() =>
      expect(
        fetcher.mock.calls.some(([url]) => url.includes("projectId=3")),
      ).toBe(true),
    );
    fireEvent.click(await screen.findByTestId("assignment-check-7"));
    fireEvent.click(screen.getByTestId("assignment-open-chance"));
    expect(await screen.findByRole("dialog")).toHaveTextContent("연장 가능성");
    fireEvent.click(within(screen.getByRole("dialog")).getByText("취소"));
    expect(writes()).toHaveLength(0);
  });
  it("mounts monthly editing and history with the selected assignment identity", async () => {
    mount();
    await openDetail();
    expect(screen.getByTestId("assignment-month-input-2026-10")).toHaveValue(
      "0.52",
    );
    fireEvent.click(screen.getByTestId("assignment-changes-tab"));
    expect(await screen.findByTestId("assignment-changes-panel")).toBeVisible();
    expect(
      fetcher.mock.calls.some(([url]) => url === "/api/assignments/7/changes"),
    ).toBe(true);
    expect(writes()).toHaveLength(0);
  });
  it("requeries the list and exact detail after monthly save, preserving microseconds", async () => {
    mount();
    await openDetail();
    const before = fetcher.mock.calls.filter(
      ([url]) => url === "/api/assignments/7",
    ).length;
    fireEvent.change(screen.getByTestId("assignment-month-input-2026-10"), {
      target: { value: "0.60" },
    });
    fireEvent.click(screen.getByTestId("assignment-months-save"));
    await waitFor(() =>
      expect(
        fetcher.mock.calls.filter(([url]) => url === "/api/assignments/7")
          .length,
      ).toBeGreaterThan(before),
    );
    const put = writes().find(
      ([url, init]) => url.endsWith("/months") && init?.method === "PUT",
    )!;
    expect(JSON.parse(put[1].body)).toMatchObject({
      updated_at: "2026-10-14T00:00:00.123456",
      months: [{ work_ym: "2026-10", mm_qty: 0.6 }],
    });
  });
  it("protects dirty monthly inputs on detail close", async () => {
    mount();
    await openDetail();
    fireEvent.change(screen.getByTestId("assignment-month-input-2026-10"), {
      target: { value: "0.60" },
    });
    fireEvent.click(screen.getByTestId("assignment-detail-close"));
    fireEvent.click(await screen.findByText("계속 작성"));
    expect(screen.getByTestId("assignment-month-input-2026-10")).toHaveValue(
      "0.60",
    );
    expect(writes()).toHaveLength(0);
  });
  it("connects the existing project annual matrix to the applied project filter", async () => {
    mount();
    await screen.findByTestId("assignment-row-7");
    fireEvent.change(screen.getByTestId("assignment-filter-project"), {
      target: { value: "3" },
    });
    fireEvent.click(screen.getByTestId("assignment-search"));
    fireEvent.click(await screen.findByTestId("assignment-project-months-tab"));
    expect(await screen.findByTestId("project-months-row-7")).toHaveTextContent(
      "개발자",
    );
    expect(
      fetcher.mock.calls.some(
        ([url]) => url === "/api/projects/3/assignment-months?year=2026",
      ),
    ).toBe(true);
  });
  it.each(["CANCELLED", "ENDED"] as const)(
    "keeps %s detail read-only across registered panels",
    async (status) => {
      row.asgn_stts_cd = status;
      mount();
      await openDetail();
      expect(
        screen.getByTestId("assignment-month-input-2026-10"),
      ).toHaveAttribute("readonly");
      expect(
        screen.queryByTestId("assignment-record-change"),
      ).not.toBeInTheDocument();
      expect(
        screen.queryByTestId("assignment-open-replace"),
      ).not.toBeInTheDocument();
    },
  );
  it("hides mutation controls in a prior year but preserves export", async () => {
    sessionStorage.setItem("UI-12.year", "2025");
    mount();
    await screen.findByTestId("assignment-row-7");
    expect(screen.queryByTestId("assignment-add")).not.toBeInTheDocument();
    expect(
      screen.queryByTestId("assignment-open-chance"),
    ).not.toBeInTheDocument();
    expect(screen.queryByTestId("assignment-check-7")).not.toBeInTheDocument();
  });
  it.each(["PLAN", "RUN", "HOLD"])(
    "hands an input-enabled %s project to the assignment creation drawer",
    async (status) => {
      project.prj_stts_cd = status;
      mount("/UI-01");
      fireEvent.click(await screen.findByTestId("project-open-3"));
      fireEvent.click(await screen.findByTestId("project-tab-assignments"));
      fireEvent.click(await screen.findByTestId("project-assignment-create"));
      const form = await screen.findByTestId("assignment-create");
      await waitFor(() =>
        expect(within(form).getByLabelText("프로젝트")).toHaveValue("3"),
      );
      expect(screen.getByLabelText("현재 주소")).toHaveTextContent("/UI-12");
      fireEvent.click(screen.getByTestId("assignment-create-cancel"));
      expect(writes()).toHaveLength(0);
    },
  );
  it.each(["END", "LOST"])(
    "does not add creation on a %s project",
    async (status) => {
      project.prj_stts_cd = status;
      mount("/UI-01");
      fireEvent.click(await screen.findByTestId("project-open-3"));
      fireEvent.click(await screen.findByTestId("project-tab-assignments"));
      expect(
        screen.queryByTestId("project-assignment-create"),
      ).not.toBeInTheDocument();
    },
  );
  it("hands project personnel to the same assignment, not developer detail", async () => {
    mount("/UI-01");
    fireEvent.click(await screen.findByTestId("project-open-3"));
    fireEvent.click(await screen.findByTestId("project-tab-assignments"));
    fireEvent.click(await screen.findByTestId("project-assignment-open-7"));
    expect(await screen.findByTestId("assignment-detail")).toHaveTextContent(
      "A-0007",
    );
    expect(screen.getByLabelText("현재 주소")).toHaveTextContent("/UI-12");
    expect(writes()).toHaveLength(0);
  });
  it("exports feature clients without introducing reverse registry imports", async () => {
    expect(client.listAssignments).toBe(assignments.listAssignments);
    expect(client.getAssignment).toBe(assignments.getAssignment);
    expect(client.createAssignment).toBe(assignments.createAssignment);
    expect(client.checkAssignments).toBe(assignments.checkAssignments);
    expect(client.getAssignmentMonths).toBe(months.getAssignmentMonths);
    expect(client.saveAssignmentMonths).toBe(months.saveAssignmentMonths);
    expect(client.getProjectAssignmentMonths).toBe(
      projectMonths.getProjectAssignmentMonths,
    );
    expect(client.createAssignmentChange).toBe(changes.createAssignmentChange);
    expect(client.createAssignmentFix).toBe(changes.createAssignmentFix);
    expect(client.listAssignmentChanges).toBe(changes.listAssignmentChanges);
    expect((await client.getAssignment(7)).asgn_id).toBe(7);
  });
});
