import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import { ProjectMemoPanel } from "./ProjectMemoPanel";
import type { MemoProject, ProjectWeeklyMemo } from "./client";

const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
const original: ProjectWeeklyMemo = {
  memo_id: 7,
  prj_id: 3,
  wrt_dt: "2026-10-16",
  actl_prgs_rt: 42,
  cmpl_work_desc: "기존 한 일",
  plan_work_desc: "기존 할 일",
  rqst_desc: "지원 요청",
};
let project: MemoProject;
let rows: ProjectWeeklyMemo[];
let failRead: boolean;
let failure: {
  status: number;
  code: string;
  field: string | null;
  message: string;
} | null;
let fetcher: ReturnType<typeof vi.fn>;
const changed = vi.fn().mockResolvedValue(undefined);
beforeEach(() => {
  vi.useFakeTimers({ toFake: ["Date"] });
  vi.setSystemTime(new Date("2026-10-22T15:01:00Z"));
  Object.defineProperty(window, "innerWidth", {
    configurable: true,
    writable: true,
    value: 1280,
  });
  rows = [{ ...original }];
  project = {
    prj_id: 3,
    prj_nm: "선택 프로젝트",
    cntrct_ty_cd: "TK",
    use_yn: "Y",
    prj_stts_cd: "RUN",
  };
  failRead = false;
  failure = null;
  changed.mockClear();
  fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (init?.method === "POST" || init?.method === "PUT") {
      if (failure)
        return new Response(
          JSON.stringify({
            success: false,
            meta: {},
            ...failure,
            requestId: "memo-request",
          }),
          { status: failure.status },
        );
      const input = JSON.parse(init.body as string);
      const previous =
        init.method === "PUT"
          ? rows.find((r) => url.endsWith(`/${r.memo_id}`))
          : rows.find((r) => r.wrt_dt === "2026-10-23");
      const row = {
        ...input,
        memo_id: previous?.memo_id ?? 9,
        prj_id: 3,
        wrt_dt: previous?.wrt_dt ?? "2026-10-23",
      };
      rows = previous
        ? rows.map((r) => (r.memo_id === previous.memo_id ? row : r))
        : [row, ...rows];
      return response(row);
    }
    if (url === "/api/projects/3/progress") {
      if (failRead) throw new Error("offline");
      return response({
        memos: rows,
        actl_prgs_rt: rows[0]?.actl_prgs_rt ?? 0,
        plan_prgs_rt: 55,
      });
    }
    throw new Error(`Unexpected test request ${url}`);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
const panel = () =>
  render(<ProjectMemoPanel project={project} onChanged={changed} />);
const writes = () =>
  fetcher.mock.calls.filter((c) => ["POST", "PUT"].includes(c[1]?.method));
const change = (label: string, value: string) =>
  fireEvent.change(screen.getByLabelText(label), { target: { value } });
async function open() {
  fireEvent.click(await screen.findByTestId("project-memo-add"));
  return screen.findByTestId("project-memo-form");
}
async function fill(rate = "42") {
  await open();
  if (project.cntrct_ty_cd === "TK" || project.cntrct_ty_cd === "IN")
    change("실적 진척률", rate);
  change("이번 주 한 일", "완료한 업무");
  change("다음 주 할 일", "다음 업무");
}
async function save() {
  fireEvent.click(screen.getByTestId("project-memo-save"));
}

describe("weekly project memos", () => {
  it("shows newest first, only latest edit, default author, request warning and no deletion", async () => {
    rows = [{ ...original, memo_id: 6, wrt_dt: "2026-10-01" }, { ...original }];
    panel();
    await screen.findByTestId("project-memo-row-7");
    expect(screen.getAllByTestId(/project-memo-row-/)[0]).toHaveAttribute(
      "data-testid",
      "project-memo-row-7",
    );
    expect(screen.getByTestId("project-memo-edit-7")).toBeVisible();
    expect(screen.queryByTestId("project-memo-edit-6")).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "삭제" }),
    ).not.toBeInTheDocument();
    expect(
      within(screen.getByTestId("project-memo-row-7")).getByText("기본 사용자"),
    ).toBeVisible();
    expect(
      within(screen.getByTestId("project-memo-row-7")).getByText(
        "요청 지원 요청",
      ).className,
    ).toContain("request");
  });
  it("renders loading before real read completes", () => {
    fetcher.mockImplementation(() => new Promise(() => {}));
    panel();
    expect(screen.getByRole("status", { name: "불러오는 중" })).toBeVisible();
    expect(screen.queryByTestId("project-memo-add")).not.toBeInTheDocument();
  });
  it("shows empty state without invented memo", async () => {
    rows = [];
    panel();
    expect(await screen.findByText("아직 주간 메모가 없습니다.")).toBeVisible();
    expect(screen.queryByTestId(/project-memo-row-/)).not.toBeInTheDocument();
  });
  it("read error allows retry and prevents writing stale data", async () => {
    failRead = true;
    panel();
    await screen.findByRole("alert");
    expect(screen.queryByTestId("project-memo-add")).not.toBeInTheDocument();
    failRead = false;
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    expect(await screen.findByTestId("project-memo-row-7")).toBeVisible();
  });
  it("POST saves 42, closes, refreshes progress and board callback then reopens today prefilled without extra row", async () => {
    panel();
    await fill();
    await save();
    expect(await screen.findByText("주간 상태를 저장했습니다.")).toBeVisible();
    await waitFor(() =>
      expect(screen.queryByTestId("project-memo-form")).not.toBeInTheDocument(),
    );
    expect(changed).toHaveBeenCalledTimes(1);
    expect(writes()[0][0]).toBe("/api/projects/3/memos");
    expect(writes()[0][1].method).toBe("POST");
    expect(JSON.parse(writes()[0][1].body)).toEqual({
      actl_prgs_rt: 42,
      cmpl_work_desc: "완료한 업무",
      plan_work_desc: "다음 업무",
      rqst_desc: null,
    });
    await open();
    expect(
      screen.getByRole("dialog", { name: "이번 주 상태 고치기" }),
    ).toBeVisible();
    expect(screen.getByLabelText("이번 주 한 일")).toHaveValue("완료한 업무");
    change("이번 주 한 일", "두번째 한 일");
    await save();
    await waitFor(() => expect(changed).toHaveBeenCalledTimes(2));
    expect(rows.filter((r) => r.wrt_dt === "2026-10-23")).toHaveLength(1);
    expect(rows.find((r) => r.wrt_dt === "2026-10-23")?.cmpl_work_desc).toBe(
      "두번째 한 일",
    );
    expect(writes()[1][1].method).toBe("POST");
  });
  it("latest edit uses PUT path id and fixed original date, not an id or date in body", async () => {
    panel();
    fireEvent.click(await screen.findByTestId("project-memo-edit-7"));
    const form = await screen.findByTestId("project-memo-form");
    expect(within(form).getByText("선택 프로젝트 · 2026-10-16")).toBeVisible();
    change("다음 주 할 일", "수정한 할 일");
    await save();
    await waitFor(() => expect(changed).toHaveBeenCalled());
    expect(writes()[0][0]).toBe("/api/projects/3/memos/7");
    expect(writes()[0][1].method).toBe("PUT");
    const body = JSON.parse(writes()[0][1].body);
    expect(body).not.toHaveProperty("memo_id");
    expect(body).not.toHaveProperty("wrt_dt");
    expect(rows[0].wrt_dt).toBe("2026-10-16");
    expect(rows[0].plan_work_desc).toBe("수정한 할 일");
  });
  it.each(["0", "100"])("allows integer boundary %s", async (rate) => {
    panel();
    await fill(rate);
    await save();
    await waitFor(() => expect(changed).toHaveBeenCalled());
    expect(JSON.parse(writes()[0][1].body).actl_prgs_rt).toBe(Number(rate));
  });
  it.each(["105", "42.5", "", "-1"])(
    "rejects invalid rate %s with field focus and no API write",
    async (rate) => {
      panel();
      await fill(rate);
      await save();
      expect(
        await screen.findByText(
          "실적 진척률은 0~100 사이 정수로 입력해 주세요.",
        ),
      ).toBeVisible();
      expect(screen.getByLabelText("실적 진척률")).toHaveFocus();
      expect(writes()).toHaveLength(0);
    },
  );
  it.each(["이번 주 한 일", "다음 주 할 일"])(
    "requires %s and keeps form",
    async (label) => {
      panel();
      await fill();
      change(label, " ");
      await save();
      expect(
        await screen.findByText("한 일과 할 일을 적어 주세요."),
      ).toBeVisible();
      expect(screen.getByLabelText(label)).toHaveFocus();
      expect(writes()).toHaveLength(0);
    },
  );
  it("uses 200-character controls and a warning, not a block, for decreased progress", async () => {
    panel();
    await fill("20");
    for (const label of ["이번 주 한 일", "다음 주 할 일", "요청 사항"])
      expect(screen.getByLabelText(label)).toHaveAttribute("maxlength", "200");
    expect(
      screen.getByText("지난 메모(42%)보다 낮습니다.").className,
    ).toContain("warning");
    await save();
    await waitFor(() => expect(changed).toHaveBeenCalled());
  });
  it.each(["MO", "DP"] as const)(
    "%s has automatic calculation guidance, no rate and null payload",
    async (type) => {
      project.cntrct_ty_cd = type;
      panel();
      await fill();
      expect(screen.queryByLabelText("실적 진척률")).not.toBeInTheDocument();
      expect(
        screen.getByText(
          "운영형(월단위) 프로젝트는 진척률을 기간 기준으로 자동 계산합니다.",
        ),
      ).toBeVisible();
      await save();
      await waitFor(() => expect(changed).toHaveBeenCalled());
      expect(JSON.parse(writes()[0][1].body).actl_prgs_rt).toBeNull();
    },
  );
  it.each(["END", "LOST", "disabled"])(
    "%s hides all input actions but keeps records",
    async (status) => {
      if (status === "disabled") project.use_yn = "N";
      else project.prj_stts_cd = status;
      panel();
      await screen.findByTestId("project-memo-row-7");
      expect(screen.queryByTestId("project-memo-add")).not.toBeInTheDocument();
      expect(
        screen.queryByTestId("project-memo-edit-7"),
      ).not.toBeInTheDocument();
      expect(screen.getByText(/기록은 읽기만 할 수 있습니다/)).toBeVisible();
    },
  );
  it.each(["PLAN", "RUN", "HOLD"])(
    "375px allows memo create and latest edit for %s",
    async (status) => {
      window.innerWidth = 375;
      project.prj_stts_cd = status;
      panel();
      await fill();
      await save();
      await waitFor(() => expect(changed).toHaveBeenCalledTimes(1));
      fireEvent.click(screen.getByTestId("project-memo-edit-9"));
      change("다음 주 할 일", "휴대폰 수정");
      await save();
      await waitFor(() => expect(changed).toHaveBeenCalledTimes(2));
      expect(writes()[1][1].method).toBe("PUT");
    },
  );
  it("server field error preserves draft, focuses field and displays request id", async () => {
    failure = {
      status: 400,
      code: "INVALID_INPUT",
      field: "plan_work_desc",
      message: "할 일을 확인해 주세요.",
    };
    panel();
    await fill();
    await save();
    expect(await screen.findByText("문의 번호 memo-request")).toBeVisible();
    expect(screen.getByLabelText("다음 주 할 일")).toHaveFocus();
    expect(screen.getByLabelText("이번 주 한 일")).toHaveValue("완료한 업무");
    expect(changed).not.toHaveBeenCalled();
  });
  it.each(["PROJECT_CLOSED", "NOT_LATEST_MEMO"])(
    "business %s stays in modal without retrying write",
    async (code) => {
      failure = {
        status: 409,
        code,
        field: null,
        message: "가장 최근 메모만 수정할 수 있습니다.",
      };
      panel();
      fireEvent.click(await screen.findByTestId("project-memo-edit-7"));
      await save();
      expect(
        await screen.findByText("가장 최근 메모만 수정할 수 있습니다."),
      ).toBeVisible();
      expect(screen.getByTestId("project-memo-form")).toBeVisible();
      expect(changed).not.toHaveBeenCalled();
    },
  );
  it("uncertain write disables resubmit until explicit read recovery", async () => {
    failure = {
      status: 500,
      code: "INTERNAL_ERROR",
      field: null,
      message: "저장 실패",
    };
    panel();
    await fill();
    await save();
    await screen.findByText("저장 실패");
    expect(screen.getByTestId("project-memo-save")).toBeDisabled();
    expect(writes()).toHaveLength(1);
    failure = null;
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    await waitFor(() =>
      expect(screen.getByTestId("project-memo-save")).toBeEnabled(),
    );
    expect(writes()).toHaveLength(1);
  });
  it("cancel sends no write", async () => {
    panel();
    await fill();
    fireEvent.click(screen.getByTestId("project-memo-cancel"));
    expect(screen.queryByTestId("project-memo-form")).not.toBeInTheDocument();
    expect(writes()).toHaveLength(0);
  });
});
