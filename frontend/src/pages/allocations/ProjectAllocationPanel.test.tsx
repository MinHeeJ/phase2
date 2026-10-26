import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import { ProjectAllocationPanel } from "./ProjectAllocationPanel";
import type { AllocationLine, AllocationStage } from "./client";

const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
let rows: Record<AllocationStage, AllocationLine[]>;
let realoc: "Y" | "N";
let readFailure: boolean;
let writeFailure: Response | null;
let fetcher: ReturnType<typeof vi.fn>;
const changed = vi.fn().mockResolvedValue(undefined);
const item = (amount = 600000000): AllocationLine => ({
  line_ty_cd: "ITEM",
  dev_id: null,
  item_nm: "기술료",
  job_cd: null,
  asgn_bgn_dt: null,
  asgn_se_cd: null,
  rmrk_desc: null,
  sort_seq: 0,
  months: [{ aloc_ym: "2026-10", aloc_amt: amount, mm_qty: 1.25 }],
});
beforeEach(() => {
  Object.defineProperty(window, "innerWidth", {
    configurable: true,
    writable: true,
    value: 1280,
  });
  rows = { EST: [item(100)], FINAL: [item(200)], ACTUAL: [item()] };
  realoc = "Y";
  readFailure = false;
  writeFailure = null;
  changed.mockClear();
  fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (url === "/api/codes/JOB")
      return response([{ code: "BE", name: "백엔드" }]);
    if (url === "/api/codes/ASSIGN_KIND")
      return response([{ code: "NEW", name: "신규" }]);
    if (url.startsWith("/api/developers/options?"))
      return response([
        {
          id: 9,
          name: "개발자",
          mngNo: "K-0009",
          mainJob: "백엔드",
          empType: "FT",
        },
      ]);
    if (url.startsWith("/api/projects/3/allocations")) {
      if (init?.method === "PUT") {
        if (writeFailure) return writeFailure.clone();
        const selected = new URL(url, "http://test").searchParams.get(
          "stage",
        ) as AllocationStage;
        rows[selected] = JSON.parse(init.body as string).items;
        realoc = "N";
      } else if (readFailure) throw new Error("offline");
      const activeStage =
        (["ACTUAL", "FINAL", "EST"] as const).find((s) => rows[s].length > 0) ??
        null;
      const stage = new URL(url, "http://test").searchParams.get(
        "stage",
      ) as AllocationStage | null;
      const selected = stage ?? activeStage ?? "EST";
      return response({
        aloc_stg_cd: selected,
        activeStage,
        items: rows[selected],
        cur_cntrct_amt: 600000000,
        realoc_need_yn: realoc,
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
const panel = (
  extra: Partial<React.ComponentProps<typeof ProjectAllocationPanel>> = {},
) =>
  render(
    <ProjectAllocationPanel
      projectId={3}
      performanceStart="2026-10-01"
      performanceEnd="2026-11-30"
      onChanged={changed}
      {...extra}
    />,
  );
const change = (label: string, value: string) =>
  fireEvent.change(screen.getByLabelText(label), { target: { value } });
const writes = () => fetcher.mock.calls.filter((c) => c[1]?.method === "PUT");
async function ready() {
  await screen.findByDisplayValue("기술료");
  await waitFor(() =>
    expect(screen.getByTestId("allocation-save")).not.toBeDisabled(),
  );
}
async function addPerson() {
  fireEvent.click(screen.getByTestId("allocation-add-person"));
}

describe("project allocation panel", () => {
  it("opens ACTUAL by default with underlined tabs and active badge", async () => {
    panel();
    await ready();
    const active = screen.getByRole("tab", { name: "실제 투입계획 사용 중" });
    expect(active).toHaveAttribute("aria-selected", "true");
    expect(within(active).getByText("사용 중").className).toContain("info");
    expect(screen.getByLabelText("2026-10 금액 1")).toHaveValue("600000000");
    expect(
      screen.queryByTestId("allocation-amount-warning"),
    ).not.toBeInTheDocument();
  });
  it.each(["FINAL", "EST"] as const)(
    "falls back to %s when later stages are absent",
    async (stage) => {
      rows.ACTUAL = [];
      if (stage === "EST") rows.FINAL = [];
      panel();
      await ready();
      expect(
        screen.getByTestId(`allocation-stage-${stage.toLowerCase()}`),
      ).toHaveAttribute("aria-selected", "true");
    },
  );
  it("selects another stage without changing its active badge", async () => {
    panel();
    await ready();
    fireEvent.click(screen.getByRole("tab", { name: "견적" }));
    await waitFor(() =>
      expect(screen.getByLabelText("2026-10 금액 1")).toHaveValue("100"),
    );
    expect(
      screen.getByRole("tab", { name: "실제 투입계획 사용 중" }),
    ).toBeVisible();
    expect(
      screen.queryByTestId("allocation-amount-warning"),
    ).not.toBeInTheDocument();
  });
  it("renders exact headers, monthly and overall amount/MM totals", async () => {
    panel();
    await ready();
    for (const name of [
      "종류",
      "사람 또는 항목명",
      "직무",
      "투입일",
      "투입 구분",
      "비고",
    ])
      expect(screen.getByRole("columnheader", { name })).toBeVisible();
    expect(screen.getByText("600,000,000원")).toBeVisible();
    expect(screen.getByText(/· 1.25 MM/)).toBeVisible();
  });
  it("shows orange mismatch using money only and permits saving", async () => {
    panel();
    await ready();
    change("2026-10 금액 1", "590000000");
    const warning = screen.getByTestId("allocation-amount-warning");
    expect(warning).toHaveTextContent(
      "배분표 합계(590,000,000)가 현재 계약금액(600,000,000)과 다릅니다.",
    );
    expect(warning.className).toContain("warning");
    fireEvent.click(screen.getByTestId("allocation-save"));
    expect(await screen.findByText("배분표를 저장했습니다.")).toBeVisible();
    expect(rows.ACTUAL[0].months[0].aloc_amt).toBe(590000000);
    expect(rows.FINAL[0].months[0].aloc_amt).toBe(200);
  });
  it("does not compare MM total with current contract", async () => {
    panel();
    await ready();
    change("2026-10 MM 1", "999.99");
    expect(
      screen.queryByTestId("allocation-amount-warning"),
    ).not.toBeInTheDocument();
  });
  it("confirmed reallocation banner disappears after successful save and parent refresh", async () => {
    panel();
    await ready();
    expect(
      screen.getByText("계약이 바뀌었습니다. 배분표를 다시 맞춰 주세요."),
    ).toBeVisible();
    fireEvent.click(screen.getByTestId("allocation-save"));
    await screen.findByText("배분표를 저장했습니다.");
    expect(
      screen.queryByText("계약이 바뀌었습니다. 배분표를 다시 맞춰 주세요."),
    ).not.toBeInTheDocument();
    expect(changed).toHaveBeenCalledOnce();
    expect(writes()[0][0]).toBe("/api/projects/3/allocations?stage=ACTUAL");
  });
  it("hides reallocation banner when API says N", async () => {
    realoc = "N";
    panel();
    await ready();
    expect(
      screen.queryByText("계약이 바뀌었습니다. 배분표를 다시 맞춰 주세요."),
    ).not.toBeInTheDocument();
  });
  it("ITEM can save without developer, job or assignment date and omits blank cells", async () => {
    panel();
    await ready();
    fireEvent.click(screen.getByTestId("allocation-add-item"));
    change("항목명 2", "경비");
    change("2026-11 금액 2", "0");
    expect(screen.queryByLabelText("직무 2")).not.toBeInTheDocument();
    fireEvent.click(screen.getByTestId("allocation-save"));
    await screen.findByText("배분표를 저장했습니다.");
    expect(rows.ACTUAL[1]).toMatchObject({
      item_nm: "경비",
      dev_id: null,
      job_cd: null,
      asgn_bgn_dt: null,
    });
    expect(rows.ACTUAL[1].months).toEqual([
      { aloc_ym: "2026-11", aloc_amt: 0, mm_qty: null },
    ]);
  });
  it("requires item name without sending a request", async () => {
    panel();
    await ready();
    fireEvent.click(screen.getByTestId("allocation-add-item"));
    fireEvent.click(screen.getByTestId("allocation-save"));
    expect(await screen.findByText("항목명을 입력해 주세요.")).toBeVisible();
    expect(writes()).toHaveLength(0);
  });
  it.each([
    ["developer", "개발자를 골라 주세요."],
    ["job", "직무를 골라 주세요."],
    ["date", "투입일을 입력해 주세요."],
  ])("PERSON requires %s", async (field, message) => {
    panel();
    await ready();
    await addPerson();
    if (field !== "developer") change("사람 2", "9");
    if (field === "date") change("직무 2", "BE");
    fireEvent.click(screen.getByTestId("allocation-save"));
    expect(await screen.findByText(message)).toBeVisible();
    expect(writes()).toHaveLength(0);
  });
  it("uses API developer/JOB/ASSIGN_KIND choices and sends a person", async () => {
    panel();
    await ready();
    await addPerson();
    change("사람 2", "9");
    change("직무 2", "BE");
    change("투입일 2", "2026-10-15");
    change("투입 구분 2", "NEW");
    change("2026-10 금액 2", "1000");
    change("2026-10 MM 2", "0.50");
    fireEvent.click(screen.getByTestId("allocation-save"));
    await screen.findByText("배분표를 저장했습니다.");
    expect(rows.ACTUAL[1]).toMatchObject({
      line_ty_cd: "PERSON",
      dev_id: 9,
      job_cd: "BE",
      asgn_bgn_dt: "2026-10-15",
      asgn_se_cd: "NEW",
    });
    expect(rows.ACTUAL[1].months[0].mm_qty).toBe(0.5);
  });
  it("deletes locally, allows empty replacement and cancels back to stored data", async () => {
    panel();
    await ready();
    fireEvent.click(screen.getByTestId("allocation-delete-0"));
    expect(screen.getByText("등록된 배분표가 없습니다.")).toBeVisible();
    expect(writes()).toHaveLength(0);
    fireEvent.click(screen.getByTestId("allocation-cancel"));
    expect(screen.getByDisplayValue("기술료")).toBeVisible();
    fireEvent.click(screen.getByTestId("allocation-delete-0"));
    fireEvent.click(screen.getByTestId("allocation-save"));
    await screen.findByText("배분표를 저장했습니다.");
    expect(rows.ACTUAL).toEqual([]);
  });
  it("dirty stage exit asks before discarding and keep-writing preserves input", async () => {
    panel();
    await ready();
    change("항목명 1", "변경");
    fireEvent.click(screen.getByRole("tab", { name: "견적" }));
    expect(screen.getByRole("dialog")).toBeVisible();
    fireEvent.click(screen.getByTestId("allocation-keep-editing"));
    expect(screen.getByLabelText("항목명 1")).toHaveValue("변경");
    fireEvent.click(screen.getByRole("tab", { name: "견적" }));
    fireEvent.click(screen.getByTestId("allocation-discard"));
    await waitFor(() =>
      expect(screen.getByLabelText("항목명 1")).toHaveValue("기술료"),
    );
    expect(writes()).toHaveLength(0);
  });
  it("preserves draft on server field validation error", async () => {
    writeFailure = new Response(
      JSON.stringify({
        success: false,
        code: "INVALID_INPUT",
        message: "직무를 확인하세요.",
        field: "job_cd",
        requestId: "allocation-request",
      }),
      { status: 400 },
    );
    panel();
    await ready();
    change("항목명 1", "수정 중");
    fireEvent.click(screen.getByTestId("allocation-save"));
    await screen.findByText("직무를 확인하세요.");
    expect(screen.getByLabelText("항목명 1")).toHaveValue("수정 중");
    expect(changed).not.toHaveBeenCalled();
    expect(screen.getByText("문의 번호 allocation-request")).toBeVisible();
  });
  it("outside-period values remain in totals and can be explicitly removed", async () => {
    rows.ACTUAL[0].months.push({
      aloc_ym: "2026-09",
      aloc_amt: 10,
      mm_qty: 0.1,
    });
    panel();
    await ready();
    expect(screen.getByLabelText("2026-09 금액 1")).toHaveAttribute("readonly");
    expect(screen.getByText("600,000,010원")).toBeVisible();
    expect(screen.getByText(/수행 기간 밖의 기존 월 값도/)).toBeVisible();
    fireEvent.click(screen.getByTestId("allocation-clear-aloc-amt-0-2026-09"));
    fireEvent.click(screen.getByTestId("allocation-clear-mm-qty-0-2026-09"));
    fireEvent.click(screen.getByTestId("allocation-save"));
    await screen.findByText("배분표를 저장했습니다.");
    expect(rows.ACTUAL[0].months.some((m) => m.aloc_ym === "2026-09")).toBe(
      false,
    );
  });
  it("empty response is not populated with sample rows", async () => {
    rows = { EST: [], FINAL: [], ACTUAL: [] };
    panel();
    expect(await screen.findByText("등록된 배분표가 없습니다.")).toBeVisible();
    expect(screen.queryByTestId("allocation-row-0")).not.toBeInTheDocument();
  });
  it("shows loading, read failure and retry without false empty state", async () => {
    readFailure = true;
    panel();
    expect(screen.getByLabelText("불러오는 중")).toBeVisible();
    await screen.findByText("서버에 연결할 수 없습니다.");
    expect(
      screen.queryByText("등록된 배분표가 없습니다."),
    ).not.toBeInTheDocument();
    readFailure = false;
    fireEvent.click(screen.getByText("다시 시도"));
    await ready();
  });
  it.each(["disabled", "mobile"])("%s state is read-only", async (mode) => {
    if (mode === "mobile")
      Object.defineProperty(window, "innerWidth", { value: 767 });
    panel(mode === "disabled" ? { useYn: "N" } : {});
    await screen.findByDisplayValue("기술료");
    expect(screen.queryByTestId("allocation-save")).not.toBeInTheDocument();
    expect(
      screen.queryByTestId("allocation-add-person"),
    ).not.toBeInTheDocument();
    expect(screen.queryByTestId("allocation-delete-0")).not.toBeInTheDocument();
    expect(screen.getByLabelText("항목명 1")).toBeDisabled();
    expect(writes()).toHaveLength(0);
  });
  it.each([
    ["2026-10 MM 1", "1.001"],
    ["2026-10 금액 1", "1.5"],
  ])("rejects invalid %s without a write", async (label, value) => {
    panel();
    await ready();
    change(label, value);
    fireEvent.click(screen.getByTestId("allocation-save"));
    expect(
      await screen.findByText(
        label.includes("MM")
          ? "MM은 소수 둘째 자리와 숫자 범위를 확인하세요."
          : "원 단위 정수 금액을 확인하세요.",
      ),
    ).toBeVisible();
    expect(writes()).toHaveLength(0);
  });
});
