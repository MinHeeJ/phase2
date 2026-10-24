import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import DeveloperLifecycleScreen from "./DeveloperLifecycleScreen";
import {
  DeveloperAssignments,
  DeveloperMonthlyCosts,
  DeveloperHistory,
} from "./DeveloperReadPanels";
import {
  DeveloperDisableDialog,
  DeveloperEnableButton,
  DeveloperExportButton,
} from "./DeveloperActions";
import { DeveloperSelect } from "./DeveloperSelect";
import * as client from "./client";

const row: client.DeveloperDetail = {
  dev_id: 301,
  mng_no: "K-0301",
  dev_nm: "개발자 가",
  use_yn: "Y",
  rsgn_dt: null,
  affiliation_name: "자사",
  emp_se_cd: "FT",
  emp_type_name: "정규직",
  grade_name: "고급",
  main_job_name: "백엔드",
  status: "투입중",
  current_project_name: "프로젝트 A",
  current_assignment_count: 2,
  current_month_mm: 1.2,
  over_one_mm: true,
  latest_assignment_end_dt: "2027-01-31",
  dflt_untprc_amt: 8000000,
  sub_job: [],
  skill: [],
  latest_salary: { month: "2026-10", slry_amt: 4000000 },
};
const blockedRow: client.DeveloperDetail = {
  ...row,
  dev_id: 303,
  dev_nm: "개발자 다",
  current_assignment_count: 1,
};
const idleRow = { ...row, current_assignment_count: 0 };
const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
const page = (items: unknown[] = []) => ({
  items,
  total: items.length,
  page: 0,
  size: 50,
});
const failed = (
  code = "SERVER_ERROR",
  message = "요청에 실패했습니다.",
  status = 500,
) =>
  new Response(
    JSON.stringify({
      success: false,
      meta: {},
      code,
      message,
      requestId: "developer-request",
      field: null,
    }),
    { status, headers: { "Content-Type": "application/json" } },
  );
let fetcher: ReturnType<typeof vi.fn>;
beforeEach(() => {
  fetcher = vi.fn(async (path: string, init?: RequestInit) => {
    if (path.includes("/api/codes/"))
      return response([
        { code: "FT", name: "정규직" },
        { code: "BE", name: "백엔드" },
      ]);
    if (path.startsWith("/api/developers?")) return response(page([row]));
    if (path.startsWith("/api/developers/options"))
      return response([
        {
          id: row.dev_id,
          mngNo: row.mng_no,
          name: row.dev_nm,
          mainJob: "백엔드",
          empType: "FT",
        },
      ]);
    if (
      path.includes("/assignments") ||
      path.includes("/monthly-costs") ||
      path.includes("/history") ||
      path.includes("/work-status")
    )
      return response(page());
    if (path.endsWith("/disable")) {
      const ids = JSON.parse(init?.body as string).ids as number[];
      return response({
        disabled_ids: ids,
        blocked: [],
        disabled_count: ids.length,
        blocked_count: 0,
      });
    }
    if (path.endsWith("/enable")) return response({ ...row, use_yn: "Y" });
    return response(row);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("developer lifecycle /UI-02", () => {
  it("renders real list fields, current-project count, MM and status tones", async () => {
    render(<DeveloperLifecycleScreen />);
    expect(
      await screen.findByRole("button", { name: row.dev_nm }),
    ).toBeInTheDocument();
    expect(screen.getByText("프로젝트 A 외 1건")).toBeInTheDocument();
    expect(screen.getByText("1.0 초과")).toBeInTheDocument();
    expect(
      screen.getByText("투입중", { selector: "span" }).className,
    ).toContain("info");
    expect(
      screen.getByText("정규직", { selector: "span" }).className,
    ).toContain("neutral");
    expect(
      screen.queryByText("조건에 맞는 결과가 없습니다."),
    ).not.toBeInTheDocument();
  });
  it("opens the right drawer and mounts only the selected tab", async () => {
    render(<DeveloperLifecycleScreen />);
    fireEvent.click(await screen.findByRole("button", { name: row.dev_nm }));
    const drawer = await screen.findByRole("dialog", { name: "개발자 상세" });
    expect(await within(drawer).findByText("최근 월 급여")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("tab", { name: "투입 이력" }));
    expect(
      await screen.findByText("투입 이력이 없습니다."),
    ).toBeInTheDocument();
    expect(
      screen.queryByText("급여 업로드로 만든 월별 원가가 없습니다."),
    ).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("tab", { name: "월별 원가" }));
    expect(
      await screen.findByText("급여 업로드로 만든 월별 원가가 없습니다."),
    ).toBeInTheDocument();
    expect(screen.queryByText("투입 이력이 없습니다.")).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("tab", { name: "변경 이력" }));
    expect(
      await screen.findByText("아직 바꾼 기록이 없습니다."),
    ).toBeInTheDocument();
    fireEvent.keyDown(document, { key: "Escape" });
    expect(
      screen.queryByRole("dialog", { name: "개발자 상세" }),
    ).not.toBeInTheDocument();
  });
  it("applies all search filters and keeps page0 adapter local", async () => {
    render(<DeveloperLifecycleScreen />);
    await screen.findByRole("button", { name: row.dev_nm });
    fireEvent.change(screen.getByLabelText("직원구분"), {
      target: { value: "FT" },
    });
    fireEvent.change(screen.getByLabelText("직무"), {
      target: { value: "BE" },
    });
    fireEvent.change(screen.getByLabelText("상태"), {
      target: { value: "퇴사" },
    });
    fireEvent.change(screen.getByLabelText("조회 범위"), {
      target: { value: "N" },
    });
    fireEvent.change(screen.getByLabelText("이름·관리번호"), {
      target: { value: "K-0301" },
    });
    fireEvent.click(screen.getByRole("button", { name: "조회" }));
    await waitFor(() =>
      expect(
        fetcher.mock.calls.some(
          ([p]) =>
            p.includes("empType=FT") &&
            p.includes("job=BE") &&
            p.includes("use_yn=N") &&
            p.includes("q=K-0301") &&
            p.includes("page=0"),
        ),
      ).toBe(true),
    );
  });
  it("renders an empty list with reset only after successful zero results", async () => {
    fetcher.mockImplementation(async (p: string) =>
      response(p.includes("/codes/") ? [] : page()),
    );
    render(<DeveloperLifecycleScreen />);
    expect(
      await screen.findByText("조건에 맞는 결과가 없습니다."),
    ).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "조건 초기화" }));
    expect(screen.getByLabelText("조회 범위")).toHaveValue("Y");
  });
  it("failed list keeps filters and displays requestId instead of empty state", async () => {
    fetcher.mockImplementation(async (p: string) =>
      p.includes("/codes/") ? response([]) : failed(),
    );
    render(<DeveloperLifecycleScreen />);
    expect(await screen.findByText("요청에 실패했습니다.")).toBeInTheDocument();
    expect(screen.getByText(/developer-request/)).toBeInTheDocument();
    expect(
      screen.queryByText("조건에 맞는 결과가 없습니다."),
    ).not.toBeInTheDocument();
    expect(screen.getByLabelText("조회 범위")).toHaveValue("Y");
  });
  it("retired disabled drawer displays both notices and a mobile-hidden enable control", async () => {
    fetcher.mockImplementation(async (p: string) =>
      p.includes("/codes/")
        ? response([])
        : p.startsWith("/api/developers?")
          ? response(page([row]))
          : response({
              ...row,
              use_yn: "N",
              status: "퇴사",
              rsgn_dt: "2026-10-22",
            }),
    );
    render(<DeveloperLifecycleScreen />);
    fireEvent.click(await screen.findByRole("button", { name: row.dev_nm }));
    expect(await screen.findByText(/퇴사한 사람입니다/)).toBeInTheDocument();
    expect(screen.getByText("사용을 중지한 사람입니다.")).toBeInTheDocument();
    expect(screen.getByTestId("developer-enable-control").className).toContain(
      "mutate",
    );
  });
  it("public permission adapter exposes the desktop actions and delegates registration", async () => {
    const create = vi.fn();
    render(<DeveloperLifecycleScreen onCreate={create} />);
    fireEvent.click(screen.getByRole("button", { name: "+ 등록" }));
    expect(create).toHaveBeenCalledOnce();
    expect(
      screen.getByTestId("developer-create").parentElement?.className,
    ).toContain("mutate");
    await screen.findByRole("button", { name: row.dev_nm });
  });
});

describe("developer detail read panels", () => {
  it("shows past assignments with project, job, inclusive period, rate and status", async () => {
    fetcher.mockImplementation(async (p: string) =>
      p.includes("/codes/")
        ? response([{ code: "ENDED", name: "종료" }])
        : response(
            page([
              {
                asgn_id: 1,
                prj_id: 8,
                prj_nm: "과거 프로젝트",
                job_cd: "BE",
                job_name: "백엔드",
                bgn_dt: "2025-01-01",
                end_dt: "2025-12-31",
                asgn_rt: 0.5,
                asgn_stts_cd: "ENDED",
              },
            ]),
          ),
    );
    render(<DeveloperAssignments id={301} />);
    expect(await screen.findByText("과거 프로젝트")).toBeInTheDocument();
    expect(screen.getByText("2025-01-01 ~ 2025-12-31")).toBeInTheDocument();
    expect(screen.getByText("50%")).toBeInTheDocument();
    expect(await screen.findByText("종료")).toBeInTheDocument();
  });
  it("renders eight unmasked salary columns including database-calculated amounts", async () => {
    fetcher.mockImplementation(async (p: string) =>
      p.includes("/codes/")
        ? response([{ code: "FT", name: "정규직" }])
        : response(
            page([
              {
                cst_ym: "2026-10",
                emp_se_cd: "FT",
                slry_amt: 4000000,
                alwnc_amt: 100000,
                sins_cmpny_amt: 200000,
                rtrpay_acml_amt: 300000,
                indv_lbr_cst_amt: 4600000,
                real_pay_amt: 3900000,
              },
            ]),
          ),
    );
    render(<DeveloperMonthlyCosts id={301} />);
    expect(await screen.findByText("4,600,000")).toBeInTheDocument();
    expect(screen.getByText("3,900,000")).toBeInTheDocument();
    expect(screen.getAllByRole("columnheader")).toHaveLength(8);
    expect(screen.getByText("4,000,000").closest("td")).toHaveAttribute(
      "data-sensitive",
      "true",
    );
  });
  it("renders audit before/after and actor", async () => {
    fetcher.mockResolvedValue(
      response(
        page([
          {
            changed_at: "2026-10-22T12:30:00",
            table_name: "mst.dev",
            record_id: "301",
            change_type: "UPDATE",
            item: "use_yn",
            before: "N",
            after: "Y",
            actor: "기본 사용자",
          },
        ]),
      ),
    );
    render(<DeveloperHistory id={301} />);
    expect(await screen.findByText("2026-10-22 12:30:00")).toBeInTheDocument();
    expect(screen.getByText("사용 여부")).toBeInTheDocument();
    expect(screen.getByText("N")).toBeInTheDocument();
    expect(screen.getByText("Y")).toBeInTheDocument();
    expect(screen.getByText("기본 사용자")).toBeInTheDocument();
  });
  it.each(["assignments", "costs", "history"])(
    "%s failed request does not render an empty-success message",
    async (kind) => {
      fetcher.mockImplementation(async (p: string) =>
        p.includes("/codes/") ? response([]) : failed(),
      );
      render(
        kind === "assignments" ? (
          <DeveloperAssignments id={301} />
        ) : kind === "costs" ? (
          <DeveloperMonthlyCosts id={301} />
        ) : (
          <DeveloperHistory id={301} />
        ),
      );
      expect(
        await screen.findByText("요청에 실패했습니다."),
      ).toBeInTheDocument();
      expect(screen.queryByText(/없습니다\.$/)).not.toBeInTheDocument();
    },
  );
});

describe("developer lifecycle confirmation", () => {
  it("shows selected names, preservation and each blocked count, and cancel does not POST", () => {
    const close = vi.fn();
    render(
      <DeveloperDisableDialog
        rows={[idleRow, blockedRow]}
        onClose={close}
        reload={vi.fn().mockResolvedValue(null)}
        onComplete={vi.fn()}
      />,
    );
    expect(
      screen.getByRole("dialog", { name: "2명의 사용을 중지할까요?" }),
    ).toBeInTheDocument();
    expect(screen.getByText(/기록은 그대로 남습니다/)).toHaveTextContent(
      "개발자 가, 개발자 다",
    );
    expect(screen.getByText("개발자 다 — 현재 투입 1건")).toBeInTheDocument();
    expect(screen.getByTestId("developer-disable-confirm").className).toContain(
      "danger",
    );
    fireEvent.click(screen.getByRole("button", { name: "취소" }));
    expect(close).toHaveBeenCalledOnce();
    expect(fetcher).not.toHaveBeenCalled();
  });
  it("mixed result posts numeric ids once and shows exact success/exclusion message", async () => {
    fetcher.mockResolvedValue(
      response({
        disabled_ids: [301],
        blocked: [
          {
            id: 303,
            name: "개발자 다",
            current_assignment_count: 1,
            code: "DEVELOPER_IN_USE",
            reason: "현재 투입 1건",
          },
        ],
        disabled_count: 1,
        blocked_count: 1,
      }),
    );
    const reload = vi.fn().mockResolvedValue(null),
      complete = vi.fn();
    render(
      <DeveloperDisableDialog
        rows={[idleRow, blockedRow]}
        onClose={vi.fn()}
        reload={reload}
        onComplete={complete}
      />,
    );
    fireEvent.click(screen.getByRole("button", { name: "사용 중지" }));
    await waitFor(() =>
      expect(complete).toHaveBeenCalledWith(
        "1명의 사용을 중지했습니다. (1명은 투입 중이라 제외)",
      ),
    );
    expect(reload).toHaveBeenCalledOnce();
    expect(JSON.parse(fetcher.mock.calls[0][1]?.body as string)).toEqual({
      ids: [301, 303],
    });
    expect(fetcher.mock.calls[0][1]?.headers).not.toHaveProperty(
      "Authorization",
    );
  });
  it("known all-blocked selection displays refusal without a mutation path", () => {
    render(
      <DeveloperDisableDialog
        rows={[blockedRow]}
        onClose={vi.fn()}
        reload={vi.fn()}
        onComplete={vi.fn()}
      />,
    );
    expect(
      screen.getByRole("dialog", { name: "사용을 중지할 수 없습니다" }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "사용 중지" }),
    ).not.toBeInTheDocument();
    expect(fetcher).not.toHaveBeenCalled();
  });
  it("server all-blocked result uses the refusal window", async () => {
    fetcher.mockResolvedValue(
      response({
        disabled_ids: [],
        disabled_count: 0,
        blocked_count: 2,
        blocked: [
          {
            id: 301,
            name: "개발자 가",
            current_assignment_count: 1,
            code: "DEVELOPER_IN_USE",
            reason: "현재 투입 1건",
          },
          {
            id: 303,
            name: "개발자 다",
            current_assignment_count: 1,
            code: "DEVELOPER_IN_USE",
            reason: "현재 투입 1건",
          },
        ],
      }),
    );
    render(
      <DeveloperDisableDialog
        rows={[idleRow, { ...blockedRow, current_assignment_count: 0 }]}
        onClose={vi.fn()}
        reload={vi.fn().mockResolvedValue(null)}
        onComplete={vi.fn()}
      />,
    );
    fireEvent.click(screen.getByRole("button", { name: "사용 중지" }));
    expect(
      await screen.findByRole("dialog", { name: "사용을 중지할 수 없습니다" }),
    ).toBeInTheDocument();
    expect(screen.getByText("개발자 가 — 현재 투입 1건")).toBeInTheDocument();
  });
  it("single conflict keeps error and changes the window to refusal", async () => {
    fetcher.mockResolvedValue(failed("DEVELOPER_IN_USE", "현재 투입 1건", 409));
    render(
      <DeveloperDisableDialog
        rows={[idleRow]}
        onClose={vi.fn()}
        reload={vi.fn()}
        onComplete={vi.fn()}
      />,
    );
    fireEvent.click(screen.getByRole("button", { name: "사용 중지" }));
    expect(
      await screen.findByRole("dialog", { name: "사용을 중지할 수 없습니다" }),
    ).toBeInTheDocument();
    expect(screen.getByText("현재 투입 1건")).toBeInTheDocument();
  });
  it("enable has no extra confirmation, refreshes and emits success", async () => {
    const reload = vi.fn().mockResolvedValue(null),
      complete = vi.fn();
    render(
      <DeveloperEnableButton id={301} reload={reload} onComplete={complete} />,
    );
    fireEvent.click(screen.getByRole("button", { name: "다시 사용" }));
    await waitFor(() =>
      expect(complete).toHaveBeenCalledWith("다시 사용하도록 변경했습니다."),
    );
    expect(reload).toHaveBeenCalledOnce();
    expect(fetcher.mock.calls[0][0]).toBe("/api/developers/301/enable");
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });
  it("uncertain enable never automatically retries POST", async () => {
    fetcher.mockRejectedValue(new TypeError("offline"));
    const reload = vi.fn().mockResolvedValue(null);
    render(
      <DeveloperEnableButton id={301} reload={reload} onComplete={vi.fn()} />,
    );
    fireEvent.click(screen.getByRole("button", { name: "다시 사용" }));
    await screen.findByText("서버에 연결할 수 없습니다.");
    expect(screen.getByRole("button", { name: "다시 사용" })).toBeDisabled();
    expect(fetcher).toHaveBeenCalledOnce();
  });
});

describe("developer options and download", () => {
  it("options use numeric ids and q searching without pagination", async () => {
    const change = vi.fn();
    render(<DeveloperSelect value={null} onChange={change} />);
    await screen.findByRole("option", { name: /개발자 가/ });
    fireEvent.change(screen.getByLabelText("개발자"), {
      target: { value: "301" },
    });
    expect(change).toHaveBeenCalledWith(301);
    fireEvent.change(screen.getByLabelText("개발자 검색"), {
      target: { value: "K-0301" },
    });
    await waitFor(() =>
      expect(
        fetcher.mock.calls.some(
          ([p]) => p === "/api/developers/options?q=K-0301",
        ),
      ).toBe(true),
    );
  });
  it("options empty and read-only controls are explicit", async () => {
    fetcher.mockResolvedValue(response([]));
    render(<DeveloperSelect value={null} onChange={vi.fn()} disabled />);
    expect(
      await screen.findByText("선택할 개발자가 없습니다."),
    ).toBeInTheDocument();
    expect(screen.getByLabelText("개발자")).toBeDisabled();
    expect(screen.getByLabelText("개발자 검색")).toBeDisabled();
  });
  it("export sends all filters, not selected ids or a page, and shows success", async () => {
    const download = vi.spyOn(client, "downloadDevelopers").mockResolvedValue();
    const filter = { empType: "FT", use_yn: "N", q: "K-0301" };
    render(<DeveloperExportButton filter={filter} />);
    fireEvent.click(screen.getByRole("button", { name: "엑셀 다운로드" }));
    expect(
      await screen.findByText("엑셀 파일을 내려받았습니다."),
    ).toBeInTheDocument();
    expect(download).toHaveBeenCalledWith(filter);
  });
  it("export failure displays server error and requestId", async () => {
    fetcher.mockResolvedValue(failed());
    render(<DeveloperExportButton filter={{ use_yn: "Y" }} />);
    fireEvent.click(screen.getByRole("button", { name: "엑셀 다운로드" }));
    expect(await screen.findByText("요청에 실패했습니다.")).toBeInTheDocument();
    expect(screen.getByText(/developer-request/)).toBeInTheDocument();
    expect(fetcher.mock.calls[0][0]).toBe("/api/developers/export?use_yn=Y");
  });
  it("download rejects a JSON response with a misleading successful status", async () => {
    fetcher.mockResolvedValue(response({ file_name: "not-real.xlsx" }));
    await expect(client.downloadDevelopers({})).rejects.toThrow(
      "엑셀 파일 응답이 아닙니다.",
    );
  });
});
