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
import { response } from "../pages/developers/DeveloperTestSupport";
import type { ProjectProgress } from "../pages/projectsops/progressClient";
import type { IssueRow } from "../pages/issues/client";
import * as client from "../api/apiClient";

let project: ProjectDetail;
let progress: ProjectProgress;
let issues: IssueRow[];
let fetcher: ReturnType<typeof vi.fn>;
let failBoard: boolean;
const menus = [
  {
    menuId: 1,
    name: "프로젝트·투입",
    screenId: null,
    icon: null,
    mobile: true,
    children: ["UI-16", "UI-01", "UI-31"].map((id, index) => ({
      menuId: index + 2,
      name:
        id === "UI-31"
          ? "프로젝트 현황판"
          : id === "UI-01"
            ? "프로젝트"
            : "대시보드",
      screenId: id,
      icon: null,
      mobile: true,
      children: [],
    })),
  },
];
function Location() {
  const location = useLocation();
  return (
    <output aria-label="현재 주소">
      {location.pathname + location.search}
    </output>
  );
}
function mount(path = "/UI-31") {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Location />
      <AppRouter />
    </MemoryRouter>,
  );
}
beforeEach(() => {
  vi.stubGlobal("innerWidth", 1280);
  vi.setSystemTime(new Date("2026-10-23T00:00:00Z"));
  localStorage.clear();
  sessionStorage.clear();
  useSharedPreferences.setState({
    favorites: [],
    recent: [],
    collapsed: false,
    pageSize: 50,
    dense: false,
  });
  failBoard = false;
  project = {
    ...emptyProject,
    prj_id: 41,
    prj_no: "P-2641",
    prj_nm: "진행 연결 시험",
    cntrct_ty_cd: "TK",
    prj_stts_cd: "RUN",
    use_yn: "Y",
    cntrct_bgn_dt: "2026-01-01",
    cntrct_end_dt: "2026-12-31",
    cur_end_dt: "2026-12-31",
    cur_cntrct_amt: 1000000,
    init_cntrct_amt: 1000000,
    assignmentCount: 0,
    custName: null,
    partName: null,
    pmName: null,
    cntrct_mm_qty: 0,
  };
  progress = {
    prj_id: 41,
    pmName: "PM 미지정",
    currentPhase: null,
    plan_prgs_rt: 50,
    actl_prgs_rt: 42.5,
    schedule_diff_rt: -7.5,
    cost_use_rt: null,
    cost_progress_diff_rt: null,
    cumulative_cost_amt: null,
    budget_amt: null,
    light: "정상",
    reasons: [],
    memo_elapsed_days: null,
    last_memo_dt: null,
    missing_salary_months: [],
    memos: [],
    phases: [
      {
        phs_id: 52,
        prj_id: 41,
        phs_seq: 1,
        phs_nm: "개발",
        wght_rt: 100,
        plan_bgn_dt: "2026-01-01",
        plan_end_dt: "2026-12-31",
        cmpl_dt: null,
        status: "진행 중",
      },
    ],
  };
  issues = [
    {
      issue_id: 63,
      prj_id: 41,
      ttl_nm: "연결 확인 이슈",
      svrt_cd: "MID",
      chrg_nm: null,
      ddln_dt: "2026-10-30",
      issue_stts_cd: "OPEN",
      actn_desc: null,
      reg_dt: "2026-10-23",
      end_dt: null,
    },
  ];
  fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (url === "/api/menus") return response(menus);
    if (url.startsWith("/api/codes/") || url.startsWith("/api/month-close?"))
      return response([]);
    if (url === "/api/params") return response([]);
    if (url.startsWith("/api/params/")) return response({ num_val: 80 });
    if (url === "/api/sales-parts/options" || url === "/api/projects/options")
      return response([]);
    if (url.startsWith("/api/project-board?")) {
      if (failBoard)
        return new Response(
          JSON.stringify({
            success: false,
            meta: {},
            code: "BOARD_UNAVAILABLE",
            message: "현황판 조회 실패",
            field: null,
            requestId: "board-request",
          }),
          { status: 500 },
        );
      return response({
        items: [
          {
            ...project,
            ...progress,
            open_issue_count: issues.length,
            overdue_issue_count: 0,
          },
        ],
        summary: {
          project_count: 1,
          normal_count: 1,
          warn_count: 0,
          risk_count: 0,
          open_issue_count: issues.length,
          high_open_issue_count: 0,
          overdue_issue_count: 0,
          stale_project_count: 0,
        },
        total: 1,
        page: 0,
        size: 50,
        stale_projects: [],
      });
    }
    if (url.startsWith("/api/issues?"))
      return response({
        items: issues.map((row) => ({ ...row, prj_nm: project.prj_nm })),
        open_count: issues.length,
        total: issues.length,
        page: 0,
        size: 50,
      });
    if (url.startsWith("/api/issues/owner-options?"))
      return response([{ name: "프로젝트 담당" }]);
    if (url.startsWith("/api/projects?"))
      return response({
        items: [project],
        total: 1,
        page: 0,
        size: 50,
        unassignedCount: 0,
      });
    if (url === "/api/projects/41" || url === "/api/projects/P-2641")
      return response(project);
    if (url === "/api/projects/41/progress") return response(progress);
    if (url === "/api/projects/41/issues")
      return response({ items: issues, open_count: issues.length });
    if (
      url === "/api/projects/41/assignments" ||
      url === "/api/projects/41/part-changes"
    )
      return response([]);
    if (
      url === "/api/projects/41/phases/52/complete" ||
      url === "/api/projects/41/phases/52/uncomplete"
    ) {
      progress.phases[0] = {
        ...progress.phases[0],
        cmpl_dt: url.endsWith("/uncomplete") ? null : "2026-10-23",
        status: url.endsWith("/uncomplete") ? "진행 중" : "완료",
      };
      return response(progress.phases[0]);
    }
    if (url === "/api/projects/41/phases/52" && init?.method === "PUT") {
      progress.phases[0] = {
        ...progress.phases[0],
        ...JSON.parse(String(init.body)),
      };
      return response(progress.phases[0]);
    }
    if (url === "/api/issues" && init?.method === "POST") {
      const saved = {
        ...JSON.parse(String(init.body)),
        issue_id: 64,
        reg_dt: "2026-10-23",
        end_dt: null,
      };
      issues = [...issues, saved];
      return response(saved);
    }
    throw new Error(`Unexpected progress route request ${url}`);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("project progress route composition", () => {
  it("mounts the board in the shared shell with issues and export, without a period or login", async () => {
    mount();
    expect(await screen.findByTestId("board-row-41")).toHaveTextContent(
      "진행 연결 시험",
    );
    expect(screen.getByTestId("board-download")).toBeEnabled();
    await waitFor(() =>
      expect(screen.getByTestId("board-issues-tab")).toHaveTextContent("(1)"),
    );
    expect(screen.queryByTestId("period-bar")).not.toBeInTheDocument();
    fireEvent.click(screen.getByTestId("board-issues-tab"));
    expect(await screen.findByTestId("issue-list-panel")).toBeVisible();
    expect(await screen.findByText("연결 확인 이슈")).toBeVisible();
    expect(screen.getByTestId("board-issues-tab")).toHaveTextContent("(1)");
    expect(
      fetcher.mock.calls.some(([url]) => String(url).includes("/auth/")),
    ).toBe(false);
  });
  it("opens the selected board project in its progress detail tab", async () => {
    mount();
    fireEvent.click(await screen.findByTestId("board-open-41"));
    expect(await screen.findByTestId("project-progress-panel")).toBeVisible();
    expect(screen.getByLabelText("현재 주소")).toHaveTextContent(
      "/UI-01?open=41&tab=progress",
    );
    expect(
      await within(screen.getByTestId("project-progress-panel")).findByText(
        "실적 42.5%",
      ),
    ).toBeVisible();
    expect(screen.getByTestId("project-tab-contract-changes")).toBeVisible();
  });
  it.each(["41", "P-2641"])(
    "resolves %s issue deep links to the writable issue panel",
    async (id) => {
      mount(`/UI-01?open=${id}&tab=issues`);
      expect(await screen.findByTestId("project-issue-panel")).toBeVisible();
      fireEvent.click(await screen.findByTestId("issue-add"));
      expect(await screen.findByTestId("issue-form")).toHaveAttribute(
        "data-screen-id",
        "SCR-UI-01-M15",
      );
      expect(screen.getByLabelText("프로젝트")).toHaveValue("진행 연결 시험");
      expect(
        fetcher.mock.calls.some(([url]) => url === `/api/projects/${id}`),
      ).toBe(true);
    },
  );
  it("opens the requested tab when returning to the same project through another deep link", async () => {
    mount();
    fireEvent.click(await screen.findByTestId("board-open-41"));
    expect(await screen.findByTestId("project-progress-panel")).toBeVisible();
    await waitFor(() =>
      expect(screen.getByTestId("project-tab-issues")).toHaveTextContent(
        "이슈 (1)",
      ),
    );
    fireEvent.click(
      within(screen.getByRole("navigation", { name: "열린 화면" })).getByRole(
        "button",
        {
          name: /^프로젝트 현황판$/,
        },
      ),
    );
    fireEvent.click(screen.getByTestId("board-issues-tab"));
    fireEvent.click(await screen.findByTestId("issue-project-63"));
    expect(await screen.findByTestId("project-issue-panel")).toBeVisible();
    expect(screen.getByTestId("project-tab-issues")).toHaveAttribute(
      "aria-selected",
      "true",
    );
    expect(screen.getByLabelText("현재 주소")).toHaveTextContent(
      "/UI-01?open=41&tab=issues",
    );
  });
  it.each(["complete", "uncomplete"])(
    "connects phase %s to the existing bodyless dialog and refresh",
    async (action) => {
      if (action === "uncomplete") progress.phases[0].cmpl_dt = "2026-10-22";
      mount("/UI-01?open=41&tab=progress");
      fireEvent.click(await screen.findByTestId(`project-phase-${action}-52`));
      fireEvent.click(await screen.findByTestId("phase-dialog-submit"));
      await waitFor(() =>
        expect(
          screen.queryByTestId("project-phase-dialog"),
        ).not.toBeInTheDocument(),
      );
      const request = fetcher.mock.calls.find(
        ([url]) => url === `/api/projects/41/phases/52/${action}`,
      );
      expect(request?.[1]).toMatchObject({ method: "POST" });
      expect(request?.[1]?.body).toBeUndefined();
      expect(
        await screen.findByTestId(
          `project-phase-${action === "complete" ? "uncomplete" : "complete"}-52`,
        ),
      ).toBeEnabled();
    },
  );
  it("connects period edit to the selected child and refreshes its dates", async () => {
    mount("/UI-01?open=41&tab=progress");
    fireEvent.click(await screen.findByTestId("project-phase-period-52"));
    fireEvent.change(screen.getByLabelText(/예정 종료일/), {
      target: { value: "2026-11-30" },
    });
    fireEvent.click(screen.getByTestId("phase-dialog-submit"));
    await waitFor(() =>
      expect(
        screen.queryByTestId("project-phase-dialog"),
      ).not.toBeInTheDocument(),
    );
    expect(
      fetcher.mock.calls.find(
        ([url]) => url === "/api/projects/41/phases/52",
      )?.[1],
    ).toMatchObject({
      method: "PUT",
      body: JSON.stringify({
        plan_bgn_dt: "2026-01-01",
        plan_end_dt: "2026-11-30",
      }),
    });
  });
  it("keeps completion and memo writes available at 375px but hides period edit", async () => {
    vi.stubGlobal("innerWidth", 375);
    mount("/UI-01?open=41&tab=progress");
    expect(
      await screen.findByTestId("project-phase-complete-52"),
    ).toBeEnabled();
    expect(
      screen.queryByTestId("project-phase-period-52"),
    ).not.toBeInTheDocument();
    fireEvent.click(await screen.findByTestId("project-memo-add"));
    expect(await screen.findByTestId("project-memo-form")).toBeVisible();
  });
  it("keeps issue registration writable at 375px", async () => {
    vi.stubGlobal("innerWidth", 375);
    mount("/UI-01?open=41&tab=issues");
    fireEvent.click(await screen.findByTestId("issue-add"));
    expect(await screen.findByTestId("issue-save")).toBeEnabled();
  });
  it("does not expose inputs on an ended project", async () => {
    project.prj_stts_cd = "END";
    mount("/UI-01?open=41&tab=progress");
    await screen.findByTestId("project-progress-panel");
    await screen.findAllByText(
      "종료 프로젝트입니다. 기록은 읽기만 할 수 있습니다.",
    );
    expect(
      screen.queryByTestId("project-phase-complete-52"),
    ).not.toBeInTheDocument();
    expect(screen.queryByTestId("project-memo-add")).not.toBeInTheDocument();
    fireEvent.click(screen.getByTestId("project-tab-issues"));
    await screen.findByTestId("project-issue-panel");
    expect(screen.queryByTestId("issue-add")).not.toBeInTheDocument();
  });
  it("refreshes board summaries after issue registration from the board", async () => {
    mount();
    await screen.findByTestId("board-row-41");
    fireEvent.click(screen.getByTestId("board-issues-tab"));
    fireEvent.click(await screen.findByTestId("issue-list-add"));
    const form = within(await screen.findByTestId("issue-form"));
    await waitFor(() => expect(form.getByLabelText(/프로젝트/)).toBeEnabled());
    fireEvent.change(form.getByLabelText(/프로젝트/), {
      target: { value: "41" },
    });
    fireEvent.change(form.getByLabelText(/제목/), {
      target: { value: "등록 후 현황 갱신" },
    });
    fireEvent.click(screen.getByTestId("issue-save"));
    expect(await screen.findByText("등록 후 현황 갱신")).toBeVisible();
    await waitFor(() =>
      expect(screen.getByTestId("board-issues-tab")).toHaveTextContent("(2)"),
    );
    fireEvent.click(screen.getByTestId("board-tab"));
    expect(await screen.findByTestId("board-row-41")).toBeVisible();
    expect(
      fetcher.mock.calls.filter(([url]) =>
        String(url).startsWith("/api/project-board?"),
      ).length,
    ).toBeGreaterThan(2);
  });
  it("retains the board error and retry flow", async () => {
    failBoard = true;
    mount();
    expect(await screen.findByText("현황판 조회 실패")).toBeVisible();
    failBoard = false;
    fireEvent.click(screen.getByText("다시 시도"));
    expect(await screen.findByTestId("board-row-41")).toBeVisible();
  });
  it("connects export to the applied filters and preserves API errors", async () => {
    const original = fetcher.getMockImplementation()!;
    fetcher.mockImplementation(async (url: string, init?: RequestInit) => {
      if (url.startsWith("/api/project-board/export"))
        return new Response(
          JSON.stringify({
            success: false,
            meta: {},
            code: "EXPORT_UNAVAILABLE",
            message: "현황판 다운로드 실패",
            field: null,
            requestId: "export-request",
          }),
          { status: 500 },
        );
      return original(url, init);
    });
    mount();
    await screen.findByTestId("board-row-41");
    fireEvent.change(screen.getByTestId("board-light-filter"), {
      target: { value: "위험" },
    });
    fireEvent.click(screen.getByTestId("board-download"));
    expect(await screen.findByText("현황판 다운로드 실패")).toBeVisible();
    const call = fetcher.mock.calls.find(([url]) =>
      String(url).startsWith("/api/project-board/export"),
    );
    const query = new URLSearchParams(String(call?.[0]).split("?")[1]);
    expect([...query.entries()]).toEqual([["light", "위험"]]);
    expect(screen.getByTestId("board-row-41")).toBeVisible();
  });
  it("registers clients without introducing feature-to-registry import cycles", async () => {
    expect((await client.getProjectProgress(41)).actl_prgs_rt).toBe(42.5);
    expect((await client.listProjectIssues(41)).open_count).toBe(1);
    expect(await client.getIssueOwnerOptions(41)).toEqual([
      { name: "프로젝트 담당" },
    ]);
    expect(await client.getParams()).toEqual([]);
    for (const name of [
      "updateProjectPhase",
      "updateProjectPhaseCompletion",
      "updateProjectPhaseUncompletion",
      "saveProjectMemo",
      "updateProjectMemo",
      "listIssues",
      "createIssue",
      "updateIssue",
      "getProjectBoard",
      "getProjectBoardExport",
      "downloadProjectBoard",
    ] as const)
      expect(client[name]).toBeTypeOf("function");
    expect(client.getProjectBoardExport).toBe(client.downloadProjectBoard);
  });
});
