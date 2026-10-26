import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import { ProjectAssignments, ProjectHistory } from "./ProjectReadPanels";
import { ProjectLifecycleActions, ProjectExportButton } from "./ProjectActions";
import { ProjectSelect } from "./ProjectSelect";
import { getProjectExport, saveProjectWorkbook } from "./client";

const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
let fetcher: ReturnType<typeof vi.fn>;
let rows: unknown[];
let failed: boolean;
let blocked: boolean;
const reload = vi.fn().mockResolvedValue(undefined),
  complete = vi.fn();
beforeEach(() => {
  Object.defineProperty(window, "innerWidth", {
    configurable: true,
    writable: true,
    value: 1280,
  });
  rows = [];
  failed = false;
  blocked = false;
  reload.mockClear();
  complete.mockClear();
  fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (url === "/api/codes/ASSIGN_STATUS")
      return response([
        { code: "ACTIVE", name: "유효" },
        { code: "ENDED", name: "종료" },
      ]);
    if (failed) throw new Error("offline");
    if (init?.method === "POST") {
      if (blocked)
        return new Response(
          JSON.stringify({
            success: false,
            meta: {},
            code: "PROJECT_IN_USE",
            message:
              "투입 1건 · 청구 단계 0건 · 배분표 0건 · 직접 경비 0건이 있어 사용 안 함으로 바꿀 수 없습니다.",
            requestId: "project-request",
          }),
          { status: 409 },
        );
      return response({
        prj_id: 8,
        use_yn: url.endsWith("enable") ? "Y" : "N",
      });
    }
    if (url.startsWith("/api/projects/export?"))
      return response({
        file_name: "프로젝트_2026_20261023.xlsx",
        media_type:
          "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        content_base64: btoa("PK"),
      });
    return response(rows);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
const writes = () => fetcher.mock.calls.filter((c) => c[1]?.method === "POST");
const actions = (useYn: "Y" | "N" = "Y") =>
  render(
    <ProjectLifecycleActions
      projectId={8}
      useYn={useYn}
      reload={reload}
      onComplete={complete}
    />,
  );
describe("project lifecycle panels at /UI-01", () => {
  it("assignment projection shows rates, dates and statuses but never prices", async () => {
    rows = [
      {
        name: "개발자",
        job: "백엔드",
        beginDate: "2026-01-01",
        endDate: "2026-12-31",
        assignmentRate: 0.5,
        status: "ACTIVE",
      },
    ];
    render(<ProjectAssignments projectId={8} />);
    expect(await screen.findByText("개발자")).toBeVisible();
    expect(screen.getByText("투입 인력 (1)")).toBeVisible();
    expect(screen.getByText("50%")).toBeVisible();
    expect(screen.getByText("2026-01-01 ~ 2026-12-31")).toBeVisible();
    await waitFor(() =>
      expect(screen.getByText("유효").className).toContain("info"),
    );
    expect(screen.queryByText(/급여|단가/)).not.toBeInTheDocument();
    expect(writes()).toHaveLength(0);
  });
  it("empty assignment message is exact and has no fake table", async () => {
    render(<ProjectAssignments projectId={8} />);
    expect(await screen.findByText("투입된 인력이 없습니다.")).toBeVisible();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
  });
  it("failed assignment request retries without showing empty success", async () => {
    failed = true;
    render(<ProjectAssignments projectId={8} />);
    await screen.findByText("서버에 연결할 수 없습니다.");
    expect(
      screen.queryByText("투입된 인력이 없습니다."),
    ).not.toBeInTheDocument();
    failed = false;
    fireEvent.click(screen.getByText("다시 시도"));
    expect(await screen.findByText("투입된 인력이 없습니다.")).toBeVisible();
  });
  it("history shows all five columns and before/after values", async () => {
    rows = [
      {
        changedAt: "2026-10-23T09:00:00",
        field: "use_yn",
        before: "Y",
        after: "N",
        actor: null,
      },
    ];
    render(<ProjectHistory projectId={8} />);
    await screen.findByText("사용 여부");
    for (const label of ["일시", "항목", "바꾸기 전", "바꾼 뒤", "처리자"])
      expect(screen.getByRole("columnheader", { name: label })).toBeVisible();
    expect(screen.getByText("기본 사용자")).toBeVisible();
    expect(screen.getByText("Y")).toBeVisible();
    expect(screen.getByText("N")).toBeVisible();
  });
  it("history empty state", async () => {
    render(<ProjectHistory projectId={8} />);
    expect(await screen.findByText("아직 바꾼 기록이 없습니다.")).toBeVisible();
  });
  it("history failed request is not empty success", async () => {
    failed = true;
    render(<ProjectHistory projectId={8} />);
    await screen.findByText("서버에 연결할 수 없습니다.");
    expect(
      screen.queryByText("아직 바꾼 기록이 없습니다."),
    ).not.toBeInTheDocument();
  });
  it("cancel confirmation makes no request", () => {
    actions();
    fireEvent.click(screen.getByTestId("project-disable-open"));
    expect(
      screen.getByText(
        "목록과 선택 목록에서 빠집니다. 기록은 그대로 남습니다.",
      ),
    ).toBeVisible();
    fireEvent.click(screen.getByTestId("project-disable-cancel"));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(writes()).toHaveLength(0);
  });
  it("confirmed disable uses selected ID and refreshes before success", async () => {
    actions();
    fireEvent.click(screen.getByTestId("project-disable-open"));
    fireEvent.click(screen.getByTestId("project-disable-confirm"));
    await waitFor(() =>
      expect(complete).toHaveBeenCalledWith("사용 안 함으로 변경했습니다."),
    );
    expect(reload).toHaveBeenCalledOnce();
    expect(writes()[0][0]).toBe("/api/projects/8/disable");
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });
  it("blocking conflict replaces confirmation with counts and acknowledgement", async () => {
    blocked = true;
    actions();
    fireEvent.click(screen.getByTestId("project-disable-open"));
    fireEvent.click(screen.getByTestId("project-disable-confirm"));
    await screen.findByText("사용 안 함으로 바꿀 수 없습니다");
    expect(screen.getByText(/투입 1건/)).toBeVisible();
    expect(
      screen.queryByTestId("project-disable-confirm"),
    ).not.toBeInTheDocument();
    expect(
      within(screen.getByRole("dialog")).getByRole("button", { name: "확인" }),
    ).toBeVisible();
    expect(reload).not.toHaveBeenCalled();
    expect(complete).not.toHaveBeenCalled();
    expect(
      within(screen.getByRole("dialog")).queryByRole("button", {
        name: "다시 시도",
      }),
    ).not.toBeInTheDocument();
  });
  it("direct disabled detail enables without another confirmation", async () => {
    actions("N");
    fireEvent.click(screen.getByTestId("project-enable"));
    await waitFor(() =>
      expect(complete).toHaveBeenCalledWith("다시 사용하도록 변경했습니다."),
    );
    expect(writes()[0][0]).toBe("/api/projects/8/enable");
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });
  it("failed enable has no success and requires read recovery", async () => {
    failed = true;
    actions("N");
    fireEvent.click(screen.getByTestId("project-enable"));
    await screen.findByText("서버에 연결할 수 없습니다.");
    expect(complete).not.toHaveBeenCalled();
    expect(screen.getByTestId("project-enable")).toBeDisabled();
  });
  it("mobile hides both state mutations", () => {
    Object.defineProperty(window, "innerWidth", { value: 767 });
    actions("N");
    expect(screen.queryByTestId("project-enable")).not.toBeInTheDocument();
  });
  it("resize closes disable confirmation and never mutates", () => {
    actions();
    fireEvent.click(screen.getByTestId("project-disable-open"));
    Object.defineProperty(window, "innerWidth", { value: 767 });
    fireEvent(window, new Event("resize"));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(writes()).toHaveLength(0);
  });
  it("project select consumes option IDs and q rather than guessing IDs", async () => {
    rows = [
      { id: 8, no: "P-2608", name: "프로젝트", type: "TK", custName: "고객" },
    ];
    const change = vi.fn();
    render(<ProjectSelect value={null} onChange={change} />);
    await screen.findByRole("option", { name: "P-2608 · 프로젝트 · 고객" });
    fireEvent.change(screen.getByLabelText("프로젝트"), {
      target: { value: "8" },
    });
    expect(change).toHaveBeenCalledWith(8);
    fireEvent.change(screen.getByTestId("project-option-search"), {
      target: { value: "고객" },
    });
    fireEvent.click(screen.getByTestId("project-option-query"));
    await waitFor(() =>
      expect(
        fetcher.mock.calls.some(
          (c) =>
            c[0] ===
            `/api/projects/options?${new URLSearchParams({ q: "고객" })}`,
        ),
      ).toBe(true),
    );
  });
  it("option errors disable selection", async () => {
    failed = true;
    render(<ProjectSelect value={null} onChange={vi.fn()} />);
    await screen.findByText("서버에 연결할 수 없습니다.");
    expect(screen.getByLabelText("프로젝트")).toBeDisabled();
  });
  it("option empty state", async () => {
    render(<ProjectSelect value={null} onChange={vi.fn()} />);
    expect(
      await screen.findByText("조건에 맞는 결과가 없습니다."),
    ).toBeVisible();
  });
  it("export forwards only current supported filters without page limit", async () => {
    await getProjectExport({
      year: 2026,
      q: "고객",
      part: "NONE",
      sort: "prj_nm,asc",
    });
    const query = new URL(fetcher.mock.calls[0][0], "https://test.invalid")
      .searchParams;
    expect(query.get("q")).toBe("고객");
    expect(query.get("part")).toBe("NONE");
    expect(query.has("page")).toBe(false);
    expect(query.has("size")).toBe(false);
  });
  it("download decodes JSON bytes and releases object URL", () => {
    const create = vi.fn().mockReturnValue("blob:workbook"),
      revoke = vi.fn();
    vi.stubGlobal(
      "URL",
      class extends URL {
        static createObjectURL = create;
        static revokeObjectURL = revoke;
      },
    );
    const click = vi
      .spyOn(HTMLAnchorElement.prototype, "click")
      .mockImplementation(() => {});
    saveProjectWorkbook({
      file_name: "프로젝트.xlsx",
      media_type:
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
      content_base64: btoa("PK"),
    });
    expect(create.mock.calls[0][0].size).toBe(2);
    expect(click).toHaveBeenCalledOnce();
    expect(revoke).toHaveBeenCalledWith("blob:workbook");
  });
  it("export failure displays retry without success", async () => {
    failed = true;
    render(<ProjectExportButton filter={{ year: 2026 }} />);
    fireEvent.click(screen.getByTestId("project-export"));
    await screen.findByText("서버에 연결할 수 없습니다.");
    expect(
      screen.queryByText("엑셀 파일을 내려받았습니다."),
    ).not.toBeInTheDocument();
  });
});
