import {
  fireEvent,
  render as renderView,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter } from "react-router-dom";
import type { ReactElement } from "react";
import { ProjectIssuePanel } from "./ProjectIssuePanel";
import { IssueForm } from "./IssueForm";
import UI31Screen from "./UI31Screen";
import {
  issueBoardProjects,
  issueToday,
  type IssueProject,
  type IssueListRow,
} from "./client";

const render = (ui: ReactElement) =>
  renderView(<MemoryRouter>{ui}</MemoryRouter>);
const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
const original: IssueListRow = {
  issue_id: 7,
  prj_id: 1,
  prj_nm: "진행 프로젝트",
  ttl_nm: "확인할 사항",
  svrt_cd: "HIGH",
  chrg_nm: "담당자",
  ddln_dt: "2020-01-01",
  issue_stts_cd: "OPEN",
  actn_desc: "확인 중",
  reg_dt: "2020-01-01",
  end_dt: null,
};
let rows: IssueListRow[], project: IssueProject;
let fetcher: ReturnType<typeof vi.fn>,
  failRead: boolean,
  failWrite: boolean,
  failOwners: boolean;
const changed = vi.fn().mockResolvedValue(undefined);
beforeEach(() => {
  rows = [{ ...original }];
  project = {
    prj_id: 1,
    prj_nm: "진행 프로젝트",
    use_yn: "Y",
    prj_stts_cd: "RUN",
  };
  failRead = false;
  failWrite = false;
  failOwners = false;
  changed.mockClear();
  Object.defineProperty(window, "innerWidth", {
    configurable: true,
    value: 375,
  });
  fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (url.startsWith("/api/issues/owner-options")) {
      if (failOwners) throw new Error("offline");
      return response([
        { name: "PM 이름" },
        { name: url.includes("projectId=2") ? "다른 담당자" : "현재 인력" },
      ]);
    }
    if (url === "/api/projects/options")
      return response([
        { id: 1, name: "진행 프로젝트" },
        { id: 2, name: "다른 프로젝트" },
      ]);
    if (url === "/api/projects/1") return response(project);
    if (url.startsWith("/api/project-board"))
      return response({
        items: [
          { prj_id: 2, prj_nm: "가 프로젝트" },
          { prj_id: 1, prj_nm: "진행 프로젝트" },
        ],
        total: 2,
        page: 0,
        size: 100,
      });
    if (init?.method && init.method !== "GET") {
      const body = JSON.parse(init.body as string);
      const field =
        body.prj_id === null
          ? "prj_id"
          : !body.ttl_nm?.trim()
            ? "ttl_nm"
            : !body.ddln_dt
              ? "ddln_dt"
              : failWrite
                ? "ttl_nm"
                : null;
      if (field)
        return new Response(
          JSON.stringify({
            success: false,
            meta: {},
            code: "INVALID_INPUT",
            field,
            message:
              field === "prj_id"
                ? "프로젝트를 골라 주세요."
                : field === "ddln_dt"
                  ? "기한을 넣어 주세요."
                  : "제목을 넣어 주세요.",
            requestId: "issue-test",
          }),
          { status: 400 },
        );
      const row = {
        ...original,
        ...body,
        issue_id: init.method === "PUT" ? 7 : 8,
        prj_id: init.method === "PUT" ? 1 : body.prj_id,
        reg_dt: issueToday(),
        end_dt: body.issue_stts_cd === "CLOSED" ? issueToday() : null,
      };
      rows =
        init.method === "PUT"
          ? rows.map((existing) => (existing.issue_id === 7 ? row : existing))
          : [...rows, row];
      return response(row);
    }
    if (url === "/api/projects/1/issues") {
      if (failRead) throw new Error("offline");
      return response({
        items: rows,
        open_count: rows.filter((row) => row.issue_stts_cd !== "CLOSED").length,
      });
    }
    if (url.startsWith("/api/issues?")) {
      if (failRead) throw new Error("offline");
      const params = new URLSearchParams(url.split("?")[1]);
      const items = rows.filter(
        (row) =>
          (params.get("status") === "CLOSED"
            ? row.issue_stts_cd === "CLOSED"
            : row.issue_stts_cd !== "CLOSED") &&
          (!params.get("severity") || row.svrt_cd === params.get("severity")) &&
          (!params.get("projectId") ||
            String(row.prj_id) === params.get("projectId")),
      );
      return response({
        items,
        total: items.length,
        open_count: rows.filter((row) => row.issue_stts_cd !== "CLOSED").length,
        page: Number(params.get("page")),
        size: Number(params.get("size")),
      });
    }
    throw new Error(`Unexpected request ${url}`);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
const change = (label: string, value: string) =>
  fireEvent.change(screen.getByLabelText(label), { target: { value } });
const writes = () =>
  fetcher.mock.calls.filter(
    ([, init]) => init?.method && init.method !== "GET",
  );
const panel = () =>
  render(<ProjectIssuePanel project={project} onChanged={changed} />);
async function add() {
  fireEvent.click(await screen.findByTestId("issue-add"));
  await screen.findByRole("option", { name: "PM 이름" });
}
async function save() {
  fireEvent.click(screen.getByTestId("issue-save"));
  await waitFor(() =>
    expect(screen.queryByTestId("issue-form")).not.toBeInTheDocument(),
  );
}

describe("project issues", () => {
  it("renders exact columns and distinct severity/status colors and overdue date", async () => {
    rows.push(
      { ...original, issue_id: 8, svrt_cd: "MID", issue_stts_cd: "DOING" },
      { ...original, issue_id: 9, svrt_cd: "LOW", issue_stts_cd: "CLOSED" },
    );
    panel();
    const first = await screen.findByTestId("issue-row-7");
    for (const label of [
      "중요도",
      "제목 · 조치 내용",
      "담당자",
      "기한",
      "상태",
      "등록일",
    ])
      expect(screen.getByRole("columnheader", { name: label })).toBeVisible();
    expect(within(first).getByText("높음").className).toContain("danger");
    expect(within(first).getByText("열림").className).toContain("info");
    expect(
      within(first).getByText(/일 지남/).parentElement?.className,
    ).toContain("overdue");
    expect(
      within(screen.getByTestId("issue-row-8")).getByText("중간").className,
    ).toContain("warning");
    expect(
      within(screen.getByTestId("issue-row-9")).queryByText(/일 지남/),
    ).not.toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "이슈 (2)" })).toBeVisible();
  });
  it("mobile registration has MID OPEN plus seven days and refreshes list and parent", async () => {
    panel();
    await add();
    expect(screen.getByLabelText("중요도")).toHaveValue("MID");
    expect(screen.getByLabelText("상태")).toHaveValue("OPEN");
    const date = new Date(`${issueToday()}T00:00:00Z`);
    date.setUTCDate(date.getUTCDate() + 7);
    expect(screen.getByLabelText("기한")).toHaveValue(
      date.toISOString().slice(0, 10),
    );
    expect(screen.getByLabelText("프로젝트")).toHaveAttribute("readonly");
    change("제목", "등록한 이슈");
    change("기한", "2020-01-01");
    await save();
    expect(await screen.findByText("등록한 이슈")).toBeVisible();
    expect(screen.getByRole("heading", { name: "이슈 (2)" })).toBeVisible();
    expect(changed).toHaveBeenCalledTimes(1);
    expect(screen.getByText("이슈를 등록했습니다.")).toBeVisible();
  });
  it("direct owner is bounded to fifty characters and snapshots name only", async () => {
    panel();
    await add();
    change("담당자", "direct");
    expect(screen.getByLabelText("담당자 직접 입력")).toHaveAttribute(
      "maxlength",
      "50",
    );
    change("담당자 직접 입력", "외부 담당자");
    change("제목", "외부 확인");
    await save();
    const body = JSON.parse(writes()[0][1].body as string);
    expect(body.chrg_nm).toBe("외부 담당자");
    expect(body).not.toHaveProperty("chrg_user_id");
  });
  it("server title validation is shown with field focus and leaves list unchanged", async () => {
    panel();
    await add();
    fireEvent.click(screen.getByTestId("issue-save"));
    await screen.findAllByText("제목을 넣어 주세요.");
    expect(screen.getByLabelText("제목")).toHaveFocus();
    expect(rows).toHaveLength(1);
    expect(changed).not.toHaveBeenCalled();
  });
  it("deadline validation is shown without closing or inserting", async () => {
    panel();
    await add();
    change("제목", "기한 검토");
    change("기한", "");
    fireEvent.click(screen.getByTestId("issue-save"));
    await screen.findAllByText("기한을 넣어 주세요.");
    expect(rows).toHaveLength(1);
    expect(screen.getByTestId("issue-form")).toBeVisible();
  });
  it("cancel does not send a write", async () => {
    panel();
    await add();
    change("제목", "취소할 입력");
    fireEvent.click(screen.getByTestId("issue-cancel"));
    expect(writes()).toHaveLength(0);
    expect(rows).toHaveLength(1);
  });
  it.each(["END", "LOST", "N"])(
    "%s project hides writes and row editor",
    async (state) => {
      project =
        state === "N"
          ? { ...project, use_yn: "N" }
          : { ...project, prj_stts_cd: state };
      panel();
      await screen.findByTestId("issue-row-7");
      expect(screen.queryByTestId("issue-add")).not.toBeInTheDocument();
      fireEvent.click(screen.getByTestId("issue-row-7"));
      expect(screen.queryByTestId("issue-form")).not.toBeInTheDocument();
    },
  );
  it("HOLD remains writable on mobile", async () => {
    project = { ...project, prj_stts_cd: "HOLD" };
    panel();
    await add();
    expect(screen.getByTestId("issue-save")).toBeEnabled();
  });
  it("editing keeps project fixed and excludes identity/date fields from PUT", async () => {
    panel();
    fireEvent.click(await screen.findByTestId("issue-row-7"));
    expect(await screen.findByLabelText("프로젝트")).toHaveAttribute(
      "readonly",
    );
    change("제목", "수정한 제목");
    change("상태", "CLOSED");
    await save();
    expect(await screen.findByText("수정한 제목")).toBeVisible();
    const [url, init] = writes()[0];
    expect(url).toBe("/api/issues/7");
    const body = JSON.parse(init.body as string);
    for (const key of ["prj_id", "issue_id", "reg_dt", "end_dt"])
      expect(body).not.toHaveProperty(key);
    expect(screen.getByRole("heading", { name: "이슈 (0)" })).toBeVisible();
    expect(screen.getByText("이슈를 저장했습니다.")).toBeVisible();
    fireEvent.click(screen.getByTestId("issue-row-7"));
    change("상태", "OPEN");
    await save();
    expect(screen.getByRole("heading", { name: "이슈 (1)" })).toBeVisible();
  });
  it("failed update retains input and original list", async () => {
    panel();
    fireEvent.click(await screen.findByTestId("issue-row-7"));
    change("제목", "수정 초안");
    failWrite = true;
    fireEvent.click(screen.getByTestId("issue-save"));
    await screen.findAllByText("제목을 넣어 주세요.");
    expect(screen.getByLabelText("제목")).toHaveValue("수정 초안");
    expect(rows[0].ttl_nm).toBe(original.ttl_nm);
  });
  it("empty list and failed read have separate states with retry", async () => {
    rows = [];
    failRead = true;
    panel();
    await screen.findByRole("button", { name: "다시 시도" });
    expect(
      screen.queryByText("등록된 이슈가 없습니다."),
    ).not.toBeInTheDocument();
    failRead = false;
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    expect(await screen.findByText("등록된 이슈가 없습니다.")).toBeVisible();
  });
  it("owner read failure still permits direct input", async () => {
    failOwners = true;
    panel();
    fireEvent.click(await screen.findByTestId("issue-add"));
    await screen.findByRole("button", { name: "다시 시도" });
    change("담당자", "direct");
    expect(screen.getByLabelText("담당자 직접 입력")).toBeEnabled();
  });
});

describe("aggregate issues screen", () => {
  it("shows initial OPEN_DOING, global count, required columns and 50 rows per page", async () => {
    render(<UI31Screen />);
    await screen.findByTestId("issue-row-7");
    expect(
      await screen.findByRole("tab", { name: "이슈 모아 보기 (1)" }),
    ).toBeVisible();
    expect(screen.getByLabelText("상태")).toHaveValue("OPEN_DOING");
    expect(screen.getByLabelText("목록 건수")).toHaveValue("50");
    for (const name of ["제목", "프로젝트", "조치 내용"])
      expect(screen.getByRole("columnheader", { name })).toBeVisible();
    expect(screen.getByTestId("issue-project-7")).toHaveAttribute(
      "href",
      "/UI-01?open=1&tab=issues",
    );
  });
  it("status severity and project are sent as AND filters and no-result wording is exact", async () => {
    render(<UI31Screen />);
    await screen.findByTestId("issue-row-7");
    change("상태", "OVERDUE");
    await waitFor(() =>
      expect(
        fetcher.mock.calls.some(([url]) => url.includes("status=OVERDUE")),
      ).toBe(true),
    );
    change("중요도", "LOW");
    change("프로젝트", "2");
    await screen.findByText("조건에 맞는 결과가 없습니다.");
    expect(
      fetcher.mock.calls.some(
        ([url]) =>
          url.includes("severity=LOW") &&
          url.includes("projectId=2") &&
          url.includes("status=OVERDUE"),
      ),
    ).toBe(true);
    change("상태", "CLOSED");
    await waitFor(() =>
      expect(
        fetcher.mock.calls.some(([url]) => url.includes("status=CLOSED")),
      ).toBe(true),
    );
    expect(
      screen.getByRole("tab", { name: "이슈 모아 보기 (1)" }),
    ).toBeVisible();
  });
  it("row opens same editor with fixed project and refreshes origin", async () => {
    render(<UI31Screen onChanged={changed} />);
    fireEvent.click(await screen.findByTestId("issue-row-7"));
    await screen.findByRole("dialog");
    expect(screen.getByTestId("issue-form")).toHaveAttribute(
      "data-screen-id",
      "SCR-UI-01-M16",
    );
    change("제목", "모아 보기 수정");
    await save();
    expect(await screen.findByText("모아 보기 수정")).toBeVisible();
    expect(changed).toHaveBeenCalledTimes(1);
  });
  it("board create starts unselected, rejects missing project, loads owners after selection, then refreshes", async () => {
    render(<UI31Screen onChanged={changed} />);
    fireEvent.click(await screen.findByTestId("issue-list-add"));
    await screen.findByTestId("issue-form");
    const form = within(screen.getByTestId("issue-form"));
    expect(form.getByText("프로젝트를 먼저 고르세요")).toBeVisible();
    expect(form.getByLabelText("담당자")).toBeDisabled();
    await waitFor(() =>
      expect(form.getByRole("option", { name: "가 프로젝트" })).toBeVisible(),
    );
    const options = within(form.getByLabelText("프로젝트")).getAllByRole(
      "option",
    );
    expect(options.map((option) => option.textContent)).toEqual([
      "선택하세요",
      "가 프로젝트",
      "진행 프로젝트",
    ]);
    fireEvent.click(screen.getByTestId("issue-save"));
    await screen.findAllByText("프로젝트를 골라 주세요.");
    expect(rows).toHaveLength(1);
    fireEvent.change(form.getByLabelText("프로젝트"), {
      target: { value: "2" },
    });
    await form.findByRole("option", { name: "다른 담당자" });
    fireEvent.change(form.getByLabelText("프로젝트"), {
      target: { value: "1" },
    });
    await form.findByRole("option", { name: "현재 인력" });
    fireEvent.change(form.getByLabelText("제목"), {
      target: { value: "현황판 등록" },
    });
    await save();
    expect(await screen.findByText("현황판 등록")).toBeVisible();
    expect(changed).toHaveBeenCalledTimes(1);
    expect(
      screen.getByRole("tab", { name: "이슈 모아 보기 (2)" }),
    ).toBeVisible();
  });
  it("collects all unfiltered board pages before sorting project choices", async () => {
    fetcher.mockImplementation(async (url: string) =>
      response(
        url.includes("page=0")
          ? {
              items: Array.from({ length: 100 }, (_, i) => ({
                prj_id: i + 1,
                prj_nm: `나 ${i}`,
              })),
              total: 101,
            }
          : { items: [{ prj_id: 101, prj_nm: "가" }], total: 101 },
      ),
    );
    const projects = await issueBoardProjects();
    expect(projects).toHaveLength(101);
    expect(projects[0].prj_id).toBe(101);
    expect(fetcher).toHaveBeenCalledWith(
      "/api/project-board?page=1&size=100",
      expect.anything(),
    );
  });
  it("incomplete board pages are an error, not a partial project choice list", async () => {
    fetcher.mockImplementation(async () => response({ items: [], total: 2 }));
    await expect(issueBoardProjects()).rejects.toThrow(
      "프로젝트 목록을 모두 불러오지 못했습니다.",
    );
  });
  it("closed project fetched for aggregate editor hides save", async () => {
    project = { ...project, prj_stts_cd: "END" };
    render(<UI31Screen />);
    fireEvent.click(await screen.findByTestId("issue-row-7"));
    await screen.findByRole("dialog");
    expect(screen.queryByTestId("issue-save")).not.toBeInTheDocument();
  });
  it("a failed project-choice request shows retry rather than invented choices", async () => {
    fetcher.mockImplementation(async () => {
      throw new Error("offline");
    });
    render(
      <IssueForm reload={changed} onSaved={() => {}} onCancel={() => {}} />,
    );
    await screen.findByRole("button", { name: "다시 시도" });
    expect(screen.getByLabelText("프로젝트")).toBeDisabled();
    expect(screen.getByTestId("issue-save")).toBeDisabled();
  });
});
