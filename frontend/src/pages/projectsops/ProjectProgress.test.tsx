import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ProjectProgressPanel } from "./ProjectProgressPanel";
import { ProjectIssuePanel, ProjectIssueTabLabel } from "./ProjectIssuePanel";
import { projectProgressTabs } from "./ProjectProgressTabs";
import { ProjectDrawer } from "../projects/ProjectDrawer";
import { emptyProject, type ProjectDetail } from "../projects/client";
import type {
  ProgressProject,
  ProjectProgress,
  ProjectIssuesData,
} from "./progressClient";

const project: ProgressProject = {
  prj_id: 8,
  prj_nm: "진행 시험",
  cntrct_ty_cd: "TK",
  prj_stts_cd: "RUN",
  use_yn: "Y",
};
const detail: ProjectDetail = {
  ...emptyProject,
  ...project,
  prj_no: "P-2608",
  custName: null,
  partName: null,
  cur_end_dt: "2026-12-31",
  cur_cntrct_amt: 500000000,
  assignmentCount: 0,
  cntrct_mm_qty: null,
  pmName: null,
};
const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
let data: ProjectProgress;
let issues: ProjectIssuesData;
let failed: boolean;
let fetcher: ReturnType<typeof vi.fn>;
const changed = vi.fn().mockResolvedValue(undefined);
beforeEach(() => {
  Object.defineProperty(window, "innerWidth", {
    configurable: true,
    writable: true,
    value: 1280,
  });
  failed = false;
  changed.mockClear();
  data = {
    prj_id: 8,
    pmName: "PM 미지정",
    currentPhase: null,
    plan_prgs_rt: 50.5,
    actl_prgs_rt: 42,
    schedule_diff_rt: -8.5,
    cost_use_rt: 57.5,
    cost_progress_diff_rt: 15.5,
    cumulative_cost_amt: 230000000,
    budget_amt: 400000000,
    light: "위험",
    reasons: [
      "일정이 계획보다 8.5%p 늦음",
      "원가 사용률이 진척률보다 15.5%p 높음 — 원가 초과 위험",
    ],
    memo_elapsed_days: 15,
    last_memo_dt: "2026-10-08",
    missing_salary_months: [],
    phases: [
      {
        phs_id: 1,
        prj_id: 8,
        phs_seq: 1,
        phs_nm: "분석",
        wght_rt: 40,
        plan_bgn_dt: "2026-01-01",
        plan_end_dt: "2026-02-01",
        cmpl_dt: "2026-02-01",
        status: "완료",
      },
      {
        phs_id: 2,
        prj_id: 8,
        phs_seq: 2,
        phs_nm: "개발",
        wght_rt: 60,
        plan_bgn_dt: "2026-02-02",
        plan_end_dt: "2026-12-31",
        cmpl_dt: null,
        status: "진행 중",
      },
    ],
    memos: [
      {
        memo_id: 2,
        prj_id: 8,
        wrt_dt: "2026-10-08",
        actl_prgs_rt: 42,
        cmpl_work_desc: "설계 완료",
        plan_work_desc: "개발 예정",
        rqst_desc: "검토 요청",
      },
      {
        memo_id: 1,
        prj_id: 8,
        wrt_dt: "2026-10-01",
        actl_prgs_rt: 20,
        cmpl_work_desc: "분석 완료",
        plan_work_desc: "설계 예정",
        rqst_desc: null,
      },
    ],
  };
  issues = {
    open_count: 1,
    items: [
      {
        prj_id: 8,
        issue_id: 1,
        ttl_nm: "조치할 이슈",
        svrt_cd: "HIGH",
        chrg_nm: "담당",
        ddln_dt: "2020-01-01",
        issue_stts_cd: "OPEN",
        actn_desc: "조치 검토",
        reg_dt: "2020-01-01",
        end_dt: null,
      },
      {
        prj_id: 8,
        issue_id: 2,
        ttl_nm: "종료 이슈",
        svrt_cd: "MID",
        chrg_nm: null,
        ddln_dt: "2020-01-01",
        issue_stts_cd: "CLOSED",
        actn_desc: null,
        reg_dt: "2020-01-01",
        end_dt: "2020-01-02",
      },
    ],
  };
  fetcher = vi.fn(async (url: string) => {
    if (url === "/api/params")
      return response([
        { oprt_std_id: "SIG_STALE_DAYS", num_val: 14, txt_val: null },
      ]);
    if (url.endsWith("/progress")) {
      if (failed) throw new Error("offline");
      return response(data);
    }
    if (url.endsWith("/issues")) {
      if (failed) throw new Error("offline");
      return response(issues);
    }
    if (url === "/api/projects/8") return response(detail);
    return response([]);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
const progress = (p = project, phase = vi.fn()) =>
  render(
    <ProjectProgressPanel
      project={p}
      onChanged={changed}
      onPhaseAction={phase}
    />,
  );

describe("SCR-UI-01 progress", () => {
  it("renders plan actual schedule reason and cost amounts in focusable info", async () => {
    progress();
    expect(await screen.findByText("계획 50.5%")).toBeVisible();
    expect(screen.getByText("실적 42.0%")).toBeVisible();
    expect(screen.getByText("-8.5%p")).toBeVisible();
    expect(screen.getByText("57.5%")).toBeVisible();
    expect(screen.getByTestId("project-progress-light")).toHaveTextContent(
      data.reasons.join(" · "),
    );
    expect(screen.getByTestId("project-progress-light").className).toContain(
      "risk",
    );
    expect(screen.getByTestId("project-cost-info")).toHaveAttribute(
      "tabindex",
      "0",
    );
    expect(screen.getByRole("tooltip")).toHaveTextContent(
      "예산 400,000,000원 · 누적 원가 230,000,000원",
    );
    expect(screen.queryByText("원가 분석")).not.toBeInTheDocument();
  });
  it.each(["정상", "주의"] as const)(
    "distinct light class %s",
    async (light) => {
      data.light = light;
      progress();
      expect(
        await screen.findByTestId("project-progress-light"),
      ).toHaveTextContent(light);
      expect(screen.getByTestId("project-progress-light").className).toContain(
        light === "정상" ? "normal" : "warn",
      );
    },
  );
  it.each(["MO", "DP"] as const)(
    "operating type %s has automatic explanation and no cost",
    async (type) => {
      data.actl_prgs_rt = data.plan_prgs_rt;
      data.cost_use_rt = null;
      progress({ ...project, cntrct_ty_cd: type });
      expect(
        await screen.findByText(
          "운영형(월단위) 프로젝트는 진척률을 기간 기준으로 자동 계산합니다.",
        ),
      ).toBeVisible();
      expect(screen.getByText("실적 50.5%")).toBeVisible();
      expect(
        screen.queryByTestId("project-progress-cost"),
      ).not.toBeInTheDocument();
    },
  );
  it("zero budget hides cost row", async () => {
    data.cost_use_rt = null;
    data.budget_amt = null;
    progress();
    await screen.findByText("계획 50.5%");
    expect(
      screen.queryByTestId("project-progress-cost"),
    ).not.toBeInTheDocument();
  });
  it.each([14, 15])(
    "elapsed %s uses effective strict threshold",
    async (days) => {
      data.memo_elapsed_days = days;
      progress();
      const elapsed = await screen.findByTestId("project-memo-elapsed");
      expect(elapsed).toHaveTextContent(`${days}일 경과`);
      await waitFor(() =>
        expect(elapsed.className.includes("stale")).toBe(days > 14),
      );
    },
  );
  it("missing memo and performance date excludes stale", async () => {
    data.memo_elapsed_days = null;
    data.last_memo_dt = null;
    progress();
    expect(await screen.findByTestId("project-memo-elapsed")).toHaveTextContent(
      "메모없음 · – (업데이트 지연 판단 제외)",
    );
  });
  it("empty phases and memos show exact messages", async () => {
    data.phases = [];
    data.memos = [];
    progress();
    expect(await screen.findByText("단계 표가 없습니다.")).toBeVisible();
    expect(await screen.findByText("아직 주간 메모가 없습니다.")).toBeVisible();
  });
  it("null dates stay dashes and no completion is offered", async () => {
    data.phases[1].plan_bgn_dt = null;
    data.phases[1].plan_end_dt = null;
    data.phases[1].status = "예정";
    progress();
    await screen.findByText("– ~ –");
    expect(
      screen.queryByTestId("project-phase-complete-2"),
    ).not.toBeInTheDocument();
  });
  it("only newest memo editable, work and optional request displayed", async () => {
    progress();
    expect(await screen.findByText("한 일 설계 완료")).toBeVisible();
    expect(screen.getByText("할 일 개발 예정")).toBeVisible();
    expect(screen.getByText("요청 검토 요청")).toBeVisible();
    expect(screen.getByTestId("project-memo-edit-2")).toBeVisible();
    expect(screen.queryByTestId("project-memo-edit-1")).not.toBeInTheDocument();
  });
  it("phase actions use selected child, mobile hides only period edit", async () => {
    Object.defineProperty(window, "innerWidth", { value: 767 });
    const action = vi.fn();
    progress(project, action);
    fireEvent.click(await screen.findByTestId("project-phase-complete-2"));
    expect(action).toHaveBeenCalledWith(
      data.phases[1],
      "complete",
      expect.any(Function),
    );
    expect(
      screen.queryByTestId("project-phase-period-2"),
    ).not.toBeInTheDocument();
    expect(screen.getByTestId("project-phase-uncomplete-1")).toBeVisible();
    expect(await screen.findByTestId("project-memo-add")).toBeVisible();
  });
  it.each(["END", "LOST", "N"])(
    "closed %s retains records but hides all controls and light",
    async (state) => {
      progress({
        ...project,
        prj_stts_cd: state === "N" ? "RUN" : state,
        use_yn: state === "N" ? "N" : "Y",
      });
      expect(
        await screen.findAllByText(/기록은 읽기만 할 수 있습니다/),
      ).not.toHaveLength(0);
      expect(await screen.findByText("한 일 설계 완료")).toBeVisible();
      for (const id of [
        "project-progress-light",
        "project-memo-add",
        "project-memo-edit-2",
        "project-phase-complete-2",
        "project-phase-uncomplete-1",
        "project-phase-period-2",
      ])
        expect(screen.queryByTestId(id)).not.toBeInTheDocument();
    },
  );
  it("failed progress is retryable and never rendered as empty success", async () => {
    failed = true;
    progress();
    await screen.findByText("서버에 연결할 수 없습니다.");
    expect(screen.queryByText("단계 표가 없습니다.")).not.toBeInTheDocument();
    failed = false;
    fireEvent.click(screen.getByText("다시 시도"));
    expect(await screen.findByText("계획 50.5%")).toBeVisible();
  });
  it("basic CTA and tab open identical panel with dynamic issue count", async () => {
    render(
      <ProjectDrawer
        id={8}
        onClose={vi.fn()}
        onSaved={changed}
        tabs={projectProgressTabs()}
      />,
    );
    fireEvent.click(await screen.findByTestId("project-progress-open"));
    expect(await screen.findByText("계획 50.5%")).toBeVisible();
    fireEvent.click(screen.getByTestId("project-tab-basic"));
    fireEvent.click(screen.getByTestId("project-tab-progress"));
    expect(await screen.findByText("계획 50.5%")).toBeVisible();
    expect(await screen.findByRole("tab", { name: "이슈 (1)" })).toBeVisible();
  });
});
describe("SCR-UI-01 issues", () => {
  it("includes closed and registration dates while tab shows open count", async () => {
    render(
      <>
        <ProjectIssueTabLabel projectId={8} />
        <ProjectIssuePanel project={project} />
      </>,
    );
    expect(await screen.findAllByText("이슈 (1)")).toHaveLength(2);
    const table = await screen.findByRole("table");
    expect(within(table).getAllByRole("row")).toHaveLength(3);
    expect(screen.getByRole("columnheader", { name: "등록일" })).toBeVisible();
    expect(screen.getByText("조치 검토")).toBeVisible();
    expect(screen.getByTestId("project-issue-deadline-1").className).toContain(
      "overdue",
    );
    expect(
      screen.getByTestId("project-issue-deadline-2").className,
    ).not.toContain("overdue");
  });
  it("today deadline is not overdue", async () => {
    const parts = new Intl.DateTimeFormat("en", {
      timeZone: "Asia/Seoul",
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
    }).formatToParts(new Date());
    issues.items[0].ddln_dt = ["year", "month", "day"]
      .map((key) => parts.find((p) => p.type === key)!.value)
      .join("-");
    render(<ProjectIssuePanel project={project} />);
    expect(
      (await screen.findByTestId("project-issue-deadline-1")).className,
    ).not.toContain("overdue");
  });
  it("mobile create and row edit invoke external forms using selected issue", async () => {
    Object.defineProperty(window, "innerWidth", { value: 767 });
    const create = vi.fn(),
      edit = vi.fn();
    render(
      <ProjectIssuePanel project={project} onCreate={create} onEdit={edit} />,
    );
    await screen.findByText("조치 검토");
    fireEvent.click(screen.getByTestId("project-issue-create"));
    expect(create).toHaveBeenCalledWith(expect.any(Function));
    fireEvent.click(screen.getByTestId("project-issue-edit-1"));
    expect(edit).toHaveBeenCalledWith(issues.items[0], expect.any(Function));
  });
  it.each(["END", "LOST", "N"])("closed %s is read only", async (state) => {
    const edit = vi.fn();
    render(
      <ProjectIssuePanel
        project={{
          ...project,
          prj_stts_cd: state,
          use_yn: state === "N" ? "N" : "Y",
        }}
        onEdit={edit}
      />,
    );
    await screen.findByText("조치할 이슈");
    fireEvent.click(screen.getByTestId("project-issue-1"));
    expect(edit).not.toHaveBeenCalled();
    expect(
      screen.queryByTestId("project-issue-create"),
    ).not.toBeInTheDocument();
  });
  it("empty list", async () => {
    issues = { items: [], open_count: 0 };
    render(<ProjectIssuePanel project={project} />);
    expect(await screen.findByText("등록된 이슈가 없습니다.")).toBeVisible();
  });
  it("failed request is retryable and has no empty success", async () => {
    failed = true;
    render(<ProjectIssuePanel project={project} />);
    await screen.findByText("서버에 연결할 수 없습니다.");
    expect(
      screen.queryByText("등록된 이슈가 없습니다."),
    ).not.toBeInTheDocument();
    failed = false;
    fireEvent.click(screen.getByText("다시 시도"));
    expect(await screen.findByText("조치 검토")).toBeVisible();
  });
});
