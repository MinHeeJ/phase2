import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { SalesPartsTab } from "./SalesPartsTab";
import { SalesPartCreateDialog } from "./SalesPartCreateDialog";
import {
  createSalesPart,
  type SalesPartDetail,
  type SalesPartStandard,
} from "./client";
import { useSharedPreferences } from "../shared/preferences";

const standard: SalesPartStandard = {
  part_std_hist_id: "21",
  aply_bgn_ym: "2026-01",
  aply_end_ym: "2026-10",
  ldr_nm: "파트장",
  prft_dstrb_rt: 40,
  bsn_fee_amt: 1500000,
  status: "적용 중",
};
const future: SalesPartStandard = {
  ...standard,
  part_std_hist_id: "22",
  aply_bgn_ym: "2026-11",
  aply_end_ym: null,
  prft_dstrb_rt: 45,
  status: "예정",
};
const part: SalesPartDetail = {
  bsn_part_id: "9007199254740993",
  bsn_part_nm: "영업",
  hq_yn: "N",
  use_yn: "Y",
  sort_seq: 1,
  referenceMonth: "2026-10",
  currentStandard: standard,
  scheduledChanges: [future],
  peopleCount: 0,
  projectCount: 0,
  standards: [future, standard],
  lastClosedMonth: "2026-09",
  firstAllowedMonth: "2026-10",
};
const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
const errorResponse = (field: string | null = null) =>
  new Response(
    JSON.stringify({
      success: false,
      meta: {},
      code: "INVALID_INPUT",
      message: "입력 오류",
      field,
      requestId: "sales-request",
    }),
    { status: 400 },
  );
let fetcher: ReturnType<typeof vi.fn>;
let rows: SalesPartDetail[], closed: { month: string; status: string }[];
beforeEach(() => {
  localStorage.clear();
  useSharedPreferences.setState({ pageSize: 50, dense: false });
  rows = [part];
  closed = [{ month: "2026-09", status: "CLOSED" }];
  fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (init?.method === "POST") return response(part);
    if (url.startsWith("/api/sales-parts?"))
      return response({ items: rows, total: rows.length, page: 1, size: 50 });
    if (url.startsWith("/api/sales-parts/"))
      return response(rows.find((r) => url.endsWith(r.bsn_part_id)) ?? part);
    if (url.startsWith("/api/month-close?")) return response(closed);
    if (url.startsWith("/api/params/"))
      return response({
        num_val: url.includes("PART_PROFIT_RATE") ? 40 : 1500000,
      });
    throw new Error(`Unexpected test request ${url}`);
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
  fetcher.mock.calls.filter((call) => call[1]?.method === "POST");
async function openCreate() {
  fireEvent.click(screen.getByRole("button", { name: "+ 파트 추가" }));
  await waitFor(() =>
    expect(screen.getByLabelText("이익 배분율 (%)")).toHaveValue("40"),
  );
}
const fillNames = () => {
  change("파트명", "신규");
  change("파트장", "이름");
};

describe("영업 파트 목록과 상세", () => {
  it("shows real response fields, scheduled badges, zero counts, and no upload/template controls", async () => {
    render(<SalesPartsTab />);
    expect(
      await screen.findByRole("button", { name: "영업" }),
    ).toBeInTheDocument();
    expect(screen.getByText("2026-11부터 45%")).toBeInTheDocument();
    expect(screen.getByText("0명")).toBeInTheDocument();
    expect(screen.getByText("0건")).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "예정된 변경" }),
    ).not.toBeInTheDocument();
    expect(screen.queryByText("엑셀 업로드")).not.toBeInTheDocument();
    expect(screen.queryByText("양식 내려받기")).not.toBeInTheDocument();
    expect(
      screen.getByText(
        "이익 배분율·영업비를 바꾸면 적용 시작월부터 새 기준을 씁니다. 지난 달 정산은 바뀌지 않습니다. 기준 변경은 시스템 관리자·재무 담당만 할 수 있습니다.",
      ),
    ).toBeInTheDocument();
  });
  it("uses selected string id without numeric conversion and preserves current versus future values", async () => {
    render(<SalesPartsTab />);
    fireEvent.click(await screen.findByRole("button", { name: "영업" }));
    const panel = await screen.findByRole("complementary", {
      name: "영업 파트 상세",
    });
    expect(await within(panel).findByText("2026-10 기준")).toBeInTheDocument();
    expect(
      within(panel).getByText(
        "마감된 달(2026-09)까지의 정산에는 그때 기준이 그대로 쓰입니다. 새 기준은 2026-10부터 넣을 수 있습니다.",
      ),
    ).toBeInTheDocument();
    const histories = within(panel).getAllByTestId(/sales-part-history-/);
    expect(histories[0]).toHaveTextContent("2026-11 ~ 현재");
    expect(histories[0]).toHaveTextContent("예정");
    expect(histories[1]).toHaveTextContent("2026-01 ~ 2026-10");
    expect(fetcher).toHaveBeenCalledWith(
      `/api/sales-parts/${part.bsn_part_id}`,
      expect.anything(),
    );
    expect(writes()).toHaveLength(0);
  });
  it("hides all headquarters mutation controls while retaining zero fee and history", async () => {
    rows = [
      {
        ...part,
        hq_yn: "Y",
        currentStandard: {
          ...standard,
          prft_dstrb_rt: 0,
          bsn_fee_amt: 0,
          ldr_nm: null,
        },
      },
    ];
    render(<SalesPartsTab />);
    fireEvent.click(await screen.findByRole("button", { name: "영업" }));
    const panel = await screen.findByRole("complementary");
    await within(panel).findByText("2026-10 기준");
    expect(within(panel).getByText("본사")).toBeInTheDocument();
    expect(within(panel).getByText("0원")).toBeInTheDocument();
    expect(
      within(panel).queryByRole("button", { name: "기준 변경" }),
    ).not.toBeInTheDocument();
    expect(
      within(panel).queryByRole("button", { name: "파트 더보기" }),
    ).not.toBeInTheDocument();
  });
  it("shows stopped banner and delegates enable/rename using selected detail", async () => {
    rows = [{ ...part, use_yn: "N" }];
    const action = vi.fn();
    render(<SalesPartsTab onAction={action} />);
    fireEvent.click(await screen.findByRole("button", { name: "영업" }));
    const panel = await screen.findByRole("complementary");
    expect(
      await within(panel).findByText("사용을 중지한 파트입니다."),
    ).toBeInTheDocument();
    fireEvent.click(within(panel).getByRole("button", { name: "파트 더보기" }));
    fireEvent.click(within(panel).getByRole("button", { name: "다시 사용" }));
    expect(action).toHaveBeenCalledWith(
      "enable",
      rows[0],
      expect.any(Function),
    );
    fireEvent.click(within(panel).getByRole("button", { name: "파트명 수정" }));
    expect(action).toHaveBeenCalledWith(
      "rename",
      rows[0],
      expect.any(Function),
    );
  });
  it("delegates standard and disable controls, keeping mobile mutation class", async () => {
    const action = vi.fn();
    render(<SalesPartsTab onAction={action} />);
    fireEvent.click(await screen.findByRole("button", { name: "영업" }));
    const panel = await screen.findByRole("complementary");
    const button = await within(panel).findByRole("button", {
      name: "기준 변경",
    });
    expect(button.parentElement?.className).toContain("mutations");
    fireEvent.click(button);
    expect(action).toHaveBeenCalledWith("standard", part, expect.any(Function));
    fireEvent.click(within(panel).getByRole("button", { name: "파트 더보기" }));
    fireEvent.click(within(panel).getByRole("button", { name: "사용 중지" }));
    expect(action).toHaveBeenCalledWith("disable", part, expect.any(Function));
    expect(
      screen.getByRole("button", { name: "+ 파트 추가" }).className,
    ).toContain("mutations");
  });
  it("shows empty state", async () => {
    rows = [];
    render(<SalesPartsTab />);
    expect(
      await screen.findByText("조건에 맞는 결과가 없습니다"),
    ).toBeInTheDocument();
  });
  it("shows loading then a failed list with retry", async () => {
    fetcher.mockResolvedValueOnce(errorResponse());
    render(<SalesPartsTab />);
    expect(screen.getByLabelText("불러오는 중")).toBeInTheDocument();
    expect(await screen.findByText("입력 오류")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: /다시 시도/ }));
    expect(
      await screen.findByRole("button", { name: "영업" }),
    ).toBeInTheDocument();
  });
  it("does not retain stale detail when detail request fails", async () => {
    fetcher.mockImplementation(async (url: string) =>
      url.startsWith("/api/sales-parts?")
        ? response({ items: rows, total: 1, page: 1, size: 50 })
        : errorResponse(),
    );
    render(<SalesPartsTab />);
    fireEvent.click(await screen.findByRole("button", { name: "영업" }));
    const panel = await screen.findByRole("complementary");
    expect(await within(panel).findByText("입력 오류")).toBeInTheDocument();
    expect(within(panel).queryByText("기준 이력")).not.toBeInTheDocument();
  });
  it("sorts server columns and updates shared page size", async () => {
    render(<SalesPartsTab />);
    await screen.findByRole("button", { name: "영업" });
    fireEvent.click(screen.getByRole("button", { name: "이익 배분율" }));
    await waitFor(() =>
      expect(
        fetcher.mock.calls.some(
          ([url]) =>
            new URLSearchParams(String(url).split("?")[1]).get("sort") ===
            "currentStandard.prft_dstrb_rt,asc",
        ),
      ).toBe(true),
    );
    fireEvent.change(screen.getByRole("combobox", { name: "목록 건수" }), {
      target: { value: "20" },
    });
    await waitFor(() =>
      expect(useSharedPreferences.getState().pageSize).toBe(20),
    );
  });
});

describe("영업 파트 추가", () => {
  it("defaults to first allowed month, loads monthly params, saves numeric body, and refreshes", async () => {
    render(<SalesPartsTab />);
    await openCreate();
    expect(screen.getByLabelText("적용 시작월")).toHaveValue("2026-10");
    expect(screen.getByLabelText("적용 시작월")).toHaveAttribute(
      "min",
      "2026-10",
    );
    expect(
      screen.getByText("마감된 달(2026-09) 다음 달부터 고를 수 있습니다."),
    ).toBeInTheDocument();
    fillNames();
    fireEvent.click(screen.getByRole("button", { name: /^추가$/ }));
    expect(await screen.findByText("신규을 추가했습니다.")).toBeInTheDocument();
    expect(JSON.parse(writes()[0][1].body)).toEqual({
      bsn_part_nm: "신규",
      ldr_nm: "이름",
      aply_bgn_ym: "2026-10",
      prft_dstrb_rt: 40,
      bsn_fee_amt: 1500000,
    });
    expect(
      fetcher.mock.calls.filter(([url]) =>
        String(url).startsWith("/api/sales-parts?"),
      ).length,
    ).toBeGreaterThan(1);
  });
  it("requires explicit month without close and does not fabricate defaults", async () => {
    closed = [];
    render(<SalesPartsTab />);
    fireEvent.click(screen.getByRole("button", { name: "+ 파트 추가" }));
    await screen.findByText(
      "마감 이력이 없습니다. 적용 시작월을 직접 선택해 주세요.",
    );
    expect(screen.getByLabelText("적용 시작월")).toHaveValue("");
    expect(screen.getByLabelText("적용 시작월")).not.toHaveAttribute("min");
    change("적용 시작월", "2025-01");
    await waitFor(() =>
      expect(screen.getByLabelText("이익 배분율 (%)")).toHaveValue("40"),
    );
    expect(
      fetcher.mock.calls.some(([url]) =>
        String(url).includes("PART_PROFIT_RATE?month=2025-01"),
      ),
    ).toBe(true);
  });
  it("reloads both defaults for changed month", async () => {
    render(<SalesPartsTab />);
    await openCreate();
    change("적용 시작월", "2026-11");
    await waitFor(() =>
      expect(
        fetcher.mock.calls.some(([url]) =>
          String(url).includes("PART_SALES_FEE?month=2026-11"),
        ),
      ).toBe(true),
    );
    await waitFor(() =>
      expect(screen.getByLabelText("영업비 (원)")).toHaveValue("1,500,000"),
    );
    expect(
      fetcher.mock.calls.some(([url]) =>
        String(url).includes("PART_PROFIT_RATE?month=2026-11"),
      ),
    ).toBe(true);
  });
  it.each([
    ["파트명", "", "파트명을 입력해 주세요."],
    ["파트장", "", "파트장을 입력해 주세요."],
    [
      "이익 배분율 (%)",
      "120",
      "이익 배분율은 0~100 사이 숫자로 입력해 주세요.",
    ],
    [
      "이익 배분율 (%)",
      "0.001",
      "이익 배분율은 0~100 사이 숫자로 입력해 주세요.",
    ],
    ["영업비 (원)", "0", "영업비를 입력해 주세요."],
  ])("validates %s without writing", async (label, value, message) => {
    render(<SalesPartsTab />);
    await openCreate();
    fillNames();
    change(label, value);
    fireEvent.click(screen.getByRole("button", { name: /^추가$/ }));
    expect(await screen.findByText(message)).toBeInTheDocument();
    expect(writes()).toHaveLength(0);
  });
  it.each(["0", "100"])("accepts percent boundary %s", async (value) => {
    render(<SalesPartsTab />);
    await openCreate();
    fillNames();
    change("이익 배분율 (%)", value);
    fireEvent.click(screen.getByRole("button", { name: /^추가$/ }));
    await waitFor(() => expect(writes()).toHaveLength(1));
  });
  it("rejects a closed month and preserves names", async () => {
    render(<SalesPartsTab />);
    await openCreate();
    fillNames();
    change("적용 시작월", "2026-09");
    fireEvent.click(screen.getByRole("button", { name: /^추가$/ }));
    expect(
      await screen.findByText(
        "적용 시작월은 마감된 달(2026-09) 다음 달부터 고를 수 있습니다.",
      ),
    ).toBeInTheDocument();
    expect(screen.getByLabelText("파트명")).toHaveValue("신규");
    expect(writes()).toHaveLength(0);
  });
  it("preserves server field error and focuses field", async () => {
    const original = fetcher.getMockImplementation()!;
    fetcher.mockImplementation((url: string, init?: RequestInit) =>
      init?.method === "POST"
        ? Promise.resolve(errorResponse("bsn_part_nm"))
        : original(url, init),
    );
    render(<SalesPartsTab />);
    await openCreate();
    fillNames();
    fireEvent.click(screen.getByRole("button", { name: /^추가$/ }));
    await waitFor(() => expect(screen.getByLabelText("파트명")).toHaveFocus());
    expect(screen.getByLabelText("파트명")).toHaveValue("신규");
    expect(
      screen.getByRole("dialog", { name: "영업 파트 추가" }),
    ).toBeInTheDocument();
  });
  it("blocks save when params fail and retries the read", async () => {
    const original = fetcher.getMockImplementation()!;
    let fail = true;
    fetcher.mockImplementation((url: string, init?: RequestInit) =>
      fail && url.startsWith("/api/params/")
        ? Promise.resolve(errorResponse())
        : original(url, init),
    );
    render(<SalesPartsTab />);
    fireEvent.click(screen.getByRole("button", { name: "+ 파트 추가" }));
    await screen.findByText("입력 오류");
    expect(screen.getByRole("button", { name: /^추가$/ })).toBeDisabled();
    fail = false;
    fireEvent.click(screen.getByRole("button", { name: /다시 시도/ }));
    await waitFor(() =>
      expect(screen.getByLabelText("이익 배분율 (%)")).toHaveValue("40"),
    );
  });
  it("guards dirty cancellation and enforces text max lengths", async () => {
    const cancel = vi.fn();
    render(
      <SalesPartCreateDialog
        onCancel={cancel}
        onSaved={vi.fn()}
        reload={async () => {}}
      />,
    );
    await waitFor(() =>
      expect(screen.getByLabelText("이익 배분율 (%)")).toHaveValue("40"),
    );
    expect(screen.getByLabelText("파트명")).toHaveAttribute("maxlength", "30");
    expect(screen.getByLabelText("파트장")).toHaveAttribute("maxlength", "50");
    expect(screen.queryByRole("combobox")).not.toBeInTheDocument();
    change("파트명", "작성");
    fireEvent.click(screen.getByRole("button", { name: "취소" }));
    expect(
      screen.getByRole("dialog", { name: "저장하지 않은 내용이 있습니다." }),
    ).toBeInTheDocument();
    expect(cancel).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "나가기" }));
    expect(cancel).toHaveBeenCalledOnce();
  });
  it("serializes long won values without losing precision", async () => {
    await createSalesPart({
      bsn_part_nm: "이름",
      aply_bgn_ym: "2026-11",
      ldr_nm: "파트장",
      prft_dstrb_rt: "40",
      bsn_fee_amt: "9007199254740993",
    });
    expect(writes()[0][1].body).toContain('"bsn_fee_amt":9007199254740993');
  });
});
