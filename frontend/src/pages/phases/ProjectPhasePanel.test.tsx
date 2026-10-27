import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import { ProjectPhasePanel } from "./ProjectPhasePanel";
import type { ProjectPhase, ProjectPhaseProject } from "./client";

const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
const original: ProjectPhase = {
  phs_id: 7,
  prj_id: 3,
  phs_seq: 1,
  phs_nm: "개발",
  wght_rt: 35.25,
  plan_bgn_dt: "2020-10-01",
  plan_end_dt: "2099-10-31",
  cmpl_dt: null,
  status: "진행 중",
};
let rows: ProjectPhase[];
let project: ProjectPhaseProject;
let fetcher: ReturnType<typeof vi.fn>;
let failRead: boolean;
let failure: {
  code: string;
  message: string;
  field?: string;
  status: number;
} | null;
const changed = vi.fn().mockResolvedValue(undefined);
beforeEach(() => {
  Object.defineProperty(window, "innerWidth", {
    configurable: true,
    writable: true,
    value: 1280,
  });
  rows = [
    { ...original },
    {
      ...original,
      phs_id: 8,
      phs_seq: 2,
      phs_nm: "테스트",
      cmpl_dt: "2026-10-01",
      status: "완료",
    },
    {
      ...original,
      phs_id: 9,
      phs_seq: 3,
      phs_nm: "오픈",
      plan_bgn_dt: "2099-11-01",
      plan_end_dt: "2099-11-30",
      status: "예정",
    },
  ];
  project = { prj_id: 3, use_yn: "Y", prj_stts_cd: "RUN" };
  failRead = false;
  failure = null;
  changed.mockClear();
  fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (init?.method && init.method !== "GET") {
      if (failure)
        return new Response(
          JSON.stringify({
            success: false,
            meta: {},
            code: failure.code,
            message: failure.message,
            field: failure.field ?? null,
            requestId: "phase-request",
          }),
          { status: failure.status },
        );
      const kind = url.endsWith("/uncomplete")
        ? "uncomplete"
        : url.endsWith("/complete")
          ? "complete"
          : "period";
      const id = Number(url.split("/").at(kind === "period" ? -1 : -2));
      rows = rows.map((row) =>
        row.phs_id !== id
          ? row
          : kind === "complete"
            ? { ...row, cmpl_dt: "2026-10-23", status: "완료" }
            : kind === "uncomplete"
              ? { ...row, cmpl_dt: null, status: "진행 중" }
              : { ...row, ...JSON.parse(init.body as string) },
      );
      return response(rows.find((row) => row.phs_id === id));
    }
    if (url === "/api/projects/3/progress") {
      if (failRead) throw new Error("offline");
      return response({ phases: rows });
    }
    throw new Error(`Unexpected test request ${url}`);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.unstubAllGlobals();
});
const writes = () =>
  fetcher.mock.calls.filter(
    ([, init]) => init?.method && init.method !== "GET",
  );
const open = () =>
  render(<ProjectPhasePanel project={project} onChanged={changed} />);
const row = (id: number) => screen.getByTestId(`project-phase-row-${id}`);
const dialog = () => within(screen.getByRole("dialog"));

describe("project phase actions", () => {
  it("renders API dates, decimal weights, status badges and state-specific controls", async () => {
    open();
    await screen.findByText("개발");
    expect(within(row(7)).getByText("35.25%")).toBeInTheDocument();
    expect(within(row(7)).getByText("진행 중")).toBeInTheDocument();
    expect(
      within(row(8)).queryByRole("button", { name: "기간 수정" }),
    ).not.toBeInTheDocument();
    expect(
      within(row(9)).queryByRole("button", { name: "완료" }),
    ).not.toBeInTheDocument();
    expect(
      within(row(9)).getByRole("button", { name: "기간 수정" }),
    ).toBeInTheDocument();
  });
  it("completion confirmation sends bodyless selected phase POST, refreshes and reports success", async () => {
    open();
    await screen.findByText("개발");
    fireEvent.click(within(row(7)).getByRole("button", { name: "완료" }));
    expect(screen.getByRole("dialog")).toHaveAccessibleName(
      "「개발」 단계를 완료로 바꿀까요?",
    );
    expect(dialog().getByText(/서버 기준 오늘/)).toBeInTheDocument();
    fireEvent.click(dialog().getByRole("button", { name: "완료" }));
    await waitFor(() =>
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument(),
    );
    expect(writes()).toHaveLength(1);
    expect(writes()[0]).toEqual([
      "/api/projects/3/phases/7/complete",
      expect.objectContaining({ method: "POST" }),
    ]);
    expect(writes()[0][1].body).toBeUndefined();
    expect(within(row(7)).getByText("2026-10-23")).toBeInTheDocument();
    expect(
      within(row(7)).getByText("완료", { selector: "span" }),
    ).toBeInTheDocument();
    expect(screen.getByText("개발 단계를 완료했습니다.")).toBeInTheDocument();
    expect(changed).toHaveBeenCalledTimes(1);
  });
  it("cancel completion makes no request and preserves dates", async () => {
    open();
    await screen.findByText("개발");
    fireEvent.click(within(row(7)).getByRole("button", { name: "완료" }));
    fireEvent.click(dialog().getByRole("button", { name: "취소" }));
    expect(writes()).toHaveLength(0);
    expect(rows[0].cmpl_dt).toBeNull();
  });
  it("cancel uncompletion preserves date; reopen and confirm clears it after reload", async () => {
    open();
    await screen.findByText("개발");
    fireEvent.click(within(row(8)).getByRole("button", { name: "완료 취소" }));
    fireEvent.click(dialog().getByRole("button", { name: "취소" }));
    await waitFor(() =>
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument(),
    );
    expect(within(row(8)).getByText("2026-10-01")).toBeInTheDocument();
    expect(writes()).toHaveLength(0);
    fireEvent.click(within(row(8)).getByRole("button", { name: "완료 취소" }));
    fireEvent.click(dialog().getByRole("button", { name: "완료 취소" }));
    await waitFor(() =>
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument(),
    );
    expect(writes()[0][0]).toBe("/api/projects/3/phases/8/uncomplete");
    expect(writes()[0][1].body).toBeUndefined();
    expect(within(row(8)).queryByText("2026-10-01")).not.toBeInTheDocument();
    expect(within(row(8)).getByText("진행 중")).toBeInTheDocument();
  });
  it("period form edits only dates, accepts overlaps and gaps, and refreshes after PUT", async () => {
    open();
    await screen.findByText("개발");
    fireEvent.click(within(row(7)).getByRole("button", { name: "기간 수정" }));
    expect(dialog().getAllByRole("button")).toHaveLength(2);
    expect(dialog().queryByLabelText("단계명")).not.toBeInTheDocument();
    fireEvent.change(dialog().getByLabelText("예정 시작일"), {
      target: { value: "2026-09-01" },
    });
    fireEvent.change(dialog().getByLabelText("예정 종료일"), {
      target: { value: "2099-12-31" },
    });
    fireEvent.click(dialog().getByRole("button", { name: "저장" }));
    await waitFor(() =>
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument(),
    );
    expect(writes()[0][0]).toBe("/api/projects/3/phases/7");
    expect(JSON.parse(writes()[0][1].body)).toEqual({
      plan_bgn_dt: "2026-09-01",
      plan_end_dt: "2099-12-31",
    });
    expect(
      within(row(7)).getByText("2026-09-01 ~ 2099-12-31"),
    ).toBeInTheDocument();
    expect(within(row(7)).getByText("35.25%")).toBeInTheDocument();
    expect(rows).toHaveLength(3);
    expect(changed).toHaveBeenCalledTimes(1);
  });
  it("reversed period shows exact field error without sending a request", async () => {
    open();
    await screen.findByText("개발");
    fireEvent.click(within(row(7)).getByRole("button", { name: "기간 수정" }));
    fireEvent.change(dialog().getByLabelText("예정 시작일"), {
      target: { value: "2026-11-01" },
    });
    fireEvent.change(dialog().getByLabelText("예정 종료일"), {
      target: { value: "2026-10-01" },
    });
    fireEvent.click(dialog().getByRole("button", { name: "저장" }));
    expect(
      dialog().getByText("예정 종료일은 시작일과 같거나 뒤여야 합니다."),
    ).toBeInTheDocument();
    expect(dialog().getByLabelText("예정 종료일")).toHaveAttribute(
      "aria-invalid",
      "true",
    );
    expect(writes()).toHaveLength(0);
    expect(rows[0]).toEqual(original);
  });
  it("cancel period keeps persisted dates unchanged", async () => {
    open();
    await screen.findByText("개발");
    fireEvent.click(within(row(7)).getByRole("button", { name: "기간 수정" }));
    fireEvent.change(dialog().getByLabelText("예정 시작일"), {
      target: { value: "2026-09-01" },
    });
    fireEvent.click(dialog().getByRole("button", { name: "취소" }));
    expect(rows[0]).toEqual(original);
    expect(writes()).toHaveLength(0);
  });
  it.each(["END", "LOST", "N"])(
    "hides all input controls for %s projects",
    async (state) => {
      project = {
        ...project,
        use_yn: state === "N" ? "N" : "Y",
        prj_stts_cd: state === "N" ? "RUN" : state,
      };
      open();
      await screen.findByText("개발");
      expect(
        screen.queryByRole("button", { name: "완료" }),
      ).not.toBeInTheDocument();
      expect(
        screen.queryByRole("button", { name: "완료 취소" }),
      ).not.toBeInTheDocument();
      expect(
        screen.queryByRole("button", { name: "기간 수정" }),
      ).not.toBeInTheDocument();
      expect(screen.getByText(/조회만 가능합니다/)).toBeInTheDocument();
    },
  );
  it.each(["PLAN", "RUN", "HOLD"])(
    "keeps completion available for writable %s",
    async (state) => {
      project.prj_stts_cd = state;
      open();
      await screen.findByText("개발");
      expect(
        within(row(7)).getByRole("button", { name: "완료" }),
      ).toBeInTheDocument();
    },
  );
  it("mobile keeps completion and uncompletion, but hides period editing", async () => {
    Object.defineProperty(window, "innerWidth", {
      value: 375,
      configurable: true,
    });
    open();
    await screen.findByText("개발");
    expect(
      screen.queryByRole("button", { name: "기간 수정" }),
    ).not.toBeInTheDocument();
    expect(
      within(row(7)).getByRole("button", { name: "완료" }),
    ).toBeInTheDocument();
    expect(
      within(row(8)).getByRole("button", { name: "완료 취소" }),
    ).toBeInTheDocument();
  });
  it("null dates render dashes and cannot complete without a start", async () => {
    rows = [
      { ...original, plan_bgn_dt: null, plan_end_dt: null, status: "예정" },
    ];
    open();
    await screen.findByText("개발");
    expect(screen.getByText("– ~ –")).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "완료" }),
    ).not.toBeInTheDocument();
  });
  it("zero phases render the specified empty state without fabricating rows", async () => {
    rows = [];
    open();
    expect(await screen.findByText("단계 표가 없습니다.")).toBeInTheDocument();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
  });
  it("read failure presents retry and no action controls until successful reload", async () => {
    failRead = true;
    open();
    await screen.findByText("서버에 연결할 수 없습니다.");
    expect(screen.queryByText("개발")).not.toBeInTheDocument();
    failRead = false;
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    expect(await screen.findByText("개발")).toBeInTheDocument();
  });
  it.each([
    ["ALREADY_DONE", "이미 완료된 단계입니다.", "complete"],
    ["PHASE_NOT_STARTED", "아직 시작하지 않은 단계입니다.", "complete"],
    ["PROJECT_CLOSED", "입력할 수 없는 프로젝트입니다.", "uncomplete"],
    ["PHASE_DONE", "완료된 단계의 기간은 수정할 수 없습니다.", "period"],
  ])(
    "%s keeps dialog and original data, shows request id",
    async (code, message, kind) => {
      failure = { code, message, status: 409 };
      open();
      await screen.findByText("개발");
      fireEvent.click(
        screen.getByTestId(`phase-${kind}-${kind === "uncomplete" ? 8 : 7}`),
      );
      fireEvent.click(
        dialog().getByRole("button", {
          name:
            kind === "complete"
              ? "완료"
              : kind === "uncomplete"
                ? "완료 취소"
                : "저장",
        }),
      );
      expect(await screen.findByText(message)).toBeInTheDocument();
      expect(screen.getByRole("dialog")).toBeInTheDocument();
      expect(screen.getByText(/phase-request/)).toBeInTheDocument();
      expect(rows[0]).toEqual(original);
      expect(changed).toHaveBeenCalledTimes(code === "ALREADY_DONE" ? 1 : 0);
    },
  );
  it("server field error keeps edited values and highlights singular field", async () => {
    failure = {
      code: "INVALID_INPUT",
      message: "날짜를 확인하세요.",
      status: 400,
      field: "plan_end_dt",
    };
    open();
    await screen.findByText("개발");
    fireEvent.click(screen.getByTestId("phase-period-7"));
    fireEvent.change(dialog().getByLabelText("예정 시작일"), {
      target: { value: "2026-09-01" },
    });
    fireEvent.click(dialog().getByRole("button", { name: "저장" }));
    await waitFor(() =>
      expect(dialog().getByLabelText("예정 종료일")).toHaveAttribute(
        "aria-invalid",
        "true",
      ),
    );
    expect(dialog().getByLabelText("예정 시작일")).toHaveValue("2026-09-01");
  });
});
