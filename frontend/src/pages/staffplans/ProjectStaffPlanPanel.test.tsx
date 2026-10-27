import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import { ProjectStaffPlanPanel } from "./ProjectStaffPlanPanel";
import {
  saveProjectStaffPlans,
  type ProjectStaffPlan,
  type ProjectStaffPlans,
} from "./client";

const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
let data: ProjectStaffPlans;
let fetcher: ReturnType<typeof vi.fn>;
let readFailure: boolean;
let writeFailure: Response | null;
const changed = vi.fn().mockResolvedValue(undefined);
const dirty = vi.fn();
const basic = vi.fn();
const plan = (
  month: string,
  mm: number,
  grade = "",
  job = "BE",
  source: number | null = null,
): ProjectStaffPlan => ({
  plan_ym: month,
  job_cd: job,
  grd_cd: grade,
  need_mm_qty: mm,
  need_psn_cnt: Math.ceil(mm),
  expc_mth_cst_amt: null,
  src_need_id: source,
});
beforeEach(() => {
  Object.defineProperty(window, "innerWidth", {
    configurable: true,
    writable: true,
    value: 1280,
  });
  data = {
    items: [plan("2026-10", 2), plan("2026-11", 2)],
    actual: [{ job_cd: "BE", mm_qty: 3.5 }],
  };
  readFailure = false;
  writeFailure = null;
  changed.mockClear();
  dirty.mockClear();
  basic.mockClear();
  fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (url === "/api/codes/JOB")
      return response([
        { code: "BE", name: "백엔드 개발" },
        { code: "FE", name: "프론트엔드 개발" },
      ]);
    if (url === "/api/codes/GRADE")
      return response([
        { code: "JR", name: "초급" },
        { code: "SR", name: "고급" },
      ]);
    if (!/^\/api\/projects\/\d+\/staff-plans$/.test(url))
      throw new Error(`Unexpected request ${url}`);
    if (init?.method === "PUT") {
      if (writeFailure) return writeFailure.clone();
      data = {
        ...data,
        items: [
          ...data.items.filter((row) => row.src_need_id != null),
          ...JSON.parse(init.body as string).items.map(
            (row: ProjectStaffPlan) => ({
              ...row,
              need_psn_cnt: Math.ceil(row.need_mm_qty),
            }),
          ),
        ],
      };
    } else if (readFailure) throw new Error("offline");
    return response(data);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
const panel = (
  props: Partial<Parameters<typeof ProjectStaffPlanPanel>[0]> = {},
) =>
  render(
    <ProjectStaffPlanPanel
      projectId={3}
      useYn="Y"
      performanceStart="2026-10-01"
      performanceEnd="2026-11-30"
      onChanged={changed}
      onDirtyChange={dirty}
      onBasicInfo={basic}
      {...props}
    />,
  );
const writes = () =>
  fetcher.mock.calls.filter((call) => call[1]?.method === "PUT");
async function ready() {
  await screen.findByTestId("staff-plan-edit");
}
async function edit() {
  await ready();
  fireEvent.click(screen.getByTestId("staff-plan-edit"));
  await screen.findByLabelText("1행 직무");
}
const change = (label: string, value: string) =>
  fireEvent.change(screen.getByLabelText(label), { target: { value } });
const save = () => fireEvent.click(screen.getByTestId("staff-plan-save"));

describe("project staff plans", () => {
  it("summarizes months and renders deficit red at 4.00 planned vs 3.50 actual", async () => {
    panel();
    await ready();
    const row = screen.getByTestId("staff-summary-BE-none");
    expect(within(row).getByText("4.00")).toBeVisible();
    expect(within(row).getByText("3.50")).toBeVisible();
    expect(within(row).getByText("부족 0.50").className).toContain("danger");
    expect(within(row).getByText("–")).toBeVisible();
    expect(writes()).toHaveLength(0);
  });
  it("shows actual and job-wide difference once across grades and includes actual-only job", async () => {
    data.items = [plan("2026-10", 2, "JR"), plan("2026-10", 2, "SR")];
    data.actual.push({ job_cd: "FE", mm_qty: 1 });
    panel();
    await ready();
    expect(screen.getAllByText("3.50")).toHaveLength(1);
    expect(screen.getAllByText("부족 0.50")).toHaveLength(1);
    expect(
      within(screen.getByTestId("staff-summary-BE-SR")).getAllByText("–"),
    ).toHaveLength(2);
    expect(screen.getByText("초과 1.00").className).toContain("warning");
    expect(
      within(screen.getByTestId("staff-summary-FE-none")).getByText("0.00"),
    ).toBeVisible();
  });
  it("renders equal badge in green", async () => {
    data.actual[0].mm_qty = 4;
    panel();
    await ready();
    expect(screen.getByText("맞음").className).toContain("success");
  });
  it("shows empty results below all summary headers when plans and actuals are empty", async () => {
    data.items = [];
    data.actual = [];
    panel();
    await ready();
    const table = screen.getByRole("table");
    const headers = within(table).getAllByRole("columnheader");
    expect(headers.map((header) => header.textContent)).toEqual([
      "직무",
      "등급",
      "계획 MM",
      "실제 MM",
      "차이",
    ]);
    const empty = screen.getByText("인원 계획이 없습니다.");
    expect(empty).toBeVisible();
    expect(
      table.compareDocumentPosition(empty) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
    expect(within(table).getAllByRole("row")).toHaveLength(1);
    expect(writes()).toHaveLength(0);
  });
  it("keeps empty-plan message below headers alongside actual-only summary", async () => {
    data.items = [];
    panel();
    await ready();
    const empty = screen.getByText("인원 계획이 없습니다.");
    expect(empty).toBeVisible();
    expect(
      screen.getByRole("table").compareDocumentPosition(empty) &
        Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
    expect(screen.getByText("초과 3.50")).toBeVisible();
  });
  it("loads then handles failed read and retries without misleading empty state", async () => {
    readFailure = true;
    panel();
    expect(screen.getByLabelText("불러오는 중")).toBeVisible();
    expect(await screen.findByText("서버에 연결할 수 없습니다.")).toBeVisible();
    expect(screen.queryByText("인원 계획이 없습니다.")).not.toBeInTheDocument();
    readFailure = false;
    fireEvent.click(screen.getByText("다시 시도"));
    await ready();
  });
  it("offers active JOB options only, optional grades and monthly cost; saves and refreshes history", async () => {
    panel();
    await edit();
    expect(
      within(screen.getByLabelText("1행 직무")).getAllByRole("option"),
    ).toHaveLength(3);
    expect(screen.getByLabelText("1행 등급")).toHaveValue("");
    change("1행 2026-10 필요 MM", "2.01");
    change("1행 예상 월 원가", "6000000");
    save();
    expect(await screen.findByTestId("staff-plan-success")).toHaveTextContent(
      "인원 계획을 저장했습니다.",
    );
    expect(changed).toHaveBeenCalledOnce();
    expect(screen.queryByLabelText("1행 직무")).not.toBeInTheDocument();
    expect(JSON.parse(writes()[0][1].body as string)).toEqual({
      items: [
        {
          plan_ym: "2026-10",
          job_cd: "BE",
          grd_cd: "",
          need_mm_qty: 2.01,
          expc_mth_cst_amt: 6000000,
        },
        {
          plan_ym: "2026-11",
          job_cd: "BE",
          grd_cd: "",
          need_mm_qty: 2,
          expc_mth_cst_amt: 6000000,
        },
      ],
    });
  });
  it.each(["100", "2.001", "-1", "NaN"])(
    "rejects invalid MM %s before write and focuses month",
    async (value) => {
      panel();
      await edit();
      change("1행 2026-10 필요 MM", value);
      save();
      expect(await screen.findByRole("alert")).toHaveTextContent(
        "필요 MM은 0~99.99",
      );
      expect(screen.getByLabelText("1행 2026-10 필요 MM")).toHaveFocus();
      expect(writes()).toHaveLength(0);
    },
  );
  it("requires JOB on added row", async () => {
    panel();
    await edit();
    fireEvent.click(screen.getByTestId("staff-plan-add"));
    save();
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "직무를 골라 주세요.",
    );
    expect(screen.getByLabelText("2행 직무")).toHaveFocus();
    expect(writes()).toHaveLength(0);
  });
  it("adds and deletes draft rows and cancel restores summary without save", async () => {
    panel();
    await edit();
    fireEvent.click(screen.getByTestId("staff-plan-add"));
    change("2행 직무", "FE");
    change("2행 등급", "JR");
    change("2행 2026-11 필요 MM", "1");
    fireEvent.click(screen.getByTestId("staff-plan-remove-0"));
    expect(dirty).toHaveBeenCalledWith(true);
    fireEvent.click(screen.getByTestId("staff-plan-cancel"));
    expect(screen.getByText("부족 0.50")).toBeVisible();
    expect(writes()).toHaveLength(0);
    await edit();
    expect(screen.getByLabelText("1행 직무")).toHaveValue("BE");
  });
  it("omits blank and zero cells and allows empty replacement", async () => {
    panel();
    await edit();
    change("1행 2026-10 필요 MM", "");
    change("1행 2026-11 필요 MM", "0");
    save();
    await screen.findByTestId("staff-plan-success");
    expect(JSON.parse(writes()[0][1].body as string)).toEqual({ items: [] });
    expect(screen.getByText("인원 계획이 없습니다.")).toBeVisible();
  });
  it("sourced rows are read-only, not deletable and never sent in PUT", async () => {
    data.items.push(plan("2026-10", 1, "SR", "FE", 8));
    panel();
    await edit();
    expect(screen.getByLabelText("2행 직무")).toBeDisabled();
    expect(screen.getByLabelText("2행 2026-10 필요 MM")).toHaveAttribute(
      "readonly",
    );
    expect(screen.getByText("원천 연결").className).toContain("locked");
    expect(screen.queryByTestId("staff-plan-remove-1")).not.toBeInTheDocument();
    save();
    await screen.findByTestId("staff-plan-success");
    expect(JSON.parse(writes()[0][1].body as string).items).toHaveLength(2);
    expect(data.items.some((row) => row.src_need_id === 8)).toBe(true);
  });
  it("prevents duplicate manual cell keys before save", async () => {
    panel();
    await edit();
    fireEvent.click(screen.getByTestId("staff-plan-add"));
    change("2행 직무", "BE");
    change("2행 2026-10 필요 MM", "1");
    save();
    expect(await screen.findByRole("alert")).toHaveTextContent("겹치는 칸");
    expect(writes()).toHaveLength(0);
  });
  it("warns about outside months, includes totals and retains unchanged values", async () => {
    panel({ performanceEnd: "2026-10-31" });
    await ready();
    expect(screen.getByText(/수행 기간 밖의 계획/).className).toContain(
      "warning",
    );
    expect(screen.getByText("4.00")).toBeVisible();
    await edit();
    save();
    await screen.findByTestId("staff-plan-success");
    expect(JSON.parse(writes()[0][1].body as string).items).toHaveLength(2);
  });
  it("rejects changing outside-period cell, but allows explicit clearing", async () => {
    panel({ performanceEnd: "2026-10-31" });
    await edit();
    change("1행 2026-11 필요 MM", "3");
    save();
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "유지하거나 지울 수만",
    );
    expect(writes()).toHaveLength(0);
    change("1행 2026-11 필요 MM", "");
    save();
    await screen.findByTestId("staff-plan-success");
    expect(JSON.parse(writes()[0][1].body as string).items).toHaveLength(1);
  });
  it("retains input and focuses mapped API field on server validation failure", async () => {
    panel();
    await edit();
    change("1행 2026-10 필요 MM", "2.5");
    writeFailure = new Response(
      JSON.stringify({
        success: false,
        meta: {},
        code: "INVALID_INPUT",
        message: "직무를 확인하세요.",
        field: "items[0].job_cd",
        requestId: "staff-request",
      }),
      { status: 400 },
    );
    save();
    await screen.findAllByText("직무를 확인하세요.");
    expect(screen.getByLabelText("1행 직무")).toHaveFocus();
    expect(screen.getByLabelText("1행 2026-10 필요 MM")).toHaveValue("2.5");
    expect(screen.getByText("문의 번호 staff-request")).toBeVisible();
    expect(changed).not.toHaveBeenCalled();
  });
  it("disabled projects hide all writes while still showing summary", async () => {
    panel({ useYn: "N" });
    await screen.findByText("부족 0.50");
    expect(
      screen.getByText("사용 안 함 프로젝트는 조회만 가능합니다."),
    ).toBeVisible();
    expect(screen.queryByTestId("staff-plan-edit")).not.toBeInTheDocument();
    expect(writes()).toHaveLength(0);
  });
  it("mobile hides editing controls and drops an open draft on resize", async () => {
    panel();
    await edit();
    change("1행 2026-10 필요 MM", "9");
    Object.defineProperty(window, "innerWidth", { value: 767 });
    fireEvent(window, new Event("resize"));
    expect(screen.queryByTestId("staff-plan-save")).not.toBeInTheDocument();
    expect(screen.queryByTestId("staff-plan-edit")).not.toBeInTheDocument();
    expect(screen.getByText("부족 0.50")).toBeVisible();
    expect(writes()).toHaveLength(0);
  });
  it("links to basic information without inventing target fields", async () => {
    panel();
    await ready();
    fireEvent.click(screen.getByTestId("staff-plan-basic-info"));
    expect(basic).toHaveBeenCalledOnce();
    await edit();
    expect(screen.queryByLabelText("목표 원가")).not.toBeInTheDocument();
    expect(screen.queryByLabelText("목표 마진율")).not.toBeInTheDocument();
  });
  it("selected-project change remounts the editor without leaking draft", async () => {
    const view = panel();
    await edit();
    change("1행 2026-10 필요 MM", "9");
    data.items = [];
    view.rerender(
      <ProjectStaffPlanPanel
        projectId={4}
        useYn="Y"
        performanceStart="2026-10-01"
        performanceEnd="2026-11-30"
        onChanged={changed}
      />,
    );
    await waitFor(() =>
      expect(screen.getByText("인원 계획이 없습니다.")).toBeVisible(),
    );
    expect(
      fetcher.mock.calls.some(
        (call) => call[0] === "/api/projects/4/staff-plans",
      ),
    ).toBe(true);
  });
  it("client strips response-only source, count and target fields", async () => {
    await saveProjectStaffPlans(3, [data.items[0]]);
    expect(JSON.parse(writes()[0][1].body as string).items[0]).toEqual({
      plan_ym: "2026-10",
      job_cd: "BE",
      grd_cd: "",
      need_mm_qty: 2,
      expc_mth_cst_amt: null,
    });
  });
});
