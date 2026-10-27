import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter } from "react-router-dom";
import UI12Screen from "./UI12Screen";
import { UI12M1 } from "./UI12M1";
import { UI12DetailPanel } from "./UI12DetailPanel";
import type { AssignmentDetail } from "./client";

const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
let fetcher: ReturnType<typeof vi.fn>,
  failRead: boolean,
  failWrite: boolean,
  empty: boolean;
let row: AssignmentDetail;
const saved = vi.fn(),
  cancelled = vi.fn(),
  reload = vi.fn().mockResolvedValue(undefined);
const codes: Record<string, { code: string; name: string }[]> = {
  JOB: [
    { code: "BE", name: "백엔드 개발" },
    { code: "FE", name: "프론트엔드 개발" },
  ],
  ASSIGN_KIND: [
    { code: "NEW", name: "신규" },
    { code: "SUB", name: "대체투입" },
  ],
  RATE_BASIS: [{ code: "MONTH", name: "월" }],
  EXT_POSSIBILITY: [
    { code: "H", name: "높음" },
    { code: "M", name: "보통" },
    { code: "L", name: "낮음" },
  ],
  ASGN_CHG_RSN: [{ code: "SCHED", name: "프로젝트 일정 변경" }],
};
beforeEach(() => {
  Object.defineProperty(window, "innerWidth", {
    configurable: true,
    writable: true,
    value: 1280,
  });
  sessionStorage.clear();
  vi.useFakeTimers({ toFake: ["Date"] });
  vi.setSystemTime(new Date("2026-10-14T00:00:00Z"));
  failRead = false;
  failWrite = false;
  empty = false;
  saved.mockClear();
  cancelled.mockClear();
  reload.mockClear();
  row = {
    asgn_id: 7,
    asgn_no: "A-0007",
    prj_id: 3,
    prj_nm: "실제 프로젝트",
    dev_id: 2,
    dev_nm: "개발자",
    mng_no: "K-0002",
    job_cd: "BE",
    bgn_dt: "2026-10-16",
    end_dt: "2026-12-31",
    plan_end_dt: "2026-11-30",
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
    person_month_mm_qty: 1,
    year_mm_qty: 2.52,
    warnings: [],
    updated_at: "2026-10-14T00:00:00.123456",
    extend_count: 1,
    rates: [{ aply_bgn_dt: "2026-10-16", bill_untprc_amt: 7000000 }],
    months: [
      {
        work_ym: "2026-10",
        auto_mm_qty: 0.52,
        mm_qty: 0.52,
        mnl_yn: "N",
        person_month_mm_qty: 1,
        closed_yn: "N",
      },
    ],
    rmrk_desc: null,
  };
  fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (url.startsWith("/api/codes/"))
      return response(codes[url.split("/").at(-1)!] ?? []);
    if (url === "/api/projects/options")
      return response([
        { id: 3, no: "P-2603", name: "실제 프로젝트", type: "MO" },
        { id: 4, no: "P-2604", name: "턴키", type: "TK" },
      ]);
    if (url.startsWith("/api/developers/options"))
      return response([
        { id: 2, name: "개발자", mngNo: "K-0002", mainJob: "백엔드 개발" },
        { id: 5, name: "계약 개발자", mngNo: "K-0005", mainJob: "백엔드 개발" },
      ]);
    if (url === "/api/developers/2" || url === "/api/developers/5")
      return response({ main_job_cd: "BE" });
    if (url === "/api/projects/3" || url === "/api/projects/4")
      return response({
        prj_id: Number(url.split("/").at(-1)),
        cntrct_ty_cd: url.endsWith("4") ? "TK" : "MO",
        stlm_mthd_cd: "MONTH",
        use_yn: "Y",
        prj_stts_cd: "RUN",
      });
    if (url.startsWith("/api/month-close"))
      return response([{ month: "2026-09", status: "CLOSED" }]);
    if (url === "/api/assignments/check")
      return response({
        warnings: [{ code: "PRE_CONTRACT", message: "미계약 선투입" }],
        months: row.months,
      });
    if (init?.method === "POST") {
      if (failWrite)
        return new Response(
          JSON.stringify({
            success: false,
            code: "INVALID_INPUT",
            message: "사유 코드를 선택하세요.",
            field: "rsn_cd",
            requestId: "assignment-request",
          }),
          { status: 400 },
        );
      return response({
        assignment: row,
        change: { asgn_chg_id: 1, asgn_chg_ty_cd: "START" },
      });
    }
    if (failRead) throw new Error("offline");
    if (url === "/api/assignments/7") return response(row);
    if (url.startsWith("/api/assignments?"))
      return response({
        items: empty ? [] : [row],
        total: empty ? 0 : 1,
        page: 0,
        size: 50,
      });
    throw new Error(`Unexpected test request ${url}`);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  vi.useRealTimers();
});
const writes = () =>
  fetcher.mock.calls.filter(
    (c) => c[1]?.method === "POST" && c[0] === "/api/assignments",
  );
const change = (label: string, value: string) =>
  fireEvent.change(screen.getByLabelText(label), { target: { value } });
const form = () =>
  render(<UI12M1 onCancel={cancelled} onSaved={saved} reload={reload} />);
async function fill() {
  await screen.findByRole("option", { name: "P-2603 · 실제 프로젝트" });
  change("프로젝트", "3");
  change("개발자", "2");
  await waitFor(() => expect(screen.getByLabelText("직무")).toHaveValue("BE"));
  change("투입일", "2026-10-16");
  change("철수일", "2026-10-31");
  change("청구 단가", "7000000");
}
describe("assignment list", () => {
  it("renders API quantities and AND search; opens exact assignment", async () => {
    render(
      <MemoryRouter>
        <UI12Screen />
      </MemoryRouter>,
    );
    expect(await screen.findByText("2.52")).toBeInTheDocument();
    fireEvent.change(screen.getByTestId("assignment-filter-project"), {
      target: { value: "3" },
    });
    fireEvent.change(screen.getByTestId("assignment-filter-job"), {
      target: { value: "BE" },
    });
    fireEvent.click(screen.getByTestId("assignment-search"));
    await waitFor(() =>
      expect(
        fetcher.mock.calls.some(
          (c) => c[0].includes("projectId=3") && c[0].includes("job=BE"),
        ),
      ).toBe(true),
    );
    fireEvent.click(screen.getByTestId("assignment-open-7"));
    expect(
      await screen.findByRole("dialog", { name: "투입 상세" }),
    ).toBeInTheDocument();
    expect(fetcher.mock.calls.some((c) => c[0] === "/api/assignments/7")).toBe(
      true,
    );
  });
  it("preserves the applied column sort and page size when submitting search", async () => {
    render(
      <MemoryRouter>
        <UI12Screen />
      </MemoryRouter>,
    );
    await screen.findByText("2.52");
    fireEvent.click(screen.getByTestId("assignment-sort-bill-untprc-amt"));
    fireEvent.change(screen.getByTestId("assignment-filter-name"), {
      target: { value: "개발자" },
    });
    fireEvent.click(screen.getByTestId("assignment-search"));
    await waitFor(() => {
      const calls = fetcher.mock.calls.filter(([url]) =>
        url.startsWith("/api/assignments?"),
      );
      const query = new URL(calls.at(-1)![0], "https://test.invalid")
        .searchParams;
      expect(query.get("q")).toBe("개발자");
      expect(query.get("sort")).toBe("bill_untprc_amt,asc");
      expect(query.get("size")).toBe("50");
      expect(query.get("page")).toBe("0");
    });
  });
  it("hides create and selection for previous year", async () => {
    render(
      <MemoryRouter>
        <UI12Screen />
      </MemoryRouter>,
    );
    await screen.findByText("2.52");
    fireEvent.click(screen.getByTestId("assignment-year-prev"));
    expect(screen.queryByTestId("assignment-add")).not.toBeInTheDocument();
    expect(screen.queryByTestId("assignment-check-7")).not.toBeInTheDocument();
    expect(screen.getByText("읽기 전용")).toBeInTheDocument();
  });
  it("renders empty results and resets filters", async () => {
    empty = true;
    render(
      <MemoryRouter>
        <UI12Screen />
      </MemoryRouter>,
    );
    expect(
      await screen.findByText("조건에 맞는 결과가 없습니다"),
    ).toBeInTheDocument();
    fireEvent.click(screen.getByTestId("assignment-empty-reset"));
    expect(writes()).toHaveLength(0);
  });
  it("shows failed read instead of a fabricated list", async () => {
    failRead = true;
    render(
      <MemoryRouter>
        <UI12Screen />
      </MemoryRouter>,
    );
    expect(
      (await screen.findAllByText("서버에 연결할 수 없습니다.")).length,
    ).toBeGreaterThan(0);
    expect(screen.queryByTestId("assignment-row-7")).not.toBeInTheDocument();
  });
});
describe("assignment create", () => {
  it("uses developer job code, previews warnings, saves and returns to list", async () => {
    form();
    await fill();
    expect(await screen.findByText("미계약 선투입")).toBeInTheDocument();
    fireEvent.click(screen.getByTestId("assignment-create-save"));
    await waitFor(() => expect(saved).toHaveBeenCalled());
    expect(JSON.parse(writes()[0][1].body)).toMatchObject({
      dev_id: 2,
      job_cd: "BE",
      asgn_rt: 1,
      asgn_se_cd: "NEW",
      bill_untprc_amt: 7000000,
    });
    expect(reload).toHaveBeenCalled();
  });
  it("preserves manually selected job after another developer selection", async () => {
    form();
    await fill();
    change("직무", "FE");
    change("개발자", "5");
    await waitFor(() =>
      expect(fetcher.mock.calls.some((c) => c[0] === "/api/developers/5")).toBe(
        true,
      ),
    );
    expect(screen.getByLabelText("직무")).toHaveValue("FE");
  });
  it("clears TK pricing and disables controls", async () => {
    form();
    await fill();
    change("프로젝트", "4");
    await waitFor(() =>
      expect(screen.getByLabelText("청구 단가")).toBeDisabled(),
    );
    expect(screen.getByLabelText("단가 기준")).toBeDisabled();
    fireEvent.click(screen.getByTestId("assignment-create-save"));
    await waitFor(() => expect(saved).toHaveBeenCalled());
    expect(JSON.parse(writes()[0][1].body)).toMatchObject({
      bill_untprc_amt: null,
      untprc_base_cd: null,
    });
  });
  it("SUB requires contract person and clears it when kind changes", async () => {
    form();
    await fill();
    change("투입 구분", "SUB");
    fireEvent.click(screen.getByTestId("assignment-create-save"));
    expect(writes()).toHaveLength(0);
    change("계약상 인력", "5");
    change("투입 구分".replace("分", "분"), "NEW");
    expect(screen.queryByLabelText("계약상 인력")).not.toBeInTheDocument();
    fireEvent.click(screen.getByTestId("assignment-create-save"));
    await waitFor(() => expect(saved).toHaveBeenCalled());
    expect(JSON.parse(writes()[0][1].body).cntrct_dev_id).toBeNull();
  });
  it("dirty cancel has no save and keeps draft when continuing", async () => {
    form();
    await fill();
    fireEvent.click(screen.getByTestId("assignment-create-cancel"));
    expect(screen.getByText("작성 중인 내용을 버릴까요?")).toBeInTheDocument();
    fireEvent.click(screen.getByTestId("assignment-keep"));
    expect(screen.getByLabelText("투입일")).toHaveValue("2026-10-16");
    await Promise.resolve();
    fireEvent.click(screen.getByTestId("assignment-create-cancel"));
    fireEvent.click(await screen.findByTestId("assignment-discard"));
    expect(cancelled).toHaveBeenCalled();
    expect(writes()).toHaveLength(0);
  });
  it("field failure preserves values and reports the reason input", async () => {
    form();
    await fill();
    failWrite = true;
    fireEvent.click(screen.getByTestId("assignment-create-save"));
    await screen.findAllByText("사유 코드를 선택하세요.");
    expect(screen.getByLabelText("투입일")).toHaveValue("2026-10-16");
    expect(saved).not.toHaveBeenCalled();
  });
  it("invalid rate does not submit", async () => {
    form();
    await fill();
    change("투입률", "0");
    fireEvent.click(screen.getByTestId("assignment-create-save"));
    expect(writes()).toHaveLength(0);
    expect(
      (await screen.findAllByText("필수 항목과 입력 범위를 확인하세요."))
        .length,
    ).toBeGreaterThan(0);
  });
});
describe("assignment detail", () => {
  it("shows cancelled status read-only, original plan, rates and MM", async () => {
    row.asgn_stts_cd = "CANCELLED";
    const actions = vi.fn();
    render(<UI12DetailPanel id={7} onClose={cancelled} actions={actions} />);
    const panel = await screen.findByRole("dialog", { name: "투입 상세" });
    await within(panel).findByText("2026-11-30");
    expect(actions).not.toHaveBeenCalled();
    expect(
      within(panel).getByTestId("assignment-rate-2026-10-16"),
    ).toHaveTextContent("7,000,000");
    expect(
      within(panel).getByTestId("assignment-month-2026-10"),
    ).toHaveTextContent("0.52");
    expect(writes()).toHaveLength(0);
  });
  it("shows legacy ENDED read-only without offering mutators", async () => {
    row.asgn_stts_cd = "ENDED";
    const actions = vi.fn();
    render(<UI12DetailPanel id={7} onClose={cancelled} actions={actions} />);
    await screen.findByText("읽기 전용 · ENDED");
    expect(actions).not.toHaveBeenCalled();
  });
  it("does not invent a TK price", async () => {
    row.bill_untprc_amt = null;
    row.untprc_base_cd = null;
    row.rates[0].bill_untprc_amt = null;
    render(<UI12DetailPanel id={7} onClose={cancelled} />);
    expect(await screen.findByText("배분표 / 단가 없음")).toBeInTheDocument();
  });
});
