import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { MemoryRouter, useLocation } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import UI31Screen from "./UI31Screen";
import {
  ProjectProgressBars,
  ProjectTrafficLight,
} from "./ProjectProgressWidgets";
import type { BoardData, BoardRow, ProgressParameter } from "./boardClient";
import { response, failure } from "../developers/DeveloperTestSupport";

const row: BoardRow = {
  prj_id: 41,
  prj_no: "P-2641",
  prj_nm: "원가 검토",
  custName: "고객사",
  cntrct_ty_cd: "TK",
  pmName: "PM 미지정",
  currentPhase: {
    phs_id: 7,
    prj_id: 41,
    phs_seq: 2,
    phs_nm: "개발",
    wght_rt: 50,
    plan_bgn_dt: null,
    plan_end_dt: null,
    cmpl_dt: null,
    status: "예정",
  },
  plan_prgs_rt: 52,
  actl_prgs_rt: 42,
  schedule_diff_rt: -10,
  cost_use_rt: 57.5,
  cost_progress_diff_rt: 15.5,
  cumulative_cost_amt: 230000000,
  budget_amt: 400000000,
  light: "위험",
  reasons: ["원가 사용률이 진척률보다 15.5%p 높음 — 원가 초과 위험"],
  memo_elapsed_days: 15,
  last_memo_dt: "2026-10-08",
  open_issue_count: 2,
  overdue_issue_count: 1,
};
const normal: BoardRow = {
  ...row,
  prj_id: 42,
  prj_nm: "정상 업무",
  light: "정상",
  cntrct_ty_cd: "MO",
  currentPhase: null,
  memo_elapsed_days: null,
  last_memo_dt: null,
  cost_use_rt: null,
  cost_progress_diff_rt: null,
  cumulative_cost_amt: null,
  budget_amt: null,
  reasons: ["계획대로 진행 중입니다."],
};
const summary: BoardData["summary"] = {
  project_count: 2,
  normal_count: 1,
  warn_count: 0,
  risk_count: 1,
  open_issue_count: 4,
  high_open_issue_count: 2,
  overdue_issue_count: 2,
  stale_project_count: 1,
};
const params: ProgressParameter[] = Object.entries({
  SIG_DELAY_WARN: 6,
  SIG_DELAY_RISK: 11,
  SIG_COST_WARN: 7,
  SIG_COST_RISK: 16,
  SIG_STALE_DAYS: 20,
  BUDGET_RATE: 75,
}).map(([key, value]) => ({
  oprt_std_id: key,
  aply_bgn_ym: "2026-01",
  oprt_std_nm: key,
  num_val: value,
  txt_val: null,
  unit_nm: "%",
  use_plc_desc: null,
}));
function Location() {
  const l = useLocation();
  return (
    <output aria-label="현재 주소">
      {l.pathname}
      {l.search}
    </output>
  );
}
function mount(props: Parameters<typeof UI31Screen>[0] = {}) {
  return render(
    <MemoryRouter initialEntries={["/UI-31"]}>
      <Location />
      <UI31Screen {...props} />
    </MemoryRouter>,
  );
}
function boardServer() {
  const state = {
    fail: false,
    failParams: false,
    empty: false,
    stale: true,
    total: 2,
  };
  const fetcher = vi.fn(async (url: string) => {
    if (url === "/api/params")
      return state.failParams ? failure("month") : response(params);
    if (url === "/api/codes/CONTRACT_TYPE")
      return response([
        { code: "TK", name: "턴키" },
        { code: "MO", name: "월단위" },
      ]);
    if (url === "/api/sales-parts/options")
      return response([{ id: "9", name: "시험 파트", hq: false }]);
    if (url.startsWith("/api/project-board?")) {
      if (state.fail) return failure(null, 500);
      const q = new URLSearchParams(url.split("?")[1]);
      let items = state.empty ? [] : [row, normal];
      if (q.get("light"))
        items = items.filter((r) => r.light === q.get("light"));
      if (q.get("type"))
        items = items.filter((r) => r.cntrct_ty_cd === q.get("type"));
      return response({
        summary,
        items,
        total: state.empty ? 0 : state.total === 2 ? items.length : state.total,
        page: Number(q.get("page")),
        size: Number(q.get("size")),
        stale_projects: state.stale
          ? [{ prj_id: 41, prj_nm: "원가 검토", memo_elapsed_days: 15 }]
          : [],
      });
    }
    throw new Error(`Unexpected request ${url}`);
  });
  vi.stubGlobal("fetch", fetcher);
  return { state, fetcher };
}
beforeEach(() => {
  localStorage.clear();
  sessionStorage.clear();
});
afterEach(() => {
  vi.unstubAllGlobals();
});

describe("프로젝트 현황판", () => {
  it("renders five global cards, reference columns, reasons, cost details and current DB thresholds", async () => {
    boardServer();
    mount();
    await screen.findByTestId("board-row-41");
    expect(screen.getByTestId("board-summary-projects")).toHaveTextContent("2");
    expect(screen.getByTestId("board-summary-risk")).toHaveTextContent("1");
    expect(screen.getByTestId("board-summary-issues")).toHaveTextContent("4");
    expect(screen.getByTestId("board-summary-overdue")).toHaveTextContent("2");
    expect(screen.getByTestId("board-summary-stale")).toHaveTextContent("1");
    expect(
      screen.getByRole("columnheader", { name: "마지막 업데이트" }),
    ).toBeInTheDocument();
  });
  it("uses dynamic calculations, amount tooltip and distinct normal/warning/danger visuals", async () => {
    boardServer();
    mount();
    await screen.findByTestId("board-row-41");
    expect(screen.getByLabelText("진척률·신호등 계산 설명")).toHaveTextContent(
      "75%",
    );
    expect(screen.getByLabelText("진척률·신호등 계산 설명")).toHaveTextContent(
      "6%p",
    );
    expect(screen.getByLabelText("진척률·신호등 계산 설명")).toHaveTextContent(
      "11%p",
    );
    expect(screen.getByLabelText("진척률·신호등 계산 설명")).toHaveTextContent(
      "7%p",
    );
    expect(screen.getByLabelText("진척률·신호등 계산 설명")).toHaveTextContent(
      "16%p",
    );
    expect(screen.getByLabelText("진척률·신호등 계산 설명")).toHaveTextContent(
      "20일 초과",
    );
    expect(
      screen.getByTitle("누적 원가 230,000,000원 / 예산 400,000,000원"),
    ).toHaveTextContent("57.5%");
    expect(
      within(screen.getByTestId("board-row-41")).getByText("위험").className,
    ).toContain("danger");
    expect(
      within(screen.getByTestId("board-row-42")).getByText("정상").className,
    ).toContain("info");
    expect(
      screen.queryByRole("button", { name: /메모/ }),
    ).not.toBeInTheDocument();
  });
  it("applies combined filters without changing summary and uses zero based paging", async () => {
    const { fetcher } = boardServer();
    mount();
    await screen.findByTestId("board-row-41");
    fireEvent.change(screen.getByLabelText("신호등"), {
      target: { value: "위험" },
    });
    await waitFor(() =>
      expect(screen.queryByTestId("board-row-42")).not.toBeInTheDocument(),
    );
    fireEvent.change(screen.getByLabelText("계약유형"), {
      target: { value: "TK" },
    });
    fireEvent.change(screen.getByLabelText("영업 파트"), {
      target: { value: "9" },
    });

    await waitFor(() =>
      expect(
        fetcher.mock.calls.some(
          ([url]) => url.includes("type=TK") && url.includes("part=9"),
        ),
      ).toBe(true),
    );
    expect(screen.getByTestId("board-summary-projects")).toHaveTextContent("2");
    expect(screen.getByTestId("board-summary-issues")).toHaveTextContent("4");
    expect(fetcher.mock.calls[0][0]).toContain("page=0&size=50");
  });
  it("opens selected project progress from stale notice and row", async () => {
    boardServer();
    mount();
    await screen.findByTestId("board-stale-41");
    expect(screen.getByTestId("board-stale-41")).toHaveTextContent("(15일)");
    fireEvent.click(screen.getByTestId("board-stale-41"));
    expect(screen.getByLabelText("현재 주소")).toHaveTextContent(
      "/UI-01?open=41&tab=progress",
    );
  });
  it("row navigation uses its own identity rather than a sample id", async () => {
    boardServer();
    mount();
    await screen.findByTestId("board-row-42");
    fireEvent.click(screen.getByTestId("board-row-42"));
    expect(screen.getByLabelText("현재 주소")).toHaveTextContent(
      "/UI-01?open=42&tab=progress",
    );
  });
  it("hides cost ratios for non TK and renders missing phases and dates safely", async () => {
    boardServer();
    mount();
    await screen.findByTestId("board-row-42");
    const r = within(screen.getByTestId("board-row-42"));
    expect(r.getByText("단계 표가 없습니다.")).toBeInTheDocument();
    expect(r.getByText("메모없음")).toBeInTheDocument();
    expect(r.queryByTitle(/누적 원가/)).not.toBeInTheDocument();
  });
  it("empty condition renders the exact message and resets filters", async () => {
    const { state, fetcher } = boardServer();
    state.empty = true;
    state.stale = false;
    mount();
    expect(
      await screen.findByText("조건에 맞는 결과가 없습니다."),
    ).toBeInTheDocument();
    expect(screen.queryByTestId("board-stale-41")).not.toBeInTheDocument();
    fireEvent.click(screen.getByTestId("board-empty-reset"));
    await waitFor(() =>
      expect(
        fetcher.mock.calls.filter(([url]) =>
          url.startsWith("/api/project-board?"),
        ).length,
      ).toBe(2),
    );
  });
  it("shows API failure with retry, no fabricated rows", async () => {
    const { state } = boardServer();
    state.fail = true;
    mount();
    await screen.findByRole("alert");
    expect(screen.queryByTestId("board-row-41")).not.toBeInTheDocument();
    state.fail = false;
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    await screen.findByTestId("board-row-41");
  });
  it("missing parameters remain an error rather than invented explanation defaults", async () => {
    const { state } = boardServer();
    state.failParams = true;
    mount();
    await screen.findByRole("alert");
    expect(
      screen.queryByLabelText("진척률·신호등 계산 설명"),
    ).not.toBeInTheDocument();
    state.failParams = false;
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    await screen.findByLabelText("진척률·신호등 계산 설명");
  });
  it("sort and size controls send approved scalar field names", async () => {
    const { state, fetcher } = boardServer();
    state.total = 101;
    mount();
    await screen.findByTestId("board-row-41");
    fireEvent.click(screen.getByTestId("board-sort-currentphase"));
    await waitFor(() =>
      expect(
        fetcher.mock.calls.some(([url]) =>
          url.includes("sort=currentPhase%2Casc"),
        ),
      ).toBe(true),
    );
    await screen.findByLabelText("목록 건수");
    fireEvent.change(screen.getByLabelText("목록 건수"), {
      target: { value: "20" },
    });
    await waitFor(() =>
      expect(fetcher.mock.calls.some(([url]) => url.includes("size=20"))).toBe(
        true,
      ),
    );
    await screen.findByRole("button", { name: "다음 페이지" });
    fireEvent.click(screen.getByRole("button", { name: "다음 페이지" }));
    await waitFor(() =>
      expect(fetcher.mock.calls.some(([url]) => url.includes("page=1"))).toBe(
        true,
      ),
    );
  });
  it("composes other owners' issue screen and passes export only supported filters", async () => {
    boardServer();
    const download = vi.fn().mockResolvedValue(undefined);
    mount({
      issuesPanel: <p>이슈 목록</p>,
      issueOpenCount: 11,
      onDownload: download,
    });
    await screen.findByTestId("board-row-41");
    fireEvent.click(screen.getByTestId("board-download"));
    await waitFor(() =>
      expect(download).toHaveBeenCalledWith({ light: "", type: "", part: "" }),
    );
    fireEvent.click(screen.getByRole("tab", { name: "이슈 모아 보기 (11)" }));
    expect(screen.getByTestId("board-issues-panel")).toHaveTextContent(
      "이슈 목록",
    );
  });
  it("progress bar danger starts exactly ten points behind plan", () => {
    const { rerender } = render(<ProjectProgressBars plan={50} actual={40} />);
    expect(
      screen.getByRole("meter", { name: "실적 진척률" }).firstElementChild
        ?.className,
    ).toContain("riskBar");
    rerender(<ProjectProgressBars plan={50} actual={40.1} />);
    expect(
      screen.getByRole("meter", { name: "실적 진척률" }).firstElementChild
        ?.className,
    ).toContain("actualBar");
  });
  it("warning light uses warning tone and preserves server reason", () => {
    render(
      <ProjectTrafficLight
        light="주의"
        reasons={["15일째 상태 업데이트 없음"]}
      />,
    );
    expect(screen.getByText("주의").className).toContain("warning");
    expect(screen.getByTitle("15일째 상태 업데이트 없음")).toHaveAttribute(
      "tabindex",
      "0",
    );
  });
});
